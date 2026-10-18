#!/data/data/com.termux/files/usr/bin/sh
# BeamApp Termux CLI installer.
#
# BeamApp > Config > "Set up Termux CLI" writes this folder (termux-cli/) and the
# bridge token (../.bridge_token) to shared storage and copies
#     sh /sdcard/BeamDisplay/termux-cli/install.sh
# to the clipboard. Paste that into Termux. Needs nothing pre-installed except
# shared-storage access (run `termux-setup-storage` once if /sdcard is denied).
#
# It installs bridge, screen and bridge-token-sync into ~/bin, makes sure ~/bin
# is on PATH in ~/.bashrc (and ~/.zshrc when zsh is used), moves the token into
# ~/.bridge_token (mode 600) and removes the token and these scripts from
# shared storage. Safe to re-run.
set -e

SRC_DIR=$(cd "$(dirname "$0")" 2>/dev/null && pwd) || {
    echo "cannot read $(dirname "$0") - run termux-setup-storage, then retry" >&2
    exit 1
}
BIN="$HOME/bin"
SCRIPTS="bridge screen bridge-token-sync"

if [ ! -d /data/data/com.termux/files/usr ]; then
    echo "warning: this does not look like Termux; continuing anyway" >&2
fi

# 1. Scripts -> ~/bin (atomic replace, executable)
mkdir -p "$BIN"
for s in $SCRIPTS; do
    if [ ! -r "$SRC_DIR/$s" ]; then
        echo "missing $SRC_DIR/$s - tap the button in BeamApp again" >&2
        exit 1
    fi
    cp "$SRC_DIR/$s" "$BIN/.$s.tmp"
    chmod 755 "$BIN/.$s.tmp"
    mv -f "$BIN/.$s.tmp" "$BIN/$s"
    echo "installed $BIN/$s"
done

# 2. ~/bin on PATH (idempotent: skip any rc file that already mentions it)
add_path() {
    rc="$1"
    if [ -f "$rc" ] && grep -Eq '(\$HOME|\$\{HOME\}|~|/data/data/com\.termux/files/home)/bin([:"'"'"' ]|$)' "$rc"; then
        echo "$rc already puts ~/bin on PATH"
        return 0
    fi
    printf '\n# BeamApp CLI\nexport PATH="$HOME/bin:$PATH"\n' >> "$rc"
    echo "added ~/bin to PATH in $rc"
}
add_path "$HOME/.bashrc"
if [ -f "$HOME/.zshrc" ] || [ "$(basename "${SHELL:-}")" = zsh ]; then
    add_path "$HOME/.zshrc"
fi

# 3. Token -> ~/.bridge_token (bridge-token-sync wipes the shared copy)
TOKEN_STATUS=0
"$BIN/bridge-token-sync" "$SRC_DIR/../.bridge_token" || TOKEN_STATUS=$?

# 4. Remove the scripts from shared storage too (re-tap the button to re-export)
for s in $SCRIPTS install.sh; do rm -f "$SRC_DIR/$s"; done
rmdir "$SRC_DIR" 2>/dev/null || true

# 5. Sanity checks
command -v python3 >/dev/null 2>&1 || \
    echo "note: bridge and screen need python3 - run: pkg install python" >&2
if [ "$TOKEN_STATUS" -ne 0 ]; then
    echo "scripts installed, but no token was installed (see above)" >&2
    exit "$TOKEN_STATUS"
fi
if command -v python3 >/dev/null 2>&1; then
    if BRIDGE_TIMEOUT=5 "$BIN/bridge" ping >/dev/null 2>&1; then
        echo "bridge ping: ok"
    else
        echo "bridge ping failed - is the BEAM running in BeamApp?" >&2
    fi
fi
case ":$PATH:" in
    *":$BIN:"*) ;;
    *) echo "open a new Termux session (or run: export PATH=\"\$HOME/bin:\$PATH\") to use the commands" ;;
esac
echo "done: try 'bridge ping' or 'screen'"
