#!/usr/bin/env bash
# Apps Mesh > Identity: every fleet app's Linux identity has ONE declaration,
# aa_cloud-superapp/data/fleet-identity.json (a group's value read from the file that owns it,
# ac_cloud-termux/build.json::shared_store.shared_user_id). This holds the manifests to it:
#   U1 every declared group resolves to a value, and its members are fleet apps (data/ipc-mesh.json)
#   U2 each group member's manifest sets android:sharedUserId to exactly that value
#      (a ${placeholder} is resolved through the module's manifestPlaceholders)
#   U3 no manifest outside a group in force sets android:sharedUserId, except to its own package
#      (the terminals' ${TERMUX_PACKAGE_NAME} today: that shares nothing). A `pending` group whose value
#      does not resolve yet is not in force.
#   U4 the page exists and is reachable: build.json apps-mesh.tabs names identity, SectionPages
#      builds FleetIdentityFragment for it, and FleetIdentityPageTest keeps its tests
# Then each planted defect must turn it red.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"; ROOT="$(cd "$APP/.." && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

# A mirror holding only what the check reads: every module manifest, the gradle files that may
# carry a placeholder, and the declarations. Read from the index when a sparse checkout lacks a file.
mirror() {  # mirror <dest>
  local d="$1" f
  mkdir -p "$d"
  { git -C "$ROOT" ls-files --cached --others --exclude-standard -- '*src/main/AndroidManifest.xml' '*/app/build.gradle' \
      'aa_cloud-superapp/data/fleet-identity.json' 'aa_cloud-superapp/data/ipc-mesh.json' \
      'aa_cloud-superapp/build.json' \
      'aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt' \
      'aa_cloud-superapp/app/src/test/java/com/diegonmarcos/superapp/apps/FleetIdentityPageTest.kt'
    python3 -c "import json,sys; [print(g['shared_user_id']['from']) for g in json.load(open(sys.argv[1])).get('groups',[]) if isinstance(g.get('shared_user_id'),dict)]" \
      "$APP/data/fleet-identity.json"; } | sort -u | while read -r f; do
    mkdir -p "$d/$(dirname "$f")"
    if [ -f "$ROOT/$f" ]; then cp "$ROOT/$f" "$d/$f"; else git -C "$ROOT" show ":$f" > "$d/$f" 2>/dev/null || rm -f "$d/$f"; fi
  done
}

check() {  # check <root>
  python3 - "$1" <<'PY'
import json, os, re, sys
root = sys.argv[1]
def rd(p):
    with open(os.path.join(root, p)) as f: return f.read()
fails = []
decl = json.loads(rd('aa_cloud-superapp/data/fleet-identity.json'))
members = json.loads(rd('aa_cloud-superapp/data/ipc-mesh.json'))['members']
dir_of = {m['pkg']: m.get('dir', '') for m in members}
want = {}   # manifest path -> value
for g in decl.get('groups', []):
    ref, val = g.get('shared_user_id'), None
    if isinstance(ref, str): val = ref
    elif isinstance(ref, dict):
        try:
            cur = json.loads(rd(ref['from']))
            for k in ref['key'].split('.'): cur = cur.get(k) if isinstance(cur, dict) else None
            val = cur if isinstance(cur, str) else None
        except FileNotFoundError: val = None
    if len(g.get('members', [])) < 2: fails.append('U1 group %s shares a uid with nobody: %r' % (g.get('id'), g.get('members')))
    if not val:
        if not g.get('pending'): fails.append('U1 group %s has no shared_user_id (%r)' % (g.get('id'), ref))
        continue   # pending and unresolved: not in force, its members are on their own uid
    for pkg in g.get('members', []):
        d = dir_of.get(pkg)
        if not d: fails.append('U1 group %s member %s is not a fleet app (ipc-mesh.json)' % (g['id'], pkg)); continue
        want['%s/app/src/main/AndroidManifest.xml' % d] = (g['id'], pkg, val)
own = {}   # manifest -> the fleet packages built from that module dir (a lib can share an app's dir)
for p, d in dir_of.items():
    if d: own.setdefault('%s/app/src/main/AndroidManifest.xml' % d, set()).add(p)
seen = {}
for dp, _, fs in os.walk(root):
    for f in fs:
        if f != 'AndroidManifest.xml' or not dp.endswith('src/main'): continue
        path = os.path.relpath(os.path.join(dp, f), root)
        m = re.search(r'<manifest\b[^>]*?android:sharedUserId="([^"]*)"', open(os.path.join(dp, f)).read(), re.S)
        if not m: continue
        v = m.group(1)
        for ph in re.findall(r'\$\{(\w+)\}', v):
            gradle = os.path.join(root, os.path.dirname(os.path.dirname(os.path.dirname(path))), 'build.gradle')
            got = re.search(r'manifestPlaceholders(?:\.%s\s*=|\s*\[[^\]]*%s\s*:)\s*["\']([^"\']+)' % (ph, ph), open(gradle).read()) if os.path.exists(gradle) else None
            v = v.replace('${%s}' % ph, got.group(1) if got else '?')
        seen[path] = v
for path, (gid, pkg, val) in want.items():
    if seen.get(path) != val:
        fails.append('U2 %s (%s, group %s) sharedUserId is %r, declared %r' % (path, pkg, gid, seen.get(path), val))
for path, v in seen.items():
    if path not in want and v not in own.get(path, ()): fails.append('U3 %s sets sharedUserId=%r but no declared group names it' % (path, v))
pages = next(s for s in json.loads(rd('aa_cloud-superapp/build.json'))['ui']['sections'] if s['id'] == 'config')['pages']
am = next((p for p in pages if p['id'] == 'apps-mesh'), {})
if 'identity' not in am.get('tabs', []) or am.get('tabs', []).index('identity') != am['tabs'].index('ipc') + 1:
    fails.append('U4 apps-mesh.tabs does not put identity beside ipc: %r' % am.get('tabs'))
sp = rd('aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt')
if not re.search(r'pageId == "identity" +-> com\.diegonmarcos\.superapp\.appstore\.FleetIdentityFragment\(\)', sp):
    fails.append('U4 SectionPages does not build FleetIdentityFragment for identity')
try: ut = rd('aa_cloud-superapp/app/src/test/java/com/diegonmarcos/superapp/apps/FleetIdentityPageTest.kt')
except FileNotFoundError: ut = ''
for t in ('I1 the Identity tab renders a row per fleet app', 'I2 the shared terminal group is shown',
          'I3 a live uid that disagrees is flagged'):
    if 'fun `%s`()' % t not in ut: fails.append('U4 FleetIdentityPageTest lost: %s' % t)
for f in fails: print('  FAIL: ' + f)
sys.exit(1 if fails else 0)
PY
}

