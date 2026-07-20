package com.example.beamapp;

import android.content.Context;
import android.media.AudioRecord;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Speaker enrollment + verification using the on-device speaker embedding
 * model that already ships for diarization. This is the phone-side enabler
 * for VOICE-21 (privileged voice actions gate on speaker verification;
 * failure degrades to screen confirmation, never silent acceptance).
 *
 * Enrollment embeddings are stored as float32 LE files, one per take, under
 * files/speakers/<name>/<n>.emb. Repeated enrollments add takes; a
 * speaker's reference embedding is the centroid (mean) of their takes,
 * which is noticeably more robust than a single take. Verification computes
 * cosine similarity between a fresh utterance embedding and each enrolled
 * centroid. (Legacy flat <name>.emb files are migrated into the per-speaker
 * directory on the next enrollment and read as a single take until then.)
 *
 * Lazily initialized and degrades to a structured error if the bundled
 * sherpa-onnx JNI lacks the extractor symbols (catches Throwable, including
 * UnsatisfiedLinkError).
 */
public class SpeakerId {
    private static final String TAG = "SpeakerId";
    private static final int SAMPLE_RATE = 16000;

    /** Cosine similarity threshold for a positive verification. */
    public static final float DEFAULT_THRESHOLD = 0.60f;

    private final SpeechEngine speechEngine;
    private final File speakerDir;

    private SpeakerEmbeddingExtractor extractor;
    private boolean initFailed = false;

    public SpeakerId(Context context, SpeechEngine speechEngine) {
        this.speechEngine = speechEngine;
        this.speakerDir = new File(context.getFilesDir(), "speakers");
    }

    /** Lazy init; returns an error message or null on success. */
    private synchronized String ensureExtractor() {
        if (extractor != null) return null;
        if (initFailed) return "speaker embedding extractor unavailable";
        String modelPath = speechEngine.getEmbeddingModelPath();
        if (modelPath == null || !new File(modelPath).exists()) {
            return "embedding model not found (diarization assets missing)";
        }
        try {
            long t = System.currentTimeMillis();
            SpeakerEmbeddingExtractorConfig config = SpeakerEmbeddingExtractorConfig.builder()
                .setModel(modelPath)
                .setNumThreads(1)
                .setDebug(false)
                .build();
            extractor = new SpeakerEmbeddingExtractor(config);
            Log.i(TAG, "Speaker embedding extractor loaded in "
                + (System.currentTimeMillis() - t) + "ms dim=" + extractor.getDim());
            return null;
        } catch (Throwable t) { // UnsatisfiedLinkError if JNI lacks symbols
            Log.e(TAG, "Speaker extractor init failed", t);
            initFailed = true;
            return "speaker extractor init failed: " + t.getMessage();
        }
    }

