#!/bin/bash
# Prepare BEAM runtime files for APK bundling
exec > /data/data/com.termux/files/home/BeamApp/prepare.log 2>&1
set -ex

PROJECT="/data/data/com.termux/files/home/BeamApp"
ERLROOT="/data/data/com.termux/files/usr/lib/erlang"
ERTS="$ERLROOT/erts-16.2"
LIBDIR="$PROJECT/build/lib/arm64-v8a"
ASSETS="$PROJECT/assets/erlang"

# Clean
rm -rf "$LIBDIR" "$ASSETS"
mkdir -p "$LIBDIR" "$ASSETS/bin" "$ASSETS/lib"

# === Native libraries (lib/arm64-v8a with lib*.so naming) ===
# beam.smp - the VM itself
cp "$ERTS/bin/beam.smp" "$LIBDIR/libbeam_vm.so"
# erlexec - the launcher
cp "$ERTS/bin/erlexec" "$LIBDIR/liberlexec.so"
# child setup helper
cp "$ERTS/bin/erl_child_setup" "$LIBDIR/liberl_child_setup.so"
# inet helper
cp "$ERTS/bin/inet_gethost" "$LIBDIR/libinet_gethost.so"

# Dependent shared libraries
cp "/data/data/com.termux/files/usr/lib/libncursesw.so.6.6" "$LIBDIR/libncursesw_compat.so"
cp "/data/data/com.termux/files/usr/lib/libz.so.1.3.1" "$LIBDIR/libz_compat.so"
cp "/data/data/com.termux/files/usr/lib/libc++_shared.so" "$LIBDIR/libc++_shared.so"

# === Sherpa-onnx JNI (native speech engine) ===
SHERPA_HOME="/data/data/com.termux/files/home/sherpa-onnx"
SHERPA_MODEL="/data/data/com.termux/files/home/sherpa-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
TTS_MODEL="/data/data/com.termux/files/home/sherpa-models/kitten-nano-en-v0_2-fp16"
# Onnxruntime shared lib
cp "$SHERPA_HOME/android-ort/jni/arm64-v8a/libonnxruntime.so" "$LIBDIR/libonnxruntime.so"
# C API shared lib (dependency of JNI)
cp "$SHERPA_HOME/build/lib/libsherpa-onnx-c-api.so" "$LIBDIR/libsherpa-onnx-c-api.so"
# C++ API shared lib (dependency of JNI for diarization/offline)
cp "$SHERPA_HOME/build/lib/libsherpa-onnx-cxx-api.so" "$LIBDIR/libsherpa-onnx-cxx-api.so"
# JNI shared lib (the main bridge between Java and C++)
cp "$SHERPA_HOME/build/lib/libsherpa-onnx-jni.so" "$LIBDIR/libsherpa-onnx-jni.so"

# === STT Model files (in assets, extracted at runtime) ===
STT_ASSETS="$PROJECT/assets/stt-model"
rm -rf "$STT_ASSETS"
mkdir -p "$STT_ASSETS"
cp "$SHERPA_MODEL/encoder-epoch-99-avg-1.int8.onnx" "$STT_ASSETS/"
cp "$SHERPA_MODEL/decoder-epoch-99-avg-1.int8.onnx" "$STT_ASSETS/"
cp "$SHERPA_MODEL/joiner-epoch-99-avg-1.int8.onnx" "$STT_ASSETS/"
cp "$SHERPA_MODEL/tokens.txt" "$STT_ASSETS/"
# Silero VAD model
cp "/data/data/com.termux/files/home/sherpa-models/silero_vad.onnx" "$STT_ASSETS/"

# === TTS Model files (KittenTTS nano, in assets, extracted at runtime) ===
TTS_ASSETS="$PROJECT/assets/tts-model"
rm -rf "$TTS_ASSETS"
mkdir -p "$TTS_ASSETS"
cp "$TTS_MODEL/model.fp16.onnx" "$TTS_ASSETS/"
cp "$TTS_MODEL/voices.bin" "$TTS_ASSETS/"
cp "$TTS_MODEL/tokens.txt" "$TTS_ASSETS/"
# espeak-ng-data directory (required for phoneme processing)
cp -r "$TTS_MODEL/espeak-ng-data" "$TTS_ASSETS/"

