#!/usr/bin/env bash
# Tester: ac_c3-watchtower is the app it says it is, and reads the endpoint it
# says it reads.
#
# OWN SOURCE ONLY. Every path below is under ac_c3-watchtower/. That is the
# #254 fleet rule — nothing outside an app's own source may fail that app's
# release — and it is load-bearing here: the SuperApp side of this feature
# (the C3 tab, the Analytics card, the ui.external_apps entry) is asserted by
# aa_cloud-superapp/test/test-watchtower-tab.sh, which runs in the SuperApp's
# own ship workflow. If those two sets of assertions lived in one file, an edit
# to aa_cloud-superapp/build.json could turn THIS app's release red, which is
# exactly what #242 and #250 cost days to.
#
# The ways a new app reports green while giving him nothing are all known,
# because this repository has shipped every one of them:
#
#   • A package-name typo compiles, ships, installs, and leaves a tab that
#     opens nothing — the SuperApp resolves the tab by
#     getLaunchIntentForPackage on that exact string. T1 never writes the
#     package down: it reads it out of all three of this app's own
#     declarations and asserts they agree. A tester that restated the string
#     would pass on the typo.
#
#   • Asserting a string is in a file proves a string is in a file. T4 greps
#     the Kotlin with its COMMENTS STRIPPED, because the KDoc above the client
#     names the endpoint, BuildConfig and the repo list in prose — a grep over
#     the raw file would match its own explanation and pass on code that was
#     deleted.
#
#   • The obvious wrong endpoint. /pub/analytics/* is the fleet's one
#     unauthenticated analytics path and it is INGEST-ONLY (c3-public-api's
#     routes/analytics.ts pins each mount to a fixed write path: /matomo.php,
#     /api/send, /api/default/default/_json). A future edit that "simplifies"
#     this app onto it would produce a screen of nothing. T5 forbids it by
#     name and says why.
#
# Every check fails closed: an empty jq result, a missing file and an
# unreadable field are all FAIL, never "nothing to compare so pass".
#
# NO `set -o pipefail` here on purpose: under pipefail a `grep -q` that MATCHES
# kills its upstream with SIGPIPE and the pipeline reports 141, so a check that
# is supposed to pass fails and a check that fails on EVERYTHING reads as a
# strict harness. That trap is already written up in this fleet.
set -u

APP="$(cd "$(dirname "$0")/.." && pwd)"          # -> ac_c3-watchtower
BJ="$APP/build.json"
GRADLE="$APP/app/build.gradle"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"
KT="$APP/app/src/main/java/com/diegonmarcos/watchtower/MainActivity.kt"
STRINGS="$APP/app/src/main/res/values/strings.xml"
STRINGS_ES="$APP/app/src/main/res/values-es/strings.xml"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
eq()  { [ "$2" = "$3" ] && ok "$1 ($2)" || bad "$1: expected '$3', got '$2'"; }

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 2; }
for f in "$BJ" "$GRADLE" "$MANIFEST" "$KT" "$STRINGS" "$STRINGS_ES"; do
    [ -f "$f" ] || { echo "ERROR: missing $f" >&2; exit 2; }
done

echo "== T1: one package id, in all three of this app's own declarations =="
# Read, never restate.
PKG_BJ="$(jq -r '.android.application_id // empty' "$BJ")"
case "$PKG_BJ" in
  com.*) ok "build.json::android.application_id = $PKG_BJ" ;;
  *)     bad "could not read android.application_id from build.json (got '$PKG_BJ')" ;;
esac
PKG_GR="$(sed -n "s/^[[:space:]]*namespace[[:space:]]*'\\(.*\\)'[[:space:]]*$/\\1/p" "$GRADLE" | head -1)"
eq "app/build.gradle::namespace agrees" "$PKG_GR" "$PKG_BJ"

# The manifest names the activity by FULLY QUALIFIED class, so its prefix is a
# third copy of the package and can drift from the other two independently.
ACT="$(sed -n 's/.*android:name="\(com\.[A-Za-z0-9_.]*MainActivity\)".*/\1/p' "$MANIFEST" | head -1)"
eq "AndroidManifest activity is <package>.MainActivity" "$ACT" "$PKG_BJ.MainActivity"

