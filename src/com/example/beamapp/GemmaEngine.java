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
 * Single-model semantics: only one engine loaded at a time. Thread-safe via
 * instance lock — all operations serialize.
 */
public class GemmaEngine {
    private static final String TAG = "GemmaEngine";

    private final Context context;
    private final Object lock = new Object();

    private Engine engine;
    private Session session;
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

            /* Unload any existing engine first. */
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
        synchronized (lock) {
            requireLoaded();
            List<InputData> inputs = Collections.<InputData>singletonList(
                new InputData.Text(prompt));
            try {
                return session.generateContent(inputs);
            } catch (Throwable t) {
                throw new RuntimeException(
                    "generate() failed: " + t.getMessage(), t);
            }
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
        synchronized (lock) {
            requireLoaded();
            List<InputData> inputs = Collections.<InputData>singletonList(
                new InputData.Text(prompt));

            final CountDownLatch done = new CountDownLatch(1);
            final AtomicReference<Throwable> errorRef = new AtomicReference<Throwable>();

            try {
                session.generateContentStream(inputs, new ResponseCallback() {
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
                done.await();
            } catch (Throwable t) {
                errorRef.compareAndSet(null, t);
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
        }
    }

    /** Cancel an in-progress streaming request. Safe to call concurrently. */
    public void cancel() {
        Session s;
        synchronized (lock) {
            s = session;
        }
        if (s != null) {
            try {
                s.cancelProcess();
            } catch (Throwable t) {
                Log.w(TAG, "cancel() failed", t);
            }
        }
    }

    /** Release model and engine resources. Idempotent. */
    public void unload() {
        synchronized (lock) {
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
