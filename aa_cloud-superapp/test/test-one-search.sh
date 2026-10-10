#!/usr/bin/env bash
# Tester: the SuperApp has ONE search — one engine, one panel, three places.
#
# WHY THIS EXISTS. Cloud > Apps once grew a second search (AppsSearch, its own
# ranking, its own result grid) next to the one the Home swipe sheet already had.
# The owner asked for it to be undone: every place the SuperApp searches runs the
# same engine (libs:search SearchEngine) through the same panel
# (search/SearchPanel.kt) with the same slim bar (libs:ui-kit KitSearchBar).
# A compiler is happy with two engines, so this holds the shape:
#
#   T1  the second engine is gone and nothing names it;
#   T2  the three places mount the one panel: Cloud > Apps and the Home swipe
#       sheet inline (InlineSearch), the Home star full screen (SearchSheetFragment);
#   T3  libs:search is the engine only — no View, Fragment or Compose in it;
#   T4  the browser scopes are declared, and the SuperApp's lookup client and
#       Cloud Browser's lookup provider name the same authority, paths, params
#       and columns (they are two files in two apps, so nothing else checks it);
#   T5  the results layer is the opaque theme surface everywhere it is drawn.
#
# Static tester: no device, no build.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
ROOT="$APP/.."
SRC="$APP/app/src/main/java/com/diegonmarcos/superapp"
LIB="$ROOT/ab_cloud-libs-shared/libs/search/src/main/java/com/diegonmarcos/superapp/search"
BROWSER="$ROOT/ac_cloud-browser/app/src/main/java/com/diegonmarcos/cloudbrowser/provider/BrowserLookup.kt"
CLIENT="$SRC/search/BrowserLookupClient.kt"
PANEL="$SRC/search/SearchPanel.kt"
HOST="$SRC/search/SearchHost.kt"
SHEET="$SRC/search/SearchSheetFragment.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

echo "== T1: one engine =="
[ ! -e "$SRC/launcher/AppsSearch.kt" ] && ok "launcher/AppsSearch.kt is gone" || bad "launcher/AppsSearch.kt still exists"
[ ! -e "$ROOT/ab_cloud-libs-shared/libs/search/src/main/java/com/diegonmarcos/superapp/search/SearchSheet.kt" ] \
  && ok "the old View-built SearchSheet left libs:search" || bad "libs:search still carries the View SearchSheet"
hits=$(rg -l --glob '*.kt' '\bAppsSearch\b' "$APP/app/src/main" 2>/dev/null)
[ -z "$hits" ] && ok "no Kotlin source names AppsSearch" || bad "AppsSearch is still named in: $hits"
grep -q 'SearchEngine.sections(' "$PANEL" && ok "the panel's sections come from libs:search's SearchEngine" \
  || bad "SearchPanel does not draw SearchEngine.sections"

echo "== T2: three places, one panel =="
grep -q 'InlineSearch(this, SearchEntry.CLOUD_APPS_PAGE' "$SRC/launcher/GroupedTilesFragment.kt" \
  && ok "Cloud > Apps mounts the shared panel inline" || bad "Cloud > Apps does not mount InlineSearch"
grep -q 'InlineSearch(this, SearchEntry.HOME_SHEET' "$SRC/launcher/AppDrawerSheetFragment.kt" \
  && grep -q 'GroupedTilesFragment.mountSearch(inline, host)' "$SRC/launcher/AppDrawerSheetFragment.kt" \
  && ok "the Home swipe sheet mounts the shared panel inline, the same way" || bad "the swipe sheet does not mount InlineSearch"
grep -q 'SearchSheetFragment.newInstance()' "$SRC/ShellActivity.kt" \
  && ok "the Home star (action:open_search) opens the shared panel full screen" || bad "openSearchSheet does not open SearchSheetFragment"
grep -q 'SearchEntry.HOME_STAR' "$SHEET" && ok "the full-screen sheet is the Home star's entry" || bad "SearchSheetFragment is not SearchEntry.HOME_STAR"
grep -q 'KitSearchBar(' "$PANEL" && ok "the bar is libs:ui-kit's KitSearchBar" || bad "SearchBox is not KitSearchBar"
# The bar sits at the TOP of Cloud > Apps now: it is the first child of the inline root.
python3 - "$SRC/launcher/GroupedTilesFragment.kt" <<'PY' && ok "the inline bar is added above the page, not under it" || bad "the inline bar is not the first child"
import re, sys
s = open(sys.argv[1]).read()
m = re.search(r'fun mountSearch\(.*?\n        \}', s, re.S)
body = m.group(0) if m else ''
bar, frame = body.find('addView(search.bar'), body.find('addView(frame')
sys.exit(0 if 0 <= bar < frame else 1)
PY

echo "== T3: libs:search is the engine, not a UI =="
ui=$(rg -l '^import (android\.view\.|android\.widget\.|androidx\.fragment\.|androidx\.compose\.)' "$LIB" 2>/dev/null)
[ -z "$ui" ] && ok "no UI import under libs:search" || bad "libs:search renders again: $ui"

echo "== T4: the browser scopes and their contract =="
for k in browser_fav browser_history browser_web; do
  jq -e --arg k "$k" '[.ui.search_scopes[] | select(.kind == $k)] | length == 1' "$APP/build.json" >/dev/null \
    && ok "build.json declares one $k scope" || bad "build.json does not declare exactly one $k scope"
done
order=$(jq -r '[.ui.search_scopes[].section] | join("|")' "$APP/build.json")
[ "$order" = "Cloud apps|Phone apps|Cloud configs|Phone configs|Browser favourites|Browser history|Web" ] \
  && ok "the result sections read in the owner's order" || bad "section order is '$order'"
for name in AUTHORITY_SUFFIX PATH_FAVOURITES PATH_HISTORY PATH_WEB PARAM_QUERY PARAM_LIMIT COL_KIND COL_TITLE COL_URL COL_TIME KIND_URL; do
  a=$(grep -oE "const val $name = \"[^\"]*\"" "$BROWSER" | head -1)
  b=$(grep -oE "const val $name = \"[^\"]*\"" "$CLIENT" | head -1)
  [ -n "$a" ] && [ "$a" = "$b" ] && ok "$name is the same on both sides (${a#*= })" || bad "$name differs: browser '$a' vs superapp '$b'"
done
grep -q 'android:name=".provider.BrowserLookupProvider"' "$ROOT/ac_cloud-browser/app/src/main/AndroidManifest.xml" \
  && ok "Cloud Browser's manifest declares the lookup provider" || bad "the lookup provider is not in Cloud Browser's manifest"

echo "== T5: the results layer is opaque =="
grep -q 'LauncherPalette.opaqueSurface' "$HOST" && ok "the inline dropdown paints LauncherPalette.opaqueSurface" || bad "InlineSearch does not use opaqueSurface"
grep -q 'LauncherPalette.opaqueSurface' "$SHEET" && ok "the Home star's sheet paints LauncherPalette.opaqueSurface" || bad "SearchSheetFragment does not use opaqueSurface"
grep -q '\.background(surface)' "$PANEL" && ok "the results list is drawn on that surface" || bad "SearchResults does not draw on the surface it is handed"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