# And the source file must actually declare that package, or the class the
# manifest names does not exist and the app dies at launch with
# ClassNotFoundException.
PKG_KT="$(sed -n 's/^package[[:space:]]\{1,\}\([A-Za-z0-9_.]*\).*/\1/p' "$KT" | head -1)"
eq "MainActivity.kt declares that package" "$PKG_KT" "$PKG_BJ"

# The siblings' convention, asserted rather than assumed: the c3- in the
# DIRECTORY name is not in the package (ac_c3-watchdog is com.diegonmarcos
# .watchdog, ac_c3-morpheus is com.diegonmarcos.morpheus). Read off this app's
# own dir so a rename cannot leave the rule behind.
DIRBASE="$(basename "$APP")"                       # ac_c3-watchtower
EXPECT="com.diegonmarcos.${DIRBASE#ac_c3-}"        # com.diegonmarcos.watchtower
eq "package follows the c3 family convention" "$PKG_BJ" "$EXPECT"

echo "== T2: both ABI variants are declared, under DIFFERENT asset names =="
NV="$(jq -r '[.release.variants[]?] | length' "$BJ")"
eq "two release variants" "$NV" "2"
A_ARM="$(jq -r '.release.variants[]? | select(.id=="arm64")  | .gh_asset // empty' "$BJ")"
A_X86="$(jq -r '.release.variants[]? | select(.id=="x86_64") | .gh_asset // empty' "$BJ")"
ASSET="$(jq -r '.release.gh_release.asset_name // empty' "$BJ")"
eq "arm64 publishes the canonical asset name" "$A_ARM" "$ASSET"
case "$A_ARM" in
  C3-WatchTower.apk) ok "the canonical asset is C3-WatchTower.apk" ;;
  *)                 bad "canonical asset is not C3-WatchTower.apk: '$A_ARM'" ;;
esac
[ -n "$A_X86" ] && [ "$A_ARM" != "$A_X86" ] \
  && ok "the two variants are different files ($A_ARM / $A_X86)" \
  || bad "arm64 and x86_64 name the same asset ('$A_ARM') — the second leg's --clobber would delete the first's"
# supported_abis is what regen.sh keys the fleet `assets` map on, and what
# AbiUpdateTag matches Build.SUPPORTED_ABIS against. Empty here and the map is
# empty, and the install path silently falls back to the flat arm64 URL — #43
# wearing a map.
S_ARM="$(jq -r '[.release.variants[]? | select(.id=="arm64")  | .supported_abis[]?] | length' "$BJ")"
S_X86="$(jq -r '[.release.variants[]? | select(.id=="x86_64") | .supported_abis[]?] | length' "$BJ")"
[ "$S_ARM" -gt 0 ] 2>/dev/null && ok "arm64 declares supported_abis ($S_ARM)" \
  || bad "arm64 variant declares no supported_abis — regen.sh would derive an empty per-ABI map"
[ "$S_X86" -gt 0 ] 2>/dev/null && ok "x86_64 declares supported_abis ($S_X86)" \
  || bad "x86_64 variant declares no supported_abis — regen.sh would derive an empty per-ABI map"
# And the image the store pulls must be this app's, not the template's.
eq "ghcr image is this app's" "$(jq -r '.release.ghcr.image // empty' "$BJ")" "c3-watchtower"

echo "== T3: the analytics declaration is usable, not just present =="
NREPOS="$(jq -r '[.analytics.repos[]?] | length' "$BJ")"
[ "$NREPOS" -gt 0 ] 2>/dev/null && ok "analytics.repos is non-empty ($NREPOS)" \
  || bad "analytics.repos is empty — the app would render an empty fleet and call it green"
