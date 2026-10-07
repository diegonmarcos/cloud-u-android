#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ fleet-config-guard.test — prove the guard FAILS when an app keeps ║
# ║ state the manifest does not declare, and passes on the real fleet ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# #783. A guard only ever watched succeeding is indistinguishable from
# `exit 0`. The green case is the real repository; every mutation runs on a
# small fixture (one app, one lib, their manifest and roster, the REAL scan
# policy) and must turn it red for the named reason:
#   M1  an app opens a SharedPreferences file nobody declared       -> C1
#   M2  a lib opens an EncryptedSharedPreferences nobody declared   -> C1
#   M3  a new Preferences DataStore                                 -> C1
#   M4  a store moves to another module (used_by is stale)          -> C2
#   M5  a declared store no code opens any more                     -> C3
#   M6  a class outside the vocabulary / a store with no doc        -> C4
#   M7  a store name built at runtime with no `resolve` entry       -> C5
#   M8  a fleet app with no `apps` entry                            -> C6
#   M9  a key override that WIDENS a config store                   -> C7
#   M10 an empty checkout                                           -> C8
#   M11 an encrypted store declared as plain prefs                  -> C9
#   M12 a helper-opened store whose helper stopped passing its name -> C3
#   M13 the human matrix edited by hand (or left stale)              -> C10
#   M14 an app's libs drift from the libs its build compiles in    -> C11
# Controls stay green: a comment naming getSharedPreferences, and two
# companion objects in one file each declaring `FILE`.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-fleet-config-guard.py"
POLICY="$ROOT/1_cicd/src/data/fleet-config-guard.json"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

MANIFEST_REL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["manifest"])' "$POLICY")"
ROSTER_REL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["roster"])' "$POLICY")"

