#!/usr/bin/env bash
# EVERY THING patches/0002 MUST REMOVE STILL EXISTS UPSTREAM, AND THERE ARE SIX.
#
# build.json::prebuilt_mode.neutralise is a SPECIFICATION for a patch that has
# not been written. A specification nobody checks rots into fiction: upstream
# moves a task, the patch is written against this list, `git am` fails on a
# hunk that matches nothing, and the failure names a line number instead of the
# reason. Worse, a patch that removes five of the six anchors produces a build
# that is green right up to the point it asks for Node, or ships assets/dist
# twice.
#
# So this reads each anchor's file OUT OF GERRIT at upstream.online.revision and
# requires the anchor text to be present. It asserts the PROBLEM, exactly like
# test-prebuilt-assets-need-rebranding.sh: the fix lives in a build that does
# not run yet, so what can be checked today is that its targets are real.
#
# WHY THE ANCHOR TEXT AND NOT THE LINE NUMBER. line_hint in build.json is for
# orientation and is deliberately NOT asserted — an upstream commit that adds a
# comment moves every line below it and would red this for nothing. The anchor
# is a string that has to survive for the patch to apply.
#
# COMMENT LINES ARE STRIPPED BEFORE MATCHING. A gradle file that merely
# *mentions* copyUnpackAssets in a `//` comment must not satisfy an assertion
# that the task is declared — this fleet has already shipped a tester that
# passed by matching a commented-out call, and another that matched its own
# documentation.
#
# Usage: ./test-prebuilt-mode-anchors-exist.sh      (needs network: Gerrit REST)
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
BJ="$APP/build.json"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo -e "  \033[0;32mok\033[0m: $1"; }
bad() { FAIL=$((FAIL+1)); echo -e "  \033[0;31mFAIL\033[0m: $1"; }
die() { echo "ERROR: $1" >&2; exit 2; }

for t in curl jq base64 sed; do
    command -v "$t" >/dev/null 2>&1 || die "$t is not on PATH — refusing to report a verdict this run never computed"
done
[ -f "$BJ" ] || die "$BJ missing"

URL="$(jq -r '.upstream.online.url      // empty' "$BJ")"
PIN="$(jq -r '.upstream.online.revision // empty' "$BJ")"
[ -n "$URL" ] || die "upstream.online.url is empty"
[ "${#PIN}" -eq 40 ] || die "upstream.online.revision must be a full 40-char sha, got '${PIN}' (${#PIN} chars)"

BASE="${URL%/*}"; PROJECT="${URL##*/}"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/cloud-office-anchors.XXXXXX")" || die "cannot mktemp"
trap 'rm -rf "$TMP"' EXIT

# Gerrit's file-content endpoint: percent-encoded path, base64 body. A 404 here
# means the path is ABSENT FROM THE TREE at this commit, not merely untouched
# by it. Anything else — a 500, a timeout, a proxy page — is UNKNOWN and must
# never be reported as "the anchor is gone".
fetch() {  # fetch <path> <dest> → 0 ok, 3 absent, 4 unreadable
    local enc; enc="$(printf '%s' "$1" | sed 's|/|%2F|g')"
    local code
    code="$(curl -sS --max-time 120 -o "$TMP/raw" -w '%{http_code}' \
            "$BASE/projects/$PROJECT/commits/$PIN/files/$enc/content" 2>/dev/null)" || return 4
    case "$code" in
        200) base64 -d <"$TMP/raw" >"$2" 2>/dev/null || return 4
             [ -s "$2" ] || return 4
             return 0 ;;
        404) return 3 ;;
        *)   return 4 ;;
    esac
}

COUNT="$(jq '.prebuilt_mode.neutralise | length' "$BJ")"
# FAIL CLOSED ON AN EMPTY SPEC. Zero anchors would run the loop zero times and
# exit 0 — a green tick for a specification that says nothing.
[ "$COUNT" -ge 1 ] 2>/dev/null \
    || die "prebuilt_mode.neutralise is empty — a spec with no anchors must not report green"

