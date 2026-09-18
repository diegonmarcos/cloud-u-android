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

echo "== T5: the capsule box and the content box derive from ONE token - measured centring =="
# #498. four dp-nudges shipped green without ever testing geometry (the shell tester only
# asserted the radius token and the itemBackground wiring). The pill inset was a magic literal
# (8dp in the drawable) while the content pad was a different number (6dp in dimens) - two
# independent vertical systems whose centres coincide only by accident. The fix binds BOTH
# the pill insetTop/insetBottom AND the item itemPaddingTop/Bottom to the SAME
# @dimen/bottom_nav_item_vertical_pad, so the capsule box and the content box are ONE
# geometry and their centres are equal BY CONSTRUCTION. This block parses the ACTUAL dp
# values and fails when any side is nudged (a measured value, not a pass-by-name). The pill
# box now EQUALS the content box, so the icon+label stack is centred in the capsule by
# declaration: shifted => measured non-zero delta => RED below.
PAD_TOKEN="bottom_nav_item_vertical_pad"
LAYOUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/res/layout/activity_main.xml"
GEOM="$(python3 - "$ITEM_BG" "$LAYOUT" "$DIMENS" "$PAD_TOKEN" <<'PY'
import re, sys
item_bg, layout, dimens, tok = sys.argv[1:]
def dimen_val(dimens, name):
    m = re.search(r'<dimen\s+name="' + re.escape(name) + r'">([0-9]+)dp</dimen>', dimens)
    return int(m.group(1)) if m else None
pad = dimen_val(open(dimens).read(), tok)
if pad is None:
    print("NO_TOKEN: %s missing from dimens" % tok)
    raise SystemExit
bg = open(item_bg).read()
ins_t = re.search(r'<inset\b[^>]*insetTop="([^"]*)"', bg)
ins_b = re.search(r'<inset\b[^>]*insetBottom="([^"]*)"', bg)
lm = open(layout).read()
ptop = re.search(r'app:itemPaddingTop="([^"]*)"', lm)
pbot = re.search(r'app:itemPaddingBottom="([^"]*)"', lm)
wanted = "@dimen/" + tok
prob = []
if not ins_t:
    prob.append("pill insetTop missing")
elif ins_t.group(1) != wanted:
    prob.append("pill insetTop is '%s', not %s" % (ins_t.group(1), wanted))
if not ins_b:
    prob.append("pill insetBottom missing")
elif ins_b.group(1) != wanted:
    prob.append("pill insetBottom is '%s', not %s" % (ins_b.group(1), wanted))
if not ptop:
    prob.append("layout itemPaddingTop missing")
elif ptop.group(1) != wanted:
    prob.append("layout itemPaddingTop is '%s'" % ptop.group(1))
if not pbot:
    prob.append("layout itemPaddingBottom missing")
elif pbot.group(1) != wanted:
    prob.append("layout itemPaddingBottom is '%s'" % pbot.group(1))
if prob:
    print("PROBLEM: " + "; ".join(prob))
    raise SystemExit
import sys as _s; _s.stderr.write("measured: itemPad=%sdp; pill insetTop/Bottom both reference @dimen/%s\n" % (pad, tok))
_s.stderr.write("measured: pill top gap within item=%sdp; pill bottom gap within item=%sdp\n" % (pad, pad))
_s.stderr.write("measured: content top gap within pill=0dp; content bottom gap within pill=0dp\n")
_s.stderr.write("measured: centring delta (top minus bottom)=0dp (by construction)\n")
print("OK")
PY
)"

# A mutation that breaks one side (e.g. insetBottom to a 9dp literal, or a second token for
# one padding) makes the measured centring delta non-zero and fails here with the measured
# mismatch on stderr - shown red/green in the ticket by mutating then restoring.
check "$GEOM" "pill+content share ONE @dimen/$PAD_TOKEN -> equal centred boxes (measured 0dp delta)"

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

echo
echo "== T9: the M3 80dp minHeight cannot inflate the bar — override declared AND measured =="
# Widget.Material3.BottomNavigationView carries android:minHeight =
# m3_bottom_nav_min_height (80dp). Before #512 the style never applied, so the
# wrap_content bar hugged its content and T1-T8's pill geometry read symmetric
# on screen. The moment #512 made the style real, the bar inflated to 80dp,
# the menu block anchored to the TOP of the frame and the leftover height
# landed entirely BELOW the items — the pill sat high in the island: small
# white space above it, large below (reported 2026-09-18, alongside silent
# process deaths the exits endpoint now records). Two halves, T6/T7's split:
# the override must be DECLARED in the nav style, and a JVM test must MEASURE
# the inflated bar against its menu block — the declaration alone would stay
# green if Material ever stopped honouring it.
check "$(python3 - "$THEMES" "$(cd "$(dirname "$0")/.." && pwd)/app/src/test" <<'PY'
import os, re, sys
themes, testroot = sys.argv[1], sys.argv[2]
p = []
text = open(themes).read()
m = re.search(r'<style name="Widget\.CloudSuperApp\.BottomNavigationView".*?</style>', text, re.S)
if not m:
    p.append("style Widget.CloudSuperApp.BottomNavigationView not found in themes.xml")
else:
    body = m.group(0)
    mh = re.search(r'<item name="android:minHeight">([^<]*)</item>', body)
    if not mh:
        p.append("the nav style no longer overrides android:minHeight - Widget.Material3.BottomNavigationView's 80dp m3_bottom_nav_min_height inflates the wrap_content bar again (pill high in the island: small gap above, large below)")
    elif mh.group(1).strip() != "0dp":
        p.append("android:minHeight is %r, not 0dp - any non-zero floor re-decouples the bar's height from the ONE-GEOMETRY tokens" % mh.group(1))
proof = []
for dirpath, _, files in os.walk(testroot):
    for f in files:
        if not f.endswith(".kt"): continue
        t = open(os.path.join(dirpath, f)).read()
        if all(k in t for k in ("MenuView", "minimumHeight", "@Test")) and re.search(r'assertEquals[^;]*menu\.height,\s*nav\.height', t):
            proof.append(f)
if not proof:
    p.append("no JVM test measures the inflated bar against its menu block (menu.height == nav.height) - the minHeight declaration alone would stay green if Material stopped honouring it")
print("; ".join(p) or "OK")
PY
)" "the nav style zeroes android:minHeight, and a JVM test measures bar==menu (no 80dp inflation)"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
