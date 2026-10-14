#!/usr/bin/env bash
# The fleet IPC guard (#ipc, cloud-android-ipc-mesh.py) must go RED for each way the rule
# "app to app on one phone is IPC, never loopback HTTP" can be broken, and stay green on the
# real tree. A guard that cannot fail reads as enforcement and is worse than none.
#
#   I3 http   a fixture holding the real allow-list's files plus ONE planted file per case:
#             an OkHttp-style URL to a sibling's debug port, a raw socket to 127.0.0.1, the
#             version-skew rung called from a new file, and one more shape in an allowed
#             file each go red; an allowed file that drops below its count goes red too
#   I2 door   on a copy of libs:devtools: the provider unexported, its permission removed,
#             call() no longer handed to FleetIpc.serve, and serve() no longer re-checking
#             the caller each go red
#   I1 gen    the real registry with one endpoint dropped is reported stale
#
# Run from anywhere: bash 1_cicd/src/scripts/test/ipc-mesh-guard.test.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-ipc-mesh.py"
ALLOW="$ROOT/1_cicd/src/data/ipc-http-allow.json"
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
fail=0
ok()  { printf 'ok    %s\n' "$1"; }
bad() { printf 'FAIL  %s\n' "$1"; fail=1; }

# ── I3: the real allow-list's world, nothing else ───────────────────────────
FX="$W/fx"
mkdir -p "$FX/1_cicd/src/data" "$FX/1_cicd/src/scripts"
cp "$ALLOW" "$FX/1_cicd/src/data/"
cp "$ROOT"/1_cicd/src/scripts/cloud-android-ipc-mesh.py "$ROOT"/1_cicd/src/scripts/cloud_android_lib_closure.py "$FX/1_cicd/src/scripts/"
python3 - "$ALLOW" "$ROOT" "$FX" <<'PY'
import json, os, shutil, sys
allow, root, fx = sys.argv[1:4]
for f in json.load(open(allow))["files"]:
    if f.startswith("_"): continue
    os.makedirs(os.path.dirname(os.path.join(fx, f)), exist_ok=True)
    shutil.copy(os.path.join(root, f), os.path.join(fx, f))
PY
run_http() { python3 "$FX/1_cicd/src/scripts/cloud-android-ipc-mesh.py" http "$FX" >"$W/out" 2>&1; }
if run_http; then ok "I3 the fixture (the allow-list's own files) is green"; else bad "I3 the clean fixture is red:"; cat "$W/out"; fi

PL="$FX/ac_cloud-news/app/src/main/java/com/diegonmarcos/cloudnews/IpcGuardProbe.kt"
mkdir -p "$(dirname "$PL")"
plant() {  # plant <label> <kotlin body>
  printf 'package x\nimport java.net.*\nobject P {\n%s\n}\n' "$2" >"$PL"
  grep -q . "$PL" || { bad "plant did not land: $1"; return; }
  if run_http; then bad "I3 NOT caught: $1"; else ok "I3 caught: $1"; fi
  rm -f "$PL"
}
plant "a sibling's debug API over HttpURLConnection" '    fun f() = URL("http://127.0.0.1:38154/api/system/info").openConnection()'
plant "a raw socket to a sibling's port"            '    fun f() = Socket().connect(InetSocketAddress("127.0.0.1", 38156), 400)'
plant "a localhost ws:// client"                    '    fun f() = "ws://localhost:38080/x"'
plant "the version-skew rung called from a new file" '    fun f() = com.diegonmarcos.superapp.devtools.FleetIpcWire.httpGet(38154, "/api/docs", null)'

SM="$FX/ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreMesh.kt"
cp "$SM" "$W/sm.bak"
printf '\nobject Extra { fun f() = java.net.InetSocketAddress("127.0.0.1", 1) }\n' >>"$SM"
if run_http; then bad "I3 NOT caught: one more loopback client in an allowed file"; else ok "I3 caught: one more loopback client in an allowed file"; fi
cp "$W/sm.bak" "$SM"
python3 - "$SM" <<'PY'
import sys; p = sys.argv[1]; s = open(p).read()
open(p, "w").write(s.replace("FleetIpcWire.httpGet(", "FleetIpcWire.ignored(", 1))
PY
if run_http && ! grep -q 'lower it' "$W/out"; then bad "I3 NOT caught: an allowed file dropped below its count (the ratchet must shrink)"
else ok "I3 caught: an allowed file below its count must lower it"; fi
cp "$W/sm.bak" "$SM"

