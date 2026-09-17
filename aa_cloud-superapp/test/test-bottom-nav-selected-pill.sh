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
# in seconds inside the ship workflow's shell phase.
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

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
