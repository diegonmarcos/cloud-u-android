#!/usr/bin/env bash
# #1320 the web mimic's tokens (cloudy-h-webapps-mob scripts/gen-tokens.mjs) are GENERATED from these Android sources. This
# tester fails HERE when a field the generator reads is renamed or moved, so the Android change is the one that goes red
# (the web side's --check only sees it a day later). One line per source the generator cites.
#   T1 launcher theme palettes: every role of every ui.launcher_themes[] resolves to a colors.xml colour or a drawable
#   T2 NavTokens.kt data-class defaults (the fields the island and strips are drawn with) + NavDecl.kt limits
#   T3 KitDensity (KitComponents.kt), KitPalette state defaults + kitTypography + KitCard shape (CloudKit.kt)
#   T4 shell dimens (dimens.xml) + the actionBarSize note, Theme.Superapp items, the four anim durations
#   T5 fleet chrome specs (libs:ui-nav-bars BottomSpec/TopFirstRowSpec/SecondRowSpec/SideMenuSpec, SearchHtmlTokens,
#      ThemeBackground DARK/LIGHT), launcher-icons.json categories, FleetWebBackground, UiIcons tintedTable
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -qE -- "$2" "$1" 2>/dev/null; }
NAV="$ROOT/ab_cloud-libs-shared/libs/bottomnav/src/commonMain/kotlin/com/diegonmarcos/superapp/bottomnav"
KIT="$ROOT/ab_cloud-libs-shared/libs/ui-kit/src/commonMain/kotlin/com/diegonmarcos/superapp/uikit"
BARS="$ROOT/ab_cloud-libs-shared/libs/ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp"
RES="$APP/app/src/main/res"

out="$(python3 - "$APP/build.json" "$RES" <<'PY'
import json, os, re, sys
ui = json.load(open(sys.argv[1]))["ui"]; res = sys.argv[2]
cols = set(re.findall(r'<color name="([^"]+)"', open(os.path.join(res, "values/colors.xml")).read()))
roles = ["window", "surface", "surface_selected", "text_primary", "text_secondary", "accent", "hairline", "tile_ink"]
bad = []
for t in ui.get("launcher_themes", []):
    p = t.get("palette") or {}
    for r in roles:
        n = p.get(r)
        if not n or (n not in cols and not os.path.exists(os.path.join(res, "drawable", n + ".xml"))): bad.append(f"{t.get('id')}.{r}={n}")
print("BAD " + ",".join(bad) if bad else "OK %d themes" % len(ui.get("launcher_themes", [])))
PY
)"
case "$out" in OK*) ok "T1 every launcher theme role resolves ($out)";; *) bad "T1 theme palette roles: $out";; esac

for f in pillInset iconSize labelTextSize islandBottomMargin widthFraction collapseMs minTouchTarget minCellWidth fitMinCell fitIconSize fitLabelTextSize \
         tabsPillRadius tabsPillPadH tabsPillPadV tabsTextSize tabsLetterSpacingMilli tabsMinChars islandFill pillFill pillInk idleInk tabsSelectedFill tabsIdleFill; do
  has "$NAV/NavTokens.kt" "^\s*val $f: (Dp|TextUnit|Color|Float|Int) = " || { bad "T2 NavTokens.$f is gone"; continue; }
done
[ $FAIL = 0 ] && ok "T2 NavTokens keeps every field the web reads"
for k in MAX_BOTTOM MIN_BOTTOM EQUAL_CELLS_BOTTOM; do has "$NAV/NavDecl.kt" "const val $k: Int = [0-9]+" || bad "T2 NavDecl.$k"; done
has "$NAV/NavDecl.kt" "const val EQUAL_CELLS_BOTTOM" && ok "T2 NavDecl limits"

has "$KIT/KitComponents.kt" "object KitDensity \{" && has "$KIT/KitComponents.kt" "const val TEXT_SCALE: Float = " && ok "T3 KitDensity" || bad "T3 KitDensity / TEXT_SCALE moved"
for s in OK WARN BAD; do has "$KIT/CloudKit.kt" "val DEFAULT_$s: Color = Color\(0x" || bad "T3 KitPalette.DEFAULT_$s"; done
has "$KIT/CloudKit.kt" "fun kitTypography\(" && has "$KIT/CloudKit.kt" "lineHeight = TextUnit.Unspecified, letterSpacing = TextUnit.Unspecified" && ok "T3 kitTypography dense()" || bad "T3 kitTypography changed shape"
has "$KIT/CloudKit.kt" "clip\(RoundedCornerShape\([0-9]+\.dp\)\)\.background\(p\.surface\)\.padding\([0-9]+\.dp\)" && ok "T3 KitCard shape" || bad "T3 KitCard shape moved"

for n in shell_top_row_margin shell_top_inset; do has "$RES/values/dimens.xml" "name=\"$n\"" || bad "T4 dimens $n"; done
has "$RES/values/dimens.xml" "[0-9]+ dp in Theme\.Superapp" && ok "T4 shell dimens + actionBarSize note" || bad "T4 the actionBarSize note left dimens.xml"
has "$RES/values/themes.xml" '<style name="Theme\.Superapp"' && ok "T4 Theme.Superapp" || bad "T4 Theme.Superapp renamed"
for a in fade_in fade_out slide_in_up slide_out_down; do has "$RES/anim/$a.xml" 'android:duration="[0-9]+"' || bad "T4 anim/$a"; done

for o in "navbarbottom/NavBarBottom.kt:BottomSpec" "navbartop1/NavBarTopFirstRow.kt:TopFirstRowSpec" "navbartop2/NavBarTopSecondRow.kt:SecondRowSpec" "navbartop2/NavSideMenu.kt:SideMenuSpec"; do
  has "$BARS/${o%%:*}" "object ${o##*:} \{" || bad "T5 ${o##*:} left ${o%%:*}"
done
has "$ROOT/ab_cloud-libs-shared/libs/bottomnav/src/main/kotlin/com/diegonmarcos/superapp/bottomnav/BottomNavSearchHtml.kt" "object SearchHtmlTokens \{" || bad "T5 SearchHtmlTokens"
TB="$ROOT/ab_cloud-libs-shared/libs/theme-background/src/main/kotlin/com/diegonmarcos/superapp/themebg/ThemeBackground.kt"
has "$TB" "private val DARK = ThemeColors\(" && has "$TB" "private val LIGHT = ThemeColors\(" && has "$TB" "val TAB_SELECTED: Color" || bad "T5 ThemeBackground palettes"
python3 -c "import json,sys; c=json.load(open(sys.argv[1]))['categories']; assert all('start' in v and 'end' in v for v in c.values())" "$ROOT/1_cicd/src/data/launcher-icons.json" 2>/dev/null || bad "T5 launcher-icons.json categories"
has "$ROOT/ab_cloud-libs-shared/web/fleet-ui/src/wasmJsMain/kotlin/com/diegonmarcos/fleet/web/FleetWebTheme.kt" "val FleetWebBackground = Color\(0x" || bad "T5 FleetWebBackground"
has "$ROOT/ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt" "tintedTable" || bad "T5 UiIcons tintedTable"
[ $FAIL = 0 ] && ok "T5 fleet chrome specs, categories, web background, icon catalog"

echo "web-tokens: $PASS passed, $FAIL failed"
[ $FAIL = 0 ]
