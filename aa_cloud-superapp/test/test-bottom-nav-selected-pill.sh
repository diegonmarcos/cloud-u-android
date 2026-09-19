#!/usr/bin/env bash
# Tester: the bottom-nav selection is ONE light capsule around icon AND label,
# rendered by CloudBottomNavView — the 2026-09-19 rebuild that deleted the
# Material stack.
#
# HISTORY, so nobody reintroduces it. The Material implementation
# (BottomNavigationView + themed style + transparent ActiveIndicator + a
# subclass of counter-patches) closed FOUR tickets green while the device
# rendered an icon-only halo: Material's NavigationBarItemView takes hidden
# branches (refreshItemBackground suppresses itemBackground while the
# indicator is ENABLED; the menu view claims every offered pixel) that
# differ between the device and Robolectric. The rebuild is one LinearLayout
# whose code path is identical everywhere. These checks pin the rebuilt
# contract; BottomNavGeometryTest + BottomNavSelectedPillTest measure it on
# the inflated view, and CI runs both.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
RES="$APP/app/src/main/res"
NAV_KT="$APP/app/src/main/java/com/diegonmarcos/superapp/ui/CloudBottomNavView.kt"
THEMES="$RES/values/themes.xml"
DIMENS="$RES/values/dimens.xml"
ISLAND="$RES/drawable/bg_nav_island.xml"
ITEM_BG="$RES/drawable/bg_bottom_nav_item_checked.xml"
LAYOUT="$RES/layout/activity_main.xml"
RADIUS_TOKEN="bottom_nav_pill_corner_radius"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2: $1"; fi; }

echo "== T1: the radius is declared exactly once, as a shared dimen token =="
grep -qF "<dimen name=\"$RADIUS_TOKEN\">999dp</dimen>" "$DIMENS" \
  && ok "values/dimens.xml declares @dimen/$RADIUS_TOKEN once (= 999dp)" \
  || bad "values/dimens.xml does not declare @dimen/$RADIUS_TOKEN = 999dp"
OCCURS=$(grep -rlF "@dimen/$RADIUS_TOKEN" "$RES" | wc -l | tr -d ' ')
[ "$OCCURS" -ge 2 ] \
  && ok "the shared token is referenced from $OCCURS files (container and item share one radius)" \
  || bad "the shared token is referenced from only $OCCURS file(s) — item and container pills have drifted apart"
MAGIC=$(grep -rnE 'radius="999dp"' "$ISLAND" "$ITEM_BG" || true)
[ -z "$MAGIC" ] \
  && ok "neither nav drawable restates the radius as a magic literal" \
  || bad "a nav drawable still restates the radius as a literal: $MAGIC"

echo "== T2: NO Material navigation classes anywhere in app source =="
# The structural pin of the rebuild. One import of the old widget is the
# whole rat's nest walking back in.
check "$(python3 - "$APP/app/src/main/java" <<'PY'
import os, sys
hits = []
for root, _, files in os.walk(sys.argv[1]):
    for f in files:
        if not f.endswith('.kt'): continue
        path = os.path.join(root, f)
        for n, line in enumerate(open(path), 1):
            s = line.strip()
            if s.startswith(('//', '*', '/*')): continue
            # the drawer's NavigationView is a different, allowed widget —
            # only the BAR classes are banned.
            if 'com.google.android.material.bottomnavigation' in s or \
               'com.google.android.material.navigation.NavigationBar' in s:
                hits.append('%s:%d' % (f, n))
print('; '.join('%s imports Material navigation — the pre-rebuild stack returning' % h for h in hits) or 'OK')
PY
)" "no Material bottom-navigation import in any Kotlin source"

echo "== T3: the selection capsule — light theme fill, shared radius, state_selected =="
check "$(python3 - "$ITEM_BG" "$RADIUS_TOKEN" <<'PY'
import re, sys
text = open(sys.argv[1]).read()
token = sys.argv[2]
p = []
m = re.search(r'<item\b[^>]*state_selected="true"[^>]*>(.*?)</item>', text, re.S)
if not m:
    p.append("no state_selected entry — CloudBottomNavView drives View.isSelected, not Checkable state_checked")
else:
    body = m.group(1)
    if '<inset' not in body:
        p.append("the selected shape is not inset, so it touches the island edge")
    if not re.search(r'<corners\b[^>]*radius="@dimen/%s"' % re.escape(token), body):
        p.append("the selected shape does not reference the shared @dimen/%s" % token)
    if not re.search(r'<solid\b[^>]*color="\?attr/colorSurfaceInverse"', body):
        p.append("the capsule fill is not ?attr/colorSurfaceInverse — the owner asked for the LIGHT Revolut capsule from the theme palette, and a literal or dark token loses it")
print("; ".join(p) or "OK")
PY
)" "selected entry: light theme-attr fill, shared radius, inset inside the container"

