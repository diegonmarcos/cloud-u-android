#!/usr/bin/env bash
# Cloud Store > Settings > Configs is a PAGE with its own top tabs: Sources | ADB Shell | AI | Notifications | About.
#
# Held statically (no build, no device, no network):
#  C1 build.json::ui declares them: the `settings` section's pages are exactly those five, in that order, flat (no sub
#     strip), Settings is not an island item and the top bar's Settings menu still offers Configs (nav-shape N1/N5).
#  C2 ConfigsPage.kt opens one body per declared page id (a page the declaration names but the host cannot open, or a body
#     nobody declares, turns this red) and MainActivity hosts it under the declared strip (PageTabs), not the old single screen.
#  C3 each page is what it is called: Sources lists the Store's feeds (switches honoured by the Feed page) + the install
#     ladder + the fleet token; ADB Shell is THE shared page under this app's own identity; AI is libs:ai's pickers over the
#     fleet gateway with no token field; Notifications is the kinds x channels matrix; About shows version, build and licence.
#  C4 Notifications is real: the three kinds the owner named, and the posters (install result, pass summary, tap-to-install)
#     ask StoreNotifyPrefs.
#  C5 density: Configs reads StoreDensity, holds no dp/sp literal, and runs under KitDenseMaterial.
#  C6 the suites exist and run through this app (storeUnitTests -> bottomnav ConfigsMenuTest, adb AdbIdentityTest, core StoreNotifyPrefsTest).
# Then each mutation is planted in a scratch copy and must turn a check red.
#
# Usage: ./test-store-configs-pages.sh   (static, no network)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
command -v python3 >/dev/null || { echo "python3 required"; exit 1; }

check() {  # check <root> -> PASS/FAIL lines, exit = #fails
python3 - "$1" <<'PY'
import json, re, sys
R = sys.argv[1]
def rd(p):
    s = open(f"{R}/{p}", encoding="utf-8").read()
    s = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), s, flags=re.S)
    return re.sub(r'(?m)^\s*//.*$|(?<=[\s;{}])//[^\n]*', '', s)
fails = 0
def ok(c, good, bad):
    global fails
    print(("  PASS: " if c else "  FAIL: ") + (good if c else bad))
    fails += 0 if c else 1
K = "ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/"
LIBS = "ab_cloud-libs-shared/libs/"
PAGES = ["sources", "adb", "ai", "notifications", "about"]
LABELS = ["Sources", "ADB Shell", "AI", "Notifications", "About"]

# C1
bj = json.load(open(f"{R}/ac_cloud-store/build.json"))
ui = bj["ui"]
sec = {s["id"]: s for s in ui["sections"]}
pg = sec.get("settings", {}).get("pages", [])
ok([p["id"] for p in pg] == PAGES and [p["label"] for p in pg] == LABELS,
   "ui.sections settings declares Sources | ADB Shell | AI | Notifications | About, in order",
   f"the settings section's pages are {[p.get('id') for p in pg]}, not {PAGES}")
ok(all(not p.get("pages") and not p.get("action") for p in pg), "the five are flat destinations (no sub strip, no launch tab)", "a Configs page is a sub-strip or a launch tab")
ok("settings" not in ui["bottom_nav"] and "configs" in ui["top_bar"]["settings"], "Settings stays off the island and the top bar's Settings menu offers Configs", "Configs moved onto the island or left the top bar's menu")

# C2
cp = rd(K + "ConfigsPage.kt")
ma = rd(K + "MainActivity.kt")
consts = dict(re.findall(r'const val (\w+) = "(\w+)"', cp))
ok(sorted(consts.values()) == sorted(PAGES), f"ConfigsPages names exactly the declared ids ({sorted(consts.values())})", f"ConfigsPages constants {sorted(consts.values())} differ from the declared pages")
when = re.search(r'when \(page\) \{(.*?)\n        \}', cp, re.S)
arms = re.findall(r'ConfigsPages\.(\w+) ->', when.group(1)) if when else []
ok(sorted(arms + ["SOURCES"]) == sorted(consts) and "else -> SourcesPage()" in cp, "ConfigsPage opens one body per declared page (Sources is the default)", f"ConfigsPage's when() opens {arms}, not every declared page")
ok("if (selected == TAB_SETTINGS) section?.let { ConfigsPage(pageOf(it)) }" in ma and "NavSectionLayout(section, pageOf(section)" in ma and "SettingsPage" not in ma,
   "MainActivity hosts ConfigsPage for the open page under the declared PageTabs strip", "MainActivity does not host ConfigsPage under the declared strip (or still draws the old SettingsPage)")

