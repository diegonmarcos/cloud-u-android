#!/usr/bin/env bash
# #894 Cloud Store has its own shell channel, and its progress bar holds one state per item.
#
# Held statically (no build, no device, no network):
#  1. build.json: shizuku_client (Shizuku fallback, NO SuperApp bridge), its own local-server
#     port, the shizuku and fleetconfig modules on the app's dependency list.
#  2. the app links the module, declares the pairing service + boot receiver + permissions, arms
#     the channel from App.onCreate and offers the pairing flow in Settings.
#  3. SuperApp stands down: no self-update schedule, no check, no Update-all of its own when Cloud
#     Store is installed.
#  4. the Store bar is written in ONE place, through ProgressBarModel (indeterminate only until
#     the first byte count, monotonic per item). The pure rules are ProgressBarModelTest's.
# Then each mutation is planted in a scratch copy and must turn a check red.
#
# Usage: ./test-shell-channel.sh   (static, no network)
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
bj = json.load(open(f"{R}/ac_cloud-store/build.json"))
sc = bj.get("shizuku_client", {})
ok(sc.get("providers") == ["moe.shizuku.privileged.api"],
   "shizuku_client lists Shizuku and nothing else (no SuperApp bridge)",
   "shizuku_client is missing or names the SuperApp bridge as a provider")
ok(bj.get("shizuku_diagnostics", {}).get("local_server", {}).get("port") not in (None, 38099),
   "the local shell server has its own port (SuperApp binds 38099)",
   "the local shell server shares SuperApp's port")
dep = bj["modules"]["app"]["depends_on"]
ok("libs:shizuku-adb-debug-tools" in dep and "libs:fleetconfig-model" in dep,
   "the app module depends on the shell channel lib and fleetconfig-model",
   "the app module does not declare the shell lib / fleetconfig-model")
gr = rd("ac_cloud-store/app/build.gradle")
ok("project(':libs:shizuku-adb-debug-tools')" in gr, "app/build.gradle links the shell lib", "app/build.gradle does not link the shell lib")
mf = open(f"{R}/ac_cloud-store/app/src/main/AndroidManifest.xml", encoding="utf-8").read()
ok("com.diegonmarcos.superapp.adbdebug.AdbPairingService" in mf and 'foregroundServiceType="specialUse"' in mf,
   "the pairing foreground service is declared", "the pairing foreground service is not declared in Cloud Store's manifest")
ok("RECEIVE_BOOT_COMPLETED" in mf and ".shell.ShellBootReceiver" in mf and "FOREGROUND_SERVICE_SPECIAL_USE" in mf,
   "boot receiver and foreground-service permissions are declared", "boot receiver / foreground-service permissions missing")
app = rd("ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/App.kt")
ok("CloudStoreShell.install(this)" in app, "App.onCreate arms the channel", "App.onCreate never arms the channel")
ma = rd("ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/MainActivity.kt")
ok("ShellChannelSection(ctx)" in ma, "Settings offers the pairing flow", "Settings does not offer the pairing flow")
sec = rd("ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/shell/ShellChannelSection.kt")
ok("AdbPairingService.start(ctx)" in sec, "the Pair button starts the pairing service", "the Pair button does not start the pairing service")
sh = rd("ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/shell/CloudStoreShell.kt")
ok("AdbPairingService.onConnected = " in sh and "WRITE_SECURE_SETTINGS" in sh, "a finished pairing grants WRITE_SECURE_SETTINGS", "a finished pairing is not followed up")
ok("EmbeddedAdbChannel.autoConnect(ctx)" in sh, "the channel reconnects over mDNS without the user", "nothing reconnects the channel")
fl = rd("ab_cloud-libs-shared/libs/updater/src/main/java/com/diegonmarcos/superapp/updater/Fleet.kt")
ok("listOf(ShellInstall, SessionInstall)" in fl and "listOf(ShellInstall)" in fl,
   "Fleet.commit tries the shell install first and the session second", "Fleet.commit's ladder is not shell-first")
hand = rd("aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/apps/CloudStoreHandoff.kt")
ok("fun ownsInstalls(" in hand, "SuperApp has one question: does Cloud Store own installs", "CloudStoreHandoff.ownsInstalls is gone")
sa = rd("aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/ShellActivity.kt")
ok(sa.count("CloudStoreHandoff.ownsInstalls(applicationContext)") >= 3,
   "SuperApp's schedule, check and Update-all all stand down", "a SuperApp install path ignores Cloud Store")
ok("else Updater.start(applicationContext)" in sa, "SuperApp schedules no self-update with Cloud Store present", "SuperApp still schedules its own self-update")
pf = rd("aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/configs/PermissionsFragment.kt")
ok("if (!com.diegonmarcos.superapp.apps.CloudStoreHandoff.ownsInstalls(ctxAny()))" in pf, "the Auto-update toggle does not restart SuperApp's own updater", "the Auto-update toggle restarts SuperApp's updater")
sf = rd("ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreCloudFragment.kt")
ok(len(re.findall(r'\.isIndeterminate\s*=', sf)) == 1, "the bar's indeterminate flag has ONE writer", "more than one place writes the bar's indeterminate flag")
ok(len(re.findall(r'\.progress\s*=', sf)) == 1, "the bar's percent has ONE writer", "more than one place writes the bar's percent")
ok("barModel.step(" in sf and "barModel.reset()" in sf and "barModel.complete()" in sf, "every draw goes through ProgressBarModel", "a draw bypasses ProgressBarModel")
pm = rd("ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/ProgressBarModel.kt")
ok("this.percent = maxOf(this.percent," in pm, "the percent never moves backwards within an item", "ProgressBarModel's percent can move backwards")
ok("if (bytes > 0 || percent > 0)" in pm, "determinate starts at the first byte count", "ProgressBarModel's determinate trigger changed")
ok("if (item.isNotEmpty() && item != this.item)" in pm, "a state naming no item does not reset the bar", "an item-less state resets the bar")
sys.exit(fails)
PY
}

