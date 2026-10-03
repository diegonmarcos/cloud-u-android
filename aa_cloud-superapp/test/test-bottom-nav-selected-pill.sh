#!/usr/bin/env bash
# Tester: the shell's bottom nav IS libs:bottomnav's Compose island, and nothing
# of the private View nav survives to shadow or fork it (#531).
#
# HISTORY, so nobody reintroduces it. The Material bar (BottomNavigationView +
# themed style + ActiveIndicator + counter-patch subclass) closed four tickets
# green while the device showed an icon-only halo. The 2026-09-19 rebuild
# (CloudBottomNavView) fixed that for superapp alone. #565 ported that design
# into ONE Compose module for the whole fleet, and #531 deleted superapp's
# private copy. What this file pins is STRUCTURE: which widget the shell hosts
# and that no second declaration of the nav is left. The geometry and the
# painted pill are MEASURED on the configured island by BottomNavGeometryTest
# and BottomNavSelectedPillTest (JVM, CI tests.unit), never read off XML here.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$APP/app/src/main"
LAYOUT="$MAIN/res/layout/activity_main.xml"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2: $1"; fi; }

echo "== T1: the shell's bottom island is libs:bottomnav's host =="
check "$(python3 - "$LAYOUT" <<'PY'
import re, sys
t = open(sys.argv[1]).read()
m = re.search(r'<([A-Za-z0-9_.]+)\s+android:id="@\+id/bottom_nav_island"', t)
if not m:
    print('no view declares @+id/bottom_nav_island - every star and chrome anchor loses its target')
elif m.group(1) != 'com.diegonmarcos.superapp.bottomnav.BottomNavIslandView':
    print('bottom_nav_island is %s, not libs:bottomnav BottomNavIslandView - a private nav is back' % m.group(1))
else:
    print('OK')
PY
)" "activity_main hosts com.diegonmarcos.superapp.bottomnav.BottomNavIslandView as bottom_nav_island"

echo "== T2: NO Material navigation-bar classes anywhere in app source or layouts =="
# Still the structural pin of the 2026-09-19 rebuild, and it still means the
# same thing after #531: the Material bar must not walk back in, whether as a
# Kotlin import or as a layout tag (the tag form was unguarded before #531).
check "$(python3 - "$MAIN" <<'PY'
import os, sys
hits = []
for root, _, files in os.walk(sys.argv[1]):
    for f in files:
        if not f.endswith(('.kt', '.xml')): continue
        path = os.path.join(root, f)
        for n, line in enumerate(open(path, encoding='utf-8', errors='replace'), 1):
            s = line.strip()
            if s.startswith(('//', '*', '/*', '<!--')): continue
            # the drawer's NavigationView is a different, allowed widget -
            # only the BAR classes are banned.
            if 'com.google.android.material.bottomnavigation' in s or \
               'com.google.android.material.navigation.NavigationBar' in s:
                hits.append('%s:%d' % (os.path.relpath(path, sys.argv[1]), n))
print('; '.join('%s names a Material navigation bar - the pre-rebuild stack returning' % h for h in hits) or 'OK')
PY
)" "no Material bottom-navigation class in any Kotlin source or layout"

echo "== T3: no private nav is left to fork or shadow the shared one =="
check "$(python3 - "$MAIN" <<'PY'
import os, re, sys
main = sys.argv[1]
p = []
for rel in ('java/com/diegonmarcos/superapp/ui/CloudBottomNavView.kt',
            'res/drawable/bg_nav_island.xml', 'res/drawable/bg_bottom_nav_item_checked.xml',
            'res/color/bottom_nav_content.xml', 'res/menu/bottom_nav.xml'):
    if os.path.exists(os.path.join(main, rel)):
        p.append('%s is back - a second declaration of the nav' % rel)
# A bottom_nav_* resource in the APP overrides the library's same-named one at
# resource merge: the island would render the app's number, not the lib's.
res = os.path.join(main, 'res')
for d in sorted(os.listdir(res)):
    if not d.startswith('values'): continue
    for f in os.listdir(os.path.join(res, d)):
        t = open(os.path.join(res, d, f), encoding='utf-8').read()
        for name in re.findall(r'<(?:dimen|color|fraction)\s+name="(bottom_nav_[^"]*)"', t):
            p.append('%s/%s declares %s - it overrides libs:bottomnav\'s token at merge' % (d, f, name))
