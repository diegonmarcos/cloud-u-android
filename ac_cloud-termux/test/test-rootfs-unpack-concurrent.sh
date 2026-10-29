#!/bin/sh
# The toolbelt unpack must survive several unpackers at once and an update over an existing root.
#
# 2026-10-28 the first start after an update failed outright and fell back to the plain Termux
# shell: hundreds of "Cannot stat: No such file or directory", "Directory renamed before its
# status could be extracted", "Cannot hard link to ./usr/bin/perl". Two unpackers (UnpackService
# through TerminalDebugApi.prepare, and the first login session) wiped and extracted the SAME tree
# at once. rootfs/enter.sh's unpack_rootfs block is run here, with a stub proot and a slow stub
# zstd, by three concurrent callers over an old root holding read-only store files and a /tmp.
# Required (R1) every caller enters, (R2) exactly one unpacks, the others wait and re-read the
# stamp, (R3) the new tree is in place, the old one gone, /tmp kept, no staging left, (R4) no
# tar error, (R5) a broken tarball leaves the working root and its stamp untouched and frees the
# lock, (R6) tar never chowns. Each rule is then broken once in a scratch copy and must go red.
#
# Static, offline, POSIX sh + tar.
set -u

DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENTER="${ENTER:-$DIR/rootfs/enter.sh}"
T="$(mktemp -d)"
trap 'chmod -R u+rwx "$T" 2>/dev/null; rm -rf "$T"' EXIT
fail=0

mkdir -p "$T/bin" "$T/src2/etc" "$T/src2/usr/bin"
cat > "$T/bin/zstd" <<'SH'
#!/bin/sh
# stub: the tarball is plain tar; slow, so concurrent callers overlap
sleep 1; exec cat "$2"
SH
chmod +x "$T/bin/zstd"
echo 'root:x:0:0:root:/root:/bin/sh' > "$T/src2/etc/passwd"
echo v2 > "$T/src2/usr/bin/new"
i=0; while [ $i -lt 200 ]; do echo "$i" > "$T/src2/usr/bin/f$i"; i=$((i + 1)); done
tar -cf "$T/v2.tar" -C "$T/src2" .
head -c 4096 /dev/urandom > "$T/broken.tar"

block() {  # $1 = enter.sh; the wipe + unpack blocks and the top-level unpack step
    sed -n '/^# wipe_rootfs begin$/,/^# The login shell is root/p' "$1"
}

stage() {  # $1 = HERE, $2 = tarball or "", $3 = wanted digest; an old v1 root with ro store + /tmp
    H="$1"; chmod -R u+rwx "$H" 2>/dev/null; rm -rf "$H"
    mkdir -p "$H/rootfs/usr/bin" "$H/rootfs/etc" "$H/rootfs/tmp" "$H/rootfs/opt/.linux-store/store/p/lib"
    echo v1 > "$H/rootfs/usr/bin/old"; echo 'root:x:0:0::/root:/bin/sh' > "$H/rootfs/etc/passwd"
    echo keep > "$H/rootfs/tmp/keep"; echo v1 > "$H/rootfs/.cloud-rootfs.sha256"
    echo ro > "$H/rootfs/opt/.linux-store/store/p/lib/f"; chmod 0444 "$H/rootfs/opt/.linux-store/store/p/lib/f"
    chmod 0555 "$H/rootfs/opt/.linux-store/store/p/lib" "$H/rootfs/opt/.linux-store/store/p"
    [ -z "$2" ] || cp "$2" "$H/rootfs.tar.zst"
    echo "$3" > "$H/rootfs.sha256"
    cat > "$H/proot" <<'SH'
#!/bin/sh
while [ "$#" -gt 0 ] && [ "$1" != tar ]; do shift; done
exec "$@"
SH
    chmod +x "$H/proot"
}

driver() {  # $1 = enter.sh, $2 = HERE; writes $2/drv.sh
    { echo 'set -eu'; echo "HERE='$2'"; echo 'ROOTFS="$HERE/rootfs"'; echo 'STAMP="$ROOTFS/.cloud-rootfs.sha256"'
      echo 'fallback() { echo "FALLBACK: $*" >&2; exit 3; }'
      block "$1"; echo 'echo ENTER-OK'; } > "$2/drv.sh"
}

run_concurrent() {  # $1 = enter.sh; sets H, out, rcs
    stage "$T/here" "$T/v2.tar" v2; driver "$1" "$H"
    for n in 1 2 3; do PATH="$T/bin:$PATH" sh "$H/drv.sh" > "$T/out$n" 2>&1 & eval "p$n=\$!"; done
    rcs=""; for n in 1 2 3; do eval "wait \$p$n"; rcs="$rcs$?"; done
    out="$(cat "$T/out1" "$T/out2" "$T/out3")"
}