echo "-- real tree --"
check "$ROOT"; REAL=$?

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
FILES="ac_cloud-store/build.json ac_cloud-store/app/build.gradle ac_cloud-store/app/src/main/AndroidManifest.xml
ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/App.kt
ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/MainActivity.kt
ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/shell/ShellChannelSection.kt
ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/shell/CloudStoreShell.kt
ab_cloud-libs-shared/libs/updater/src/main/java/com/diegonmarcos/superapp/updater/Fleet.kt
aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/apps/CloudStoreHandoff.kt
aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/ShellActivity.kt
aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/configs/PermissionsFragment.kt
ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreCloudFragment.kt
ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/ProgressBarModel.kt"
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
A=aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp
P=ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore
M=0
mutate "SuperApp bridge listed as a provider" $S/build.json '"moe.shizuku.privileged.api"' '"moe.shizuku.privileged.api", "com.diegonmarcos.superapp"' || M=$((M+1))
mutate "shares SuperApp's server port" $S/build.json '"port": 38098' '"port": 38099' || M=$((M+1))
mutate "shell lib dropped from the app module" $S/build.json '"libs:shizuku-adb-debug-tools",
        "libs:fleetconfig-model"' '"libs:fleetconfig-model"' || M=$((M+1))
mutate "app no longer links the shell lib" $S/app/build.gradle "implementation project(':libs:shizuku-adb-debug-tools')" '' || M=$((M+1))
mutate "pairing service not declared" $S/app/src/main/AndroidManifest.xml 'foregroundServiceType="specialUse">' 'foregroundServiceType="dataSync">' || M=$((M+1))
mutate "boot receiver dropped" $S/app/src/main/AndroidManifest.xml '.shell.ShellBootReceiver' '.shell.Gone' || M=$((M+1))
mutate "channel never armed" $K/App.kt 'CloudStoreShell.install(this)' 'Unit' || M=$((M+1))
mutate "no pairing flow in Settings" $K/MainActivity.kt 'ShellChannelSection(ctx)' 'Unit' || M=$((M+1))
mutate "Pair does not start the service" $K/shell/ShellChannelSection.kt 'AdbPairingService.start(ctx)' 'Unit' || M=$((M+1))
mutate "pairing not followed up" $K/shell/CloudStoreShell.kt 'AdbPairingService.onConnected' 'AdbPairingService.onConnectedX' || M=$((M+1))
mutate "no reconnect" $K/shell/CloudStoreShell.kt 'EmbeddedAdbChannel.autoConnect(ctx)' 'Pair(false, "")' || M=$((M+1))
mutate "session install first" ab_cloud-libs-shared/libs/updater/src/main/java/com/diegonmarcos/superapp/updater/Fleet.kt 'listOf(ShellInstall, SessionInstall)' 'listOf(SessionInstall, ShellInstall)' || M=$((M+1))
mutate "SuperApp schedules its own self-update" $A/ShellActivity.kt 'else Updater.start(applicationContext)' 'Updater.start(applicationContext)' || M=$((M+1))
mutate "SuperApp's Update-all installs again" $A/ShellActivity.kt 'if (!com.diegonmarcos.superapp.apps.CloudStoreHandoff.ownsInstalls(applicationContext))
                kotlin.concurrent.thread {' 'kotlin.concurrent.thread {' || M=$((M+1))
mutate "toggle restarts SuperApp's updater" $A/configs/PermissionsFragment.kt 'if (!com.diegonmarcos.superapp.apps.CloudStoreHandoff.ownsInstalls(ctxAny()))' '' || M=$((M+1))
mutate "a second writer of the indeterminate flag" $P/StoreCloudFragment.kt 'if (!d.indeterminate && bar.progress != d.percent) bar.progress = d.percent' 'bar.isIndeterminate = false; bar.progress = d.percent' || M=$((M+1))
mutate "a draw bypasses the model" $P/StoreCloudFragment.kt 'drawBar(bar, barModel.step(p.appId.ifEmpty { p.pkg }, p.bytes, p.percent, p.failed))' 'drawBar(bar, ProgressBarModel.Draw(p.percent < 0, p.percent))' || M=$((M+1))
mutate "percent can move backwards" $P/ProgressBarModel.kt 'this.percent = maxOf(this.percent, percent.coerceIn(0, 100))' 'this.percent = percent.coerceIn(0, 100)' || M=$((M+1))
mutate "determinate before any byte" $P/ProgressBarModel.kt 'if (bytes > 0 || percent > 0)' 'if (true)' || M=$((M+1))
mutate "an item-less state resets the bar" $P/ProgressBarModel.kt 'if (item.isNotEmpty() && item != this.item)' 'if (item != this.item)' || M=$((M+1))

echo "== RESULT: real tree $REAL failure(s), $M mutation(s) not caught =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
