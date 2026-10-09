#!/usr/bin/env bash
# test-account-shell-runbook: Cloud Account has its OWN uid-2000 channel and the runbook's shell +
# store steps (account redesign spec 2.4 + 4.6). Static, no build, no network. Checks the real tree,
# then plants each mutation in a scratch copy and requires RED, so a check that cannot fail does
# not count as a check.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

check() {
python3 - "$1" <<'PY'
import json, sys
R = sys.argv[1]
fails = 0
def rd(p): return open(f"{R}/{p}", encoding="utf-8").read()
def ok(c, good, bad):
    global fails
    print(("  ok   " if c else "  FAIL ") + (good if c else bad))
    if not c: fails += 1

bj = json.loads(rd("ac_cloud-account/build.json"))
mods = bj["modules"]
ok("libs:shizuku-adb-debug-tools" in mods.get("app", {}).get("depends_on", []), "app module depends on the shell lib", "app module dropped the shell lib")
ok("libs:shizuku-adb-debug-tools" in mods.get("libs:account", {}).get("depends_on", []), "libs:account depends on the shell lib", "libs:account dropped the shell lib")
prov = bj.get("shizuku_client", {}).get("providers")
ok(prov == ["moe.shizuku.privileged.api"], "shizuku_client = Shizuku only (embedded first, as Cloud Store)", f"shizuku_client providers wrong: {prov}")
port = bj.get("shizuku_diagnostics", {}).get("local_server", {}).get("port")
ok(port not in (None, 38098, 38099), f"own local server port {port}", f"local server port {port} collides (Store 38098 / SuperApp 38099) or is missing")
steps = bj.get("ui", {}).get("account", {}).get("runbook", {}).get("steps", [])
ok(steps == ["connected", "profile", "shell", "store", "apps", "configs", "perms", "verified"], "runbook declares the spec's eight steps in order", f"runbook steps wrong: {steps}")

g = rd("ac_cloud-account/app/build.gradle")
ok("implementation project(':libs:shizuku-adb-debug-tools')" in g, "app links the shell lib", "app does not link the shell lib")
ga = rd("ab_cloud-libs-shared/libs/account/build.gradle")
ok("implementation project(':libs:shizuku-adb-debug-tools')" in ga, "libs:account links the shell lib", "libs:account does not link the shell lib")

mf = rd("ac_cloud-account/app/src/main/AndroidManifest.xml")
ok("com.diegonmarcos.superapp.adbdebug.AdbPairingService" in mf and 'foregroundServiceType="specialUse"' in mf, "pairing service declared (specialUse FGS)", "pairing service not declared")
ok("com.diegonmarcos.superapp.adbdebug.HostShellBootReceiver" in mf and "android.intent.action.BOOT_COMPLETED" in mf and "RECEIVE_BOOT_COMPLETED" in mf, "boot re-arm declared", "boot re-arm missing")
ok("android.permission.WRITE_SECURE_SETTINGS" in mf, "WRITE_SECURE_SETTINGS declared (self-grant target)", "WRITE_SECURE_SETTINGS not declared")

app = rd("ac_cloud-account/app/src/main/java/com/diegonmarcos/cloudaccount/App.kt")
ok("HostShell.install(this)" in app, "App.onCreate arms HostShell", "App.onCreate never arms HostShell")

hs = rd("ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug/HostShell.kt")
ok("android.permission.WRITE_SECURE_SETTINGS" in hs and "AdbPairingService.onConnected = " in hs, "HostShell self-grants after pairing", "HostShell does not self-grant after pairing")

rb = rd("ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/SetupRunbook.kt")
ok("SHELL -> Step(id, { checkShell() }, { runShell() })" in rb and "STORE -> Step(id, { checkStore() })" in rb, "runbook wires shell + store", "runbook does not wire shell/store")
ok('private fun checkStore(): State =\n        storeInfo()?.let { State.Already("Cloud Store ${it.versionName} installed") }' in rb, "store check says ALREADY when Cloud Store is installed", "store check never says ALREADY")
ok('if (ch == null && !allowPrompt)\n            return State.Failed("no shell channel' in rb, "no channel + no prompt -> a named FAILED", "no channel falls through silently")
ok("theirs.intersect(ours).isEmpty()" in rb, "store refuses an APK not signed with the fleet key", "store installs without the signer check")
ok('else -> Step(id, { notHere() }, { notHere() })' in rb, "an unknown step id answers TODO", "an unknown step id is not TODO")
api = rd("ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AccountDebugApi.kt")
ok('"runbook" -> SetupRunbook(ctx)' in api and 'Op("runbook"' in api, "debug API serves /api/account/runbook", "debug API has no runbook op")

# task 5: the remaining steps, Run all, the runbook + apps pages (spec 4.6 / 4.7)
for sid in ("CONNECTED", "PROFILE", "APPS", "CONFIGS", "PERMS", "VERIFIED"):
    ok(f"{sid} -> Step(id," in rb, f"runbook wires {sid.lower()}", f"runbook does not wire {sid.lower()}")
ok("if (st is State.Failed || st is State.Running) { stopped = s.id; break }" in rb, "Run all stops at the first FAILED", "Run all does not stop at FAILED")
ok("if (it.app.pkg == SUPERAPP_PKG) 0 else 1" in rb, "configs pushes SuperApp first", "configs does not order SuperApp first")
ok("StoreImport.EXTRA_IMPORT" in rb, "apps hands the inventory over EXTRA_IMPORT", "apps does not use the Store hand-off")
ok("PermsPlan.plan(ctx, prof, ch)" in rb and "PermsPlan.apply(ctx, plan, ch)" in rb, "perms step runs PermsPlan over the channel", "perms step does not use PermsPlan")
dr = rb.split("private fun drift")[1].split("private fun checkConfigs")[0]
ok("i.value" not in dr.replace("FleetSetup.same(back.opt(i.key), i.value)", ""), "drift lines carry key names only", "drift line prints a value")
ok(' need a source"' in rb, "apps line counts what needs a source", "apps line lost its need-a-source count")
pg = rd("ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/RunbookPage.kt")
ra = pg.split("RunbookTags.RUN_ALL")[1].split("KitCard")[0] if "RunbookTags.RUN_ALL" in pg else ""
ok("rb.plan()" in ra and "runAll" not in ra, "Run all opens the plan sheet first", "Run all acts without the plan sheet")
ok("rb.runAll" in pg.split("RunbookTags.SHEET_GO")[-1], "Run all acts only from the sheet's confirm", "Run all is not behind the sheet")
ok(not any(x in pg for x in (".value", "\"value\"", "workingProfile", "settings")), "runbook rows print state + detail only", "runbook page prints a value")
ap = rd("ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AppsPage.kt")
ok("\"no source declared: add it to the source map\"" in ap and "play" not in ap.lower().replace("display", ""), "apps page says 'no source declared'", "apps page lost 'no source declared'")
ok(not any(x in ap for x in (".intent", "startActivity", "storePage", "play.google", "market://")), "apps page never offers a Play page", "apps page offers a store/Play page")
ok("versionName" not in ap and "settings" not in ap, "apps rows are names and classes only", "apps page prints a value")
ok("handToStore()" in ap and "DeviceVault(ctx).backup(" in ap, "apps page: Install missing via Store + Capture", "apps page lost an action")
ma = rd("ac_cloud-account/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt")
ok('"runbook" -> RunbookPage(go)' in ma and '"apps" -> AppsPage()' in ma and '"configs" -> FleetSetupPage(model)' in ma, "Setup mounts runbook / apps / configs", "Setup does not mount runbook/apps/configs")
ok('q["step"].isNullOrBlank() -> r.runAll(' in api and 'Op("apps"' in api and '"apps" -> SetupRunbook(ctx).appsPlan()' in api, "debug API: run=1 = Run all, apps op", "debug API lacks Run all or apps")
sys.exit(fails)
PY
}

