#!/bin/bash
#
# Build the BeamApp APK. Dual-mode:
#
#   Termux (on the phone, original workflow)
#     Uses Termux-packaged tools (aapt2, apksigner, r8.jar, ...) and the
#     on-device staged artifacts. Behavior unchanged from the original
#     script; run ./prepare_assets.sh first when runtime/models changed.
#
#   Host (macOS/Linux with the Android SDK)
#     Uses SDK build-tools (aapt2, d8, zipalign, apksigner) + JDK 11+.
#     Device-built artifacts (BEAM runtime .so's, sherpa-onnx libs, model
#     assets, LiteRT jars, debug keystore) must be synced from the phone
#     once via ./sync_artifacts.sh <phone-ip> — they are produced by
#     prepare_assets.sh ON the phone and only change when the phone's
#     erlang/sherpa/models change. If erlc is on PATH, erlang_src/*.erl is
#     recompiled into the assets (warns when the local ERTS major differs
#     from the device's). Env overrides: ANDROID_SDK_ROOT, KS (keystore),
#     SKIP_ASSET_CHECK=1, INSTALL=1 (adb install -r after build).
#
set -e

# ---------- Environment detection ----------
if [[ -n "${TERMUX_VERSION:-}" || -d "/data/data/com.termux/files/usr" ]]; then
    MODE=termux
else
    MODE=host
fi

if [[ "$MODE" == termux ]]; then
    exec > /data/data/com.termux/files/home/BeamApp/build.log 2>&1
    # Mirror the log to shared storage on exit, success or failure. Termux's
    # home is under its own uid, so nothing else on the device — including the
    # BeamApp itself — can read build.log there. /sdcard is the one filesystem
    # both uids can see, which makes a failed build diagnosable without having
    # to hand-copy the log every time.
    trap 'cp -f /data/data/com.termux/files/home/BeamApp/build.log \
              /sdcard/beamapp-build.log 2>/dev/null || true' EXIT
    set -x
    export PATH="/data/data/com.termux/files/usr/bin:$PATH"
    PROJECT="/data/data/com.termux/files/home/BeamApp"
    ANDROID_JAR="/data/data/com.termux/files/home/HelloWorld/build/android-13/android.jar"
    AAPT2=aapt2
    ZIPALIGN=zipalign
    APKSIGNER=apksigner
    DEX() { java -cp /data/data/com.termux/files/home/tools/r8.jar com.android.tools.r8.D8 "$@"; }
    KS="${KS:-/data/data/com.termux/files/home/.debug.keystore}"
    KS_ALIAS=debug
