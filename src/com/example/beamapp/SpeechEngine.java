package com.example.beamapp;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
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
            File ttsMarker = new File(ttsDir, ".extracted_v1");
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
            File kittenModel = new File(ttsModelDir, "model.fp16.onnx");
            if (!kittenModel.exists()) kittenModel = new File(ttsModelDir, "model.onnx");
            if (!kittenModel.exists()) {
                Log.w(TAG, "No TTS model.onnx found in " + ttsModelDir);
                return;
            }

            OfflineTtsKittenModelConfig kitten = OfflineTtsKittenModelConfig.builder()
                .setModel(kittenModel.getAbsolutePath())
                .setVoices(ttsModelDir + "/voices.bin")
                .setTokens(ttsModelDir + "/tokens.txt")
                .setDataDir(ttsModelDir + "/espeak-ng-data")
                .setLengthScale(1.0f)
                .build();

            OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                .setKitten(kitten)
                .setNumThreads(2)
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
    @SuppressWarnings("deprecation")
    public String speak(String text) { return speak(text, 5, 1.0f); }

    @SuppressWarnings("deprecation")
    public String speak(String text, int speakerId, float speed) {
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

            int bufSize = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack track = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(bufSize, pcm.length * 2),
                AudioTrack.MODE_STATIC);

            track.write(pcm, 0, pcm.length);
            track.play();

            float durationSec = (float) samples.length / sampleRate;
            Thread.sleep((long) (durationSec * 1000) + 200);
            track.stop();
            track.release();

            return "{\"status\":\"ok\",\"text\":\"" + escJson(text)
                + "\",\"duration\":" + String.format("%.1f", durationSec)
                + ",\"gen_ms\":" + genMs + "}";
        } catch (Exception e) {
            Log.e(TAG, "TTS speak failed", e);
            return "{\"error\":\"" + escJson(e.getMessage()) + "\"}";
        }
    }

    /** Transcribe a WAV file using streaming recognizer (batch mode). */
    public String transcribeFile(String wavPath) {
        if (recognizer == null) return "{\"error\":\"STT not initialized\"}";

        try {
            long t0 = System.currentTimeMillis();
            float[] samples = readWavSamples(wavPath);
            if (samples == null || samples.length == 0) {
                return "{\"error\":\"failed to read WAV file\"}";
            }

            OnlineStream stream = recognizer.createStream();
            stream.acceptWaveform(samples, SAMPLE_RATE);
            stream.inputFinished();

            while (recognizer.isReady(stream)) {
                recognizer.decode(stream);
            }

            OnlineRecognizerResult result = recognizer.getResult(stream);
            stream.release();

            long elapsed = System.currentTimeMillis() - t0;
            String text = result.getText().trim();
            // Apply punctuation post-processing
            String punctuated = addPunctuation(text);

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
     * Start pre-recording mic into a ring buffer so we capture audio
     * before stream_listen is actually dispatched. Call when user taps Listen.
     */
    @SuppressWarnings("MissingPermission")
    public void startPreRecord() {
        if (preRecording) return;
        try {
            int bufSize = Math.max(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT),
                SAMPLE_RATE * 2);
            preMic = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (preMic.getState() != AudioRecord.STATE_INITIALIZED) {
                preMic.release();
                preMic = null;
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
        }
    }

    /**
     * Stop the pre-record thread and take ownership of the mic (don't release it).
     * Returns the buffered lookback audio and the still-recording AudioRecord.
     */
    private AudioRecord takePreRecordMic(float[][] lookbackOut) {
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
        return mic;
    }

    /**
     * Stream-listen: record from mic, stream STT results continuously.
     * Keeps listening until stopListening() is called or maxDurationSec is reached.
     * On recognizer endpoint, finalizes the current utterance and starts a new one.
     * Reuses the pre-record mic if available (no audio gap).
     */
    @SuppressWarnings("MissingPermission")
    public String streamListen(int maxDurationSec, PartialCallback partialCallback) {
        if (recognizer == null) return "{\"error\":\"STT not initialized\"}";

        listening.set(true);
        partialResult = "";

        // Take over the pre-record mic (still recording, no gap)
        float[][] lookbackHolder = new float[1][];
        AudioRecord mic = takePreRecordMic(lookbackHolder);
        float[] lookback = lookbackHolder[0];
        boolean micOwned = (mic != null);

        OnlineStream stream = null;
        try {
            // If no pre-record mic, create a new one
            if (mic == null) {
                int bufSize = Math.max(
                    AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT),
                    SAMPLE_RATE * 2);
                mic = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
                if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
                    mic.release();
                    return "{\"error\":\"mic not available\"}";
                }
                mic.startRecording();
            }

            stream = recognizer.createStream();

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
            }

            String finalText = allText.toString().trim();
            if (finalText.isEmpty()) finalText = lastPartial;

            // Apply punctuation post-processing
            String punctuated = addPunctuation(finalText);

            long elapsed = System.currentTimeMillis() - startTime;

            return "{\"text\":\"" + escJson(punctuated)
                + "\",\"duration\":" + String.format("%.1f", elapsed / 1000.0)
                + ",\"elapsed_ms\":" + elapsed + "}";
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
        }
    }

    public void stopListening() {
        listening.set(false);
        // Clean up pre-record if it's still running
        preRecording = false;
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

    public void release() {
        if (recognizer != null) { recognizer.release(); recognizer = null; }
        if (vad != null) { vad.release(); vad = null; }
        if (tts != null) { tts.release(); tts = null; }
        if (punctuation != null) { punctuation.release(); punctuation = null; }
        if (offlineRecognizer != null) { offlineRecognizer.release(); offlineRecognizer = null; }
        if (diarizer != null) { diarizer.release(); diarizer = null; }
    }

    /** Read 16-bit mono 16kHz WAV file and return float samples */
    private float[] readWavSamples(String path) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(path, "r");
        raf.seek(44);
        long dataLen = raf.length() - 44;
        if (dataLen <= 0) { raf.close(); return null; }

        int numSamples = (int) (dataLen / 2);
        float[] samples = new float[numSamples];
        byte[] raw = new byte[(int) dataLen];
        raf.readFully(raw);
        raf.close();

        for (int i = 0; i < numSamples; i++) {
            short s = (short) ((raw[i * 2] & 0xFF) | (raw[i * 2 + 1] << 8));
            samples[i] = s / 32768.0f;
        }
        return samples;
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
        if (recognizer == null) return "{\"error\":\"STT not initialized\"}";

        listening.set(true);
        partialResult = "";
        OnlineStream stream = null;

        try {
            stream = recognizer.createStream();

            long startTime = System.currentTimeMillis();
            long maxMs = maxDurationSec * 1000L;
            String lastPartial = "";
            StringBuilder allText = new StringBuilder();

            // VAD gating: accumulate chunks into 512-sample windows
            boolean useVad = (vad != null);
            int vadWindow = 512;
            float[] vadBuf = useVad ? new float[vadWindow] : null;
            int vadBufPos = 0;
            int vadSpeechFrames = 0, vadSilenceFrames = 0;
            if (useVad) vad.reset();

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
                    // Accumulate into 512-sample windows for VAD
                    int pos = 0;
                    while (pos < chunk.length) {
                        int toCopy = Math.min(chunk.length - pos, vadWindow - vadBufPos);
                        System.arraycopy(chunk, pos, vadBuf, vadBufPos, toCopy);
                        vadBufPos += toCopy;
                        pos += toCopy;

                        if (vadBufPos == vadWindow) {
                            vad.acceptWaveform(vadBuf);
                            if (vad.isSpeechDetected()) {
                                vadSpeechFrames++;
                                stream.acceptWaveform(vadBuf, SAMPLE_RATE);
                            } else {
                                vadSilenceFrames++;
                            }
                            vadBufPos = 0;
                        }
                    }
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
                    }
                    recognizer.reset(stream);
                }
            }

            if (useVad) {
                Log.i(TAG, "VAD stats: speech=" + vadSpeechFrames
                    + " silence=" + vadSilenceFrames
                    + " ratio=" + (vadSpeechFrames > 0 ?
                        String.format("%.1f%%", 100.0 * vadSpeechFrames / (vadSpeechFrames + vadSilenceFrames)) : "N/A"));
            }

            // Flush any remaining partial VAD buffer
            if (useVad && vadBufPos > 0) {
                // Pad with zeros to fill the window
                for (int i = vadBufPos; i < vadWindow; i++) vadBuf[i] = 0;
                vad.acceptWaveform(vadBuf);
                if (vad.isSpeechDetected()) {
                    vadSpeechFrames++;
                    stream.acceptWaveform(vadBuf, SAMPLE_RATE);
                }
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
            }

            String finalText = allText.toString().trim();
            if (finalText.isEmpty()) finalText = lastPartial;

            String punctuated = addPunctuation(finalText);
            long elapsed = System.currentTimeMillis() - startTime;

            String vadInfo = useVad ?
                ",\"vad_speech\":" + vadSpeechFrames
                + ",\"vad_silence\":" + vadSilenceFrames : "";

            return "{\"text\":\"" + escJson(punctuated)
                + "\",\"source\":\"ble\""
                + ",\"duration\":" + String.format("%.1f", elapsed / 1000.0)
                + ",\"elapsed_ms\":" + elapsed
                + vadInfo + "}";
        } catch (Exception e) {
            Log.e(TAG, "External audio stream listen failed", e);
            return "{\"error\":\"" + escJson(e.getMessage()) + "\"}";
        } finally {
            listening.set(false);
            if (stream != null) stream.release();
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
}
