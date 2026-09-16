#!/usr/bin/env bash
# WHEN THE COOL BUNDLE BUILD FAILS, make's OWN ERROR HAS TO REACH THE JOB LOG.
#
# generateCoolReleaseAssets is the one upstream task this app cannot skip:
# patches/0001 edits browser/, so assets/dist must be rebuilt from this tree
# instead of lifted out of the pinned APK. It drives that build by shelling out
# to `make -C ../../browser` through Gradle's providers.exec — which CAPTURES
# the subprocess's stdout and stderr into the ExecOutput it returns and forwards
# neither to Gradle's logger. Upstream's two call sites ask only for
# `.result.get()`, so the exit status arrives and the reason is thrown away.
#
# Run 35038703895 is what that cost: 78 of 78 Android tasks executed, this one
# task failed 37 seconds in, and all 1,270 lines of the job log held a Gradle
# stack trace and not one line of make's output. Every run of this workflow
# fetches a ~2 GB Gerrit tree, so a step that can only be diagnosed by guessing
# costs a whole fetch per guess. patches/0004 fixes it; this proves the fix is
# still a fix, and that it is still needed.
#
# COMMENT LINES ARE STRIPPED BEFORE MATCHING, in the patch and in upstream. A
# patch whose // comment merely MENTIONS ignoreExitValue must not satisfy an
# assertion that it SETS it — this fleet has already shipped a tester that
# passed by matching its own documentation, and patches/0004 carries a long
# comment naming every token asserted below.
#
# Everything asserted comes from build.json::prebuilt_mode.cool_bundle_diagnostics.
#
# Usage: ./test-cool-make-failure-is-legible.sh   (needs network: Gerrit REST)
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
BJ="$APP/build.json"
PATCH_DIR="${CLOUD_OFFICE_PATCH_DIR:-$APP/patches}"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo -e "  \033[0;32mok\033[0m: $1"; }
bad() { FAIL=$((FAIL+1)); echo -e "  \033[0;31mFAIL\033[0m: $1"; }
die() { echo "ERROR: $1" >&2; exit 2; }

for t in curl jq base64 sed grep; do
    command -v "$t" >/dev/null 2>&1 || die "$t is not on PATH — refusing to report a verdict this run never computed"
done
[ -f "$BJ" ] || die "$BJ missing"

SPEC='.prebuilt_mode.cool_bundle_diagnostics'
FILE="$(jq -r "$SPEC.file // empty" "$BJ")"
PATCHNO="$(jq -r "$SPEC.patch // empty" "$BJ")"
DISCARD="$(jq -r "$SPEC.upstream_discards_output // empty" "$BJ")"
[ -n "$FILE" ] && [ -n "$PATCHNO" ] && [ -n "$DISCARD" ] \
    || die "build.json::prebuilt_mode.cool_bundle_diagnostics is incomplete — an empty spec must not report green"

mapfile -t MUST_ADD      < <(jq -r "$SPEC.must_add[]?" "$BJ")
mapfile -t MUST_PRESERVE < <(jq -r "$SPEC.must_preserve[]?" "$BJ")
[ "${#MUST_ADD[@]}" -gt 0 ] && [ "${#MUST_PRESERVE[@]}" -gt 0 ] \
    || die "must_add or must_preserve is empty — a spec that requires nothing cannot be covered"

PATCH="$(ls "$PATCH_DIR"/"$PATCHNO"-*.patch 2>/dev/null | head -1)"
[ -n "$PATCH" ] && [ -f "$PATCH" ] \
    || die "no patches/$PATCHNO-*.patch — the legible-failure fix IS the patch, not the spec"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/cool-make-legible.XXXXXX")" || die "cannot mktemp"
trap 'rm -rf "$TMP"' EXIT

# `//`-only lines out. Not a general comment parser: these are line comments in
# a Gradle build script, and a trailing comment on a real statement still leaves
# the statement in the stripped text, which is the behaviour we want.
strip_comments() { sed 's|[[:space:]]*//.*$||' | grep -v '^[[:space:]]*$'; }

echo "== T1: upstream at the pin was read this run =="
UPSTREAM="$TMP/upstream.gradle"
if [ -n "${CLOUD_OFFICE_UPSTREAM_GRADLE:-}" ] && [ -s "${CLOUD_OFFICE_UPSTREAM_GRADLE}" ]; then
    cp "$CLOUD_OFFICE_UPSTREAM_GRADLE" "$UPSTREAM"
    ok "reused the copy of $FILE this run already fetched"
else
    URL="$(jq -r '.upstream.online.url      // empty' "$BJ")"
    PIN="$(jq -r '.upstream.online.revision // empty' "$BJ")"
    [ "${#PIN}" -eq 40 ] || die "upstream.online.revision must be a full 40-char sha, got '${PIN}'"
    BASE="${URL%/*}"; PROJECT="${URL##*/}"
    ENC="$(printf '%s' "$FILE" | sed 's|/|%2F|g')"
    # A 404 means the path is ABSENT at this commit. Anything else — a 500, a
    # timeout, a proxy login page — is UNKNOWN and must never be reported as a
    # verdict about upstream.
    CODE="$(curl -sS --max-time 120 -o "$TMP/raw" -w '%{http_code}' \
            "$BASE/projects/$PROJECT/commits/$PIN/files/$ENC/content" 2>/dev/null)" \
        || die "cannot reach $BASE — no verdict"
    case "$CODE" in
        200) base64 -d <"$TMP/raw" >"$UPSTREAM" 2>/dev/null || die "$FILE did not decode — no verdict"
             [ -s "$UPSTREAM" ] || die "$FILE decoded empty — no verdict"
             ok "read $FILE out of $PROJECT at $PIN ($(wc -l <"$UPSTREAM") lines)" ;;
        404) die "$FILE is ABSENT at $PIN — re-pin, or the whole prebuilt_mode spec is about a file that is gone" ;;
        *)   die "HTTP $CODE reading $FILE — UNKNOWN, not a verdict about upstream" ;;
    esac
