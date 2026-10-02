#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #767 Cloud Calc — the declaration and the code that renders it agree,     ║
# ║ the microphone is asked for in one place, and no maths lives in the app   ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
#   C1  every declared tab has a mode, every mode names a declared tab, ids unique.
#   C2  the kinds build.json declares and the kinds ModeScreen's `when (mode.kind)`
#       dispatches are the same set, both ways: a declared kind with no renderer
#       draws the "no renderer" line, a renderer no mode uses is dead code.
#   C3  every declared tab icon has a branch in IconCatalog — a misspelt name would
#       silently draw the fallback.
#   C4  RECORD_AUDIO: the manifest declares it and exactly one Kotlin file
#       (MeterScreen.kt) asks for it, so no other mode can ever pop the dialog.
#   C5  the engine stays an engine: no gradle file, settings or module map of this
#       app names libs:calc, and no app source loads a native library — every
#       calculation crosses to Cloud-Lib-Calc.
#   C6  offline: no app source opens a URL or a socket (the only network use is the
#       engine's rate download, in libs:calc).
#   C7  the debug API registers eval, modes and info under build.json::ui.debug_api.group.
#   MUT each property, broken on a copy (and the edit proven to have landed), goes red.
#
# OWN-SOURCE ONLY: reads ac_cloud-calc and nothing else. python3 + grep.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
for f in "$APP/build.json" "$APP/app/src/main/AndroidManifest.xml" \
         "$APP/app/src/main/java/com/diegonmarcos/cloudcalc/ui/ModeScreens.kt" \
         "$APP/app/src/main/java/com/diegonmarcos/cloudcalc/ui/IconCatalog.kt" \
         "$APP/app/src/main/java/com/diegonmarcos/cloudcalc/debugapi/CalcDebugApi.kt"; do
    [ -f "$f" ] || { echo "ERROR missing source: $f — this tester is unrun, not passing"; exit 1; }
done

CHECK="$(mktemp)"
trap 'rm -f "$CHECK"; rm -rf "${WORK:-}"' EXIT
cat > "$CHECK" <<'PY'
import glob, json, os, re, sys

app = sys.argv[1]
bad = []
src = os.path.join(app, "app", "src", "main", "java", "com", "diegonmarcos", "cloudcalc")

def code(p):
    """The file with comment lines dropped: prose may NAME what code must not do."""
    return "\n".join(l for l in open(p, encoding="utf-8").read().split("\n")
                     if not re.match(r"\s*(\*|//|/\*)", l))

kts = sorted(glob.glob(os.path.join(src, "**", "*.kt"), recursive=True))
bj = json.load(open(os.path.join(app, "build.json"), encoding="utf-8"))
tabs, modes = bj["ui"]["tabs"], bj["ui"]["modes"]

# C1
tab_ids = [t["id"] for t in tabs]
for t in tab_ids:
    if not any(m["tab"] == t for m in modes):
        bad.append("C1 tab %s has no mode — the nav would open an empty page" % t)
for m in modes:
    if m["tab"] not in tab_ids:
        bad.append("C1 mode %s names tab %s, which is not declared — it can never be reached" % (m["id"], m["tab"]))
for kind, ids in (("tab", tab_ids), ("mode", [m["id"] for m in modes])):
    if len(ids) != len(set(ids)):
        bad.append("C1 duplicate %s ids: %s" % (kind, sorted(i for i in set(ids) if ids.count(i) > 1)))

# C2
ms = code(os.path.join(src, "ui", "ModeScreens.kt"))
m = re.search(r"when \(mode\.kind\) \{(.*?)\n        \}", ms, re.S)
dispatched = set(re.findall(r'^\s*"(\w+)" ->', m.group(1), re.M)) if m else set()
if not dispatched:
    bad.append("C2 ModeScreen has no `when (mode.kind)` this tester can read")
declared = {m["kind"] for m in modes}
for k in sorted(declared - dispatched):
    bad.append("C2 kind %s is declared but ModeScreen renders no such kind" % k)
for k in sorted(dispatched - declared):
    bad.append("C2 ModeScreen renders kind %s, which no mode declares — dead renderer" % k)

# C3
ic = code(os.path.join(src, "ui", "IconCatalog.kt"))
icons = set(re.findall(r'^\s*"([\w-]+)" ->', ic, re.M))
for t in tabs:
    if t.get("icon") not in icons:
        bad.append("C3 tab %s icon %r has no IconCatalog branch — it would draw the fallback" % (t["id"], t.get("icon")))

# C4
manifest = open(os.path.join(app, "app", "src", "main", "AndroidManifest.xml"), encoding="utf-8").read()
if 'android.permission.RECORD_AUDIO' not in manifest:
    bad.append("C4 the manifest does not declare RECORD_AUDIO — the meter could never be granted")
askers = sorted(os.path.relpath(p, src) for p in kts if "RECORD_AUDIO" in code(p))
if askers != ["ui/MeterScreen.kt"]:
    bad.append("C4 RECORD_AUDIO must be asked for by ui/MeterScreen.kt alone, found in %s" % askers)

# C5
names_engine = re.compile(r"libs[:/]calc\b")
for f in [os.path.join(app, "build.json"), os.path.join(app, "settings.gradle"), os.path.join(app, "build.gradle"),
          os.path.join(app, "app", "build.gradle")]:
    if os.path.isfile(f):
        text = open(f, encoding="utf-8").read() if f.endswith(".json") else code(f)
        if f.endswith(".json"):  # module ids and dirs only: the _doc prose says libs:calc is NOT there
            text = " ".join(k + " " + str(v.get("dir", "")) for k, v in bj.get("modules", {}).items() if isinstance(v, dict))
        if names_engine.search(text):
            bad.append("C5 %s names libs:calc — the app would compile the engine" % os.path.relpath(f, app))
