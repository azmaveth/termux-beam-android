# Updating BeamApp Dependencies

This document covers how to update the bundled Erlang/OTP runtime, Elixir, and the
hex package dependencies (ecto, exqlite, jason, etc.) inside the BeamApp APK.

## Overview

The BeamApp APK ships its own self-contained BEAM environment:

| Component | Source | How it gets in |
|---|---|---|
| ERTS (`beam.smp`, `erlexec`, ...) | Termux `pkg install erlang` | `prepare_assets.sh` copies from `/data/data/com.termux/files/usr/lib/erlang/erts-*` |
| OTP libs (kernel, stdlib, compiler) | Same | Same |
| Elixir + hex deps (`ecto`, `exqlite`, ...) | Built via a temporary mix project | `update_elixir_deps.sh` |
| `sqlite3_nif.so` | Built by `mix deps.compile exqlite` | `update_elixir_deps.sh` copies to `build/lib/arm64-v8a/libsqlite3_nif.so`; `BeamService.java` symlinks it into `lib/exqlite-<ver>/priv/sqlite3_nif.so` at boot |
| Custom Erlang modules (`android.erl`, `speech.erl`) | This repo | Compiled by `prepare_assets.sh` |

Termux runs natively on Android with the NDK toolchain, so anything Termux compiles
(including `mix deps.compile`) produces ARM64 Android binaries automatically — no
cross-compile needed.

## Source of Truth for Versions

- **OTP / ERTS** — whatever Termux currently has installed. `prepare_assets.sh` now
  auto-detects the version dirs (`erts-*`, `lib/kernel-*`, etc.), so there is nothing
  to edit when OTP upgrades.
- **Hex deps** — the `DEPS` array near the top of `update_elixir_deps.sh`. Bump
  versions there.

## How to Update

### Update Erlang/OTP

```bash
pkg upgrade erlang elixir
./update_elixir_deps.sh   # rebuild deps against the new OTP/Elixir
./prepare_assets.sh       # picks up new ERTS/kernel/stdlib/compiler automatically
./build.sh                # rebuild APK
```

You should re-run `update_elixir_deps.sh` after upgrading OTP because the BEAM
files are version-pinned to the OTP they were compiled against.

### Update one or more hex deps

1. Edit `update_elixir_deps.sh` and bump the version in the `DEPS` array, e.g.:
   ```bash
   "exqlite        0.36.0"
   ```
2. Run:
   ```bash
   ./update_elixir_deps.sh
   ./prepare_assets.sh
   ./build.sh
   ```

The script will:
- Remove the old `assets/erlang/lib/<dep>-<oldver>/` dir
- Stage the new `assets/erlang/lib/<dep>-<newver>/`
- Rewrite the hardcoded `exqlite-<ver>` strings in `BeamService.java` if exqlite
  changed

### Update Elixir itself

```bash
pkg upgrade elixir
./update_elixir_deps.sh   # rebuilds all deps against new Elixir
./prepare_assets.sh
./build.sh
```

Note: `prepare_assets.sh` does **not** currently copy Elixir's own BEAM files
(`elixir-*`, `eex-*`, `iex-*`, `logger-*`) — those were copied in by hand during
the original Elixir bring-up commit. If Elixir is upgraded substantially, you may
also need to refresh those by copying from `/data/data/com.termux/files/usr/lib/elixir/lib/`
(this is a TODO — see "Limitations" below).

## How the NIF Symlink Works

`exqlite` ships a NIF (`sqlite3_nif.so`) that needs to be loaded from the
package's `priv/` directory. But Android only extracts files placed in `lib/<abi>/`
and only if they match `lib*.so`. So we:

1. `update_elixir_deps.sh` copies the NIF to `build/lib/arm64-v8a/libsqlite3_nif.so`
   (note the `lib` prefix). This gets bundled into the APK by `aapt2 link` and
   extracted by Android into `nativeLibraryDir` at install time.
2. At BEAM startup, `BeamService.java` (around line 132) creates the directory
   `<extracted-erlang>/lib/exqlite-<ver>/priv/` and symlinks
   `nativeLibraryDir/libsqlite3_nif.so` → `priv/sqlite3_nif.so`.
3. exqlite then loads the NIF normally via its standard load path.

If you bump the exqlite version, the script auto-updates the version literal in
`BeamService.java` so the symlink path stays correct.

## Verifying an Update

After running the scripts, sanity-check:

```bash
# Verify ERTS version matches Termux
cat assets/erlang/erts_version
ls -d /data/data/com.termux/files/usr/lib/erlang/erts-*

# Verify hex dep versions
ls assets/erlang/lib/ | grep -E '(exqlite|ecto|jason)'

# Verify NIF is aarch64
file build/lib/arm64-v8a/libsqlite3_nif.so

# Verify Java still references the right exqlite version
grep 'exqlite-' src/com/example/beamapp/BeamService.java
```

Then rebuild and install:
```bash
./build.sh
# install resulting APK from build/apk/
```

## Limitations / TODOs

- **Elixir core libs are not auto-refreshed.** `elixir-*`, `eex-*`, `iex-*`,
  `logger-*` were committed by hand during the initial Elixir bring-up and are not
  re-staged by either script. For minor patch bumps of Elixir this doesn't matter,
  but a major version change would need them refreshed.
- **`asn1`, `crypto`, `ssl`, `public_key` OTP apps** are committed in `assets/erlang/lib/`
  but also not refreshed by `prepare_assets.sh`. Same caveat.
- **Cluster setup scripts** (`cluster/`) are unrelated and don't need updating.

### Safety note

`prepare_assets.sh` only cleans the directories it manages (`kernel-*`, `stdlib-*`,
`compiler-*`, `android`, `bin`, `erts_version`) and the model dirs. It will **not**
touch hex dep dirs or `libsqlite3_nif.so`. The two scripts can be run in either
order, but the recommended order is `update_elixir_deps.sh` first, then
`prepare_assets.sh`, since the latter is faster to re-run if you need to iterate.

## Troubleshooting

**`mix not found`** — `pkg install elixir` (which pulls in erlang).

**`mix local.hex` prompts on first run** — run `mix local.hex --force` once.

**`prepare_assets.sh: ERROR: no match for erts-*`** — Termux's Erlang is missing
or in an unexpected location. Run `pkg install erlang` and check
`/data/data/com.termux/files/usr/lib/erlang/`.

**APK boots but exqlite fails to load NIF** — check `BeamService.java` line ~132
references the correct exqlite version dir, and that
`build/lib/arm64-v8a/libsqlite3_nif.so` exists and is aarch64.
