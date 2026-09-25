#!/usr/bin/env bash
# Tester (#531/#533): Cloud Wallet's top-level tabs are the fleet's bottom nav,
# at the BOTTOM, and the old top strip is gone. Structure only: order, pill,
# Me-launch routing and clearance are MEASURED by libs:wallet's
# WalletBottomNavTest (CI tests.unit).
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
LIB="$APP/../ab_cloud-libs-shared/libs/wallet"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2: $1"; fi; }

echo "== T1: the top strip is gone and the island is drawn under the content =="
check "$(python3 - "$LIB/src/main/java/com/diegonmarcos/superapp/wallet" <<'PY'
import os, re, sys
src = {f: open(os.path.join(sys.argv[1], f)).read() for f in os.listdir(sys.argv[1]) if f.endswith('.kt')}
p = []
for f, t in src.items():
    if re.search(r'\bWalletTabStrip\b', t):
        p.append('%s still names WalletTabStrip - the top strip is back' % f)
ui = src.get('WalletTabsUi.kt', '')
if 'BottomNavIsland(' not in ui:
    p.append('WalletBottomNav does not draw libs:bottomnav BottomNavIsland')
frag = src.get('WalletFragment.kt', '')
nav, content = frag.find('WalletBottomNav('), frag.find('Modifier.fillMaxWidth().weight(1f)')
if nav < 0:
    p.append('WalletScreen never draws WalletBottomNav')
elif content < 0 or nav < content:
    p.append('WalletBottomNav is drawn BEFORE the content box - the tabs are at the top again')
print('; '.join(p) or 'OK')
PY
)" "no WalletTabStrip; WalletBottomNav draws the shared island after (below) the content"

echo "== T2: the shared module is linked by reference =="
check "$(python3 - "$APP/build.json" "$LIB/build.gradle" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
g = open(sys.argv[2]).read()
p = []
if d.get('modules', {}).get('libs:bottomnav', {}).get('dir') != '../ab_cloud-libs-shared/libs/bottomnav':
    p.append('build.json modules.libs:bottomnav does not point at the ONE shared module')
if "project(':libs:bottomnav')" not in g:
    p.append("libs:wallet does not link project(':libs:bottomnav')")
print('; '.join(p) or 'OK')
PY
)" "libs:bottomnav declared in build.json and linked by libs:wallet"

echo "== T3: the measured proof exists and CI runs it =="
check "$(python3 - "$APP/build.json" "$LIB/src/test/java/com/diegonmarcos/superapp/wallet/WalletBottomNavTest.kt" <<'PY'
import json, os, sys
unit = json.load(open(sys.argv[1])).get('tests', {}).get('unit', {})
p = []
if unit.get('task') != ':libs:wallet:testDebugUnitTest' or unit.get('enabled') is False:
    p.append('tests.unit does not run :libs:wallet:testDebugUnitTest')
t = open(sys.argv[2]).read() if os.path.exists(sys.argv[2]) else ''
if '@Test' not in t or 'WalletBottomNav(' not in t:
    p.append('WalletBottomNavTest is missing or does not render WalletBottomNav')
print('; '.join(p) or 'OK')
PY
)" "WalletBottomNavTest renders the real bar and tests.unit runs it"

echo "== T4: the shared bar's scroll-collapse (#532) is wired from the content into the bar =="
check "$(python3 - "$LIB/src/main/java/com/diegonmarcos/superapp/wallet" <<'PY'
import os, re, sys
d = sys.argv[1]
frag = open(os.path.join(d, 'WalletFragment.kt')).read()
ui = open(os.path.join(d, 'WalletTabsUi.kt')).read()
p = []
if not re.search(r'weight\(1f\)\.nestedScroll\(collapse\)', frag):
    p.append('the content box does not hang nestedScroll(collapse) - scrolling never reaches the bar')
if not re.search(r'WalletBottomNav\((?:(?!\n {16}\)).)*collapsed\s*=\s*collapse\.collapsed', frag, re.S):
    p.append('WalletScreen does not pass collapse.collapsed to WalletBottomNav')
nav = ui[ui.find('fun WalletBottomNav('):]
if not re.search(r'BottomNavIsland\((?:(?!\n {8}\)).)*collapsed\s*=\s*collapsed', nav, re.S):
    p.append('WalletBottomNav does not forward collapsed to BottomNavIsland')
print('; '.join(p) or 'OK')
PY
)" "content scroll -> BottomNavCollapse -> WalletBottomNav -> BottomNavIsland"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
