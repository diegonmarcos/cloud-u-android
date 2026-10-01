#!/usr/bin/env bash
# #733 Configs ▸ Watchdog ▸ Mesh and the Apps Mesh page.
#
#   C1  captions: the wg page reads Cloud Mesh (id unchanged), the Store's mesh
#       entry and the Configs apps-mesh page both read Apps Mesh — the SAME
#       words, from their two declarations — and Mesh holds them in order
#   C2  ONE page, two entry points: AppsMeshFragment and the Store's renderMesh
#       both call AppsMesh.page, config/apps-mesh routes to AppsMeshFragment, and
#       nothing else draws the mesh
#   C3  the four member actions (API · Start · Stop · Open · Details) are declared
#       and each id has its own branch doing the thing it names
#   C4  the missing-membership detector: every GapKind has words + fix declared
#       and is emitted by gaps(); Export shares the report
#   C5  the declaration-only gap is FIXED: the devtools manifest makes every
#       mesh member visible to every other (queries + exported, guarded marker)
#   C6  Peer Control: a peer selector at the top, fed by the declarations, and no
#       action targets "whichever link is open first" any more
#
# What the detector DECIDES (each gap planted on one app, each with its healthy
# control) is asserted by app/src/test/.../AppsMeshTest.kt.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
BJ="$APP/build.json"
LIBS="$ROOT/ab_cloud-libs-shared/libs"
STORE="$LIBS/appstore/src/main/java/com/diegonmarcos/superapp/appstore"
CONTROLS="$LIBS/appstore/src/main/assets/appstore-controls.json"
MESH="$STORE/AppsMesh.kt"
FRAG="$STORE/AppsMeshFragment.kt"
PAGE="$STORE/StoreCloudFragment.kt"
PAGES="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt"
DEVMAN="$LIBS/devtools/src/main/AndroidManifest.xml"
RECV="$LIBS/devtools/src/main/java/com/diegonmarcos/superapp/devtools/FleetMemberReceiver.kt"
KDE="$LIBS/kde-connect/src/main/java/com/diegonmarcos/superapp/kdeconnect"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$BJ" "$CONTROLS" "$MESH" "$FRAG" "$PAGE" "$PAGES" "$DEVMAN" "$RECV" "$KDE/KdePeers.kt" "$KDE/KdeConnectFragment.kt"; do
  [ -f "$f" ] || { echo "ERROR: missing $f — a check over nothing passes" >&2; exit 2; }
done
# Comment-stripped Kotlin, so a KDoc that NAMES a call cannot satisfy a check for the call.
code() { python3 - "$1" <<'EOF'
import re, sys
s = open(sys.argv[1]).read()
s = re.sub(r'/\*.*?\*/', '', s, flags=re.S); s = re.sub(r'(?m)^\s*//.*$', '', s)
print(s)
EOF
}
# The body of one Kotlin function, by name (to the first line that closes it at its indent).
fn() { code "$1" | awk -v n="fun $2(" 'index($0, n){f=1; match($0,/^ */); ind=RLENGTH} f{print} f && $0 ~ "^ {" ind "}}$" {exit}'; }

echo "== C1: captions =="
out="$(python3 - "$BJ" "$CONTROLS" <<'EOF'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')['pages']
by = {p['id']: p for p in pages}
store_caption = json.load(open(sys.argv[2]))['pages']['mesh']['label']
mesh = [p['id'] for p in pages if p.get('subgroup') == 'Mesh' and not p.get('hidden')]
print('WG', by.get('wg', {}).get('label'), by.get('wg', {}).get('action'))
print('APPS', by.get('apps-mesh', {}).get('label'), '|', store_caption)
print('ORDER', ','.join(mesh))
EOF
)"
echo "$out" | sed 's/^/    /'
echo "$out" | grep -qx 'WG Cloud Mesh section:wg' && ok "the wg page reads Cloud Mesh and still opens section:wg" \
  || bad "the wg page is not 'Cloud Mesh' opening section:wg"
