#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════╗
# ║ debug-ports-guard.test — prove the #792 port guard FAILS on each rule ║
# ║ it holds, and passes on the real tree                                 ║
# ╚══════════════════════════════════════════════════════════════════════╝
#
# Copies the files the guard reads (plus every root's build.json and generated
# debug-api.json slice, #796), requires a pass on the copy, then breaks one
# property at a time, proves the break landed, and requires a FAIL naming that
# rule. python3 and coreutils only; no network, no build.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-debug-ports-guard.py"
# The guard imports the generator and the closure module from ITS OWN directory,
# so the copy under test must run from a copied scripts dir that holds all three.
GUARD_COPY_DIR=1_cicd/src/scripts
FLEET=aa_cloud-superapp/data/constellation-fleet.json
PORTS=1_cicd/src/data/debug-ports.json
GEN=1_cicd/src/scripts/cloud-android-mesh-slices-gen.py
CLOSURE=1_cicd/src/scripts/cloud_android_lib_closure.py
GRADLE=ab_cloud-libs-shared/libs/devtools/build.gradle
SERVER=ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/AppDebugServer.kt
PROBE=ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreMesh.kt
for f in "$GUARD" "$ROOT/$FLEET" "$ROOT/$PORTS" "$ROOT/$GRADLE" "$ROOT/$SERVER" "$ROOT/$PROBE"; do
    [ -f "$f" ] || { echo "ERROR missing source: $f — this test is unrun, not passing"; exit 1; }
done

FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

stage() {
    rm -rf "$WORK/t"; mkdir -p "$WORK/t"
    (cd "$ROOT" && cp --parents -t "$WORK/t" $(printf '%s\n' 1_cicd/src/scripts/cloud-android-debug-ports-guard.py "$FLEET" "$PORTS" "$GRADLE" "$SERVER" "$PROBE" "$GEN" "$CLOSURE" \
        $(ls -d a[ac]_*/build.json ab_cloud-libs-shared/*/build.json a_solutions/*/build.json \
                a[ac]_*/debug-api.json ab_cloud-libs-shared/*/debug-api.json 2>/dev/null) \
        $(ls ab_cloud-libs-shared/libs/*/build.gradle) \
        $(find a[ac]_* ab_cloud-libs-shared/lib-apks ab_cloud-libs-shared/keyboard-engines a_solutions -maxdepth 3 \
               \( -name 'settings.gradle' -o -name 'settings.gradle.kts' -o -name 'build.gradle' -o -name 'build.gradle.kts' \) \
               -not -path '*/build/*' 2>/dev/null) | sort -u))
}

# mutate <label> <file> <python-edit-of-s> <expected-substring-in-guard-output>
mutate() {
    stage
    local before after out
    before="$(cat "$WORK/t/$2")"
    python3 -c "import re,json,sys; p=sys.argv[1]; s=open(p).read(); $3; open(p,'w').write(s)" "$WORK/t/$2"
    after="$(cat "$WORK/t/$2")"
    if [ "$before" = "$after" ]; then fail "$1: the mutation did not land — the fixture moved"; return; fi
    out="$(python3 "$WORK/t/$GUARD_COPY_DIR/cloud-android-debug-ports-guard.py" "$WORK/t")"; local rc=$?
    if [ "$rc" -eq 1 ] && grep -qF -- "$4" <<<"$out"; then ok "$1 goes red ($4)"
    else fail "$1 stayed green or named the wrong thing (rc=$rc)"; printf '%s\n' "$out" | tail -5; fi
}

stage
out="$(python3 "$WORK/t/$GUARD_COPY_DIR/cloud-android-debug-ports-guard.py" "$WORK/t")"; rc=$?
if [ "$rc" -eq 0 ]; then ok "unbroken copy passes ($(tail -1 <<<"$out"))"
else fail "unbroken copy is red (rc=$rc)"; printf '%s\n' "$out"; fi

J='d=json.loads(s); P=d["ports"]'
W='s=json.dumps(d,indent=2)'
mutate "the SuperApp loses its port" "$PORTS" \
    "$J; del P['com.diegonmarcos.superapp']; $W" \
    "P1 aa_cloud-superapp (com.diegonmarcos.superapp) has no port"
