package com.example.beamapp;

import android.content.Context;
import android.util.Log;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.InputData;
import com.google.ai.edge.litertlm.ResponseCallback;
import com.google.ai.edge.litertlm.Session;
import com.google.ai.edge.litertlm.SessionConfig;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Java facade over Google's LiteRT-LM Kotlin library. Loads a .litertlm model
 * file (Gemma 4 etc. from HuggingFace litert-community) and runs blocking or
 * streaming text generation.
 *
 * Single-model semantics: only one engine loaded at a time. Generations
 * serialize via the `busy` flag, but the lock is NOT held during inference —
 * so cancel() and status() work mid-generation instead of blocking until it
 * finishes. load()/unload() cancel any in-flight generation and wait for it
 * to release the session before touching engine state.
 */
public class GemmaEngine {
    private static final String TAG = "GemmaEngine";

    private final Context context;
    private final Object lock = new Object();

    private Engine engine;
    private Session session;
    /* True while a generate/streamGenerate owns the session. Guarded by lock;
     * waiters are woken via lock.notifyAll(). */
    private boolean busy = false;
    /* Set by cancel() while busy, so a cancel that lands before the library
     * call actually starts is re-issued instead of silently lost. Guarded by
     * lock; cleared when a generation claims the session. */
    private boolean cancelRequested = false;
    private String loadedModelPath;
    private long loadedAt;

    /** Listener for streaming generation. Called on a background thread. */
    public interface StreamListener {
        /** Called for each partial chunk of generated text. */
        void onToken(String chunk);
        /** Called once generation is complete. */
        void onDone();
        /** Called if generation fails. */
        void onError(Throwable error);
    }

    public GemmaEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Load a .litertlm model from disk. Blocking — initialization can take
     * several seconds depending on the model size.
     *
     * @param modelPath absolute path to the .litertlm file
     * @throws IllegalArgumentException if the file doesn't exist or isn't readable
     * @throws RuntimeException if engine initialization fails
     */
    public void load(String modelPath) {
        load(modelPath, "cpu");
    }

    /**
     * "cpu" or "gpu". Thread counts are left to the runtime.
     *
     * litertlm 0.14.0 changed this API relative to 0.10.0: Backend.GpuArtisan
     * was removed outright, and Backend.CPU's single-Integer constructor became
     * CPU(Integer, Integer), so CPU(null) no longer resolves. Use the no-arg
     * forms, which exist in both.
     */
    private static Backend backendFor(String name) {
        if ("gpu".equalsIgnoreCase(name)) return new Backend.GPU();
        return new Backend.CPU();
    }

    /**
     * Paths where ARM's Mali userspace blob lives. On this SoC that blob
     * exports the full OpenCL API (clGetPlatformIDs, clCreateContext,
     * clEnqueueNDRangeKernel, clBuildProgram, clCommand*KHR, …) even though
     * the vendor ships no libOpenCL.so anywhere on the device.
     */
    private static final String[] MALI_BLOB_PATHS = {
        "/vendor/lib64/egl/libGLES_mali.so",
        "/vendor/lib64/libGLES_mali.so",
        "/system/vendor/lib64/egl/libGLES_mali.so",
    };

    private static boolean openClPreloadTried = false;
    private static String openClPreloadResult = "not attempted";

    /**
     * Pull OpenCL symbols into the process before LiteRT-LM probes for them.
     *
     * The GPU backend loads a model fine here but fails at generation with
     * "Can not find OpenCL library on this device", because it resolves
     * dlopen("libOpenCL.so") and MediaTek never created that name — the
     * implementation is inside the Mali GLES blob instead.
     *
     * Loading the blob by absolute path only helps if LiteRT falls back to
     * dlsym(RTLD_DEFAULT, ...) after its own dlopen fails. If it keeps a
     * private handle this will not be enough, and the fix becomes shipping a
     * copy named libOpenCL.so in jniLibs (so the app's own lib dir satisfies
     * the name lookup) or a root symlink at /vendor/lib64/libOpenCL.so.
     * Cheap to try first; the outcome is reported back in gemma_load's reply
     * either way, so a failure here is diagnostic rather than silent.
     */
    private static synchronized String preloadOpenCL() {
        if (openClPreloadTried) return openClPreloadResult;
        openClPreloadTried = true;
        for (String p : MALI_BLOB_PATHS) {
            if (!new File(p).exists()) continue;
            try {
                System.load(p);
                openClPreloadResult = "loaded " + p;
                Log.i(TAG, "OpenCL preload: " + openClPreloadResult);
                return openClPreloadResult;
            } catch (Throwable t) {
                openClPreloadResult = "failed " + p + ": " + t.getMessage();
                Log.w(TAG, "OpenCL preload: " + openClPreloadResult);
            }
        }
        if ("not attempted".equals(openClPreloadResult)) {
            openClPreloadResult = "no Mali blob found";
            Log.w(TAG, "OpenCL preload: " + openClPreloadResult);
        }
        return openClPreloadResult;
    }

