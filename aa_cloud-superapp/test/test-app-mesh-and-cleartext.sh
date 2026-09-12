#!/usr/bin/env bash
# #300 — two findings from one question ("why can't cloud-browser reach
# localhost:8000? is it permissions, or the android mesh we created?").
#
# The answer was neither: it is Android's cleartext policy. Since API 28 a
# WebView refuses http:// to any host not named in a network security config,
# and Cloud Browser shipped none — while the SAME libs/browser code reaches
# localhost:8000 inside the SuperApp, which does carry one. Part A pins that
# config so the app cannot lose it again, and pins it as a NARROW allowance
# (loopback + the owner's own mesh and gateways) rather than the blanket
# usesCleartextTraffic=true that would also permit cleartext to the internet.
#
# The second half of the question was the real one. There IS an inter-app mesh
# — eight Binder channels in daily use — and nothing anywhere declared it, so
# Configs ▸ About ▸ Network ▸ IPC Contract printed "No IPC contract declared
# yet" while all eight were live. A manifest can only describe its own side of
# a binding; the peer list lived as hard-coded constants inside the libraries
# that bind each channel. Part B pins the roster, and pins it AGAINST those
# constants: a roster that can drift from the code that does the binding is
# worse than none, because it would describe a mesh that isn't there.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
ROOT="$(cd "$APP/.." && pwd)"                    # → cloud-u-android
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

BJ="$APP/build.json"
BROWSER="$ROOT/ac_cloud-browser/app/src/main"
NSC="$BROWSER/res/xml/network_security_config.xml"
FRAG="$APP/app/src/main/java/com/diegonmarcos/superapp/devcontrol/DevControlFragment.kt"
GRADLE="$APP/app/build.gradle"

# Code only — a rule satisfied by a comment mentioning it is not satisfied.
code() { grep -vE '^[[:space:]]*(//|\*|/\*|<!--)' "$1" 2>/dev/null; }
has()  { code "$2" | grep -qF -- "$1" && ok "$3" || bad "$3"; }

echo "== A1: Cloud Browser HAS a network security config, and points at it =="
[ -f "$NSC" ] && ok "res/xml/network_security_config.xml exists" \
  || bad "res/xml/network_security_config.xml is MISSING — this is the whole localhost:8000 bug"
has 'android:networkSecurityConfig="@xml/network_security_config"' \
  "$BROWSER/AndroidManifest.xml" \
  "the manifest's <application> points at it (a config nothing references is inert)"

echo "== A2: the allowance is NARROW, not a blanket =="
# usesCleartextTraffic=true would fix localhost by also permitting plain HTTP
# to every site on the internet, in a browser. That is the wrong fix and the
# tempting one, so it is forbidden here rather than merely undocumented.
if code "$BROWSER/AndroidManifest.xml" | grep -q 'usesCleartextTraffic="true"'; then
    bad "manifest sets usesCleartextTraffic=true — blanket cleartext in a BROWSER"
else
    ok "no blanket usesCleartextTraffic=true"
fi
has 'cleartextTrafficPermitted="false"' "$NSC" \
  "base-config keeps the strict default for everything not named"

echo "== A3: the hosts the owner actually needs =="
# localhost is the reported symptom; the mesh peers and gateways are the rest of
# what he browses. <domain> takes no CIDR, so 10.0.0.x is enumerated host by
# host — this checks the enumeration is complete, since a gap is invisible until
# the day he opens that peer.
for host in localhost 127.0.0.1 192.168.0.1 192.168.1.1 192.168.4.1 192.168.8.1 local lan; do
    grep -qF ">$host</domain>" "$NSC" \
      && ok "cleartext permitted to $host" || bad "$host is not permitted"
done
MISSING=""
for n in 1 2 3 4 5 6 7 8 9 10; do
    grep -qF ">10.0.0.$n</domain>" "$NSC" || MISSING="$MISSING 10.0.0.$n"
done
[ -z "$MISSING" ] && ok "all ten WG mesh peers 10.0.0.1–10 are permitted" \
  || bad "mesh peers not permitted:$MISSING"

echo "== B1: the roster exists and every row is complete =="
# A half-filled row renders as a peer with no verdict, which is exactly the
# "is it broken or was it never wired" ambiguity this whole section exists to
# end. Each row needs a name, a kind, a channel, a permission and at least one
# server — and exactly ONE channel, because kind decides which one is read.
SHAPE=$(python3 -c "
import json
d = json.load(open('$BJ'))
mesh = d['ui'].get('app_mesh')
if not mesh: print('ABSENT'); raise SystemExit
out = []
for e in mesh:
    for k in ('id', 'label', 'kind', 'permission', 'serves', 'note'):
        if not e.get(k): out.append('%s lacks %s' % (e.get('id', '?'), k))
    if e.get('kind') not in ('service', 'provider'):
        out.append('%s kind=%r is neither service nor provider' % (e.get('id'), e.get('kind')))
    want = 'authority' if e.get('kind') == 'provider' else 'action'
    other = 'action' if want == 'authority' else 'authority'
    if not e.get(want): out.append('%s is a %s but declares no %s' % (e['id'], e['kind'], want))
    if e.get(other):    out.append('%s declares both action and authority' % e['id'])
ids = [e['id'] for e in mesh]
if len(set(ids)) != len(ids): out.append('duplicate ids')
print('; '.join(out) or 'ok:%d' % len(mesh))" 2>&1)
case "$SHAPE" in
  ok:*) ok "ui.app_mesh declares ${SHAPE#ok:} well-formed channels" ;;
  ABSENT) bad "ui.app_mesh is absent — the mesh is undeclared again" ;;
  *) bad "$SHAPE" ;;
