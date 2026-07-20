// Copyright 2024 Xiaomi Corporation

package com.k2fsa.sherpa.onnx;

/**
 * Java binding for sherpa-onnx's speaker embedding extractor, mirroring the
 * upstream java-api class. The bundled libsherpa-onnx-jni.so is the full JNI
 * build (it already serves diarization, which uses speaker embeddings
 * internally), so these symbols are expected to be present; callers should
 * still guard construction with a catch of Throwable in case a stripped
 * build is ever shipped.
 */
public class SpeakerEmbeddingExtractor {
    private long ptr = 0;

    public SpeakerEmbeddingExtractor(SpeakerEmbeddingExtractorConfig config) {
        LibraryLoader.maybeLoad();
        ptr = newFromFile(config);
        if (ptr == 0) {
            throw new IllegalArgumentException(
                "Invalid SpeakerEmbeddingExtractorConfig: failed to create native extractor");
        }
    }

    public OnlineStream createStream() {
        long p = createStream(ptr);
        return new OnlineStream(p);
    }

    public boolean isReady(OnlineStream stream) {
        return isReady(ptr, stream.getPtr());
    }

    public float[] compute(OnlineStream stream) {
        return compute(ptr, stream.getPtr());
    }

    public int getDim() {
        return dim(ptr);
    }

    @Override
    protected void finalize() throws Throwable {
        release();
    }

    public void release() {
        if (this.ptr == 0) {
            return;
        }
        delete(this.ptr);
        this.ptr = 0;
    }

    private native long newFromFile(SpeakerEmbeddingExtractorConfig config);

    private native void delete(long ptr);

    private native long createStream(long ptr);

    private native boolean isReady(long ptr, long streamPtr);

    private native float[] compute(long ptr, long streamPtr);

    private native int dim(long ptr);
}
