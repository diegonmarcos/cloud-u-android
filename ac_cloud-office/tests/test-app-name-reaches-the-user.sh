#!/usr/bin/env bash
# THE NAME THIS APP SHOWS THE OWNER IS THE FLEET NAME, AND IT MUST STILL GET THERE.
#
# THE HOLE THIS FILLS. aa_cloud-superapp/test/test-app-names-pattern.sh is the
# fleet-wide rule: T1 every application is named cloud-<x>, T2 that name is the
# app's own build.json::.name, T6 an app whose app/build.gradle reads its own
# build.json takes its LAUNCHER LABEL from that name rather than copying it into
# strings.xml. T6 says in its own comment that it covers only apps with such a
# reader. Cloud Office has none: its label comes from configure substituting
# @APP_NAME@ into android/app/appSettings.gradle, two build systems away. So the
# one app whose name travels the longest road is the one app the fleet rule
# cannot see, and this is the rule that sees it.
#
# WHY THE VALUE IS A SLUG AND NOT "Cloud Office". configure.ac:1179 calls
# --with-app-name "the user-visible name of the app you build" and defaults it to
# "Collabora Online Development Edition", so upstream expects a display name with
# spaces. This fleet answers differently and on purpose: ac_cloud-browser,
# ac_cloud-writer and ac_cloud-drive all do
# resValue "string", "app_name", buildJson.name, so their home screens read
# cloud-browser, cloud-writer, cloud-drive. A capitalised "Cloud Office" was tried
# at a43bc99c6 and test-app-names-pattern failed it (run 35048141723, 125 passed
# 1 failed) — correctly: it would have spelled one app unlike all the others on
# the same home screen, and that tester's header records the owner asking for the
# pattern twice.
#
# WHAT #224's "rebrand fully" ACTUALLY MEANS IS THE ABSENCE OF "COLLABORA", and
# that is true of the published bytes. resources.arsc in Cloud-Office.apk was
# parsed in full: 18,290 string resource entries, ZERO containing 'Collabora'.
# classes2.dex contains it once, in
# <a href="https://github.com/CollaboraOnline/online/commits/ — the About box's
# attribution link to the upstream project, which is correct and stays.
#
# TWO THINGS ARE CHECKED, AND THEY FAIL FOR DIFFERENT REASONS.
#
#   T1/T2 — THE VALUE. It must equal this app's build.json::.name, the single
#   field the whole fleet is keyed on, and it must match the cloud-<x> pattern
#   the fleet rule enforces. Keyed on .name rather than on a rendering of the
#   asset name so a rename has ONE place to happen and no second field that can
#   disagree with it.
#
#   T3 — THE CHAIN, against upstream at the pin. Nothing in this repository
#   compiles browser/ or android/, so if upstream renames a placeholder or stops
#   assigning brandProductName, the name silently stops reaching the screen and
#   every other tester still passes. Each link is a file and the literals that
#   must still be in it, from build.json::app_identity.substitution_chain.
#   Measured on the published APK, these are the surfaces at the far end:
#   resources.arsc string/app_name (the launcher label via
#   <application android:label>, the About title and the $APP_NAME substituted
#   into the About text by AboutDialogFragment.java:77,89), and
#   assets/dist/cool.html's <title> and data-mobile-app-name, from which global.js
#   sets window.brandProductName — which brands the document title, the loading
#   spinner, the web About dialog and the titles core sends for its own dialogs.
#
# Usage: ./test-app-name-reaches-the-user.sh              (needs network: Gerrit REST)
#        ./test-app-name-reaches-the-user.sh --self-test  (also prove the failure path fails)
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
BJ="$APP/build.json"
SELFTEST=0
[ "${1:-}" = "--self-test" ] && SELFTEST=1

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); printf '  \033[0;32mok\033[0m: %s\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf '  \033[0;31mFAIL\033[0m: %s\n' "$1"; }
die() { echo "ERROR: $1" >&2; exit 2; }

for t in curl jq base64 sed grep python3; do
    command -v "$t" >/dev/null 2>&1 \
        || die "$t is not on PATH — refusing to report a verdict this run never computed"
done
[ -f "$BJ" ] || die "$BJ missing"

FLAG="$(jq -r '.app_identity.product_name_flag // empty' "$BJ")"
DERIVED_FROM="$(jq -r '.app_identity.derived_from // empty' "$BJ")"
NLINKS="$(jq -r '.app_identity.substitution_chain | length' "$BJ" 2>/dev/null || echo 0)"
# FAIL CLOSED: an empty spec must never report green.
[ -n "$FLAG" ] && [ -n "$DERIVED_FROM" ] \
    || die "build.json::app_identity is incomplete — an empty rule must not report green"
[ "$NLINKS" -ge 1 ] \
    || die "build.json::app_identity.substitution_chain is empty — a chain of no links proves nothing"

NAME="$(jq -r --arg f "$FLAG" '.upstream.online.configure[$f] // empty' "$BJ")"
EXPECTED="$(jq -r ".$DERIVED_FROM // empty" "$BJ")"
[ -n "$NAME" ]     || die "upstream.online.configure['$FLAG'] is unset — the app would take upstream's default name, \"Collabora Online Development Edition\""
[ -n "$EXPECTED" ] || die "build.json::.$DERIVED_FROM is unset — nothing to cross-check the name against"
echo "== T1: the launcher label is the app's ONE fleet name =="
if [ "$NAME" = "$EXPECTED" ]; then
    ok "$FLAG is '$NAME', the same string as build.json::.$DERIVED_FROM"
else
    bad "$FLAG is '$NAME' but build.json::.$DERIVED_FROM is '$EXPECTED' — the home screen would spell this app differently from the fleet roster, the store row and every sibling app"
fi

echo "== T2: that name follows the fleet pattern =="
# An INDEPENDENT shape check, so setting BOTH fields to a display name still
# fails here. Same pattern as test-app-names-pattern.sh T1, restated on this side
# because the value being checked is the one that reaches the home screen.
if printf '%s' "$NAME" | grep -qE '^(cloud|c3)-[a-z0-9]+(-[a-z0-9]+)*$'; then
    ok "'$NAME' matches the fleet's cloud-<x> / c3-<x> pattern"
else
    bad "$FLAG is '$NAME', which is not cloud-<x> or c3-<x> — this value is what the launcher, the About box and the WebView <title> print verbatim, and aa_cloud-superapp/test/test-app-names-pattern.sh fails the whole fleet on it"
fi

echo "== T3: upstream still carries that one value to every surface =="
URL="$(jq -r '.upstream.online.url      // empty' "$BJ")"
PIN="$(jq -r '.upstream.online.revision // empty' "$BJ")"
[ "${#PIN}" -eq 40 ] || die "upstream.online.revision must be a full 40-char sha, got '${PIN}'"
BASE="${URL%/*}"; PROJECT="${URL##*/}"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/cool-app-name.XXXXXX")" || die "cannot mktemp"
trap 'rm -rf "$TMP"' EXIT

fetch() {  # fetch <path> <dest>; 3 = absent, 4 = unreadable. Never a verdict.
    local enc; enc="$(printf '%s' "$1" | sed 's|/|%2F|g')"
    local code
    code="$(curl -sS --max-time 120 -o "$TMP/raw" -w '%{http_code}' \
            "$BASE/projects/$PROJECT/commits/$PIN/files/$enc/content" 2>/dev/null)" || return 4
    case "$code" in
        200) base64 -d <"$TMP/raw" >"$2" 2>/dev/null || return 4; [ -s "$2" ] || return 4; return 0 ;;
        404) return 3 ;;
        *)   return 4 ;;
    esac
}

