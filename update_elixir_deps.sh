#!/bin/bash
#
# update_elixir_deps.sh — Fetch/compile Elixir & hex deps and stage them into BeamApp assets.
#
# Single source of truth: the DEPS array below. Bump versions there, run this script,
# then run prepare_assets.sh and build.sh.
#
# What it does:
#   1. Creates a temporary mix project with the deps listed below
#   2. Runs `mix deps.get && mix deps.compile`
#   3. For each dep, removes old assets/erlang/lib/<dep>-* dir and copies fresh BEAMs
#   4. Copies the exqlite NIF to build/lib/arm64-v8a/libsqlite3_nif.so
#   5. Rewrites the hardcoded exqlite version in BeamService.java if it changed
#
# Re-runnable. Safe to run for both updates and from-scratch installs.
#
# Prerequisites: Termux packages `elixir` (which pulls in `erlang`) and `hexpm` setup.
#   pkg install elixir
#   mix local.hex --force
#
set -euo pipefail

PROJECT="/data/data/com.termux/files/home/BeamApp"
ASSETS_LIB="$PROJECT/assets/erlang/lib"
NATIVE_LIB_DIR="$PROJECT/build/lib/arm64-v8a"
BEAM_SERVICE="$PROJECT/src/com/example/beamapp/BeamService.java"
WORK_DIR="${WORK_DIR:-/tmp/beamapp_deps}"

# === Single source of truth for hex dep versions ===
# Format: "name version"
DEPS=(
    "telemetry      1.4.0"
    "decimal        2.3.0"
    "db_connection  2.9.0"
    "ecto           3.13.5"
    "ecto_sql       3.13.5"
    "exqlite        0.36.0"
    "ecto_sqlite3   0.22.0"
    "jason          1.4.4"
)
# ===================================================

log() { echo "[update_elixir_deps] $*" >&2; }
die() { log "ERROR: $*"; exit 1; }

command -v mix >/dev/null || die "mix not found — pkg install elixir"
[[ -d "$PROJECT" ]] || die "BeamApp project dir not found: $PROJECT"
[[ -f "$BEAM_SERVICE" ]] || die "BeamService.java not found"

mkdir -p "$NATIVE_LIB_DIR"

# === Step 1: Build mix.exs and fetch/compile deps ===
log "Preparing mix project at $WORK_DIR"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

{
    echo 'defmodule BeamAppDeps.MixProject do'
    echo '  use Mix.Project'
    echo '  def project, do: ['
    echo '    app: :beamapp_deps,'
    echo '    version: "0.0.0",'
    echo '    deps: ['
    for entry in "${DEPS[@]}"; do
        name=$(echo "$entry" | awk '{print $1}')
        ver=$(echo "$entry" | awk '{print $2}')
        echo "      {:${name}, \"== ${ver}\"},"
    done
    echo '    ]'
    echo '  ]'
    echo '  def application, do: []'
    echo 'end'
} > mix.exs

export MIX_OS_CONCURRENCY_LOCK=0
log "Running mix deps.get"
mix deps.get
log "Running mix deps.compile"
mix deps.compile

# === Step 2: Copy compiled deps into assets ===
BUILD_LIB="$WORK_DIR/_build/dev/lib"
[[ -d "$BUILD_LIB" ]] || die "mix build dir not found: $BUILD_LIB"

OLD_EXQLITE_VER=""
NEW_EXQLITE_VER=""

for entry in "${DEPS[@]}"; do
    name=$(echo "$entry" | awk '{print $1}')
    ver=$(echo "$entry" | awk '{print $2}')
    src_ebin="$BUILD_LIB/$name/ebin"
    [[ -d "$src_ebin" ]] || die "Missing compiled ebin: $src_ebin"

    # Remove any old version of this dep
    log "Staging $name-$ver"
    for old in "$ASSETS_LIB"/$name-*; do
        [[ -d "$old" ]] || continue
        if [[ "$name" == "exqlite" ]]; then
            OLD_EXQLITE_VER=$(basename "$old" | sed "s/^${name}-//")
        fi
        rm -rf "$old"
    done

    dest="$ASSETS_LIB/$name-$ver/ebin"
    mkdir -p "$dest"
    cp "$src_ebin"/*.beam "$dest/"
    cp "$src_ebin"/*.app "$dest/" 2>/dev/null || true

    if [[ "$name" == "exqlite" ]]; then
        NEW_EXQLITE_VER="$ver"
    fi
done

# === Step 3: Copy exqlite NIF into native lib dir ===
NIF_SRC="$BUILD_LIB/exqlite/priv/sqlite3_nif.so"
NIF_DEST="$NATIVE_LIB_DIR/libsqlite3_nif.so"
[[ -f "$NIF_SRC" ]] || die "exqlite NIF not built: $NIF_SRC"

log "Copying NIF: $NIF_SRC → $NIF_DEST"
cp "$NIF_SRC" "$NIF_DEST"

# Verify it's an Android aarch64 binary
file "$NIF_DEST" | grep -q "ARM aarch64" || die "NIF is not aarch64"
log "NIF: $(file "$NIF_DEST" | sed 's|^[^:]*: ||')"

# === Step 4: Update hardcoded exqlite version in BeamService.java ===
if [[ -n "$OLD_EXQLITE_VER" && "$OLD_EXQLITE_VER" != "$NEW_EXQLITE_VER" ]]; then
    log "Updating BeamService.java: exqlite-$OLD_EXQLITE_VER → exqlite-$NEW_EXQLITE_VER"
    sed -i "s/exqlite-${OLD_EXQLITE_VER}/exqlite-${NEW_EXQLITE_VER}/g" "$BEAM_SERVICE"
fi

# === Summary ===
log "=== Done ==="
log "Staged deps:"
for entry in "${DEPS[@]}"; do
    name=$(echo "$entry" | awk '{print $1}')
    ver=$(echo "$entry" | awk '{print $2}')
    count=$(ls "$ASSETS_LIB/$name-$ver/ebin/"*.beam 2>/dev/null | wc -l)
    log "  $name-$ver ($count beams)"
done
log "NIF: $NIF_DEST ($(du -h "$NIF_DEST" | cut -f1))"
log ""
log "Next steps:"
log "  1. ./prepare_assets.sh   (refresh ERTS/kernel/stdlib/compiler from Termux)"
log "  2. ./build.sh            (rebuild APK)"
