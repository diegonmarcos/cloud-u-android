#!/usr/bin/env bash
#
# Cloud Office — does every configure flag we pass actually EXIST upstream,
# and does the one that sets our package id actually reach applicationId?
#
# ── THE FAILURE THIS EXISTS FOR ───────────────────────────────────────────
# build.json::upstream.online.configure carried "--with-android-package-name":
# "com.diegonmarcos.cloudoffice" for a day. No such option exists at the pinned
# revision — upstream calls it --with-app-package-name. autoconf does not fail on
# an unknown --with-*; it prints "unrecognized options" to stderr and CARRIES ON.
# So configure would have succeeded, $with_app_package_name would have stayed
# empty, APP_PACKAGE_NAME would have fallen back to its default org.collabora.app,
# and the ~$35 NDK megabuild would have produced an APK whose applicationId is
# upstream's, not ours — after which the store entry, the launcher's
# installed-app probe and the fleet manifest all name an app that does not exist.
# The build would have been GREEN. That is the whole point: this class of defect
# is invisible to every check that only asks "did configure exit 0".
#
# ── WHY IT IS DERIVED AND NOT A LIST ──────────────────────────────────────
# Nothing here hardcodes a flag name, and that is deliberate: a hardcoded
# expectation is a second copy of the thing under test, and it goes stale in the
# same direction as the bug. The required flag is computed from upstream itself,
# every run:
#
#   android/app/appSettings.gradle.in  says  applicationId '@APP_PACKAGE_NAME@'
#   configure.ac                       says  APP_PACKAGE_NAME="$with_app_package_name"
#   therefore the flag we MUST set is   --with-app-package-name
#
# If upstream renames the option, the placeholder or the variable, this test
# recomputes and tells us the new name instead of passing on a stale one.
#
# Network failure is a FAILURE, not a skip — same rule as tests/patches-apply.sh
# and tests/test-engine-artefacts-in-apk.sh. The claim is about a remote object;
# a test that shrugged at an unreachable remote would assert nothing. It reads
# two text files over Gerrit's REST API (~135 KB), not a 471 MB clone.
#
#   ./tests/test-configure-flags-exist.sh              # check every flag
#   ./tests/test-configure-flags-exist.sh --self-test  # also prove failures fail
#
set -uo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_JSON="$APP_DIR/build.json"

PASS=0; FAILED=0
pass() { PASS=$((PASS + 1));   printf '\033[0;32mok: %s\033[0m\n' "$1"; }
bad()  { FAILED=$((FAILED + 1)); printf '\033[0;31mFAIL: %s\033[0m\n' "$1" >&2; }
die()  { printf '\033[0;31mFAIL: %s\033[0m\n' "$1" >&2; exit 1; }

command -v jq     >/dev/null 2>&1 || die "jq is not on PATH"
command -v curl   >/dev/null 2>&1 || die "curl is not on PATH"
command -v base64 >/dev/null 2>&1 || die "base64 is not on PATH"
[ -f "$BUILD_JSON" ] || die "no build.json at $BUILD_JSON"

URL="$(jq -r '.upstream.online.url      // empty' "$BUILD_JSON")"
PIN="$(jq -r '.upstream.online.revision // empty' "$BUILD_JSON")"
APPID="$(jq -r '.android.application_id // empty' "$BUILD_JSON")"

[ -n "$URL"   ] || die "build.json::upstream.online.url is empty"
[ -n "$PIN"   ] || die "build.json::upstream.online.revision is empty"
[ -n "$APPID" ] || die "build.json::android.application_id is empty"
[ "${#PIN}" -eq 40 ] || die "upstream.online.revision must be a full 40-char sha, got ${#PIN}"

# https://host/project  →  base=https://host  project=project
BASE="${URL%/*}"
PROJECT="${URL##*/}"

# Gerrit's file-content endpoint wants the path percent-encoded, and answers
# base64. A 404 here means the path is ABSENT FROM THE TREE, not merely
# unchanged by that commit — verified against paths this commit does not touch.
fetch() {  # fetch <path-in-repo> → contents on stdout, non-zero if absent
    local enc; enc="$(printf '%s' "$1" | sed 's|/|%2F|g')"
    local url="$BASE/projects/$PROJECT/commits/$PIN/files/$enc/content"
    local code; code="$(curl -sS --max-time 90 -o "$TMP/raw" -w '%{http_code}' "$url" 2>"$TMP/curl.err")" || return 1
    [ "$code" = "200" ] || return 1
    base64 -d <"$TMP/raw" 2>/dev/null
}

TMP="$(mktemp -d "${TMPDIR:-/tmp}/cloud-office-flags.XXXXXX")" || die "cannot mktemp"
trap 'rm -rf "$TMP"' EXIT

CONFIGURE_AC="$TMP/configure.ac"
fetch configure.ac >"$CONFIGURE_AC" || die "cannot read configure.ac at $PIN from $BASE/$PROJECT — refusing to report a green check on an unfetched pin"
[ -s "$CONFIGURE_AC" ] || die "configure.ac at $PIN came back empty"
pass "fetched configure.ac at $PIN ($(wc -c <"$CONFIGURE_AC") bytes)"

# ── does an option exist upstream? ─────────────────────────────────────────
# autoconf writes AC_ARG_WITH(name,...) or AC_ARG_WITH([name],...). Match the
# macro call, never the AS_HELP_STRING text: the help string is PROSE, it is
# where the flag is described rather than declared, and a fleet tester has
# already passed by matching its own documentation instead of a real call.
# Comment lines are stripped first for the same reason.
# NOT `sed ... | grep -q`. Under `set -o pipefail` that pipeline reports SED's
# status, and `grep -q` exits the instant it matches, which SIGPIPEs sed for 141
# — so the pipeline returns non-zero ON A SUCCESSFUL MATCH and every flag reads
# as absent. The first draft of this file did exactly that and reported all four
# real flags missing. Strip once into a file, then grep the file.
CONFIGURE_NOCOMMENT="$TMP/configure.ac.nocomment"
sed 's/^[[:space:]]*#.*$//; s/^dnl .*$//' "$CONFIGURE_AC" >"$CONFIGURE_NOCOMMENT"

