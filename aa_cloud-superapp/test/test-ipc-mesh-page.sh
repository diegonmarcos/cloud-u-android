#!/usr/bin/env bash
# #ipc SuperApp > Configs > Network > Apps Mesh > IPC (a0_docs/eng-specs/fleet-ipc.md): the fleet's IPC Mesh,
# the SAME shared composable Cloud Store > Access > IPC draws (libs:appstore IpcMeshPage).
#   R1 build.json: config page ipc ("IPC") is the tab of Apps Mesh after its Apps tab, and no longer a Network tile; Network keeps 2..8
#   R2 SectionPages builds libs:appstore IpcMeshFragment for it, which hosts IpcMeshPage() and nothing else
#   R3 the reachability and "every declared endpoint is listed" tests exist (IpcMeshPageTest P1-P3)
# Then each planted defect must turn it red.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"; ROOT="$(cd "$APP/.." && pwd)"
SP="app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt"
PG="ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/IpcMeshPage.kt"
UT="app/src/test/java/com/diegonmarcos/superapp/apps/IpcMeshPageTest.kt"
check() {  # check <app dir> <page file>
  local A="$1" P="$2" f=0
  python3 - "$A/build.json" <<'PY' || { echo "  FAIL: R1 ipc is not the IPC tab of Apps Mesh after Apps, or is still a Network tile (2..8)"; f=$((f+1)); }
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')['pages']
net = [p for p in pages if p.get('subgroup') == 'Network' and p.get('group') == 'Setup' and not p.get('hidden')]
ids = [p['id'] for p in net]
tabs = next((p.get('tabs', []) for p in net if p['id'] == 'apps-mesh'), [])
ipc = next((p for p in pages if p['id'] == 'ipc'), {})
ok = 'ipc' not in ids and tabs[:2] == ['apps-mesh-members', 'ipc'] and 2 <= len(ids) <= 8 \
    and ipc.get('label') == 'IPC' and ipc.get('hidden') is True
sys.exit(0 if ok else 1)
PY
  grep -q 'pageId == "ipc"            -> com.diegonmarcos.superapp.appstore.IpcMeshFragment()' "$A/$SP" \
    || { echo "  FAIL: R2 SectionPages does not build IpcMeshFragment for ipc"; f=$((f+1)); }
  grep -q 'ComposeView(requireContext()).apply { setContent { IpcMeshPage() } }' "$P" \
    || { echo "  FAIL: R2 IpcMeshFragment does not host IpcMeshPage"; f=$((f+1)); }
  for t in 'P1 the baked registry carries every declared endpoint' 'P2 the page lists every declared endpoint' \
           'P3 Configs Network Apps Mesh IPC is reachable and hosts the shared page'; do
    grep -qF "fun \`$t\`()" "$A/$UT" || { echo "  FAIL: R3 IpcMeshPageTest lost: $t"; f=$((f+1)); }
  done
  return $f
}
FAIL=0
if check "$APP" "$ROOT/$PG"; then echo "  PASS: R1-R3 Configs > Network > Apps Mesh > IPC reaches the shared IPC Mesh page"; else FAIL=$((FAIL+1)); fi
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
while IFS='|' read -r file expr; do
  [ -n "$file" ] || continue
  rm -rf "$TMP/a"; mkdir -p "$TMP/a/$(dirname "$SP")" "$TMP/a/$(dirname "$UT")"
  cp "$APP/build.json" "$TMP/a/"; cp "$APP/$SP" "$TMP/a/$SP"; cp "$APP/$UT" "$TMP/a/$UT"; cp "$ROOT/$PG" "$TMP/page.kt"
  tgt="$TMP/a/$file"; [ "$file" = page ] && tgt="$TMP/page.kt"
  python3 -c "import sys; p=sys.argv[1]; s=open(p).read(); t=$expr; open(p,'w').write(t); sys.exit(0 if t!=s else 3)" "$tgt" \
    || { echo "  FAIL: mutation did not apply: $file"; FAIL=$((FAIL+1)); continue; }
  if check "$TMP/a" "$TMP/page.kt" >/dev/null 2>&1; then echo "  FAIL: NOT caught: $file ${expr:0:60}"; FAIL=$((FAIL+1))
  else echo "  PASS: caught: $file ${expr:0:60}"; fi
done <<MUT
build.json|s.replace('"id": "ipc",\n            "label": "IPC"', '"id": "ipc",\n            "label": "IPC Mesh"')
build.json|s.replace('"tabs": ["apps-mesh-members", "ipc", "identity"]', '"tabs": ["apps-mesh-members", "identity"]')
build.json|s.replace('"id": "ipc",\n            "label": "IPC",\n            "icon": "ic_mode_apps",\n            "hidden": true', '"id": "ipc",\n            "label": "IPC",\n            "icon": "ic_mode_apps",\n            "group": "Setup", "subgroup": "Network"')
$SP|s.replace('com.diegonmarcos.superapp.appstore.IpcMeshFragment()', 'com.diegonmarcos.superapp.appstore.AppsMeshFragment()')
page|s.replace('setContent { IpcMeshPage() }', 'setContent { }')
$UT|s.replace('fun \`P2 the page lists every declared endpoint\`()', 'fun p2()')
MUT
echo "== RESULT(#ipc SuperApp IPC page): $FAIL failed =="
[ "$FAIL" -eq 0 ]
