#!/bin/bash
#
# sync_artifacts.sh — Pull device-built artifacts from the phone so the APK
# can be built on a host machine (see build.sh host mode).
#
# What it pulls (all produced ON the phone by prepare_assets.sh /
# download_litertlm.sh, and only changing when the phone's erlang, sherpa,
# or models change):
#   BeamApp/build/lib/       — BEAM runtime + sherpa-onnx native .so's
#   BeamApp/build/litertlm/  — LiteRT-LM jars (GemmaEngine compile dep)
#   BeamApp/assets/          — erlang runtime, STT/TTS/punct/SenseVoice/
#                              diarization models, PCA matrices (~300 MB
#                              first sync; incremental after with rsync)
#   ~/.debug.keystore        — phone debug keystore, so host-built APKs
#                              keep the installed app's signature
#
# Usage: ./sync_artifacts.sh <phone-ip>
# Env:   PHONE_SSH_PORT (default 8022 — Termux sshd; matches
#        cluster/deploy-to-phone.sh conventions)
#
set -euo pipefail

PHONE_IP="${1:?usage: $0 <phone-ip>}"
PORT="${PHONE_SSH_PORT:-8022}"
PROJECT="$(cd "$(dirname "$0")" && pwd)"
REMOTE="BeamApp"

mkdir -p "$PROJECT/build"

if command -v rsync >/dev/null \
        && ssh -p "$PORT" "$PHONE_IP" "command -v rsync" >/dev/null 2>&1; then
    echo "=== rsync mode ==="
    RS() { rsync -av --delete -e "ssh -p $PORT" "$@"; }
    RS "$PHONE_IP:$REMOTE/build/lib/" "$PROJECT/build/lib/"
    RS "$PHONE_IP:$REMOTE/assets/"    "$PROJECT/assets/"
    if ssh -p "$PORT" "$PHONE_IP" "[ -d $REMOTE/build/litertlm ]"; then
        RS "$PHONE_IP:$REMOTE/build/litertlm/" "$PROJECT/build/litertlm/"
    else
        echo "NOTE: no litertlm on phone — Gemma jars unavailable (build will complain)"
    fi
else
    echo "=== scp mode (no rsync on one side — full copy each time) ==="
    scp -P "$PORT" -r "$PHONE_IP:$REMOTE/build/lib" "$PROJECT/build/"
    scp -P "$PORT" -r "$PHONE_IP:$REMOTE/assets"    "$PROJECT/"
    scp -P "$PORT" -r "$PHONE_IP:$REMOTE/build/litertlm" "$PROJECT/build/" \
        || echo "NOTE: no litertlm on phone — Gemma jars unavailable"
fi

echo "=== Keystore (signature continuity for adb install -r) ==="
scp -P "$PORT" "$PHONE_IP:.debug.keystore" "$PROJECT/build/phone-debug.keystore" \
    || echo "NOTE: no phone keystore found — build.sh will generate a local one
      (first install over a phone-built APK will then need an uninstall)"

echo ""
echo "=== Sync complete ==="
du -sh "$PROJECT/build/lib" "$PROJECT/assets" 2>/dev/null || true
echo "Now build with: ./build.sh   (and adb install -r build/apk/beamapp.apk)"
