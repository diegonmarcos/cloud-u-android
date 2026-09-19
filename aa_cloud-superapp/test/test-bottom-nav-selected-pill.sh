#!/usr/bin/env bash
# Tester: the selected bottom-nav item is ONE pill around the icon AND the
# label; the pill radius is declared once and shared with the container pill
# (requirement of task 462), and the icon-bound Material 3 active indicator
# paints nothing anymore.
#
# WHY THIS EXISTS. #417/#431 shipped the nav CONTAINER as a full pill but left
# the per-item SELECTED indicator on Material 3's stock ActiveIndicator, which
# is a separate view bound to the icon container and therefore wraps only the
# ICON — no width/height/shapeAppearance tuning on itemActiveIndicatorStyle can
# grow it to include the label, because that is a structural property of the
# widget, not a value. The mechanism that paints behind the WHOLE item (icon
# plus label) is itemBackground, whose state_checked="true" entry is a pill.
# These assertions pin that contract so a future edit cannot silently
# resurrect the icon-only highlight or re-introduce a disconnected radius.
#
# Static tester (no device, no build): pure resource-file checks, so it runs
# in seconds inside the ship workflow's shell phase. STATIC MEANS PARTIAL: it
# was green for a whole release with no pill on screen (#512). What the view
# actually resolved is proven by app/src/test/.../BottomNavSelectedPillTest.kt,
# and T8 fails this file if that test is missing or not run by CI.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # -> aa_cloud-superapp
RES="$APP/app/src/main/res"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2: $1"; fi; }

THEMES="$RES/values/themes.xml"
DIMENS="$RES/values/dimens.xml"
ISLAND="$RES/drawable/bg_nav_island.xml"
ITEM_BG="$RES/drawable/bg_bottom_nav_item_checked.xml"
RADIUS_TOKEN="bottom_nav_pill_corner_radius"

echo "== T1: the radius is declared exactly once, as a shared dimen token =="
[ -f "$DIMENS" ] || { bad "$DIMENS is missing — the radius has no single declaration"; }
if [ -f "$DIMENS" ]; then
  grep -qF "<dimen name=\"$RADIUS_TOKEN\">999dp</dimen>" "$DIMENS" \
    && ok "values/dimens.xml declares @dimen/$RADIUS_TOKEN once (= 999dp)" \
    || bad "values/dimens.xml does not declare @dimen/$RADIUS_TOKEN = 999dp"
fi
# The token must be REFERENCED (as @dimen/bottom_nav_pill_corner_radius) in at
# least the container drawable AND the item drawable — one declaration, two
# consumers. grep -l counts files, so this is 'two files must reference it'.
OCCURS=$(grep -rlF "@dimen/$RADIUS_TOKEN" "$RES" | wc -l | tr -d ' ')
[ "$OCCURS" -ge 2 ] \
  && ok "the shared token is referenced from $OCCURS files (container and item reference the same radius)" \
  || bad "the shared token is referenced from only $OCCURS file(s) — item and container pills have drifted apart"
# No file may restate the radius as a magic literal: the container used to
# hardcode 999dp in two places, and a copy beside the token is exactly the
# duplication the rule forbids.
MAGIC=$(grep -rnE 'radius="999dp"' "$ISLAND" "$ITEM_BG" || true)
[ -z "$MAGIC" ] \
  && ok "neither nav drawable restates the radius as a magic literal" \
  || bad "a nav drawable still restates the radius as a literal: $MAGIC"

echo "== T2: the whole-item selected pill is wired as itemBackground =="
if [ -f "$THEMES" ]; then
  NAV_NAME="$(python3 - "$THEMES" <<'PY'
import re, sys
text = open(sys.argv[1]).read()
m = re.search(r'<style name="Widget.CloudSuperApp.BottomNavigationView".*?</style>', text, re.S)
if not m:
    print(""); raise SystemExit
