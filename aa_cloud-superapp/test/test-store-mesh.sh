#!/usr/bin/env bash
# #728 Store ▸ Mesh — the links it draws are the apps' OWN declarations.
#
#   M1  every app's build.json::engines arrives, unchanged, on that app's row of
#       the fleet manifest — and no row carries an engine its app did not declare
#   M2  every link points at a fleet member (a dangling link has no Store row to fix)
#   M3  StoreMesh reads nodes, groups and links off the manifest and names none
#       of the members or packages the links involve
#   M4  the page reaches it: Mesh is on the destination line and renderTab draws it
#
# What it DRAWS (every member is a node, a missing engine is the red link with
# its fix) is asserted by app/src/test/.../StoreMeshTest.kt on the baked fleet.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
FLEET="$APP/data/constellation-fleet.json"
STORE="$ROOT/ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore"
MESH="$STORE/StoreMesh.kt"
PAGE="$STORE/StoreCloudFragment.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$FLEET" "$MESH" "$PAGE"; do
  [ -f "$f" ] || { echo "ERROR: missing $f" >&2; exit 2; }
done

echo "== M1/M2: declared engines == the manifest's links, and every link lands on a member =="
out="$(python3 - "$ROOT" "$FLEET" <<'EOF'
import glob, json, os, sys
root, fleet_path = sys.argv[1], sys.argv[2]
fleet = json.load(open(fleet_path))
rows = {a.get("package"): a for a in fleet["apps"]}
ids = {a["id"] for a in fleet["apps"]}
declared = {}
for bj in sorted(glob.glob(os.path.join(root, "*/build.json")) + glob.glob(os.path.join(root, "*/*/build.json"))):
    try: d = json.load(open(bj))
    except Exception: continue
    eng = {k: v for k, v in (d.get("engines") or {}).items() if k != "_doc" and isinstance(v, dict)}
    pkg = (d.get("android") or {}).get("application_id")
    if eng and pkg: declared[pkg] = (os.path.relpath(bj, root), eng)
print("DECLARING %d" % len(declared))
for pkg, (bj, eng) in declared.items():
    row = rows.get(pkg)
    if row is None: print("M1 %s declares engines but no fleet row has package %s" % (bj, pkg)); continue
    want = sorted((k, v.get("fleet"), v.get("action"), v.get("min_contract")) for k, v in eng.items())
    got = sorted((e.get("binding"), e.get("fleet"), e.get("action"), e.get("min_contract")) for e in row.get("engines", []))
    if want != got: print("M1 fleet row %s carries %s, %s declares %s" % (row["id"], got, bj, want))
for a in fleet["apps"]:
    if a.get("engines") and a.get("package") not in declared:
        print("M1 fleet row %s carries engines its build.json does not declare" % a["id"])
    for e in a.get("engines", []):
        if e.get("fleet") not in ids: print("M2 %s -> %s: no fleet member has that id" % (a["id"], e.get("fleet")))
EOF
)"
echo "$out" | sed 's/^/    /'
n="$(printf '%s\n' "$out" | sed -n 's/^DECLARING //p')"
[ "${n:-0}" -gt 0 ] && ok "$n apps declare engines" || bad "no build.json declares an engine — M1 would verify nothing"
printf '%s\n' "$out" | grep -q '^M1 ' && bad "a fleet row's links differ from its app's build.json::engines" \
  || ok "every fleet row carries exactly its app's declared engines"
printf '%s\n' "$out" | grep -q '^M2 ' && bad "a link points at no fleet member" \
  || ok "every link lands on a fleet member"

echo "== M3: StoreMesh draws from the manifest and names no member =="
grep -qF 'optJSONArray("engines")' "$MESH" && ok "links are read off each fleet row's engines" \
  || bad "StoreMesh does not read the rows' engines"
grep -qF 'optJSONArray("groups")' "$MESH" && ok "nodes are grouped by the declared groups" \
  || bad "StoreMesh does not read the declared groups"
lits="$(jq -r '.apps[] | select(.engines) | (.id, .package, (.engines[] | .fleet, .binding))' "$FLEET" | sort -u)"
[ -n "$lits" ] || bad "no link literals derived — the check below would verify nothing"
for lit in $lits; do
  grep -qF "\"$lit\"" "$MESH" && bad "StoreMesh hardcodes \"$lit\"" || ok "\"$lit\" is not written in StoreMesh"
done
lib_pkgs="$(jq -r '.apps[] | select(.engines) | .engines[].fleet' "$FLEET" | sort -u)"
for id in $lib_pkgs; do
  pkg="$(jq -r --arg id "$id" '.apps[] | select(.id == $id) | .package' "$FLEET")"
  grep -qF "$pkg" "$MESH" && bad "StoreMesh names engine package $pkg" || ok "engine package $pkg is not named in StoreMesh"
done

echo "== M4: Store ▸ Mesh is reachable =="
grep -qF 'controls.page(MESH) + controls.page(PERMS)' "$PAGE" \
  && ok "Mesh sits on the destination line beside Perms" || bad "Mesh is not on the tab bar"
render_tab="$(awk '/private fun renderTab\(/{f=1} f{print} f && /^    }$/{exit}' "$PAGE")"
[ -n "$render_tab" ] || bad "could not isolate renderTab - the next check would verify nothing"
printf '%s' "$render_tab" | grep -qF 'tab == tabs.size + feeds.size -> renderMesh(ctx)' \
  && ok "renderTab draws the mesh at Mesh's index" || bad "renderTab never draws the mesh"

echo
echo "== RESULT(#728 store mesh): $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