    /** Outcome of the last OpenCL preload attempt, for reporting to callers. */
    public static String openClStatus() {
        return openClPreloadResult;
    }

    /**
     * Load a model on a named backend: "cpu" (default) or "gpu".
     *
     * Worth trying GPU: on the reference Android device the vendor benchmarks
     * put GPU at 0.3 s time-to-first-token and 676 MB, versus 1.8 s and
     * 1733 MB on CPU. The memory drop matters as much as the speed on a
     * memory-constrained handset. GPU availability depends on the SoC's
     * driver, so this stays selectable at call time rather than hardcoded —
     * if GPU init fails, the caller can retry with "cpu" without a rebuild.
     */
    public void load(String modelPath, String backendName) {
        synchronized (lock) {
            File f = new File(modelPath);
            if (!f.exists() || !f.canRead()) {
                throw new IllegalArgumentException(
                    "Model file not found or unreadable: " + modelPath);
            }

            /* Unload any existing engine first (cancels and waits out any
             * in-flight generation). */
            waitUntilIdleLocked();
            unloadLocked();

            File cacheDir = new File(context.getCacheDir(), "litertlm");
            if (!cacheDir.exists()) cacheDir.mkdirs();

            Log.i(TAG, "Loading model: " + modelPath);
            long startMs = System.currentTimeMillis();

            /* Per-modality backends. The model declares constraints per
             * modality and they are not uniform — with all three on GPU the
             * engine rejects audio ("Model requires one of [cpu] but Audio
             * backend is GPU"); with vision on CPU it rejects the vision
             * encoder ("must have exactly one signature but got 3"). Rather
             * than guess-and-rebuild, the spec is "text[,vision[,audio]]",
             * e.g. "gpu", "gpu,gpu,cpu", "cpu". Unspecified slots inherit the
             * text backend. */
            /* Spec is "text[,vision[,audio]]". An OMITTED slot is null, not a
             * copy of the text backend — null means "this model has no such
             * modality", which is what a text-only bundle needs. Naming an
             * audio backend for a text-only model makes the runtime look for
             * an encoder that isn't there:
             *   NOT_FOUND: TF_LITE_AUDIO_ENCODER_HW not found in the model.
             *
             * Only modelPath and the text backend are @NotNull on
             * EngineConfig; visionBackend, audioBackend, maxNumTokens,
             * maxNumImages and cacheDir are all @Nullable (verified against
             * the 0.14.0 class's parameter annotations). The original code
             * passed one backend instance to all three slots on the assumption
             * that they were required — they are not.
             *
             * So: "cpu" = text-only on CPU; "cpu,cpu,cpu" = full multimodal;
             * "gpu,gpu,cpu" = GPU text+vision with the CPU audio the model
             * demands. */
            String[] parts = backendName.split(",");
            String textName = parts.length > 0 && !parts[0].trim().isEmpty()
                ? parts[0].trim() : "cpu";

            /* Any GPU slot means LiteRT will go looking for OpenCL. */
            if (backendName.toLowerCase(Locale.US).contains("gpu")) {
                preloadOpenCL();
            }
            Backend visionBackend = parts.length > 1 ? backendFor(parts[1].trim()) : null;
            Backend audioBackend  = parts.length > 2 ? backendFor(parts[2].trim()) : null;
            Log.i(TAG, "Backends: text=" + textName
                     + " vision=" + (visionBackend == null ? "none" : parts[1].trim())
                     + " audio="  + (audioBackend  == null ? "none" : parts[2].trim()));
            /* litertlm 0.14.0 inserted maxNumImages before cacheDir:
             *   (modelPath, backend, visionBackend, audioBackend,
             *    maxNumTokens, maxNumImages, cacheDir)
             * Must be null or positive — 0 is rejected outright:
             *   "maxNumImages must be positive or null (use the default from
             *    model or engine)."
             * So there is no way to switch the vision encoder off from here;
             * null takes the model/engine default. */
            EngineConfig config = new EngineConfig(
                modelPath,
                backendFor(textName),   // @NotNull — the only required backend
                visionBackend,          // null = model has/needs no vision
                audioBackend,           // null = model has/needs no audio
                null,                   // maxNumTokens — library default
                null,                   // maxNumImages — model/engine default
                cacheDir.getAbsolutePath()
            );

            initEngine(config, modelPath, startMs);
        }
    }