mm = re.search(r'name="itemBackground">@drawable/([^<]+)<', m.group(0))
print(mm.group(1) if mm else "")
PY
)"
  if [ -z "$NAV_NAME" ]; then
    bad "the nav style (Widget.CloudSuperApp.BottomNavigationView) does not declare itemBackground"
  elif [ -f "$RES/drawable/$NAV_NAME.xml" ]; then
    ok "the nav style wires itemBackground -> @drawable/$NAV_NAME"
  else
    bad "the style references itemBackground drawable $NAV_NAME.xml, which does not exist"
  fi
fi

echo "== T3: the checked entry is one capsule around the whole item =="
if [ -f "$ITEM_BG" ]; then
  check "$(python3 - "$ITEM_BG" "$RADIUS_TOKEN" <<'PY'
import re, sys
text = open(sys.argv[1]).read()
token = sys.argv[2]
p = []
m = re.search(r'<item\b[^>]*state_checked="true"[^>]*>(.*?)</item>', text, re.S)
if not m:
    p.append("there is no state_checked entry")
else:
    body = m.group(1)
    if '<inset' not in body:
        p.append("the checked shape is not inset, so it touches the container edge")
    if not re.search(r'<corners\b[^>]*radius="@dimen/%s"' % re.escape(token), body):
        p.append("the checked shape does not reference the shared @dimen/%s" % token)
    if not re.search(r'<solid\b[^>]*color="\?attr/colorPrimaryContainer"', body):
        p.append("the checked fill is not ?attr/colorPrimaryContainer, so it will not follow every launcher theme")
print("; ".join(p) or "OK")
PY
)" "checked entry: theme-attr fill, shared radius, inset inside the container"
fi

echo "== T4: the icon-bound active indicator paints nothing anymore =="
if [ -f "$THEMES" ]; then
  check "$(python3 - "$THEMES" <<'PY'
import re, sys
text = open(sys.argv[1]).read()
m = re.search(r'<style name="Widget.CloudSuperApp.BottomNavigationView.ActiveIndicator".*?</style>', text, re.S)
if not m:
    print("the ActiveIndicator style is gone entirely")
elif '@android:color/transparent' in m.group(0):
    print("OK")
elif 'cloud_primary_container' in m.group(0):
    print("the ActiveIndicator still paints cloud_primary_container behind the icon — it stacks a second highlight under the whole-item pill")
else:
    print("the ActiveIndicator still paints a colour behind the icon — the whole-item pill and the indicator would double up")
PY
)" "the ActiveIndicator style is neutralised to transparent"
fi

echo "== T5: the pill inset and the item pad are each declared ONCE, as tokens =="
# #498 (third attempt). WHAT THIS BLOCK USED TO BE. It printed four lines
# beginning "measured:" - "content top gap within pill=0dp", "centring delta
# (top minus bottom)=0dp (by construction)" - and every one of those numbers
# was a literal typed into this script. It had measured nothing. It checked
# that the pill's insetTop/insetBottom and the item's itemPaddingTop/Bottom
# all named the SAME dimen and concluded the stack was centred "by
# construction". The real laid-out bar has a different distance above the icon
# than below the label inside that pill, and a gap between icon and label far
# wider than the declared token - so the conclusion was false for five tickets
# while this file stayed green. That is the #511 blindness in its purest form.
#
# A shell tester reading XML CANNOT see rendered geometry: the distance from
# the pill edge to the glyph depends on Material's internal view tree and on
# font metrics, neither of which is in any file here. So this block now claims
# only what text can prove - each distance is declared once, as a token, so it
# cannot be restated as a drifting literal - and T9 makes this file FAIL unless
# the tester that really measures exists and is run by CI.
PAD_TOKEN="bottom_nav_item_vertical_pad"
PILL_TOKEN="bottom_nav_pill_inset"
LAYOUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/res/layout/activity_main.xml"
check "$(python3 - "$ITEM_BG" "$LAYOUT" "$DIMENS" "$PAD_TOKEN" "$PILL_TOKEN" <<'PY'
import re, sys
item_bg, layout, dimens, pad_tok, pill_tok = sys.argv[1:]
dm = open(dimens).read()
prob = []
vals = {}
for tok in (pad_tok, pill_tok):
    m = re.search(r'<dimen\s+name="' + re.escape(tok) + r'">([0-9.]+)dp</dimen>', dm)
    if not m:
        prob.append("@dimen/%s is not declared" % tok)
    else:
        vals[tok] = float(m.group(1))