fi
export CLOUD_OFFICE_UPSTREAM_GRADLE="$UPSTREAM"

echo "== T2: upstream STILL throws make's output away (the defect is real) =="
# The two generateCool*Assets tasks, up to the afterEvaluate that follows them.
REGION="$TMP/region"
sed -n "/tasks.register('generateCoolDebugAssets')/,/^afterEvaluate/p" "$UPSTREAM" | strip_comments >"$REGION"
if [ ! -s "$REGION" ]; then
    bad "cannot find the generateCool*Assets region in upstream $FILE — the spec is about code that moved"
else
    n="$(grep -cF -- "$DISCARD" "$REGION")"
    if [ "$n" -ge 1 ]; then
        ok "upstream discards make's output: '$DISCARD' appears $n time(s) in the generateCool*Assets region"
    else
        bad "upstream no longer contains '$DISCARD' there — if upstream fixed this itself, DROP patches/$PATCHNO instead of carrying a duplicate of an upstream fix"
    fi
    if grep -qE 'standardOutput|standardError' "$REGION"; then
        bad "upstream already reads the captured streams — patches/$PATCHNO is redundant, re-derive it"
    else
        ok "upstream reads neither standardOutput nor standardError there — make's reason is genuinely lost"
    fi
fi

echo "== T3: patches/$PATCHNO removes the discarding form and touches only $FILE =="
ADDED="$TMP/added"; REMOVED="$TMP/removed"
grep '^+' "$PATCH" | grep -v '^+++' | sed 's/^+//' | strip_comments >"$ADDED"
grep '^-' "$PATCH" | grep -v '^---' | sed 's/^-//' | strip_comments >"$REMOVED"
[ -s "$ADDED" ] || die "patches/$PATCHNO adds no non-comment lines — a patch that is only prose cannot fix anything"
if grep -qF -- "$DISCARD" "$REMOVED"; then
    ok "patches/$PATCHNO removes '$DISCARD'"
else
    bad "patches/$PATCHNO removes no '$DISCARD' — it leaves the discarding call sites in place"
fi
touched="$(grep '^+++ b/' "$PATCH" | sed 's|^+++ b/||' | sort -u)"
if [ "$touched" = "$FILE" ]; then
    ok "patches/$PATCHNO touches exactly $FILE"
else
    bad "patches/$PATCHNO touches [$(echo $touched)] — a diagnostics patch that reaches past $FILE is doing something else too"
fi

echo "== T4: the failure path actually prints, in code and not in a comment =="
for tok in "${MUST_ADD[@]}"; do
    if grep -qF -- "$tok" "$ADDED"; then
        ok "patches/$PATCHNO adds code containing '$tok'"
    else
        bad "patches/$PATCHNO adds no NON-COMMENT line containing '$tok' — without it the output is captured and still never reaches the log"
    fi
done

echo "== T5: what is built did not change =="
for tok in "${MUST_PRESERVE[@]}"; do
    if grep -qF -- "$tok" "$ADDED"; then
        ok "patches/$PATCHNO keeps '$tok'"
    else
        bad "patches/$PATCHNO drops '$tok' — a diagnostics patch that changes the make arguments is not a diagnostics patch"
    fi
done

# ── self-test: strip the printing from the patch and require a red ────
# Without this the tester is four greps that would also pass against a patch
# that captures make's output and silently discards it a second time.
if [ -z "${CLOUD_OFFICE_LEGIBLE_SELFTEST:-}" ]; then
    echo "== T6: a patch that captures the output and never prints it must FAIL this tester =="
    BROKEN="$TMP/broken"; mkdir -p "$BROKEN/patches"
    cp "$PATCH_DIR"/[0-9][0-9][0-9][0-9]-*.patch "$BROKEN/patches/"
    victim="$(ls "$BROKEN/patches/$PATCHNO"-*.patch | head -1)"
    # Delete every added line that carries a must_add token, comment or not.
    for tok in "${MUST_ADD[@]}"; do
        grep -vF -- "$tok" "$victim" >"$victim.new" && mv "$victim.new" "$victim"
    done
    if CLOUD_OFFICE_LEGIBLE_SELFTEST=1 CLOUD_OFFICE_PATCH_DIR="$BROKEN/patches" \
       "$0" >/dev/null 2>&1; then
        bad "self-test: a patches/$PATCHNO with every printing line deleted still PASSED — this tester asserts nothing"
    else
        ok "self-test: stripping the printing from patches/$PATCHNO turns this tester red"
    fi
fi

echo
echo "passed $PASS, failed $FAIL"
[ "$FAIL" -eq 0 ]
