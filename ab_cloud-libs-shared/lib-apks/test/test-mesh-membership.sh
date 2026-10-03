#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #743 — every Cloud-Lib APK that is a mesh member holds CONSTELLATION_DATA, ║
# ║ and every Cloud-Lib APK's versionCode moves                               ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# A lib APK is a mesh member when its flavor compiles libs:devtools (that is
# where FleetTokenProvider and FleetMemberReceiver live). Both are guarded by
# CONSTELLATION_DATA, which only libs:core DEFINES and REQUESTS. Every member
# reaches core transitively except devtools itself (the edge runs core ->
# devtools), so the published Cloud-Lib-Devtools.apk carried the provider but
# neither defined nor held the permission — the one member every app's peer
# view lacked (39/40).
#
#   M1  every shipped flavor whose closure contains devtools also contains core
#       (module edges from each libs/*/build.gradle, plus the per-flavor edges
#       lib-apks/app/build.gradle adds).
#   M2  the APK versionCode is the wall clock, never build.json's declared
#       version_code (1): a lib whose code never moves shows no build age and
#       is refused by anything that orders by versionCode.
#   M3  #792 EVERY shipped flavor's closure contains core: every Cloud-Lib APK is
#       a full mesh member, not only the ones whose module happens to link core
#       (eighteen did not — the Store could install them but never wake, list
#       or read one). Read from the all-flavors loop in lib-apks/app/build.gradle.
#   MUT every property, broken on a copy, goes red.
#
# OWN-SOURCE ONLY, python3 only, no build, no network.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
SHARED="$ROOT/ab_cloud-libs-shared"
for required in "$SHARED/lib-apks/build.json" "$SHARED/lib-apks/app/build.gradle" "$SHARED/libs/devtools/build.gradle" "$SHARED/libs/core/build.gradle"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

# check <shared-root> -> prints one FAIL line per violation, nothing when clean
check() {
    python3 - "$1" <<'PY'
import json, os, re, sys
shared = sys.argv[1]
apks = os.path.join(shared, 'lib-apks')
cfg = json.load(open(os.path.join(apks, 'build.json')))['lib_apks']
roots = [os.path.normpath(os.path.join(apks, r)) for r in (cfg['scan'] if isinstance(cfg['scan'], list) else [cfg['scan']])]
excluded = set(cfg.get('exclude', {}))
edge = re.compile(r"project\(['\"]:libs:([\w-]+)['\"]\)")
deps, shipped = {}, []
for r in roots:
    for d in sorted(os.listdir(r)):
        g = os.path.join(r, d, 'build.gradle')
        if os.path.isfile(g):
            deps[d] = set(edge.findall(open(g).read()))
            if d not in excluded:
                shipped.append(d)
app = open(os.path.join(apks, 'app', 'build.gradle')).read()
# per-flavor extra edges: add("${flavorOf('x')}Implementation", project(':libs:y'))
extra = {}
for flav, dep in re.findall(r"flavorOf\('([\w-]+)'\)\}Implementation\",\s*project\(':libs:([\w-]+)'\)", app):
    extra.setdefault(flav, set()).add(dep)
# #792 the all-flavors edge: shipped.findAll { it != 'x' && ... }.each { name -> add("${flavorOf(name)}Implementation", project(':libs:y')) }
for skip, dep in re.findall(r"shipped\.findAll \{ ([^}]*) \}\.each \{ name ->\s*add\(\"\$\{flavorOf\(name\)\}Implementation\",\s*project\(':libs:([\w-]+)'\)\)", app):
    skipped = set(re.findall(r"it != '([\w-]+)'", skip))
    for name in shipped:
        if name not in skipped:
            extra.setdefault(name, set()).add(dep)
def closure(seed):
    seen, todo = set(), list(seed)
    while todo:
        m = todo.pop()
        if m in seen: continue
        seen.add(m); todo.extend(deps.get(m, ()))
    return seen
for name in shipped:
    c = closure({name} | extra.get(name, set()))
    if 'devtools' in c and 'core' not in c:
        print(f"FAIL M1 Cloud-Lib APK '{name}' is a mesh member (compiles devtools) but not libs:core: "
              f"it carries the fleet provider without defining or holding CONSTELLATION_DATA")
    elif 'core' not in c:
        print(f"FAIL M3 Cloud-Lib APK '{name}' does not link libs:core: not a mesh member, so the Store "
              f"cannot wake it, list it or read its debug API")
body = app.split('def cloudVersionCode', 1)[1].split('\nandroid {', 1)[0] if 'def cloudVersionCode' in app else ''
if not body:
    print("FAIL M2 lib-apks/app/build.gradle has no cloudVersionCode")
elif 'version_code' in body:
    print("FAIL M2 cloudVersionCode reads build.json version_code — every lib APK ships that static number")
if 'versionCode cloudVersionCode()' not in app:
    print("FAIL M2 defaultConfig does not take versionCode from cloudVersionCode()")
PY
}

FAIL=0
out="$(check "$SHARED")"
if [ -z "$out" ]; then echo "  PASS  M1+M2 every mesh-member lib APK links core; versionCode is the wall clock"
else printf '%s\n' "$out"; FAIL=1; fi

# ── mutations: each property broken on a copy must go red ──────────────────
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mutate() {   # mutate <label> <python-edit-of-app/build.gradle>
    rm -rf "$TMP/s"; mkdir -p "$TMP/s"
    cp -r "$SHARED/lib-apks" "$TMP/s/"; ln -s "$SHARED/libs" "$TMP/s/libs"
    python3 -c "p='$TMP/s/lib-apks/app/build.gradle'; s=open(p).read(); $2; open(p,'w').write(s)"
    if [ -n "$(check "$TMP/s")" ]; then echo "  PASS  MUT $1 goes red"
    else echo "  FAIL  MUT $1 stayed green — the check cannot see it"; FAIL=1; fi
}
mutate "devtools flavor without core" \
    "s=s.replace(\"shipped.findAll { it != 'core' }\", \"shipped.findAll { it != 'core' && it != 'devtools' }\", 1); assert 'devtools' in s"
mutate "the all-flavors core edge dropped (eighteen libs back out of the mesh)" \
    "import re; s=re.sub(r\"shipped\\.findAll \\{ it != 'core' \\}\\.each \\{ name ->\\s*add\\([^\\n]*\\n\\s*\\}\", '', s, count=1)"
mutate "static versionCode override restored" \
    "s=s.replace('def cloudVersionCode = { ->', 'def cloudVersionCode = { ->\\n    if (buildJson.android.version_code) return buildJson.android.version_code as int', 1)"

[ "$FAIL" -eq 0 ] && echo "test-mesh-membership: OK" || { echo "test-mesh-membership: FAILED"; exit 1; }
