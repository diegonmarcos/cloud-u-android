#!/usr/bin/env bash
# ac_cloud-notes tester (#543): every Capacitor plugin the WEB bundle calls must
# have a NATIVE implementation registered in the activity.
#
# Diego could not create a workspace: `"Auth" plugin is not implemented on
# android`. Nothing was wrong upstream and nothing was wrong with the build
# config — #469's de-clouding commit deleted AuthPlugin.kt from the Android shell
# and left packages/frontend/apps/android/src/plugins/auth calling
# registerPlugin('Auth'). Capacitor does not degrade an unregistered plugin, it
# throws, and proxy.ts had handed that plugin to installAuthRequestProxy(),
# which wraps globalThis.fetch — so the throw landed on the purely LOCAL
# workspace path, which needs no auth at all.
#
# Neither compiler can see this. TypeScript type-checks registerPlugin against a
# hand-written interface, never against Kotlin. Gradle compiles an activity that
# is free to register nothing. The bridge is only resolved at runtime, on the
# phone, on first use. So the invariant is asserted here, off the source, with no
# build and no device:
#
#   name in registerPlugin<T>('name')  =>  a class in the activity's
#   registerPlugins(listOf(...)) whose @CapacitorPlugin(name = "...") is that
#   name.
#
# BOTH SIDES ARE DERIVED. No plugin name is written in this file: a list here
# would have to be edited by the same person who forgot the native class, and
# would have passed on the broken tree. Paths come from
# build.json::tests.capacitor_surface. Everything read is inside this
# application (#254).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAIL=0

ok()   { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAIL=1; }

report="$(python3 - "$ROOT" <<'PY'
import json, os, re, sys

root = sys.argv[1]
cfg = json.load(open(os.path.join(root, 'build.json')))['tests']['capacitor_surface']

web_root = os.path.join(root, cfg['web_root'])
activity = os.path.join(root, cfg['activity'])
kotlin_root = os.path.join(root, cfg['kotlin_root'])
for label, path in (('web_root', web_root), ('kotlin_root', kotlin_root)):
    if not os.path.isdir(path):
        print('ERROR %s missing: %s' % (label, cfg[label]))
        raise SystemExit(2)
if not os.path.isfile(activity):
    print('ERROR activity missing: %s' % cfg['activity'])
    raise SystemExit(2)

# ── needed: every plugin name the bundle asks the bridge for ───────────────
NEEDS = re.compile(r"""registerPlugin\s*(?:<[^>]*>)?\s*\(\s*['"]([^'"]+)['"]""")
needed, ts_files = {}, 0
for dirpath, _dirs, files in os.walk(web_root):
    for name in files:
        if not name.endswith(('.ts', '.tsx')):
            continue
        ts_files += 1
        path = os.path.join(dirpath, name)
        text = open(path, encoding='utf-8', errors='replace').read()
        for plugin in NEEDS.findall(text):
            needed.setdefault(plugin, os.path.relpath(path, root))

# ── provided: the classes the activity registers, by annotated name ────────
activity_src = open(activity, encoding='utf-8', errors='replace').read()
block = re.search(r'registerPlugins\s*\(\s*listOf\s*\((.*?)\)\s*\)', activity_src, re.S)
if not block:
    print('ERROR no registerPlugins(listOf(...)) block in %s' % cfg['activity'])
    raise SystemExit(2)
registered = re.findall(r'([A-Za-z_]\w*)\s*::\s*class\s*\.\s*java', block.group(1))

# The annotation argument list nests parentheses (`permissions =
# [Permission(...)]`), so it is scanned forward to the class it decorates rather
# than matched as one balanced group.
NAME_ARG = re.compile(r'\bname\s*=\s*"([^"]+)"')
CLASS_DECL = re.compile(r'\bclass\s+([A-Za-z_]\w*)')
annotated = {}
for dirpath, _dirs, files in os.walk(kotlin_root):
    for name in files:
        if not name.endswith('.kt'):
            continue
        text = open(os.path.join(dirpath, name), encoding='utf-8', errors='replace').read()
        for hit in re.finditer(r'@CapacitorPlugin\b', text):
            tail = text[hit.end():hit.end() + 600]
            cls = CLASS_DECL.search(tail)
            if not cls:
                continue
            plugin_name = NAME_ARG.search(tail[:cls.start()])
            if plugin_name:
                annotated[cls.group(1)] = plugin_name.group(1)

# A derivation that found nothing would certify anything. Fail closed.
if ts_files < 20 or len(needed) < 5 or len(registered) < 5:
    print('ERROR derivation is empty: %d ts files, %d needed names, %d registered classes'
          % (ts_files, len(needed), len(registered)))
    raise SystemExit(2)

provided = {}
for cls in registered:
    if cls in annotated:
        provided.setdefault(annotated[cls], cls)
    else:
        print('UNANNOTATED %s is registered but carries no @CapacitorPlugin(name = ...)' % cls)

for plugin in sorted(needed):
    if plugin in provided:
        print('COVERED %s -> %s' % (plugin, provided[plugin]))
    else:
        print('MISSING %s (called from %s) has no registered native class' % (plugin, needed[plugin]))

print('DERIVED %d ts files, %d plugin names needed, %d registered classes'
      % (ts_files, len(needed), len(registered)))
PY
)"
status=$?

printf '%s\n' "$report"
if [ "$status" -ne 0 ]; then
  fail "plugin-surface derivation could not run (exit $status) — see ERROR above"
else
  printf '%s\n' "$report" | grep -q '^DERIVED ' \
    && ok "$(printf '%s\n' "$report" | grep '^DERIVED ')" \
    || fail "derivation printed no DERIVED line"

  missing="$(printf '%s\n' "$report" | grep '^MISSING ' || true)"
  if [ -n "$missing" ]; then
    fail "a plugin the web bundle calls has no native implementation registered in the activity"
  else
    ok "every Capacitor plugin name the bundle calls is registered in the activity"
  fi

  unannotated="$(printf '%s\n' "$report" | grep '^UNANNOTATED ' || true)"
  if [ -n "$unannotated" ]; then
    fail "a registered class has no @CapacitorPlugin(name = ...) — the bridge cannot route to it"
  else
    ok "every registered class carries a @CapacitorPlugin name"
  fi
fi

# The one flow this app exists for must not depend on a token. The fetch proxy
# treats the app's OWN origin as an auth endpoint, so a rejecting auth provider
# must degrade to "no Authorization header", never propagate — the XHR half of
# the same file has always done that, the fetch half did not (#543).
proxy="$ROOT/packages/frontend/apps/mobile-shared/src/auth/request.ts"
if [ ! -f "$proxy" ]; then
  fail "auth request proxy not found at packages/frontend/apps/mobile-shared/src/auth/request.ts"
elif grep -q 'provider.getValidAccessToken(endpoint).catch(() => null)' "$proxy"; then
  ok "createAuthFetch degrades to no token when the auth provider rejects"
else
  fail "createAuthFetch lets an auth-provider rejection fail the request — a local fetch dies on a missing plugin"
fi

if [ "$FAIL" -eq 0 ]; then
  printf '  cloud-notes capacitor plugin-surface tester: ALL PASS\n'
else
  printf '  cloud-notes capacitor plugin-surface tester: FAIL\n' >&2
  exit 1
fi
