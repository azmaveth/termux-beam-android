# Updating BeamApp Dependencies

This document covers how to update the bundled Erlang/OTP runtime, Elixir, and the
hex package dependencies (ecto, exqlite, jason, etc.) inside the BeamApp APK.

## Overview

The BeamApp APK ships its own self-contained BEAM environment:

| Component | Source | How it gets in |
|---|---|---|
| ERTS (`beam.smp`, `erlexec`, ...) | Termux `pkg install erlang` | `prepare_assets.sh` copies from `/data/data/com.termux/files/usr/lib/erlang/erts-*` |
| OTP libs (kernel, stdlib, compiler, asn1, crypto, public_key, ssl) | Same | Same (crypto/asn1 NIFs copied into `priv/lib/`) |
| Elixir core (`elixir`, `eex`, `iex`, `logger`) | Termux `pkg install elixir` | `prepare_assets.sh` copies from Termux's Elixir lib dir into `<app>-<vsn>/` |
| Hex deps (`ecto`, `exqlite`, ...) | Built via a temporary mix project | `update_elixir_deps.sh` |
| `sqlite3_nif.so` | Built by `mix deps.compile exqlite` | `update_elixir_deps.sh` copies to `build/lib/arm64-v8a/libsqlite3_nif.so`; `BeamService.java` symlinks it into `lib/exqlite-<ver>/priv/sqlite3_nif.so` at boot |
| Custom Erlang modules (`android.erl`, `speech.erl`) | This repo | Compiled by `prepare_assets.sh` |
| LiteRT-LM (Gemma 4 runtime) + Kotlin stdlib/coroutines/gson | Google Maven + Maven Central | `download_litertlm.sh` fetches AARs/JARs, stages `classes.jar`s into `build/litertlm/classpath/` and `liblitertlm_jni.so` into `build/lib/arm64-v8a/` |

Termux runs natively on Android with the NDK toolchain, so anything Termux compiles
(including `mix deps.compile`) produces ARM64 Android binaries automatically — no
cross-compile needed.

## Source of Truth for Versions

- **OTP / ERTS / Elixir** — whatever Termux currently has installed. `prepare_assets.sh`
  auto-detects the version dirs (`erts-*`, `lib/kernel-*`, `lib/crypto-*`, etc.) and
  reads the Elixir version from `elixir.app`, so there is nothing to edit when OTP or
  Elixir upgrades. `BeamService.java` finds the bundled `crypto-*`, `ssl-*`,
  `elixir-*`, ... dirs from the APK's assets at boot, so it needs no edits either.

## What is (and isn't) tracked in git

Everything `prepare_assets.sh` generates is **not** committed — `assets/` and
`build/` are gitignored and the generated output was untracked. Run
`./prepare_assets.sh` after cloning (and after any `pkg upgrade erlang elixir`)
before `./build.sh`. Generated: ERTS binaries and dependent `.so`s in
`build/lib/arm64-v8a/` (including `libepmd.so`), `assets/erlang/bin/*.boot`,
`assets/erlang/erts_version`, `assets/erlang/lib/{kernel,stdlib,compiler,asn1,crypto,public_key,ssl}-*`,
`assets/erlang/lib/{elixir,eex,iex,logger}-*`, `assets/erlang/lib/android`, and the
model dirs.

Still tracked (force-added): the hex deps under `assets/erlang/lib/` and
`build/lib/arm64-v8a/libsqlite3_nif.so`, which `update_elixir_deps.sh` manages.
- **Hex deps** — the `DEPS` array near the top of `update_elixir_deps.sh`. Bump
  versions there.
- **LiteRT-LM / Kotlin deps** — the `PKGS` array near the top of
  `download_litertlm.sh`. Bump versions there.

## How to Update

### Update Erlang/OTP

