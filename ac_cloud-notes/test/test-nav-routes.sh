#!/usr/bin/env bash
# ac_cloud-notes tester (#868): the native nav and the web router must agree.
#
# The bottom nav is declared in build.json::ui (section and page IDS) and drawn by
# libs:bottomnav in the Compose shell; the shell sends `cloud-notes:nav` events
# carrying those ids. The web half owns the routes, in
# core/src/mobile/components/native-nav-routes.ts. Nothing type-checks a string id
# across Kotlin/JSON/TypeScript, so an id added to one side and not the other is a
# tap that does nothing. Asserted here off the source, no build, no device:
#
#   every ui.sections[].pages[] id  <=>  exactly one NATIVE_NAV_TARGETS row
#   every ui.sections[] id has a NATIVE_NAV_FIRST_PAGE that is one of its pages
#   every route target is a route the mobile workbench declares
#   the Kotlin shell uses the same event name as the web bridge
#
# python3 only; paths below are the only constants.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import json, os, re, sys
root = sys.argv[1]
fails = 0
def check(ok, msg):
    global fails
    print(('  PASS  ' if ok else '  FAIL  ') + msg)
    if not ok: fails += 1
rd = lambda *p: open(os.path.join(root, *p), encoding='utf-8').read()

ui = json.loads(rd('build.json')).get('ui') or {}
ts = rd('packages/frontend/core/src/mobile/components/native-nav-routes.ts')
router = rd('packages/frontend/core/src/mobile/workbench-router.ts')
kt = rd('packages/frontend/apps/android/App/app/src/main/java/app/cloudnotes/pro/NativeNav.kt')

sections = ui.get('sections') or []
declared = {f"{s['id']}/{p['id']}": p for s in sections for p in s.get('pages', [])}
check(bool(declared), 'build.json::ui declares sections with pages (%d pages)' % len(declared))

targets = re.search(r'NATIVE_NAV_TARGETS[^=]*=\s*\{(.*?)\n\};', ts, re.S).group(1)
rows = dict(re.findall(r"'([\w-]+/[\w-]+)':\s*(\{[^}]*\})", targets))
check(set(rows) == set(declared),
      'ui pages and NATIVE_NAV_TARGETS rows are the same set'
      + ('' if set(rows) == set(declared) else
         ': ui-only %s, web-only %s' % (sorted(set(declared) - set(rows)), sorted(set(rows) - set(declared)))))

first = dict(re.findall(r"(\w+):\s*'([\w-]+)'", re.search(r'NATIVE_NAV_FIRST_PAGE[^=]*=\s*\{(.*?)\};', ts, re.S).group(1)))
for s in sections:
    ids = [p['id'] for p in s.get('pages', [])]
    check(first.get(s['id']) in ids, 'section %s opens a page it declares (%s)' % (s['id'], first.get(s['id'])))
check(all(a in ('', 'launch') for a in (p.get('action', '') for p in declared.values())), "page actions are 'launch' or empty")
for key, p in declared.items():
    if p.get('action'):
        check("kind: 'action'" in rows.get(key, ''), 'launch page %s is a web action, not a route' % key)

declared_routes = set(re.findall(r"path:\s*'(/[\w/]*)'", router))
for key, row in sorted(rows.items()):
    m = re.search(r"to:\s*'([^']+)'", row)
    if m:
        check(m.group(1) in declared_routes, '%s -> %s is a mobile workbench route' % (key, m.group(1)))

ev = re.search(r"NATIVE_NAV_EVENT\s*=\s*'([^']+)'", ts).group(1)
check(f'EVENT = "{ev}"' in kt, 'the Kotlin shell dispatches the web bridge event (%s)' % ev)

print('RESULT: %s' % ('RED' if fails else 'GREEN'))
raise SystemExit(1 if fails else 0)
PY
