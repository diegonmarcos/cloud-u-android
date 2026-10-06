#!/usr/bin/env bash
# Tester (#573, re-anchored by #868): every tab strip's top/bottom spacing is ONE declaration.
#
# Diego: the top-nav bar's tabs must have the same top/bottom spacing alignment
# as every page, fixed at the one shared dimen/inset declaration, not per page.
# Since #868 every strip in the fleet is libs:bottomnav's PageTabs (the pixel port of the
# AppTabsStyle this app used to carry), and PageTabsTest (JVM) measures the margins it ends up
# with. This pins the declaration itself:
#   T1  libs:bottomnav's res/values/dimens.xml declares page_tabs_top_inset and
#       page_tabs_bottom_inset exactly once, no values-* qualifier overrides either, and this app
#       declares no tab_strip_* token of its own
#   T2  PageTabs.kt reads BOTH dimens, pads the strip with top base + live inset and the bottom
#       gap, takes the live status/cutout inset only for a strip under the top chrome, and
#       READS the inset (never consumes it)
#   T3  every strip site in this app is a PageTabsView: no TabLayout, no AppTabsStyle, no tab_strip_
#       dimen or insets listener of its own, and there are at least four sites (a section's, the
#       drawer's, a sheet's, a container sheet's)
#   T4  mutation: dropping the bottom gap from PageTabs, or putting a TabLayout back into
#       SectionTabsFragment, turns the checks RED
set -uo pipefail
APP="${SA_APP:-$(cd "$(dirname "$0")/.." && pwd)}"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

LIB="$APP/../ab_cloud-libs-shared/libs/bottomnav/src/main"
RES="$APP/app/src/main/res"
SRC="$APP/app/src/main/java"
PT="$LIB/kotlin/com/diegonmarcos/superapp/bottomnav/PageTabs.kt"
STF="$SRC/com/diegonmarcos/superapp/launcher/SectionTabsFragment.kt"
for f in "$LIB/res/values/dimens.xml" "$PT" "$STF"; do
    [ -f "$f" ] || { echo "FAIL: $f missing — this tester is unrun, not passing"; exit 1; }
done
codeof() { awk '{ l=$0; sub(/^[[:space:]]+/,"",l); if (l ~ /^\/\// || l ~ /^\*/ || l ~ /^\/\*/) next; print }' "$@"; }

echo "== T1: one declaration =="
for d in page_tabs_top_inset page_tabs_bottom_inset; do
    n=$(grep -c "<dimen name=\"$d\"" "$LIB/res/values/dimens.xml")
    [ "$n" = 1 ] && ok "T1: $d declared once in libs:bottomnav values/dimens.xml" || bad "T1: $d declared $n times in libs:bottomnav values/dimens.xml"
    others=$(grep -l "<dimen name=\"$d\"" "$LIB"/res/values-*/dimens.xml 2>/dev/null | wc -l)
    [ "$others" = 0 ] && ok "T1: $d is not overridden by a qualifier" || bad "T1: $d is redeclared in a values-* qualifier"
done
if grep -rq 'tab_strip_' "$RES" "$SRC"; then bad "T1: this app still declares or reads a tab_strip_* token"; else ok "T1: no tab_strip_* token left in this app"; fi