# ── I2: the door, on a copy of libs:devtools ────────────────────────────────
DT="ab_cloud-libs-shared/libs/devtools"
mkdir -p "$FX/$DT/src/main"
cp -r "$ROOT/$DT/src/main/AndroidManifest.xml" "$ROOT/$DT/src/main/java" "$FX/$DT/src/main/"
run_door() { python3 "$FX/1_cicd/src/scripts/cloud-android-ipc-mesh.py" door "$FX" >"$W/out" 2>&1; }
if run_door; then ok "I2 the real door is green"; else bad "I2 the real door is red:"; cat "$W/out"; fi
SRC="$FX/$DT/src/main/java/com/diegonmarcos/superapp/devtools"
mutate() {  # mutate <label> <file> <python expr over s>
  cp "$2" "$W/m.bak"
  python3 -c "import sys; p=sys.argv[1]; s=open(p).read(); t=$3; open(p,'w').write(t); sys.exit(0 if t != s else 3)" "$2" \
    || { bad "mutation did not apply: $1"; cp "$W/m.bak" "$2"; return; }
  if run_door; then bad "I2 NOT caught: $1"; else ok "I2 caught: $1"; fi
  cp "$W/m.bak" "$2"
}
mutate "the door provider is not exported" "$FX/$DT/src/main/AndroidManifest.xml" \
  "s.replace('android:name=\"com.diegonmarcos.superapp.devtools.FleetTokenProvider\"\n            android:authorities=\"\${applicationId}.fleet\"\n            android:exported=\"true\"', 'android:name=\"com.diegonmarcos.superapp.devtools.FleetTokenProvider\"\n            android:authorities=\"\${applicationId}.fleet\"\n            android:exported=\"false\"')"
mutate "the door provider lost its signature permission" "$FX/$DT/src/main/AndroidManifest.xml" \
  "s.replace('android:exported=\"true\"\n            android:permission=\"com.diegonmarcos.cloud.permission.CONSTELLATION_DATA\" />', 'android:exported=\"true\" />', 1)"
mutate "the MESH_MEMBER visibility query is gone" "$FX/$DT/src/main/AndroidManifest.xml" \
  "s.replace('<action android:name=\"com.diegonmarcos.cloud.action.MESH_MEMBER\" />', '', 1)"
mutate "call() no longer goes to FleetIpc.serve" "$SRC/FleetTokenProvider.kt" "s.replace('FleetIpc.serve(', 'FleetIpc.other(')"
mutate "serve() no longer re-checks the caller" "$SRC/FleetIpc.kt" \
  "s.replace('ctx.checkCallingPermission(FleetToken.PERMISSION) == PackageManager.PERMISSION_GRANTED', 'true')"
mutate "serve() skips the gate" "$SRC/FleetIpc.kt" "s.replace('FleetIpcWire.gate(granted) ?: ', '')"

# ── I1: a stale registry on the real tree ───────────────────────────────────
REG="$ROOT/aa_cloud-superapp/data/ipc-mesh.json"
python3 - "$REG" "$W/stale.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])); d["members"][0]["endpoints"] = d["members"][0]["endpoints"][1:]
open(sys.argv[2], "w").write(json.dumps(d, indent=1, ensure_ascii=False) + "\n")
PY
if python3 "$GUARD" check "$ROOT" --registry "$W/stale.json" >"$W/out" 2>&1; then bad "I1 NOT caught: a registry missing an endpoint"
elif grep -q '^FAIL I1' "$W/out"; then ok "I1 caught: a registry missing an endpoint is stale"
else bad "I1 red, but not for staleness:"; cat "$W/out"; fi

[ "$fail" = 0 ] && echo "ALL GREEN - the IPC guard fails on every planted break" || echo "RED"
exit "$fail"