echo "== T4: selected content is the inverse pair, from one selector =="
check "$(python3 - "$RES/color/bottom_nav_content.xml" <<'PY'
import re, sys
t = open(sys.argv[1]).read()
p = []
if not re.search(r'state_selected="true"[^/]*color="\?attr/colorOnSurfaceInverse"', t):
    p.append("selected content is not ?attr/colorOnSurfaceInverse — dark ink on the light capsule is the Revolut half of the pair")
if '?attr/' not in t or re.search(r'#[0-9A-Fa-f]{6,8}', t):
    p.append("the selector carries a colour literal — themes cannot reach it")
print("; ".join(p) or "OK")
PY
)" "bottom_nav_content: onSurfaceInverse when selected, theme attrs only"

echo "== T5: every distance is a token, read by the ONE owner =="
check "$(python3 - "$NAV_KT" "$ITEM_BG" <<'PY'
import sys
kt = open(sys.argv[1]).read()
bg = open(sys.argv[2]).read()
p = []
for tok in ('bottom_nav_item_vertical_pad', 'bottom_nav_pill_inset',
            'bottom_nav_icon_label_gap', 'bottom_nav_icon_size',
            'bottom_nav_label_text_size'):
    if ('R.dimen.%s' % tok) not in kt:
        p.append('CloudBottomNavView no longer reads R.dimen.%s' % tok)
if '@dimen/bottom_nav_pill_inset' not in bg:
    p.append('the capsule drawable no longer insets by @dimen/bottom_nav_pill_inset')
if 'setPadding(0, pillInset + pad, 0, pillInset + pad)' not in kt:
    p.append('the cell padding is not one symmetric expression — top and bottom can drift apart')
print('; '.join(p) or 'OK')
PY
)" "CloudBottomNavView reads every token; padding symmetric by one expression"

echo "== T6: no theme style may smuggle includeFontPadding (a silent Android-14 no-op) =="
check "$(python3 - "$THEMES" <<'PY'
import re, sys
text = open(sys.argv[1]).read()
bad = [st.group(1) for st in re.finditer(r'<style name="([^"]*)"[^>]*>.*?</style>', text, re.S)
       if re.search(r'includeFontPadding', st.group(0))]
print("PROBLEM: style(s) carry includeFontPadding, which TextView.readTextAppearance silently ignores: %s" % ",".join(bad) if bad else "OK")
PY
)" "no style-smuggled includeFontPadding"

echo "== T7: the real font-metrics fix lives on the label instance =="
check "$(python3 - "$NAV_KT" "$LAYOUT" <<'PY'
import re, sys
kt = open(sys.argv[1]).read()
layout = open(sys.argv[2]).read()
p = []
if 'includeFontPadding = false' not in kt:
    p.append('CloudBottomNavView no longer sets includeFontPadding=false on the label — the glyphs ride high in their line box again (#498)')
m = re.search(r'<([A-Za-z0-9_.]+)\s+android:id="@\+id/bottom_nav"', layout)
if not m:
    p.append('no view declares @+id/bottom_nav')
elif m.group(1) != 'com.diegonmarcos.superapp.ui.CloudBottomNavView':
    p.append('bottom_nav is %s, not CloudBottomNavView — the rebuild was reverted' % m.group(1))
print('; '.join(p) or 'OK')
PY
)" "label sets includeFontPadding=false; layout names CloudBottomNavView"

echo "== T8: the runtime proof exists and CI runs it =="
# Text cannot prove what a view painted; #512's whole lesson. Both inflated-
# view tests must exist and tests.unit must be on.
check "$(python3 - "$APP" <<'PY'
import json, os, sys
app = sys.argv[1]
p = []
unit = json.load(open(os.path.join(app, "build.json"))).get("tests", {}).get("unit", {})
if not unit.get("task") or unit.get("enabled") is False:
    p.append("build.json::tests.unit is off — the inflated-view proofs never run in CI")
found = set()
for root, _, files in os.walk(os.path.join(app, "app", "src", "test")):
    for f in files:
        t = open(os.path.join(root, f)).read()
        if "R.layout.activity_main" in t and "@Test" in t:
            if "pill(" in t: found.add("pill")
            if "theEdgePillsShareTheIslandsEndCurvature" in t: found.add("curvature")
for need in ("pill", "curvature"):
    if need not in found:
        p.append("no JVM test measures the %s on the inflated view" % need)
print("; ".join(p) or "OK")
PY
)" "inflated-view pill + curvature tests exist and CI is told to run them"

echo "== T9 (curvature): the end inset IS the pill inset — concentric arcs by one token =="
check "$(python3 - "$DIMENS" <<'PY'
import re, sys
t = open(sys.argv[1]).read()
m = re.search(r'<dimen name="bottom_nav_end_inset">([^<]*)</dimen>', t)
if not m:
    print("PROBLEM: bottom_nav_end_inset is not declared")
elif m.group(1).strip() != "@dimen/bottom_nav_pill_inset":
    print("PROBLEM: bottom_nav_end_inset is %r, not an alias of @dimen/bottom_nav_pill_inset - the edge arcs drift off-centre the moment either number changes" % m.group(1))
else:
    print("OK")
PY
)" "bottom_nav_end_inset aliases bottom_nav_pill_inset"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