# ── the checks as functions, so T4 can run them on mutated copies ──
t2() {   # $1 = PageTabs.kt
    local c; c=$(codeof "$1")
    grep -q 'R.dimen.page_tabs_top_inset' <<<"$c" || return 1
    grep -q 'R.dimen.page_tabs_bottom_inset' <<<"$c" || return 1
    grep -q 'top = with(density) { (topBase + liveTop).toDp() }' <<<"$c" || return 1
    grep -q 'bottom = with(density) { bottomGap.toDp() }' <<<"$c" || return 1
    grep -q 'WindowInsets.statusBars.union(WindowInsets.displayCutout)' <<<"$c" || return 1
    grep -q 'if (underTopChrome) live.getTop(density) else 0' <<<"$c" || return 1
    grep -q 'underTopChrome: Boolean' <<<"$c" || return 1
    return 0
}
t3() {   # $1 = source root; every strip site is a PageTabsView and owns no geometry of its own
    local root="$1" sites f n=0
    sites=$(grep -rl 'PageTabsView(' "$root" --include=*.kt)
    for f in $sites; do
        grep -qE 'TabLayout|AppTabsStyle|tab_strip_|setOnApplyWindowInsetsListener' <<<"$(codeof "$f")" && return 1
    done
    n=$(( $(grep -rl 'PageTabsView(' "$root" --include=*.kt | wc -l) + $(grep -rl '<com.diegonmarcos.superapp.bottomnav.PageTabsView' "$RES/layout" 2>/dev/null | wc -l) ))
    [ "$n" -ge 4 ]
}

echo "== T2: PageTabs applies both, plus the live inset, without consuming it =="
t2 "$PT" && ok "T2: reads both dimens, pads top (base + live inset) and bottom, live inset only under the top chrome" || bad "T2: PageTabs does not own the whole geometry"

echo "== T3: every strip site is a PageTabsView =="
SITES=$( (grep -rl 'PageTabsView(' "$SRC" --include=*.kt; grep -rl '<com.diegonmarcos.superapp.bottomnav.PageTabsView' "$RES/layout") | sed "s#$APP/##")
echo "$SITES" | sed 's/^/     site: /'
t3 "$SRC" && ok "T3: $(echo "$SITES" | wc -l) strip sites, none with its own dimen, listener or TabLayout" || bad "T3: a strip site is not a PageTabsView, or declares its own spacing"
grep -q 'underTopChrome = false' "$SRC/com/diegonmarcos/superapp/launcher/AppDrawerSheetFragment.kt" \
    && ok "T3: the sheet's strip opts out of the top-chrome inset, not of the geometry" || bad "T3: the drawer sheet strip is not declared as not-under-top-chrome"
[ ! -e "$SRC/com/diegonmarcos/superapp/launcher/AppTabsStyle.kt" ] && ok "T3: AppTabsStyle.kt is gone (the lib's PageTabs replaced it)" || bad "T3: AppTabsStyle.kt is back"

echo "== T4: mutation =="
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
grep -v 'bottom = with(density) { bottomGap.toDp() }' "$PT" > "$TMP/no-bottom.kt"
t2 "$TMP/no-bottom.kt" && bad "T4: T2 passed without the bottom gap" || ok "T4: no bottom gap → RED"
grep -v 'WindowInsets.statusBars.union(WindowInsets.displayCutout)' "$PT" > "$TMP/no-live.kt"
t2 "$TMP/no-live.kt" && bad "T4: T2 passed without the live inset" || ok "T4: no live status/cutout inset → RED"
mkdir -p "$TMP/src"; for f in $(grep -rl 'PageTabsView(' "$SRC" --include=*.kt); do cp "$f" "$TMP/src/$(basename "$f")"; done
printf '%s\n' 'private fun oldStrip(t: TabLayout) { ViewCompat.setOnApplyWindowInsetsListener(t) { _, i -> i } }' >> "$TMP/src/SectionTabsFragment.kt"
grep -q 'setOnApplyWindowInsetsListener' "$TMP/src/SectionTabsFragment.kt" || bad "T4: the mutation did not land in the scratch copy"
t3 "$TMP/src" && bad "T4: T3 passed a section strip with its own TabLayout/listener back" || ok "T4: a per-strip TabLayout/listener back → RED"
[ "$(( $(grep -rl 'PageTabsView(' "$TMP/src" --include=*.kt | wc -l) + $(grep -rl '<com.diegonmarcos.superapp.bottomnav.PageTabsView' "$RES/layout" | wc -l) ))" -ge 4 ] && ok "T4: the scratch tree still has every strip site" || bad "T4: the scratch tree lost a strip site — the mutation proved nothing"

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" = 0 ]
