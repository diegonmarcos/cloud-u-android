#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #567 — every engine library this app links is declared, wired, Compose,  ║
# ║ and imports NOTHING from the application (the module boundary is law)   ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. The owner's architecture for cloud-drive: the main APK is
# chrome and every tooling is a full engine in its OWN library on the shared
# shelf. Four things about that can rot silently while Gradle still exits 0:
#
#   - a module can be on the shelf and compiled by lib-apks but never linked
#     here, so the app ships without the engine and nobody notices until a tap;
#   - a module can be linked in app/build.gradle but missing from the
#     build.json module map, so settings.gradle never includes it and the link
#     fails only at configuration time on CI, hours later;
#   - a module can reach BACK into the app (an `import com.diegonmarcos.clouddrive.*`
#     compiles fine inside this app's own build) and the library stops being a
#     library: lib-apks can no longer build it and every other consumer breaks;
#   - the ship workflow's path list can lag the module map, so an engine edit
#     never triggers this app's build — the exact failure the publish gate's
#     managed-paths header exists to prevent, and the one only a test can see.
#
# WHAT IT PINS — every assertion is against DERIVED state, never a list written
# here. The engine set is DISCOVERED: it is every module in build.json::modules
# that has a `dir` and whose build.gradle applies the Compose compiler plugin
# (#565: an engine is a Compose library). Add an engine and it is covered with
# no edit to this file; a module that stops being Compose falls out of the set
# and E1 notices the app still depends on it.
#
#   E0  at least one engine is discovered (a suite that discovers nothing has
#       nothing to say and must not go green).
#   E1  each engine is in app.depends_on AND linked with `implementation
#       project(':libs:<name>')` in app/build.gradle — both ends.
#   E2  the root build.gradle declares the Compose compiler plugin `apply false`
#       — the libs apply the bare id, which resolves only if the root names it.
#   E3  each engine exports EXACTLY ONE @Composable `<X>Screen(target: String?,  (+ optional defaulted args, #575)
#       onOpenFile: (String) -> Unit, onClose: () -> Unit)` — the host contract
#       push 5 wires against, in the lib's own namespace package.
#   E4  the module boundary: no source under an engine imports this app's
#       application_id package, and no engine gradle references project(':app').
#   E5  1_cicd/src/cicd/ship-cloud-drive.yml (the SOURCE the generator manages)
#       lists `<dir>/**` for every module dir in the map — the map and the
#       trigger list are one list read twice, and this is where they meet.
#   E6  each engine's screen resolves its title through a string resource that
#       the module's own values/strings.xml defines (rendered key, not a literal).
#
# OWN-SOURCE ONLY, DELIBERATELY. Everything read here is inside this app's own
# source set as cloud-android-source-identity.sh derives it (the app dir, the
# module dirs the map declares, and the workflow SOURCE). The cross-cutting
# registrations — i18n policy, the fleet manifest, the generated workflow
# copies — live in test-drive-engine-registrations.sh, which the engine will
# rightly classify as reaching outside this application. Keeping them apart is
# what lets THIS file fail the release.
#
# python3 and grep only, both in build.json::tests.shell.requires.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
BUILD_JSON="$APP/build.json"
ROOT_GRADLE="$APP/build.gradle"
APP_GRADLE="$APP/app/build.gradle"
SHIP_SRC="$ROOT/1_cicd/src/cicd/ship-cloud-drive.yml"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

for required in "$BUILD_JSON" "$ROOT_GRADLE" "$APP_GRADLE" "$SHIP_SRC"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required"; exit 1; }
done

