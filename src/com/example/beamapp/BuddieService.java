package com.example.beamapp;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persistent BLE connection to Buddie earbuds (JieLi JL701N).
 * Receives audio over BLE service 0xAE00.
 *
 * Supports two audio modes:
 * - ae04 (Opus): 84-byte packets with mic+speaker Opus frames (preferred)
 * - ae03 (PCA):  244-byte packets with PCA+DCT compressed audio (fallback)
 *
 * Both decode to 16kHz mono PCM for sherpa-onnx STT.
 */
@SuppressWarnings("MissingPermission")
public class BuddieService {
    private static final String TAG = "BuddieService";

    private static final UUID SERVICE_UUID =
        UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_AE03_UUID =
        UUID.fromString("0000ae03-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_AE04_UUID =
        UUID.fromString("0000ae04-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // PCA packet format constants (from fft_and_pca.h)
    private static final int PCA_PACKET_SIZE = 244;
    private static final int PCA_SIZE = 47;
    private static final int DCT_SIZE = 257;
    private static final int SIGN_BYTE = 33;
    private static final int BLOCK_BYTE = 80;  // PCA_SIZE + SIGN_BYTE
    private static final int BLOCK_NUM = 3;

    // Opus packet format
    private static final int OPUS_PACKET_SIZE = 84;
    private static final int OPUS_FRAME_SIZE = 40;

    private static final int SAMPLE_RATE = 16000;

    // State
    public enum State { DISCONNECTED, CONNECTING, CONNECTED, LISTENING }
    private volatile State state = State.DISCONNECTED;
    private volatile String audioMode = "none"; // "opus", "pca", or "none"

    private final Context context;
    private final BluetoothAdapter btAdapter;
    private final SpeechEngine speechEngine;

    private BluetoothGatt gatt;
    private String connectedAddress;

    // Audio buffer: decoded PCM chunks queued for STT consumption
    private final ConcurrentLinkedQueue<float[]> audioQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean audioSourceActive = new AtomicBoolean(false);
    private final AtomicInteger packetsReceived = new AtomicInteger(0);
    private final AtomicLong lastPacketTime = new AtomicLong(0);
    private volatile String lastTranscription = "";

    // PCA decode matrices (loaded from assets)
    private float[][] iPcaMatrix;  // 47 x 257
    private float[][] iDctMatrix;  // 257 x 257

    // PCA audio accumulator: 3 blocks of 255 samples each → send in 512-sample chunks
    private float[] pcaAccumulator = new float[BLOCK_NUM * (DCT_SIZE - 1)]; // 768
    private int pcaAccumPos = 0;

    // Opus decoder via MediaCodec
    private MediaCodec opusDecoder;

    public BuddieService(Context context, BluetoothAdapter btAdapter, SpeechEngine speechEngine) {
        this.context = context;
        this.btAdapter = btAdapter;
        this.speechEngine = speechEngine;
    }

    public State getState() { return state; }
    public String getConnectedAddress() { return connectedAddress; }
    public int getPacketsReceived() { return packetsReceived.get(); }
    public String getLastTranscription() { return lastTranscription; }
    public String getAudioMode() { return audioMode; }

    /**
     * Connect to Buddie earbuds at the given BLE address.
     * Tries ae04 (Opus) first; if no data after 3s, falls back to ae03 (PCA).
     */
    public String connect(String address) {
        if (btAdapter == null) return "{\"error\":\"no bluetooth adapter\"}";
        if (state != State.DISCONNECTED) {
            return "{\"error\":\"already " + state.name().toLowerCase(Locale.US) + "\"}";
        }

        address = address.trim().toUpperCase(Locale.US);
        if (address.isEmpty()) return "{\"error\":\"empty address\"}";

        state = State.CONNECTING;
        connectedAddress = address;
        packetsReceived.set(0);
        lastTranscription = "";
        audioMode = "none";

        // Load PCA matrices (always, for fallback)
        if (iPcaMatrix == null) {
            try {
                loadPcaMatrices();
            } catch (Exception e) {
                Log.w(TAG, "PCA matrix load failed (PCA mode unavailable)", e);
            }
        }

        final BluetoothDevice device = btAdapter.getRemoteDevice(address);
        final CountDownLatch connectLatch = new CountDownLatch(1);
        final CountDownLatch discoverLatch = new CountDownLatch(1);
        final CountDownLatch subscribeLatch = new CountDownLatch(1);
        final String[] errorHolder = {null};

        BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED
                        && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "GATT connected, requesting MTU 247");
                    g.requestMtu(247);
                    connectLatch.countDown();
                    g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.w(TAG, "GATT disconnected, status=" + status);
                    if (state == State.CONNECTING) {
                        errorHolder[0] = "connection failed, status=" + status;
                    }
                    handleDisconnect();
                    connectLatch.countDown();
                    discoverLatch.countDown();
                    subscribeLatch.countDown();
                }
            }

