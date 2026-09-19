#!/usr/bin/env bash
# test-main-menu-single-decl.sh — the home-screen top-left menu is "main-menu",
# and the site map lives in exactly ONE declaration.
#
# WHY (2026-09-19 #520): the top-left affordance was the anonymous
# top_menu_orb and the drawer's a11y label still said "Open navigation",
# while the owner calls it the main menu. It is now main-menu by code
# identifier (`main_menu_orb`) and by display label (`main_menu_open` /
# `main_menu_close`), and the drawer's nav inventory is build.json::ui.home_groups
# — declared ONCE, consumed by the drawer AND the Home Apps view. Anything
# that re-declares the site map is a second seam for the two surfaces to
# drift apart, which is the exact defect this tester exists to catch.
set -u
cd "$(dirname "$0")/.."
ROOT="app/src/main"
LAYOUT="$ROOT/res/layout/activity_main.xml"
SHELL_KT="$ROOT/java/com/diegonmarcos/superapp/ShellActivity.kt"
DRAWER_KT="$ROOT/java/com/diegonmarcos/superapp/launcher/HomeDrawerFragment.kt"
STRINGS_ES="$ROOT/res/values/strings.xml"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { [ "$1" = "OK" ] && ok "$2" || bad "$2 — $1"; }

echo "== T1: the old menu identifier is GONE — count of 'top_menu_orb' == 0 =="
check "$(python3 - "$ROOT" <<'PY'
import os, sys
root = sys.argv[1]
hits = []
for dirpath, _, files in os.walk(root):
    for f in files:
        if not f.endswith((".kt", ".xml")): continue
        path = os.path.join(dirpath, f)
        for n, line in enumerate(open(path), 1):
            if "top_menu_orb" in line:
                hits.append("%s:%d" % (os.path.relpath(path, root), n))
print("; ".join(hits) or "OK")
PY
)" "top_menu_orb has zero occurrences in app/src"

echo "== T2: the main-menu identifier exists and is wired =="
check "$(python3 - "$LAYOUT" "$SHELL_KT" <<'PY'
import sys
lay, kt = open(sys.argv[1]).read(), open(sys.argv[2]).read()
p = []
if 'android:id="@+id/main_menu_orb"' not in lay:
    p.append("main_menu_orb is not declared in the layout")
if 'R.id.main_menu_orb' not in kt:
    p.append("ShellActivity never references R.id.main_menu_orb — the orb is not wired")
print("; ".join(p) or "OK")
PY
)" "main_menu_orb declared in layout and referenced in ShellActivity"

echo "== T3: the display label says main-menu, and the old label is dead =="
check "$(python3 - "$STRINGS_ES" "$LAYOUT" "$SHELL_KT" <<'PY'
import sys
strings, lay, kt = open(sys.argv[1]).read(), open(sys.argv[2]).read(), open(sys.argv[3]).read()
p = []
for name, want in (("main_menu_open", "Open main menu"), ("main_menu_close", "Close main menu")):
    if ('<string name="%s">%s</string>' % (name, want)) not in strings:
        p.append("%s is missing or not exactly %r" % (name, want))
if 'drawer_open' in lay or 'drawer_close' in lay:
    p.append("the layout still names the old drawer_open/drawer_close labels")
if 'R.string.drawer_open' in kt or 'R.string.drawer_close' in kt:
    p.append("ShellActivity still binds the old drawer_open/drawer_close labels")
if '<string name="drawer_open">' in strings or '<string name="drawer_close">' in strings:
    p.append("the old drawer_open/drawer_close strings still exist")
print("; ".join(p) or "OK")
PY
)" "main_menu_open/close strings used; old drawer_open/close gone"

echo "== T4: the site map is declared ONCE — home_groups is the single inventory =="
check "$(python3 - build.json "$DRAWER_KT" "$ROOT" <<'PY'
import json, os, sys
bj = sys.argv[1]; drawer = sys.argv[2]; root = sys.argv[3]
d = json.load(open(bj))
ui = d.get('ui', {})
p = []
if 'home_groups' not in ui or not isinstance(ui['home_groups'], list) or not ui['home_groups']:
    p.append("build.json::ui.home_groups is missing or empty — the drawer's inventory")
if 'home_drawer_prepend' not in ui:
    p.append("build.json::ui.home_drawer_prepend is missing — the prepend inventory")
# Exactly ONE JSON key named home_groups — the decoration lives in _doc_*,
# the data is one list.
if (json.dumps(list(ui.keys())).count('"home_groups"') != 1):
    p.append("ui declares home_groups more than once")
dt = open(drawer).read()
if 'Sections.homeGroups()' not in dt or 'Sections.homeDrawerPrepend()' not in dt:
    p.append("HomeDrawerFragment does not build from the home_groups/prepend accessors")
# The drawer declares no hardcoded section-page inventory of its own: every
# MenuItem label it emits comes from a Sections accessor, never a literal.
for lit in ('"communication"', '"configs"', '"infos"', '"labs"', '"suite"', '"tools"'):
    if lit in dt:
        p.append("HomeDrawerFragment hardcodes a section id (%s) — a second copy of the site map" % lit)
# And no other file in app/src may declare a second nav inventory keyed on
# the same sections with its own menu-building loop — single declaration.
for dirpath, _, files in os.walk(root):
    for f in files:
        if not f.endswith(".kt"): continue
        path = os.path.join(dirpath, f)
        if path.endswith("HomeDrawerFragment.kt"): continue
        t = open(path).read()
        if 'Sections.homeGroups()' in t and 'menu.addSubMenu' in t:
            p.append("%s builds a menu from homeGroups — the drawer is not the only site map" % os.path.relpath(path, root))
print("; ".join(p) or "OK")
PY
)" "home_groups declared once, drawer consumes it, no second inventory"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]