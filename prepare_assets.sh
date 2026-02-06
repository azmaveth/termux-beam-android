#!/bin/bash
# Prepare BEAM runtime files for APK bundling
# Copies beam.smp, OTP libraries, and dependencies into the build directory
set -e

PROJECT="$(cd "$(dirname "$0")" && pwd)"

# Auto-detect Erlang/OTP installation
ERLROOT="$(erl -noshell -eval 'io:format("~s", [code:root_dir()]), halt().')"
ERTS_VSN="$(erl -noshell -eval 'io:format("~s", [erlang:system_info(version)]), halt().')"
OTP_REL="$(erl -noshell -eval 'io:format("~s", [erlang:system_info(otp_release)]), halt().')"
ERTS="$ERLROOT/erts-$ERTS_VSN"

echo "Detected OTP $OTP_REL (ERTS $ERTS_VSN)"
echo "  Root: $ERLROOT"
echo "  ERTS: $ERTS"

LIBDIR="$PROJECT/build/lib/arm64-v8a"
ASSETS="$PROJECT/assets/erlang"

# Clean
rm -rf "$LIBDIR" "$ASSETS"
mkdir -p "$LIBDIR" "$ASSETS/bin" "$ASSETS/lib"

# === Native libraries (lib/arm64-v8a with lib*.so naming) ===
echo "=== Copying native binaries ==="

# beam.smp - the VM itself
cp "$ERTS/bin/beam.smp" "$LIBDIR/libbeam_vm.so"
# erlexec - the launcher
cp "$ERTS/bin/erlexec" "$LIBDIR/liberlexec.so"
# child setup helper
cp "$ERTS/bin/erl_child_setup" "$LIBDIR/liberl_child_setup.so"
# inet helper
cp "$ERTS/bin/inet_gethost" "$LIBDIR/libinet_gethost.so"

# Dependent shared libraries
TERMUX_LIB="$(dirname "$(which erl)")/../lib"
cp "$TERMUX_LIB/libncursesw.so."* "$LIBDIR/libncursesw_compat.so" 2>/dev/null || \
    cp "$TERMUX_LIB/libncursesw.so" "$LIBDIR/libncursesw_compat.so" 2>/dev/null || true
cp "$TERMUX_LIB/libz.so."* "$LIBDIR/libz_compat.so" 2>/dev/null || \
    cp "$TERMUX_LIB/libz.so" "$LIBDIR/libz_compat.so" 2>/dev/null || true
cp "$TERMUX_LIB/libc++_shared.so" "$LIBDIR/libc++_shared.so" 2>/dev/null || true

# === OTP boot files ===
echo "=== Copying OTP boot files ==="
cp "$ERLROOT/bin/start_clean.boot" "$ASSETS/bin/"
cp "$ERLROOT/bin/no_dot_erlang.boot" "$ASSETS/bin/"

# === OTP libraries (kernel + stdlib + compiler) ===
echo "=== Copying OTP libraries ==="

# Find and copy each required OTP lib
for lib in kernel stdlib compiler; do
    libdir=$(find "$ERLROOT/lib" -maxdepth 1 -name "${lib}-*" -type d | head -1)
    if [ -z "$libdir" ]; then
        echo "WARNING: $lib not found in $ERLROOT/lib"
        continue
    fi
    libname=$(basename "$libdir")
    echo "  $libname"
    mkdir -p "$ASSETS/lib/$libname/ebin"
    cp "$libdir/ebin/"*.beam "$ASSETS/lib/$libname/ebin/"
    cp "$libdir/ebin/"*.app "$ASSETS/lib/$libname/ebin/"
done

# === ERTS version ===
echo "$ERTS_VSN" > "$ASSETS/erts_version"

# === Compile android.erl bridge module ===
echo "=== Compiling android.erl bridge module ==="
mkdir -p "$ASSETS/lib/android/ebin"
erlc -o "$ASSETS/lib/android/ebin" "$PROJECT/erlang_src/android.erl"

echo ""
echo "=== Assets prepared ==="
du -sh "$LIBDIR"
du -sh "$ASSETS"
echo ""
echo "Run 'bash build.sh' to build the APK."
