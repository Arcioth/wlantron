#!/bin/sh
# Build and install wlantron (wifi-login, lan-login) for Linux.
#
# From a checkout:
#   ./build.sh
# From anywhere, this is the curl build command:
#   curl -fsSL https://raw.githubusercontent.com/Arcioth/wlantron/main/build.sh | sh
#
# WLANTRON_PREFIX  install prefix (default: $HOME/.local)
# WLANTRON_REF     git branch when downloading (default: main)
set -eu

REPO="https://github.com/Arcioth/wlantron"
REF="${WLANTRON_REF:-main}"
PREFIX="${WLANTRON_PREFIX:-$HOME/.local}"

die() {
    printf 'wlantron: %s\n' "$1" >&2
    exit 1
}

need() {
    command -v "$1" >/dev/null 2>&1 || die "missing '$1'"
}

need python3
python3 -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)' \
    || die "Python 3.10 or newer is required"

ROOT=""
BASE=$(basename "$0" 2>/dev/null || printf '%s' "$0")
case "$BASE" in
    build.sh)
        HERE=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
        if [ -f "$HERE/wlantron/__main__.py" ]; then
            ROOT=$HERE
        fi
        ;;
esac

TMP=""
cleanup() {
    if [ -n "$TMP" ] && [ -d "$TMP" ]; then
        rm -rf "$TMP"
    fi
}
trap cleanup EXIT

if [ -z "$ROOT" ]; then
    need curl
    need tar
    TMP=$(mktemp -d)
    printf 'Downloading %s (%s)...\n' "$REPO" "$REF"
    curl -fsSL "$REPO/archive/refs/heads/$REF.tar.gz" | tar -xz -C "$TMP"
    for dir in "$TMP"/*; do
        if [ -f "$dir/wlantron/__main__.py" ]; then
            ROOT=$dir
            break
        fi
    done
    [ -n "$ROOT" ] || die "download did not contain the wlantron package"
    mkdir -p "$PREFIX/share/wlantron"
    rm -rf "$PREFIX/share/wlantron/wlantron"
    cp -a "$ROOT/wlantron" "$PREFIX/share/wlantron/wlantron"
    TARGET="$PREFIX/share/wlantron"
else
    TARGET=$ROOT
fi

python3 -m compileall -q "$TARGET/wlantron" || die "Python compile failed"

mkdir -p "$PREFIX/bin"
LAUNCHER="$PREFIX/bin/wifi-login"
cat > "$LAUNCHER" << EOF
#!/bin/sh
as=\$(basename "\$0")
export WLANTRON_AS="\$as"
exec python3 "$TARGET/wlantron/__main__.py" "\$@"
EOF
chmod 755 "$LAUNCHER"
ln -sfn wifi-login "$PREFIX/bin/lan-login"
ln -sfn wifi-login "$PREFIX/bin/wlantron"

printf 'Installed wifi-login, lan-login, and wlantron into %s\n' "$PREFIX/bin"
case ":$PATH:" in
    *":$PREFIX/bin:"*) ;;
    *) printf 'wlantron: %s is not on PATH. Add it to your shell profile.\n' "$PREFIX/bin" >&2 ;;
esac
printf 'Next: wifi-login --configure\n'
printf 'Later: wifi-login --update\n'