# C3
ok("FeedViewer.feeds(ctx)" in cp and "StoreSourcePrefs.set(ctx, f.id, it)" in cp and "PhoneAppActions.resolver(" in cp and "FleetBearer.Own(" in cp,
   "Sources lists the Store's feeds with switches, the install ladder and the fleet token", "the Sources page is not built from the Store's own declared feeds / ladder / token")
fp = rd(K + "FeedPage.kt")
ok("StoreSourcePrefs.enabled(ctx, feed.id)" in fp, "a feed switched off in Sources is not read by the Feed page", "the Feed page ignores the Sources switches")
adbp = rd(LIBS + "shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug/AdbShellPage.kt")
ok("AdbShellHostedPage(" in cp and "AdbIdentity.host(ctx.packageName)" in adbp, "ADB Shell is the shared page (the one SuperApp hosts too), under this app's own identity", "the ADB Shell page is not the shared page / does not show this app's identity")
ok("val own = page != ConfigsPages.ADB" in cp and "Modifier.verticalScroll(rememberScrollState())" in cp, "the shared page is hosted bare (it scrolls itself), every other page in this host's scrolling column", "the ADB Shell page is nested in a second scroll, or another page lost its scroll")
ok("AiSettingsPickers(" in cp and "AiFleetChat.chat(ctx, BuildConfig.AI_GATEWAY_URL)" in cp and "OutlinedTextField" not in cp.split("private fun AiPage")[1].split("// ── Notifications")[0],
   "AI is libs:ai's agent/model pickers over the fleet gateway, with no token field", "the AI page lacks the shared pickers or carries a token field")
ok("StoreNotifyPrefs.Kind.values()" in cp and "StoreNotifyPrefs.Channel.values()" in cp and "StoreNotifyPrefs.set(ctx, kind, ch, it)" in cp,
   "Notifications is the kinds x channels matrix", "the Notifications page is not the kinds x channels matrix")
ok("versionName" in cp and "BuildConfig.GIT_SHORT_SHA" in cp and "BuildConfig.BUILD_TIMESTAMP" in cp and "LICENSING.md" in cp,
   "About shows version, build and the licence / attribution pointer", "About lacks version, build or licence")

# C5 the chips open the page in the Configs frame (MainActivity is the lib's AdbShellHost), never a second screen
ok("AdbShellHost" in ma and "override fun openAdbShellInFrame(): Boolean" in ma and "choose(TAB_SETTINGS, ConfigsPages.ADB)" in ma and "requested = TAB_SETTINGS" in ma,
   "MainActivity hosts the ADB Shell page in Configs for every chip that opens it", "MainActivity does not open Configs > ADB Shell for the Wireless debugging chip")
ok("ConfigsAdbShellTest" in open(f"{R}/ac_cloud-store/app/src/test/java/com/diegonmarcos/cloudstore/ConfigsAdbShellTest.kt", encoding="utf-8").read(),
   "the in-frame opening has its own suite (ConfigsAdbShellTest)", "ConfigsAdbShellTest is missing")

# C4
npf = rd(LIBS + "core/src/main/java/com/diegonmarcos/superapp/core/StoreNotifyPrefs.kt")
ids = re.findall(r'^\s+\w+\("([a-z-]+)", "', npf.split("enum class Channel")[0], re.M)
ok(ids == ["update-available", "install-done", "failures"], "the kinds are update available, install done, failures", f"notification kinds are {ids}")
ok("StoreNotifyGate.mayPost(ctx) && allows(" in npf, "a switch only narrows the gate: the SuperApp stays closed whatever is stored", "StoreNotifyPrefs.allows does not go through StoreNotifyGate first")
pir = rd(LIBS + "updater/src/main/java/com/diegonmarcos/superapp/updater/PackageInstallerReceiver.kt")
pl = rd(LIBS + "updater/src/main/java/com/diegonmarcos/superapp/updater/PassLedger.kt")
ok(pir.count("StoreNotifyPrefs.allows(") >= 3 and "StoreNotifyPrefs.allows(ctx, kind, StoreNotifyPrefs.Channel.PUSH)" in pl,
   "install results, tap-to-install and the pass summary ask StoreNotifyPrefs", "a Store poster does not ask StoreNotifyPrefs")

