#!/usr/bin/env bash
# Tester: the WatchTower launch tab on C3, and the Analytics card on
# C3 ▸ Observability.
#
# The owner asked for three things in one sentence — "create the WatchTower
# app, that you will link here beside Morpheus. Then inside of Observability
# you will add the card Analytics that will be what WatchTower will do" — and
# the ways each of them reports green while giving him nothing are all known,
# because this repository has shipped every one of them:
#
#   • "build.json contains the string WatchTower" proves a string is in a file.
#     T1 resolves the page the way Sections.parse does — section by `id`, then
#     the NON-HIDDEN page list, by INDEX — because Sections.parse filters
#     hidden pages before the strip ever sees them, so a raw array index is not
#     the index the user taps.
#
#   • "beside Morpheus" is a POSITION, and #103/#106/#107/#120/#251 are all
#     tasks about things landing in the wrong place. T1 asserts index 4, not
#     mere presence, and T2 asserts the `|` divider did not move with it.
#
#   • A package-name typo compiles, ships, installs, and leaves a tab that
#     opens nothing. T4 never writes the package down: it reads it out of
#     ac_c3-watchtower's OWN declarations and asserts every copy here agrees.
#
#   • A card added without an Index tile is #78 regressing on the very page
#     #78 was fixed on. T5 asserts the anchor RESOLVES, not that a tile exists.
#
#   • A card whose `source` has no `when` arm renders the renderFeed fallback
#     string — a visible card that draws an error and still passes any check
#     that only reads build.json. T6 greps the renderer with its COMMENTS
#     STRIPPED, because the KDoc above it names the source, the endpoint and
#     the app in prose, and a grep over the raw file would match its own
#     explanation.
#
# Every check fails closed: an empty jq result, a missing file and an
# unreadable field are all FAIL, never "nothing to compare so pass".
#
# NO `set -o pipefail`: under pipefail a `grep -q` that MATCHES kills its
# upstream with SIGPIPE and the pipeline reports 141, so a check meant to pass
# fails and a check that fails on EVERYTHING reads as a strict harness.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
ROOT="$(cd "$APP/.." && pwd)"                    # → cloud-u-android
BJ="$APP/build.json"
FLEET="$APP/data/constellation-fleet.json"
WT="$ROOT/ac_c3-watchtower"
AGG="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/AggregatorStackFragment.kt"
NAV="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/LauncherNavController.kt"
TABS="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/SectionTabsFragment.kt"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
eq()  { [ "$2" = "$3" ] && ok "$1 ($2)" || bad "$1: expected '$3', got '$2'"; }

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 2; }
for f in "$BJ" "$AGG" "$NAV" "$TABS"; do
    [ -f "$f" ] || { echo "ERROR: missing $f" >&2; exit 2; }
done

# The renderer's own view of this section's pages. Sections.parse keeps
# `pages = pages.filter { !it.hidden }`, and SectionTabsFragment draws exactly
# that list — so THIS is the page model, and a raw .pages[4] would not be.
VIS='[.ui.sections[] | select(.id == "c3") | .pages[] | select((.hidden // false) | not)]'

