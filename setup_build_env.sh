#!/bin/bash
#
# setup_build_env.sh — one-time bootstrap of the Termux build environment.
#
# build.sh and prepare_assets.sh both assume a pile of things already exist in
# $HOME. On a fresh phone none of them do, and the failure is unhelpful: the
# erlc glob stays literal, or prepare_assets dies on a missing .so. This script
# creates everything they expect.
#
#   ~/HelloWorld/build/android-13/android.jar   SDK stubs to compile against
#   ~/tools/r8.jar                              D8 dexer (build.sh calls D8;
#                                               the `dx` package is NOT enough)
#   ~/.debug.keystore                           APK signing key
#   ~/sherpa-onnx/build/lib/*.so                prebuilt JNI libs, no compiling
#   ~/sherpa-onnx/android-ort/jni/arm64-v8a/    onnxruntime
#   ~/sherpa-models/*                           STT, TTS, VAD, punctuation,
#                                               diarization, speaker-id models
#
# Idempotent: anything already present is left alone, so it is safe to re-run
# after a partial or interrupted setup.
#
# Also stages the per-checkout LiteRT-LM jars via download_litertlm.sh.
#
# After this: ./prepare_assets.sh && ./build.sh
#
set -euo pipefail

if [[ -z "${TERMUX_VERSION:-}" && ! -d /data/data/com.termux/files/usr ]]; then
    echo "ERROR: this bootstraps the on-device Termux build. Run it in Termux."
    echo "       For host builds use sync_artifacts.sh instead."
    exit 1
fi

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOME_DIR="${TERMUX_HOME:-${HOME:-/data/data/com.termux/files/home}}"
SHERPA_HOME="$HOME_DIR/sherpa-onnx"
MODELS="$HOME_DIR/sherpa-models"
TOOLS="$HOME_DIR/tools"
JAR_DIR="$HOME_DIR/HelloWorld/build/android-13"
KS="$HOME_DIR/.debug.keystore"
WORK="${TMPDIR:-$HOME_DIR}/beamapp_setup"

# Pinned so a re-run reproduces the same environment.
SHERPA_VER="v1.13.4"
R8_VER="8.7.18"

mkdir -p "$WORK" "$TOOLS" "$JAR_DIR" "$MODELS" \
         "$SHERPA_HOME/build/lib" "$SHERPA_HOME/android-ort/jni/arm64-v8a"

say()  { echo; echo "=== $* ==="; }
have() { [[ -s "$1" ]]; }

# fetch <url> <dest> — resumable; this link drops often enough to matter
fetch() {
    local url="$1" dest="$2"
    if have "$dest"; then echo "  present: $(basename "$dest")"; return; fi
    echo "  fetching: $(basename "$dest")"
    for _ in $(seq 1 10); do
        curl -fL -C - --retry 5 --retry-delay 3 -o "$dest" "$url" && return 0
        sleep 3
    done
    echo "ERROR: could not download $url"; exit 1
}

say "1/7  Termux packages"
# dx is not used (build.sh calls D8 from r8.jar) but apksigner/aapt2 are.
pkg install -y aapt2 apksigner zip unzip tar bzip2 curl git openjdk-17 erlang cmake
echo "  ok"

say "2/7  android.jar (API 33)"
if have "$JAR_DIR/android.jar"; then
    echo "  present: android.jar"
else
    fetch "https://dl.google.com/android/repository/platform-33_r02.zip" \
          "$WORK/platform-33.zip"
    unzip -o -j "$WORK/platform-33.zip" "android-13/android.jar" -d "$JAR_DIR/"
    echo "  installed: $JAR_DIR/android.jar"
fi

say "3/7  r8.jar (D8 dexer)"
fetch "https://maven.google.com/com/android/tools/r8/$R8_VER/r8-$R8_VER.jar" \
      "$TOOLS/r8.jar"

say "4/7  debug keystore"
if have "$KS"; then
    echo "  present: .debug.keystore"
else
    keytool -genkeypair -keystore "$KS" -alias debug \
        -storepass android -keypass android -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=BeamApp Debug"
    echo "  generated: $KS"
fi

say "5/7  sherpa-onnx prebuilt JNI libs ($SHERPA_VER)"
# Prebuilt, not built from source: compiling sherpa-onnx on-device takes hours.
# prepare_assets.sh reads two different layouts, so map the tarball into both.
if have "$SHERPA_HOME/build/lib/libsherpa-onnx-jni.so"; then
    echo "  present: sherpa .so files"
else
    fetch "https://github.com/k2-fsa/sherpa-onnx/releases/download/$SHERPA_VER/sherpa-onnx-$SHERPA_VER-android.tar.bz2" \
          "$WORK/sherpa-android.tar.bz2"
    tar xjf "$WORK/sherpa-android.tar.bz2" -C "$WORK" ./jniLibs/arm64-v8a
    for so in libsherpa-onnx-c-api.so libsherpa-onnx-cxx-api.so libsherpa-onnx-jni.so; do
        cp "$WORK/jniLibs/arm64-v8a/$so" "$SHERPA_HOME/build/lib/$so"
    done
    cp "$WORK/jniLibs/arm64-v8a/libonnxruntime.so" \
       "$SHERPA_HOME/android-ort/jni/arm64-v8a/libonnxruntime.so"
    echo "  installed 4 .so files"
fi

say "6/7  sherpa models"
# "<release-channel>/<file>"; note the upstream typo in
# speaker-recongition-models — that really is the tag name.
BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download"
ARCHIVES=(
    "asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2"
    "tts-models/kitten-nano-en-v0_2-fp16.tar.bz2"
    "asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
    "punctuation-models/sherpa-onnx-online-punct-en-2024-08-06.tar.bz2"
    "speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
)
PLAIN=(
    "asr-models/silero_vad.onnx"
    "speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
)

for a in "${ARCHIVES[@]}"; do
    f="$(basename "$a")"; d="${f%.tar.bz2}"
    if [[ -d "$MODELS/$d" ]]; then echo "  present: $d"; continue; fi
    fetch "$BASE/$a" "$WORK/$f"
    tar xjf "$WORK/$f" -C "$MODELS"
    echo "  extracted: $d"
done
for p in "${PLAIN[@]}"; do
    fetch "$BASE/$p" "$MODELS/$(basename "$p")"
done

say "7/7  LiteRT-LM jars (per-checkout, not per-machine)"
# These stage into $PROJECT/build/, so unlike everything above they belong to
# this checkout rather than $HOME — a second clone needs its own copy. Omitting
# this step is not a graceful failure: javac reports 32 errors of the form
# "package com.google.ai.edge.litertlm does not exist", which reads like broken
# source rather than a missing dependency.
if ls "$PROJECT/build/litertlm/classpath/"*.jar >/dev/null 2>&1; then
    echo "  present: $(ls "$PROJECT/build/litertlm/classpath/"*.jar | wc -l) jars"
else
    "$PROJECT/download_litertlm.sh"
fi

say "done"
cat <<EOF
  android.jar   $JAR_DIR/android.jar
  r8.jar        $TOOLS/r8.jar
  keystore      $KS
  sherpa libs   $SHERPA_HOME/build/lib
  models        $MODELS  ($(du -sh "$MODELS" 2>/dev/null | cut -f1))
  litertlm      $PROJECT/build/litertlm/classpath

Next, from this checkout:
  ./prepare_assets.sh && ./build.sh

The scripts derive the project root from their own location, so the tree can
live anywhere. The toolchain above is tied to \$HOME (override with TERMUX_HOME);
the LiteRT jars are per-checkout.
EOF