echo "$out" | grep -qx 'APPS Apps Mesh | Apps Mesh' && ok "Configs apps-mesh and Store's mesh entry both read Apps Mesh" \
  || bad "the two entry points to the Apps Mesh page do not both read 'Apps Mesh'"
echo "$out" | grep -qx 'ORDER wg,kde,apps-mesh' && ok "Watchdog ▸ Mesh = Cloud Mesh · Peer Control · Apps Mesh" \
  || bad "Watchdog ▸ Mesh is not wg, kde, apps-mesh in that order"

echo "== C2: one page, two entry points =="
code "$FRAG" | grep -qF 'AppsMesh.page(this, col)' && ok "AppsMeshFragment hosts AppsMesh.page" \
  || bad "AppsMeshFragment does not draw AppsMesh.page"
fn "$PAGE" renderMesh | grep -qF 'AppsMesh.page(this, host)' && ok "Store's renderMesh hosts the same AppsMesh.page" \
  || bad "Store's renderMesh does not draw AppsMesh.page — the two entry points show different pages"
fn "$PAGE" renderMesh | grep -qF 'StoreMesh.render' && bad "Store's renderMesh still draws its own copy of the mesh" \
  || ok "Store's renderMesh draws no copy of its own"
grep -qE 'sectionId == "config" && pageId == "apps-mesh" +-> .*AppsMeshFragment\(\)' "$PAGES" \
  && ok "config/apps-mesh routes to AppsMeshFragment" || bad "config/apps-mesh does not route to AppsMeshFragment"
callers="$(grep -rlF 'StoreMesh.render(' "$LIBS" "$APP/app/src/main" --include=*.kt | xargs -r -n1 sh -c 'python3 - "$0" <<EOF
import re,sys
s=open(sys.argv[1]).read(); s=re.sub(r"/\*.*?\*/","",s,flags=re.S); s=re.sub(r"(?m)^\s*//.*$","",s)
print(sys.argv[1] if "StoreMesh.render(" in s else "")
EOF' | grep -v '^$' | xargs -r -n1 basename | sort -u | tr '\n' ' ')"
[ "$callers" = "AppsMesh.kt " ] && ok "only AppsMesh draws the mesh (callers: $callers)" \
  || bad "the mesh is drawn outside AppsMesh: $callers"

echo "== C3: the member actions =="
ids="$(jq -r '.apps_mesh.member_actions[].id' "$CONTROLS" | tr '\n' ' ')"
act="$(fn "$MESH" act)"
[ -n "$act" ] || bad "could not isolate AppsMesh.act — the checks below would verify nothing"
for a in api start stop open details; do
  case " $ids " in *" $a "*) ok "action $a is declared" ;; *) bad "action $a is not declared in apps_mesh.member_actions" ;; esac
  printf '%s' "$act" | grep -qE "^\s+\"$a\" ->" && ok "act() handles $a" || bad "act() has no branch for $a"
done
branch() { printf '%s' "$act" | awk -v a="\"$1\" ->" 'index($0,a){f=1} f{print} f && /^            "[a-z]+" ->/ && !index($0,a){exit}'; }
branch api     | grep -qF '"/api/docs"'                && branch api | grep -qF 'FleetToken.get' \
  && ok "API fetches /api/docs with the fleet bearer"   || bad "API does not fetch /api/docs with the fleet token"
branch start   | grep -qF 'FleetPeers.wake('            && ok "Start wakes through the fleet provider" || bad "Start does not use FleetPeers.wake"
branch stop    | grep -qF 'PhoneAppActions.forceStop('  && branch stop | grep -qF 'store_fleet_stop_no_channel' \
  && ok "Stop force-stops via the privileged channel and says so when none is armed" \
  || bad "Stop does not use the shell channel, or is silent when it is not armed"
branch open    | grep -qF 'getLaunchIntentForPackage'   && ok "Open launches the app" || bad "Open does not launch the app"
branch details | grep -qF 'details('                    && ok "Details builds the full detail text" || bad "Details does not build details()"
fn "$MESH" details | grep -qF 'installedDetails' && fn "$MESH" details | grep -qF 'permissions(' \
  && ok "details() reads the installed APK identity and its permissions" || bad "details() lacks installed identity or permissions"
