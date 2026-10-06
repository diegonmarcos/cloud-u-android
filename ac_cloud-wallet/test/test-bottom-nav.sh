#!/usr/bin/env bash
# Tester (#531/#533): Cloud Wallet's top-level tabs are the fleet's bottom nav,
# at the BOTTOM, and the old top strip is gone. Structure only: order, pill,
# Me-launch routing and clearance are MEASURED by libs:wallet's
# WalletBottomNavTest (CI tests.unit).
set -u
APP="${WALLET_APP:-$(cd "$(dirname "$0")/.." && pwd)}"
LIB="${WALLET_LIB:-$APP/../ab_cloud-libs-shared/libs/wallet}"
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

echo "== T5: (#868) the bar and the Events strip are build.json::ui, read through NavDecl, not listed in Kotlin =="
check "$(python3 - "$APP" "$LIB" <<'PY'
import json, os, re, sys
app, lib = sys.argv[1:3]
ui = json.load(open(os.path.join(app, 'build.json')))['ui']
kt = os.path.join(lib, 'src/main/java/com/diegonmarcos/superapp/wallet')
tabs = open(os.path.join(kt, 'WalletTabsUi.kt')).read()
host = open(os.path.join(kt, 'WalletHost.kt')).read()
frag = open(os.path.join(kt, 'WalletFragment.kt')).read()
act = open(os.path.join(app, 'app/src/main/java/com/diegonmarcos/cloudwallet/MainActivity.kt')).read()
gradle = open(os.path.join(app, 'app/build.gradle')).read()
p = []
secs = {s['id']: s for s in ui.get('sections', [])}
bar = ui.get('bottom_nav', [])
if bar != ['ids', 'pay', 'me', 'vcards', 'events']:
    p.append('ui.bottom_nav is %r, not IDs/Pay/Me/Vcards/Events' % bar)
for b in bar:
    if b not in secs: p.append('bottom_nav id %s is not a ui.sections id' % b)
if ui.get('default_section') not in bar: p.append('ui.default_section is not on the bar')
if secs.get('me', {}).get('pages'): p.append('Me LAUNCHES cloud-me; it must have no pages')
# every wallet tab names its section, every section a tab; every Events page a sub-tab
tab_sections = re.findall(r'^    \w+\("[^"]+", "(\w+)"\)', tabs.split('enum class TicketsSubTab')[0], re.M)
for sid in tab_sections:
    if sid not in secs: p.append('WalletTab names section %s, which ui.sections does not declare' % sid)
for sid in secs:
    if sid != 'me' and sid not in tab_sections: p.append('ui.sections %s has no WalletTab' % sid)
sub = re.findall(r'^    \w+\("[^"]+", "(\w+)"\)', tabs.split('enum class TicketsSubTab')[1].split('}')[0], re.M)
if [x['id'] for x in secs.get('events', {}).get('pages', [])] != sub:
    p.append('ui.sections[events].pages %r != TicketsSubTab pages %r' % ([x['id'] for x in secs.get('events', {}).get('pages', [])], sub))
if re.search(r'enum class WalletNavItem', tabs): p.append('WalletNavItem is back: the bar is a Kotlin list again')
if 'val entries = nav.islandEntries {' not in tabs: p.append('WalletBottomNav does not feed the island from NavDecl.islandEntries')
if 'val nav: NavDecl' not in host or 'fun PageStrip(' not in host: p.append('WalletHost lost nav / PageStrip')
if '(ctx as? WalletHost)?.nav' not in frag or 'nav.default()' not in frag: p.append('WalletScreen does not read the host NavDecl and its default section')
if 'NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)' not in act: p.append('MainActivity does not bake the NavDecl from BuildConfig')
if not re.search(r'override fun PageStrip\(.*?\)\s*\{\s*PageTabs\(', act, re.S): p.append('MainActivity.PageStrip does not draw PageTabs')
for f in ('UI_BOTTOM_NAV', 'UI_SECTIONS_B64', 'UI_DEFAULT_SECTION'):
    if f not in gradle: p.append('app/build.gradle does not bake %s' % f)