bg = open(item_bg).read()
for attr in ("insetTop", "insetBottom"):
    m = re.search(r'<inset\b[^>]*%s="([^"]*)"' % attr, bg, re.S)
    if not m:
        prob.append("pill %s missing" % attr)
    elif m.group(1) != "@dimen/" + pill_tok:
        prob.append("pill %s is '%s', not @dimen/%s" % (attr, m.group(1), pill_tok))
lm = open(layout).read()
for attr in ("itemPaddingTop", "itemPaddingBottom"):
    m = re.search(r'app:%s="([^"]*)"' % attr, lm)
    if not m:
        prob.append("layout %s missing" % attr)
    elif m.group(1) != "@dimen/" + pad_tok:
        prob.append("layout %s is '%s', not @dimen/%s" % (attr, m.group(1), pad_tok))
# Deliberately NOT asserted here: how the two values relate on screen. The
# breathing room the ink gets inside the capsule is (rendered ink inset minus
# pill inset), and the rendered ink inset is not either of these numbers - it
# is these numbers plus whatever Material's own item tree adds. Guessing that
# relation from the dp literals is precisely the mistake this block used to
# make. BottomNavGeometryTest.theSelectedPillWrapsThatInkConcentrically
# measures it on the laid-out view; T9 makes this file fail if that test is
# missing.
print("; ".join(prob) or "OK")
PY
)" "pill inset and item pad are each declared ONE token, referenced by name from drawable and layout"

echo "== T6: no fake includeFontPadding-in-TextAppearance fix may be claimed =="
# The label font (Roboto-Medium 12sp) reserves ~12.6 ascent vs ~3.3 descent (read off the real
# font hhea tables), so its line box is asymmetric around the baseline. The correct lever
# would be includeFontPadding=false ON THE NAV LABEL, but that label is an internal view of
# NavigationBarItemView built by Material (design_bottom_navigation_item.xml) and reachable
# only via itemTextAppearance. Android 14 TextView.readTextAppearance() reads exactly 23
# TextAppearance attrs - includeFontPadding IS NOT one of them - so any includeFontPadding
# placed inside a style/TextAppearance is SILENTLY IGNORED by the platform. That is the
# #417-#477 trap: four tickets nudged the geometry and CI went green while the actual knob
# the framework hears was a no-op. This guard rejects smuggling it through the theme; the
# real fix must set it on the (single) TextView instance, never in a TextAppearance.
if [ -f "$THEMES" ]; then
  check "$(python3 - "$THEMES" <<'PY'
import re, sys
text = open(sys.argv[1]).read()
bad = []
for st in re.finditer(r'<style name="([^"]*)"[^>]*>.*?</style>', text, re.S):
    body = st.group(0)
    if re.search(r'includeFontPadding', body):
        bad.append(st.group(1))
if bad:
    print("PROBLEM: style(s) carry android:includeFontPadding: %s. Android's TextView.readTextAppearance() reads only the 23 TextAppearance attrs and silently ignores includeFontPadding inside a style/TextAppearance, so this is a no-op that cannot move the nav label; write it on the real TextView instead." % ",".join(bad))
else:
    print("OK")
PY
)" "no theme style may smuggle includeFontPadding (a silent Android-14 no-op)"
fi

echo "== T7: the REAL font-metrics fix is wired — includeFontPadding=false set on the actual TextView =="
# T6 only rejects the fake fix (a no-op placement). This checks the real one exists: the
# bottom_nav tag in activity_main.xml must be a custom view class (not the bare Material
# BottomNavigationView), and that class must set includeFontPadding=false directly on the
# TextView instances it finds in its own tree — the only lever Android 14 actually reads,
# per T6's own comment. A mutation reverting the layout tag to the stock Material class, or
# gutting the class body, must fail here.
LAYOUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/res/layout/activity_main.xml"
JAVA_ROOT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/java"
check "$(python3 - "$LAYOUT" "$JAVA_ROOT" <<'PY'
import re, sys
layout, java_root = sys.argv[1:]
lm = open(layout).read()
m = re.search(r'<([A-Za-z0-9_.]+)\s+android:id="@\+id/bottom_nav"', lm)
if not m:
    print("PROBLEM: no view declares android:id=\"@+id/bottom_nav\"")
    raise SystemExit