# === Punctuation model (online punct, ~7MB int8) ===
PUNCT_MODEL="/data/data/com.termux/files/home/sherpa-models/sherpa-onnx-online-punct-en-2024-08-06"
PUNCT_ASSETS="$PROJECT/assets/punct-model"
rm -rf "$PUNCT_ASSETS"
mkdir -p "$PUNCT_ASSETS"
cp "$PUNCT_MODEL/model.int8.onnx" "$PUNCT_ASSETS/"
cp "$PUNCT_MODEL/bpe.vocab" "$PUNCT_ASSETS/"

# === SenseVoice offline STT model (~228MB int8) ===
SENSEVOICE_MODEL="/data/data/com.termux/files/home/sherpa-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
SV_ASSETS="$PROJECT/assets/sensevoice-model"
rm -rf "$SV_ASSETS"
mkdir -p "$SV_ASSETS"
cp "$SENSEVOICE_MODEL/model.int8.onnx" "$SV_ASSETS/"
cp "$SENSEVOICE_MODEL/tokens.txt" "$SV_ASSETS/"

# === Diarization models (segmentation + speaker embedding) ===
DIAR_ASSETS="$PROJECT/assets/diarization"
rm -rf "$DIAR_ASSETS"
mkdir -p "$DIAR_ASSETS"
# Pyannote segmentation model (int8, ~1.5MB)
cp "/data/data/com.termux/files/home/sherpa-models/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx" "$DIAR_ASSETS/segmentation.onnx"
# 3D-Speaker embedding model (~29MB)
cp "/data/data/com.termux/files/home/sherpa-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx" "$DIAR_ASSETS/embedding.onnx"

# === OTP boot files ===
cp "$ERLROOT/bin/start_clean.boot" "$ASSETS/bin/"
cp "$ERLROOT/bin/no_dot_erlang.boot" "$ASSETS/bin/"

# === Minimal OTP libraries (kernel + stdlib only) ===
# Copy kernel .beam files
mkdir -p "$ASSETS/lib/kernel-10.5/ebin"
cp "$ERLROOT/lib/kernel-10.5/ebin/"*.beam "$ASSETS/lib/kernel-10.5/ebin/"
cp "$ERLROOT/lib/kernel-10.5/ebin/"*.app "$ASSETS/lib/kernel-10.5/ebin/"

# Copy stdlib .beam files
mkdir -p "$ASSETS/lib/stdlib-7.2/ebin"
cp "$ERLROOT/lib/stdlib-7.2/ebin/"*.beam "$ASSETS/lib/stdlib-7.2/ebin/"
cp "$ERLROOT/lib/stdlib-7.2/ebin/"*.app "$ASSETS/lib/stdlib-7.2/ebin/"

# Copy compiler (needed for some eval operations)
mkdir -p "$ASSETS/lib/compiler-9.0.4/ebin"
cp "$ERLROOT/lib/compiler-9.0.4/ebin/"*.beam "$ASSETS/lib/compiler-9.0.4/ebin/"
cp "$ERLROOT/lib/compiler-9.0.4/ebin/"*.app "$ASSETS/lib/compiler-9.0.4/ebin/"

# Copy ERTS version file
echo "16.2" > "$ASSETS/erts_version"

# === Compile Erlang bridge modules ===
mkdir -p "$ASSETS/lib/android/ebin"
erlc -o "$ASSETS/lib/android/ebin" "$PROJECT/erlang_src/android.erl"
erlc -o "$ASSETS/lib/android/ebin" "$PROJECT/erlang_src/speech.erl"

echo "=== Assets prepared ==="
du -sh "$LIBDIR"
du -sh "$ASSETS"
find "$LIBDIR" -type f | while read f; do echo "$(ls -lh "$f" | awk '{print $5}') $f"; done
echo "---DONE---"