FAIL=0
mirror "$TMP/base"
if check "$TMP/base"; then echo "  PASS: U1-U4 every manifest's sharedUserId is the declared one, and only declared groups set one"
else FAIL=$((FAIL+1)); fi

TERMUX_M="ac_cloud-termux/app/src/main/AndroidManifest.xml"
NIX_M="ac_cloud-nix-on-droid/app/src/main/AndroidManifest.xml"
OTHER_M="$(cd "$TMP/base" && ls ac_cloud-calc/app/src/main/AndroidManifest.xml 2>/dev/null || find . -path '*src/main/AndroidManifest.xml' ! -path "./$TERMUX_M" ! -path "./$NIX_M" | head -1 | sed 's|^\./||')"
while IFS='|' read -r file expr; do
  [ -n "$file" ] || continue
  rm -rf "$TMP/m"; cp -r "$TMP/base" "$TMP/m"
  python3 -c "import sys; p=sys.argv[1]; s=open(p).read(); t=$expr; open(p,'w').write(t); sys.exit(0 if t!=s else 3)" "$TMP/m/$file" \
    || { echo "  FAIL: mutation did not apply: $file"; FAIL=$((FAIL+1)); continue; }
  if check "$TMP/m" >/dev/null 2>&1; then echo "  FAIL: NOT caught: $file ${expr:0:70}"; FAIL=$((FAIL+1))
  else echo "  PASS: caught: $file ${expr:0:70}"; fi
done <<MUT
$OTHER_M|s.replace('<manifest ', '<manifest android:sharedUserId="cld.rogue" ', 1)
$NIX_M|__import__('re').sub(r'android:sharedUserId="[^"]*"', 'android:sharedUserId="cld.other"', s, 1)
aa_cloud-superapp/data/fleet-identity.json|s.replace('"members": ["cld.termux", "cld.termux.nix", "com.diegonmarcos.clouddrive"]', '"members": ["cld.termux"]')
aa_cloud-superapp/data/fleet-identity.json|s.replace('"key": "shared_store.shared_user_id"', '"key": "shared_store.nope"').replace('"pending": true,', '')
aa_cloud-superapp/build.json|s.replace('"tabs": ["apps-mesh-members", "ipc", "identity"]', '"tabs": ["apps-mesh-members", "ipc"]')
aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt|s.replace('appstore.FleetIdentityFragment()', 'appstore.IpcMeshFragment()')
aa_cloud-superapp/app/src/test/java/com/diegonmarcos/superapp/apps/FleetIdentityPageTest.kt|s.replace('fun \`I2 the shared terminal group is shown\`()', 'fun i2()')
MUT
echo "== RESULT(fleet shared uid): $FAIL failed =="
[ "$FAIL" -eq 0 ]