else
    PROJECT="$(cd "$(dirname "$0")" && pwd)"
    exec > >(tee "$PROJECT/build.log") 2>&1
    set -x

    SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -z "$SDK" ]]; then
        for cand in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
            if [[ -d "$cand" ]]; then SDK="$cand"; break; fi
        done
    fi
    if [[ -z "$SDK" || ! -d "$SDK" ]]; then
        echo "ERROR: Android SDK not found — set ANDROID_SDK_ROOT"
        exit 1
    fi

    BT="$(ls -d "$SDK/build-tools"/*/ 2>/dev/null | sort -V | tail -1)"
    BT="${BT%/}"
    if [[ -z "$BT" ]]; then
        echo "ERROR: no build-tools in $SDK — install with: sdkmanager 'build-tools;34.0.0'"
        exit 1
    fi

    # android.jar: prefer the manifest target (API 33), else the newest installed
    if [[ -f "$SDK/platforms/android-33/android.jar" ]]; then
        ANDROID_JAR="$SDK/platforms/android-33/android.jar"
    else
        ANDROID_JAR="$(ls "$SDK/platforms"/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
    fi
    if [[ -z "$ANDROID_JAR" || ! -f "$ANDROID_JAR" ]]; then
        echo "ERROR: no platform android.jar — install with: sdkmanager 'platforms;android-33'"
        exit 1
    fi

    AAPT2="$BT/aapt2"
    ZIPALIGN="$BT/zipalign"
    APKSIGNER="$BT/apksigner"
    DEX() { "$BT/d8" "$@"; }

    command -v javac >/dev/null || { echo "ERROR: javac not found (need JDK 11+)"; exit 1; }
    command -v zip   >/dev/null || { echo "ERROR: zip not found"; exit 1; }

    # Keystore: prefer the phone's debug keystore (signature continuity —
    # an APK signed with a different key cannot update the installed app);
    # otherwise generate a local one.
    if [[ -n "${KS:-}" ]]; then
        KS_ALIAS="${KS_ALIAS:-debug}"
    elif [[ -f "$PROJECT/build/phone-debug.keystore" ]]; then
        KS="$PROJECT/build/phone-debug.keystore"
        KS_ALIAS=debug
    else
        KS="$PROJECT/build/debug.keystore"
        KS_ALIAS=debug
        if [[ ! -f "$KS" ]]; then
            echo "NOTE: generating a NEW debug keystore."
            echo "      Installing over a phone-built APK will fail with a signature"
            echo "      mismatch — sync the phone keystore (./sync_artifacts.sh) or"
            echo "      uninstall the app once (app-private data is lost)."
            mkdir -p "$PROJECT/build"
            keytool -genkeypair -keystore "$KS" -alias "$KS_ALIAS" \
                -storepass android -keypass android -keyalg RSA -keysize 2048 \
                -validity 10000 -dname "CN=BeamApp Debug"
        fi
    fi

    # Preflight: artifacts only prepare_assets.sh on the phone can produce.
    MISSING=""
    for so in libbeam_vm.so liberlexec.so libepmd.so \
              libsherpa-onnx-jni.so libsherpa-onnx-c-api.so libonnxruntime.so; do
        [[ -f "$PROJECT/build/lib/arm64-v8a/$so" ]] || MISSING="$MISSING lib/$so"
    done
    for d in assets/erlang assets/stt-model assets/tts-model; do
        [[ -d "$PROJECT/$d" ]] || MISSING="$MISSING $d"
    done
    ls "$PROJECT/build/litertlm/classpath/"*.jar >/dev/null 2>&1 \
        || MISSING="$MISSING litertlm-jars(GemmaEngine-needs-them-to-compile)"
    if [[ -n "$MISSING" && -z "${SKIP_ASSET_CHECK:-}" ]]; then
        echo "ERROR: device-built artifacts missing:$MISSING"
        echo "Run: ./sync_artifacts.sh <phone-ip>   (SKIP_ASSET_CHECK=1 to try anyway)"
        exit 1
    fi

fi

# Recompile the Erlang bridge modules so erlang_src edits are packaged.
#
# This runs in BOTH modes. It used to live inside the host-only branch above,
# which meant a Termux (on-device) build silently shipped whatever .beam files
# happened to already be in assets/ — erlang_src edits never reached the
# device, with no error and no warning, and in termux mode stdout is
# redirected to build.log so nothing was visible on the terminal either.
if command -v erlc >/dev/null; then
    DEV_ERTS="$(cat "$PROJECT/assets/erlang/erts_version" 2>/dev/null || echo unknown)"
    LOCAL_ERTS="$(erl -noshell -eval 'io:put_chars(erlang:system_info(version)), halt().' 2>/dev/null || echo unknown)"
    if [[ "${DEV_ERTS%%.*}" != "${LOCAL_ERTS%%.*}" ]]; then
        echo "WARNING: local ERTS $LOCAL_ERTS vs device ERTS $DEV_ERTS —"
        echo "         .beam files compiled by a NEWER major may not load on the"
        echo "         device. Match OTP majors (brew install erlang@NN)."
    fi
    EBIN="$PROJECT/assets/erlang/lib/android/ebin"
    mkdir -p "$EBIN"
    erlc -o "$EBIN" "$PROJECT/erlang_src/"*.erl
    echo "Erlang bridge modules recompiled into $EBIN"
else
    echo "WARNING: erlc not found — packaging previously compiled .beam files;"
    echo "         erlang_src edits are NOT included in this APK."
fi

BUILD="$PROJECT/build"
GEN="$BUILD/gen"
OBJ="$BUILD/obj"
APK_DIR="$BUILD/apk"
COMPILED_RES="$BUILD/compiled_res"

echo "=== Cleaning build artifacts ==="
rm -rf "$GEN" "$OBJ" "$APK_DIR" "$COMPILED_RES" "$BUILD/classes.dex"
mkdir -p "$GEN" "$OBJ" "$APK_DIR" "$COMPILED_RES"