# ── discovery: engine = module build.json::modules DECLARES with `engine: true` ──
# (#579) Until the redesign every Compose library this app linked was an engine, so
# the set was inferred as "Compose library"; libs:bottomnav — the fleet's nav island,
# chrome not tooling — is the first Compose library here that is not one, so the role
# is declared in build.json (modules._doc_engine_flag) and read here. E3 still holds
# every declared engine to being Compose. One python pass emits
# `name|absolute-dir|namespace` per engine; every section below reads that. The
# namespace is read out of the module's own build.gradle so E3/E6 look for the
# screen where the module says it lives. A declared engine whose dir or gradle is
# missing is emitted with an empty namespace so E3 fails on it instead of skipping.
ENGINES="$(python3 - "$BUILD_JSON" "$APP" <<'PYTHON'
import json, os, re, sys
build_json, app = sys.argv[1], sys.argv[2]
modules = json.load(open(build_json, encoding="utf-8"))["modules"]
for name, spec in modules.items():
    if name.startswith("_") or not isinstance(spec, dict) or spec.get("engine") is not True:
        continue
    mod_dir = os.path.normpath(os.path.join(app, spec.get("dir") or name.replace(":", "/")))
    gradle = os.path.join(mod_dir, "build.gradle")
    text = open(gradle, encoding="utf-8").read() if os.path.isfile(gradle) else ""
    ns = re.search(r"namespace\s+'([^']+)'", text)
    print("%s|%s|%s" % (name, mod_dir, ns.group(1) if ns else ""))
PYTHON
)"

echo "── E0 engines are discovered from build.json::modules + Compose ──"
if [ -n "$ENGINES" ]; then
    pass "engines discovered: $(printf '%s\n' "$ENGINES" | cut -d'|' -f1 | tr '\n' ' ')"
else
    fail "no module with a dir applies the Compose compiler plugin — nothing to test, refusing to go green"
    exit 1
fi

echo "── E1 every engine is linked at both ends (build.json depends_on + app/build.gradle) ──"
DEPENDS_ON="$(python3 -c 'import json,sys; print("\n".join(json.load(open(sys.argv[1]))["modules"]["app"]["depends_on"]))' "$BUILD_JSON")"
while IFS='|' read -r name dir ns; do
    if printf '%s\n' "$DEPENDS_ON" | grep -qxF "$name"; then
        pass "$name in modules.app.depends_on"
    else
        fail "$name is a linked engine but absent from modules.app.depends_on"
    fi
    if grep -qE "implementation[[:space:]]+project\(':$name'\)" "$APP_GRADLE"; then
        pass "$name linked in app/build.gradle"
    else
        fail "$name not linked with implementation project(':$name') in app/build.gradle"
    fi
done <<<"$ENGINES"

echo "── E2 the root declares the Compose compiler plugin the libs apply bare ──"
if grep -qE "id[[:space:]]+'org\.jetbrains\.kotlin\.plugin\.compose'[[:space:]]+version[[:space:]]+'[^']+'[[:space:]]+apply[[:space:]]+false" "$ROOT_GRADLE"; then
    pass "root build.gradle declares org.jetbrains.kotlin.plugin.compose apply false"
else
    fail "root build.gradle does not declare org.jetbrains.kotlin.plugin.compose apply false — every engine's bare plugin id fails to resolve"
fi

echo "── E3 every engine exports exactly one host-contract screen ──"
while IFS='|' read -r name dir ns; do
    python3 - "$dir" "$ns" <<'PYTHON' && pass "$name exports one <X>Screen(target, onOpenFile, onClose) in $ns" || fail "$name breaks the host contract (see above)"
import os, re, sys
mod_dir, ns = sys.argv[1], sys.argv[2]
if not ns:
    print("    no namespace declared in build.gradle"); sys.exit(1)
src = os.path.join(mod_dir, "src", "main", "java", *ns.split("."))
if not os.path.isdir(src):
    print("    namespace package directory missing: " + src); sys.exit(1)
found = []
for dirpath, _, files in os.walk(src):
    for f in files:
        if not f.endswith(".kt"):
            continue
        text = open(os.path.join(dirpath, f), encoding="utf-8").read()
        # Nearest-preceding annotation: the signature must be a top-level
        # @Composable in the namespace package itself, not a nested helper.
        for m in re.finditer(
            r"@Composable\s*\nfun\s+(\w+Screen)\s*\(\s*"
            r"target:\s*String\?\s*,\s*"
            r"onOpenFile:\s*\(String\)\s*->\s*Unit\s*,\s*"
            # #575: an engine may take FURTHER named, defaulted arguments after the
            # host contract (git-sync's cloneUrl); the three the host always passes
            # stay first and positional.
            r"onClose:\s*\(\)\s*->\s*Unit\s*,?\s*(?:\w+:\s*[^=,)]+=\s*[^,)]+,?\s*)*\)", text):
            if os.path.dirname(os.path.join(dirpath, f)) == src:
                found.append(m.group(1))