# C5
ok("StoreDensity." in cp and not re.search(r'[1-9][0-9.]*\.(dp|sp)\b', cp), "Configs reads StoreDensity and holds no dp/sp literal", "Configs holds its own dp/sp literal or ignores StoreDensity")
ok("KitDenseMaterial(" in ma, "the app (Configs included) runs under KitDenseMaterial", "MainActivity no longer wraps the app in KitDenseMaterial")

# C6
gr = open(f"{R}/ac_cloud-store/build.gradle", encoding="utf-8").read()
ok("'storeUnitTests'" in gr and all(t in gr for t in (":libs:bottomnav:testDebugUnitTest", ":libs:shizuku-adb-debug-tools:testDebugUnitTest", ":libs:core:testDebugUnitTest")) and bj["tests"]["unit"]["task"] == "storeUnitTests",
   "tests.unit runs storeUnitTests (bottomnav + adb lib + core)", "tests.unit does not run the bottomnav, adb and core suites")
t1 = rd(LIBS + "bottomnav/src/test/kotlin/com/diegonmarcos/superapp/bottomnav/ConfigsMenuTest.kt")
ok("ac_cloud-store/build.json" in t1 and "a tap on each tab opens that page and only that page" in t1 and "performClick()" in t1, "ConfigsMenuTest drives the declared tabs from the Store's build.json", "ConfigsMenuTest is missing or no longer reads the declaration")
t2 = rd(LIBS + "shizuku-adb-debug-tools/src/test/java/com/diegonmarcos/superapp/adbdebug/AdbIdentityTest.kt")
ok("theStorePresentsAsCloudStoreAndNotAsTheSuperApp" in t2 and "stateIsScopedToThePackageItWasMeasuredFor" in t2, "AdbIdentityTest holds identity per host app and the state scope", "AdbIdentityTest lost the per-app identity / scope cases")
sys.exit(fails)
PY
}

echo "-- real tree --"
check "$ROOT"; REAL=$?

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
L=ab_cloud-libs-shared/libs
FILES="ac_cloud-store/build.json ac_cloud-store/build.gradle
ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/ConfigsPage.kt
ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/MainActivity.kt
ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/FeedPage.kt
$L/shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug/AdbShellPage.kt
ac_cloud-store/app/src/test/java/com/diegonmarcos/cloudstore/ConfigsAdbShellTest.kt
$L/core/src/main/java/com/diegonmarcos/superapp/core/StoreNotifyPrefs.kt
$L/updater/src/main/java/com/diegonmarcos/superapp/updater/PackageInstallerReceiver.kt
$L/updater/src/main/java/com/diegonmarcos/superapp/updater/PassLedger.kt
$L/bottomnav/src/test/kotlin/com/diegonmarcos/superapp/bottomnav/ConfigsMenuTest.kt
$L/shizuku-adb-debug-tools/src/test/java/com/diegonmarcos/superapp/adbdebug/AdbIdentityTest.kt"
mutate() {  # mutate <label> <file> <old> <new>
  local label="$1" f="$2" old="$3" new="$4" d="$TMP/m"
  rm -rf "$d"; mkdir -p "$d"
  for x in $FILES; do mkdir -p "$d/$(dirname "$x")"; cp "$ROOT/$x" "$d/$x"; done
  python3 - "$d/$f" "$old" "$new" <<'PY' || { echo "  MUTATION NOT APPLIED: $label"; return 1; }
import sys
p, old, new = sys.argv[1:4]
s = open(p, encoding='utf-8').read()
if old not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
  if check "$d" >/dev/null; then echo "  MUTATION SURVIVED: $label"; return 1; fi
  echo "  mutation caught: $label"; return 0
}
S=ac_cloud-store; K=ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore
M=0
mutate "a page dropped from the declaration" $S/build.json '"id": "about",' '"id": "abouty",' || M=$((M+1))
mutate "pages reordered" $S/build.json '"id": "sources",
            "label": "Sources"' '"id": "sources",
            "label": "Origins"' || M=$((M+1))
mutate "Settings back on the island" $S/build.json '"access"
    ],
    "top_bar"' '"access",
      "settings"
    ],
    "top_bar"' || M=$((M+1))