esac

echo "== B2: the roster cannot drift from the code that does the binding =="
# This is the assertion that makes the roster worth having. TextTools.
# SERVICE_PACKAGES is the list the app actually walks when it binds, and its
# ORDER is preference, not decoration: the first installed server wins. A
# roster listing different packages, or the same two in the other order, would
# describe a mesh that does not exist.
TT="$ROOT/ab_cloud-libs-shared/libs/text-tools/src/main/java/com/diegonmarcos/superapp/texttools/TextTools.kt"
WANT=$(python3 -c "
import re
src = open('$TT').read()
block = re.search(r'SERVICE_PACKAGES\s*=\s*listOf\((.*?)\)', src, re.S).group(1)
print(','.join(re.findall(r'\"([^\"]+)\"', block)))")
GOT=$(python3 -c "
import json
d = json.load(open('$BJ'))
e = [x for x in d['ui']['app_mesh'] if x['id'] == 'text-tools']
print(','.join(e[0]['serves']) if e else 'MISSING')")
[ -n "$WANT" ] && [ "$GOT" = "$WANT" ] \
  && ok "text-tools serves == TextTools.SERVICE_PACKAGES, same order ($GOT)" \
  || bad "text-tools serves '$GOT' but the binder walks '$WANT'"

FT="$ROOT/ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/FleetToken.kt"
FTPKG=$(python3 -c "
import re
print(re.search(r'AUTHORITY_PKG\s*=\s*\"([^\"]+)\"', open('$FT').read()).group(1))")
python3 -c "
import json, sys
d = json.load(open('$BJ'))
e = [x for x in d['ui']['app_mesh'] if x['id'] == 'fleet-token']
sys.exit(0 if e and e[0]['serves'] == ['$FTPKG'] and e[0]['authority'] == '\$pkg.fleet' else 1)" \
  && ok "fleet-token names FleetToken.AUTHORITY_PKG ($FTPKG) and the .fleet authority" \
  || bad "fleet-token disagrees with FleetToken.AUTHORITY_PKG ($FTPKG)"

echo "== B3: every fixed action string exists somewhere in the fleet =="
# A \$pkg placeholder is resolved per server at render time, so only the
# literal actions can be checked statically — but those are the ones a typo
# would silently kill, since a wrong action resolves to nothing and reads as
# "peer absent" rather than "roster wrong".
ACTIONS=$(python3 -c "
import json
d = json.load(open('$BJ'))
for e in d['ui']['app_mesh']:
    a = e.get('action', '')
    if a and '\$pkg' not in a: print(a)")
while read -r act; do
    [ -z "$act" ] && continue
    if grep -rqlF "$act" --include=*.kt --include=*.xml --include=*.aidl "$ROOT" 2>/dev/null; then
        ok "action $act is a real channel in the tree"
    else
        bad "action $act appears nowhere in the fleet source — typo or dead channel"
    fi
done <<<"$ACTIONS"

echo "== B4: the roster is BAKED, or the phone never sees it =="
has 'buildJson.ui.app_mesh' "$GRADLE" "build.gradle reads ui.app_mesh"
has 'UI_APP_MESH_B64' "$GRADLE" "and bakes it as its own BuildConfig constant"

echo "== B5: About ▸ Network renders it, with a live verdict, both halves =="
has 'private fun collectAppMesh' "$FRAG" "collectAppMesh exists"
has 'BuildConfig.UI_APP_MESH_B64' "$FRAG" "it reads the baked roster, not a second hardcoded list"
has 'collectAppMesh(appCtx) to collectSelfIpcContract(appCtx)' "$FRAG" \
  "the IPC Contract section probes the mesh AND this app's own exports"
# Three states, not two. "Installed but publishing nothing" is the state the
# owner's question was actually about — Cloud Writer is the preferred text-tools
# server and deliberately ships no service, so a two-state verdict (there /
# not there) would report the healthy design as a fault.
has 'absent' "$FRAG" "verdict: absent"
has 'channel not published' "$FRAG" "verdict: installed but not publishing"
has 'live' "$FRAG" "verdict: live"
has 'resolveContentProvider' "$FRAG" "providers are resolved as providers"
has 'queryIntentServices' "$FRAG" "services are resolved as services"
has 'replace("\$pkg", pkg)' "$FRAG" "the \$pkg placeholder resolves per serving package"

echo
echo "-- $PASS passed, $FAIL failed"
exit "$FAIL"
