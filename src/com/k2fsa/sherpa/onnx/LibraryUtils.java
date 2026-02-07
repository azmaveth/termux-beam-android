package com.k2fsa.sherpa.onnx;

import java.io.File;

/**
 * Native library loader customized for Android (BeamApp).
 * Call setNativeLibDir() before using any sherpa-onnx API classes.
 */
public class LibraryUtils {
    private static String nativeLibDir = null;
    private static boolean loaded = false;

    /** Set the directory containing native .so files (call from SpeechEngine.init) */
    public static void setNativeLibDir(String dir) {
        nativeLibDir = dir;
    }

    public static synchronized void load() {
        if (loaded) return;

        if (nativeLibDir == null) {
            throw new RuntimeException("LibraryUtils.setNativeLibDir() must be called before using sherpa-onnx");
        }

        // Load onnxruntime first (dependency)
        File ortLib = new File(nativeLibDir, "libonnxruntime.so");
        if (ortLib.exists()) {
            System.load(ortLib.getAbsolutePath());
        }

        // Load sherpa-onnx C API (dependency of JNI)
        File cApiLib = new File(nativeLibDir, "libsherpa-onnx-c-api.so");
        if (cApiLib.exists()) {
            System.load(cApiLib.getAbsolutePath());
        }

        // Load sherpa-onnx CXX API if present
        File cxxApiLib = new File(nativeLibDir, "libsherpa-onnx-cxx-api.so");
        if (cxxApiLib.exists()) {
            System.load(cxxApiLib.getAbsolutePath());
        }

        // Load the JNI bridge
        File jniLib = new File(nativeLibDir, "libsherpa-onnx-jni.so");
        if (!jniLib.exists()) {
            throw new RuntimeException("libsherpa-onnx-jni.so not found in " + nativeLibDir);
        }
        System.load(jniLib.getAbsolutePath());

        loaded = true;
    }

    static boolean isAndroid() {
        return true;
    }
}