echo "=== Step 1: Compile resources ==="
"$AAPT2" compile --dir "$PROJECT/res" -o "$COMPILED_RES/"

echo "=== Step 2: Link resources ==="
"$AAPT2" link \
    -I "$ANDROID_JAR" \
    --manifest "$PROJECT/AndroidManifest.xml" \
    --java "$GEN" \
    -A "$PROJECT/assets" \
    -o "$APK_DIR/beamapp-unsigned.apk" \
    "$COMPILED_RES"/*.flat

echo "=== Step 3: Compile Java ==="
# All sources under src/ (sherpa bindings + app). Using find instead of an
# explicit list so new files (VadSegmenter, SpeakerId, ...) are picked up.
ALL_JAVA=$(find "$PROJECT/src" -name "*.java")
# LiteRT-LM + Kotlin/Coroutines classpath (staged by download_litertlm.sh)
LITERTLM_CP=""
if [[ -d "$BUILD/litertlm/classpath" ]]; then
    for jar in "$BUILD/litertlm/classpath"/*.jar; do
        [[ -f "$jar" ]] && LITERTLM_CP="$LITERTLM_CP:$jar"
    done
fi
javac \
    -source 11 -target 11 \
    -classpath "$ANDROID_JAR$LITERTLM_CP" \
    -d "$OBJ" \
    "$GEN/com/example/beamapp/R.java" \
    $ALL_JAVA

echo "=== Step 4: DEX ==="
# Dex both our compiled classes AND the LiteRT-LM/Kotlin library jars, so all
# of their classes land in classes.dex. d8 accepts a mix of .class files and
# .jar inputs and dedupes sensibly.
find "$OBJ" -name "*.class" > "$BUILD/classlist.txt"
LITERTLM_JARS=""
if [[ -d "$BUILD/litertlm/classpath" ]]; then
    for jar in "$BUILD/litertlm/classpath"/*.jar; do
        [[ -f "$jar" ]] && LITERTLM_JARS="$LITERTLM_JARS $jar"
    done
fi
DEX \
    --min-api 26 \
    --lib "$ANDROID_JAR" \
    --output "$BUILD/" \
    $(cat "$BUILD/classlist.txt") \
    $LITERTLM_JARS

echo "=== Step 5: Package APK ==="
cp "$APK_DIR/beamapp-unsigned.apk" "$APK_DIR/beamapp-tmp.apk"
cd "$BUILD"
# Add DEX
zip -j "$APK_DIR/beamapp-tmp.apk" classes.dex
# Add native libraries UNCOMPRESSED (-0) — required by Android
zip -0 "$APK_DIR/beamapp-tmp.apk" lib/arm64-v8a/*.so
cd "$PROJECT"

echo "=== Step 6: Zipalign ==="
"$ZIPALIGN" -f 4 "$APK_DIR/beamapp-tmp.apk" "$APK_DIR/beamapp.apk"

echo "=== Step 7: Sign ==="
"$APKSIGNER" sign \
    --ks "$KS" \
    --ks-key-alias "$KS_ALIAS" \
    --ks-pass pass:android \
    --key-pass pass:android \
    "$APK_DIR/beamapp.apk"

if [[ "$MODE" == termux ]]; then
    echo ""
    echo "=== Copying to /sdcard ==="
    cp "$APK_DIR/beamapp.apk" /sdcard/beamapp.apk
fi

echo ""
echo "=== BUILD SUCCESSFUL ==="
ls -lh "$APK_DIR/beamapp.apk"
if [[ "$MODE" == termux ]]; then
    ls -lh /sdcard/beamapp.apk
fi
unzip -l "$APK_DIR/beamapp.apk" | grep -E "\.so|classes\.dex|assets" | head -20

if [[ "$MODE" == host ]]; then
    echo ""
    if [[ -n "${INSTALL:-}" ]] && command -v adb >/dev/null; then
        echo "=== Installing via adb ==="
        adb install -r "$APK_DIR/beamapp.apk"
    else
        echo "Install with: adb install -r $APK_DIR/beamapp.apk"
    fi
fi
echo ""
echo "---DONE---"
