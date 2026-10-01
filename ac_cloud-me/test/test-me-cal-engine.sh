#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ engine-apk-split move 3 — Cloud Me REACHES the calendar, it does not       ║
# ║ CARRY it: Agenda is a thin client of the cal engine (Cloud-Lib-Cal.apk)    ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# Cloud Me used to compile libs:cal and run CalEngine in its own process, so every
# calendar change republished Cloud Me. The engine half is pinned by
# ab_cloud-libs-shared/lib-apks/test/test-engine-services.sh (Cloud Libs ship, the
# only one that watches libs/cal now) and the pairing by the engine contract guard
# (every push). This is the CLIENT half; it reads only Cloud Me's own source, so a
# failure here is Cloud Me's and stops its release:
#
#   M1  NOT CARRIED: no module map entry, no gradle link, no import of the cal
#       library, and the ship workflow does not watch libs/cal.
#   M2  DECLARED: build.json::engines.cal names its Store row, the action (from
#       {package}) and the contract this build needs; build.gradle resolves the
#       package from the fleet manifest, fails the build on an unknown id, and
#       bakes every field.
#   M3  VISIBLE: the manifest queries the engine's package, or Android 11+ hides it
#       and "installed" reads as "not installed".
#   M4  HANDSHAKE BEFORE BIND: the client resolves the service by action and
#       package with its meta-data, refuses a contract below the declared one, and
#       only then builds the binder client; every call goes through it.
#   M5  LOUD: missing and too-old are two different lines, each naming the Store,
#       and an engine that fails a call is said, never drawn as an empty list.
#   MUT each property, broken on a copy (and proven broken), goes red.
#
# OWN-SOURCE ONLY. python3 and grep only, no network, no build.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-me"
BJ="$APP/build.json"
GRADLE="$APP/app/build.gradle"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"
MAIN="$APP/app/src/main/java"
CLIENT="$MAIN/com/diegonmarcos/cloudme/CalEngineClient.kt"
PAGE="$MAIN/com/diegonmarcos/cloudme/AgendaFragment.kt"
STR="$APP/app/src/main/res/values/strings.xml"
WF="$ROOT/1_cicd/src/cicd/ship-cloud-me.yml"
for required in "$BJ" "$GRADLE" "$MANIFEST" "$CLIENT" "$PAGE" "$STR" "$WF"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
# a file's CODE: comment lines dropped, because these files EXPLAIN what they no longer do
_code() { grep -vE '^[[:space:]]*(\*|//|/\*|<!--)' "$1"; }

