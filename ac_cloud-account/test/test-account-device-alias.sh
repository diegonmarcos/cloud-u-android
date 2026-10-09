#!/usr/bin/env bash
# test-account-device-alias: the S21+ id `galaxy` became `galaxy-s21`; a phone whose Connections still holds the
# legacy id migrates ONCE to the renamed device. Static, no build, no network. The alias table is DATA
# (build.json::ui.account.device_aliases), never written in Kotlin. Checks the real tree, then plants each
# mutation in a scratch copy and requires RED.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PROF="ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile"

check() {
python3 - "$1" "$PROF" <<'PY'
import json, sys
R, PROF = sys.argv[1], sys.argv[2]
fails = 0
def rd(p): return open(f"{R}/{p}", encoding="utf-8").read()
def ok(c, good, bad):
    global fails
    print(("  ok   " if c else "  FAIL ") + (good if c else bad))
    if not c: fails += 1

bj = json.loads(rd("ac_cloud-account/build.json"))
ok(bj["ui"]["account"].get("device_aliases") == {"galaxy": "galaxy-s21"}, "build.json ui.account.device_aliases maps galaxy -> galaxy-s21", "device_aliases missing or wrong")
ap = rd(f"{PROF}/AccountPages.kt")
dv = ap.split("object AccountDevice")[1].split("fun setId")[0]
ok('optJSONObject("device_aliases")' in dv and "UI_ACCOUNT_B64" in dv, "aliases are read from the baked declaration", "aliases are not read from device_aliases")
ok('"galaxy' not in dv, "no device id is written in AccountDevice", "a device id literal is hardcoded in AccountDevice")
res = dv.split("fun resolve")[1].split("fun candidates")[0]
ok("migrated(conn, listingIds(ctx), aliases())" in res, "resolve migrates the Connections id against the vault listing", "resolve never migrates the legacy id")
ok('v.putConnection("device.id", to)' in res and res.index('putConnection("device.id", to)') < res.index("if (conn.isNotBlank())"), "the migrated id is persisted before it is used", "the migrated id is not persisted")
ok("conn in listing" in dv and "aliases[conn]?.takeIf { it in listing }" in dv, "migrates only when the old id is gone and the target is listed", "migration guard missing")
sys.exit(fails)
PY
}

echo "-- real tree --"
check "$ROOT"; REAL=$?

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
FILES="ac_cloud-account/build.json $PROF/AccountPages.kt"
mutate() {  # mutate <label> <file> <old> <new>
  local label="$1" f="$2" old="$3" new="$4" d="$TMP/m"
  rm -rf "$d"; mkdir -p "$d"
  for x in $FILES; do mkdir -p "$d/$(dirname "$x")"; cp "$ROOT/$x" "$d/$x"; done
  python3 - "$d/$f" "$old" "$new" <<'PY' || { echo "  MUTATION NOT APPLIED: $label"; return 1; }
import sys
p, old, new = sys.argv[1:4]
s = open(p, encoding='utf-8').read()
if old not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
  if check "$d" >/dev/null; then echo "  MUTATION SURVIVED: $label"; return 1; fi
  echo "  mutation red: $label"; return 0
}

echo "-- mutations --"
MUT=0
mutate "alias table hardcoded" "$PROF/AccountPages.kt" \
  '?.optJSONObject("device_aliases") ?: return emptyMap()' \
  '?.optJSONObject("device_aliases") ?: return mapOf("galaxy" to "galaxy-s21")' || MUT=1
mutate "alias table dropped from build.json" "ac_cloud-account/build.json" '"galaxy": "galaxy-s21"' '"x": "y"' || MUT=1
mutate "legacy id not migrated" "$PROF/AccountPages.kt" \
  'migrated(conn, listingIds(ctx), aliases())?.let' 'null?.let' || MUT=1
mutate "migrated id not persisted" "$PROF/AccountPages.kt" 'v.putConnection("device.id", to)' 'Unit' || MUT=1
mutate "migrates without the target being listed" "$PROF/AccountPages.kt" 'aliases[conn]?.takeIf { it in listing }' 'aliases[conn]' || MUT=1

[ "$REAL" -eq 0 ] && [ "$MUT" -eq 0 ] && { echo "PASS"; exit 0; }
echo "FAIL (real=$REAL mutations=$MUT)"; exit 1