fn "$MESH" textDialog | grep -qF 'copy(ctx, body)' && ok "the detail/API dialog offers Copy all" || bad "no Copy all on the detail dialog"
fn "$MESH" showActions | grep -qF 'actionsFor(decl' && ok "a tapped member offers the declared actions" \
  || bad "the member tap does not offer the declared actions"

echo "== C4: missing membership + export =="
kinds="$(code "$MESH" | sed -n 's/.*enum class GapKind { \(.*\) }.*/\1/p' | tr -d ' ' | tr ',' '\n')"
[ -n "$kinds" ] || bad "no GapKind enum found — the checks below would verify nothing"
gaps_fn="$(fn "$MESH" gaps)"
for k in $kinds; do
  jq -e --arg k "$k" '.apps_mesh.gaps[$k] | (.label|length>0) and (.fix|length>0)' "$CONTROLS" >/dev/null \
    && ok "gap $k has its words and fix declared" || bad "gap $k has no label/fix in apps_mesh.gaps"
  printf '%s' "$gaps_fn" | grep -qF "GapKind.$k" && ok "gaps() can report $k" || bad "gaps() never reports $k"
done
fn "$MESH" page | grep -qF 'Intent.ACTION_SEND' && fn "$MESH" page | grep -qF 'report(decl' \
  && ok "Export shares the page's report" || bad "Export does not share report()"

echo "== C5: every mesh member can see every other (the manifest-only gap, fixed) =="
python3 - "$DEVMAN" <<'EOF' && ok "devtools queries MESH_MEMBER and exports a CONSTELLATION_DATA-guarded receiver answering it" || bad "the mesh visibility marker is missing or unguarded"
import sys, xml.etree.ElementTree as ET
A = '{http://schemas.android.com/apk/res/android}'
m = ET.parse(sys.argv[1]).getroot()
q = {a.get(A+'name') for a in m.findall('./queries/intent/action')}
r = [x for x in m.findall('./application/receiver')
     if x.get(A+'exported') == 'true'
     and x.get(A+'permission') == 'com.diegonmarcos.cloud.permission.CONSTELLATION_DATA'
     and {a.get(A+'name') for a in x.findall('./intent-filter/action')} & q]
sys.exit(0 if q and r else 1)
EOF
grep -qF 'class FleetMemberReceiver' "$RECV" && grep -qF 'FleetMemberReceiver' "$DEVMAN" \
  && ok "the receiver the manifest names exists" || bad "FleetMemberReceiver is declared but not implemented (or vice versa)"

echo "== C6: Peer Control targets the selected peer =="
code "$KDE/KdePeers.kt" | grep -qF 'KdeConnectConfig.get().devices' && code "$KDE/KdePeers.kt" | grep -qF 'KdeMesh.nodes()' \
  && ok "the peer list is the declared devices + the mesh nodes" || bad "KdePeers does not read both declarations"
KF="$(code "$KDE/KdeConnectFragment.kt")"
printf '%s' "$KF" | grep -qF 'connectedIds().firstOrNull()' \
  && bad "an action still targets the first open link instead of the selected peer" \
  || ok "no action targets 'the first open link'"
n="$(printf '%s' "$KF" | grep -cF 'selectedLiveId()')"
[ "$n" -ge 6 ] && ok "$n call sites target the selected peer" || bad "only $n call sites use selectedLiveId()"
cv="$(fn "$KDE/KdeConnectFragment.kt" onCreateView)"
sel="$(printf '%s\n' "$cv" | grep -nF 'root.addView(selector)' | cut -d: -f1)"
clip="$(printf '%s\n' "$cv" | grep -nF 'root.addView(buildClipboardCard' | cut -d: -f1)"
[ -n "$sel" ] && [ -n "$clip" ] && [ "$sel" -lt "$clip" ] && ok "the selector sits at the top, above every action card" \
  || bad "the peer selector is not drawn above the action cards (selector@$sel clipboard@$clip)"

echo
echo "== RESULT(#733 apps mesh): $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
