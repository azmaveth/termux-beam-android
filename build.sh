#!/bin/bash
exec > /data/data/com.termux/files/home/BeamApp/build.log 2>&1
set -ex

export PATH="/data/data/com.termux/files/usr/bin:$PATH"

PROJECT="/data/data/com.termux/files/home/BeamApp"
ANDROID_JAR="/data/data/com.termux/files/home/HelloWorld/build/android-13/android.jar"
BUILD="$PROJECT/build"
GEN="$BUILD/gen"
OBJ="$BUILD/obj"
APK_DIR="$BUILD/apk"
COMPILED_RES="$BUILD/compiled_res"

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
# Collect all sherpa-onnx Java API source files
SHERPA_JAVA=$(find "$PROJECT/src/com/k2fsa" -name "*.java" 2>/dev/null)
javac \
    -source 11 -target 11 \
    -classpath "$ANDROID_JAR" \
    -d "$OBJ" \
    "$GEN/com/example/beamapp/R.java" \
    $SHERPA_JAVA \
    "$PROJECT/src/com/example/beamapp/SpeechEngine.java" \
    "$PROJECT/src/com/example/beamapp/PhoneAPI.java" \
    "$PROJECT/src/com/example/beamapp/BuddieService.java" \
    "$PROJECT/src/com/example/beamapp/BridgeServer.java" \
    "$PROJECT/src/com/example/beamapp/ScreenService.java" \
    "$PROJECT/src/com/example/beamapp/BootReceiver.java" \
    "$PROJECT/src/com/example/beamapp/BeamService.java" \
    "$PROJECT/src/com/example/beamapp/MainActivity.java"

echo "=== Step 4: DEX ==="
# Collect all .class files from both packages
find "$OBJ" -name "*.class" > "$BUILD/classlist.txt"
java -cp /data/data/com.termux/files/home/tools/r8.jar com.android.tools.r8.D8 --min-api 26 --output "$BUILD/" $(cat "$BUILD/classlist.txt")

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
apksigner sign \
    --ks "/data/data/com.termux/files/home/.debug.keystore" \
    --ks-key-alias debug \
    --ks-pass pass:android \
    --key-pass pass:android \
    "$APK_DIR/beamapp.apk"

echo ""
echo "=== Copying to /sdcard ==="
cp "$APK_DIR/beamapp.apk" /sdcard/beamapp.apk

echo ""
echo "=== BUILD SUCCESSFUL ==="
ls -lh "$APK_DIR/beamapp.apk"
ls -lh /sdcard/beamapp.apk
unzip -l "$APK_DIR/beamapp.apk" | grep -E "\.so|classes\.dex|assets" | head -20
echo ""
echo "---DONE---"