for p in kts:
    if re.search(r"System\.loadLibrary|external fun ", code(p)):
        bad.append("C5 %s loads native code — the maths belongs to Cloud-Lib-Calc" % os.path.relpath(p, src))

# C6
net = re.compile(r"java\.net\.|HttpURLConnection|okhttp3|\bSocket\(|URL\(")
for p in kts:
    hit = net.search(code(p))
    if hit:
        bad.append("C6 %s uses the network (%s) — the app is offline, rates are the engine's" % (os.path.relpath(p, src), hit.group(0)))

# C7
api = code(os.path.join(src, "debugapi", "CalcDebugApi.kt"))
if "BuildConfig.DEBUG_API_GROUP" not in api:
    bad.append("C7 CalcDebugApi does not register under build.json::ui.debug_api.group")
for op in ("eval", "modes", "info"):
    if not re.search(r'AppDebugServer\.Op\("%s"' % op, api) or not re.search(r'"%s" ->' % op, api):
        bad.append("C7 /api/<group>/%s is not both documented and answered" % op)
if not bj["ui"].get("debug_api", {}).get("group"):
    bad.append("C7 build.json::ui.debug_api.group is missing")

for b in bad:
    print("  FAIL  " + b)
sys.exit(1 if bad else 0)
PY

FAILURES=0
echo "── C1-C7 against the tree ──"
if python3 "$CHECK" "$APP"; then echo "  PASS  C1-C7"; else FAILURES=$((FAILURES + 1)); fi

# ── mutations: each must go red, for the right reason ─────────────────────────
WORK="$(mktemp -d)"
mutate() {  # name, file (relative to the app), python expression over s, expected message fragment
    local name="$1" rel="$2" expr="$3" want="$4" copy="$WORK/$1"
    mkdir -p "$copy"
    cp -r "$APP/build.json" "$APP/app" "$copy/"
    for f in settings.gradle build.gradle; do [ -f "$APP/$f" ] && cp "$APP/$f" "$copy/"; done
    if ! python3 - "$copy/$rel" "$expr" <<'PY'
import sys
p, expr = sys.argv[1], sys.argv[2]
s = open(p, encoding="utf-8").read()
t = eval(expr)
if t == s:
    sys.exit(1)
open(p, "w", encoding="utf-8").write(t)
PY
    then echo "  VOID  MUT $name: the edit did not land — the mutation targets text that moved"; FAILURES=$((FAILURES + 1)); return; fi
    local out
    out="$(python3 "$CHECK" "$copy" 2>&1)"
    if [ $? -eq 0 ]; then
        echo "  FAIL  MUT $name: the check passed a broken tree"; FAILURES=$((FAILURES + 1))
    elif [[ "$out" != *"$want"* ]]; then
        echo "  FAIL  MUT $name: red for the wrong reason: $out"; FAILURES=$((FAILURES + 1))
    else
        echo "  PASS  MUT $name"
    fi
}
J='app/src/main/java/com/diegonmarcos/cloudcalc'
mutate tab-without-mode build.json 's.replace("\"tab\": \"graph\"", "\"tab\": \"calc\"")' "C1 tab graph has no mode"
mutate mode-orphan-tab build.json 's.replace("\"tab\": \"history\"", "\"tab\": \"nowhere\"", 1)' "names tab nowhere"
mutate kind-without-renderer "$J/ui/ModeScreens.kt" 's.replace("\"plot\" -> PlotMode(mode)", "")' "C2 kind plot is declared"
mutate dead-renderer "$J/ui/ModeScreens.kt" 's.replace("\"history\" -> HistoryMode(mode)", "\"history\" -> HistoryMode(mode)\n            \"abacus\" -> HistoryMode(mode)")' "C2 ModeScreen renders kind abacus"
mutate icon-misspelt build.json 's.replace("\"icon\": \"chart\"", "\"icon\": \"chrat\"")' "C3 tab graph icon"
mutate mic-elsewhere "$J/ui/ModeScreens.kt" 's + "\nprivate val leak = android.Manifest.permission.RECORD_AUDIO\n"' "C4 RECORD_AUDIO must be asked for"
mutate mic-undeclared app/src/main/AndroidManifest.xml 's.replace("<uses-permission android:name=\"android.permission.RECORD_AUDIO\" />", "")' "C4 the manifest does not declare"
mutate engine-in-module-map build.json 's.replace("\"libs:bottomnav\": {", "\"libs:calc\": {\"dir\": \"../ab_cloud-libs-shared/libs/calc\"},\n    \"libs:bottomnav\": {")' "C5 build.json names libs:calc"
mutate engine-compiled app/build.gradle 's.replace("implementation project(\x27:libs:bottomnav\x27)", "implementation project(\x27:libs:bottomnav\x27)\n    implementation project(\x27:libs:calc\x27)")' "C5 app/build.gradle names libs:calc"
mutate native-in-app "$J/Logic.kt" 's + "\nprivate object Q { init { System.loadLibrary(\"qalc\") } }\n"' "C5 Logic.kt loads native code"
mutate app-online "$J/Logic.kt" 's + "\nprivate val u = java.net.URL(\"https://example.org\")\n"' "C6 Logic.kt uses the network"
mutate debug-op-dropped "$J/debugapi/CalcDebugApi.kt" 's.replace("\"modes\" -> modesJson()", "")' "C7 /api/<group>/modes"

echo "── C1-C7 + mutations: $FAILURES failure(s) ──"
[ "$FAILURES" -eq 0 ]