cls = m.group(1)
if cls == "com.google.android.material.bottomnavigation.BottomNavigationView":
    print("PROBLEM: bottom_nav is the bare Material BottomNavigationView again — the font-metrics fix (includeFontPadding on the real label TextView) has nowhere to live")
    raise SystemExit
if "." not in cls:
    print("PROBLEM: bottom_nav view class '%s' is unqualified/unexpected" % cls)
    raise SystemExit
rel = cls.replace(".", "/") + ".kt"
import os
path = os.path.join(java_root, rel)
if not os.path.isfile(path):
    print("PROBLEM: bottom_nav references %s but %s does not exist" % (cls, path))
    raise SystemExit
body = open(path).read()
if "BottomNavigationView" not in body:
    print("PROBLEM: %s does not extend/reference BottomNavigationView" % path)
    raise SystemExit
if not re.search(r'includeFontPadding\s*=\s*false', body):
    print("PROBLEM: %s never sets includeFontPadding = false — the class exists but does not apply the fix" % path)
    raise SystemExit
if "TextView" not in body:
    print("PROBLEM: %s sets includeFontPadding but never targets TextView, so it cannot reach the internal label view" % path)
    raise SystemExit
print("OK")
PY
)" "bottom_nav is a custom view class that sets includeFontPadding=false on its real TextView descendants"

echo "== T8: the themed style can actually REACH the view, and the resolved view is tested =="
# #512. T1-T7 all passed on a build that painted NO pill. The pill is itemBackground in the
# style behind ?attr/bottomNavigationStyle, and the #498 subclass declared
# `defStyleAttr: Int = 0` and handed that to Material's constructor — 0 means "no default
# style attribute", so the whole themed style was dropped while every XML file this tester
# reads stayed correct. Text cannot prove a view applied a style; only an inflated view can.
# So this block (a) rejects the one static shape of that bug, on whatever class the layout
# names at @+id/bottom_nav (resolved by id, walked up to Material's class — no hardcoded
# class name), and (b) FAILS unless the runtime proof exists and CI is told to run it: a JVM
# test that inflates activity_main and reads the resolved itemBackground off the view.
check "$(python3 - "$LAYOUT" "$JAVA_ROOT" "$APP" <<'PY'
import json, os, re, sys
layout, java_root, app = sys.argv[1:]
MATERIAL = "com.google.android.material.bottomnavigation.BottomNavigationView"
m = re.search(r'<([A-Za-z0-9_.]+)\s+android:id="@\+id/bottom_nav"', open(layout).read())
if not m:
    print("PROBLEM: no view declares android:id=\"@+id/bottom_nav\""); raise SystemExit
cls, p, hops = m.group(1), [], 0
while cls != MATERIAL:
    hops += 1
    path = os.path.join(java_root, cls.replace(".", "/") + ".kt")
    if hops > 8 or not os.path.isfile(path):
        p.append("cannot resolve %s up to %s (no source at %s)" % (cls, MATERIAL, path)); break
    body = open(path).read()
    d = re.search(r'defStyleAttr\s*:\s*Int\s*=\s*([^,)\n]+)', body)
    if d and not d.group(1).strip().endswith("R.attr.bottomNavigationStyle"):
        p.append("%s defaults defStyleAttr to '%s' — anything but R.attr.bottomNavigationStyle drops the themed style (and the pill with it)" % (cls, d.group(1).strip()))
    sup = re.search(r'\)\s*:\s*([A-Za-z0-9_.]+)\s*\(', body)
    if not sup:
        p.append("%s has no superclass constructor call to follow" % cls); break
    name = sup.group(1)
    imp = re.search(r'^import\s+([\w.]+\.%s)\s*$' % re.escape(name), body, re.M)
    pkg = re.search(r'^package\s+([\w.]+)', body, re.M)
    cls = name if "." in name else imp.group(1) if imp else "%s.%s" % (pkg.group(1), name)
