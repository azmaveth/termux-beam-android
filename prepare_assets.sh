#!/bin/bash
# Prepare BEAM runtime files for APK bundling
exec > /data/data/com.termux/files/home/BeamApp/prepare.log 2>&1
set -ex

PROJECT="/data/data/com.termux/files/home/BeamApp"
ERLROOT="/data/data/com.termux/files/usr/lib/erlang"
LIBDIR="$PROJECT/build/lib/arm64-v8a"
ASSETS="$PROJECT/assets/erlang"

# === Auto-detect installed OTP component versions ===
# Picks up whatever Termux currently has, so `pkg upgrade erlang` is enough
# to update — no manual edits to this script.
detect_one() {
    local pattern="$1"
    local found
    found=$(ls -d "$ERLROOT"/$pattern 2>/dev/null | sort -V | tail -1)
    [[ -n "$found" ]] || { echo "ERROR: no match for $pattern in $ERLROOT" >&2; exit 1; }
    basename "$found"
}

ERTS_DIR=$(detect_one "erts-*")
KERNEL_DIR=$(detect_one "lib/kernel-*")
STDLIB_DIR=$(detect_one "lib/stdlib-*")
COMPILER_DIR=$(detect_one "lib/compiler-*")
ERTS_VERSION="${ERTS_DIR#erts-}"
ERTS="$ERLROOT/$ERTS_DIR"

echo "Detected: $ERTS_DIR / $KERNEL_DIR / $STDLIB_DIR / $COMPILER_DIR"

# Clean only what this script manages — leave hex deps (elixir, ecto, exqlite, ...)
# and the libsqlite3_nif.so (managed by update_elixir_deps.sh) in place.
mkdir -p "$LIBDIR" "$ASSETS/bin" "$ASSETS/lib"
rm -f "$ASSETS/erts_version"
rm -rf "$ASSETS/lib/android"
# Remove any stale kernel/stdlib/compiler dirs from previous OTP versions
for stale in "$ASSETS/lib/kernel-"* "$ASSETS/lib/stdlib-"* "$ASSETS/lib/compiler-"*; do
    [[ -d "$stale" ]] && rm -rf "$stale"
done

# === Native libraries (lib/arm64-v8a with lib*.so naming) ===
# beam.smp - the VM itself
cp "$ERTS/bin/beam.smp" "$LIBDIR/libbeam_vm.so"
# erlexec - the launcher
cp "$ERTS/bin/erlexec" "$LIBDIR/liberlexec.so"
# child setup helper
cp "$ERTS/bin/erl_child_setup" "$LIBDIR/liberl_child_setup.so"
# inet helper
cp "$ERTS/bin/inet_gethost" "$LIBDIR/libinet_gethost.so"
# epmd — required for Erlang distribution
cp "$ERTS/bin/epmd" "$LIBDIR/libepmd.so"

# Dependent shared libraries
# Resolve via the unversioned symlink so we always get whatever Termux has
cp -L "/data/data/com.termux/files/usr/lib/libncursesw.so.6" "$LIBDIR/libncursesw_compat.so"
cp -L "/data/data/com.termux/files/usr/lib/libz.so.1"        "$LIBDIR/libz_compat.so"
cp    "/data/data/com.termux/files/usr/lib/libc++_shared.so" "$LIBDIR/libc++_shared.so"
# OpenSSL — needed by crypto/ssl NIFs. Android requires lib*.so naming.
cp -L "/data/data/com.termux/files/usr/lib/libcrypto.so.3"   "$LIBDIR/libcrypto3.so"
cp -L "/data/data/com.termux/files/usr/lib/libssl.so.3"      "$LIBDIR/libssl3.so"

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

# === Minimal OTP libraries (kernel + stdlib + compiler) ===
copy_otp_lib() {
    local dir="$1"
    mkdir -p "$ASSETS/lib/$dir/ebin"
    cp "$ERLROOT/lib/$dir/ebin/"*.beam "$ASSETS/lib/$dir/ebin/"
    cp "$ERLROOT/lib/$dir/ebin/"*.app  "$ASSETS/lib/$dir/ebin/"
}
copy_otp_lib "$KERNEL_DIR"
copy_otp_lib "$STDLIB_DIR"
copy_otp_lib "$COMPILER_DIR"

# Copy ERTS version file
echo "$ERTS_VERSION" > "$ASSETS/erts_version"

# === Compile Erlang bridge modules ===
mkdir -p "$ASSETS/lib/android/ebin"
erlc -o "$ASSETS/lib/android/ebin" "$PROJECT/erlang_src/android.erl"
erlc -o "$ASSETS/lib/android/ebin" "$PROJECT/erlang_src/speech.erl"
erlc -o "$ASSETS/lib/android/ebin" "$PROJECT/erlang_src/gemma.erl"
erlc -o "$ASSETS/lib/android/ebin" "$PROJECT/erlang_src/eval.erl"
erlc -o "$ASSETS/lib/android/ebin" "$PROJECT/erlang_src/cluster.erl"
erlc -o "$ASSETS/lib/android/ebin" "$PROJECT/erlang_src/reconnect.erl"

echo "=== Assets prepared ==="
du -sh "$LIBDIR"
du -sh "$ASSETS"
find "$LIBDIR" -type f | while read f; do echo "$(ls -lh "$f" | awk '{print $5}') $f"; done
echo "---DONE---"
