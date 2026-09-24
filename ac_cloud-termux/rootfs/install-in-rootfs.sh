#!/bin/sh
# Runs as root INSIDE the Debian root being built (docker in CI). Installs
# exactly what resolve.py rendered from rootfs.json + the fleet tool belt and
# names nothing itself: every list below arrives in $1, an env file.
#
# It ends by probing every declared binary with `command -v`, the same way
# the fleet images do (#509), so a tool that failed to install breaks this
# build instead of turning up missing on the phone.
set -eu
. "$1"
export DEBIAN_FRONTEND=noninteractive

apt-get update
# shellcheck disable=SC2086 # word splitting is the list
apt-get install -y --no-install-recommends $APT_PACKAGES

# Tarballs: name|url|dest|strip|path-inside-archive
printf '%s\n' "$TARBALLS" | while IFS='|' read -r name url dest strip inner; do
    [ -n "$name" ] || continue
    tmp="$(mktemp -d)"
    curl -fsSL "$url" -o "$tmp/download"
    if [ -n "$strip" ]; then
        tar -xf "$tmp/download" -C "$dest" --strip-components="$strip" --no-same-owner
    elif [ -n "$inner" ]; then
        tar -xf "$tmp/download" -C "$tmp"
        install -m 0755 "$tmp/$inner" "$dest"
    else
        install -m 0755 "$tmp/download" "$dest"
    fi
    rm -rf "$tmp"
done

# shellcheck disable=SC2086
[ -z "$NPM_GLOBALS" ] || npm install -g $NPM_GLOBALS

# Python agents, one venv each: package|version|venv|console scripts
printf '%s\n' "$PIP_VENVS" | while IFS='|' read -r pkg version venv provides; do
    [ -n "$pkg" ] || continue
    python3 -m venv "$venv"
    "$venv/bin/pip" install --no-cache-dir "$pkg==$version"
    for b in $provides; do ln -sf "$venv/bin/$b" "/usr/local/bin/$b"; done
done

usermod -s "$DEFAULT_SHELL" root

apt-get clean
rm -rf /var/lib/apt/lists/* /root/.npm /tmp/*

missing=""
for t in $BINARIES; do
    command -v "$t" >/dev/null || missing="$missing $t"
done
[ -z "$missing" ] || { echo "rootfs: declared but not installed:$missing" >&2; exit 1; }
echo "rootfs: all declared binaries installed ($(echo $BINARIES | wc -w))"
