#!/bin/bash
#
# download_litertlm.sh — Fetch LiteRT-LM + transitive deps and stage them for BeamApp.
#
# LiteRT-LM is the runtime Google's Edge Gallery uses to run Gemma 4 and other
# on-device LLMs. This script pulls the AAR from Google Maven plus its Kotlin and
# Coroutines transitive dependencies, unpacks them into build/litertlm/, and
# stages the arm64 native library into build/lib/arm64-v8a/.
#
# build.sh picks up:
#   - build/litertlm/classpath/*.jar   → added to javac classpath
#   - build/lib/arm64-v8a/*.so         → packaged by aapt2 link
#
# Source of truth: the PKGS array below. To update versions, edit and re-run.
#
set -euo pipefail

PROJECT="/data/data/com.termux/files/home/BeamApp"
STAGE="$PROJECT/build/litertlm"
CLASSPATH_DIR="$STAGE/classpath"
NATIVE_LIB_DIR="$PROJECT/build/lib/arm64-v8a"
WORK_DIR="${WORK_DIR:-/tmp/litertlm_fetch}"

GOOGLE_MAVEN="https://dl.google.com/dl/android/maven2"
CENTRAL="https://repo1.maven.org/maven2"

# === Dep closure ===
# Format: "repo group artifact version ext"
#   repo: google | central
#   ext:  jar | aar
PKGS=(
    "google  com.google.ai.edge.litertlm   litertlm-android             0.10.0   aar"
    "central com.google.code.gson           gson                         2.13.2   jar"
    "central org.jetbrains.kotlin           kotlin-reflect               2.2.21   jar"
    "central org.jetbrains.kotlin           kotlin-stdlib                2.2.21   jar"
    "central org.jetbrains.kotlinx          kotlinx-coroutines-android   1.9.0    jar"
    "central org.jetbrains.kotlinx          kotlinx-coroutines-core-jvm  1.9.0    jar"
)
# ===================

log() { echo "[download_litertlm] $*" >&2; }
die() { log "ERROR: $*"; exit 1; }

command -v curl >/dev/null || die "curl not found"
command -v unzip >/dev/null || die "unzip not found"

mkdir -p "$WORK_DIR" "$CLASSPATH_DIR" "$NATIVE_LIB_DIR"
# Clean only our staging dir so we never leave stale jars around
rm -f "$CLASSPATH_DIR"/*.jar

url_for() {
    local repo="$1" group="$2" artifact="$3" version="$4" ext="$5"
    local group_path="${group//.//}"
    local base
    case "$repo" in
        google)  base="$GOOGLE_MAVEN" ;;
        central) base="$CENTRAL" ;;
        *) die "unknown repo: $repo" ;;
    esac
    echo "$base/$group_path/$artifact/$version/$artifact-$version.$ext"
}

stage_classes_jar() {
    local src="$1" dest_name="$2"
    cp "$src" "$CLASSPATH_DIR/$dest_name"
    log "  → $dest_name ($(du -h "$CLASSPATH_DIR/$dest_name" | cut -f1))"
}

for entry in "${PKGS[@]}"; do
    read -r repo group artifact version ext <<< "$entry"
    url=$(url_for "$repo" "$group" "$artifact" "$version" "$ext")
    local_file="$WORK_DIR/$artifact-$version.$ext"

    log "Fetching $artifact-$version.$ext"
    if [[ ! -f "$local_file" ]]; then
        curl -sS -f -L -o "$local_file" "$url" \
            || die "failed to fetch $url"
    fi

    if [[ "$ext" == "jar" ]]; then
        stage_classes_jar "$local_file" "$artifact-$version.jar"
    else
        # AAR: extract classes.jar and native libs
        local_aar_dir="$WORK_DIR/$artifact-$version-unpacked"
        rm -rf "$local_aar_dir"
        mkdir -p "$local_aar_dir"
        (cd "$local_aar_dir" && unzip -oq "$local_file")

        # classes.jar is always at the root of an AAR (may be missing for pure-res AARs)
        if [[ -f "$local_aar_dir/classes.jar" ]]; then
            stage_classes_jar "$local_aar_dir/classes.jar" "$artifact-$version.jar"
        fi

        # Native libs: only arm64-v8a, never x86_64 or armeabi-v7a
        if [[ -d "$local_aar_dir/jni/arm64-v8a" ]]; then
            for so in "$local_aar_dir/jni/arm64-v8a/"*.so; do
                [[ -f "$so" ]] || continue
                bn=$(basename "$so")
                cp "$so" "$NATIVE_LIB_DIR/$bn"
                log "  → native $bn ($(du -h "$NATIVE_LIB_DIR/$bn" | cut -f1))"
            done
        fi
    fi
done

log ""
log "=== Classpath jars ==="
ls -lh "$CLASSPATH_DIR"/*.jar
log ""
log "=== LiteRT-LM native libs in $NATIVE_LIB_DIR ==="
ls -lh "$NATIVE_LIB_DIR"/liblitertlm*.so 2>/dev/null || log "  (none)"
log ""
log "Done. Run ./build.sh to rebuild the APK with LiteRT-LM."
