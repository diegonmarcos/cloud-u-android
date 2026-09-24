#!/system/bin/sh
# Cloud Terminal's login shell: enter the glibc root baked into this APK.
#
# The app stages this file, proot, rootfs.tar.zst and rootfs.sha256 into one
# directory and points ~/.termux/shell here (com.termux.cloud.CloudRootfs), so
# Termux's own $PREFIX/bin/login execs it for every new session. Nothing in
# here names the app id or a path: everything is found relative to this file,
# so the com.termux -> cld.termux rewrite has nothing to rewrite.
#
# The failsafe session (long-press "New session") skips login and this file,
# and ~/.termux/shell can be deleted to go back to Termux bash.
#
# CI runs THIS file (rootfs/verify-rootfs.sh) against the tarball it built,
# with the same static proot, so the path tested is the path the phone takes.
set -eu

HERE="$(dirname "$(realpath "$0")")"
ROOTFS="$HERE/rootfs"
STAMP="$ROOTFS/.cloud-rootfs.sha256"

fallback() {
    echo "cloud-rootfs: $*" >&2
    echo "cloud-rootfs: starting the plain Termux shell instead" >&2
    exec "${CLOUD_ROOTFS_FALLBACK:-bash}" -l
}

# Termux's login probes $SHELL with exactly this to decide whether termux-exec's
# LD_PRELOAD works. It is a question about the Termux side, so Termux's sh
# answers it: entering proot here would unpack the rootfs silently on first
# start and then fail the probe, since Debian has no coreutils multicall binary.
if [ "$#" -eq 2 ] && [ "$1" = -c ] && [ "$2" = "coreutils --coreutils-prog=true" ]; then
    exec sh "$@"
fi

login=""
if [ "${1:-}" = -l ]; then login=-l; shift; fi

# termux-exec is a bionic library; preloaded into glibc processes it breaks every exec.
unset LD_PRELOAD
export PROOT_TMP_DIR="$HERE/tmp"
mkdir -p "$PROOT_TMP_DIR"

want="$(cat "$HERE/rootfs.sha256" 2>/dev/null)" || fallback "rootfs.sha256 is missing: the app has not staged the rootfs"
if [ "$(cat "$STAMP" 2>/dev/null || true)" != "$want" ]; then
    [ -f "$HERE/rootfs.tar.zst" ] || fallback "rootfs.tar.zst is missing and no unpacked rootfs matches $want"
    echo "Unpacking the agent toolbelt (first start after install or update)..." >&2
    rm -rf "$ROOTFS"
    mkdir -p "$ROOTFS"
    # Android forbids hard links in app storage; --link2symlink emulates the
    # rootfs's (claude's bin/claude.exe is one) and proot resolves them later.
    zstd -dc "$HERE/rootfs.tar.zst" | "$HERE/proot" --link2symlink -0 tar -xf - -C "$ROOTFS" \
        || fallback "unpacking the rootfs failed"
    echo "$want" > "$STAMP"
    rm -f "$HERE/rootfs.tar.zst"
fi

# The login shell is root's entry in the rootfs, written at build time from
# rootfs.json::default_shell. This file does not choose one.
login_shell=""
while IFS=: read -r user _ _ _ _ _ entry_shell; do
    if [ "$user" = root ]; then login_shell="$entry_shell"; break; fi
done < "$ROOTFS/etc/passwd"
[ -n "$login_shell" ] || fallback "the rootfs /etc/passwd has no root entry"

# Android denies apps /proc/stat and /proc/uptime; top, htop and node read them.
binds=""
mkdir -p "$HERE/fake"
if [ ! -r /proc/stat ]; then
    printf 'cpu  1 0 1 1000 0 0 0 0 0 0\ncpu0 1 0 1 1000 0 0 0 0 0 0\nintr 0\nctxt 0\nbtime 0\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n' > "$HERE/fake/stat"
    binds="$binds -b $HERE/fake/stat:/proc/stat"
fi
if [ ! -r /proc/uptime ]; then
    printf '1000.00 1000.00\n' > "$HERE/fake/uptime"
    binds="$binds -b $HERE/fake/uptime:/proc/uptime"
fi
[ ! -d /storage/emulated/0 ] || binds="$binds -b /storage/emulated/0:/sdcard"

# $HOME is bound as /root, so credentials, git config and work survive a
# rootfs update (which replaces the tree above) and stay visible to Termux.
# shellcheck disable=SC2086 # $binds is a list of flags
exec "$HERE/proot" --kill-on-exit --link2symlink --sysvipc -0 -r "$ROOTFS" \
    -b /dev -b /proc -b /sys -b /proc/self/fd:/dev/fd -b "$HOME:/root" $binds -w /root \
    /usr/bin/env -i HOME=/root USER=root LOGNAME=root SHELL="$login_shell" \
        TERM="${TERM:-xterm-256color}" COLORTERM="${COLORTERM:-truecolor}" LANG=C.UTF-8 TMPDIR=/tmp \
        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    "$login_shell" $login "$@"