    /**
     * Enroll a speaker take. Uses `wavPath` when given, otherwise records
     * `seconds` from the mic (via the mic arbiter). Repeated enrollments
     * add takes; verification uses the centroid of all takes.
     */
    public String enroll(String name, String wavPath, int seconds) {
        if (name == null || name.isEmpty()) return "{\"error\":\"usage: speaker_enroll <name> [wav_path]\"}";
        String err = ensureExtractor();
        if (err != null) return "{\"error\":\"" + esc(err) + "\"}";
        try {
            float[] samples = (wavPath != null)
                ? speechEngine.readWavSamples(wavPath)
                : recordSamples(seconds, "speaker_enroll");
            if (samples == null || samples.length < SAMPLE_RATE / 2) {
                return "{\"error\":\"not enough audio for enrollment (need >0.5s)\"}";
            }
            float[] emb = embed(samples);
            if (emb == null || emb.length == 0) {
                return "{\"error\":\"embedding failed (audio too short?)\"}";
            }
            int takes = saveTake(name, emb);
            return "{\"status\":\"enrolled\",\"name\":\"" + esc(name)
                + "\",\"takes\":" + takes
                + ",\"dim\":" + emb.length
                + ",\"audio_sec\":" + fmt(samples.length / (float) SAMPLE_RATE) + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /**
     * Verify a speaker. Compares a fresh utterance (recorded `seconds`, or
     * `wavPath` when given) against one enrolled speaker (`name` non-null)
     * or all enrolled speakers (best match). Returns name, cosine score,
     * and verified = score >= threshold.
     */
    public String verify(String name, String wavPath, int seconds, float threshold) {
        String err = ensureExtractor();
        if (err != null) return "{\"error\":\"" + esc(err) + "\"}";

        Map<String, java.util.List<float[]>> enrolled = loadAllTakes(name);
        if (enrolled.isEmpty()) {
            return (name != null)
                ? "{\"error\":\"no enrollment for '" + esc(name) + "'\"}"
                : "{\"error\":\"no enrolled speakers\"}";
        }

        try {
            float[] samples = (wavPath != null)
                ? speechEngine.readWavSamples(wavPath)
                : recordSamples(seconds, "speaker_verify");
            if (samples == null || samples.length < SAMPLE_RATE / 2) {
                return "{\"error\":\"not enough audio for verification (need >0.5s)\"}";
            }
            float[] emb = embed(samples);
            if (emb == null || emb.length == 0) {
                return "{\"error\":\"embedding failed (audio too short?)\"}";
            }

            String best = null;
            float bestScore = -1.0f;
            int bestTakes = 0;
            for (Map.Entry<String, java.util.List<float[]>> e : enrolled.entrySet()) {
                float score = cosine(emb, centroid(e.getValue()));
                if (score > bestScore) {
                    bestScore = score;
                    best = e.getKey();
                    bestTakes = e.getValue().size();
                }
            }
            boolean verified = bestScore >= threshold;
            return "{\"name\":\"" + esc(best)
                + "\",\"score\":" + fmt(bestScore)
                + ",\"verified\":" + verified
                + ",\"threshold\":" + fmt(threshold)
                + ",\"takes\":" + bestTakes
                + ",\"audio_sec\":" + fmt(samples.length / (float) SAMPLE_RATE) + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /** List enrolled speakers with their take counts. */
    public String list() {
        Map<String, java.util.List<float[]>> all = loadAllTakes(null);
        StringBuilder sb = new StringBuilder("{\"speakers\":[");
        boolean first = true;
        for (Map.Entry<String, java.util.List<float[]>> e : all.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"name\":\"").append(esc(e.getKey()))
              .append("\",\"takes\":").append(e.getValue().size())
              .append(",\"dim\":").append(e.getValue().get(0).length).append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Remove a speaker's enrollment (all takes). */
    public String forget(String name) {
        if (name == null || name.isEmpty()) return "{\"error\":\"usage: speaker_forget <name>\"}";
        String safe = sanitize(name);
        File dir = new File(speakerDir, safe);
        File legacy = new File(speakerDir, safe + ".emb");
        if (!dir.exists() && !legacy.exists()) {
            return "{\"error\":\"no enrollment for '" + esc(name) + "'\"}";
        }
        boolean ok = true;
        if (dir.isDirectory()) {
            File[] takes = dir.listFiles();
            if (takes != null) {
                for (File t : takes) ok &= t.delete();
            }
            ok &= dir.delete();
        }
        if (legacy.exists()) ok &= legacy.delete();
        return ok ? "{\"status\":\"forgotten\",\"name\":\"" + esc(name) + "\"}"
                  : "{\"error\":\"failed to delete enrollment\"}";
    }

    // ==== Internals ====

    private float[] embed(float[] samples) {
        OnlineStream stream = extractor.createStream();
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE);
            stream.inputFinished();
            if (!extractor.isReady(stream)) return null;
            return extractor.compute(stream);
        } finally {
            stream.release();
        }
    }

    /** Record from the mic through the shared arbiter + wake lock. */
    private float[] recordSamples(int seconds, String claimTag) throws Exception {
        String busy = speechEngine.claimMic(claimTag);
        if (busy != null) throw new Exception("mic busy (" + busy + ")");
        AudioRecord mic = null;
        speechEngine.acquireListenLock();
        try {
            mic = speechEngine.openMic();
            if (mic == null) throw new Exception("mic not available");
            mic.startRecording();
            int total = SAMPLE_RATE * seconds;
            float[] out = new float[total];
            short[] buf = new short[SAMPLE_RATE / 10];
            int pos = 0;
            while (pos < total) {
                int read = mic.read(buf, 0, Math.min(buf.length, total - pos));
                if (read <= 0) break;
                for (int i = 0; i < read; i++) out[pos + i] = buf[i] / 32768.0f;
                pos += read;
            }
            return (pos == total) ? out : Arrays.copyOf(out, pos);
        } finally {
            if (mic != null) {
                try { mic.stop(); } catch (Exception ignored) {}
                mic.release();
            }
            speechEngine.releaseMic(claimTag);
            speechEngine.releaseListenLock();
        }
    }

    /** Sanitize a speaker name into a safe flat filename component. */
    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /**
     * Save one enrollment take under speakers/<name>/<n>.emb, migrating a
     * legacy flat <name>.emb into the directory first. Returns the total
     * take count for the speaker.
     */
    private int saveTake(String name, float[] emb) throws Exception {
        String safe = sanitize(name);
        File dir = new File(speakerDir, safe);
        dir.mkdirs();
        File legacy = new File(speakerDir, safe + ".emb");
        if (legacy.exists() && !legacy.renameTo(new File(dir, "1.emb"))) {
            Log.w(TAG, "Legacy enrollment migration failed for " + safe);
        }
        int next = 1;
        int count = 0;
        File[] existing = dir.listFiles();
        if (existing != null) {
            for (File t : existing) {
                if (!t.getName().endsWith(".emb")) continue;
                count++;
                try {
                    int idx = Integer.parseInt(
                        t.getName().substring(0, t.getName().length() - 4));
                    if (idx >= next) next = idx + 1;
                } catch (Exception ignored) { }
            }
        }
        writeEmb(new File(dir, next + ".emb"), emb);
        return count + 1;
    }

    private static void writeEmb(File f, float[] emb) throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(emb.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : emb) bb.putFloat(v);
        DataOutputStream out = new DataOutputStream(new FileOutputStream(f));
        out.write(bb.array());
        out.close();
    }

    private static float[] readEmb(File f) {
        try {
            byte[] raw = new byte[(int) f.length()];
            DataInputStream in = new DataInputStream(new FileInputStream(f));
            in.readFully(raw);
            in.close();
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            float[] emb = new float[raw.length / 4];
            for (int i = 0; i < emb.length; i++) emb[i] = bb.getFloat();
            return emb.length > 0 ? emb : null;
        } catch (Exception e) {
            Log.w(TAG, "Failed to load embedding " + f.getName(), e);
            return null;
        }
    }

    /**
     * Load all takes for one speaker (only != null) or every speaker.
     * Reads both per-speaker directories and legacy flat .emb files.
     */
    private Map<String, java.util.List<float[]>> loadAllTakes(String only) {
        Map<String, java.util.List<float[]>> result = new HashMap<>();
        String onlySafe = (only != null) ? sanitize(only) : null;
        File[] entries = speakerDir.listFiles();
        if (entries == null) return result;
        for (File e : entries) {
            String name;
            if (e.isDirectory()) {
                name = e.getName();
            } else if (e.getName().endsWith(".emb")) {
                name = e.getName().substring(0, e.getName().length() - 4);
            } else {
                continue;
            }
            if (onlySafe != null && !name.equals(onlySafe)) continue;
            java.util.List<float[]> takes =
                result.containsKey(name) ? result.get(name) : new java.util.ArrayList<float[]>();
            if (e.isDirectory()) {
                File[] tf = e.listFiles();
                if (tf != null) {
                    for (File t : tf) {
                        if (!t.getName().endsWith(".emb")) continue;
                        float[] emb = readEmb(t);
                        if (emb != null) takes.add(emb);
                    }
                }
            } else {
                float[] emb = readEmb(e);
                if (emb != null) takes.add(emb);
            }
            if (!takes.isEmpty()) result.put(name, takes);
        }
        return result;
    }

    /** Mean of all takes (the speaker's reference embedding). */
    private static float[] centroid(java.util.List<float[]> takes) {
        if (takes.size() == 1) return takes.get(0);
        int dim = takes.get(0).length;
        float[] c = new float[dim];
        int n = 0;
        for (float[] t : takes) {
            if (t.length != dim) continue;
            for (int i = 0; i < dim; i++) c[i] += t[i];
            n++;
        }
        if (n > 0) {
            for (int i = 0; i < dim; i++) c[i] /= n;
        }
        return c;
    }

    private static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return -1.0f;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return -1.0f;
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }

    private static String fmt(float v) {
        return String.format(Locale.US, "%.3f", v);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    public void release() {
        if (extractor != null) {
            extractor.release();
            extractor = null;
        }
    }
}