echo "== T1: the pinned tree is reachable and this run read it =="
CONTROL="$TMP/control"
if fetch "android/lib/build.gradle" "$CONTROL"; then
    ok "android/lib/build.gradle at $PIN ($(wc -c <"$CONTROL") bytes)"
else
    die "cannot read android/lib/build.gradle at $PIN — refusing to report on anchors this run never fetched"
fi

echo "== T2: every declared anchor is really there, in code and not in a comment =="
declare -A SEEN=()
for i in $(seq 0 $((COUNT - 1))); do
    id="$(jq  -r ".prebuilt_mode.neutralise[$i].id"     "$BJ")"
    file="$(jq -r ".prebuilt_mode.neutralise[$i].file"  "$BJ")"
    anc="$(jq -r ".prebuilt_mode.neutralise[$i].anchor" "$BJ")"

    if [ -z "$id" ] || [ "$id" = "null" ] || [ -z "$anc" ] || [ "$anc" = "null" ] \
       || [ -z "$file" ] || [ "$file" = "null" ]; then
        bad "neutralise[$i] is missing id, file or anchor — an entry that names nothing cannot be patched"
        continue
    fi
    if [ -n "${SEEN[$id]:-}" ]; then
        bad "neutralise[$i] repeats id '$id' — two entries with one name means one of them is never reported on"
        continue
    fi
    SEEN[$id]=1

    dest="$TMP/$(printf '%s' "$file" | tr '/' '_')"
    if [ ! -f "$dest" ]; then
        fetch "$file" "$dest"; rc=$?
        case $rc in
            3) bad "$id: $file is ABSENT from the tree at $PIN — the spec describes a file upstream no longer has"; continue ;;
            4) bad "$id: $file could not be read at $PIN — reported as UNREADABLE, not as missing"; continue ;;
        esac
    fi

    # Strip // and * comment lines before matching, so prose about a task can
    # never stand in for the task's declaration.
    code="$TMP/code_$(printf '%s' "$file" | tr '/' '_')"
    sed -e 's|//.*||' -e 's|^[[:space:]]*\*.*||' "$dest" >"$code"

    if grep -qF -- "$anc" "$code"; then
        n="$(grep -cF -- "$anc" "$code")"
        ok "$id: '$anc' present in $file ($n occurrence(s))"
    else
        bad "$id: '$anc' is NOT in $file at $PIN (outside comments). Upstream moved or renamed it, so patches/0002 would apply to nothing. Re-read the file and update prebuilt_mode.neutralise before writing the patch."
    fi
done

echo "== T3: the two externalNativeBuild declarations are genuinely TWO =="
# The load-bearing count from _doc_BLOCKER_configure_gate (2). Removing one and
# believing the native build is gone is the specific mistake this guards, and a
# bare occurrence count is the only way to see it.
N="$(sed -e 's|//.*||' "$CONTROL" | grep -cF 'externalNativeBuild')"
if [ "$N" -eq 2 ]; then
    ok "android/lib/build.gradle declares externalNativeBuild exactly twice, as the spec says"
else
    bad "android/lib/build.gradle declares externalNativeBuild $N time(s), not 2 — prebuilt_mode.neutralise is calibrated to two and a patch written from it would leave $((N - 2)) behind (or fail on a hunk that is gone)"
fi

echo "== T4: the COOL bundle really is on the RELEASE graph =="
# The anchor _doc_BLOCKER_configure_gate missed. It is only a blocker because
# afterEvaluate wires it into generateReleaseAssets; if upstream ever stops
# doing that, the anchor can be dropped from the spec — but that is a decision,
# not something to discover from a build that suddenly wants Node.
if grep -qF 'generateReleaseAssets.dependsOn' "$CONTROL" \
   && grep -F 'generateReleaseAssets.dependsOn' "$CONTROL" | grep -qF 'generateCoolReleaseAssets'; then
    ok "generateReleaseAssets dependsOn generateCoolReleaseAssets — the npm/webpack build IS on the release graph and must be neutralised"
else
    bad "generateReleaseAssets no longer dependsOn generateCoolReleaseAssets at $PIN — prebuilt_mode's cool-bundle-release anchor may be obsolete; confirm before keeping it"
fi

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
