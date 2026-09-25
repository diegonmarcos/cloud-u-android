#!/usr/bin/env bash
# Tester (#573): every tab strip's top/bottom spacing is ONE declaration.
#
# Diego: the top-nav bar's tabs must have the same top/bottom spacing alignment
# as every page, fixed at the one shared dimen/inset declaration, not per page.
# AppTabsStyleTest (JVM) measures the margins a styled strip ends up with. This
# pins the declaration itself:
#   T1  res/values/dimens.xml declares tab_strip_top_inset and tab_strip_bottom_inset
#       exactly once, and no other values-* qualifier overrides either
#   T2  AppTabsStyle.kt reads BOTH dimens, sets topMargin AND bottomMargin, adds the
#       live status/cutout inset through an insets listener that returns the insets
#       unchanged, and offers the underTopChrome switch
#   T3  every strip site (each file calling AppTabsStyle.apply) takes the geometry
#       from there: none reads a tab_strip_ dimen or installs its own insets listener,
#       and there are at least three sites (a section's, a page's, a sheet's)
#   T4  mutation: dropping the bottom margin from AppTabsStyle, or putting the old
#       per-strip listener back into SectionTabsFragment, turns the checks RED
set -uo pipefail
APP="${SA_APP:-$(cd "$(dirname "$0")/.." && pwd)}"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

RES="$APP/app/src/main/res"
SRC="$APP/app/src/main/java"
ATS="$SRC/com/diegonmarcos/superapp/launcher/AppTabsStyle.kt"
STF="$SRC/com/diegonmarcos/superapp/launcher/SectionTabsFragment.kt"
for f in "$RES/values/dimens.xml" "$ATS" "$STF"; do
    [ -f "$f" ] || { echo "FAIL: $f missing — this tester is unrun, not passing"; exit 1; }
done
codeof() { awk '{ l=$0; sub(/^[[:space:]]+/,"",l); if (l ~ /^\/\// || l ~ /^\*/ || l ~ /^\/\*/) next; print }' "$@"; }

echo "== T1: one declaration =="
for d in tab_strip_top_inset tab_strip_bottom_inset; do
    n=$(grep -c "<dimen name=\"$d\"" "$RES/values/dimens.xml")
    [ "$n" = 1 ] && ok "T1: $d declared once in values/dimens.xml" || bad "T1: $d declared $n times in values/dimens.xml"
    others=$(grep -l "<dimen name=\"$d\"" "$RES"/values-*/dimens.xml 2>/dev/null | wc -l)
    [ "$others" = 0 ] && ok "T1: $d is not overridden by a qualifier" || bad "T1: $d is redeclared in a values-* qualifier"
done

# ── the checks as functions, so T4 can run them on mutated copies ──
t2() {   # $1 = AppTabsStyle.kt
    local c; c=$(codeof "$1")
    echo "$c" | grep -q 'R.dimen.tab_strip_top_inset' || return 1
    echo "$c" | grep -q 'R.dimen.tab_strip_bottom_inset' || return 1
    echo "$c" | grep -q 'topMargin = top + liveInset' || return 1
    echo "$c" | grep -q 'bottomMargin = bottom' || return 1
    echo "$c" | grep -q 'setOnApplyWindowInsetsListener(tabLayout)' || return 1
    echo "$c" | grep -qE '^\s*insets$' || return 1
    echo "$c" | grep -q 'underTopChrome: Boolean' || return 1
    return 0
}
t3() {   # $1 = source root; every strip site takes the geometry from AppTabsStyle
    local root="$1" sites f n=0
    sites=$(grep -rl 'AppTabsStyle.apply(' "$root" --include=*.kt | grep -v '/AppTabsStyle.kt$')
    for f in $sites; do
        n=$((n+1))
        codeof "$f" | grep -q 'tab_strip_' && return 1
        codeof "$f" | grep -q 'setOnApplyWindowInsetsListener' && return 1
    done
    [ "$n" -ge 3 ]
}

echo "== T2: AppTabsStyle applies both, plus the live inset, without consuming it =="
t2 "$ATS" && ok "T2: reads both dimens, sets both margins, listens and returns the insets unchanged" || bad "T2: AppTabsStyle does not own the whole geometry"

echo "== T3: every strip site takes the geometry from AppTabsStyle =="
SITES=$(grep -rl 'AppTabsStyle.apply(' "$SRC" --include=*.kt | grep -v '/AppTabsStyle.kt$' | sed "s#$APP/##")
echo "$SITES" | sed 's/^/     site: /'
t3 "$SRC" && ok "T3: $(echo "$SITES" | wc -l) strip sites, none with its own dimen or listener" || bad "T3: a strip site declares its own spacing or listener"
grep -q 'AppTabsStyle.apply(this, underTopChrome = false)' "$SRC/com/diegonmarcos/superapp/launcher/AppDrawerSheetFragment.kt" \
    && ok "T3: the sheet's strip opts out of the top-chrome inset, not of the geometry" || bad "T3: the drawer strip is not declared as not-under-top-chrome"

echo "== T4: mutation =="
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
grep -v 'bottomMargin = bottom' "$ATS" > "$TMP/no-bottom.kt"
t2 "$TMP/no-bottom.kt" && bad "T4: T2 passed without the bottom margin" || ok "T4: no bottom margin → RED"
grep -v '^\s*insets$' "$ATS" > "$TMP/consumed.kt"
t2 "$TMP/consumed.kt" && bad "T4: T2 passed a listener that does not hand the insets on" || ok "T4: insets consumed → RED"
mkdir -p "$TMP/src/launcher"; cp "$STF" "$TMP/src/launcher/SectionTabsFragment.kt"; cp "$ATS" "$TMP/src/launcher/AppTabsStyle.kt"
for f in $(grep -rl 'AppTabsStyle.apply(' "$SRC" --include=*.kt | grep -v -e '/AppTabsStyle.kt$' -e '/SectionTabsFragment.kt$'); do cp "$f" "$TMP/src/launcher/"; done
# The old #477 listener, put back beside the shared apply — appended as code
# (not a comment), so codeof() must see it and T3 must go red.
printf '%s\n' 'private fun oldStrip(t: TabLayout) { ViewCompat.setOnApplyWindowInsetsListener(t) { _, i -> i }; AppTabsStyle.apply(t) }' >> "$TMP/src/launcher/SectionTabsFragment.kt"
grep -q 'setOnApplyWindowInsetsListener' "$TMP/src/launcher/SectionTabsFragment.kt" || bad "T4: the mutation did not land in the scratch copy"
t3 "$TMP/src" && bad "T4: T3 passed a section strip with its own listener back" || ok "T4: a per-strip listener back → RED"
[ "$(grep -rl 'AppTabsStyle.apply(' "$TMP/src" --include=*.kt | grep -vc '/AppTabsStyle.kt$')" -ge 3 ] && ok "T4: the scratch tree still has every strip site" || bad "T4: the scratch tree lost a strip site — the mutation proved nothing"

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" = 0 ]
