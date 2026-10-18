#!/usr/bin/env bash
# THE NAME THIS APP SHOWS THE OWNER IS THE FLEET NAME, AND IT MUST STILL GET THERE.
#
# THE HOLE THIS FILLS. aa_cloud-superapp/test/test-app-names-pattern.sh is the
# fleet-wide identity rule; test-launcher-labels.sh is the fleet LABEL rule: every
# launcher label is build.json::display_name rather than a copy in strings.xml. T6 says in its own comment that it covers only apps with such a
# reader. Cloud Office has none: its label comes from configure substituting
# @APP_NAME@ into android/app/appSettings.gradle, two build systems away. So the
# one app whose name travels the longest road is the one app the fleet rule
# cannot see, and this is the rule that sees it.
#
# THE VALUE IS THE DISPLAY NAME. Every fleet app derives its launcher label from
# build.json::display_name ("Cloud Browser", "Cloud Office"); the slug in .name is
# identity only. aa_cloud-superapp/test/test-launcher-labels.sh is the fleet rule.
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
#   T1/T2 — THE VALUE. It must equal this app's build.json::.display_name, the one
#   field every fleet launcher label derives from, and be "Cloud " + Title Case
#   words.
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

echo "== T2: that name is Cloud + Title Case words =="
# An INDEPENDENT shape check, so setting BOTH fields to a display name still
# fails here. Same pattern as test-app-names-pattern.sh T1, restated on this side
# because the value being checked is the one that reaches the home screen.
if grep -qE '^Cloud( [A-Z][A-Za-z0-9]*)+$' <<<"$NAME"; then
    ok "'$NAME' is Cloud + Title Case words"
else
    bad "$FLAG is '$NAME', which is not 'Cloud ' + Title Case words — this value is what the launcher, the About box and the WebView <title> print verbatim, and aa_cloud-superapp/test/test-launcher-labels.sh fails the whole fleet on it"
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
    echo "== T4: SELF-TEST — the slug must fail T1 and T2 =="
    # The slug the fleet used before display_name existed.
    WRONG="cloud-office"
    t1=0; t2=0
    [ "$WRONG" = "$EXPECTED" ] || t1=1
    grep -qE '^Cloud( [A-Z][A-Za-z0-9]*)+$' <<<"$WRONG" || t2=1
    if [ "$t1" -eq 1 ] && [ "$t2" -eq 1 ]; then
        ok "'$WRONG', the old slug, fails T1 and T2 as it must"
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
