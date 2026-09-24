#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #567 — every engine library is REGISTERED with the fleet: i18n policy,   ║
# ║ constellation libs taxonomy, and the generated workflow copies           ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY A SECOND FILE. test-drive-engine-modules.sh reads only this app's own
# source, so its verdict is this app's and it can fail this app's release.
# The registrations below live in OTHER owners' files — 1_cicd's i18n policy,
# the SuperApp's fleet manifest, the generated workflow copies — and the test
# engine will classify a tester that reads them as reaching outside this
# application (FOREIGN-DOWNGRADED: it runs, it reports, it does not gate).
# That is the correct reading, and putting these assertions in their own file
# is what keeps the other file fatal. Each registration ALSO has a fleet-wide
# gate of its own that runs on every push with no path filter (i18n-guard.yml,
# generated-up-to-date.yml, fleet-manifest-guard.yml); this file is the early,
# named, per-engine answer.
#
#   R1  every engine that owns a values/strings.xml is declared in
#       1_cicd/src/i18n-policy.json as base_language host (#493: the guard has
#       no path filter and an undeclared module turns main red).
#   R2  the constellation fleet manifest carries each engine as kind lib, with
#       the canonical cloud-lib-{name} label and the applicationId lib-apks
#       derives (#474/#343) — i.e. regen.sh was run after the module appeared.
#   R3  the generated copies of the ship workflow (1_cicd/dist and .github)
#       carry the same watched paths as the source — a regenerated source that
#       was never emitted is a change that never reaches CI.
#
# The engine set is discovered exactly as the sibling tester discovers it.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
BUILD_JSON="$APP/build.json"
POLICY="$ROOT/1_cicd/src/i18n-policy.json"
FLEET="$ROOT/aa_cloud-superapp/data/constellation-fleet.json"
SHIP_SRC="$ROOT/1_cicd/src/cicd/ship-cloud-drive.yml"
SHIP_DIST="$ROOT/1_cicd/dist/cicd/ship-cloud-drive.yml"
SHIP_GH="$ROOT/.github/workflows/ship-cloud-drive.yml"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

for required in "$BUILD_JSON" "$POLICY" "$FLEET" "$SHIP_SRC" "$SHIP_DIST" "$SHIP_GH"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required"; exit 1; }
done

ENGINES="$(python3 - "$BUILD_JSON" "$APP" "$ROOT" <<'PYTHON'
import json, os, re, sys
build_json, app, root = sys.argv[1:4]
modules = json.load(open(build_json, encoding="utf-8"))["modules"]
for name, spec in modules.items():
    if name.startswith("_") or not isinstance(spec, dict) or not spec.get("dir"):
        continue
    mod_dir = os.path.normpath(os.path.join(app, spec["dir"]))
    gradle = os.path.join(mod_dir, "build.gradle")
    if not os.path.isfile(gradle):
        continue
    if not re.search(r"id\s+'org\.jetbrains\.kotlin\.plugin\.compose'", open(gradle, encoding="utf-8").read()):
        continue
    print("%s|%s|%s" % (name, mod_dir, os.path.relpath(mod_dir, root)))
PYTHON
)"
[ -n "$ENGINES" ] || { echo "ERROR no engine discovered — nothing to register, refusing to go green"; exit 1; }

echo "── R1 i18n policy declares every engine that owns strings ──"
while IFS='|' read -r name dir rel; do
    [ -f "$dir/src/main/res/values/strings.xml" ] || { pass "$name owns no strings.xml (nothing to declare)"; continue; }
    LANG_DECL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["modules"].get(sys.argv[2],{}).get("base_language",""))' "$POLICY" "$rel")"
    if [ "$LANG_DECL" = "host" ]; then
        pass "$rel declared base_language host"
    else
        fail "$rel owns values/strings.xml but i18n-policy.json says base_language='$LANG_DECL' (want host)"
    fi
done <<<"$ENGINES"

echo "── R2 the constellation fleet manifest registers every engine as cloud-lib-{name} ──"
while IFS='|' read -r name dir rel; do
    short="${name#libs:}"
    python3 - "$FLEET" "$short" <<'PYTHON' && pass "lib-$short registered as cloud-lib-$short, kind lib, in group libs" || fail "lib-$short is not (correctly) in the fleet manifest — run aa_cloud-superapp/data/regen.sh --constellation-only"
import json, sys
fleet, short = sys.argv[1], sys.argv[2]
d = json.load(open(fleet, encoding="utf-8"))
entry = next((a for a in d["apps"] if a.get("id") == "lib-" + short), None)
problems = []
if entry is None:
    problems.append("no apps[] entry with id lib-" + short)
else:
    if entry.get("label") != "cloud-lib-" + short: problems.append("label=%r" % entry.get("label"))
    if entry.get("kind") != "lib": problems.append("kind=%r" % entry.get("kind"))
    if entry.get("package") != "com.diegonmarcos.cloudlib." + short.replace("-", ""): problems.append("package=%r" % entry.get("package"))
    libs_group = next((g for g in d["groups"] if g.get("id") == "libs"), {})
    if "lib-" + short not in libs_group.get("members", []): problems.append("not a member of groups[libs]")
for p in problems:
    print("    " + p)
sys.exit(1 if problems else 0)
PYTHON
done <<<"$ENGINES"

echo "── R3 the generated workflow copies carry the source's watched paths ──"
SRC_PATHS="$(grep -E '^\s*-\s*"[^"]+"\s*$' "$SHIP_SRC" | sort)"
for copy in "$SHIP_DIST" "$SHIP_GH"; do
    COPY_PATHS="$(grep -E '^\s*-\s*"[^"]+"\s*$' "$copy" | sort)"
    if [ "$SRC_PATHS" = "$COPY_PATHS" ]; then
        pass "$(basename "$(dirname "$copy")")/ship-cloud-drive.yml paths match the source"
    else
        fail "$copy watched paths differ from the source — run ./build.sh workflow and commit"
    fi
done
while IFS='|' read -r name dir rel; do
    if printf '%s\n' "$SRC_PATHS" | grep -qF "\"$rel/**\""; then
        pass "$rel/** is watched"
    else
        fail "$rel/** is not in the ship workflow's paths"
    fi
done <<<"$ENGINES"

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "test-drive-engine-registrations: all checks passed"
else
    echo "test-drive-engine-registrations: $FAILURES check(s) FAILED"
    exit 1
fi