if "project(':libs:bottomnav')" not in gradle: p.append('app/build.gradle does not link libs:bottomnav')
print('; '.join(p) or 'OK')
PY
)" "ui.bottom_nav/sections/default_section declared, WalletTab/TicketsSubTab map them, the host bakes NavDecl and draws PageTabs"

# T6: each break, planted on a copy, must turn T1-T5 red (a check that cannot fail is not a check).
if [ -z "${WALLET_MUT:-}" ]; then
  echo "== T6: mutations =="
  TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
  K=ab_cloud-libs-shared/libs/wallet/src/main/java/com/diegonmarcos/superapp/wallet
  mutate() {  # mutate <label> <path under the copy root> <old> <new>
    local d="$TMP/m"; rm -rf "$d"; mkdir -p "$d/ac_cloud-wallet" "$d/ab_cloud-libs-shared/libs"
    cp -r "$APP/app" "$APP/build.json" "$d/ac_cloud-wallet/"; cp -r "$LIB" "$d/ab_cloud-libs-shared/libs/wallet"
    python3 - "$d/$2" "$3" "$4" <<'PY' || { bad "mutation not applied: $1"; return; }
import sys
p, old, new = sys.argv[1:4]
s = open(p, encoding='utf-8').read()
if old not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
    if WALLET_MUT=1 WALLET_APP="$d/ac_cloud-wallet" WALLET_LIB="$d/ab_cloud-libs-shared/libs/wallet" bash "$0" >/dev/null 2>&1; then bad "mutation survived: $1"; else ok "mutation caught: $1"; fi
  }
  mutate "a bar item leaves ui.bottom_nav" ac_cloud-wallet/build.json '"bottom_nav": ["ids", "pay", "me", "vcards", "events"]' '"bottom_nav": ["ids", "pay", "me", "vcards"]'
  mutate "the default section leaves the bar" ac_cloud-wallet/build.json '"default_section": "pay"' '"default_section": "config"'
  mutate "an Events page is dropped from the declaration" ac_cloud-wallet/build.json '{"id": "passes", "label": "Passes", "icon": ""},' ''
  mutate "Me grows pages" ac_cloud-wallet/build.json '{"id": "me", "label": "Me", "icon": "me", "pages": []}' '{"id": "me", "label": "Me", "icon": "me", "pages": [{"id": "x", "label": "X"}]}'
  mutate "the bar is a Kotlin enum again" "$K/WalletTabsUi.kt" 'internal fun walletTabOf(' 'internal enum class WalletNavItem { IDs }
internal fun walletTabOf('
  mutate "the island stops reading NavDecl" "$K/WalletTabsUi.kt" 'val entries = nav.islandEntries' 'val entries = emptyList<Nothing>().islandEntries'
  mutate "the sub-tab page id drifts" "$K/WalletTabsUi.kt" 'Calendar("Cal", "cal")' 'Calendar("Cal", "calendar")'
  mutate "the host loses PageStrip" "$K/WalletHost.kt" 'fun PageStrip(' 'fun PageStripx('
  mutate "the screen ignores the host NavDecl" "$K/WalletFragment.kt" '(ctx as? WalletHost)?.nav' 'null'
  mutate "the host stops baking NavDecl" ac_cloud-wallet/app/src/main/java/com/diegonmarcos/cloudwallet/MainActivity.kt 'NavDecl.fromBuildConfig(' 'NavDeclx.fromBuildConfig('
  mutate "the host draws its own strip" ac_cloud-wallet/app/src/main/java/com/diegonmarcos/cloudwallet/MainActivity.kt 'PageTabs(pages = pages' 'Text(pages = pages'
  mutate "gradle stops baking the sections" ac_cloud-wallet/app/build.gradle '"UI_SECTIONS_B64"' '"UI_SECTIONS"'
  mutate "the screen never draws the bar" "$K/WalletFragment.kt" 'WalletBottomNav(' 'WalletBottomNavx('
fi

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
