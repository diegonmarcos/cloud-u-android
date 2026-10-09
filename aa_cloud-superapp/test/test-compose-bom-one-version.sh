#!/usr/bin/env bash
# ONE Compose BOM for the whole fleet. A shared lib is a project module: it compiles against the
# BOM ITS build.gradle names, while the app that hosts it runs on the highest version any of its
# modules pulled in. libs:health moved to 2025.11.01, libs:shizuku-adb-debug-tools stayed on
# 2025.01.00, and the SuperApp's ADB Shell page died with NoSuchMethodError FlowRow(...): an
# experimental API has no binary compatibility across Compose versions, and no compile step
# can see the mismatch. So every `compose-bom:` declaration in the repo must name the same
# version, and so must a build.json that pins it as data. Apps with their own version catalog (forks:
# mail, matrix, notes, vault) are separate builds sharing no Compose module, so they are out of scope.
# Fleet-wide: inside ship-cloud-superapp this is a warning (it reads outside the app);
# compose-bom-guard.yml runs it fatal.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok   $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL $1"; }

echo "== one compose-bom version"
lines=$(cd "$ROOT" && git grep -n 'compose-bom:[0-9]' -- '*build.gradle' '*build.gradle.kts' 2>/dev/null | grep -v ':[[:space:]]*//' || true)
[ -n "$lines" ] || lines=$(grep -rn 'compose-bom:[0-9]' --include=build.gradle --include=build.gradle.kts "$ROOT" 2>/dev/null | grep -v '/build/' | grep -v ':[[:space:]]*//' || true)
# A build.json that pins the BOM as data (ac_cloud-writer: toolchain.compose_bom, restated by its gradle) is a declaration too.
pins=$(cd "$ROOT" && git grep -n '"compose_bom": *"[0-9]' -- '*build.json' 2>/dev/null | sed 's/"compose_bom": *"\([0-9.]*\)"/compose-bom:\1/' || true)
[ -n "$pins" ] && lines="$lines
$pins"
versions=$(echo "$lines" | grep -o 'compose-bom:[0-9][0-9.]*' | sort -u)
n=$(echo "$versions" | grep -c .)
total=$(echo "$lines" | grep -c .)
[ "$total" -ge 10 ] && ok "$total declarations found across apps and libs" || bad "only $total compose-bom declarations found — the scan walks nothing"
if [ "$n" -eq 1 ]; then ok "every declaration names ${versions#compose-bom:}"
else bad "$n different Compose BOM versions in the fleet:"; for v in $versions; do echo "       $v: $(echo "$lines" | grep -c "$v") file(s), e.g. $(echo "$lines" | grep "$v" | head -1 | cut -d: -f1)"; done; fi

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
