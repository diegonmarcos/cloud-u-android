#!/usr/bin/env bash
# ac_cloud-notes tester: the Obsidian-style vault (open a folder, md/canvas/json/html/mermaid,
# the .obsidian reader, the default plugin mapping). No build, no device:
#   1. the generated mirrors (vault-decl.json, docs/vault-plugin-mapping.md) equal build.json::vault
#   2. node tests over test/vault/*.test.mjs (type-stripped TypeScript sources, fixtures under
#      test/fixtures/vault: config JSONs copied from cloud-me-pvt-notes and git-repos-master)
#   3. the native side agrees with the declaration (plugin registered, store address pinned)
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1
fails=0
step() { printf '== %s\n' "$1"; }

step "generated mirrors are current"
python3 scripts/gen-vault-decl.py --check || fails=$((fails+1))

step "vault logic (node)"
node --experimental-strip-types --no-warnings --import ./test/vault/loader.mjs --test "test/vault/*.test.mjs" 2>&1 | tail -40
[ "${PIPESTATUS[0]}" -eq 0 ] || fails=$((fails+1))

step "native side agrees with build.json::vault"
python3 - "$ROOT" <<'PY' || fails=$((fails+1))
import json, os, re, sys
root = sys.argv[1]
fails = 0
def check(ok, msg):
    global fails
    print(('  PASS  ' if ok else '  FAIL  ') + msg)
    if not ok: fails += 1
rd = lambda *p: open(os.path.join(root, *p), encoding='utf-8').read()
v = json.loads(rd('build.json'))['vault']
termux = json.loads(open(os.path.join(root, '..', 'ac_cloud-termux', 'build.json'), encoding='utf-8').read())['shared_store']['provider']
check(v['open']['store']['authority'] == termux['authority'], 'store authority equals ac_cloud-termux shared_store.provider.authority')
check(v['open']['store']['permission'] == termux['permission'], 'store permission equals ac_cloud-termux shared_store.provider.permission')
app = 'packages/frontend/apps/android'
kt = rd(app, 'App/app/src/main/java/app/cloudnotes/pro/plugin/VaultPlugin.kt')
act = rd(app, 'App/app/src/main/java/app/cloudnotes/pro/MainActivity.kt')
man = rd(app, 'App/app/src/main/AndroidManifest.xml')
ts = rd(app, 'src/plugins/vault.ts')
check('@CapacitorPlugin(name = "Vault"' in kt or 'name = "Vault"' in kt, 'VaultPlugin is a Capacitor plugin named Vault')
check("registerPlugin<VaultNative>('Vault')" in ts, "the web side registers 'Vault'")
check('VaultPlugin::class.java' in act, 'MainActivity registers VaultPlugin')
check('ACTION_OPEN_DOCUMENT_TREE' in kt and 'takePersistableUriPermission' in kt, 'the picker is SAF with a persisted grant')
check(v['open']['store']['authority'] in man, 'the manifest can see the store provider (queries)')
check(v['open']['store']['permission'] in man, 'the manifest requests the store permission')
for m in re.findall(r'^\s+(\w+)\(options', rd('packages/frontend/core/src/mobile/vault/vault-fs.ts'), re.M):
    check(re.search(r'fun %s\(call' % m, kt) is not None, 'VaultNative.%s is implemented in Kotlin' % m)
check(all(r in kt for r in v['open']['direct_roots']), 'Kotlin direct roots include the declared ones')
route = v['route']
check(re.search(r"path:\s*'%s'" % re.escape(route), rd('packages/frontend/core/src/mobile/workbench-router.ts')) is not None, 'workbench router declares %s' % route)
raise SystemExit(1 if fails else 0)
PY

[ "$fails" -eq 0 ] && { echo "RESULT: GREEN"; exit 0; }
echo "RESULT: RED ($fails step(s))"; exit 1