echo "== T1: the WatchTower tab is at index 4, right after Morpheus =="
N="$(jq -r "[.ui.sections[] | select(.id == \"c3\")] | length" "$BJ")"
eq "exactly one section with id 'c3'" "$N" "1"

eq "visible page 3 is Morpheus"   "$(jq -r "$VIS | .[3].id" "$BJ")" "morpheus"
eq "visible page 4 is watchtower" "$(jq -r "$VIS | .[4].id" "$BJ")" "watchtower"
eq "its label is the owner's word" "$(jq -r "$VIS | .[4].label" "$BJ")" "WatchTower"
eq "it dispatches the external app" "$(jq -r "$VIS | .[4].action" "$BJ")" "extapp:c3-watchtower"
eq "it is an ACTION tab, not a content page" "$(jq -r "$VIS | .[4].is_action" "$BJ")" "true"
# A launch tab that grew a facet would claim a pane and change the strip.
eq "it carries no facet" "$(jq -r "$VIS | .[4].facet // false" "$BJ")" "false"
# Exactly one, anywhere in the section — a second copy in the hidden pages
# would route page:c3/watchtower somewhere else.
eq "exactly one page with id 'watchtower' in the whole section" \
   "$(jq -r '[.ui.sections[] | select(.id == "c3") | .pages[] | select(.id == "watchtower")] | length' "$BJ")" "1"

echo "== T2: the | divider did not move =="
# SectionTabsFragment positions it with pages.indexOfFirst { action.isNotBlank() }
# over the SAME visible list. It must still land on Watchdog.
DIV="$(jq -r "$VIS | map((.action // \"\") != \"\") | index(true)" "$BJ")"
eq "divider index is still 2" "$DIV" "2"
eq "and index 2 is still Watchdog" "$(jq -r "$VIS | .[2].id" "$BJ")" "watchdog"

echo "== T3: a third launch tab did not turn the tablet strip into a rail =="
# LauncherNavController falls to SectionMenuFragment past MAX_PANES. Launch
# tabs claim no pane (SectionTabsFragment: panePages = pages.filter {
# it.action.isBlank() }), so the gate must weigh PANE-CLAIMING pages. Read the
# constant out of the source; a restated 4 would agree with a changed engine.
MAXP="$(sed -n 's/.*const val MAX_PANES = \([0-9]\{1,\}\).*/\1/p' "$TABS" | head -1)"
case "$MAXP" in
  ''|*[!0-9]*) bad "could not read MAX_PANES out of SectionTabsFragment.kt (got '$MAXP')" ;;
  *)           ok "MAX_PANES read from source ($MAXP)" ;;
esac
PANE="$(jq -r "$VIS | map(select((.action // \"\") == \"\")) | length" "$BJ")"
[ -n "$MAXP" ] && [ "$PANE" -le "$MAXP" ] 2>/dev/null \
  && ok "C3 claims $PANE panes, within MAX_PANES ($MAXP)" \
  || bad "C3 claims $PANE panes, past MAX_PANES ($MAXP) — a tablet would get the rail, not the strip"
# And the gate must COUNT pane-claiming pages, not all pages. Comments
# stripped: the paragraph above that line explains the bug in prose and names
# both expressions, so a raw grep would match the explanation of the fix
# instead of the fix.
NAVCODE="$(mktemp)"; trap 'rm -f "$NAVCODE"' EXIT
sed -e 's://[^"]*$::' "$NAV" | grep -vE '^[[:space:]]*(//|/\*|\*)' > "$NAVCODE"
grep -qF 'section.pages.count { it.action.isBlank() } > SectionTabsFragment.MAX_PANES' "$NAVCODE" \
  && ok "the tablet gate counts pane-claiming pages" \
  || bad "the tablet gate does not count pane-claiming pages (not in LauncherNavController.kt, comments stripped)"
# Fail closed: the old predicate must be GONE, or both exist and the wrong one
# could still be the live branch.
grep -qF 'section.pages.size > SectionTabsFragment.MAX_PANES' "$NAVCODE" \
  && bad "the old page-COUNT gate is still present — a launch tab still costs a pane it never uses" \
  || ok "the old page-count gate is gone"

echo "== T4: the package is ac_c3-watchtower's own, everywhere it is copied =="
[ -f "$WT/build.json" ] || bad "ac_c3-watchtower/build.json not found at $WT"
PKG="$(jq -r '.android.application_id // empty' "$WT/build.json")"
case "$PKG" in
  com.*) ok "ac_c3-watchtower/build.json::android.application_id = $PKG" ;;
  *)     bad "could not read android.application_id from ac_c3-watchtower/build.json (got '$PKG')" ;;
esac
PKG_GR="$(sed -n "s/^[[:space:]]*namespace[[:space:]]*'\\(.*\\)'[[:space:]]*$/\\1/p" \
          "$WT/app/build.gradle" 2>/dev/null | head -1)"
eq "its app/build.gradle::namespace agrees" "$PKG_GR" "$PKG"

EA='.ui.external_apps[] | select(.id == "c3-watchtower")'
eq "exactly one ui.external_apps entry" "$(jq -r "[$EA] | length" "$BJ")" "1"
eq "ui.external_apps.hub_package agrees"     "$(jq -r "$EA | .hub_package"     "$BJ")" "$PKG"
eq "ui.external_apps.install_package agrees" "$(jq -r "$EA | .install_package" "$BJ")" "$PKG"
# The generated fleet. Present here because regen.sh was RUN, not typed.
if [ -f "$FLEET" ]; then
    eq "constellation-fleet.json agrees" \
       "$(jq -r '.apps[] | select(.id == "watchtower") | .package' "$FLEET")" "$PKG"
else
    bad "constellation-fleet.json not found — data/regen.sh was not run"
fi
# #170: identity lives in TWO hand-maintained places. Not three.
DUP="$(jq -r --arg p "$PKG" '[.ui.phone_folders[].match_keywords // [] | .[]
       | select(ascii_downcase == ("pkg:" + ($p | ascii_downcase)))] | length' "$BJ")"
eq "no ui.phone_folders keyword restates the package" "$DUP" "0"
EA_FOLDER="$(jq -r "$EA | .folder" "$BJ")"
eq "its folder is a declared phone_folder ($EA_FOLDER)" \
   "$(jq -r --arg f "$EA_FOLDER" '[.ui.phone_folders[] | select(.id == $f)] | length' "$BJ")" "1"

echo "== T5: the not-installed path has something to install, for BOTH ABIs =="
EA_URL="$(jq -r "$EA | .install_apk_url" "$BJ")"
case "$EA_URL" in
  https://*/C3-WatchTower.apk) ok "install_apk_url offers a real APK ($EA_URL)" ;;
  *)                           bad "install_apk_url is not a C3-WatchTower.apk URL: '$EA_URL'" ;;