# Every entry complete. A repo missing `owner` builds the URL
# api.github.com/repos//<repo>/actions/runs, which 404s, which this app reports
# as UNREACHABLE — a config error wearing a network error's clothes.
INCOMPLETE="$(jq -r '[.analytics.repos[]? | select((.owner // "") == "" or (.repo // "") == "" or (.label // "") == "")] | length' "$BJ")"
eq "every repo entry has owner+repo+label" "$INCOMPLETE" "0"
WIN="$(jq -r '.analytics.window_runs // 0' "$BJ")"
[ "$WIN" -gt 0 ] 2>/dev/null && [ "$WIN" -le 100 ] 2>/dev/null \
  && ok "window_runs is a single GitHub page ($WIN)" \
  || bad "window_runs must be 1..100 (one anonymous request); got '$WIN'"
TTL="$(jq -r '.analytics.cache_minutes // 0' "$BJ")"
[ "$TTL" -gt 0 ] 2>/dev/null && ok "cache_minutes is set ($TTL)" \
  || bad "cache_minutes unset — every screen open would spend the 60/hour quota again"

echo "== T4: the app READS that declaration, rather than carrying its own copy =="
# Comments stripped: the KDoc above the client names BuildConfig, the endpoint
# and the repo list in prose, and a grep over the raw file would match its own
# explanation.
CODE="$(mktemp)"; trap 'rm -f "$CODE"' EXIT
sed -e 's://[^"]*$::' "$KT" | grep -vE '^[[:space:]]*(//|/\*|\*)' > "$CODE"
codehas() { grep -qF "$1" "$CODE" && ok "$2" || bad "$2 (not in MainActivity.kt, comments stripped)"; }
codehas 'BuildConfig.ANALYTICS_JSON_B64' "the repo list comes from build.json via BuildConfig"
codehas '/actions/runs?per_page=' "it calls the Actions runs endpoint"
# The denominator. Counting in-progress runs as 'not green' makes the rate sag
# whenever the fleet is mid-build, which is precisely when this screen is read.
codehas 'conclusion.isNotBlank()' "in-progress runs are excluded from the denominator"
# Fail closed on the inverse: a hardcoded repo would make build.json decorative.
if grep -qE 'api\.github\.com/repos/[a-zA-Z]' "$CODE"; then
    bad "a repo owner is hardcoded in the Kotlin — build.json::analytics.repos would be decorative"
else
    ok "no repo owner is hardcoded in the Kotlin"
fi

echo "== T5: it does not read the write-only analytics path =="
# /pub/analytics/* accepts telemetry and answers no questions: every mount in
# c3-public-api's routes/analytics.ts is pinned to one ingest path. An app
# pointed there shows nothing and looks like a network fault.
if grep -qF '/pub/analytics' "$CODE"; then
    bad "MainActivity references /pub/analytics — that path is INGEST-ONLY and would render an empty screen"
else
    ok "MainActivity does not reference the ingest-only /pub/analytics path"
fi

echo "== T6: every string it draws exists, in both declared locales =="
MISSING=0
for name in $(grep -oE 'R\.string\.[A-Za-z0-9_]+' "$CODE" | sed 's/R\.string\.//' | sort -u); do
    grep -qF "name=\"$name\"" "$STRINGS" || { bad "R.string.$name has no entry in values/strings.xml"; MISSING=1; }
done
[ "$MISSING" -eq 0 ] && ok "every R.string the code draws is declared in values/strings.xml"
# Spanish parity, minus app_name: WatchTower is a product name and is
# deliberately not translated, exactly as Watchdog and Morpheus are not.
UNTRANSLATED=""
for name in $(grep -oE 'name="[A-Za-z0-9_]+"' "$STRINGS" | sed 's/name="//;s/"//' | sort -u); do
    [ "$name" = "app_name" ] && continue
    grep -qF "name=\"$name\"" "$STRINGS_ES" || UNTRANSLATED="$UNTRANSLATED $name"
done
[ -z "$UNTRANSLATED" ] && ok "values-es/ translates every string but app_name" \
  || bad "values-es/ is missing:$UNTRANSLATED"
# app_name must NOT be in values-es, or the launcher label changes language.
grep -qF 'name="app_name"' "$STRINGS_ES" \
  && bad "values-es/ overrides app_name — the product name must not be translated" \
  || ok "values-es/ leaves app_name alone"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