            @Override
            public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
                Log.i(TAG, "MTU changed to " + mtu + " (status=" + status + ")");
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                g.setPreferredPhy(BluetoothDevice.PHY_LE_2M,
                    BluetoothDevice.PHY_LE_2M,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED);
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Services discovered");
                    discoverLatch.countDown();
                } else {
                    errorHolder[0] = "service discovery failed, status=" + status;
                    discoverLatch.countDown();
                    subscribeLatch.countDown();
                }
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor desc, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "CCCD descriptor written for " + desc.getCharacteristic().getUuid());
                } else {
                    Log.w(TAG, "CCCD write failed, status=" + status);
                    errorHolder[0] = "notification subscribe failed";
                }
                subscribeLatch.countDown();
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt g,
                    BluetoothGattCharacteristic ch, byte[] value) {
                if (value == null) return;
                if (value.length == OPUS_PACKET_SIZE) {
                    handleOpusPacket(value);
                } else if (value.length == PCA_PACKET_SIZE) {
                    handlePcaPacket(value);
                } else {
                    // Log unexpected sizes for debugging
                    if (packetsReceived.get() < 5) {
                        Log.d(TAG, "Unexpected packet size: " + value.length
                            + " from " + ch.getUuid());
                    }
                }
            }
        };

        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);

        try {
            if (!connectLatch.await(10, TimeUnit.SECONDS)) {
                cleanup();
                return "{\"error\":\"connection timeout\"}";
            }
            if (errorHolder[0] != null) {
                cleanup();
                return "{\"error\":\"" + escJson(errorHolder[0]) + "\"}";
            }
            if (!discoverLatch.await(10, TimeUnit.SECONDS)) {
                cleanup();
                return "{\"error\":\"service discovery timeout\"}";
            }
            if (errorHolder[0] != null) {
                cleanup();
                return "{\"error\":\"" + escJson(errorHolder[0]) + "\"}";
            }

            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                StringBuilder sb = new StringBuilder();
                for (BluetoothGattService svc : gatt.getServices()) {
                    sb.append(svc.getUuid().toString()).append(" ");
                }
                cleanup();
                return "{\"error\":\"service 0xAE00 not found. Available: "
                    + escJson(sb.toString().trim()) + "\"}";
            }

            // Try ae04 (Opus) first
            BluetoothGattCharacteristic ae04 = service.getCharacteristic(CHAR_AE04_UUID);
            boolean ae04subscribed = false;
            if (ae04 != null) {
                gatt.setCharacteristicNotification(ae04, true);
                BluetoothGattDescriptor cccd = ae04.getDescriptor(CCCD_UUID);
                if (cccd != null) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    if (subscribeLatch.await(5, TimeUnit.SECONDS) && errorHolder[0] == null) {
                        ae04subscribed = true;
                        Log.i(TAG, "Subscribed to ae04 (Opus)");
                    }
                }
            }

            // Wait briefly to see if Opus packets arrive
            if (ae04subscribed) {
                try {
                    initOpusDecoder();
                } catch (Exception e) {
                    Log.w(TAG, "Opus decoder init failed", e);
                }
                Thread.sleep(2000);
                if (packetsReceived.get() > 0) {
                    audioMode = "opus";
                    state = State.CONNECTED;
                    Log.i(TAG, "Opus mode active, " + packetsReceived.get() + " packets");
                    return "{\"status\":\"connected\",\"address\":\"" + escJson(address)
                        + "\",\"mode\":\"opus\"}";
                }
                Log.i(TAG, "No Opus packets after 2s, falling back to PCA (ae03)");
                // Unsubscribe ae04
                gatt.setCharacteristicNotification(ae04, false);
                releaseOpusDecoder();
            }

            // Fall back to ae03 (PCA)
            if (iPcaMatrix == null || iDctMatrix == null) {
                cleanup();
                return "{\"error\":\"PCA matrices not loaded, cannot use PCA mode\"}";
            }

            BluetoothGattCharacteristic ae03 = service.getCharacteristic(CHAR_AE03_UUID);
            if (ae03 == null) {
                cleanup();
                return "{\"error\":\"characteristic ae03 not found\"}";
            }

            // Reset latch for ae03 subscribe
            final CountDownLatch ae03Latch = new CountDownLatch(1);
            errorHolder[0] = null;

            // We need a fresh latch — replace the callback's reference
            // Since we can't change the latch in the callback, we reuse subscribeLatch
            // which was already counted down. Instead, just subscribe and wait briefly.
            gatt.setCharacteristicNotification(ae03, true);
            BluetoothGattDescriptor cccd03 = ae03.getDescriptor(CCCD_UUID);
            if (cccd03 != null) {
                gatt.writeDescriptor(cccd03, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                // The subscribeLatch already counted down, so we just wait a bit
                Thread.sleep(500);
            }

            audioMode = "pca";
            pcaAccumPos = 0;
            state = State.CONNECTED;

            // Wait to confirm packets are arriving
            Thread.sleep(1000);
            Log.i(TAG, "Connected in PCA mode, " + packetsReceived.get() + " packets received");

            return "{\"status\":\"connected\",\"address\":\"" + escJson(address)
                + "\",\"mode\":\"pca\",\"packets\":" + packetsReceived.get() + "}";

        } catch (InterruptedException e) {
            cleanup();
            return "{\"error\":\"interrupted\"}";
        }
    }

    /**
     * Disconnect from earbuds, clean up GATT and decoder.
     */
    public String disconnect() {
        if (state == State.DISCONNECTED) {
            return "{\"status\":\"already_disconnected\"}";
        }
        audioSourceActive.set(false);
        cleanup();
        return "{\"status\":\"disconnected\"}";
    }

    /**
     * Start streaming STT from earbud audio.
     * Blocks until maxDurationSec elapsed, stopListening() called, or disconnect.
     */
    public String listen(int maxDurationSec, SpeechEngine.PartialCallback callback) {
        if (state != State.CONNECTED) {
            return "{\"error\":\"not connected (state=" + state.name() + ")\"}";
        }
        if (!speechEngine.isSttReady()) {
            return "{\"error\":\"STT engine not ready\"}";
        }

        state = State.LISTENING;
        audioQueue.clear();
        audioSourceActive.set(true);

        SpeechEngine.ExternalAudioSource source = new SpeechEngine.ExternalAudioSource() {
            @Override
            public float[] readChunk() {
                return audioQueue.poll();
            }

            @Override
            public boolean isActive() {
                return audioSourceActive.get() && state == State.LISTENING;
            }
        };

        try {
            String result = speechEngine.streamFromExternalAudio(maxDurationSec, source, callback);
            lastTranscription = extractText(result);
            return result;
        } finally {
            audioSourceActive.set(false);
            if (state == State.LISTENING) {
                state = State.CONNECTED;
            }
        }
    }

    public void stopListening() {
        audioSourceActive.set(false);
        speechEngine.stopListening();
    }

    public String getStatus() {
        return "{\"state\":\"" + state.name().toLowerCase(Locale.US) + "\""
            + ",\"address\":\"" + escJson(connectedAddress != null ? connectedAddress : "") + "\""
            + ",\"mode\":\"" + audioMode + "\""
            + ",\"packets_received\":" + packetsReceived.get()
            + ",\"last_packet_ms\":" + lastPacketTime.get()
            + ",\"last_transcription\":\"" + escJson(lastTranscription) + "\""
            + "}";
    }

    // ==== PCA decode pipeline ====

    private void loadPcaMatrices() throws Exception {
        long t = System.currentTimeMillis();
        iPcaMatrix = loadMatrixAsset("ipca_weight.bin", PCA_SIZE, DCT_SIZE);
        iDctMatrix = loadMatrixAsset("idct_weight.bin", DCT_SIZE, DCT_SIZE);
        Log.i(TAG, "PCA matrices loaded in " + (System.currentTimeMillis() - t) + "ms"
            + " iPCA=" + PCA_SIZE + "x" + DCT_SIZE
            + " iDCT=" + DCT_SIZE + "x" + DCT_SIZE);
    }

    private float[][] loadMatrixAsset(String name, int rows, int cols) throws Exception {
        InputStream is = context.getAssets().open(name);
        byte[] raw = new byte[rows * cols * 4];
        int offset = 0;
        while (offset < raw.length) {
            int read = is.read(raw, offset, raw.length - offset);
            if (read < 0) break;
            offset += read;
        }
        is.close();

        if (offset != raw.length) {
            throw new Exception(name + ": expected " + raw.length + " bytes, got " + offset);
        }

        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[][] matrix = new float[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                matrix[r][c] = bb.getFloat();
            }
        }
        return matrix;
    }

    /**
     * Handle a 244-byte PCA packet from ae03 notifications.
     * Packet: [header(1)] [block0(80)] [block1(80)] [block2(80)] [debug(3)]
     * Each block: [pca_data(47)] [sign_data(33)]
     */
    // Debug counters
    private int dbgSilenceCount = 0;
    private int dbgAudioCount = 0;
    private float dbgMaxRawAmplitude = 0;

    private void handlePcaPacket(byte[] packet) {
        packetsReceived.incrementAndGet();
        lastPacketTime.set(System.currentTimeMillis());

        if (iPcaMatrix == null || iDctMatrix == null) return;

        byte header = packet[0];

        // Check if packet data blocks are all zeros (silence)
        boolean allZero = true;
        for (int i = 1; i <= BLOCK_NUM * BLOCK_BYTE && allZero; i++) {
            if (packet[i] != 0) allZero = false;
        }
        if (allZero) {
            dbgSilenceCount++;
            if (dbgSilenceCount % 1000 == 0) {
                Log.i(TAG, "Stats: silence=" + dbgSilenceCount + " audio=" + dbgAudioCount
                    + " maxRawAmp=" + dbgMaxRawAmplitude);
            }
            // DON'T feed silence — just skip. STT handles gaps fine.
            return;
        }

        dbgAudioCount++;
        if (dbgAudioCount <= 5) {
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < Math.min(50, packet.length); i++) {
                hex.append(String.format("%02x", packet[i] & 0xFF));
            }
            Log.i(TAG, "AUDIO#" + dbgAudioCount + " hdr=0x"
                + String.format("%02x", header & 0xFF) + " hex=" + hex);
        }

        // Decode 3 blocks
        for (int b = 0; b < BLOCK_NUM; b++) {
            int blockOffset = 1 + b * BLOCK_BYTE;
            float[] raw = decodePcaBlock(packet, blockOffset);
            if (raw == null) continue;

            // Track raw amplitude for diagnostics
            float blockMax = 0;
            for (float s : raw) {
                float a = Math.abs(s);
                if (a > blockMax) blockMax = a;
            }
            if (blockMax > dbgMaxRawAmplitude) dbgMaxRawAmplitude = blockMax;

            if (dbgAudioCount <= 5 && b == 0) {
                Log.i(TAG, "Block0: len=" + raw.length + " max=" + blockMax
                    + " [" + raw[0] + "," + raw[1] + "," + raw[2] + ","
                    + raw[3] + "," + raw[4] + "]");
            }

            // Scale to [-1, 1] range for STT.
            // The PCA decode output is in a small range due to int8 quantization.
            // Empirically scale up to make speech audible to the recognizer.
            // We'll use automatic gain: normalize so blockMax maps to ~0.5
            if (blockMax > 0.0001f) {
                float gain = 0.5f / blockMax;
                // Clamp gain to avoid amplifying noise too much
                if (gain > 200.0f) gain = 200.0f;
                for (int i = 0; i < raw.length; i++) {
                    raw[i] = raw[i] * gain;
                }
            }

            if (audioSourceActive.get()) {
                audioQueue.offer(raw);
            }
        }

        if (dbgAudioCount % 100 == 0) {
            Log.i(TAG, "Stats: silence=" + dbgSilenceCount + " audio=" + dbgAudioCount
                + " maxRawAmp=" + dbgMaxRawAmplitude + " queueSize=" + audioQueue.size());
        }
    }

    /**
     * Decode one 80-byte PCA block → 255 audio samples.
     * Block: [47 bytes PCA coefficients] [33 bytes sign bits]
     *
     * 1. PCA bytes (int8) / 128 → compressed spectrum (47 floats)
     * 2. compressed × iPCA (47×257) → spectrum (257 floats)
     * 3. Apply absolute value, restore signs from sign bits
     * 4. spectrum × iDCT (257×257) → 257 audio samples
     * 5. Drop first sample → 256 samples (firmware uses 255, we use 256)
     */
    private float[] decodePcaBlock(byte[] packet, int offset) {
        // Step 1: Extract PCA coefficients
        float[] compressed = new float[PCA_SIZE];
        for (int i = 0; i < PCA_SIZE; i++) {
            compressed[i] = ((byte) packet[offset + i]) / 128.0f;
        }

        // Step 2: Inverse PCA — compressed(1x47) × iPCA(47x257) → spectrum(1x257)
        float[] spectrum = new float[DCT_SIZE];
        for (int k = 0; k < DCT_SIZE; k++) {
            float sum = 0;
            for (int j = 0; j < PCA_SIZE; j++) {
                sum += compressed[j] * iPcaMatrix[j][k];
            }
            spectrum[k] = Math.abs(sum);
        }

        // Step 3: Apply signs from sign bits
        int signOffset = offset + PCA_SIZE;
        int bitIdx = 0;
        for (int i = 0; i < SIGN_BYTE && bitIdx < DCT_SIZE; i++) {
            int signByte = packet[signOffset + i] & 0xFF;
            for (int j = 0; j < 8 && bitIdx < DCT_SIZE; j++) {
                if ((signByte & (1 << j)) != 0) {
                    spectrum[bitIdx] = -spectrum[bitIdx];
                }
                bitIdx++;
            }
        }

        // Step 4: Inverse DCT — spectrum(1x257) × iDCT(257x257) → audio(1x257)
        float[] audio = new float[DCT_SIZE];
        for (int n = 0; n < DCT_SIZE; n++) {
            float sum = 0;
            for (int k = 0; k < DCT_SIZE; k++) {
                sum += spectrum[k] * iDctMatrix[n][k];
            }
            audio[n] = sum;
        }

        // Step 5: Drop first sample (per firmware convention), return 256 samples
        float[] result = new float[DCT_SIZE - 1];
        System.arraycopy(audio, 1, result, 0, DCT_SIZE - 1);
        return result;
    }

    // ==== Opus decode pipeline ====

    private void initOpusDecoder() throws Exception {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS,
            SAMPLE_RATE, 1);
        byte[] csd0 = new byte[] {
            'O', 'p', 'u', 's', 'H', 'e', 'a', 'd',
            1, 1, 0x00, 0x00,
            (byte)(SAMPLE_RATE & 0xFF), (byte)((SAMPLE_RATE >> 8) & 0xFF), 0x00, 0x00,
            0x00, 0x00, 0
        };
        byte[] csd1 = new byte[] {
            'O', 'p', 'u', 's', 'T', 'a', 'g', 's',
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        };
        ByteBuffer csd2Buf = ByteBuffer.allocate(8);
        csd2Buf.order(ByteOrder.nativeOrder());
        csd2Buf.putLong(80000000L);
        csd2Buf.flip();

        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1));
        format.setByteBuffer("csd-2", csd2Buf);

        opusDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
        opusDecoder.configure(format, null, null, 0);
        opusDecoder.start();
        Log.i(TAG, "Opus MediaCodec decoder initialized");
    }

    private void releaseOpusDecoder() {
        if (opusDecoder != null) {
            try { opusDecoder.stop(); opusDecoder.release(); }
            catch (Exception e) { Log.w(TAG, "Opus decoder release error", e); }
            opusDecoder = null;
        }
    }

    private float[] decodeOpusFrame(byte[] frame) {
        if (opusDecoder == null) return null;
        try {
            int inIdx = opusDecoder.dequeueInputBuffer(5000);
            if (inIdx < 0) return null;
            ByteBuffer inBuf = opusDecoder.getInputBuffer(inIdx);
            inBuf.clear();
            inBuf.put(frame);
            opusDecoder.queueInputBuffer(inIdx, 0, frame.length, 0, 0);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outIdx = opusDecoder.dequeueOutputBuffer(info, 5000);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outIdx = opusDecoder.dequeueOutputBuffer(info, 5000);
            }
            if (outIdx >= 0) {
                ByteBuffer outBuf = opusDecoder.getOutputBuffer(outIdx);
                int sampleCount = info.size / 2;
                float[] samples = new float[sampleCount];
                outBuf.position(info.offset);
                for (int i = 0; i < sampleCount; i++) {
                    samples[i] = outBuf.getShort() / 32768.0f;
                }
                opusDecoder.releaseOutputBuffer(outIdx, false);
                return samples;
            }
        } catch (Exception e) {
            Log.w(TAG, "Opus decode error", e);
        }
        return null;
    }

    private void handleOpusPacket(byte[] packet) {
        packetsReceived.incrementAndGet();
        lastPacketTime.set(System.currentTimeMillis());

        byte[] micFrame = new byte[OPUS_FRAME_SIZE];
        byte[] spkFrame = new byte[OPUS_FRAME_SIZE];
        System.arraycopy(packet, 0, micFrame, 0, OPUS_FRAME_SIZE);
        System.arraycopy(packet, OPUS_FRAME_SIZE, spkFrame, 0, OPUS_FRAME_SIZE);

        boolean micActive = false, spkActive = false;
        for (int i = 0; i < OPUS_FRAME_SIZE; i++) {
            if (micFrame[i] != 0) { micActive = true; break; }
        }
        for (int i = 0; i < OPUS_FRAME_SIZE; i++) {
            if (spkFrame[i] != 0) { spkActive = true; break; }
        }
        if (!micActive && !spkActive) return;

        float[] micPcm = micActive ? decodeOpusFrame(micFrame) : null;
        float[] spkPcm = spkActive ? decodeOpusFrame(spkFrame) : null;

        float[] mixed;
        if (micPcm != null && spkPcm != null) {
            int len = Math.max(micPcm.length, spkPcm.length);
            mixed = new float[len];
            for (int i = 0; i < len; i++) {
                float m = (i < micPcm.length) ? micPcm[i] : 0;
                float s = (i < spkPcm.length) ? spkPcm[i] : 0;
                mixed[i] = Math.max(-1.0f, Math.min(1.0f, m + s));
            }
        } else if (micPcm != null) {
            mixed = micPcm;
        } else if (spkPcm != null) {
            mixed = spkPcm;
        } else {
            return;
        }

        if (audioSourceActive.get()) {
            audioQueue.offer(mixed);
        }
    }

    // ==== Cleanup ====

    private void handleDisconnect() {
        state = State.DISCONNECTED;
        audioSourceActive.set(false);
    }

    private void cleanup() {
        audioSourceActive.set(false);
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception e) { /* ignore */ }
            try { gatt.close(); } catch (Exception e) { /* ignore */ }
            gatt = null;
        }
        releaseOpusDecoder();
        audioQueue.clear();
        audioMode = "none";
        state = State.DISCONNECTED;
    }

    public void release() {
        cleanup();
    }

    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String extractText(String json) {
        if (json == null) return "";
        int idx = json.indexOf("\"text\":\"");
        if (idx < 0) return "";
        int start = idx + 8;
        int end = json.indexOf("\"", start);
        while (end > 0 && json.charAt(end - 1) == '\\') {
            end = json.indexOf("\"", end + 1);
        }
        if (end < 0) return "";
        return json.substring(start, end);
    }
}