esac
# The magic recency route flaps with whatever shipped last — regen.sh's own
# _doc measured it 404ing. The tag must be NAMED.
case "$EA_URL" in
  */releases/latest/download/*) bad "install_apk_url uses the magic /releases/latest/download/ route, which resolves by recency and flaps" ;;
  */releases/download/*)        ok "install_apk_url names its tag explicitly" ;;
  *)                            bad "install_apk_url is not a releases download URL: '$EA_URL'" ;;
esac
if [ -f "$FLEET" ]; then
    A_ARM="$(jq -r '.apps[] | select(.id == "watchtower") | .assets["arm64-v8a"] // ""' "$FLEET")"
    A_X86="$(jq -r '.apps[] | select(.id == "watchtower") | .assets["x86_64"]    // ""' "$FLEET")"
    eq "fleet declares the arm64 asset"  "$A_ARM" "C3-WatchTower.apk"
    eq "fleet declares the x86_64 asset" "$A_X86" "C3-WatchTower-x86_64.apk"
    [ -n "$A_ARM" ] && [ "$A_ARM" != "$A_X86" ] \
      && ok "the two variants are different files (a map that maps everything to one name is not an ABI map)" \
      || bad "arm64 and x86_64 name the same asset — #43's hardcoded-arm64 shape, wearing a map"
fi

echo "== T6: the Analytics card, and the Index row that jumps to it =="
OBS='.ui.sections[] | select(.id == "c3") | .stack_observability[]'
CARD="$OBS | select(.anchor == \"analytics\")"
eq "exactly one card claims the 'analytics' anchor" "$(jq -r "[$CARD] | length" "$BJ")" "1"
eq "its title is the owner's word" "$(jq -r "$CARD | .title" "$BJ")" "Analytics"
eq "it is a feed card"             "$(jq -r "$CARD | .kind"  "$BJ")" "feed"
SRC="$(jq -r "$CARD | .source" "$BJ")"
eq "its source is the run-stats fetcher" "$SRC" "github_run_stats"
NR="$(jq -r "[$CARD | .repos[]?] | length" "$BJ")"
[ "$NR" -gt 0 ] 2>/dev/null && ok "the card declares its repo set ($NR)" \
  || bad "the Analytics card declares no repos — renderRunStatsFeed would draw 'No repos declared'"

# #78: the Index row is the first card and must jump to EVERY card below it.
IDXROW="$OBS | select(.kind == \"tile_row\" and .title == \"Index\")"
eq "exactly one Index tile_row" "$(jq -r "[$IDXROW] | length" "$BJ")" "1"
eq "the Index row has a tile targeting anchor:analytics" \
   "$(jq -r "[$IDXROW | .tiles[] | select(.target == \"anchor:analytics\")] | length" "$BJ")" "1"
eq "that tile is labelled Analytics" \
   "$(jq -r "$IDXROW | .tiles[] | select(.target == \"anchor:analytics\") | .label" "$BJ")" "Analytics"
# The anchor must RESOLVE — an Index tile pointing at an anchor no card claims
# scrolls nowhere and reports nothing. Checked both ways round, for every tile,
# so this also catches a card whose anchor was renamed out from under the row.
ORPHAN="$(jq -r "[ ($IDXROW | .tiles[] | .target | sub(\"^anchor:\";\"\")) ] -
                 [ ($OBS | select(has(\"anchor\")) | .anchor) ] | length" "$BJ")"
eq "every Index tile resolves to a card anchor" "$ORPHAN" "0"

# The More row opens FULL PAGES. Analytics has none, so it must not be there —
# a tile pointing at page:c3/analytics would route to an undeclared page.
MOREROW="$OBS | select(.kind == \"tile_row\" and .title == \"More\")"
eq "the More row was not given an analytics tile" \
   "$(jq -r "[$MOREROW | .tiles[] | select(.id == \"analytics\" or (.target | test(\"analytics\")))] | length" "$BJ")" "0"
# ...and it is otherwise untouched: its seven tiles are the ONLY referrer five
# hidden pages have (test-stack-anchors-declared.sh T8).
eq "the More row still carries its seven tiles" "$(jq -r "[$MOREROW | .tiles[]] | length" "$BJ")" "7"

echo "== T7: the renderer HANDLES that source, rather than drawing its fallback =="
CODE="$(mktemp)"; trap 'rm -f "$NAVCODE" "$CODE"' EXIT
sed -e 's://[^"]*$::' "$AGG" | grep -vE '^[[:space:]]*(//|/\*|\*)' > "$CODE"
codehas() { grep -qF "$1" "$CODE" && ok "$2" || bad "$2 (not in AggregatorStackFragment.kt, comments stripped)"; }
codehas "\"$SRC\" -> renderRunStatsFeed" "renderFeed dispatches $SRC to a real arm"
codehas 'private fun renderRunStatsFeed' "that arm exists"
# The denominator, again — asserted on the card as well as in the app, because
# these are two independent implementations of the same number.
codehas 'val finished = runs.filter { it.conclusion.isNotBlank() }' \
        "in-progress runs are excluded from the card's denominator"
# The handoff must go through the ONE dispatcher (#156: no seventh ad-hoc
# ACTION_VIEW site; #195: onTileClicked fires once). githubRow already routes
# its url through onTileClicked, so the card must reuse githubRow and must NOT
# start an activity itself.
codehas 'return githubRow(' "the summary row reuses githubRow, so the tap goes through onTileClicked"
STATS_FN="$(sed -n '/private fun renderRunStatsFeed/,/^    private fun renderDaguRunsFeed/p' "$CODE")"
case "$STATS_FN" in
  *startActivity*) bad "renderRunStatsFeed/runStatsRow calls startActivity directly — a seventh ad-hoc launch site (#156)" ;;
  *)               ok "no startActivity in the new render path" ;;
esac
# And the card's declared handoff target must be the app, resolved through the
# same ui.external_apps entry T4 pinned.
eq "the card hands off to the WatchTower app" "$(jq -r "$CARD | .url" "$BJ")" "extapp:c3-watchtower"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