unit = json.load(open(os.path.join(app, "build.json"))).get("tests", {}).get("unit", {})
if not unit.get("task") or unit.get("enabled") is False:
    p.append("build.json::tests.unit is off — the resolved-view proof would never run in CI")
proof = []
for root, _, files in os.walk(os.path.join(app, "app", "src", "test")):
    for f in files:
        t = open(os.path.join(root, f)).read()
        if all(k in t for k in ("R.layout.activity_main", "R.id.bottom_nav", "itemBackgroundResource", "@Test")):
            proof.append(f)
if not proof:
    p.append("no JVM test under app/src/test inflates R.layout.activity_main and reads itemBackgroundResource off R.id.bottom_nav — this tester alone is XML text and has been green with no pill on screen")
print("; ".join(p) or "OK")
PY
)" "bottom_nav's class chain keeps ?attr/bottomNavigationStyle, and an inflated-view test is wired into CI"

echo "== T10: the geometry is proven on a laid-out view, not on this file =="
# #498 (third attempt). T1-T8 are text. T8 already refuses to pass without a
# test that inflates the nav and reads the RESOLVED itemBackground; that
# caught #512's missing pill but says nothing about SPACING, which is what
# Diego reports and what four tickets kept getting wrong. This block refuses
# to pass unless a JVM test also LAYS THE BAR OUT and asserts the distances:
# the icon-to-label gap against @dimen/bottom_nav_icon_label_gap, the ink's
# inset against @dimen/bottom_nav_item_vertical_pad, and includeFontPadding
# read back off a real TextView instance (T7 can only grep for the words).
check "$(python3 - "$APP" <<'PY'
import json, os, sys
app = sys.argv[1]
need = [
    ("R.layout.activity_main",         "it never inflates the real layout"),
    ("R.id.bottom_nav",                "it never finds the bar by its role id"),
    ("offsetDescendantRectToMyCoords", "it never converts a child's laid-out bounds into bar coordinates, so it cannot be measuring positions"),
    ("bottom_nav_icon_label_gap",      "it never checks the rendered icon-to-label gap against the declared token"),
    ("bottom_nav_item_vertical_pad",   "it never checks the rendered inset against the declared pad"),
    ("includeFontPadding",             "it never reads includeFontPadding back off a TextView instance"),
]
prob = []
unit = json.load(open(os.path.join(app, "build.json"))).get("tests", {}).get("unit", {})
if not unit.get("task") or unit.get("enabled") is False:
    prob.append("build.json::tests.unit is off - a measuring test would never run in CI")
best, best_missing = None, None
for root, _, files in os.walk(os.path.join(app, "app", "src", "test")):
    for f in files:
        t = open(os.path.join(root, f)).read()
        if "@Test" not in t:
            continue
        missing = [why for key, why in need if key not in t]
        if best_missing is None or len(missing) < len(best_missing):
            best, best_missing = f, missing
if best_missing is None:
    prob.append("there is no JVM test at all under app/src/test")
elif best_missing:
    prob.append("no JVM test measures the laid-out bar (closest is %s: %s)" % (best, "; ".join(best_missing)))
print("; ".join(prob) or "OK")
PY
)" "a JVM test lays the bar out and asserts its distances against the declared dimens"

