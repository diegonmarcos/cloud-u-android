#!/usr/bin/env bash
# ╔════════════════════════════════════════════════════════════════════╗
# ║ mesh-source-guard.test — prove the guard FAILS when an app drops out ║
# ║ of the fleet mesh, and passes on the real tree                      ║
# ╚════════════════════════════════════════════════════════════════════╝
#
# #753. A gate only ever watched passing is indistinguishable from `exit 0`.
# This copies the files the guard reads (git-tracked gradle scripts, patches,
# ship workflows, the fleet roster, core's and devtools' manifests), requires a
# pass on the copy, then breaks one property at a time, proves the break
# landed, and requires a FAIL naming the app or the manifest it broke.
#
# python3, git and coreutils only; no network, no build.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-mesh-source-guard.py"
CORE_MF=ab_cloud-libs-shared/libs/core/src/main/AndroidManifest.xml
DEV_MF=ab_cloud-libs-shared/libs/devtools/src/main/AndroidManifest.xml
FLEET=aa_cloud-superapp/data/constellation-fleet.json
WRITER=ac_cloud-writer/app/build.gradle
MAIL=ac_cloud-mail/app/build.gradle.kts
CORE=ab_cloud-libs-shared/libs/core/build.gradle
WRITER_WF=.github/workflows/ship-cloud-writer.yml
for f in "$GUARD" "$ROOT/$CORE_MF" "$ROOT/$DEV_MF" "$ROOT/$FLEET" "$ROOT/$WRITER" "$ROOT/$MAIL" "$ROOT/$CORE" "$ROOT/$WRITER_WF"; do
    [ -f "$f" ] || { echo "ERROR missing source: $f — this test is unrun, not passing"; exit 1; }
done

FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

stage() {
    rm -rf "$WORK/t"; mkdir -p "$WORK/t"
    (cd "$ROOT" && { git ls-files -z -- '*.gradle' '*.gradle.kts' '*.patch' '.github/workflows/ship-*.yml'
                     printf '%s\0' "$FLEET" "$CORE_MF" "$DEV_MF"; } | xargs -0 cp --parents -t "$WORK/t")
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
else fail "unbroken copy is red (rc=$rc)"; printf '%s\n' "$out" | grep GAP; fi

# writer links core directly and nothing else that reaches it; mail links no
# mesh lib at all and is a member only through updater (-> core, and again via
# shizuku-adb-debug-tools -> core). Apps with a second route stay members when
# one is cut, so each mutation targets an app whose ONLY route it removes.
mutate "writer's core dependency commented out" "$WRITER" \
    "s=s.replace(\"    implementation project(':libs:core')\", \"    // implementation project(':libs:core')\", 1)" \
    "writer           G1 dependency closure lacks libs:core, libs:devtools"
mutate "mail drops updater, its only route to core" "$MAIL" \
    "s=s.replace('    implementation(project(\":libs:updater\"))', '', 1)" \
    "mail             G1 dependency closure lacks libs:core, libs:devtools"
mutate "core stops exporting devtools" "$CORE" \
    "s=s.replace(\"api project(':libs:devtools')\", '', 1)" \
    "G1 dependency closure lacks libs:devtools"
mutate "writer's ship workflow stops watching core" "$WRITER_WF" \
    "s=s.replace('      - \"ab_cloud-libs-shared/libs/core/**\"\n', '', 1)" \
    "writer           G2 ship-cloud-writer.yml does not watch ab_cloud-libs-shared/libs/core/**"
mutate "core stops requesting CONSTELLATION_DATA" "$CORE_MF" \
    "s=re.sub(r'<uses-permission[^>]*CONSTELLATION_DATA\"\s*/>', '', s, count=1)" \
    "libs:core no longer REQUESTS CONSTELLATION_DATA"
mutate "devtools drops its <queries>" "$DEV_MF" \
    "s=re.sub(r'<queries>.*?</queries>', '', s, count=1, flags=re.S)" \
    "libs:devtools lost <queries> MESH_MEMBER"
mutate "a fleet app row points at a dir that is not there" "$FLEET" \
    "d=json.loads(s); [r.update(repo_url=r['repo_url']+'-gone') for r in d['apps'] if r['id']=='writer']; s=json.dumps(d)" \
    "writer           source dir"

[ "$FAILURES" -eq 0 ] && echo "mesh-source-guard.test: OK" || { echo "mesh-source-guard.test: $FAILURES FAILED"; exit 1; }
