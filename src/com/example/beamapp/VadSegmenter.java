package com.example.beamapp;

import com.k2fsa.sherpa.onnx.Vad;

/**
 * Shared VAD gating/segmentation for 16 kHz mono float audio.
 *
 * Wraps a sherpa-onnx {@link Vad} with:
 *  - fixed 512-sample windowing (Silero's expected window size),
 *  - pre-roll: a ring of recent un-forwarded silence windows flushed on the
 *    silence→speech transition, so the first word's attack is kept instead
 *    of clipped (VAD needs some energy before it confirms speech),
 *  - hysteresis: a grace period of silence windows still forwarded after
 *    speech, so trailing consonants and short inter-word pauses survive,
 *  - an utterance-end event at the speech→silence transition (after grace).
 *
 * This unifies what used to be two divergent copies of the logic in
 * SpeechEngine (streamFromExternalAudio had pre-roll + hysteresis; the
 * remote-chunking path had neither and clipped word onsets). It also fixes a
 * subtle duplication bug in the old external-audio path: grace windows were
 * both forwarded AND stored in the pre-roll ring, so the next onset re-sent
 * ~320 ms of already-delivered audio. Here the pre-roll ring only ever holds
 * windows that have not been forwarded.
 */
public class VadSegmenter {

    /** Receives gated audio and utterance boundaries. */
    public interface Sink {
        /** A 512-sample window of gated audio (pre-roll, speech, or grace). */
        void onAudio(float[] window);

        /** Speech→silence transition, after the grace period. */
        void onUtteranceEnd();
    }

    /** Silero VAD window size at 16 kHz. */
    public static final int WINDOW = 512;

    /** Default pre-roll: 15 windows ≈ 480 ms of lookback. */
    public static final int DEFAULT_PRE_ROLL_WINDOWS = 15;

    /** Default grace: 10 windows ≈ 320 ms of trailing forwarding. */
    public static final int DEFAULT_GRACE_WINDOWS = 10;

    private final Vad vad;
    private final Sink sink;
    private final int preRollWindows;
    private final int graceWindows;

    private final float[] winBuf = new float[WINDOW];
    private int winPos = 0;

    private final float[][] preRoll;
    private int preRollPos = 0;

    private boolean inSpeech = false;
    private int graceCount = 0;

    private int speechWindows = 0;
    private int silenceWindows = 0;

    public VadSegmenter(Vad vad, Sink sink) {
        this(vad, sink, DEFAULT_PRE_ROLL_WINDOWS, DEFAULT_GRACE_WINDOWS);
    }

    public VadSegmenter(Vad vad, Sink sink, int preRollWindows, int graceWindows) {
        this.vad = vad;
        this.sink = sink;
        this.preRollWindows = preRollWindows;
        this.graceWindows = graceWindows;
        this.preRoll = new float[preRollWindows][];
        vad.reset();
    }

    /** Feed the first {@code length} samples of {@code chunk}. */
    public void accept(float[] chunk, int length) {
        int pos = 0;
        while (pos < length) {
            int toCopy = Math.min(length - pos, WINDOW - winPos);
            System.arraycopy(chunk, pos, winBuf, winPos, toCopy);
            winPos += toCopy;
            pos += toCopy;
            if (winPos == WINDOW) {
                processWindow();
                winPos = 0;
            }
        }
    }

    /** Feed an arbitrary-length chunk of samples. */
    public void accept(float[] chunk) {
        accept(chunk, chunk.length);
    }

    private void processWindow() {
        vad.acceptWaveform(winBuf);
        if (vad.isSpeechDetected()) {
            if (!inSpeech) {
                /* Silence→speech: flush pre-roll so the consumer sees the
                 * word attack, not just the already-established vowel. */
                for (int i = 0; i < preRollWindows; i++) {
                    int idx = (preRollPos + i) % preRollWindows;
                    if (preRoll[idx] != null) {
                        sink.onAudio(preRoll[idx]);
                        preRoll[idx] = null;
                    }
                }
                inSpeech = true;
            }
            speechWindows++;
            graceCount = 0;
            sink.onAudio(winBuf.clone());
        } else {
            silenceWindows++;
            boolean forwarded = false;
            if (inSpeech) {
                if (graceCount < graceWindows) {
                    /* Trailing grace — keep forwarding so trailing
                     * consonants and short pauses aren't clipped. */
                    graceCount++;
                    sink.onAudio(winBuf.clone());
                    forwarded = true;
                    if (graceCount == graceWindows) {
                        endUtterance();
                    }
                } else {
                    /* Defensive; normally unreachable. */
                    endUtterance();
                }
            }
            if (!forwarded) {
                /* Only un-forwarded silence goes into the pre-roll ring —
                 * forwarded grace windows must not be re-sent at the next
                 * onset. */
                preRoll[preRollPos] = winBuf.clone();
                preRollPos = (preRollPos + 1) % preRollWindows;
            }
        }
    }

    private void endUtterance() {
        inSpeech = false;
        graceCount = 0;
        vad.reset();
        sink.onUtteranceEnd();
    }

    /**
     * Flush a partial trailing window (zero-padded) and close any open
     * utterance. Call once at end of stream so the consumer can emit its
     * final chunk.
     */
    public void flush() {
        if (winPos > 0) {
            for (int i = winPos; i < WINDOW; i++) winBuf[i] = 0;
            vad.acceptWaveform(winBuf);
            if (vad.isSpeechDetected() || inSpeech) {
                speechWindows++;
                sink.onAudio(winBuf.clone());
            } else {
                silenceWindows++;
            }
            winPos = 0;
        }
        if (inSpeech) {
            endUtterance();
        }
    }

    public boolean isInSpeech() { return inSpeech; }

    public int getSpeechWindows() { return speechWindows; }

    public int getSilenceWindows() { return silenceWindows; }
}