if len(found) != 1:
    print("    expected exactly one contract screen, found %r" % found); sys.exit(1)
PYTHON
done <<<"$ENGINES"

echo "── E4 module boundary: no engine imports the application, none names project(':app') ──"
APP_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["android"]["application_id"])' "$BUILD_JSON")"
[ -n "$APP_ID" ] || { fail "build.json::android.application_id is empty — the boundary has no name"; }
while IFS='|' read -r name dir ns; do
    HITS="$(grep -rnE "^import[[:space:]]+${APP_ID//./\\.}\." "$dir/src" --include='*.kt' --include='*.java')"
    if [ -z "$HITS" ]; then
        pass "$name imports nothing from $APP_ID"
    else
        fail "$name reaches back into the application:"; printf '%s\n' "$HITS" | sed 's/^/        /'
    fi
    if grep -qE "project\(':app'\)" "$dir/build.gradle"; then
        fail "$name/build.gradle depends on project(':app') — a library that needs its host is not a library"
    else
        pass "$name/build.gradle names no project(':app')"
    fi
done <<<"$ENGINES"

echo "── E5 the ship workflow SOURCE watches every module dir the map declares ──"
python3 - "$BUILD_JSON" "$APP" "$ROOT" "$SHIP_SRC" <<'PYTHON' && pass "every build.json::modules dir is a <dir>/** path in ship-cloud-drive.yml" || fail "ship-cloud-drive.yml lags the module map (see above)"
import json, os, re, sys
build_json, app, root, ship = sys.argv[1:5]
modules = json.load(open(build_json, encoding="utf-8"))["modules"]
watched = set(re.findall(r'^\s*-\s*"([^"]+)"\s*$', open(ship, encoding="utf-8").read(), re.M))
missing = []
for name, spec in modules.items():
    if name.startswith("_") or not isinstance(spec, dict) or not spec.get("dir"):
        continue
    rel = os.path.relpath(os.path.normpath(os.path.join(app, spec["dir"])), root)
    if rel + "/**" not in watched:
        missing.append("%s -> %s/**" % (name, rel))
for m in missing:
    print("    not watched: " + m)
sys.exit(1 if missing else 0)
PYTHON

echo "── E6 every engine's title is a string resource its own values/strings.xml defines ──"
while IFS='|' read -r name dir ns; do
    python3 - "$dir" "$ns" <<'PYTHON' && pass "$name title key resolves in its strings.xml" || fail "$name title key does not resolve (see above)"
import os, re, sys
import xml.etree.ElementTree as ET
mod_dir, ns = sys.argv[1], sys.argv[2]
src = os.path.join(mod_dir, "src", "main", "java", *ns.split("."))
keys = set()
for dirpath, _, files in os.walk(src):
    for f in files:
        if f.endswith("Screen.kt"):
            keys |= set(re.findall(r"R\.string\.(\w+)", open(os.path.join(dirpath, f), encoding="utf-8").read()))
if not keys:
    print("    the screen references no R.string key"); sys.exit(1)
strings = os.path.join(mod_dir, "src", "main", "res", "values", "strings.xml")
if not os.path.isfile(strings):
    print("    no values/strings.xml in the module"); sys.exit(1)
defined = {e.get("name") for e in ET.parse(strings).getroot() if e.tag == "string"}
undefined = sorted(keys - defined)
for k in undefined:
    print("    referenced but undefined: " + k)
sys.exit(1 if undefined else 0)
PYTHON
done <<<"$ENGINES"

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "test-drive-engine-modules: all checks passed"
else
    echo "test-drive-engine-modules: $FAILURES check(s) FAILED"
    exit 1
fi
