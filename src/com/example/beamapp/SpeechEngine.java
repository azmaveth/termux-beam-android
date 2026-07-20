package com.example.beamapp;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.PowerManager;
import android.util.Log;

import com.k2fsa.sherpa.onnx.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unified speech engine using sherpa-onnx JNI for STT, TTS, VAD,
 * punctuation restoration, offline STT (SenseVoice), and speaker diarization.
 * Models are loaded once and reused across calls.
 */
public class SpeechEngine {
    private static final String TAG = "SpeechEngine";
    private static final int SAMPLE_RATE = 16000;

    private final Context context;
    private final String nativeLibDir;
    private final String filesDir;

    // Streaming STT
    private OnlineRecognizer recognizer;
    private String sttModelDir;

    // VAD
    private Vad vad;
    private String vadModelPath;

    // TTS
    private OfflineTts tts;
    private String ttsModelDir;

    // Online punctuation (post-processor for streaming STT)
    private OnlinePunctuation punctuation;
    private String punctModelDir;

    // Offline STT (SenseVoice — with punctuation, casing, emotion, language ID)
    private OfflineRecognizer offlineRecognizer;
    private String senseVoiceModelDir;

    // Speaker diarization
    private OfflineSpeakerDiarization diarizer;
    private String segmentationModelDir;
    private String embeddingModelPath;

    // Streaming listen state
    private final AtomicBoolean listening = new AtomicBoolean(false);
    private volatile String partialResult = "";

    // TTS playback state (streamed, cancelable)
    private final Object ttsPlaybackLock = new Object();
    private volatile AudioTrack ttsTrack;
    private final AtomicBoolean ttsCancel = new AtomicBoolean(false);

    // Mic arbiter: one owner at a time so concurrent captures fail with a
    // descriptive "mic busy (<holder>)" instead of a cryptic init error.
    private final Object micLock = new Object();
    private String micHolder = null;

    // CPU wake lock held while capturing audio so Doze doesn't starve
    // long-running listens. Reference-counted; 30 min safety timeout.
    private PowerManager.WakeLock listenWakeLock;

    // Pre-recording mic buffer to capture audio before stream_listen dispatch
    private AudioRecord preMic;
    private float[] preBuffer;       // ring buffer of float samples
    private int preBufferPos;        // write position in ring buffer
    private int preBufferFilled;     // how many samples have been written total
    private volatile boolean preRecording;
    private Thread preRecordThread;

    public SpeechEngine(Context context) {
        this.context = context;
        this.nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        this.filesDir = context.getFilesDir().getAbsolutePath();
    }

    /** Initialize all speech engines. Call from a background thread. */
    public void init() {
        long t0 = System.currentTimeMillis();
        try {
            LibraryUtils.setNativeLibDir(nativeLibDir);
            LibraryUtils.load();
            Log.i(TAG, "JNI libraries loaded in " + (System.currentTimeMillis() - t0) + "ms");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load JNI libraries", e);
            return;
        }

        extractModels();
        initStt();
        initVad();
        initTts();
        initPunctuation();
        initOfflineStt();
        initDiarization();

        Log.i(TAG, "SpeechEngine fully initialized in " + (System.currentTimeMillis() - t0) + "ms");
    }

    private void extractModels() {
        try {
            // Extract streaming STT model
            sttModelDir = filesDir + "/stt-model";
            File sttDir = new File(sttModelDir);
            File sttMarker = new File(sttDir, ".extracted_v3");
            if (!sttMarker.exists()) {
                sttDir.mkdirs();
                new File(sttDir, ".extracted").delete();
                new File(sttDir, ".extracted_v2").delete();
                String[] sttFiles = {
                    "encoder-epoch-99-avg-1.int8.onnx",
                    "decoder-epoch-99-avg-1.int8.onnx",
                    "joiner-epoch-99-avg-1.int8.onnx",
                    "tokens.txt",
                    "silero_vad.onnx"
                };
                extractAssetDir("stt-model", sttDir, sttFiles);
                new FileOutputStream(sttMarker).close();
                Log.i(TAG, "STT model extracted");
            }
            vadModelPath = sttModelDir + "/silero_vad.onnx";

            // Extract TTS model
            ttsModelDir = filesDir + "/tts-model";
            File ttsDir = new File(ttsModelDir);
            File ttsMarker = new File(ttsDir, ".extracted_v2");
            if (!ttsMarker.exists()) {
                ttsDir.mkdirs();
                String[] ttsFiles = context.getAssets().list("tts-model");
                if (ttsFiles != null && ttsFiles.length > 0) {
                    extractAssetDirRecursive("tts-model", ttsDir);
                    new FileOutputStream(ttsMarker).close();
                    Log.i(TAG, "TTS model extracted");
                } else {
                    Log.w(TAG, "No TTS model files in assets");
                    ttsModelDir = null;
                }
            }

            // Extract punctuation model
            punctModelDir = filesDir + "/punct-model";
            File punctDir = new File(punctModelDir);
            File punctMarker = new File(punctDir, ".extracted_v1");
            if (!punctMarker.exists()) {
                punctDir.mkdirs();
                String[] punctFiles = context.getAssets().list("punct-model");
                if (punctFiles != null && punctFiles.length > 0) {
                    extractAssetDir("punct-model", punctDir, punctFiles);
                    new FileOutputStream(punctMarker).close();
                    Log.i(TAG, "Punctuation model extracted");
                } else {
                    Log.w(TAG, "No punctuation model in assets");
                    punctModelDir = null;
                }
            }

            // Extract SenseVoice offline STT model
            senseVoiceModelDir = filesDir + "/sensevoice-model";
            File svDir = new File(senseVoiceModelDir);
            File svMarker = new File(svDir, ".extracted_v1");
            if (!svMarker.exists()) {
                svDir.mkdirs();
                String[] svFiles = context.getAssets().list("sensevoice-model");
                if (svFiles != null && svFiles.length > 0) {
                    extractAssetDir("sensevoice-model", svDir, svFiles);
                    new FileOutputStream(svMarker).close();
                    Log.i(TAG, "SenseVoice model extracted");
                } else {
                    Log.w(TAG, "No SenseVoice model in assets");
                    senseVoiceModelDir = null;
                }
            }

            // Extract diarization models
            segmentationModelDir = filesDir + "/diarization";
            File diarDir = new File(segmentationModelDir);
            File diarMarker = new File(diarDir, ".extracted_v1");
            if (!diarMarker.exists()) {
                diarDir.mkdirs();
                String[] diarFiles = context.getAssets().list("diarization");
                if (diarFiles != null && diarFiles.length > 0) {
                    extractAssetDir("diarization", diarDir, diarFiles);
                    new FileOutputStream(diarMarker).close();
                    Log.i(TAG, "Diarization models extracted");
                } else {
                    Log.w(TAG, "No diarization models in assets");
                    segmentationModelDir = null;
                }
            }
            if (segmentationModelDir != null) {
                embeddingModelPath = segmentationModelDir + "/embedding.onnx";
            }
        } catch (Exception e) {
            Log.e(TAG, "Model extraction failed", e);
        }
    }

