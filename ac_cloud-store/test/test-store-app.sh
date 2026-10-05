#!/usr/bin/env bash
# #865 Cloud Store is the Store, split out of cloud-superapp.
#
# What this holds, statically (no build, no device, no network):
#  1. Cloud Store hosts the three Store pages from libs:appstore (no copy of
#     the store code in this app) and claims the unattended fleet pass.
#  2. libs:appstore honours AppStoreHost.runsFleetPass at all four entry
#     points of that pass: schedule (start), Wi-Fi/screen kick, the
#     launch-time checkNow, and doWork itself. A missed one is two apps
#     racing the same downloads and install sessions.
#  3. SuperApp hands the pass over exactly when Cloud Store is installed, and
#     routes all three Store pages through the hand-off.
#  4. One package name: SuperApp's hand-off, this app's build.json and the
#     fleet manifest row agree, and the OPEN action SuperApp sends is the one
#     this manifest declares.
#
# Then it plants each mutation below in a scratch copy and requires the
# checks to go red, so a check that cannot fail does not count as a check.
#
# Usage: ./test-store-app.sh   (static, no network)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

STORE="$ROOT/ac_cloud-store"
LIB="$ROOT/ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore"
SUPER="$ROOT/aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp"
FLEET="$ROOT/aa_cloud-superapp/data/constellation-fleet.json"

strip() {
  python3 - "$1" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
s = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), s, flags=re.S)
sys.stdout.write(re.sub(r'//[^\n]*', '', s))
PY
}

