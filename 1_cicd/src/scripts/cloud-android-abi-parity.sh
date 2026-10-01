#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-abi-parity — one run, one versionCode per package  ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY (#636). A per-ABI app is built by one matrix leg per ABI, and its rootfs
# companion is published by every leg under ONE applicationId. The companion's
# versionCode is derived from COMMS_BUILD_TIMESTAMP (rootfs-lib/build.gradle),
# and each leg used to stamp its own clock, so the arm64 and x86_64 companions
# of one build carried versionCodes minutes apart. The stamp is now one job
# output per run; this checks that the APKs which came out actually agree,
# reading each APK rather than trusting the stamp that should have made them.
#
#   cloud-android-abi-parity.sh <dir>
#       <dir>/<leg>/…/*.apk, one subdirectory per ABI leg (what
#       actions/download-artifact writes for a pattern). Every package that
#       more than one leg built must carry exactly one versionCode.
#
# A package only one leg built has nothing to be compared with (the other
# leg's gate skipped it, or that leg failed and the run is red already): said,
# not failed. An APK that cannot be read IS a failure — a check that read
# nothing and passed is exactly the defect this file exists to close.
#
# Every run first drives the verdict over a planted disagreement, so a green
# here is never the green of a comparison that cannot fail.
set -eu

# The verdict, over lines "<leg> <package> <versionCode> <apk>" on stdin.
_verdict() {
    awk '
        { legs[$2] = legs[$2] " " $1 "=" $3 " (" $4 ")"
          if (!(($2, $3) in seen))  { seen[$2, $3] = 1;  codes[$2]++ }
          if (!(($2, $1) in built)) { built[$2, $1] = 1; nlegs[$2]++ } }
        END {
            bad = 0
            for (p in legs) {
                if (nlegs[p] < 2)      print "only one leg built " p ":" legs[p] " — nothing to compare it with"
                else if (codes[p] > 1) { print "FAIL " p " carries " codes[p] " versionCodes in one run:" legs[p]; bad = 1 }
                else                   print "ok   " p " carries one versionCode:" legs[p]
            }
            if (NR == 0) print "no leg built an APK this run (every gate skipped) — nothing to compare"
            exit bad
        }'
}

_self_check() {
    printf 'arm64 p.a 3001000 a.apk\nx86_64 p.a 3001000 b.apk\n' | _verdict >/dev/null || {
        echo "FAIL self-check: two legs that agree were judged unequal" >&2; return 1; }
    if printf 'arm64 p.a 3001000 a.apk\nx86_64 p.a 3001002 b.apk\n' | _verdict >/dev/null; then
        echo "FAIL self-check: one package with two versionCodes passed — this check cannot fail" >&2; return 1
    fi
    echo "self-check: agreeing legs pass, a planted 3001000/3001002 split fails"
}

# "<leg> <package> <versionCode> <apk>" for every APK under $1, via the SDK's
# aapt2 — the same reader the fork engines trust for an APK's identity.
_read() {
    bt="$(ls -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/nonexistent}}"/build-tools/* 2>/dev/null | sort -V | tail -1)"
    aapt2=""
    for t in "$bt/aapt2" aapt2; do
        if [ -x "$t" ] || command -v "$t" >/dev/null 2>&1; then aapt2="$t"; break; fi
    done
    [ -n "$aapt2" ] || {
        echo "FAIL no aapt2 (ANDROID_HOME=${ANDROID_HOME:-unset}) — no versionCode can be read, so parity cannot be claimed" >&2
        return 1; }
    list="$(mktemp)"
    find "$1" -mindepth 2 -type f -name '*.apk' | sort > "$list"
    while IFS= read -r apk; do
        leg="${apk#"$1"/}"; leg="${leg%%/*}"
        first="$("$aapt2" dump badging "$apk" 2>/dev/null | sed -n 1p)" || first=""
        pkg="$(printf '%s\n' "$first" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
        code="$(printf '%s\n' "$first" | sed -n "s/.* versionCode='\([0-9]*\)'.*/\1/p")"
        [ -n "$pkg" ] && [ -n "$code" ] || {
            echo "FAIL cannot read package/versionCode from $apk (aapt2 said: ${first:-nothing})" >&2
            rm -f "$list"; return 1; }
        printf '%s %s %s %s\n' "$leg" "$pkg" "$code" "$(basename "$apk")"
    done < "$list"
    rm -f "$list"
}

[ $# -eq 1 ] && [ -d "$1" ] || {
    echo "usage: $(basename "$0") <dir with one subdirectory per ABI leg>" >&2; exit 2; }
_self_check
found="$(_read "${1%/}")"
if [ -n "$found" ]; then printf '%s\n' "$found" | _verdict; else _verdict </dev/null; fi