    private void extractAssetDir(String assetPrefix, File outDir, String[] files) throws Exception {
        android.content.res.AssetManager am = context.getAssets();
        for (String name : files) {
            Log.i(TAG, "Extracting: " + assetPrefix + "/" + name);
            java.io.InputStream in = am.open(assetPrefix + "/" + name);
            FileOutputStream fos = new FileOutputStream(new File(outDir, name));
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) > 0) fos.write(buf, 0, len);
            fos.close();
            in.close();
        }
    }

    private void extractAssetDirRecursive(String assetPath, File outDir) throws Exception {
        android.content.res.AssetManager am = context.getAssets();
        String[] entries = am.list(assetPath);
        if (entries == null) return;
        outDir.mkdirs();
        for (String entry : entries) {
            String fullPath = assetPath + "/" + entry;
            String[] children = am.list(fullPath);
            if (children != null && children.length > 0) {
                extractAssetDirRecursive(fullPath, new File(outDir, entry));
            } else {
                java.io.InputStream in = am.open(fullPath);
                FileOutputStream fos = new FileOutputStream(new File(outDir, entry));
                byte[] buf = new byte[65536];
                int len;
                while ((len = in.read(buf)) > 0) fos.write(buf, 0, len);
                fos.close();
                in.close();
            }
        }
    }

    private void initStt() {
        if (sttModelDir == null) return;
        try {
            long t = System.currentTimeMillis();
            OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                .setEncoder(sttModelDir + "/encoder-epoch-99-avg-1.int8.onnx")
                .setDecoder(sttModelDir + "/decoder-epoch-99-avg-1.int8.onnx")
                .setJoiner(sttModelDir + "/joiner-epoch-99-avg-1.int8.onnx")
                .build();

            OnlineModelConfig modelConfig = OnlineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(sttModelDir + "/tokens.txt")
                .setNumThreads(2)
                .setDebug(false)
                .build();

            OnlineRecognizerConfig config = OnlineRecognizerConfig.builder()
                .setOnlineModelConfig(modelConfig)
                .setEnableEndpoint(true)
                .build();

            recognizer = new OnlineRecognizer(config);
            Log.i(TAG, "STT recognizer loaded in " + (System.currentTimeMillis() - t) + "ms");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize STT recognizer", e);
            recognizer = null;
        }
    }

    private void initVad() {
        if (vadModelPath == null || !new File(vadModelPath).exists()) return;
        try {
            long t = System.currentTimeMillis();
            SileroVadModelConfig silero = SileroVadModelConfig.builder()
                .setModel(vadModelPath)
                .setThreshold(0.5f)
                .setMinSilenceDuration(0.5f)
                .setMinSpeechDuration(0.25f)
                .setWindowSize(512)
                .build();

            VadModelConfig vadConfig = VadModelConfig.builder()
                .setSileroVadModelConfig(silero)
                .setSampleRate(SAMPLE_RATE)
                .setNumThreads(1)
                .setDebug(false)
                .build();

            vad = new Vad(vadConfig);
            Log.i(TAG, "VAD loaded in " + (System.currentTimeMillis() - t) + "ms");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize VAD", e);
            vad = null;
        }
    }

    private void initTts() {
        if (ttsModelDir == null) return;
        try {
            long t = System.currentTimeMillis();
            File vitsModel = new File(ttsModelDir, "model.onnx");
            if (!vitsModel.exists()) {
                Log.w(TAG, "No TTS model.onnx found in " + ttsModelDir);
                return;
            }

            OfflineTtsVitsModelConfig vits = OfflineTtsVitsModelConfig.builder()
                .setModel(vitsModel.getAbsolutePath())
                .setTokens(ttsModelDir + "/tokens.txt")
                .setDataDir(ttsModelDir + "/espeak-ng-data")
                .setLengthScale(1.0f)
                .build();

            OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                .setVits(vits)
                .setNumThreads(4)
                .setDebug(false)
                .build();

            OfflineTtsConfig config = OfflineTtsConfig.builder()
                .setModel(modelConfig)
                .build();

            tts = new OfflineTts(config);
            Log.i(TAG, "TTS loaded in " + (System.currentTimeMillis() - t) + "ms"
                + " sampleRate=" + tts.getSampleRate()
                + " speakers=" + tts.getNumSpeakers());
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize TTS", e);
            tts = null;
        }
    }

    private void initPunctuation() {
        if (punctModelDir == null) return;
        File modelFile = new File(punctModelDir, "model.int8.onnx");
        File vocabFile = new File(punctModelDir, "bpe.vocab");
        if (!modelFile.exists() || !vocabFile.exists()) {
            Log.w(TAG, "Punctuation model files not found");
            return;
        }
        try {
            long t = System.currentTimeMillis();
            OnlinePunctuationModelConfig modelConfig = OnlinePunctuationModelConfig.builder()
                .setCnnBilstm(modelFile.getAbsolutePath())
                .setBpeVocab(vocabFile.getAbsolutePath())
                .setNumThreads(1)
                .setDebug(false)
                .build();

            OnlinePunctuationConfig config = OnlinePunctuationConfig.builder()
                .setModel(modelConfig)
                .build();

            punctuation = new OnlinePunctuation(config);
            Log.i(TAG, "Punctuation model loaded in " + (System.currentTimeMillis() - t) + "ms");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize punctuation", e);
            punctuation = null;
        }
    }

    private void initOfflineStt() {
        if (senseVoiceModelDir == null) return;
        File modelFile = new File(senseVoiceModelDir, "model.int8.onnx");
        File tokensFile = new File(senseVoiceModelDir, "tokens.txt");
        if (!modelFile.exists() || !tokensFile.exists()) {
            Log.w(TAG, "SenseVoice model files not found");
            return;
        }
        try {
            long t = System.currentTimeMillis();
            OfflineSenseVoiceModelConfig senseVoice = OfflineSenseVoiceModelConfig.builder()
                .setModel(modelFile.getAbsolutePath())
                .setLanguage("en")
                .setInverseTextNormalization(true)
                .build();

            OfflineModelConfig modelConfig = OfflineModelConfig.builder()
                .setSenseVoice(senseVoice)
                .setTokens(tokensFile.getAbsolutePath())
                .setNumThreads(2)
                .setDebug(false)
                .build();

            OfflineRecognizerConfig config = OfflineRecognizerConfig.builder()
                .setOfflineModelConfig(modelConfig)
                .build();

            offlineRecognizer = new OfflineRecognizer(config);
            Log.i(TAG, "SenseVoice offline STT loaded in " + (System.currentTimeMillis() - t) + "ms");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize offline STT", e);
            offlineRecognizer = null;
        }
    }

    private void initDiarization() {
        if (segmentationModelDir == null) return;
        File segModel = new File(segmentationModelDir, "segmentation.onnx");
        File embModel = new File(segmentationModelDir, "embedding.onnx");
        if (!segModel.exists() || !embModel.exists()) {
            Log.w(TAG, "Diarization model files not found");
            return;
        }
        try {
            long t = System.currentTimeMillis();
            OfflineSpeakerSegmentationPyannoteModelConfig pyannote =
                OfflineSpeakerSegmentationPyannoteModelConfig.builder()
                    .setModel(segModel.getAbsolutePath())
                    .build();

            OfflineSpeakerSegmentationModelConfig segConfig =
                OfflineSpeakerSegmentationModelConfig.builder()
                    .setPyannote(pyannote)
                    .setNumThreads(1)
                    .setDebug(false)
                    .build();

            SpeakerEmbeddingExtractorConfig embConfig =
                SpeakerEmbeddingExtractorConfig.builder()
                    .setModel(embModel.getAbsolutePath())
                    .setNumThreads(1)
                    .setDebug(false)
                    .build();

            FastClusteringConfig clustering = FastClusteringConfig.builder()
                .setNumClusters(-1)
                .setThreshold(0.5f)
                .build();

            OfflineSpeakerDiarizationConfig config = OfflineSpeakerDiarizationConfig.builder()
                .setSegmentation(segConfig)
                .setEmbedding(embConfig)
                .setClustering(clustering)
                .setMinDurationOn(0.3f)
                .setMinDurationOff(0.5f)
                .build();

            diarizer = new OfflineSpeakerDiarization(config);
            Log.i(TAG, "Diarization loaded in " + (System.currentTimeMillis() - t) + "ms"
                + " sampleRate=" + diarizer.getSampleRate());
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize diarization", e);
            diarizer = null;
        }
    }

    /** Apply punctuation/casing to raw STT text, if punctuation model is loaded. */
    public String addPunctuation(String text) {
        if (punctuation == null || text == null || text.isEmpty()) return text;
        try {
            // The punctuation model expects lowercase input; zipformer outputs UPPERCASE
            return punctuation.addPunctuation(text.toLowerCase().trim());
        } catch (Exception e) {
            Log.w(TAG, "Punctuation failed, returning raw text", e);
            return text;
        }
    }

    public int getTtsNumSpeakers() { return tts != null ? tts.getNumSpeakers() : 0; }

    /** Speak text via local TTS engine, playing through AudioTrack. Returns when done. */
    public String speak(String text) { return speak(text, 5, 1.0f); }

    /** Generate TTS audio and save to a WAV file without playing. For evals/testing. */
    public String speakToFile(String text, String wavPath, int speakerId, float speed) {
        if (tts == null) return "{\"error\":\"TTS not initialized\"}";
        if (text == null || text.isEmpty()) return "{\"error\":\"empty text\"}";
        try {
            long t0 = System.currentTimeMillis();
            GeneratedAudio audio = tts.generate(text, speakerId, speed);
            long genMs = System.currentTimeMillis() - t0;
            float[] samples = audio.getSamples();
            int sampleRate = audio.getSampleRate();
            if (samples == null || samples.length == 0) {
                return "{\"error\":\"TTS generated no audio\"}";
            }
            short[] pcm = new short[samples.length];
            for (int i = 0; i < samples.length; i++) {
                float s = Math.max(-1.0f, Math.min(1.0f, samples[i]));
                pcm[i] = (short) (s * 32767);
            }
            writeWav(wavPath, pcm, sampleRate);
            float durationSec = (float) samples.length / sampleRate;
            return "{\"status\":\"ok\",\"path\":\"" + escJson(wavPath)
                + "\",\"text\":\"" + escJson(text)
                + "\",\"duration\":" + String.format("%.1f", durationSec)
                + ",\"gen_ms\":" + genMs
                + ",\"sample_rate\":" + sampleRate
                + ",\"samples\":" + samples.length + "}";
        } catch (Exception e) {
            Log.e(TAG, "TTS speakToFile failed", e);
            return "{\"error\":\"" + escJson(e.getMessage()) + "\"}";
        }
    }

    private void writeWav(String path, short[] pcm, int sampleRate) throws Exception {
        int dataLen = pcm.length * 2;
        int totalLen = 36 + dataLen;
        java.io.DataOutputStream out = new java.io.DataOutputStream(
            new java.io.BufferedOutputStream(new java.io.FileOutputStream(path)));
        // RIFF header
        out.writeBytes("RIFF");
        out.writeInt(Integer.reverseBytes(totalLen));
        out.writeBytes("WAVE");
        // fmt chunk
        out.writeBytes("fmt ");
        out.writeInt(Integer.reverseBytes(16));  // chunk size
        out.writeShort(Short.reverseBytes((short) 1));  // PCM
        out.writeShort(Short.reverseBytes((short) 1));  // mono
        out.writeInt(Integer.reverseBytes(sampleRate));
        out.writeInt(Integer.reverseBytes(sampleRate * 2));  // byte rate
        out.writeShort(Short.reverseBytes((short) 2));  // block align
        out.writeShort(Short.reverseBytes((short) 16)); // bits per sample
        // data chunk
        out.writeBytes("data");
        out.writeInt(Integer.reverseBytes(dataLen));
        for (short s : pcm) {
            out.writeShort(Short.reverseBytes(s));
        }
        out.close();
    }

    /**
     * Speak via local TTS, streaming generated chunks into an AudioTrack as
     * they arrive: time-to-first-audio ≈ first chunk generation instead of
     * the whole utterance. Cancelable via stopSpeaking(). Concurrent callers
     * are serialized. Reports gen_ms and first_audio_ms (VOICE-32).
     */
    public String speak(String text, int speakerId, float speed) {
        if (tts == null) return "{\"error\":\"TTS not initialized\"}";
        if (text == null || text.isEmpty()) return "{\"error\":\"empty text\"}";

        synchronized (ttsPlaybackLock) {
            ttsCancel.set(false);
            AudioTrack track = null;
            try {
                final int sampleRate = tts.getSampleRate();
                int minBuf = AudioTrack.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack(
                    new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                    Math.max(minBuf * 2, sampleRate), // ≥ 0.5 s of buffer
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
                ttsTrack = track;
                track.play();

                final AudioTrack playTrack = track;
                final long t0 = System.currentTimeMillis();
                final long[] firstAudioMs = { -1 };
                final long[] totalSamples = { 0 };

                tts.generateWithCallback(text, speakerId, speed,
                    new OfflineTtsCallback() {
                        @Override
                        public Integer invoke(float[] samples) {
                            if (ttsCancel.get()) return 0; // abort generation
                            if (firstAudioMs[0] < 0) {
                                firstAudioMs[0] = System.currentTimeMillis() - t0;
                            }
                            short[] pcm = new short[samples.length];
                            for (int i = 0; i < samples.length; i++) {
                                float s = Math.max(-1.0f, Math.min(1.0f, samples[i]));
                                pcm[i] = (short) (s * 32767);
                            }
                            // Blocking write paces generation to playback
                            playTrack.write(pcm, 0, pcm.length);
                            totalSamples[0] += samples.length;
                            return ttsCancel.get() ? 0 : 1;
                        }
                    });
                long genMs = System.currentTimeMillis() - t0;

                if (totalSamples[0] == 0 && !ttsCancel.get()) {
                    return "{\"error\":\"TTS generated no audio\"}";
                }

                // Wait for the buffered tail to finish playing (or cancel)
                long deadline = System.currentTimeMillis() + 30000;
                while (!ttsCancel.get()
                        && track.getPlaybackHeadPosition() < totalSamples[0]
                        && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }

                float durationSec = (float) totalSamples[0] / sampleRate;
                boolean cancelled = ttsCancel.get();
                return "{\"status\":\"" + (cancelled ? "cancelled" : "ok")
                    + "\",\"text\":\"" + escJson(text)
                    + "\",\"duration\":" + String.format("%.1f", durationSec)
                    + ",\"gen_ms\":" + genMs
                    + ",\"first_audio_ms\":" + (firstAudioMs[0] < 0 ? genMs : firstAudioMs[0])
                    + "}";
            } catch (Exception e) {
                Log.e(TAG, "TTS speak failed", e);
                return "{\"error\":\"" + escJson(e.getMessage()) + "\"}";
            } finally {
                ttsTrack = null;
                if (track != null) {
                    try { track.stop(); } catch (Exception ignored) {}
                    track.release();
                }
            }
        }
    }

    /**
     * Cancel in-progress TTS playback. Returns immediately; the speak() call
     * unblocks, stops generation, and reports status "cancelled".
     */
    public String stopSpeaking() {
        ttsCancel.set(true);
        AudioTrack track = ttsTrack;
        if (track != null) {
            // pause+flush discards buffered audio and unblocks a pending
            // blocking write in the generation callback.
            try { track.pause(); track.flush(); } catch (Exception ignored) {}
            return "{\"status\":\"stopping\"}";
        }
        return "{\"status\":\"idle\"}";
    }

    /**
     * Encoder warm-up length. The online model emits nothing usable until it
     * has seen roughly this much non-degenerate audio — and digital silence
     * does NOT count, because all-zero frames give degenerate log-mel
     * features that never let the encoder settle. Measured on-device with one
     * fixed utterance ("The quick brown fox jumps over the lazy dog"):
     *
     *   no padding        -> "S over the Lazy do"
     *   +2.5s digital sil -> "S jumps over the Lazy Dog"   (still broken)
     *   +2.5s dither      -> "The quick brown fox jumps over the lazy dog"
     *
     * Room tone counts only partially (a real recording with 1.7 s of room
     * noise still lost its first word), so we prepend synthetic dither rather
     * than relying on ambient noise or a bigger pre-roll buffer.
     */
    private static final int WARMUP_MS = 2500;

    /**
     * Feed low-level dither so the encoder is warm before real audio arrives.
     * The dither itself decodes to nothing, so it adds no spurious tokens.
     */
    private void warmUpStream(OnlineStream stream) {
        int total = SAMPLE_RATE * WARMUP_MS / 1000;
        int chunk = SAMPLE_RATE / 10;
        float[] buf = new float[chunk];
        for (int off = 0; off < total; off += chunk) {
            int len = Math.min(chunk, total - off);
            for (int i = 0; i < len; i++) {
                buf[i] = (float) (Math.sin((off + i) / 8.0) * 12.0 / 32768.0);
            }
            stream.acceptWaveform(len == chunk ? buf : Arrays.copyOf(buf, len), SAMPLE_RATE);
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream);
            }
        }
    }

    /**
     * Transcribe a WAV file with the streaming recognizer.
     *
     * An online recognizer has to be driven like a stream: fed in small
     * chunks, decoded as it goes, with endpoints accumulated. Handing it the
     * whole file in one acceptWaveform() and decoding once only ever yielded
     * roughly the last 1-1.5 s of audio, and clips shorter than that returned
     * nothing at all ("Baltimore" -> "", the pangram -> "S over the Lazy do").
     * This mirrors streamListen's read loop, which drives the model correctly.
     */
    public String transcribeFile(String wavPath) {
        if (recognizer == null) return "{\"error\":\"STT not initialized\"}";

        try {
            long t0 = System.currentTimeMillis();
            float[] samples = readWavSamples(wavPath);
            if (samples == null || samples.length == 0) {
                return "{\"error\":\"failed to read WAV file\"}";
            }

            OnlineStream stream = recognizer.createStream();
            StringBuilder allText = new StringBuilder();
            try {
                warmUpStream(stream);
                int chunk = SAMPLE_RATE / 10;   // 100 ms, same as streamListen
                for (int off = 0; off < samples.length; off += chunk) {
                    int end = Math.min(off + chunk, samples.length);
                    stream.acceptWaveform(Arrays.copyOfRange(samples, off, end), SAMPLE_RATE);
                    while (recognizer.isReady(stream)) {
                        recognizer.decode(stream);
                    }
                    /* Finalize at each endpoint — reset() otherwise discards
                     * everything decoded since the previous one. */
                    if (recognizer.isEndpoint(stream)) {
                        String seg = recognizer.getResult(stream).getText().trim();
                        if (!seg.isEmpty()) {
                            if (allText.length() > 0) allText.append(" ");
                            allText.append(seg);
                        }
                        recognizer.reset(stream);
                    }
                }

                stream.inputFinished();
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream);
                }
                String tail = recognizer.getResult(stream).getText().trim();
                if (!tail.isEmpty()) {
                    if (allText.length() > 0) allText.append(" ");
                    allText.append(tail);
                }
            } finally {
                stream.release();
            }

            long elapsed = System.currentTimeMillis() - t0;
            // Apply punctuation post-processing
            String punctuated = addPunctuation(allText.toString().trim());

            return "{\"text\":\"" + escJson(punctuated)
                + "\",\"duration\":" + String.format("%.1f", (float) samples.length / SAMPLE_RATE)
                + ",\"elapsed_ms\":" + elapsed + "}";
        } catch (Exception e) {
            Log.e(TAG, "Transcribe file failed", e);
            return "{\"error\":\"" + escJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * Transcribe a WAV file using SenseVoice offline recognizer.
     * Returns text with punctuation, casing, emotion, and language.
     */
    public String transcribeOffline(String wavPath) {
        if (offlineRecognizer == null) return "{\"error\":\"offline STT not initialized\"}";

        try {
            long t0 = System.currentTimeMillis();
            float[] samples = readWavSamples(wavPath);
            if (samples == null || samples.length == 0) {
                return "{\"error\":\"failed to read WAV file\"}";
            }

            OfflineStream stream = offlineRecognizer.createStream();
            stream.acceptWaveform(samples, SAMPLE_RATE);
            offlineRecognizer.decode(stream);
            OfflineRecognizerResult result = offlineRecognizer.getResult(stream);
            stream.release();

            long elapsed = System.currentTimeMillis() - t0;
            String text = result.getText().trim();
            String lang = result.getLang();
            String emotion = result.getEmotion();
            String event = result.getEvent();

            StringBuilder sb = new StringBuilder();
            sb.append("{\"text\":\"").append(escJson(text)).append("\"");
            sb.append(",\"duration\":").append(String.format("%.1f", (float) samples.length / SAMPLE_RATE));
            sb.append(",\"elapsed_ms\":").append(elapsed);
            if (lang != null && !lang.isEmpty()) sb.append(",\"lang\":\"").append(escJson(lang)).append("\"");
            if (emotion != null && !emotion.isEmpty()) sb.append(",\"emotion\":\"").append(escJson(emotion)).append("\"");
            if (event != null && !event.isEmpty()) sb.append(",\"event\":\"").append(escJson(event)).append("\"");
            sb.append("}");
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Offline transcription failed", e);
            return "{\"error\":\"" + escJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * Transcribe float samples using SenseVoice offline recognizer.
     * Used internally by diarize() for individual segments.
     */
    private OfflineRecognizerResult transcribeOfflineSamples(float[] samples) {
        if (offlineRecognizer == null) return null;
        OfflineStream stream = offlineRecognizer.createStream();
        stream.acceptWaveform(samples, SAMPLE_RATE);
        offlineRecognizer.decode(stream);
        OfflineRecognizerResult result = offlineRecognizer.getResult(stream);
        stream.release();
        return result;
    }

    /**
     * Diarize a WAV file: identify speakers and transcribe each segment.
     * Combines OfflineSpeakerDiarization with SenseVoice transcription.
     * @return JSON array of segments: [{speaker, start, end, text}, ...]
     */
    public String diarize(String wavPath) {
        if (diarizer == null) return "{\"error\":\"diarization not initialized\"}";
        if (offlineRecognizer == null) return "{\"error\":\"offline STT not initialized (needed for diarization transcription)\"}";

        try {
            long t0 = System.currentTimeMillis();
            float[] samples = readWavSamples(wavPath);
            if (samples == null || samples.length == 0) {
                return "{\"error\":\"failed to read WAV file\"}";
            }

            // Run diarization
            OfflineSpeakerDiarizationSegment[] segments = diarizer.process(samples);
            long diarMs = System.currentTimeMillis() - t0;
            Log.i(TAG, "Diarization found " + segments.length + " segments in " + diarMs + "ms");

            if (segments.length == 0) {
                return "{\"segments\":[],\"duration\":"
                    + String.format("%.1f", (float) samples.length / SAMPLE_RATE)
                    + ",\"elapsed_ms\":" + diarMs + "}";
            }

            // Transcribe each segment using SenseVoice
            StringBuilder sb = new StringBuilder();
            sb.append("{\"segments\":[");
            for (int i = 0; i < segments.length; i++) {
                float start = segments[i].getStart();
                float end = segments[i].getEnd();
                int speaker = segments[i].getSpeaker();

                // Extract audio segment
                int startSample = Math.max(0, (int) (start * SAMPLE_RATE));
                int endSample = Math.min(samples.length, (int) (end * SAMPLE_RATE));
                if (endSample <= startSample) continue;

                float[] segSamples = Arrays.copyOfRange(samples, startSample, endSample);

                // Transcribe segment
                OfflineRecognizerResult result = transcribeOfflineSamples(segSamples);
                String text = (result != null) ? result.getText().trim() : "";

                if (i > 0) sb.append(",");
                sb.append("{\"speaker\":").append(speaker);
                sb.append(",\"start\":").append(String.format("%.2f", start));
                sb.append(",\"end\":").append(String.format("%.2f", end));
                sb.append(",\"text\":\"").append(escJson(text)).append("\"}");
            }
            sb.append("]");

            long elapsed = System.currentTimeMillis() - t0;
            sb.append(",\"num_speakers\":").append(countSpeakers(segments));
            sb.append(",\"duration\":").append(String.format("%.1f", (float) samples.length / SAMPLE_RATE));
            sb.append(",\"elapsed_ms\":").append(elapsed);
            sb.append("}");
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Diarization failed", e);
            return "{\"error\":\"" + escJson(e.getMessage()) + "\"}";
        }
    }

    private int countSpeakers(OfflineSpeakerDiarizationSegment[] segments) {
        int max = -1;
        for (OfflineSpeakerDiarizationSegment seg : segments) {
            if (seg.getSpeaker() > max) max = seg.getSpeaker();
        }
        return max + 1;
    }

    /**
     * Try to claim the mic for `who`. Returns null on success, or the name
     * of the current holder (for a descriptive "mic busy" error).
     */
    public String claimMic(String who) {
        synchronized (micLock) {
            if (micHolder != null) return micHolder;
            micHolder = who;
            return null;
        }
    }

    /** Release the mic claim if `who` still holds it. */
    public void releaseMic(String who) {
        synchronized (micLock) {
            if (who.equals(micHolder)) micHolder = null;
        }
    }

    /** Acquire the capture wake lock (reference-counted, 30 min safety cap). */
    public void acquireListenLock() {
        try {
            synchronized (this) {
                if (listenWakeLock == null) {
                    PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                    listenWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "beamapp:listen");
                    listenWakeLock.setReferenceCounted(true);
                }
            }
            listenWakeLock.acquire(30 * 60 * 1000L);
        } catch (Exception e) {
            Log.w(TAG, "Wake lock acquire failed", e);
        }
    }

    /** Release one reference on the capture wake lock. */
    public void releaseListenLock() {
        try {
            if (listenWakeLock != null && listenWakeLock.isHeld()) listenWakeLock.release();
        } catch (Exception e) {
            Log.w(TAG, "Wake lock release failed", e);
        }
    }

    /**
     * Open the mic for speech recognition: VOICE_RECOGNITION source (platform
     * tuning for ASR) with echo cancellation and noise suppression attached
     * when the device supports them. Without AEC, the phone's own TTS output
     * re-enters the recognizer in VAD/continuous modes.
     * Returns null if the mic is unavailable.
     * Callers are expected to hold a mic claim (claimMic) first.
     */
    @SuppressWarnings("MissingPermission")
    AudioRecord openMic() {
        int bufSize = Math.max(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT),
            SAMPLE_RATE * 2);
        AudioRecord mic;
        try {
            mic = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
        } catch (Exception e) {
            Log.w(TAG, "Mic open failed", e);
            return null;
        }
        if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
            mic.release();
            return null;
        }
        try {
            int session = mic.getAudioSessionId();
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler aec = AcousticEchoCanceler.create(session);
                if (aec != null) aec.setEnabled(true);
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor ns = NoiseSuppressor.create(session);
                if (ns != null) ns.setEnabled(true);
            }
        } catch (Exception e) {
            Log.w(TAG, "AEC/NS setup failed (continuing without)", e);
        }
        return mic;
    }

    /**
     * Start pre-recording mic into a ring buffer so we capture audio
     * before stream_listen is actually dispatched. Call when user taps Listen.
     */
    @SuppressWarnings("MissingPermission")
    public void startPreRecord() {
        if (preRecording) return;
        String busy = claimMic("pre_record");
        if (busy != null) {
            Log.i(TAG, "Pre-record skipped: mic busy (" + busy + ")");
            return;
        }
        try {
            preMic = openMic();
            if (preMic == null) {
                releaseMic("pre_record");
                return;
            }
            // Ring buffer: 1.5 seconds of lookback
            preBuffer = new float[SAMPLE_RATE * 3 / 2];
            preBufferPos = 0;
            preBufferFilled = 0;
            preRecording = true;
            preMic.startRecording();

            preRecordThread = new Thread(() -> {
                short[] raw = new short[SAMPLE_RATE / 10]; // 100ms chunks
                while (preRecording) {
                    int read = preMic.read(raw, 0, raw.length);
                    if (read > 0) {
                        synchronized (preBuffer) {
                            for (int i = 0; i < read; i++) {
                                preBuffer[preBufferPos] = raw[i] / 32768.0f;
                                preBufferPos = (preBufferPos + 1) % preBuffer.length;
                                preBufferFilled++;
                            }
                        }
                    }
                }
            }, "pre-record");
            preRecordThread.start();
            Log.i(TAG, "Pre-record started");
        } catch (Exception e) {
            Log.w(TAG, "Pre-record failed", e);
            preRecording = false;
            if (preMic != null) { preMic.release(); preMic = null; }
            releaseMic("pre_record");
        }
    }

    /**
     * Stop the pre-record thread and take ownership of the mic (don't release it).
     * Returns the buffered lookback audio and the still-recording AudioRecord.
     * If a mic is returned, the mic claim transfers from "pre_record" to
     * `newHolder` with no gap.
     */
    private AudioRecord takePreRecordMic(float[][] lookbackOut, String newHolder) {
        synchronized (micLock) {
            preRecording = false;
            if (preRecordThread != null) {
                try { preRecordThread.join(500); } catch (Exception ignored) {}
                preRecordThread = null;
            }
            // Extract lookback audio from ring buffer
            if (preBuffer != null) {
                synchronized (preBuffer) {
                    int total = Math.min(preBufferFilled, preBuffer.length);
                    if (total > 0) {
                        float[] result = new float[total];
                        if (preBufferFilled >= preBuffer.length) {
                            int firstLen = preBuffer.length - preBufferPos;
                            System.arraycopy(preBuffer, preBufferPos, result, 0, firstLen);
                            System.arraycopy(preBuffer, 0, result, firstLen, preBufferPos);
                        } else {
                            System.arraycopy(preBuffer, 0, result, 0, total);
                        }
                        lookbackOut[0] = result;
                    }
                    preBuffer = null;
                }
            }
            // Return the mic — still recording, caller takes ownership
            AudioRecord mic = preMic;
            preMic = null;
            if (mic != null && "pre_record".equals(micHolder)) {
                micHolder = newHolder;
            }
            return mic;
        }
    }

    /**
     * Stop pre-record without a taker: stop the thread, release the mic and
     * the claim. Previously this path leaked an open, recording AudioRecord
     * if no listen ever took the pre-record mic over.
     */
    private void cancelPreRecord() {
        synchronized (micLock) {
            if (!preRecording && preMic == null) return;
            preRecording = false;
            if (preRecordThread != null) {
                try { preRecordThread.join(500); } catch (Exception ignored) {}
                preRecordThread = null;
            }
            if (preMic != null) {
                try { preMic.stop(); } catch (Exception ignored) {}
                preMic.release();
                preMic = null;
            }
            preBuffer = null;
            if ("pre_record".equals(micHolder)) micHolder = null;
        }
    }

    /**
     * Stream-listen: record from mic, stream STT results continuously.
     * Keeps listening until stopListening() is called or maxDurationSec is reached.
     * On recognizer endpoint, finalizes the current utterance and starts a new one.
     * Reuses the pre-record mic if available (no audio gap).
     */
    public String streamListen(int maxDurationSec, PartialCallback partialCallback) {
        return streamListen(maxDurationSec, false, partialCallback);
    }

    /**
     * Stream-listen with optional endpoint-return: when stopAtEndpoint is
     * true, returns as soon as the first complete utterance is detected
     * (conversation turn-taking) instead of listening for the full window.
     */
    @SuppressWarnings("MissingPermission")
    public String streamListen(int maxDurationSec, boolean stopAtEndpoint,
            PartialCallback partialCallback) {
        if (recognizer == null) return "{\"error\":\"STT not initialized\"}";

        listening.set(true);
        partialResult = "";

        // Take over the pre-record mic (still recording, no gap; claim
        // transfers with it)
        float[][] lookbackHolder = new float[1][];
        AudioRecord mic = takePreRecordMic(lookbackHolder, "stream_listen");
        float[] lookback = lookbackHolder[0];
        boolean micClaimed = (mic != null);

        OnlineStream stream = null;
        acquireListenLock();
        try {
            // If no pre-record mic, create a new one
            if (mic == null) {
                String busy = claimMic("stream_listen");
                if (busy != null) return "{\"error\":\"mic busy (" + busy + ")\"}";
                micClaimed = true;
                mic = openMic();
                if (mic == null) return "{\"error\":\"mic not available\"}";
                mic.startRecording();
            }

            stream = recognizer.createStream();
            warmUpStream(stream);

            // Feed lookback buffer first (audio captured before this method was called)
            if (lookback != null && lookback.length > 0) {
                stream.acceptWaveform(lookback, SAMPLE_RATE);
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream);
                }
                Log.i(TAG, "Fed " + lookback.length + " lookback samples (" +
                    String.format("%.1f", lookback.length / (float)SAMPLE_RATE) + "s)");
            }

            long startTime = System.currentTimeMillis();
            long maxMs = maxDurationSec * 1000L;
            long utteranceEndTs = 0;
            short[] buf = new short[SAMPLE_RATE / 10]; // 100ms chunks
            float[] floatBuf = new float[buf.length];
            String lastPartial = "";
            StringBuilder allText = new StringBuilder();

            while (listening.get() && (System.currentTimeMillis() - startTime) < maxMs) {
                int read = mic.read(buf, 0, buf.length);
                if (read <= 0) continue;

                for (int i = 0; i < read; i++) {
                    floatBuf[i] = buf[i] / 32768.0f;
                }

                float[] chunk;
                if (read < floatBuf.length) {
                    chunk = new float[read];
                    System.arraycopy(floatBuf, 0, chunk, 0, read);
                } else {
                    chunk = floatBuf;
                }

                stream.acceptWaveform(chunk, SAMPLE_RATE);
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream);
                }

                OnlineRecognizerResult result = recognizer.getResult(stream);
                String text = result.getText().trim();

                // Build display: all finalized utterances + current partial
                String display = allText.toString();
                if (!text.isEmpty()) {
                    display = display.isEmpty() ? text : display + " " + text;
                }
                if (!display.isEmpty() && !display.equals(lastPartial)) {
                    lastPartial = display;
                    partialResult = display;
                    if (partialCallback != null) {
                        partialCallback.onPartial(display);
                    }
                }

                // On endpoint: finalize this utterance, append to allText, reset stream
                if (recognizer.isEndpoint(stream)) {
                    if (!text.isEmpty()) {
                        if (allText.length() > 0) allText.append(" ");
                        allText.append(text);
                        utteranceEndTs = System.currentTimeMillis();
                        if (stopAtEndpoint) {
                            recognizer.reset(stream);
                            break;
                        }
                    }
                    recognizer.reset(stream);
                }
            }

            mic.stop();

            // Flush any remaining audio in the stream
            stream.inputFinished();
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream);
            }
            OnlineRecognizerResult finalResult = recognizer.getResult(stream);
            String tailText = finalResult.getText().trim();
            if (!tailText.isEmpty()) {
                if (allText.length() > 0) allText.append(" ");
                allText.append(tailText);
                utteranceEndTs = System.currentTimeMillis();
            }

            String finalText = allText.toString().trim();
            if (finalText.isEmpty()) finalText = lastPartial;

            // Apply punctuation post-processing
            String punctuated = addPunctuation(finalText);

            long elapsed = System.currentTimeMillis() - startTime;

            // utterance_end_ts: epoch ms of the last finalized utterance —
            // lets the caller compute end-of-utterance→response latency
            // (VOICE-32 telemetry).
            return "{\"text\":\"" + escJson(punctuated)
                + "\",\"duration\":" + String.format("%.1f", elapsed / 1000.0)
                + ",\"elapsed_ms\":" + elapsed
                + ",\"utterance_end_ts\":" + utteranceEndTs + "}";
        } catch (Exception e) {
            Log.e(TAG, "Stream listen failed", e);
            return "{\"error\":\"" + escJson(e.getMessage()) + "\"}";
        } finally {
            listening.set(false);
            if (stream != null) stream.release();
            if (mic != null) {
                try { mic.stop(); } catch (Exception ignored) {}
                mic.release();
            }
            if (micClaimed) releaseMic("stream_listen");
            releaseListenLock();
        }
    }

    public void stopListening() {
        listening.set(false);
        // Clean up pre-record if it's still running (releases the mic and
        // its claim if no listen ever took it over)
        cancelPreRecord();
    }
    public String getPartialResult() { return partialResult; }

    public String getStatus() {
        return "{\"stt\":" + (recognizer != null)
            + ",\"vad\":" + (vad != null)
            + ",\"tts\":" + (tts != null)
            + ",\"punctuation\":" + (punctuation != null)
            + ",\"offline_stt\":" + (offlineRecognizer != null)
            + ",\"diarization\":" + (diarizer != null) + "}";
    }

    public boolean isSttReady() { return recognizer != null; }
    public boolean isTtsReady() { return tts != null; }
    public boolean isOfflineSttReady() { return offlineRecognizer != null; }
    public boolean isDiarizationReady() { return diarizer != null; }

    /** Path of the speaker embedding model (also used by SpeakerId), or null. */
    public String getEmbeddingModelPath() { return embeddingModelPath; }

    public void release() {
        if (recognizer != null) { recognizer.release(); recognizer = null; }
        if (vad != null) { vad.release(); vad = null; }
        if (tts != null) { tts.release(); tts = null; }
        if (punctuation != null) { punctuation.release(); punctuation = null; }
        if (offlineRecognizer != null) { offlineRecognizer.release(); offlineRecognizer = null; }
        if (diarizer != null) { diarizer.release(); diarizer = null; }
    }

    /**
     * Read a 16-bit mono 16kHz WAV file and return float samples.
     * Walks RIFF chunks to find "data" instead of assuming a 44-byte header,
     * so files with LIST/fact chunks parse correctly. Non-RIFF files fall
     * back to the legacy skip-44 behavior.
     */
    float[] readWavSamples(String path) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(path, "r");
        try {
            if (raf.length() < 12) return null;
            byte[] hdr = new byte[12];
            raf.readFully(hdr);
            boolean riff = hdr[0] == 'R' && hdr[1] == 'I' && hdr[2] == 'F' && hdr[3] == 'F'
                && hdr[8] == 'W' && hdr[9] == 'A' && hdr[10] == 'V' && hdr[11] == 'E';
            long dataLen = -1;
            if (riff) {
                while (raf.getFilePointer() + 8 <= raf.length()) {
                    byte[] ch = new byte[8];
                    raf.readFully(ch);
                    long size = (ch[4] & 0xFFL) | ((ch[5] & 0xFFL) << 8)
                        | ((ch[6] & 0xFFL) << 16) | ((ch[7] & 0xFFL) << 24);
                    if (ch[0] == 'd' && ch[1] == 'a' && ch[2] == 't' && ch[3] == 'a') {
                        dataLen = Math.min(size, raf.length() - raf.getFilePointer());
                        break;
                    }
                    raf.seek(raf.getFilePointer() + size + (size & 1)); // chunks are word-aligned
                }
                if (dataLen < 0) return null;
            } else {
                raf.seek(44);
                dataLen = raf.length() - 44;
            }
            if (dataLen <= 0) return null;

            int numSamples = (int) (dataLen / 2);
            float[] samples = new float[numSamples];
            byte[] raw = new byte[numSamples * 2];
            raf.readFully(raw);
            for (int i = 0; i < numSamples; i++) {
                short s = (short) ((raw[i * 2] & 0xFF) | (raw[i * 2 + 1] << 8));
                samples[i] = s / 32768.0f;
            }
            return samples;
        } finally {
            raf.close();
        }
    }

    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * Stream-listen from an external audio source (e.g. BLE earbuds) instead of the phone mic.
     * Reuses the same OnlineRecognizer, endpoint detection, and punctuation pipeline.
     */
    public String streamFromExternalAudio(int maxDurationSec,
            ExternalAudioSource source, PartialCallback partialCallback) {
        return streamFromExternalAudio(maxDurationSec, false, source, partialCallback);
    }

    /**
     * External-audio stream-listen with optional endpoint-return
     * (stopAtEndpoint — see streamListen). VAD gating with pre-roll and
     * hysteresis lives in VadSegmenter; gated windows feed the recognizer.
     */
    public String streamFromExternalAudio(int maxDurationSec, boolean stopAtEndpoint,
            ExternalAudioSource source, PartialCallback partialCallback) {
        if (recognizer == null) return "{\"error\":\"STT not initialized\"}";

        listening.set(true);
        partialResult = "";
        OnlineStream stream = null;

        acquireListenLock();
        try {
            stream = recognizer.createStream();
            warmUpStream(stream);

            long startTime = System.currentTimeMillis();
            long maxMs = maxDurationSec * 1000L;
            long utteranceEndTs = 0;
            String lastPartial = "";
            StringBuilder allText = new StringBuilder();

            boolean useVad = (vad != null);
            final OnlineStream recStream = stream;
            VadSegmenter segmenter = !useVad ? null : new VadSegmenter(vad,
                new VadSegmenter.Sink() {
                    @Override
                    public void onAudio(float[] window) {
                        recStream.acceptWaveform(window, SAMPLE_RATE);
                    }

                    @Override
                    public void onUtteranceEnd() {
                        // Utterance boundaries are handled by the recognizer's
                        // own endpoint detection in the read loop.
                    }
                });

            while (listening.get() && source.isActive()
                    && (System.currentTimeMillis() - startTime) < maxMs) {
                float[] chunk = source.readChunk();
                if (chunk == null) {
                    Thread.sleep(10);
                    continue;
                }

                if (!useVad) {
                    // No VAD — feed everything directly (fallback)
                    stream.acceptWaveform(chunk, SAMPLE_RATE);
                } else {
                    segmenter.accept(chunk);
                }

                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream);
                }

                OnlineRecognizerResult result = recognizer.getResult(stream);
                String text = result.getText().trim();

                String display = allText.toString();
                if (!text.isEmpty()) {
                    display = display.isEmpty() ? text : display + " " + text;
                }
                if (!display.isEmpty() && !display.equals(lastPartial)) {
                    lastPartial = display;
                    partialResult = display;
                    if (partialCallback != null) {
                        partialCallback.onPartial(display);
                    }
                }

                if (recognizer.isEndpoint(stream)) {
                    if (!text.isEmpty()) {
                        if (allText.length() > 0) allText.append(" ");
                        allText.append(text);
                        utteranceEndTs = System.currentTimeMillis();
                        if (stopAtEndpoint) {
                            recognizer.reset(stream);
                            break;
                        }
                    }
                    recognizer.reset(stream);
                }
            }

            if (useVad) {
                // Flush any remaining partial VAD window (trailing edge of
                // the last utterance).
                segmenter.flush();
                Log.i(TAG, "VAD stats: speech=" + segmenter.getSpeechWindows()
                    + " silence=" + segmenter.getSilenceWindows()
                    + " ratio=" + (segmenter.getSpeechWindows() > 0 ?
                        String.format("%.1f%%", 100.0 * segmenter.getSpeechWindows()
                            / (segmenter.getSpeechWindows() + segmenter.getSilenceWindows())) : "N/A"));
            }

            stream.inputFinished();
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream);
            }
            OnlineRecognizerResult finalResult = recognizer.getResult(stream);
            String tailText = finalResult.getText().trim();
            if (!tailText.isEmpty()) {
                if (allText.length() > 0) allText.append(" ");
                allText.append(tailText);
                utteranceEndTs = System.currentTimeMillis();
            }

            String finalText = allText.toString().trim();
            if (finalText.isEmpty()) finalText = lastPartial;

            String punctuated = addPunctuation(finalText);
            long elapsed = System.currentTimeMillis() - startTime;

            String vadInfo = useVad ?
                ",\"vad_speech\":" + segmenter.getSpeechWindows()
                + ",\"vad_silence\":" + segmenter.getSilenceWindows() : "";

            return "{\"text\":\"" + escJson(punctuated)
                + "\",\"source\":\"ble\""
                + ",\"duration\":" + String.format("%.1f", elapsed / 1000.0)
                + ",\"elapsed_ms\":" + elapsed
                + ",\"utterance_end_ts\":" + utteranceEndTs
                + vadInfo + "}";
        } catch (Exception e) {
            Log.e(TAG, "External audio stream listen failed", e);
            return "{\"error\":\"" + escJson(e.getMessage()) + "\"}";
        } finally {
            listening.set(false);
            if (stream != null) stream.release();
            releaseListenLock();
        }
    }

    public interface ExternalAudioSource {
        /** Returns a chunk of float samples, or null if no data available yet. */
        float[] readChunk();
        /** Returns false when the source has stopped producing audio. */
        boolean isActive();
    }

    public interface PartialCallback {
        void onPartial(String text);
    }

    /**
     * Callback for delivering completed audio chunks (WAV bytes) during
     * remote-ASR streaming. Called once per VAD-detected utterance.
     */
    public interface AudioChunkCallback {
        void onChunk(byte[] wavBytes, int chunkIndex, float durationSec);
        void onDone(int totalChunks, long elapsedMs);
    }

    /**
     * Record from mic using VAD to segment speech into utterances.
     * Each completed utterance is delivered as WAV bytes via the callback.
     * Designed for remote ASR: the caller sends each chunk to a GPU node.
     *
     * @param maxDurationSec  max total recording time
     * @param callback        receives WAV bytes for each utterance
     * @return final status JSON
     */
    @SuppressWarnings("MissingPermission")
    public String remoteStreamListen(int maxDurationSec, AudioChunkCallback callback) {
        if (vad == null) return "{\"error\":\"VAD not initialized\"}";

        listening.set(true);
        long startTime = System.currentTimeMillis();
        long maxMs = maxDurationSec * 1000L;

        // Take over pre-record mic if available (claim transfers with it)
        float[][] lookbackHolder = new float[1][];
        AudioRecord mic = takePreRecordMic(lookbackHolder, "remote_listen");
        float[] lookback = lookbackHolder[0];
        boolean micClaimed = (mic != null);

        acquireListenLock();
        try {
            if (mic == null) {
                String busy = claimMic("remote_listen");
                if (busy != null) return "{\"error\":\"mic busy (" + busy + ")\"}";
                micClaimed = true;
                mic = openMic();
                if (mic == null) return "{\"error\":\"mic not available\"}";
                mic.startRecording();
            }

            // Utterance segmentation (pre-roll + hysteresis) via VadSegmenter.
            // The old inline version here had neither, so chunks sent to the
            // remote ASR node clipped word onsets (VOICE_REVIEW.md #5).
            final AudioChunkCallback chunkCb = callback;
            final float[][] bufHolder = { new float[SAMPLE_RATE * 30] };
            final int[] lenHolder = { 0 };
            final int[] chunkHolder = { 0 };
            VadSegmenter segmenter = new VadSegmenter(vad, new VadSegmenter.Sink() {
                @Override
                public void onAudio(float[] window) {
                    if (lenHolder[0] + window.length > bufHolder[0].length) {
                        bufHolder[0] = Arrays.copyOf(bufHolder[0], bufHolder[0].length * 2);
                    }
                    System.arraycopy(window, 0, bufHolder[0], lenHolder[0], window.length);
                    lenHolder[0] += window.length;
                }

                @Override
                public void onUtteranceEnd() {
                    if (lenHolder[0] > SAMPLE_RATE / 4) { // min 250ms
                        byte[] wav = samplesToWav(bufHolder[0], lenHolder[0]);
                        if (chunkCb != null) {
                            chunkCb.onChunk(wav, chunkHolder[0],
                                (float) lenHolder[0] / SAMPLE_RATE);
                        }
                        chunkHolder[0]++;
                    }
                    lenHolder[0] = 0;
                }
            });

            // Feed lookback audio (captured by pre_listen) first
            if (lookback != null && lookback.length > 0) {
                segmenter.accept(lookback);
            }

            short[] rawBuf = new short[SAMPLE_RATE / 10]; // 100ms reads
            float[] floatBuf = new float[rawBuf.length];

            while (listening.get() && (System.currentTimeMillis() - startTime) < maxMs) {
                int read = mic.read(rawBuf, 0, rawBuf.length);
                if (read <= 0) continue;

                for (int i = 0; i < read; i++) {
                    floatBuf[i] = rawBuf[i] / 32768.0f;
                }
                segmenter.accept(floatBuf, read);
            }

            // Flush the trailing partial window / mid-speech utterance
            segmenter.flush();

            long elapsed = System.currentTimeMillis() - startTime;
            if (callback != null) {
                callback.onDone(chunkHolder[0], elapsed);
            }

            return "{\"status\":\"ok\",\"chunks\":" + chunkHolder[0]
                + ",\"elapsed_ms\":" + elapsed + "}";
        } catch (Exception e) {
            Log.e(TAG, "Remote stream listen failed", e);
            return "{\"error\":\"" + escJson(e.getMessage()) + "\"}";
        } finally {
            listening.set(false);
            if (mic != null) {
                try { mic.stop(); } catch (Exception ignored) {}
                mic.release();
            }
            if (micClaimed) releaseMic("remote_listen");
            releaseListenLock();
        }
    }

    /** Convert float samples to a 16-bit mono WAV byte array. */
    private byte[] samplesToWav(float[] samples, int length) {
        int dataLen = length * 2;
        int totalLen = 44 + dataLen;
        byte[] wav = new byte[totalLen];

        // RIFF header
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        intToLe(totalLen - 8, wav, 4);
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';

        // fmt chunk
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        intToLe(16, wav, 16);          // chunk size
        shortToLe(1, wav, 20);         // PCM
        shortToLe(1, wav, 22);         // mono
        intToLe(SAMPLE_RATE, wav, 24); // sample rate
        intToLe(SAMPLE_RATE * 2, wav, 28); // byte rate
        shortToLe(2, wav, 32);         // block align
        shortToLe(16, wav, 34);        // bits per sample

        // data chunk
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        intToLe(dataLen, wav, 40);

        // PCM samples
        for (int i = 0; i < length; i++) {
            float s = Math.max(-1.0f, Math.min(1.0f, samples[i]));
            short pcm = (short) (s * 32767);
            wav[44 + i * 2] = (byte) (pcm & 0xFF);
            wav[44 + i * 2 + 1] = (byte) ((pcm >> 8) & 0xFF);
        }
        return wav;
    }

    private static void intToLe(int val, byte[] buf, int off) {
        buf[off]     = (byte) (val & 0xFF);
        buf[off + 1] = (byte) ((val >> 8) & 0xFF);
        buf[off + 2] = (byte) ((val >> 16) & 0xFF);
        buf[off + 3] = (byte) ((val >> 24) & 0xFF);
    }

    private static void shortToLe(int val, byte[] buf, int off) {
        buf[off]     = (byte) (val & 0xFF);
        buf[off + 1] = (byte) ((val >> 8) & 0xFF);
    }
}