echo "-- real tree --"
check "$ROOT"; REAL=$?

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
FILES="ac_cloud-account/build.json ac_cloud-account/app/build.gradle ac_cloud-account/app/src/main/AndroidManifest.xml
ac_cloud-account/app/src/main/java/com/diegonmarcos/cloudaccount/App.kt
ab_cloud-libs-shared/libs/account/build.gradle
ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug/HostShell.kt
ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/SetupRunbook.kt
ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AccountDebugApi.kt
ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/RunbookPage.kt
ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/AppsPage.kt
ac_cloud-account/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt"
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
A=ac_cloud-account; K=$A/app/src/main/java/com/diegonmarcos/cloudaccount
P=ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile
M=0
mutate "manifest without the pairing service" $A/app/src/main/AndroidManifest.xml 'android:name="com.diegonmarcos.superapp.adbdebug.AdbPairingService"' 'android:name="gone"' || M=$((M+1))
mutate "boot receiver dropped" $A/app/src/main/AndroidManifest.xml 'adbdebug.HostShellBootReceiver' 'adbdebug.Gone' || M=$((M+1))
mutate "channel never armed" $K/App.kt 'HostShell.install(this)' 'Unit' || M=$((M+1))
mutate "app no longer links the shell lib" $A/app/build.gradle "implementation project(':libs:shizuku-adb-debug-tools')" '' || M=$((M+1))
mutate "SuperApp's server port reused" $A/build.json '"port": 38097' '"port": 38099' || M=$((M+1))
mutate "SuperApp bridge listed as a provider" $A/build.json '"moe.shizuku.privileged.api"' '"com.diegonmarcos.superapp"' || M=$((M+1))
mutate "runbook step dropped" $A/build.json '"store",' '' || M=$((M+1))
mutate "store never ALREADY" $P/SetupRunbook.kt 'State.Already("Cloud Store ${it.versionName} installed")' 'State.Todo("x")' || M=$((M+1))
mutate "no channel is silent" $P/SetupRunbook.kt 'if (ch == null && !allowPrompt)' 'if (false)' || M=$((M+1))
mutate "signer check removed" $P/SetupRunbook.kt 'theirs.intersect(ours).isEmpty()' 'false' || M=$((M+1))
mutate "debug op missing" $P/AccountDebugApi.kt '"runbook" -> SetupRunbook(ctx)' '"runbookX" -> SetupRunbook(ctx)' || M=$((M+1))
mutate "Run all does not stop at FAILED" $P/SetupRunbook.kt 'if (st is State.Failed || st is State.Running) { stopped = s.id; break }' 'if (false) { stopped = s.id; break }' || M=$((M+1))
mutate "plan sheet skipped" $P/RunbookPage.kt 'runCatching { rb.plan() }.getOrNull()' 'runCatching { rb.runAll() }.getOrNull()' || M=$((M+1))
mutate "a value printed in a drift row" $P/SetupRunbook.kt 'out += "${ap.app.id}.${sf.first}.${i.key}"' 'out += "${ap.app.id}.${sf.first}.${i.key}=${i.value}"' || M=$((M+1))
mutate "a value printed in a runbook row" $P/RunbookPage.kt 'Text(r?.optString("detail").orEmpty()' 'Text(r?.optString("detail").orEmpty() + rb.configsPlan()?.apps?.firstOrNull()?.items?.firstOrNull()?.value' || M=$((M+1))
mutate "a value printed in an apps row" $P/AppsPage.kt 'pl.installed.map { it.pkg to (if (it.ours) "fleet" else "installed") }' 'pl.installed.map { it.pkg to it.versionName }' || M=$((M+1))
mutate "a Play page offered" $P/AppsPage.kt '(pl.store.map { it.entry } + pl.manual)' '(pl.store.also { ctx.startActivity(it.first().intent) }.map { it.entry } + pl.manual)' || M=$((M+1))
mutate "no source declared dropped" $P/AppsPage.kt '"no source declared: add it to the source map"' '"open in Play"' || M=$((M+1))
mutate "perms step not wired to PermsPlan" $P/SetupRunbook.kt 'PermsPlan.apply(ctx, plan, ch)' 'PermsPlan.Outcome(emptyList())' || M=$((M+1))
mutate "runbook page unmounted" $K/MainActivity.kt '"runbook" -> RunbookPage(go)' '"runbook" -> AccountPlaceholderPage(section, page, "x")' || M=$((M+1))
mutate "debug run=1 without step is not Run all" $P/AccountDebugApi.kt 'q["step"].isNullOrBlank() -> r.runAll(' 'false -> r.runAll(' || M=$((M+1))

echo "== RESULT: real tree $REAL failure(s), $M mutation(s) not caught =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
