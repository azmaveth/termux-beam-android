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

            /* Text-only on CPU with default thread count. Vision/audio backends
             * must still be non-null — pass the same CPU instance. We leave
             * maxNumTokens as null to use the runtime default. */
            Backend cpu = new Backend.CPU(null);
            EngineConfig config = new EngineConfig(
                modelPath,
                cpu,                    // text backend
                cpu,                    // vision backend (unused for Gemma text)
                cpu,                    // audio backend (unused)
                null,                   // maxNumTokens — library default
                cacheDir.getAbsolutePath()
            );

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
                new InputData.Text(prompt));
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
                new InputData.Text(prompt));

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