mutate "a new fleet row arrives without a port" "$FLEET" \
    "d=json.loads(s); d['apps'].append({'id':'zz-new','package':'com.example.zznew'}); s=json.dumps(d)" \
    "P1 zz-new (com.example.zznew) has no port"
mutate "two packages share a port" "$PORTS" \
    "$J; P['com.diegonmarcos.cloudmail_x']=P['com.diegonmarcos.superapp']; $W" \
    "P2 port 38140 is given to both com.diegonmarcos.superapp and com.diegonmarcos.cloudmail_x"
mutate "a port outside the range" "$PORTS" \
    "$J; P['com.diegonmarcos.superapp']=38080; $W" \
    "P2 com.diegonmarcos.superapp :38080 is outside the range"
mutate "the fallback shrinks below the member count" "$PORTS" \
    "$J; d['fallback']=[38380,38389]; $W" \
    "P3 only 10 fallback ports"
mutate "a package is assigned a port inside the fallback sub-range" "$PORTS" \
    "$J; P['com.diegonmarcos.superapp']=38300; $W" \
    "P2 com.diegonmarcos.superapp :38300 is inside the fallback sub-range"
mutate "the fallback leaves the range" "$PORTS" \
    "$J; d['fallback']=[38300,38400]; $W" \
    "P2 \`fallback\` 38300..38400 is not a sub-range"
mutate "a root's slice is stale (its port moved in the table)" "$PORTS" \
    "$J; P['com.diegonmarcos.cloudnews']=38299; $W" \
    "P5 ac_cloud-news/debug-api.json is not what the generator produces"
mutate "a root's slice was hand-edited" "ac_cloud-news/debug-api.json" \
    "d=json.loads(s); d['ports']['com.diegonmarcos.cloudnews']=38299; $W" \
    "P5 ac_cloud-news/debug-api.json is not what the generator produces"
mutate "the fallback scan goes back over the whole range" "$SERVER" \
    "s=s.replace('fallbackPorts(FALLBACK_FIRST, FALLBACK_LAST, PORTS.values)', 'fallbackPorts(PORT_FIRST, PORT_LAST, PORTS.values)', 1)" \
    "P4 AppDebugServer.bindOwn no longer binds portOf(pkg) first"
mutate "the server goes back to first-free" "$SERVER" \
    "s=s.replace('val sock = bindOwn(app.packageName)', 'val sock = bindFirstFree()', 1)" \
    "P4 AppDebugServer.start does not bind through bindOwn"
mutate "bindOwn stops trying the assigned port" "$SERVER" \
    "s=s.replace('val own = portOf(pkg)', 'val own: Int? = null', 1)" \
    "P4 AppDebugServer.bindOwn no longer binds portOf(pkg) first"
mutate "devtools stops baking the root's slice" "$GRADLE" \
    "s=s.replace(\"'debug-api.json'\", \"'ports.json'\", 1)" \
    "P4 libs/devtools/build.gradle no longer bakes the root's debug-api.json"
stage; mkdir -p "$WORK/t/ab_cloud-libs-shared/libs/devtools"; cp "$ROOT/$PORTS" "$WORK/t/ab_cloud-libs-shared/libs/devtools/debug-ports.json"
out="$(python3 "$WORK/t/$GUARD_COPY_DIR/cloud-android-debug-ports-guard.py" "$WORK/t")"; rc=$?
if [ "$rc" -eq 1 ] && grep -qF -- "P5 ab_cloud-libs-shared/libs/devtools/debug-ports.json exists" <<<"$out"; then ok "a copy of the table back inside libs/devtools goes red (P5)"
else fail "a copy of the table inside libs/devtools stayed green (rc=$rc)"; fi
mutate "the probe goes back to a blind sweep" "$PROBE" \
    "s=s.replace('AppDebugServer.portOf(', 'AppDebugServer.noSuch(')" \
    "P4 the Apps Mesh probe no longer reads the port table"

echo
if [ "$FAILURES" -eq 0 ]; then echo "== RESULT(#792 debug-ports guard): every rule proven red =="; exit 0; fi
echo "== RESULT(#792 debug-ports guard): $FAILURES check(s) failed =="; exit 1