declared() {  # declared <option-name-without-with->
    grep -Eq "AC_ARG_WITH\(\[?$1\]?[,)]" "$CONFIGURE_NOCOMMENT"
}

echo "== T1: every --with-* flag we pass is declared at the pinned revision =="
mapfile -t FLAGS < <(jq -r '(.upstream.online.configure // {}) | keys[] | select(startswith("--with-"))' "$BUILD_JSON")
[ "${#FLAGS[@]}" -gt 0 ] || die "build.json::upstream.online.configure declares no --with-* flags — this tester would assert nothing"

for flag in "${FLAGS[@]}"; do
    name="${flag#--with-}"
    if declared "$name"; then
        pass "$flag is a real AC_ARG_WITH"
    else
        bad "$flag does NOT exist at $PIN — autoconf will warn 'unrecognized options' and silently use the default. Grep configure.ac for the real name."
    fi
done

echo "== T2: the flag that sets applicationId is the one we actually pass =="
# Step 1 — what placeholder does the generated gradle file put in applicationId?
SETTINGS_IN="android/app/appSettings.gradle.in"
GRADLE_IN="$TMP/appSettings.gradle.in"
if ! fetch "$SETTINGS_IN" >"$GRADLE_IN" || [ ! -s "$GRADLE_IN" ]; then
    die "cannot read $SETTINGS_IN at $PIN — the applicationId chain cannot be verified, and a silent pass here is exactly the bug this file exists for"
fi
PLACEHOLDER="$(grep -oE "applicationId[[:space:]]*'@[A-Za-z0-9_]+@'" "$GRADLE_IN" | head -1 | grep -oE '@[A-Za-z0-9_]+@' | tr -d '@')"
[ -n "$PLACEHOLDER" ] || die "no applicationId '@VAR@' placeholder in $SETTINGS_IN — upstream changed how the id is set; re-read this chain by hand"
pass "$SETTINGS_IN sets applicationId from @$PLACEHOLDER@"

# Step 2 — which --with-* does configure.ac assign that variable from?
WITHVAR="$(grep -oE "^[[:space:]]*$PLACEHOLDER=\"\\\$with_[A-Za-z0-9_]+\"" "$CONFIGURE_NOCOMMENT" \
           | head -1 | grep -oE 'with_[A-Za-z0-9_]+')"
[ -n "$WITHVAR" ] || die "configure.ac never assigns $PLACEHOLDER from a \$with_* variable — the id is no longer set by a configure flag; re-read this chain by hand"
REQUIRED="--with-$(printf '%s' "${WITHVAR#with_}" | tr '_' '-')"
pass "configure.ac takes $PLACEHOLDER from \$$WITHVAR, so the required flag is $REQUIRED"

# Step 3 — do we pass it, and does it carry OUR id?
GOT="$(jq -r --arg k "$REQUIRED" '(.upstream.online.configure // {})[$k] // empty' "$BUILD_JSON")"
if [ -z "$GOT" ]; then
    bad "build.json does not pass $REQUIRED — applicationId would fall back to upstream's default, and the APK would not be $APPID"
elif [ "$GOT" != "$APPID" ]; then
    bad "$REQUIRED is '$GOT' but android.application_id is '$APPID' — the APK's id and the id every other surface installs by would disagree"
else
    pass "$REQUIRED passes $APPID, matching android.application_id"
fi

# ── self-test: the failure paths must actually fail ────────────────────────
# An assertion nobody has watched go red is a decoration. Both branches that
# matter are exercised against a MUTATED COPY of build.json, so a green run of
# this file means the checks above can still distinguish good from bad.
if [ "${1:-}" = "--self-test" ]; then
    echo "== self-test: mutate build.json and require a red =="
    probe() {  # probe <description> <jq-mutation>
        local desc="$1" mutation="$2" tmpdir
        tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/cloud-office-selftest.XXXXXX")"
        mkdir -p "$tmpdir/tests"
        jq "$mutation" "$BUILD_JSON" >"$tmpdir/build.json" || { rm -rf "$tmpdir"; bad "self-test: jq mutation failed for: $desc"; return; }
        cp "$0" "$tmpdir/tests/$(basename "$0")"
        if bash "$tmpdir/tests/$(basename "$0")" >"$tmpdir/out" 2>&1; then
            sed 's/^/    /' "$tmpdir/out" >&2
            bad "self-test: '$desc' was accepted — this tester cannot see the defect it exists for"
        else
            pass "self-test: '$desc' is rejected"
        fi
        rm -rf "$tmpdir"
    }
    # The historical defect, verbatim: the flag renamed to one that does not exist.
    probe "a --with-* flag that does not exist upstream" \
          '.upstream.online.configure |= (with_entries(if .key == "--with-app-package-name" then .key = "--with-android-package-name" else . end))'
    # The subtler one: the right flag carrying the wrong value.
    probe "the package-name flag disagreeing with android.application_id" \
          '.upstream.online.configure["--with-app-package-name"] = "com.example.wrong"'
fi

echo
if [ "$FAILED" -eq 0 ]; then
    printf '\033[0;32mALL CHECKS PASSED\033[0m — %s assertions against %s\n' "$PASS" "$PIN"
    exit 0
fi
printf '\033[0;31m%s FAILED\033[0m (%s passed) — against %s\n' "$FAILED" "$PASS" "$PIN"
exit 1