fixture() {   # $1 = dir: one app (ac_cloud-demo), one lib (libs/demo), manifest + roster
  local t="$1"
  mkdir -p "$t/ac_cloud-demo/app/src/main/java/x" "$t/ab_cloud-libs-shared/libs/demo/src/main/java/y" \
           "$t/$(dirname "$MANIFEST_REL")" "$t/$(dirname "$ROSTER_REL")"
  cat > "$t/ac_cloud-demo/app/src/main/java/x/Prefs.kt" <<'EOF'
object Prefs {
    // getSharedPreferences("commented_out", 0) is not a store
    private const val FILE = "demo_settings"
    fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
object Status {
    private const val FILE = "demo_status"
    fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
EOF
  cat > "$t/ab_cloud-libs-shared/libs/demo/src/main/java/y/Creds.kt" <<'EOF'
class Creds(ctx: Context) {
    private val prefs = EncryptedSharedPreferences.create(ctx, "demo_creds", key, A, B)
    private val helper = Helper(ctx, "demo_helped")
}
EOF
  python3 - "$t/$MANIFEST_REL" "$t/$ROSTER_REL" <<'PY'
import json, sys
json.dump({"classes": {"config": "", "secret": "", "device": "", "content": ""}, "migrate": ["config", "secret"],
  "kinds": {"prefs": "", "encrypted": "", "datastore": "", "room": "", "file": ""},
  "stores": {
    "demo_settings": {"kind": "prefs", "class": "config", "doc": "the demo's settings", "used_by": ["ac_cloud-demo"], "keys": {"install_id": "device"}},
    "demo_status": {"kind": "prefs", "class": "device", "doc": "a status cursor", "used_by": ["ac_cloud-demo"]},
    "demo_creds": {"kind": "encrypted", "class": "secret", "doc": "a token", "used_by": ["lib-demo"]},
    "demo_helped": {"kind": "prefs", "class": "config", "doc": "opened through a helper", "used_by": ["lib-demo"],
                    "helper": "ab_cloud-libs-shared/libs/demo/src/main/java/y/Creds.kt"}},
  "resolve": {}, "apps": {"demo": {"package": "x.demo", "module": "ac_cloud-demo", "libs": [], "items": []}}, "libs": {}},
  open(sys.argv[1], "w"), indent=1)
json.dump({"apps": [{"id": "demo", "package": "x.demo", "kind": "app"}, {"id": "lib-demo", "package": "x.lib", "kind": "lib"}]},
  open(sys.argv[2], "w"))
PY
  python3 "$GUARD" "$t" "$POLICY" --matrix >/dev/null
}

mutate_manifest() {   # $1 = fixture, $2 = python statement over `m`
  python3 - "$1/$MANIFEST_REL" "$2" <<'PY'
import json, sys
p = sys.argv[1]; m = json.load(open(p)); exec(sys.argv[2]); json.dump(m, open(p, "w"), indent=1)
PY
}

expect() {    # $1 = name, $2 = want rc (0|1), $3 = repo, [$4 = code the output must name]
  local out rc
  out="$(python3 "$GUARD" "$3" "$POLICY" 2>&1)"; rc=$?
  if [ "$rc" -ne "$2" ]; then fail "$1 (rc=$rc, want $2): $out"; return; fi
  if [ -n "${4:-}" ] && ! grep -qF -- "$4" <<<"$out"; then fail "$1 (red, but not for $4): $out"; return; fi
  ok "$1"
}

echo "== the real fleet =="
expect "the real tree is green" 0 "$ROOT"

echo "== control: the fixture is green (a comment is not a store; nearest FILE wins) =="
G="$WORK/green"; fixture "$G"
expect "fixture green" 0 "$G"

echo "== mutations: each must be RED =="
M="$WORK/m1"; fixture "$M"
printf 'object Extra { fun p(ctx: Context) = ctx.getSharedPreferences("demo_undeclared", 0) }\n' > "$M/ac_cloud-demo/app/src/main/java/x/Extra.kt"
expect "M1 an undeclared prefs file" 1 "$M" "C1 store 'demo_undeclared'"

M="$WORK/m2"; fixture "$M"
printf 'class More(ctx: Context) { val p = EncryptedSharedPreferences.create(ctx, "demo_tokens", k, A, B) }\n' > "$M/ab_cloud-libs-shared/libs/demo/src/main/java/y/More.kt"
expect "M2 an undeclared encrypted store in a lib" 1 "$M" "C1 store 'demo_tokens'"

M="$WORK/m3"; fixture "$M"
printf 'val Context.ds by preferencesDataStore(name = "demo_ds")\n' > "$M/ac_cloud-demo/app/src/main/java/x/Ds.kt"
expect "M3 an undeclared DataStore" 1 "$M" "C1 store 'datastore:demo_ds'"

M="$WORK/m4"; fixture "$M"; mutate_manifest "$M" 'm["stores"]["demo_settings"]["used_by"] = ["lib-demo"]'
expect "M4 used_by is stale" 1 "$M" "C2 store 'demo_settings'"

M="$WORK/m5"; fixture "$M"; mutate_manifest "$M" 'm["stores"]["demo_gone"] = {"kind": "prefs", "class": "device", "doc": "x", "used_by": ["ac_cloud-demo"]}'
expect "M5 a stale declaration" 1 "$M" "C3 store 'demo_gone'"

M="$WORK/m6"; fixture "$M"; mutate_manifest "$M" 'm["stores"]["demo_status"]["class"] = "maybe"; m["stores"]["demo_settings"]["doc"] = ""'
expect "M6a a class outside the vocabulary" 1 "$M" "C4 store 'demo_status': class 'maybe'"
expect "M6b a store with no doc" 1 "$M" "C4 store 'demo_settings' has no doc"

M="$WORK/m7"; fixture "$M"
printf 'class Dyn(ctx: Context, n: String) { val p = ctx.getSharedPreferences(n + "_x", 0) }\n' > "$M/ac_cloud-demo/app/src/main/java/x/Dyn.kt"
expect "M7 a runtime-built name with no resolve" 1 "$M" "C5 ac_cloud-demo/app/src/main/java/x/Dyn.kt"

M="$WORK/m8"; fixture "$M"; mutate_manifest "$M" 'del m["apps"]["demo"]'
expect "M8 a fleet app with no apps entry" 1 "$M" "C6 fleet app 'demo'"

M="$WORK/m9"; fixture "$M"; mutate_manifest "$M" 'm["stores"]["demo_settings"]["keys"]["theme"] = "config"'
expect "M9 an override that widens" 1 "$M" "C7 store 'demo_settings' key 'theme'"

M="$WORK/m10"; mkdir -p "$M"; cp -r "$G/$(echo "$MANIFEST_REL" | cut -d/ -f1)" "$M/"; mkdir -p "$M/$(dirname "$ROSTER_REL")"; cp "$G/$ROSTER_REL" "$M/$ROSTER_REL"
rm -rf "$M/ab_cloud-libs-shared/libs/demo"
find "$M" -name '*.kt' -delete
expect "M10 nothing to scan" 1 "$M" "C8"

M="$WORK/m11"; fixture "$M"; mutate_manifest "$M" 'm["stores"]["demo_creds"]["kind"] = "prefs"'
expect "M11 an encrypted store declared plain" 1 "$M" "C9 store 'demo_creds'"

M="$WORK/m12"; fixture "$M"; sed -i 's/"demo_helped"/name/' "$M/ab_cloud-libs-shared/libs/demo/src/main/java/y/Creds.kt"
expect "M12 a helper that no longer passes the name" 1 "$M" "C3 store 'demo_helped': its helper"

M="$WORK/m13"; fixture "$M"; MATRIX_REL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["matrix"])' "$POLICY")"
printf '| a row someone typed |\n' >> "$M/$MATRIX_REL"
expect "M13 a hand-edited matrix" 1 "$M" "C10"

M="$WORK/m14"; fixture "$M"; CONSUMERS_REL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["lib_consumers"])' "$POLICY")"
mkdir -p "$M/$(dirname "$CONSUMERS_REL")"
printf '{"build_time": [{"id": "demo", "compiled_by": ["ac_cloud-demo"]}]}\n' > "$M/$CONSUMERS_REL"
expect "M14 an app's libs miss a lib its build compiles in" 1 "$M" "C11 apps['demo'].libs"
mutate_manifest "$M" 'm["apps"]["demo"]["libs"] = ["lib-demo"]'
python3 "$GUARD" "$M" "$POLICY" --matrix >/dev/null
expect "M14 control: the same libs as the build is green" 0 "$M"

if [ "$FAILURES" -ne 0 ]; then echo "fleet-config-guard.test: $FAILURES failure(s)"; exit 1; fi
echo "fleet-config-guard.test: all green"