# check <store-dir> <lib-dir> <superapp-dir> <fleet.json>  → prints PASS/FAIL lines, exit = #fails
check() {
  local S="$1" L="$2" P="$3" F="$4" fails=0
  ok()  { echo "  PASS: $1"; }
  bad() { echo "  FAIL: $1"; fails=$((fails+1)); }
  local MA="$S/app/src/main/java/com/diegonmarcos/cloudstore/MainActivity.kt"
  local AP="$S/app/src/main/java/com/diegonmarcos/cloudstore/App.kt"
  local MF="$S/app/src/main/AndroidManifest.xml"
  local W="$L/ConstellationWorker.kt" H="$L/AppStoreHost.kt"
  local HO="$P/apps/CloudStoreHandoff.kt" SA="$P/App.kt" SP="$P/launcher/SectionPages.kt"
  for f in "$MA" "$AP" "$MF" "$W" "$H" "$HO" "$SA" "$SP" "$S/build.json" "$S/app/build.gradle" "$F"; do
    [ -f "$f" ] || { bad "missing $f"; return 99; }
  done

  # 1. hosts the lib's pages, owns no store code, claims the pass
  local ma; ma="$(strip "$MA")"
  for frag in StoreCloudFragment StorePhoneFragment AppsMeshFragment; do
    printf '%s' "$ma" | grep -Eq -- "AndroidFragment<$frag>" && ok "Cloud Store shows $frag" || bad "Cloud Store does not show $frag"
  done
  grep -q "project(':libs:appstore')" "$S/app/build.gradle" && ok "app links :libs:appstore" || bad "app does not link :libs:appstore"
  if find "$S/app/src" -name '*.kt' | xargs grep -l "^package com.diegonmarcos.superapp.appstore" 2>/dev/null | grep -q .; then
    bad "a copy of libs:appstore code lives in ac_cloud-store"; else ok "no copy of the store code in this app"; fi
  local ap; ap="$(strip "$AP")"
  printf '%s' "$ap" | grep -q 'runsFleetPass *= *{ *true *}' && ok "Cloud Store claims the fleet pass" || bad "Cloud Store does not claim the fleet pass"
  printf '%s' "$ap" | grep -q 'ConstellationWorker.start(this)' && ok "Cloud Store schedules the fleet pass" || bad "Cloud Store never schedules the fleet pass"

  # 2. the lib honours the hook at all four entry points
  grep -q 'var runsFleetPass: (android.content.Context) -> Boolean = { true }' "$H" && ok "hook declared, default true" || bad "AppStoreHost.runsFleetPass missing or not defaulting to true"
  local w; w="$(strip "$W")"
  body() { printf '%s' "$w" | sed -n "/$1/,/^        }/p"; }
  printf '%s' "$w" | sed -n '/override suspend fun doWork/,/StoreAuto.attach/p' | grep -q 'runsFleetPass(applicationContext)' && ok "doWork stands down when another app owns the pass" || bad "doWork runs the pass regardless of runsFleetPass"
  body 'fun kick(' | grep -q 'runsFleetPass(context)) return' && ok "kick() gated" || bad "kick() not gated on runsFleetPass"
  body 'fun checkNow(' | grep -q 'runsFleetPass(context)) return' && ok "checkNow() gated" || bad "checkNow() not gated on runsFleetPass"
  local st; st="$(body 'fun start(')"
  if printf '%s' "$st" | sed -n '/runsFleetPass/,/StoreAuto.attach/p' | grep -q 'StoreAuto.attach' &&
     printf '%s' "$st" | sed -n '/runsFleetPass/,/return/p' | grep -q 'cancelUniqueWork("$WORK_NAME-kick")'; then
    ok "start() cancels queued work and attaches no Wi-Fi trigger when it does not own the pass"
  else bad "start() schedules or attaches before checking runsFleetPass, or leaves the kick queued"; fi

  # 3. SuperApp hands it over exactly when Cloud Store is installed
  strip "$SA" | grep -q 'runsFleetPass *= *{ *ctx *-> *!.*CloudStoreHandoff.installed(ctx) *}' && ok "SuperApp runs the pass only while Cloud Store is absent" || bad "SuperApp's runsFleetPass is not !CloudStoreHandoff.installed"
  local sp; sp="$(strip "$SP")"
  for pg in store-cloud store-phone apps-mesh; do
    printf '%s' "$sp" | grep -q "pageId == \"$pg\" *-> *com.diegonmarcos.superapp.apps.CloudStoreHandoff.page(pageId)" && ok "SuperApp page $pg goes through the hand-off" || bad "SuperApp page $pg bypasses the hand-off"
  done

  # 4. one package name, one OPEN action
  local appid pkg action
  appid="$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['android']['application_id'])" "$S/build.json")"
  pkg="$(strip "$HO" | sed -n 's/.*const val PKG = "\([^"]*\)".*/\1/p')"
  [ -n "$appid" ] && [ "$appid" = "$pkg" ] && ok "hand-off package = build.json application_id ($appid)" || bad "hand-off package '$pkg' != application_id '$appid'"
  grep -q "\"package\":\"$appid\"" "$F" && ok "fleet manifest lists $appid (installable and updated by the Store)" || bad "fleet manifest has no row for $appid"
  action="$(strip "$HO" | sed -n 's/.*ACTION_OPEN = "\([^"]*\)".*/\1/p')"
  grep -q "<action android:name=\"$action\" */>" "$MF" && ok "manifest answers SuperApp's $action" || bad "manifest does not declare the action SuperApp sends ('$action')"
  return $fails
}

echo "── real tree ──"
check "$STORE" "$LIB" "$SUPER" "$FLEET"; REAL=$?

# ── mutations: each must turn the checks red ──
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mutate() {  # mutate <label> <relative-file-under-root-of-copy> <python-replace-old> <new>
  local label="$1" which="$2" old="$3" new="$4" d="$TMP/m"
  rm -rf "$d"; mkdir -p "$d"
  cp -r "$STORE" "$d/store"; cp -r "$LIB" "$d/lib"; cp -r "$SUPER" "$d/super"; cp "$FLEET" "$d/fleet.json"
  python3 - "$d/$which" "$old" "$new" <<'PY' || { echo "  MUTATION NOT APPLIED: $1"; return 1; }
import sys
p, old, new = sys.argv[1:4]
s = open(p, encoding='utf-8').read()
if old not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
  if check "$d/store" "$d/lib" "$d/super" "$d/fleet.json" >/dev/null; then echo "  MUTATION SURVIVED: $label"; return 1; fi
  echo "  mutation caught: $label"; return 0
}
M=0
W=lib/ConstellationWorker.kt
mutate "doWork gate removed" "$W" 'if (!AppStoreHost.runsFleetPass(applicationContext)) {' 'if (false) {' || M=$((M+1))
mutate "kick gate removed" "$W" 'if (!AppStoreHost.runsFleetPass(context)) return   // #865
                val req' 'val req' || M=$((M+1))
mutate "start gate removed" "$W" 'if (!AppStoreHost.runsFleetPass(context)) {' 'if (false) {' || M=$((M+1))
mutate "hook default flipped" lib/AppStoreHost.kt 'var runsFleetPass: (android.content.Context) -> Boolean = { true }' 'var runsFleetPass: (android.content.Context) -> Boolean = { false }' || M=$((M+1))
mutate "SuperApp keeps the pass" super/App.kt 'runsFleetPass = { ctx -> !com.diegonmarcos.superapp.apps.CloudStoreHandoff.installed(ctx) }' 'runsFleetPass = { true }' || M=$((M+1))
mutate "store page bypasses hand-off" super/launcher/SectionPages.kt 'pageId == "store-phone"    -> com.diegonmarcos.superapp.apps.CloudStoreHandoff.page(pageId)' 'pageId == "store-phone"    -> com.diegonmarcos.superapp.appstore.StorePhoneFragment()' || M=$((M+1))
mutate "package name drifts" super/apps/CloudStoreHandoff.kt 'const val PKG = "com.diegonmarcos.cloudstore"' 'const val PKG = "com.diegonmarcos.store"' || M=$((M+1))
mutate "OPEN action dropped" store/app/src/main/AndroidManifest.xml '<action android:name="com.diegonmarcos.cloudstore.OPEN" />' '' || M=$((M+1))
mutate "Cloud Store gives up the pass" store/app/src/main/java/com/diegonmarcos/cloudstore/App.kt 'runsFleetPass = { true }' 'runsFleetPass = { false }' || M=$((M+1))
mutate "Mesh tab dropped" store/app/src/main/java/com/diegonmarcos/cloudstore/MainActivity.kt 'AndroidFragment<AppsMeshFragment>' 'AndroidFragment<StoreCloudFragment>' || M=$((M+1))

echo "== RESULT: real tree $REAL failure(s), $M mutation(s) not caught =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