# m1 <build.json> <app/build.gradle> <main java root> <workflow>
m1() {
    local bj="$1" gradle="$2" main="$3" wf="$4" bad=0 hit
    python3 - "$bj" <<'PYTHON' || bad=1
import json, sys
m = json.load(open(sys.argv[1], encoding="utf-8")).get("modules") or {}
bad = 0
if "libs:cal" in m:
    print("    build.json::modules declares libs:cal again — settings.gradle would compile the engine into this app"); bad = 1
if "libs:cal" in ((m.get("app") or {}).get("depends_on") or []):
    print("    build.json::modules.app.depends_on lists libs:cal"); bad = 1
sys.exit(bad)
PYTHON
    [ "$(_code "$gradle" | grep -cE "project\\(['\"]:libs:cal['\"]\\)")" -eq 0 ] \
        || { echo "    app/build.gradle links :libs:cal — the calendar engine is back inside Cloud Me"; bad=1; }
    hit="$(grep -rnE '^import com\.diegonmarcos\.superapp\.cal\.' "$main" || true)"
    [ -z "$hit" ] || { echo "    the app imports the cal library it no longer compiles:"; printf '%s\n' "$hit" | sed 's/^/        /'; bad=1; }
    grep -qF '"ab_cloud-libs-shared/libs/cal/**"' "$wf" \
        && { echo "    ship-cloud-me.yml watches libs/cal — every calendar change would republish Cloud Me again"; bad=1; }
    return $bad
}

# m2 <build.json> <app/build.gradle>
m2() {
    local bj="$1" gradle="$2" bad=0 field
    python3 - "$bj" <<'PYTHON' || bad=1
import json, sys
e = (json.load(open(sys.argv[1], encoding="utf-8")).get("engines") or {}).get("cal") or {}
bad = 0
if not isinstance(e.get("fleet"), str) or not e["fleet"].strip():
    print("    engines.cal.fleet does not name the engine's Store row"); bad = 1
a = e.get("action")
if not isinstance(a, str) or "{package}" not in a or not a.endswith(".ENGINE"):
    print("    engines.cal.action %r is not the engine's {package}.ENGINE action" % (a,)); bad = 1
c = e.get("min_contract")
if not isinstance(c, int) or isinstance(c, bool) or c < 1:
    print("    engines.cal.min_contract %r is not a contract number >= 1" % (c,)); bad = 1
sys.exit(bad)
PYTHON
    grep -qF '.apps.find { it.id == calEngineDecl.fleet }' <<<"$(_code "$gradle")" \
        || { echo "    build.gradle does not resolve the engine's package from the fleet manifest"; bad=1; }
    python3 - "$gradle" <<'PYTHON' || bad=1
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"if \(calEngineFleet == null\) \{(.*?)\n\}", src, re.S)
if not m or "throw new GradleException" not in m.group(1):
    print("    an engine fleet id the manifest lacks does not fail the build"); sys.exit(1)
PYTHON
    for field in CAL_ENGINE_PACKAGE CAL_ENGINE_ACTION CAL_ENGINE_MIN_CONTRACT; do
        grep -qE "buildConfigField +\"[a-zA-Z]+\", +\"$field\"" <<<"$(_code "$gradle")" \
            || { echo "    build.gradle does not bake $field"; bad=1; }
    done
    grep -qF 'manifestPlaceholders = [calEnginePackage: calEnginePackage]' <<<"$(_code "$gradle")" \
        || { echo "    the manifest placeholder calEnginePackage is not the resolved package"; bad=1; }
    return $bad
}

# m3 <manifest>
m3() {
    python3 - "$1" <<'PYTHON'
import sys
import xml.etree.ElementTree as ET
A = "{http://schemas.android.com/apk/res/android}"
root = ET.parse(sys.argv[1]).getroot()
names = [p.get(A + "name") for q in root.iter("queries") for p in q.iter("package")]
if "${calEnginePackage}" not in names:
    print("    <queries> does not name ${calEnginePackage}: on API 30+ the engine is invisible and reads as not installed")
    sys.exit(1)
PYTHON
}

# m4 <CalEngineClient.kt>
m4() {
    python3 - "$1" <<'PYTHON'
import re, sys
src = "\n".join(l for l in open(sys.argv[1], encoding="utf-8").read().split("\n") if not re.match(r"\s*(\*|//|/\*)", l))
bad = 0
def no(msg):
    global bad
    print("    " + msg); bad = 1
m = re.search(r"\n    fun check\(\): Check \{(.*?)\n    \}\n", src, re.S)
if not m:
    no("CalEngineClient has no check()"); sys.exit(1)
body = m.group(1)
if "resolveService(Intent(BuildConfig.CAL_ENGINE_ACTION).setPackage(pkg), PackageManager.GET_META_DATA)" not in body:
    no("check() does not resolve the service by the declared action in the declared package, with its meta-data")
if "val pkg = BuildConfig.CAL_ENGINE_PACKAGE" not in body:
    no("check() does not look in the declared package")
if "metaData?.getInt(CONTRACT_KEY, 0)" not in body:
    no("check() does not read the engine's CONTRACT")
old = body.find("if (found < needed) return Check.TooOld(")
bind = body.find("DataBackendClient(")
if old < 0:
    no("check() does not refuse an engine below the declared contract")
if bind < 0 or (old >= 0 and bind < old):
    no("check() binds the engine before (or without) checking its contract")
if "val needed = BuildConfig.CAL_ENGINE_MIN_CONTRACT" not in body:
    no("check() does not take the needed contract from the declaration")
if not re.search(r"if \(installed\) Check\.TooOld\(pkg, 0, needed\) else Check\.NotInstalled\(pkg\)", body):
    no("check() does not tell an engine APK with no service (too old) from no engine APK at all")
if 'const val CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"' not in src:
    no("the client reads a contract key that is not the engines' declared one")
a = re.search(r"\n    private fun ask\(method: String, vararg args: String\): String \{(.*?)\n    \}\n", src, re.S)
if not a or "val why = check()" not in a.group(1) or "why !is Check.Ready" not in a.group(1):
    no("ask() reaches the engine without the handshake")
if len(re.findall(r"\.call\(", src)) != 1:
    no("something other than ask() calls the engine's binder directly")
sys.exit(bad)
PYTHON
}

# m5 <AgendaFragment.kt> <strings.xml> <CalEngineClient.kt>
m5() {
    local page="$1" str="$2" client="$3" bad=0 sid want
    grep -qF 'why is CalEngineClient.Check.NotInstalled -> col.addView(emptyState(ctx,' "$page" \
        && grep -qF 'getString(R.string.cal_engine_missing_title), getString(R.string.cal_engine_missing, why.pkg)))' "$page" \
        || { echo "    a missing engine is not the cal_engine_missing line"; bad=1; }
    grep -qF 'why is CalEngineClient.Check.TooOld -> col.addView(emptyState(ctx,' "$page" \
        && grep -qF 'getString(R.string.cal_engine_old_title), getString(R.string.cal_engine_old, why.pkg, why.found, why.needed)))' "$page" \
        || { echo "    an engine too old for this build is not the cal_engine_old line"; bad=1; }
    grep -qF 'getString(R.string.cal_engine_failed_title), rows?.exceptionOrNull()?.message.orEmpty()))' "$page" \
        || { echo "    an engine call that failed is not said on the tab"; bad=1; }
    while IFS='|' read -r sid want; do
        grep -qE "name=\"$sid\">[^<]*$want" "$str" \
            || { echo "    $sid does not name its next step ($want)"; bad=1; }
    done <<'STEPS'
cal_engine_missing|install it from Store ▸ Cloud Constellation ▸ Libs
cal_engine_old|update it from Store ▸ Cloud Constellation ▸ Libs
STEPS
    # an {"error": …} answer must be thrown, never parsed into an empty list
    grep -qF 'runCatching { JSONArray(text) }.getOrElse {' "$client" \
        && grep -qE '^\s+throw IllegalStateException\(error' "$client" \
        || { echo "    an engine error answer is not thrown — it would draw as an empty agenda"; bad=1; }
    [ "$(_code "$page" | grep -cE '\bCalEngine\(|\bTodoStore\b|\bCalTodo\b')" -eq 0 ] \
        || { echo "    the tab still runs the calendar in-process"; bad=1; }
    local need
    for need in 'engine.todos()' 'engine.events(now, now + ' 'engine.sync()'; do
        grep -qF "$need" "$page" || { echo "    Agenda does not reach the engine for: $need"; bad=1; }
    done
    return $bad
}

echo "── M1 Cloud Me does not carry the calendar engine ──"
m1 "$BJ" "$GRADLE" "$MAIN" "$WF" && pass "no module entry, no gradle link, no import, and the ship workflow does not watch libs/cal" \
    || fail "libs:cal is compiled into Cloud Me again, or a calendar change still republishes it"
echo "── M2 the engine is declared and resolved from the fleet manifest ──"
m2 "$BJ" "$GRADLE" && pass "engines.cal names its Store row, action and contract; build.gradle resolves, refuses an unknown id and bakes all of it" \
    || fail "the cal engine is not declared, resolved or baked"
echo "── M3 the engine's package is visible to this app ──"
m3 "$MANIFEST" && pass "<queries> names \${calEnginePackage}" || fail "the engine would be invisible on API 30+"
echo "── M4 the contract is checked before anything binds ──"
m4 "$CLIENT" && pass "the service is resolved by action+package with its contract, a lower one is refused, and only then is it bound; every call goes through the handshake" \
    || fail "the client binds an engine it has not checked"
echo "── M5 missing, too-old and failed are loud, and name the Store ──"
m5 "$PAGE" "$STR" "$CLIENT" && pass "three lines, the first two naming Store ▸ Cloud Constellation ▸ Libs; Agenda reaches the calendar only through the engine" \
    || fail "a missing, old or failing engine is silent, or the tab still runs the calendar itself"

# ══ MUT ════════════════════════════════════════════════════════════════════
MUT="$(mktemp -d)"; trap 'rm -rf "$MUT"' EXIT
MUTATIONS=0; HOLLOW=0
_red() { local label="$1"; shift; MUTATIONS=$((MUTATIONS+1)); if "$@" >/dev/null 2>&1; then echo "  MUT-HOLLOW  $label — still passes"; HOLLOW=$((HOLLOW+1)); else echo "  MUT-RED     $label"; fi; }
_green() { local label="$1"; shift; "$@" >/dev/null 2>&1 && return 0; echo "  MUT-VOID    $label — unmutated already fails"; HOLLOW=$((HOLLOW+1)); return 1; }
# PROOF THE MUTATION APPLIED: the copy differs from the original AND carries the planted text.
_applied() {
    python3 -c 'import sys; a, b = (open(f, "rb").read() for f in sys.argv[1:3]); sys.exit(0 if a != b and sys.argv[3].encode() in b else 1)' "$1" "$2" "$3" \
        || { echo "  MUT-NOOP    the mutation did not apply ($3)"; HOLLOW=$((HOLLOW+1)); return 1; }
}
_gone() { python3 -c 'import sys; sys.exit(0 if sys.argv[2] not in open(sys.argv[1]).read() else 1)' "$1" "$2" \
        || { echo "  MUT-NOOP    the mutation did not remove ($2)"; HOLLOW=$((HOLLOW+1)); return 1; }; }
_sub() { python3 -c "import sys; p=sys.argv[1]; s=open(p).read(); open(p,'w').write(s.replace(sys.argv[2], sys.argv[3], 1))" "$1" "$2" "$3"; }
_json() { python3 -c "import json,sys; p=sys.argv[1]; d=json.load(open(p)); exec(sys.argv[2]); json.dump(d, open(p, 'w'), indent=1)" "$1" "$2"; }

echo "── MUT each property, broken on a copy, goes red ──"
# M1 — the module comes back, three ways, and the workflow watches it again
cp "$BJ" "$MUT/b.json"
_green m1 m1 "$MUT/b.json" "$GRADLE" "$MAIN" "$WF" && {
    _json "$MUT/b.json" 'd["modules"]["libs:cal"] = {"dir": "../ab_cloud-libs-shared/libs/cal", "type": "library"}'
    _applied "$BJ" "$MUT/b.json" '"libs:cal"' && _red "M1 build.json declares libs:cal again" m1 "$MUT/b.json" "$GRADLE" "$MAIN" "$WF"; }
cp "$GRADLE" "$MUT/app.gradle"
_green m1 m1 "$BJ" "$MUT/app.gradle" "$MAIN" "$WF" && {
    _sub "$MUT/app.gradle" "    implementation project(':libs:fin')" "    implementation project(':libs:fin')
    implementation project(':libs:cal')"
    _applied "$GRADLE" "$MUT/app.gradle" "project(':libs:cal')" && _red "M1 app/build.gradle links :libs:cal again" m1 "$BJ" "$MUT/app.gradle" "$MAIN" "$WF"; }
mkdir -p "$MUT/java/x"; cp "$PAGE" "$MUT/java/x/AgendaFragment.kt"
_green m1 m1 "$BJ" "$GRADLE" "$MUT/java" "$WF" && {
    _sub "$MUT/java/x/AgendaFragment.kt" 'import org.json.JSONArray' 'import com.diegonmarcos.superapp.cal.TodoStore
import org.json.JSONArray'
    _applied "$PAGE" "$MUT/java/x/AgendaFragment.kt" 'import com.diegonmarcos.superapp.cal.TodoStore' \
        && _red "M1 the tab imports the cal library again" m1 "$BJ" "$GRADLE" "$MUT/java" "$WF"; }
cp "$WF" "$MUT/wf.yml"
_green m1 m1 "$BJ" "$GRADLE" "$MAIN" "$MUT/wf.yml" && {
    _sub "$MUT/wf.yml" '      - "ab_cloud-libs-shared/libs/core/**"' '      - "ab_cloud-libs-shared/libs/cal/**"
      - "ab_cloud-libs-shared/libs/core/**"'
    _applied "$WF" "$MUT/wf.yml" 'libs/cal/**' && _red "M1 the ship workflow watches libs/cal again" m1 "$BJ" "$GRADLE" "$MAIN" "$MUT/wf.yml"; }
# M2 — the declaration rots
cp "$BJ" "$MUT/b.json"
_green m2 m2 "$MUT/b.json" "$GRADLE" && {
    _json "$MUT/b.json" 'd["engines"]["cal"]["min_contract"] = 0'
    _applied "$BJ" "$MUT/b.json" '"min_contract": 0' && _red "M2 a contract of 0 accepts any engine" m2 "$MUT/b.json" "$GRADLE"; }
cp "$BJ" "$MUT/b.json"
_green m2 m2 "$MUT/b.json" "$GRADLE" && {
    _json "$MUT/b.json" 'd["engines"]["cal"]["action"] = "com.diegonmarcos.cloudlib.cal.ENGINE"'
    _applied "$BJ" "$MUT/b.json" '"com.diegonmarcos.cloudlib.cal.ENGINE"' && _red "M2 the action is a typed package, not {package}" m2 "$MUT/b.json" "$GRADLE"; }
cp "$GRADLE" "$MUT/app.gradle"
_green m2 m2 "$BJ" "$MUT/app.gradle" && {
    python3 - "$MUT/app.gradle" <<'PYTHON'
import re, sys
p = sys.argv[1]; s = open(p).read()
s = re.sub(r"if \(calEngineFleet == null\) \{.*?\n\}", "if (calEngineFleet == null) {\n    calEngineFleet = [package: 'com.example.planted']\n}", s, count=1, flags=re.S)
open(p, "w").write(s)
PYTHON
    _applied "$GRADLE" "$MUT/app.gradle" 'com.example.planted' && _red "M2 an unknown fleet id falls back to a package instead of failing" m2 "$BJ" "$MUT/app.gradle"; }
cp "$GRADLE" "$MUT/app.gradle"
_green m2 m2 "$BJ" "$MUT/app.gradle" && {
    _sub "$MUT/app.gradle" '        buildConfigField "int",    "CAL_ENGINE_MIN_CONTRACT", "${calEngineMinContract}"
' ''
    _gone "$MUT/app.gradle" '"CAL_ENGINE_MIN_CONTRACT"' && _applied "$GRADLE" "$MUT/app.gradle" 'CAL_ENGINE_ACTION' \
        && _red "M2 the needed contract is not baked" m2 "$BJ" "$MUT/app.gradle"; }
# M3 — invisible
cp "$MANIFEST" "$MUT/AndroidManifest.xml"
_green m3 m3 "$MUT/AndroidManifest.xml" && {
    _sub "$MUT/AndroidManifest.xml" '        <package android:name="${calEnginePackage}" />
' ''
    _gone "$MUT/AndroidManifest.xml" '"${calEnginePackage}"' && _applied "$MANIFEST" "$MUT/AndroidManifest.xml" 'com.diegonmarcos.superapp' \
        && _red "M3 the engine's package is not queried" m3 "$MUT/AndroidManifest.xml"; }
# M4 — bind before the handshake, no contract floor, no handshake on a call, old reads as missing
cp "$CLIENT" "$MUT/c.kt"
_green m4 m4 "$MUT/c.kt" && {
    _sub "$MUT/c.kt" '        if (found < needed) return Check.TooOld(pkg, found, needed)
' ''
    _gone "$MUT/c.kt" 'if (found < needed)' && _applied "$CLIENT" "$MUT/c.kt" 'val found = service.metaData' \
        && _red "M4 an engine below the declared contract is bound anyway" m4 "$MUT/c.kt"; }
cp "$CLIENT" "$MUT/c.kt"
_green m4 m4 "$MUT/c.kt" && {
    python3 - "$MUT/c.kt" <<'PYTHON'
import sys
p = sys.argv[1]; s = open(p).read()
bind = """        if (client == null) synchronized(this) {
            if (client == null) client = DataBackendClient(ctx, service.packageName, service.name)
        }
"""
gate = "        if (found < needed) return Check.TooOld(pkg, found, needed)\n"
assert bind in s and gate in s
s = s.replace(bind, "").replace(gate, bind + gate)
open(p, "w").write(s)
PYTHON
    _applied "$CLIENT" "$MUT/c.kt" 'service.name)
        }
        if (found < needed)' && _red "M4 the engine is bound before its contract is checked" m4 "$MUT/c.kt"; }
cp "$CLIENT" "$MUT/c.kt"
_green m4 m4 "$MUT/c.kt" && {
    _sub "$MUT/c.kt" '        val why = check()
        val c = client
        if (why !is Check.Ready || c == null)' '        val c = client
        if (c == null)'
    _applied "$CLIENT" "$MUT/c.kt" '        if (c == null) throw' && _red "M4 a call reaches the engine without the handshake" m4 "$MUT/c.kt"; }
cp "$CLIENT" "$MUT/c.kt"
_green m4 m4 "$MUT/c.kt" && {
    _sub "$MUT/c.kt" 'return if (installed) Check.TooOld(pkg, 0, needed) else Check.NotInstalled(pkg)' 'return Check.NotInstalled(pkg)'
    _applied "$CLIENT" "$MUT/c.kt" '            return Check.NotInstalled(pkg)' && _red "M4 an engine APK too old to serve reads as not installed" m4 "$MUT/c.kt"; }
# M5 — silence, a failure drawn as empty, and the tab running the calendar itself
cp "$STR" "$MUT/strings.xml"
_green m5 m5 "$PAGE" "$MUT/strings.xml" "$CLIENT" && {
    _sub "$MUT/strings.xml" ' — update it from Store ▸ Cloud Constellation ▸ Libs.' '.'
    _applied "$STR" "$MUT/strings.xml" 'needs %3$d.<' && _red "M5 the too-old line names no next step" m5 "$PAGE" "$MUT/strings.xml" "$CLIENT"; }
cp "$PAGE" "$MUT/page.kt"
_green m5 m5 "$MUT/page.kt" "$STR" "$CLIENT" && {
    _sub "$MUT/page.kt" 'getString(R.string.cal_engine_old_title), getString(R.string.cal_engine_old, why.pkg, why.found, why.needed)))' 'getString(R.string.cal_engine_missing_title), getString(R.string.cal_engine_missing, why.pkg)))'
    _applied "$PAGE" "$MUT/page.kt" 'TooOld -> col.addView(emptyState(ctx,
                        getString(R.string.cal_engine_missing_title)' && _red "M5 too-old is worded as missing" m5 "$MUT/page.kt" "$STR" "$CLIENT"; }
cp "$CLIENT" "$MUT/c.kt"
_green m5 m5 "$PAGE" "$STR" "$MUT/c.kt" && {
    _sub "$MUT/c.kt" 'runCatching { JSONArray(text) }.getOrElse {' 'runCatching { JSONArray(text) }.getOrDefault(JSONArray()).let { return it }; run {'
    _applied "$CLIENT" "$MUT/c.kt" 'getOrDefault(JSONArray())' && _red "M5 an engine error is parsed into an empty list" m5 "$PAGE" "$STR" "$MUT/c.kt"; }
cp "$PAGE" "$MUT/page.kt"
_green m5 m5 "$MUT/page.kt" "$STR" "$CLIENT" && {
    _sub "$MUT/page.kt" 'if (todos) engine.todos()' 'if (todos) JSONArray(com.diegonmarcos.superapp.cal.CalEngine(requireContext()).todos(""))'
    _applied "$PAGE" "$MUT/page.kt" 'cal.CalEngine(requireContext())' && _red "M5 the todo tab runs the calendar in-process again" m5 "$MUT/page.kt" "$STR" "$CLIENT"; }

echo "── $MUTATIONS mutations, $HOLLOW hollow/void/no-op ──"
[ "$MUTATIONS" -ge 16 ] || { echo "  only $MUTATIONS mutations ran — a mutation block that stops early proves less than it prints"; FAILURES=$((FAILURES + 1)); }
[ "$HOLLOW" -eq 0 ] || FAILURES=$((FAILURES + HOLLOW))

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-me-cal-engine: all checks passed"; else echo "test-me-cal-engine: $FAILURES check(s) FAILED"; exit 1; fi