mutate "Configs loses the About body" $K/ConfigsPage.kt 'ConfigsPages.ABOUT -> AboutPage()' '' || M=$((M+1))
mutate "an undeclared page body" $K/ConfigsPage.kt 'const val ABOUT = "about"' 'const val ABOUT = "about"
    const val EXTRA = "extra"' || M=$((M+1))
mutate "MainActivity back to the single screen" $K/MainActivity.kt 'section?.let { ConfigsPage(pageOf(it)) }' 'SettingsPage()' || M=$((M+1))
mutate "Sources forgets the feeds" $K/ConfigsPage.kt 'FeedViewer.feeds(ctx)' 'emptyList<FeedViewer.Feed>()' || M=$((M+1))
mutate "the Feed page ignores the switch" $K/FeedPage.kt 'StoreSourcePrefs.enabled(ctx, feed.id)' 'true' || M=$((M+1))
mutate "ADB Shell shows no identity" $L/shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug/AdbShellPage.kt 'AdbIdentity.host(ctx.packageName)' 'null' || M=$((M+1))
mutate "ADB Shell nested in a second scroll" $K/ConfigsPage.kt 'val own = page != ConfigsPages.ADB' 'val own = true' || M=$((M+1))
mutate "the chip no longer opens Configs" $K/MainActivity.kt 'choose(TAB_SETTINGS, ConfigsPages.ADB)' '' || M=$((M+1))
mutate "MainActivity is no longer the host" $K/MainActivity.kt 'override fun openAdbShellInFrame(): Boolean {' 'fun openAdbShellInFrameX(): Boolean {' || M=$((M+1))
mutate "AI grows a token field" $K/ConfigsPage.kt 'Head("Gateway")' 'Head("Gateway"); OutlinedTextField(value = "", onValueChange = {})' || M=$((M+1))
mutate "AI loses the shared pickers" $K/ConfigsPage.kt 'AiSettingsPickers(chat, materialAiTheme())' '' || M=$((M+1))
mutate "Notifications keeps one channel" $K/ConfigsPage.kt 'StoreNotifyPrefs.Channel.values().forEach' 'listOf(StoreNotifyPrefs.Channel.PUSH).forEach' || M=$((M+1))
mutate "About loses the licence" $K/ConfigsPage.kt 'LICENSING.md' 'the repository' || M=$((M+1))
mutate "a kind renamed" $L/core/src/main/java/com/diegonmarcos/superapp/core/StoreNotifyPrefs.kt '"install-done"' '"done"' || M=$((M+1))
mutate "the gate is bypassed" $L/core/src/main/java/com/diegonmarcos/superapp/core/StoreNotifyPrefs.kt 'StoreNotifyGate.mayPost(ctx) && allows(' 'allows(' || M=$((M+1))
mutate "install results ignore the switches" $L/updater/src/main/java/com/diegonmarcos/superapp/updater/PackageInstallerReceiver.kt 'val toPush = StoreNotifyPrefs.allows(context, kind, StoreNotifyPrefs.Channel.PUSH)' 'val toPush = true' || M=$((M+1))
mutate "the pass summary ignores the switches" $L/updater/src/main/java/com/diegonmarcos/superapp/updater/PassLedger.kt 'if (!StoreNotifyPrefs.allows(ctx, kind, StoreNotifyPrefs.Channel.PUSH)) return' '' || M=$((M+1))
mutate "a dp literal in Configs" $K/ConfigsPage.kt 'padding(top = StoreDensity.dpValue(StoreDensity.S8).dp)' 'padding(top = 8.dp)' || M=$((M+1))
mutate "dense kit dropped" $K/MainActivity.kt 'KitDenseMaterial(' 'androidx.compose.material3.MaterialTheme(' || M=$((M+1))
mutate "unit task narrowed" $S/build.gradle ':libs:shizuku-adb-debug-tools:testDebugUnitTest' ':libs:core:testDebugUnitTest' || M=$((M+1))
mutate "Configs menu test loses its taps" $L/bottomnav/src/test/kotlin/com/diegonmarcos/superapp/bottomnav/ConfigsMenuTest.kt 'performClick()' 'assertExists()' || M=$((M+1))

echo "== RESULT: real tree $REAL failure(s), $M mutation(s) not caught =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
