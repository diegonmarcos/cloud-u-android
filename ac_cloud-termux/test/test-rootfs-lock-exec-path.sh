#!/bin/sh
# The rootfs unpack lock must work on every entry path, including Cloud Drive's CLOUD_EXEC job.
#
# 2026-10-27 a Drive clone in cld.termux failed with "flock: 9: Bad file descriptor": enter.sh
# runs under Android's /system/bin/sh (mksh), whose `exec 9>file` opens fd 9 close-on-exec, so
# the external `flock 9` child had no fd 9. This test reproduces that with a `flock` shim that
# closes fd 9 before running the real flock, and starts every caller with NO inherited fd 9.
# Required: (L1) no "Bad file descriptor", (L2) the flock path is taken (not the mkdir fallback),
# (L3) two callers are serialized (the second takes the lock only after the first releases it).
# The old bare `flock 9` is then put back in a scratch copy and must go red on L1.
#
# Static, offline, POSIX sh; needs util-linux flock (skips otherwise).
set -u
DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENTER="${ENTER:-$DIR/rootfs/enter.sh}"
REAL="$(command -v flock || true)"
[ -n "$REAL" ] || { echo "SKIP no flock on this runner"; exit 0; }
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
fail=0
mkdir -p "$T/bin"
cat > "$T/bin/flock" <<SH
#!/bin/sh
# mksh's close-on-exec, reproduced: fd 9 never reaches flock.
echo used >> "$T/flock.log"
exec 9>&-
exec "$REAL" "\$@"
SH
chmod +x "$T/bin/flock"

check() {  # $1 = enter.sh; prints violations
    H="$T/here"; rm -rf "$H" "$T/flock.log"; mkdir -p "$H"
    { echo "HERE='$H'"; sed -n '/^LOCKDIR=/,/^unpack_rootfs() {/p' "$1" | sed '$d'
      echo 'rootfs_lock; echo "IN $(date +%s%N)"; sleep 1; echo "OUT $(date +%s%N)"; rootfs_unlock'; } > "$T/drv.sh"
    for sh in sh bash; do
        command -v "$sh" >/dev/null 2>&1 || continue
        PATH="$T/bin:$PATH" "$sh" "$T/drv.sh" > "$T/a" 2>&1 9>&- &
        pa=$!
        PATH="$T/bin:$PATH" "$sh" "$T/drv.sh" > "$T/b" 2>&1 9>&- &
        pb=$!
        wait $pa; wait $pb
        grep -q 'Bad file descriptor' "$T/a" "$T/b" && echo "L1 $sh: flock: Bad file descriptor"
        [ -s "$T/flock.log" ] || echo "L2 $sh: the flock path was not used"
        [ -e "$H/var/lib/cloud-rootfs/unpack.lck" ] && echo "L2 $sh: the mkdir fallback lock was taken"
        ai="$(sed -n 's/^IN //p' "$T/a")"; ao="$(sed -n 's/^OUT //p' "$T/a")"
        bi="$(sed -n 's/^IN //p' "$T/b")"; bo="$(sed -n 's/^OUT //p' "$T/b")"
        if [ -z "$ai" ] || [ -z "$bi" ]; then echo "L3 $sh: a caller never took the lock"
        elif [ "$ai" -lt "$bi" ]; then [ "$bi" -ge "$ao" ] || echo "L3 $sh: both callers held the lock at once"
        else [ "$ai" -ge "$bo" ] || echo "L3 $sh: both callers held the lock at once"; fi
        rm -f "$T/flock.log"
    done
}

[ -f "$ENTER" ] || { echo "FAIL no enter.sh at $ENTER"; exit 1; }
v="$(check "$ENTER")"
if [ -n "$v" ]; then echo "FAIL"; echo "$v"; fail=1; else echo "ok   unpack lock without an inherited fd 9 (mksh close-on-exec): flock taken, callers serialized, no EBADF"; fi

sed 's|flock 8 8>&9 2>/dev/null|flock 9|' "$ENTER" > "$T/mut.sh"
if cmp -s "$ENTER" "$T/mut.sh"; then echo "FAIL MUTATION DID NOT APPLY: bare flock 9"; fail=1
elif check "$T/mut.sh" | grep -q '^L1'; then echo "ok   mutation proved: a bare 'flock 9' goes red (Bad file descriptor)"
else echo "FAIL MUTATION SURVIVED: bare flock 9"; fail=1; fi

[ "$fail" -eq 0 ] && echo "PASS rootfs lock exec path" || { echo "FAILED rootfs lock exec path"; exit 1; }
