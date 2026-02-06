#!/bin/bash
# Build BeamApp APK in Termux
# Usage: bash build.sh
set -e

PROJECT="$(cd "$(dirname "$0")" && pwd)"
BUILD="$PROJECT/build"
GEN="$BUILD/gen"
OBJ="$BUILD/obj"
APK_DIR="$BUILD/apk"
COMPILED_RES="$BUILD/compiled_res"

# Auto-detect android.jar location
if [ -z "$ANDROID_JAR" ]; then
    ANDROID_JAR="$(find "$HOME" -name android.jar -path "*/android-*/android.jar" 2>/dev/null | head -1)"
fi
if [ ! -f "$ANDROID_JAR" ]; then
    echo "ERROR: android.jar not found. Set ANDROID_JAR or run setup.sh first."
    exit 1
fi

# Keystore
KEYSTORE="${KEYSTORE:-$HOME/.debug.keystore}"
if [ ! -f "$KEYSTORE" ]; then
    echo "Creating debug keystore..."
    keytool -genkeypair -keystore "$KEYSTORE" -alias debug \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=Debug,O=Debug,C=US"
fi

echo "=== Cleaning build artifacts ==="
rm -rf "$GEN" "$OBJ" "$APK_DIR" "$COMPILED_RES" "$BUILD/classes.dex"
mkdir -p "$GEN" "$OBJ" "$APK_DIR" "$COMPILED_RES"

echo "=== Step 1: Compile resources ==="
aapt2 compile --dir "$PROJECT/res" -o "$COMPILED_RES/"

echo "=== Step 2: Link resources ==="
aapt2 link \
    -I "$ANDROID_JAR" \
    --manifest "$PROJECT/AndroidManifest.xml" \
    --java "$GEN" \
    -A "$PROJECT/assets" \
    -o "$APK_DIR/beamapp-unsigned.apk" \
    "$COMPILED_RES"/*.flat

echo "=== Step 3: Compile Java ==="
javac \
    -source 1.8 -target 1.8 \
    -classpath "$ANDROID_JAR" \
    -d "$OBJ" \
    "$GEN/com/example/beamapp/R.java" \
    "$PROJECT/src/com/example/beamapp/"*.java

echo "=== Step 4: DEX ==="
dx --dex --output="$BUILD/classes.dex" "$OBJ"

echo "=== Step 5: Package APK ==="
cp "$APK_DIR/beamapp-unsigned.apk" "$APK_DIR/beamapp-tmp.apk"
cd "$BUILD"
zip -j "$APK_DIR/beamapp-tmp.apk" classes.dex
zip -0 "$APK_DIR/beamapp-tmp.apk" lib/arm64-v8a/*.so
cd "$PROJECT"

echo "=== Step 6: Zipalign ==="
zipalign -f 4 "$APK_DIR/beamapp-tmp.apk" "$APK_DIR/beamapp.apk"

echo "=== Step 7: Sign ==="
apksigner sign \
    --ks "$KEYSTORE" \
    --ks-key-alias debug \
    --ks-pass pass:android \
    --key-pass pass:android \
    "$APK_DIR/beamapp.apk"

# Copy to /sdcard for easy install
cp "$APK_DIR/beamapp.apk" /sdcard/beamapp.apk 2>/dev/null || true

echo ""
echo "=== BUILD SUCCESSFUL ==="
ls -lh "$APK_DIR/beamapp.apk"
echo ""
echo "Install: termux-open $APK_DIR/beamapp.apk"
