#!/bin/sh
# The toolbelt unpack must survive stale read-only files in the old rootfs.
#
# 2026-10-07 the first start after an update printed hundreds of
# "rm: cannot remove '.../tmp/claude-0/.../.linux-store/store/...': Permission
# denied" and exited 1, killing the session: a previous agent session left
# nix/linux-store style files (0444 in 0555 dirs) under the rootfs /tmp. This
# runs rootfs/enter.sh's wipe_rootfs against such a tree and requires: exit 0,
# no per-file output, everything outside /tmp gone, /tmp untouched. The rm is a
# shim that refuses non-writable directories exactly as an unprivileged phone
# does, so the result does not depend on the uid CI runs as. The rule is then
# broken once in a scratch copy (mutant) and must go red.
#
# Static, offline, POSIX sh.
set -u

DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENTER="${ENTER:-$DIR/rootfs/enter.sh}"
T="$(mktemp -d)"
trap 'chmod -R u+rwx "$T" 2>/dev/null; rm -rf "$T"' EXIT
fail=0

mkdir -p "$T/shim"
cat > "$T/shim/rm" <<'SH'
#!/bin/sh
# rm -rf that fails like a non-root phone: a file in a directory without u+w cannot go.
real=/bin/rm; [ -x "$real" ] || real=/usr/bin/rm
for a in "$@"; do
    case "$a" in -*) continue ;; esac
    if [ -d "$a" ] && [ ! -L "$a" ]; then
        bad="$(find "$a" -type d ! -perm -u+w 2>/dev/null | head -1)"
        [ -z "$bad" ] || { echo "rm: cannot remove '$bad/x': Permission denied" >&2; exit 1; }
    fi
done
exec "$real" "$@"
SH
chmod +x "$T/shim/rm"

extract() {  # $1 = enter.sh; prints wipe_rootfs's definition
    sed -n '/^# wipe_rootfs begin$/,/^# wipe_rootfs end$/p' "$1"
}

plant() {  # $1 = rootfs: read-only store trees in /tmp and in /opt, plus ordinary files
    mkdir -p "$1/usr/bin" "$1/etc" "$1/tmp/claude-0/-root/u/scratchpad/home/.linux-store/store/abc-pkg/bin" \
             "$1/opt/.linux-store/store/def-pkg/lib"
    echo x > "$1/usr/bin/tool"; echo x > "$1/etc/passwd"; echo x > "$1/.cloud-rootfs.sha256"
    ln -s /nowhere "$1/lib"
    for d in "$1/tmp/claude-0/-root/u/scratchpad/home/.linux-store/store/abc-pkg" "$1/opt/.linux-store/store/def-pkg"; do
        find "$d" -type d | while read -r sub; do echo ro > "$sub/f"; chmod 0444 "$sub/f"; done
        find "$d" -type d -exec chmod 0555 {} +
    done
}

run() {  # $1 = enter.sh to take the function from; sets rc, out
    R="$T/root"; chmod -R u+rwx "$R" 2>/dev/null; rm -rf "$R"; mkdir -p "$R"; plant "$R"
    { echo 'set -eu'; echo "ROOTFS='$R'"; extract "$1"; echo 'wipe_rootfs'; echo 'echo survived'; } > "$T/drv.sh"
    out="$(PATH="$T/shim:$PATH" sh "$T/drv.sh" 2>&1)"; rc=$?
}

check() {  # prints violations for the last run()
    [ "$rc" -eq 0 ] || echo "W1 the wipe exited $rc: the session would die"
    case "$out" in *survived*) ;; *) echo "W1 the shell never reached the line after the wipe" ;; esac
    n="$(printf '%s\n' "$out" | grep -c 'cannot remove')"
    [ "$n" -eq 0 ] || echo "W2 $n per-file 'cannot remove' lines leaked to the output"
    [ "$(printf '%s\n' "$out" | wc -l)" -le 2 ] || echo "W2 more than one summary line printed"
    [ ! -e "$R/usr" ] && [ ! -e "$R/opt" ] && [ ! -e "$R/etc" ] && [ ! -L "$R/lib" ] || echo "W3 old rootfs entries outside /tmp survived the wipe"
    [ -d "$R/tmp/claude-0" ] || echo "W4 the wipe recursed into the rootfs /tmp"
}

[ -f "$ENTER" ] || { echo "FAIL no enter.sh at $ENTER"; exit 1; }
[ -n "$(extract "$ENTER")" ] || { echo "FAIL enter.sh has no wipe_rootfs block"; exit 1; }
grep -q '^    wipe_rootfs$' "$ENTER" || { echo "FAIL enter.sh never calls wipe_rootfs on the unpack path"; fail=1; }
if grep -q '^    rm -rf "\$ROOTFS"' "$ENTER"; then echo "FAIL enter.sh still has a bare rm -rf of the whole rootfs"; fail=1; fi

run "$ENTER"
v="$(check)"
if [ -n "$v" ]; then echo "FAIL"; echo "$v"; fail=1; else echo "ok   a read-only store tree is wiped, quietly, exit 0, /tmp left alone"; fi

# mutants: each rule broken once in a scratch copy must go red
mutant() {  # $1 = sed expr, $2 = what it breaks, $3 = violation code that must fire
    sed "$1" "$ENTER" > "$T/mut.sh"
    if cmp -s "$ENTER" "$T/mut.sh"; then echo "FAIL MUTATION DID NOT APPLY: $2"; fail=1; return; fi
    run "$T/mut.sh"
    if ! check | grep -q "^${3:-W}"; then echo "FAIL MUTATION SURVIVED: $2"; fail=1; else echo "ok   mutation proved: $2 goes red"; fi
}
mutant '/chmod -R u+rwx/d' "no chmod before the rm" W3
mutant '/chmod -R u+rwx/d;s|rm -rf "\$e" >/dev/null 2>&1 \|\| true|rm -rf "$e"|' "an rm whose failure aborts the shell" W1
mutant 's|\[ "\$e" != "\$ROOTFS/tmp" \] \|\| continue|:|' "a wipe that recurses into the rootfs /tmp" W4

[ "$fail" -eq 0 ] && echo "PASS toolbelt wipe" || { echo "FAILED toolbelt wipe"; exit 1; }
