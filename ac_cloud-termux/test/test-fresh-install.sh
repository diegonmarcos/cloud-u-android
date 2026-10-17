#!/bin/sh
# Fresh install must end in a working shell, offline. Regression for the 2026-10-17 reinstall:
# rish was exported into $PREFIX/var before the bootstrap, isPrefixInstalled() said "installed",
# the bootstrap was skipped and every session was a bare /system/bin/sh with PATH=$PREFIX/bin.
set -eu
D="$(cd "$(dirname "$0")/.." && pwd)"
I="$D/app/src/main/java/com/termux/app/TermuxInstaller.java"
S="$D/termux-shared/src/main/java/com/termux/shared/shell/TermuxSession.java"
U="$D/termux-shared/src/main/java/com/termux/shared/shell/TermuxShellUtils.java"
fail() { echo "FAIL: $*" >&2; exit 1; }
grep -q 'TERMUX_BIN_PREFIX_DIR_PATH, "login").canExecute()' "$I" || fail "isPrefixInstalled must require an executable \$PREFIX/bin/login"
grep -q ':/system/bin:/system/xbin' "$S" || fail "the no-bootstrap fallback must add /system/bin to PATH"
grep -q 'alias repair=' "$S" || fail "the no-bootstrap fallback must offer 'repair'"
grep -q '"PATH= "' "$U" && fail "failsafe PATH has a leading space"
# enter.sh with nothing staged, network off when unshare allows: says why + repair, and ls/uname work.
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
cp "$D/rootfs/enter.sh" "$W/enter.sh"; mkdir -p "$W/home"
run() { if unshare -rn true 2>/dev/null; then unshare -rn "$@"; else "$@"; fi; }
out="$(HOME="$W/home" PATH=/usr/bin:/bin CLOUD_ROOTFS_FALLBACK=sh run /bin/sh "$W/enter.sh" -c 'ls / >/dev/null && uname -s' 2>&1 </dev/null)" \
    || true
case "$out" in *"repair:"*) ;; *) fail "enter.sh fallback printed no repair hint: $out" ;; esac
case "$out" in *Linux*) ;; *) fail "ls/uname did not work in the fallback shell: $out" ;; esac
echo "ok: fresh install reaches a usable shell offline"