for root, _, files in os.walk(os.path.join(main, 'java')):
    for f in files:
        if f.endswith('.kt') and re.search(r'class\s+\w*BottomNav\w*\s*[(:@]', open(os.path.join(root, f)).read()) \
                and f != 'ShellBottomNav.kt':
            p.append('%s declares its own bottom-nav class' % f)
print('; '.join(p) or 'OK')
PY
)" "CloudBottomNavView + its 4 res files gone, no app bottom_nav_* token shadows the library"

echo "== T4: build.json declares the bar once, and links the shared module =="
check "$(python3 - "$APP/build.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
p = []
mod = d.get('modules', {}).get('libs:bottomnav')
if not mod:
    p.append('modules has no libs:bottomnav - the shell cannot link the island')
elif mod.get('dir') != '../ab_cloud-libs-shared/libs/bottomnav':
    p.append('libs:bottomnav points at %r, not the ONE shared module' % mod.get('dir'))
ui = d.get('ui', {})
bar = ui.get('bottom_nav')
sections = {s.get('id'): s for s in ui.get('sections', [])}
if not isinstance(bar, list) or not bar:
    p.append('ui.bottom_nav is missing or empty - the island would have no items')
else:
    for sid in bar:
        if sid not in sections:
            p.append('ui.bottom_nav names %r, which is not a ui.sections id' % sid)
    if len(set(bar)) != len(bar):
        p.append('ui.bottom_nav repeats a section: %s' % bar)
flagged = [sid for sid, s in sections.items() if 'bottom_nav' in s]
if flagged:
    p.append('sections still carry a bottom_nav flag (%s) - a second declaration of the bar' % ', '.join(flagged))
print('; '.join(p) or 'OK')
PY
)" "modules.libs:bottomnav is the shared dir; ui.bottom_nav is the one list, every id a real section"

echo "== T5: Compose is on in the shell (reversed by decree 2026-09-24) =="
check "$(python3 - "$APP/app/build.gradle" <<'PY'
import re, sys
t = open(sys.argv[1]).read()
p = []
if re.search(r'^\s*compose\s*=\s*false', t, re.M):
    p.append('buildFeatures.compose = false is back - the island cannot compile')
if not re.search(r'^\s*compose\s*=\s*true', t, re.M):
    p.append('buildFeatures.compose = true is not declared')
if "id 'org.jetbrains.kotlin.plugin.compose'" not in t:
    p.append('the Compose compiler plugin is not applied')
if "project(':libs:bottomnav')" not in t:
    p.append("the app does not link project(':libs:bottomnav')")
print('; '.join(p) or 'OK')
PY
)" "compose = true, compiler plugin applied, libs:bottomnav linked"

echo "== T6: the runtime proof exists, measures the CONFIGURED island, and CI runs it =="
# Text cannot prove what a view painted (#512). Both JVM tests must exist, drive
# the island through ShellBottomNav.configure (the shell's own code path), and
# tests.unit must be on.
check "$(python3 - "$APP" <<'PY'
import json, os, sys
app = sys.argv[1]
p = []
unit = json.load(open(os.path.join(app, "build.json"))).get("tests", {}).get("unit", {})
if not unit.get("task") or unit.get("enabled") is False:
    p.append("build.json::tests.unit is off - the measured proofs never run in CI")
test = os.path.join(app, "app", "src", "test", "java", "com", "diegonmarcos", "superapp", "ui")
def read(name):
    f = os.path.join(test, name)
    return open(f).read() if os.path.exists(f) else ""
harness = read("ShellIslandHarness.kt")
if "ShellBottomNav.configure(" not in harness:
    p.append("the test harness does not configure the island through ShellBottomNav - it would measure a nav the shell never shows")
for name in ("BottomNavGeometryTest.kt", "BottomNavSelectedPillTest.kt"):
    t = read(name)
    if "@Test" not in t or ": ShellIslandHarness()" not in t:
        p.append("%s is missing or does not measure the shell's configured island" % name)
print("; ".join(p) or "OK")
PY
)" "geometry + pill tests exist on the configured island and CI is told to run them"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