    /** Build the engine + session from a prepared config. Caller holds the lock. */
    private void initEngine(EngineConfig config, String modelPath, long startMs) {
        try {
            Engine e = new Engine(config);
            e.initialize();
            Session s = e.createSession(new SessionConfig());
            this.engine = e;
            this.session = s;
            this.loadedModelPath = modelPath;
            this.loadedAt = System.currentTimeMillis();

            long elapsedMs = System.currentTimeMillis() - startMs;
            Log.i(TAG, "Loaded in " + elapsedMs + "ms: " + modelPath);
        } catch (Throwable t) {
            /* Best-effort cleanup on partial init. */
            closeQuietly();
            throw new RuntimeException(
                "LiteRT-LM load failed: " + t.getMessage(), t);
        }
    }

    /**
     * Wrap a bare prompt in the loaded model's chat template.
     *
     * An instruct model emits an immediate end-of-turn for un-templated input,
     * which is why raw prompts returned {"text":""} while still burning
     * seconds of compute. LiteRT-LM does not apply the template itself, even
     * though the bundle embeds one.
     *
     * Templates are per-family and share no syntax:
     *   Gemma 4  — "<|turn>user\n" … "<turn|>\n<|turn>model\n"
     *              (note: NOT Gemma 2/3's <start_of_turn>; the pipe flips
     *               sides on the closing marker)
     *   Granite  — "<|start_of_role|>user<|end_of_role|>" …
     *              "<|end_of_text|>\n<|start_of_role|>assistant<|end_of_role|>"
     *   Qwen3    — ChatML: "<|im_start|>user\n" … "<|im_end|>\n<|im_start|>assistant\n"
     *              followed by an EMPTY "<think>\n\n</think>\n\n" block.
     *
     * That empty think block is not cosmetic. Qwen3 reasons by default and
     * would spend its entire budget on chain-of-thought before answering —
     * fatal for a voice turn. Pre-filling a closed think block is what the
     * upstream template emits for enable_thinking=false.
     *
     * Selected from the loaded model's filename, defaulting to Gemma. A prompt
     * that already carries role markers is passed through untouched, so a
     * caller can build multi-turn context or use a family we don't know about.
     */
    private String applyChatTemplate(String prompt) {
        if (prompt.contains("<|turn>") || prompt.contains("<|start_of_role|>")
                || prompt.contains("<|im_start|>")) {
            return prompt;
        }
        String model = loadedModelPath == null
            ? "" : loadedModelPath.toLowerCase(Locale.US);
        if (model.contains("granite")) {
            return "<|start_of_role|>user<|end_of_role|>" + prompt
                 + "<|end_of_text|>\n<|start_of_role|>assistant<|end_of_role|>";
        }
        if (model.contains("qwen")) {
            return "<|im_start|>user\n" + prompt
                 + "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
        }
        return "<|turn>user\n" + prompt + "<turn|>\n<|turn>model\n";
    }

    /**
     * Generate a full response for the given prompt, blocking until complete.
     *
     * @throws IllegalStateException if no model is loaded
     */
    public String generate(String prompt) {
        Session s = acquireSessionForGeneration();
        try {
            synchronized (lock) {
                /* A cancel that landed between acquire and here has nothing
                 * to interrupt yet — honor it instead of losing it. */
                if (cancelRequested) {
                    throw new RuntimeException("generate() cancelled before start");
                }
            }
            List<InputData> inputs = Collections.<InputData>singletonList(
                new InputData.Text(applyChatTemplate(prompt)));
            try {
                return s.generateContent(inputs);
            } catch (Throwable t) {
                throw new RuntimeException(
                    "generate() failed: " + t.getMessage(), t);
            }
        } finally {
            releaseGeneration();
        }
    }

