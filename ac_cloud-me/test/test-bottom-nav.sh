#!/usr/bin/env bash
# Tester (#531): Cloud Me's bottom bar is libs:bottomnav's Compose island, the
# stock Material BottomNavigationView is gone, and the bar reads
# Buro | Projects | Profile | Agenda | Wallet (#504). Structure only: the
# rendered order, pill, tap routing and clearance are MEASURED on the real
# layout by app/src/test/.../MeBottomNavTest (CI tests.unit).
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$APP/app/src/main"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2: $1"; fi; }

echo "== T1: activity_main hosts the shared island as bottom_nav =="
check "$(python3 - "$MAIN/res/layout/activity_main.xml" <<'PY'
import re, sys
t = open(sys.argv[1]).read()
m = re.search(r'<([A-Za-z0-9_.]+)\s+android:id="@\+id/bottom_nav"', t)
print('OK' if m and m.group(1) == 'com.diegonmarcos.superapp.bottomnav.BottomNavIslandView'
      else 'bottom_nav is %s, not libs:bottomnav BottomNavIslandView' % (m.group(1) if m else 'missing'))
PY
)" "bottom_nav is com.diegonmarcos.superapp.bottomnav.BottomNavIslandView"

echo "== T2: no Material bottom-navigation class in any source or layout =="
check "$(python3 - "$MAIN" <<'PY'
import os, sys
hits = []
for root, _, files in os.walk(sys.argv[1]):
    for f in files:
        if not f.endswith(('.kt', '.java', '.xml')): continue
        p = os.path.join(root, f)
        for n, line in enumerate(open(p, encoding='utf-8', errors='replace'), 1):
            s = line.strip()
            if s.startswith(('//', '*', '/*', '<!--')): continue
            if 'com.google.android.material.bottomnavigation' in s:
                hits.append('%s:%d' % (os.path.relpath(p, sys.argv[1]), n))
print('; '.join(hits) or 'OK')
PY
)" "the stock BottomNavigationView is gone"

echo "== T3: the bar sections, in order, are Buro | Projects | Profile | Agenda | Wallet (#504) =="
check "$(python3 - "$APP/build.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
bar = sorted((s for s in d['ui']['sections'] if s.get('bottom_nav')), key=lambda s: s.get('order', 0))[:5]
got = [s['label'] for s in bar]
want = ['Buro', 'Projects', 'Profile', 'Agenda', 'Wallet']
p = []
if got != want:
    p.append('the bar reads %s, #504 asked for %s' % (' | '.join(got), ' | '.join(want)))
mod = d.get('modules', {}).get('libs:bottomnav', {})
if mod.get('dir') != '../ab_cloud-libs-shared/libs/bottomnav':
    p.append('modules.libs:bottomnav does not point at the ONE shared module')
print('; '.join(p) or 'OK')
PY
)" "build.json bar order is the #504 order and libs:bottomnav is linked by reference"

echo "== T4: the measured proof exists and CI runs it =="
check "$(python3 - "$APP" <<'PY'
import json, os, sys
app = sys.argv[1]
p = []
unit = json.load(open(os.path.join(app, 'build.json'))).get('tests', {}).get('unit', {})
if not unit.get('task') or unit.get('enabled') is False:
    p.append('build.json::tests.unit is off - MeBottomNavTest never runs in CI')
f = os.path.join(app, 'app/src/test/java/com/diegonmarcos/cloudme/MeBottomNavTest.kt')
t = open(f).read() if os.path.exists(f) else ''
if '@Test' not in t or 'MeBottomNav.configure(' not in t or 'R.layout.activity_main' not in t:
    p.append('MeBottomNavTest is missing or does not measure the real layout configured by MeBottomNav')
print('; '.join(p) or 'OK')
PY
)" "MeBottomNavTest measures the real layout and tests.unit runs it"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
