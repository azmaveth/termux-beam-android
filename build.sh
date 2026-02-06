#!/bin/bash
# Build the BEAM Runner APK from Termux
# Run setup.sh and prepare_assets.sh first
set -e

PROJECT="$(cd "$(dirname "$0")" && pwd)"
ANDROID_JAR="$HOME/android-sdk/android.jar"
BUILD="$PROJECT/build"
GEN="$BUILD/gen"
OBJ="$BUILD/obj"
APK_DIR="$BUILD/apk"
COMPILED_RES="$BUILD/compiled_res"

if [ ! -f "$ANDROID_JAR" ]; then
    echo "ERROR: android.jar not found. Run setup.sh first."
    exit 1
fi

if [ ! -d "$BUILD/lib/arm64-v8a" ]; then
    echo "ERROR: Native libs not found. Run prepare_assets.sh first."
    exit 1
fi

if [ ! -d "$PROJECT/assets/erlang" ]; then
    echo "ERROR: Erlang assets not found. Run prepare_assets.sh first."
    exit 1
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
    "$PROJECT/src/com/example/beamapp/BridgeServer.java" \
    "$PROJECT/src/com/example/beamapp/BeamService.java" \
    "$PROJECT/src/com/example/beamapp/MainActivity.java"

echo "=== Step 4: DEX ==="
dx --dex --output="$BUILD/classes.dex" "$OBJ"

echo "=== Step 5: Package APK ==="
cp "$APK_DIR/beamapp-unsigned.apk" "$APK_DIR/beamapp-tmp.apk"
cd "$BUILD"
# Add DEX
zip -j "$APK_DIR/beamapp-tmp.apk" classes.dex
# Add native libraries UNCOMPRESSED (-0) — required by Android
zip -0 "$APK_DIR/beamapp-tmp.apk" lib/arm64-v8a/*.so
cd "$PROJECT"

echo "=== Step 6: Zipalign ==="
zipalign -f 4 "$APK_DIR/beamapp-tmp.apk" "$APK_DIR/beamapp.apk"

echo "=== Step 7: Sign ==="
if [ ! -f "$HOME/.debug.keystore" ]; then
    keytool -genkeypair \
        -keystore "$HOME/.debug.keystore" \
        -alias debug \
        -keyalg RSA -keysize 2048 \
        -validity 10000 \
        -storepass android \
        -keypass android \
        -dname "CN=Debug, OU=Debug, O=Debug, L=Debug, ST=Debug, C=US"
fi

apksigner sign \
    --ks "$HOME/.debug.keystore" \
    --ks-key-alias debug \
    --ks-pass pass:android \
    --key-pass pass:android \
    "$APK_DIR/beamapp.apk"

echo ""
echo "=== BUILD SUCCESSFUL ==="
ls -lh "$APK_DIR/beamapp.apk"
echo ""
echo "To install:"
echo "  cp $APK_DIR/beamapp.apk ~/storage/shared/ && termux-open ~/storage/shared/beamapp.apk"