    /**
     * Stream a response token by token. Blocks the calling thread until
     * {@link StreamListener#onDone()} or {@link StreamListener#onError(Throwable)}
     * has fired, so the caller can use the result synchronously.
     *
     * @throws IllegalStateException if no model is loaded
     */
    public void streamGenerate(String prompt, final StreamListener listener) {
        Session s = acquireSessionForGeneration();
        try {
            List<InputData> inputs = Collections.<InputData>singletonList(
                new InputData.Text(applyChatTemplate(prompt)));

            final CountDownLatch done = new CountDownLatch(1);
            final AtomicReference<Throwable> errorRef = new AtomicReference<Throwable>();

            boolean streamStarted = false;
            try {
                s.generateContentStream(inputs, new ResponseCallback() {
                    @Override
                    public void onNext(String chunk) {
                        try {
                            listener.onToken(chunk);
                        } catch (Throwable t) {
                            Log.w(TAG, "listener.onToken threw", t);
                        }
                    }

                    @Override
                    public void onDone() {
                        done.countDown();
                    }

                    @Override
                    public void onError(Throwable error) {
                        errorRef.set(error);
                        done.countDown();
                    }
                });
                streamStarted = true;

                /* A cancel() that landed before the stream actually started
                 * hit an idle session and did nothing — re-issue it now that
                 * there is something to cancel. */
                boolean reCancel;
                synchronized (lock) {
                    reCancel = cancelRequested;
                }
                if (reCancel) {
                    try { s.cancelProcess(); } catch (Throwable ignore) { }
                }
            } catch (Throwable t) {
                errorRef.compareAndSet(null, t);
            }

            /* Await the terminal callback uninterruptibly: releasing busy
             * while the native stream still runs would let a second
             * generation start concurrently. An interrupt is converted into
             * a cancel, and the wait continues until the stream terminates. */
            if (streamStarted) {
                boolean interrupted = false;
                for (;;) {
                    try {
                        done.await();
                        break;
                    } catch (InterruptedException ie) {
                        interrupted = true;
                        try { s.cancelProcess(); } catch (Throwable ignore) { }
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }

            Throwable err = errorRef.get();
            if (err != null) {
                try {
                    listener.onError(err);
                } catch (Throwable ignore) { /* ignore listener errors */ }
            } else {
                try {
                    listener.onDone();
                } catch (Throwable ignore) { /* ignore */ }
            }
        } finally {
            releaseGeneration();
        }
    }

    /** Cancel an in-progress streaming request. Safe to call concurrently.
     *  Also flags the request so a cancel that races the library-call start
     *  is re-issued by the generating thread instead of being lost. */
    public void cancel() {
        Session s;
        synchronized (lock) {
            if (busy) {
                cancelRequested = true;
                s = session;
            } else {
                s = null;
            }
        }
        if (s != null) {
            try {
                s.cancelProcess();
            } catch (Throwable t) {
                Log.w(TAG, "cancel() failed", t);
            }
        }
    }

    /** Release model and engine resources. Idempotent. Cancels and waits out
     *  any in-flight generation first. */
    public void unload() {
        synchronized (lock) {
            waitUntilIdleLocked();
            unloadLocked();
        }
    }

    public boolean isLoaded() {
        synchronized (lock) {
            return engine != null && session != null;
        }
    }

    /** Simple status snapshot. Returns null fields if not loaded. */
    public Status status() {
        synchronized (lock) {
            return new Status(loadedModelPath, loadedAt, isLoaded());
        }
    }

    public static final class Status {
        public final String modelPath;
        public final long loadedAt;
        public final boolean loaded;

        Status(String modelPath, long loadedAt, boolean loaded) {
            this.modelPath = modelPath;
            this.loadedAt  = loadedAt;
            this.loaded    = loaded;
        }
    }

    // --- internals ---

    /**
     * Claim the session for one generation: waits for any in-flight
     * generation to finish, marks busy, and returns the session to use.
     * The caller MUST pair this with releaseGeneration() in a finally block,
     * and must run the actual inference outside the lock so cancel() can
     * interleave.
     *
     * @throws IllegalStateException if no model is loaded (checked again
     *         after waiting, since unload() may have run meanwhile)
     */
    private Session acquireSessionForGeneration() {
        synchronized (lock) {
            requireLoaded();
            boolean interrupted = false;
            while (busy) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            requireLoaded();
            busy = true;
            cancelRequested = false;
            return session;
        }
    }

    private void releaseGeneration() {
        synchronized (lock) {
            busy = false;
            lock.notifyAll();
        }
    }

    /**
     * Cancel any in-flight generation and wait until it releases the
     * session. Must be called with the lock held. The generation thread can
     * finish because inference runs outside the lock; re-cancels on each
     * wakeup in case the first cancelProcess() raced the stream start.
     */
    private void waitUntilIdleLocked() {
        boolean interrupted = false;
        int rounds = 0;
        while (busy) {
            if (session != null) {
                try {
                    session.cancelProcess();
                } catch (Throwable t) { /* ignore */ }
            }
            try {
                /* Do NOT re-assert the interrupt inside the loop: wait()
                 * with the flag set throws immediately, which would spin
                 * with the lock held and starve releaseGeneration(). */
                lock.wait(500);
            } catch (InterruptedException e) {
                interrupted = true;
            }
            if (++rounds % 20 == 0) {
                Log.w(TAG, "still waiting for in-flight generation to stop ("
                    + (rounds / 2) + "s) — cancelProcess() may not be honored");
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void requireLoaded() {
        if (engine == null || session == null) {
            throw new IllegalStateException(
                "No model loaded — call gemma_load first");
        }
    }

    private void unloadLocked() {
        closeQuietly();
        this.engine = null;
        this.session = null;
        this.loadedModelPath = null;
        this.loadedAt = 0;
    }

    private void closeQuietly() {
        if (session != null) {
            try { session.close(); } catch (Throwable t) { /* ignore */ }
        }
        if (engine != null) {
            try { engine.close(); } catch (Throwable t) { /* ignore */ }
        }
    }
}