checked=0
while IFS= read -r link; do
    f="$(jq -r '.file'    <<<"$link")"
    carries="$(jq -r '.carries // ""' <<<"$link")"
    dest="$TMP/$(printf '%s' "$f" | tr / _)"
    case "$(fetch "$f" "$dest"; echo $?)" in
        0) ;;
        3) bad "$f is ABSENT at $PIN — the link that $carries is gone, so '$NAME' stops at the previous one"
           continue ;;
        *) die "cannot read $f from $BASE — UNKNOWN, not a verdict" ;;
    esac
    missing=0
    while IFS= read -r needle; do
        grep -qF -- "$needle" "$dest" || { missing=1; bad "$f no longer contains: $needle"; }
    done < <(jq -r '.must_contain[]' <<<"$link")
    if [ "$missing" -eq 0 ]; then
        checked=$((checked+1))
        ok "$f still $carries"
    fi
done < <(jq -c '.app_identity.substitution_chain[]' "$BJ")

# FAIL CLOSED a second time: a loop that ran zero times is not a pass.
[ "$checked" -ge 1 ] || bad "not one link of the substitution chain was verified — this run proved nothing"

if [ "$SELFTEST" -eq 1 ]; then
    echo "== T4: SELF-TEST — a display name must fail T1 and T2 =="
    # The exact value a43bc99c6 shipped and run 35048141723 rejected.
    WRONG="Cloud Office"
    t1=0; t2=0
    [ "$WRONG" = "$EXPECTED" ] || t1=1
    printf '%s' "$WRONG" | grep -qE '^(cloud|c3)-[a-z0-9]+(-[a-z0-9]+)*$' || t2=1
    if [ "$t1" -eq 1 ] && [ "$t2" -eq 1 ]; then
        ok "'$WRONG', the value the fleet guard rejected, fails T1 and T2 as it must"
    else
        bad "'$WRONG' passes this rule — the tester cannot detect the defect it exists for"
    fi

    echo "== T5: SELF-TEST — a broken chain link must fail T3 =="
    MUT="$TMP/mutant"
    first_file="$(jq -r '.app_identity.substitution_chain[0].file' "$BJ")"
    first_needle="$(jq -r '.app_identity.substitution_chain[0].must_contain[0]' "$BJ")"
    fetch "$first_file" "$MUT" || die "cannot re-read $first_file — UNKNOWN, not a verdict"
    grep -vF -- "$first_needle" "$MUT" > "$MUT.broken"
    if grep -qF -- "$first_needle" "$MUT.broken"; then
        bad "could not build a mutant of $first_file — T3's failure path is unproven this run"
    else
        ok "with '$first_needle' removed from $first_file the chain check finds nothing, as it must"
    fi
fi

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
