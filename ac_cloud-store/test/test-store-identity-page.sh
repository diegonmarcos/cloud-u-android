#!/usr/bin/env bash
# Cloud Store > Access > Identity: libs:appstore's FleetIdentityPage (the page SuperApp > Configs > Apps Mesh > Identity draws).
#   I1 build.json::ui access.pages are exactly mesh, ipc, identity, android, cloud, in that order ("Identity" labelled)
#   I2 AccessPage routes PAGE_IDENTITY to the shared FleetIdentityPage
#   I3 one FleetIdentityPage in the fleet (no second copy)
#   I4 build.json::ui._doc names the Identity tab
#   I5/I6 ONE source (store-identity-guard.py): shared-uid groups only in data/fleet-identity.json; the Access tab
#      list only in build.json (AccessPage.kt's PAGE_* ids cannot drift, no Kotlin list of the tabs)
# Then each planted defect must turn it red.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
PASS=0; FAIL=0

check() {  # check <root> -> number of failures
  local R="$1" f=0
  ok()  { echo "  PASS: $1"; }
  bad() { echo "  FAIL: $1"; f=$((f+1)); }
  local BJ="$R/ac_cloud-store/build.json" AP="$R/ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/AccessPage.kt"
  for x in "$BJ" "$AP"; do [ -f "$x" ] || { bad "missing ${x#$R/}"; return 1; }; done
  python3 - "$BJ" <<'PY' && ok "I1 Access = mesh, ipc, identity, android, cloud" || bad "I1 Access pages are not mesh, ipc, identity, android, cloud in order"
import json, sys
s = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'access')
ids = [p['id'] for p in s['pages']]
sys.exit(0 if ids == ['mesh', 'ipc', 'identity', 'android', 'cloud'] and s['pages'][2]['label'] == 'Identity' else 1)
PY
  grep -q 'const val PAGE_IDENTITY = "identity"' "$AP" && grep -q 'PAGE_IDENTITY -> FleetIdentityPage()' "$AP" \
    && ok "I2 AccessPage routes identity to the shared FleetIdentityPage" || bad "I2 AccessPage does not route identity to FleetIdentityPage"
  local copies; copies="$(grep -rl --include=*.kt 'fun FleetIdentityPage(' "$R"/a[abc]_* 2>/dev/null | grep -v '/test/' | wc -l)"
  [ "$copies" -le 1 ] && ok "I3 at most one FleetIdentityPage in the checked tree" || bad "I3 $copies FleetIdentityPage definitions"
  python3 - "$BJ" <<'PY' && ok "I4 ui._doc names the Identity tab" || bad "I4 ui._doc does not mention Identity"
import json, sys
sys.exit(0 if 'IPC | Identity | Android Perms' in json.load(open(sys.argv[1]))['ui']['_doc'] else 1)
PY
  python3 -I "$HERE/store-identity-guard.py" "$R" I5 && ok "I5 shared-uid groups are declared only in fleet-identity.json" || bad "I5 a shared-uid group is declared outside fleet-identity.json"
  python3 -I "$HERE/store-identity-guard.py" "$R" I6 && ok "I6 Access tabs: Kotlin PAGE_* == build.json, no Kotlin list" || bad "I6 AccessPage PAGE_* drifted from build.json, or Kotlin lists the tabs"
  return $f
}

out="$(check "$ROOT")"; rc=$?; printf '%s\n' "$out"
PASS=$(printf '%s\n' "$out" | grep -c '  PASS:'); FAIL=$rc

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mirror() {
  rm -rf "$TMP/r"; mkdir -p "$TMP/r"
  for d in ac_cloud-store/build.json ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/AccessPage.kt \
           aa_cloud-superapp/data/fleet-identity.json ac_cloud-termux/build.json; do
    mkdir -p "$TMP/r/$(dirname "$d")"; cp "$ROOT/$d" "$TMP/r/$d"; done
}
while IFS='|' read -r file expr; do
  [ -n "$file" ] || continue
  mirror
  python3 -c "import sys; p=sys.argv[1]; s=open(p).read(); t=$expr; open(p,'w').write(t); sys.exit(0 if t!=s else 3)" "$TMP/r/$file" \
    || { echo "  FAIL: mutation did not apply: $file ${expr:0:60}"; FAIL=$((FAIL+1)); continue; }
  if check "$TMP/r" >/dev/null 2>&1; then echo "  FAIL: NOT caught: $file ${expr:0:70}"; FAIL=$((FAIL+1))
  else echo "  PASS: caught: $file ${expr:0:70}"; PASS=$((PASS+1)); fi
done <<MUT
ac_cloud-store/build.json|s.replace('"id": "identity"', '"id": "identity-x"')
ac_cloud-store/build.json|s.replace('IPC | Identity | Android Perms', 'IPC | Android Perms')
ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/AccessPage.kt|s.replace('PAGE_IDENTITY -> FleetIdentityPage()', 'PAGE_IDENTITY -> PermsList(cloud = true)')
ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/AccessPage.kt|s.replace('const val PAGE_IDENTITY = "identity"', 'const val PAGE_IDENTITY = "identity2"')
ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/AccessPage.kt|s.replace('const val PAGE_CLOUD = "cloud"', 'const val PAGE_CLOUD = "cloud"\nconst val PAGE_X = "x"')
ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/AccessPage.kt|s.replace('const val PAGE_CLOUD = "cloud"', 'const val PAGE_CLOUD = "cloud"\nval TABS = listOf(PAGE_MESH, PAGE_IPC, PAGE_IDENTITY)')
ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/AccessPage.kt|s.replace('const val PAGE_CLOUD = "cloud"', 'const val PAGE_CLOUD = "cloud"\nval UID = "cld.termux.shared"')
ac_cloud-store/build.json|s.replace('"ui": {', '"ui": {"shared_user_id": "cld.rogue", ', 1)
MUT

echo "== RESULT(store Identity page): $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