echo
echo "== T9: the bar's height is stated and capped — it may not fill its parent =="
# Widget.Material3.BottomNavigationView carries android:minHeight =
# m3_bottom_nav_min_height (80dp), and when the bar came out too tall the
# instinct was to zero it. That is backwards. In
# BottomNavigationView.makeMinHeightSpec this attribute is not a floor, it is
# the CAP - min(available, minHeight), forced to EXACTLY - and
# BottomNavigationMenuView.onMeasure then takes whatever height it is handed,
# EXACTLY. Zero skips the clamp entirely and the wrap_content bar fills its
# parent: 788.00dp of bottom bar measured on CI 35402703581, while the tester
# written to catch that inflation stayed green because it compared the bar to
# its own menu block - and those are equal by construction at every height.
# So: the style must NOT carry a zero (or any) android:minHeight, and a JVM
# test must assert the measured height against the DECLARED geometry.
check "$(python3 - "$THEMES" "$(cd "$(dirname "$0")/.." && pwd)/app/src/test" <<'PY'
import os, re, sys
themes, testroot = sys.argv[1], sys.argv[2]
p = []
text = open(themes).read()
m = re.search(r'<style name="Widget\.CloudSuperApp\.BottomNavigationView".*?</style>', text, re.S)
if not m:
    p.append("style Widget.CloudSuperApp.BottomNavigationView not found in themes.xml")
else:
    mh = re.search(r'<item name="android:minHeight">([^<]*)</item>', m.group(0))
    if mh:
        p.append("the nav style sets android:minHeight=%r. That attribute is Material's height CAP, "
                 "not a floor: 0dp removes the cap and the bar fills its parent (788dp, CI 35402703581), "
                 "and any literal decouples the height from the declared item geometry. The height is "
                 "computed from the tokens plus the label's ink height in "
                 "CenteredLabelBottomNavigationView." % mh.group(1))
proof = []
for dirpath, _, files in os.walk(testroot):
    for f in files:
        if not f.endswith(".kt"):
            continue
        t = open(os.path.join(dirpath, f)).read()
        if "@Test" not in t or "minimumHeight" not in t:
            continue
        # The height must be checked against declared dimens, never against
        # another view of the same bar - menu.height == nav.height is a
        # tautology and shipped as one.
        if "bottom_nav_pill_inset" in t and "bottom_nav_icon_label_gap" in t and "nav.height" in t:
            proof.append(f)
if not proof:
    p.append("no JVM test asserts the measured bar height against the declared dimens "
             "(pill inset + pad + icon + gap + label + pad + pill inset) - a height check that "
             "compares the bar to its own menu block cannot fail")
print("; ".join(p) or "OK")
PY
)" "no android:minHeight in the nav style, and the height is measured against the declared geometry"

echo
echo "== T11: the end inset IS the pill inset — concentric end arcs by one token =="
# The island and the pill share one radius token and both clamp to stadium
# ends. Their end arcs are concentric only when the horizontal pill→island
# gap (the bar's paddingStart/End = bottom_nav_end_inset) equals the pill's
# vertical inset (bottom_nav_pill_inset): radii then differ by exactly that
# gap and the centres coincide. A literal here CAN drift — a 16dp literal
# against the 6dp pill inset put the end arcs 10dp off-centre (reported
# 2026-09-19). The dimen must be an ALIAS, and the JVM geometry test must
# measure the ring on the laid-out edge cells.
check "$(python3 - "$RES/values/dimens.xml" "$(cd "$(dirname "$0")/.." && pwd)/app/src/test/java/com/diegonmarcos/superapp/ui/BottomNavGeometryTest.kt" <<'PY'
import re, sys
p = []
t = open(sys.argv[1]).read()
m = re.search(r'<dimen name="bottom_nav_end_inset">([^<]*)</dimen>', t)
if not m:
    p.append("bottom_nav_end_inset is not declared")
elif m.group(1).strip() != "@dimen/bottom_nav_pill_inset":
    p.append("bottom_nav_end_inset is %r, not an alias of @dimen/bottom_nav_pill_inset - the end arcs drift off-centre the moment either number changes" % m.group(1))
jt = open(sys.argv[2]).read()
if "theEdgePillsShareTheIslandsEndCurvature" not in jt:
    p.append("BottomNavGeometryTest no longer measures the edge-cell ring (theEdgePillsShareTheIslandsEndCurvature) - the alias alone cannot prove the drawn arcs are concentric")
print("; ".join(p) or "OK")
PY
)" "end inset aliases the pill inset, and the edge ring is measured on the laid-out bar"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