check_concurrent() {  # prints violations
    [ "$rcs" = 000 ] || echo "R1 exit codes $rcs: a caller fell back to the plain shell"
    [ "$(printf '%s\n' "$out" | grep -c ENTER-OK)" -eq 3 ] || echo "R1 not every caller entered"
    n="$(printf '%s\n' "$out" | grep -c 'Unpacking the agent toolbelt')"
    [ "$n" -eq 1 ] || echo "R2 $n callers unpacked, want exactly 1"
    [ "$(cat "$H/rootfs/.cloud-rootfs.sha256" 2>/dev/null)" = v2 ] || echo "R3 the stamp is not v2"
    [ -f "$H/rootfs/usr/bin/new" ] && [ -f "$H/rootfs/usr/bin/f199" ] || echo "R3 the new tree is incomplete"
    [ ! -e "$H/rootfs/usr/bin/old" ] && [ ! -e "$H/rootfs/opt" ] || echo "R3 old root entries survived"
    [ -f "$H/rootfs/tmp/keep" ] || echo "R3 the root's /tmp was lost"
    ls -d "$H"/rootfs.new.* "$H"/rootfs.old.* >/dev/null 2>&1 && echo "R3 staging or old roots left behind"
    [ ! -e "$H/rootfs.tar.zst" ] || echo "R3 the staged tarball was not consumed"
    printf '%s\n' "$out" | grep -q 'tar:\|Cannot\|FALLBACK' && echo "R4 errors: $(printf '%s\n' "$out" | grep 'tar:\|Cannot\|FALLBACK' | head -2 | tr '\n' ' ')"
}

run_broken() {  # $1 = enter.sh; v1 root, broken tarball, want v3; then a good tarball must unpack
    stage "$T/here" "$T/broken.tar" v3; driver "$1" "$H"
    PATH="$T/bin:$PATH" sh "$H/drv.sh" > "$T/outb" 2>&1; brc=$?
    bout="$(cat "$T/outb")"
    cp "$T/v2.tar" "$H/rootfs.tar.zst"
    PATH="$T/bin:$PATH" timeout 20 sh "$H/drv.sh" > "$T/outc" 2>&1; crc=$?
}
check_broken() {
    [ "$brc" -eq 3 ] || echo "R5 a broken tarball did not fall back (rc $brc)"
    case "$bout" in *"previous root"*) ;; *) echo "R5 the failure does not say the previous root is kept" ;; esac
    [ "$crc" -eq 0 ] || echo "R5 after a failure the next unpack did not succeed (rc $crc): lock or staging left"
}
broken_root_kept() {  # between the two runs: checked by rerunning the first half only
    stage "$T/here" "$T/broken.tar" v3; driver "$1" "$H"
    PATH="$T/bin:$PATH" sh "$H/drv.sh" >/dev/null 2>&1
    [ -f "$H/rootfs/usr/bin/old" ] && [ "$(cat "$H/rootfs/.cloud-rootfs.sha256")" = v1 ] || echo "R5 a failed unpack damaged the working root"
    ls -d "$H"/rootfs.new.* >/dev/null 2>&1 && echo "R5 a failed unpack left its staging dir"
}

all() {  # $1 = enter.sh; prints every violation
    run_concurrent "$1"; check_concurrent
    broken_root_kept "$1"
    run_broken "$1"; check_broken
    grep -q -- '-C "\$ROOTFS"' "$1" || echo "R7 the tarball is not extracted at its final path (link2symlink links are absolute)"
    grep -q 'tar --no-same-owner' "$1" || echo "R6 tar may try to chown (no --no-same-owner)"
}

[ -f "$ENTER" ] || { echo "FAIL no enter.sh at $ENTER"; exit 1; }
grep -q '^# unpack_rootfs begin$' "$ENTER" || { echo "FAIL enter.sh has no unpack_rootfs block"; exit 1; }
v="$(all "$ENTER")"
if [ -n "$v" ]; then echo "FAIL"; echo "$v"; fail=1; else echo "ok   3 concurrent unpackers over an old read-only root: one unpacks, all enter, swap is atomic, a broken tarball keeps the root"; fi

mutant() {  # $1 = sed expr, $2 = what it breaks, $3 = rule that must fire
    sed "$1" "$ENTER" > "$T/mut.sh"
    if cmp -s "$ENTER" "$T/mut.sh"; then echo "FAIL MUTATION DID NOT APPLY: $2"; fail=1; return; fi
    if all "$T/mut.sh" | grep -q "^$3"; then echo "ok   mutation proved: $2 goes red"; else echo "FAIL MUTATION SURVIVED: $2"; fail=1; fi
}
mutant 's|^    rootfs_lock$|    :|' "no single-flight lock" R2
mutant 's|^        UNPACK_OLD="\$HERE/rootfs.old.\$\$"$|        UNPACK_OLD=""|' "extracting over the live root" R
mutant 's|tar --no-same-owner --no-same-permissions|tar|' "tar that chowns" R6
mutant 's|if \[ -n "\$UNPACK_OLD" \]; then mv "\$UNPACK_OLD" "\$ROOTFS" 2>/dev/null \|\| true; UNPACK_OLD=""; fi|:|' "a failure that does not put the previous root back" R5

[ "$fail" -eq 0 ] && echo "PASS rootfs unpack concurrency" || { echo "FAILED rootfs unpack concurrency"; exit 1; }
