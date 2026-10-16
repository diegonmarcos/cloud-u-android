#!/usr/bin/env bash
# ac_cloud-notes tester: the fleet's top bar, and ONE source for the top inset.
#
# Cloud Notes adopts libs:bottomnav's AppTopBar (declared in build.json::ui.top_bar).
# It is a View/WebView shell, so the bar lives in a ComposeView over the editor and
# three halves must agree, none of which a compiler can see across:
#
#   declaration  build.json::ui.top_bar + modules(libs:ai) <-> app/build.gradle bakes
#                UI_TOP_BAR_B64 / AI_GATEWAY_URL and links :libs:ai
#   wiring       MainActivity builds the bar from the baked declaration; NativeTopBar
#                calls AppTopBarScaffold with the Account launcher, the AI panel and a
#                Configs handler that asks the web half to open AFFiNE's settings
#   bridge       the Kotlin event names / host object == the TypeScript ones
#   insets       the double top gap (strip height + WebView margin + web status-bar
#                padding) cannot come back: ONE Kotlin writer of the WebView's top
#                margin (NativeNav.applyWebMargin, fed by the bar's reported height),
#                and the web half zeroes --safe-area-inset-top when native owns the bar
#
# python3 only; paths below are the only constants.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import json, os, re, sys
root = sys.argv[1]
fails = 0
def check(ok, msg):
    global fails
    print(('  PASS  ' if ok else '  FAIL  ') + msg)
    if not ok: fails += 1
rd = lambda *p: open(os.path.join(root, *p), encoding='utf-8').read()

APP = 'packages/frontend/apps/android/App/app'
KT = APP + '/src/main/java/app/affine/pro'
WEB = 'packages/frontend/core/src/mobile/components'
build = json.loads(rd('build.json'))
ui = build.get('ui') or {}
bar = ui.get('top_bar') or {}
gradle = rd(APP, 'build.gradle')
main = rd(KT, 'MainActivity.kt')
top = rd(KT, 'NativeTopBar.kt')
nav = rd(KT, 'NativeNav.kt')
routes = rd(WEB, 'native-nav-routes.ts')
bridge = rd(WEB, 'native-nav-bridge.tsx')
setup = rd('packages/frontend/apps/android/src/setup.ts')

# declaration
check(bar.get('drawer') is True and bar.get('ai') is True, 'ui.top_bar declares the drawer and the AI panel')
check(bar.get('settings') == ['account', 'configs'], 'ui.top_bar.settings is [account, configs]')
mods = build.get('modules') or {}
check('libs:bottomnav' in mods and 'libs:ai' in mods and 'libs:model-catalogue' in mods,
      'build.json::modules links libs:bottomnav, libs:ai and its api dependency libs:model-catalogue')
check('"UI_TOP_BAR_B64"' in gradle and 'uiTopBarB64' in gradle, 'app/build.gradle bakes UI_TOP_BAR_B64 from ui.top_bar')
check('"AI_GATEWAY_URL"' in gradle and 'ai-gateway.gradle' in gradle, 'app/build.gradle bakes AI_GATEWAY_URL via the shared ai-gateway.gradle')
check("project(':libs:ai')" in gradle, 'app/build.gradle implements :libs:ai')
check(re.search(r"ext\.aiFleetRoot\s*=", gradle) is not None, 'the nested gradle root names the repository root for ai-gateway.gradle')

# wiring
check('TopBarDecl.fromBuildConfig(BuildConfig.UI_TOP_BAR_B64)' in main, 'MainActivity builds the bar from the baked declaration')
check('addJavascriptInterface(' in main and '"CloudNotesHost"' in main, 'MainActivity registers the CloudNotesHost bridge object')
check('AppTopBarScaffold(' in top and 'rememberAccountLauncher()' in top, 'NativeTopBar draws AppTopBarScaffold with the Account launcher')
check('AiFleetChatPanel(' in top, 'the AI panel is libs:ai\'s shared chat')
check('onConfigs = { run(NativeNavScript.openSettings()) }' in top, 'Configs asks the web half to open AFFiNE settings')
check('TopBarDrawer {' in top, 'the drawer is filled through TopBarDrawer')

# bridge
for const, kt in (('NATIVE_NAV_EVENT', 'EVENT'), ('NATIVE_OPEN_SETTINGS_EVENT', 'OPEN_SETTINGS_EVENT'), ('NATIVE_OPEN_DOC_EVENT', 'OPEN_DOC_EVENT')):
    ts = re.search(const + r"\s*=\s*'([^']+)'", routes)
    k = re.search(r'const val ' + kt + r' = "([^"]+)"', nav)
    check(bool(ts and k and ts.group(1) == k.group(1)), '%s: web and Kotlin use the same event name (%s)' % (const, ts.group(1) if ts else '?'))
check('CloudNotesHost' in bridge and 'setDocs' in bridge, 'the web half pushes the page list to CloudNotesHost.setDocs')
check(re.search(r'@JavascriptInterface\s+fun setDocs\(', top) is not None, 'Kotlin exposes setDocs as a @JavascriptInterface')

# insets: one source
margin_writers = []
for dp, _, fs in os.walk(os.path.join(root, KT)):
    for f in fs:
        if f.endswith('.kt') and re.search(r'\.topMargin\s*=', open(os.path.join(dp, f), encoding='utf-8').read()):
            margin_writers.append(f)
check(margin_writers == ['NativeNav.kt'], 'only NativeNav writes a top margin (found: %s)' % margin_writers)
check(len(re.findall(r'\.topMargin\s*=', nav)) == 2, 'NativeNav writes exactly two top margins: the strip below the bar, the editor and the Ask page below the strip')
check('topInset + stripHeight' in nav, 'the WebView starts at bar height + strip height, nothing else')
check('statusBarsPadding()' in top, 'the bar owns the status bar inset')
check('--safe-area-inset-top:0px !important' in setup, 'the web half zeroes the status-bar safe area once native owns it')
check(re.search(r'cloudNotesNativeNav\s*=\s*\n?\s*true', setup) is not None, 'the zeroing sits with the native-nav flag')

# Search > Ask is a page the shell draws itself (libs:ai), never a toast
rows = dict(re.findall(r"'([\w-]+/[\w-]+)':\s*(\{[^}]*\})", routes))
web_native = {k for k, v in rows.items() if "kind: 'native'" in v}
kt_native = set(re.findall(r'"([\w-]+/[\w-]+)"', re.search(r'NATIVE_PAGES[^=]*=\s*setOf\(([^)]*)\)', nav).group(1)))
check(web_native == kt_native and bool(kt_native), 'native pages: web rows %s == Kotlin NATIVE_PAGES %s' % (sorted(web_native), sorted(kt_native)))
check(not re.search(r"kind: 'planned'|notify\.", bridge + routes), 'the web nav bridge has no planned-toast path left')
check('AiFleetChatPanel(gatewayUrl)' in nav and 'ask.visibility' in nav, 'NativeNav shows libs:ai\'s chat for the native Ask page')
check("'graph/global': { kind: 'route'" in routes and "'graph/local': { kind: 'route'" in routes, 'Graph global and local are routes')

print('RESULT: %s' % ('RED' if fails else 'GREEN'))
raise SystemExit(1 if fails else 0)
PY
