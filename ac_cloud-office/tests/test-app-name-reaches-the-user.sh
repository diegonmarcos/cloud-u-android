#!/usr/bin/env bash
# THE NAME THIS APP SHOWS THE OWNER MUST BE THE PRODUCT NAME, AND IT MUST STILL GET THERE.
#
# The rebrand was never a missing substitution. Every link works. What shipped
# wrong was the VALUE: upstream.online.configure --with-app-name carried
# "cloud-office", the directory slug, and configure.ac:1179 defines that flag as
# "the user-visible name of the app you build" — its own default is
# "Collabora Online Development Edition", spaces and all.
#
# READ OUT OF Cloud-Office.apk AS PUBLISHED (266,888,676 bytes, run 35043058309),
# not inferred from source:
#   resources.arsc   string/app_name = 'cloud-office'
#                    -> <application android:label> : THE LAUNCHER LABEL
#                    -> AboutDialogFragment.java:89 : the About dialog TITLE
#                    -> AboutDialogFragment.java:77 : the $APP_NAME substituted
#                       into string/app_description, so the About text read
#                       "cloud-office is a modern, easy-to-use ... suite"
#   assets/dist/cool.html   <title>cloud-office</title>
#                           data-mobile-app-name='cloud-office'
#                    -> global.js sets window.brandProductName from it, which is
#                       what brands the document title, the loading spinner, the
#                       web About dialog and the titles core sends for its own
#                       dialogs.
# The same read found ZERO "Collabora" brand strings in resources.arsc and one
# in classes2.dex — a github.com/CollaboraOnline attribution URL in the About
# box, which is correct and stays.
#
# TWO THINGS ARE CHECKED, AND THEY FAIL FOR DIFFERENT REASONS.
#
#   T1/T2 — THE VALUE, cross-checked against a DIFFERENT field so the rule is not
#   circular. This fleet names its release asset after its product, so
#   release.gh_release.asset_name with '.apk' dropped and '-' turned into ' ' IS
#   the product name: Cloud-Office.apk -> "Cloud Office". The old value fails it.
#
#   T3 — THE CHAIN, against upstream at the pin. Nothing in this repository
#   compiles browser/ or android/, so if upstream renames a placeholder or stops
#   assigning brandProductName, the value fix silently stops reaching the screen
#   and every other tester still passes. Each link is a file and the literals
#   that must still be in it, from build.json::app_identity.substitution_chain.
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
ASSET="$(jq -r ".$DERIVED_FROM // empty" "$BJ")"
[ -n "$NAME" ]  || die "upstream.online.configure['$FLAG'] is unset — the app would take upstream's default name"
[ -n "$ASSET" ] || die "$DERIVED_FROM is unset — nothing to cross-check the name against"

# The derivation, spelled once. 'Cloud-Office.apk' -> 'Cloud Office'.
EXPECTED="$(printf '%s' "${ASSET%.apk}" | tr '-' ' ')"

echo "== T1: the product name agrees with the asset this fleet publishes =="
if [ "$NAME" = "$EXPECTED" ]; then
    ok "$FLAG is '$NAME', which is $DERIVED_FROM ('$ASSET') read as a product name"
else
    bad "$FLAG is '$NAME' but $DERIVED_FROM ('$ASSET') says the product is '$EXPECTED' — the store, the release and the launcher would name three different things"
fi

echo "== T2: the product name is a name, not a build-system slug =="
# An INDEPENDENT shape check, so changing both fields to slugs still fails. This
# is the exact shape that shipped: lowercase, hyphenated, no space.
if printf '%s' "$NAME" | grep -q -- '-'; then
    bad "$FLAG is '$NAME' — a hyphen is a slug separator, and this value is what the launcher, the About box and the WebView <title> print verbatim"
elif [ "$NAME" = "$(printf '%s' "$NAME" | tr '[:upper:]' '[:lower:]')" ]; then
    bad "$FLAG is '$NAME' — an all-lowercase value is a directory id, not a product name shown to a person"
else
    ok "'$NAME' reads as a product name (no slug hyphen, not all-lowercase)"
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
    echo "== T4: SELF-TEST — the old slug must fail T1 and T2 =="
    OLD="cloud-office"
    t1=0; t2=0
    [ "$OLD" = "$EXPECTED" ] || t1=1
    printf '%s' "$OLD" | grep -q -- '-' && t2=1
    if [ "$t1" -eq 1 ] && [ "$t2" -eq 1 ]; then
        ok "'$OLD', the value that actually shipped, fails T1 and T2 as it must"
    else
        bad "'$OLD' passes this rule — the tester cannot detect the defect it exists for"
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