```bash
pkg upgrade erlang elixir
./update_elixir_deps.sh   # rebuild deps against the new OTP/Elixir
./prepare_assets.sh       # picks up new ERTS/OTP libs/Elixir automatically
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

`prepare_assets.sh` copies Elixir's own BEAM files (`elixir`, `eex`, `iex`, `logger`)
from Termux's Elixir install (`$PREFIX/opt/elixir/lib`, or wherever
`elixir -e 'IO.puts :code.lib_dir(:elixir)'` points; override with `ELIXIR_LIB=...`)
into `assets/erlang/lib/<app>-<vsn>/`, removing older versions first.

## Termux CLI (bridge commands)

The Termux-side commands live in `termux/`: `bridge` (call a bridge command on
port 9877), `screen` (readable dump of `screen_read`), `bridge-token-sync`
(install an exported token) and `install.sh`. `build.sh` stages them into the APK
as `assets/termux-cli/` on every build, so edit them in `termux/`, not in
`assets/`.

To set up Termux on a phone (BraX3, S22, ...): start the BEAM, open BeamApp >
Config and tap **Set up Termux CLI (token + commands)**. The app writes the token
to `/sdcard/BeamDisplay/.bridge_token` and the scripts to
`/sdcard/BeamDisplay/termux-cli/`, and copies this to the clipboard:

```sh
sh /sdcard/BeamDisplay/termux-cli/install.sh
```

Paste it in Termux (run `termux-setup-storage` once first if `/sdcard` is denied).
It needs nothing pre-installed. It copies the scripts into `~/bin` (chmod 755),
adds `~/bin` to `PATH` in `~/.bashrc` and in `~/.zshrc` if zsh is used (skipped
when already present), moves the token to `~/.bridge_token` (chmod 600), and wipes
and deletes the token and scripts from shared storage. It then runs `bridge ping`.
`bridge` and `screen` need `python3` (`pkg install python`). After the token
changes (e.g. app reinstall), tap the button again and re-run the command.


`crypto` and `asn1` NIFs (`crypto.so`, `crypto_callback.so`, `asn1rt_nif.so`) are
copied by `prepare_assets.sh` into the asset tree at `lib/<app>-<vsn>/priv/lib/`.
`BeamService.java` extracts the whole `erlang` asset tree into the app's files dir
at boot, and the apps load their NIFs from `priv/lib` the normal way. Their OpenSSL
dependency is bundled as `build/lib/arm64-v8a/libcrypto3.so`/`libssl3.so` and
symlinked to `libcrypto.so.3`/`libssl.so.3` at boot.

### exqlite

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

- **Hex deps must be rebuilt after an OTP/Elixir upgrade.** `prepare_assets.sh`
  refreshes OTP and Elixir core, but the hex deps under `assets/erlang/lib/` only
  change when `update_elixir_deps.sh` is run.
- **Cluster setup scripts** (`cluster/`) are unrelated and don't need updating.

### Update LiteRT-LM or its Kotlin deps

1. Edit `download_litertlm.sh` and bump versions in the `PKGS` array. The format is:
   ```
   "repo  group  artifact  version  ext"
   ```
   where `repo` is `google` (for Google Maven) or `central` (for Maven Central),
   and `ext` is `jar` or `aar`.
2. Run:
   ```bash
   ./download_litertlm.sh
   ./build.sh
   ```

The script re-downloads only missing files (cached in `/tmp/litertlm_fetch`),
cleans old jars out of `build/litertlm/classpath/`, and restages everything fresh.
`build.sh` auto-picks up all `*.jar` files in `build/litertlm/classpath/` for both
the javac classpath and the d8 dex step.

**What LiteRT-LM gives you:** the same Kotlin runtime that Google's Edge Gallery
app ships with, so BeamApp can load `.litertlm` model files (Gemma 4 and others
from HuggingFace `litert-community`) and run on-device LLM inference. The Java
entry points you'll most likely use are `com.google.ai.edge.litertlm.Engine`,
`EngineConfig`, `Conversation.sendMessageAsync(...)`, and `ResponseCallback`.
See Gallery's `LlmChatModelHelper.kt` for a reference implementation to port.

**Current dep closure** (approximate sizes after d8):
- litertlm-android — 116 KB of Kotlin classes + 20 MB `liblitertlm_jni.so`
- kotlin-stdlib + kotlin-reflect — ~5 MB of classes
- kotlinx-coroutines core + android — ~1.4 MB
- gson — 284 KB

### Safety note

`prepare_assets.sh` only cleans the directories it manages (`kernel-*`, `stdlib-*`,
`compiler-*`, `asn1-*`, `crypto-*`, `public_key-*`, `ssl-*`, `elixir-*`, `eex-*`,
`iex-*`, `logger-*`, `android`, `erts_version`) and the model dirs. It will **not**
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
