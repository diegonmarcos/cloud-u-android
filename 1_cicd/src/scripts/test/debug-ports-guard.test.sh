#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════╗
# ║ debug-ports-guard.test — prove the #792 port guard FAILS on each rule ║
# ║ it holds, and passes on the real tree                                 ║
# ╚══════════════════════════════════════════════════════════════════════╝
#
# Copies the five files the guard reads, requires a pass on the copy, then
# breaks one property at a time, proves the break landed, and requires a FAIL
# naming that rule. python3 and coreutils only; no network, no build.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-debug-ports-guard.py"
FLEET=aa_cloud-superapp/data/constellation-fleet.json
PORTS=ab_cloud-libs-shared/libs/devtools/debug-ports.json
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
    (cd "$ROOT" && cp --parents -t "$WORK/t" "$FLEET" "$PORTS" "$GRADLE" "$SERVER" "$PROBE")
}

# mutate <label> <file> <python-edit-of-s> <expected-substring-in-guard-output>
mutate() {
    stage
    local before after out
    before="$(cat "$WORK/t/$2")"
    python3 -c "import re,json,sys; p=sys.argv[1]; s=open(p).read(); $3; open(p,'w').write(s)" "$WORK/t/$2"
    after="$(cat "$WORK/t/$2")"
    if [ "$before" = "$after" ]; then fail "$1: the mutation did not land — the fixture moved"; return; fi
    out="$(python3 "$GUARD" "$WORK/t")"; local rc=$?
    if [ "$rc" -eq 1 ] && grep -qF -- "$4" <<<"$out"; then ok "$1 goes red ($4)"
    else fail "$1 stayed green or named the wrong thing (rc=$rc)"; printf '%s\n' "$out" | tail -5; fi
}

stage
out="$(python3 "$GUARD" "$WORK/t")"; rc=$?
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
mutate "the range shrinks back to fifty ports" "$PORTS" \
    "$J; d['range']=[38140,38239]; $W" \
    "P3 only"
mutate "the server goes back to first-free" "$SERVER" \
    "s=s.replace('val sock = bindOwn(app.packageName)', 'val sock = bindFirstFree()', 1)" \
    "P4 AppDebugServer.start does not bind through bindOwn"
mutate "bindOwn stops trying the assigned port" "$SERVER" \
    "s=s.replace('val own = portOf(pkg)', 'val own: Int? = null', 1)" \
    "P4 AppDebugServer.bindOwn no longer binds portOf(pkg) first"
mutate "devtools stops baking the table" "$GRADLE" \
    "s=s.replace(\"'debug-ports.json'\", \"'ports.json'\", 1)" \
    "P4 libs/devtools/build.gradle no longer bakes debug-ports.json"
mutate "the probe goes back to a blind sweep" "$PROBE" \
    "s=s.replace('AppDebugServer.portOf(', 'AppDebugServer.noSuch(')" \
    "P4 the Apps Mesh probe no longer reads the port table"

echo
if [ "$FAILURES" -eq 0 ]; then echo "== RESULT(#792 debug-ports guard): every rule proven red =="; exit 0; fi
echo "== RESULT(#792 debug-ports guard): $FAILURES check(s) failed =="; exit 1
