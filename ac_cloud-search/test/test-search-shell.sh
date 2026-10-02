#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #779 Cloud Search — the declaration and the code that renders it agree,   ║
# ║ the network and the AI token each have one door, and no mock data ships   ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
#   S1  core/…/tax/Lohnsteuer2026.kt is exactly what core/tools/pap2kt.py makes of the BMF's
#       core/pap/Lohnsteuer2026.xml: the payroll tax cannot be hand-edited or drift from the
#       ministry's PAP it claims to be.
#   S2  the subpage kinds build.json::search.subpages declares and the kinds SearchShell's
#       `when (kind)` dispatches are the same set, both ways; every vertical's subpage is declared.
#   S3  every vertical icon has a branch in IconCatalog — a misspelt name would draw the fallback.
#   S4  every enabled api source names a parser Parsers.parse dispatches, every declared calculator
#       is a Calculators.run branch, and neither dispatch has a branch nothing declares.
#   S5  one network door: only core/Engine.kt (UrlHttp) opens a connection; no app or other core
#       source names java.net, HttpURLConnection, OkHttp or a socket, and the app declares INTERNET.
#   S6  one token door: only data/Account.kt calls revealAiKey; it and data/ChatFlow.kt never log,
#       and Account.kt writes nothing (no SharedPreferences, no file) — the fleet Account is the
#       only store of the OpenRouter token.
#   S7  /api/<group>/verticals, query and calc are documented and answered under
#       build.json::ui.debug_api.group, and no op is named `state` (GET /api/state's key).
#   S8  no mock data ships: no placeholder image host, lorem ipsum or `mock` identifier in app or
#       core sources, and no Kotlin colour literal (colours are res/values/colors.xml).
#   MUT each property, broken on a copy (and the edit proven to have landed), goes red.
#
# OWN-SOURCE ONLY: reads ac_cloud-search and nothing else. python3 + grep.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
K='app/src/main/java/com/diegonmarcos/cloudsearch'
C='core/src/main/kotlin/com/diegonmarcos/cloudsearch/core'
for f in build.json app/src/main/AndroidManifest.xml core/pap/Lohnsteuer2026.xml core/tools/pap2kt.py \
         "$C/tax/Lohnsteuer2026.kt" "$C/Listing.kt" "$C/Calculators.kt" "$C/Engine.kt" \
         "$K/ui/SearchShell.kt" "$K/ui/IconCatalog.kt" "$K/data/Account.kt" "$K/debugapi/SearchDebugApi.kt"; do
    [ -f "$APP/$f" ] || { echo "ERROR missing source: $APP/$f — this tester is unrun, not passing"; exit 1; }
done

CHECK="$(mktemp)"
trap 'rm -f "$CHECK"; rm -rf "${WORK:-}"' EXIT
cat > "$CHECK" <<'PY'
import glob, json, os, re, subprocess, sys

app = sys.argv[1]
bad = []
src = os.path.join(app, "app", "src", "main", "java", "com", "diegonmarcos", "cloudsearch")
core = os.path.join(app, "core", "src", "main", "kotlin", "com", "diegonmarcos", "cloudsearch", "core")

def code(p):
    """The file with comment lines dropped: prose may NAME what code must not do."""
    return "\n".join(l for l in open(p, encoding="utf-8").read().split("\n")
                     if not re.match(r"\s*(\*|//|/\*)", l))

kts = sorted(glob.glob(os.path.join(src, "**", "*.kt"), recursive=True))
cks = sorted(glob.glob(os.path.join(core, "**", "*.kt"), recursive=True))
bj = json.load(open(os.path.join(app, "build.json"), encoding="utf-8"))
S = bj["search"]

# S1
gen = subprocess.run([sys.executable, os.path.join(app, "core", "tools", "pap2kt.py"), os.path.join(app, "core", "pap", "Lohnsteuer2026.xml")],
                     capture_output=True, text=True)
committed = open(os.path.join(core, "tax", "Lohnsteuer2026.kt"), encoding="utf-8").read()
if gen.returncode != 0:
    bad.append("S1 pap2kt.py failed: %s" % gen.stderr.strip()[-200:])
elif gen.stdout.rstrip("\n") != committed.rstrip("\n"):
    bad.append("S1 tax/Lohnsteuer2026.kt differs from what pap2kt.py makes of core/pap/Lohnsteuer2026.xml — regenerate it, never edit it")

# S2
shell = code(os.path.join(src, "ui", "SearchShell.kt"))
m = re.search(r"when \(kind\) \{(.*?)\n        \}", shell, re.S)
dispatched = set(re.findall(r'^\s*"(\w+)" ->', m.group(1), re.M)) if m else set()
if not dispatched:
    bad.append("S2 SearchShell has no `when (kind)` this tester can read")
declared = {p["kind"] for p in S["subpages"]}
for k in sorted(declared - dispatched):
    bad.append("S2 subpage kind %s is declared but no page draws it" % k)
for k in sorted(dispatched - declared):
    bad.append("S2 SearchShell draws kind %s, which no subpage declares — dead page" % k)
sub_ids = {p["id"] for p in S["subpages"]}
for v in S["verticals"]:
    for p in v["subpages"]:
        if p not in sub_ids:
            bad.append("S2 vertical %s names subpage %s, which is not declared" % (v["id"], p))

# S3
ic = code(os.path.join(src, "ui", "IconCatalog.kt"))
icons = set(re.findall(r'^\s*"([\w-]+)" ->', ic, re.M))
for v in S["verticals"]:
    if v.get("icon") not in icons:
        bad.append("S3 vertical %s icon %r has no IconCatalog branch — it would draw the fallback" % (v["id"], v.get("icon")))

# S4
def branches(path, fn):
    t = code(path)
    m = re.search(r"fun %s\(.*?when \((\w+)\) \{(.*?)\n        else ->" % fn, t, re.S)
    return set(re.findall(r'^\s*"([\w-]+)" ->', m.group(2), re.M)) if m else set()
parsers = branches(os.path.join(core, "Listing.kt"), "parse")
used = {s["parser"] for s in S["sources"].values() if s.get("kind") == "api" and s.get("enabled") and s.get("parser")}
if not parsers:
    bad.append("S4 Parsers.parse has no dispatch this tester can read")
for p in sorted(used - parsers):
    bad.append("S4 a source uses parser %s, which Parsers.parse does not have" % p)
for p in sorted(parsers - used):
    bad.append("S4 Parsers.parse has parser %s, which no enabled source uses — dead parser" % p)
calcs = branches(os.path.join(core, "Calculators.kt"), "run")
for c in sorted(set(S["calculators"]) - calcs):
    bad.append("S4 calculator %s is declared but Calculators.run does not compute it" % c)
for c in sorted(calcs - set(S["calculators"])):
    bad.append("S4 Calculators.run computes %s, which no calculator declares — dead branch" % c)

# S5
net = re.compile(r"java\.net\.|HttpURLConnection|okhttp3|\bSocket\(|\bURL\(")
for p in kts + cks:
    hit = net.search(code(p))
    if hit and os.path.relpath(p, core) != "Engine.kt":
        bad.append("S5 %s uses the network (%s) — core/Engine.kt UrlHttp is the only door" % (os.path.basename(p), hit.group(0)))
manifest = open(os.path.join(app, "app", "src", "main", "AndroidManifest.xml"), encoding="utf-8").read()
if "android.permission.INTERNET" not in manifest:
    bad.append("S5 the manifest does not declare INTERNET — no source could be read")

# S6
holders = sorted(os.path.relpath(p, src) for p in kts if "revealAiKey" in code(p))
if holders != ["data/Account.kt"]:
    bad.append("S6 revealAiKey must be called by data/Account.kt alone, found in %s" % holders)
for rel in ("data/Account.kt", "data/ChatFlow.kt"):
    t = code(os.path.join(src, rel))
    if re.search(r"\bLog\.[a-z]\(|println\(|printStackTrace", t):
        bad.append("S6 %s logs — the token must never reach logcat" % rel)
acct = code(os.path.join(src, "data", "Account.kt"))
if re.search(r"SharedPreferences|getSharedPreferences|putString|writeText|FileOutputStream", acct):
    bad.append("S6 data/Account.kt stores something — the fleet Account is the token's only store")

# S7
api = code(os.path.join(src, "debugapi", "SearchDebugApi.kt"))
if "BuildConfig.DEBUG_API_GROUP" not in api:
    bad.append("S7 SearchDebugApi does not register under build.json::ui.debug_api.group")
for op in ("verticals", "query", "calc"):
    if not re.search(r'AppDebugServer\.Op\("%s"' % op, api) or not re.search(r'"%s" ->' % op, api):
        bad.append("S7 /api/<group>/%s is not both documented and answered" % op)
if re.search(r'"state"', api):
    bad.append("S7 SearchDebugApi names an op state — that key is GET /api/state's (update-ack guard)")
if not bj.get("ui", {}).get("debug_api", {}).get("group"):
    bad.append("S7 build.json::ui.debug_api.group is missing")

# S8
mock = re.compile(r"placehold\.co|picsum\.photos|lorem ipsum|\bmock\w*", re.I)
for p in kts + cks:
    hit = mock.search(code(p))
    if hit:
        bad.append("S8 %s carries mock data (%s) — the shipped app shows real sources only" % (os.path.basename(p), hit.group(0)))
    if re.search(r"Color\(0x", code(p)):
        bad.append("S8 %s names a colour literal — colours are res/values/colors.xml" % os.path.basename(p))

for b in bad:
    print("  FAIL  " + b)
sys.exit(1 if bad else 0)
PY

FAILURES=0
echo "── S1-S8 against the tree ──"
if python3 "$CHECK" "$APP"; then echo "  PASS  S1-S8"; else FAILURES=$((FAILURES + 1)); fi

# ── mutations: each must go red, for the right reason ─────────────────────────
WORK="$(mktemp -d)"
mutate() {  # name, file (relative to the app), python expression over s, expected message fragment
    local name="$1" rel="$2" expr="$3" want="$4" copy="$WORK/$1"
    mkdir -p "$copy"
    cp -r "$APP/build.json" "$APP/app" "$APP/core" "$copy/"
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
J="$K"
mutate pap-hand-edited "$C/tax/Lohnsteuer2026.kt" 's.replace("GFB = bd(12348)", "GFB = bd(12000)")' "S1 tax/Lohnsteuer2026.kt differs"
mutate pap-xml-changed core/pap/Lohnsteuer2026.xml 's.replace("BigDecimal.valueOf(20350)", "BigDecimal.valueOf(20000)")' "S1 tax/Lohnsteuer2026.kt differs"
mutate kind-without-page "$J/ui/SearchShell.kt" 's.replace("\"feed\" -> FeedPage(v)", "")' "S2 subpage kind feed is declared"
mutate dead-page "$J/ui/SearchShell.kt" 's.replace("\"saved\" -> SavedPage(v)", "\"saved\" -> SavedPage(v)\n            \"atlas\" -> SavedPage(v)")' "S2 SearchShell draws kind atlas"
mutate subpage-undeclared build.json 's.replace("\"subpages\": [\n          \"web\",", "\"subpages\": [\n          \"webz\",")' "names subpage webz"
mutate icon-misspelt build.json 's.replace("\"icon\": \"groceries\"", "\"icon\": \"grocerys\"")' "S3 vertical groceries icon"
mutate parser-missing "$C/Listing.kt" 's.replace("\"open_prices\" -> openPrices(body, source)", "")' "S4 a source uses parser open_prices"
mutate dead-parser "$C/Listing.kt" 's.replace("\"ba\" -> ba(body, source)", "\"ba\" -> ba(body, source)\n        \"immo\" -> ba(body, source)")' "S4 Parsers.parse has parser immo"
mutate calc-missing "$C/Calculators.kt" 's.replace("\"max_rent\" -> Result(", "\"max_rentx\" -> Result(")' "S4 calculator max_rent is declared"
mutate app-online "$J/ui/VerticalPages.kt" 's + "\nprivate val u = java.net.URL(\"https://example.org\")\n"' "S5 VerticalPages.kt uses the network"
mutate core-online "$C/Chat.kt" 's + "\nprivate fun leak() = java.net.Socket(\"x\", 1)\n"' "S5 Chat.kt uses the network"
mutate no-internet app/src/main/AndroidManifest.xml 's.replace("<uses-permission android:name=\"android.permission.INTERNET\" />", "")' "S5 the manifest does not declare INTERNET"
mutate token-second-door "$J/data/ChatFlow.kt" 's + "\nprivate fun peek(c: com.diegonmarcos.superapp.texttools.TextToolsClient) = c.revealAiKey(\"openrouter\")\n"' "S6 revealAiKey must be called by data/Account.kt alone"
mutate token-logged "$J/data/Account.kt" 's.replace("val r = c.revealAiKey(provider)", "val r = c.revealAiKey(provider)\n        android.util.Log.d(\"acct\", r.text.orEmpty())")' "S6 data/Account.kt logs"
mutate token-stored "$J/data/Account.kt" 's.replace("val r = c.revealAiKey(provider)", "val r = c.revealAiKey(provider)\n        ctx.getSharedPreferences(\"x\", 0).edit().putString(\"t\", r.text).apply()")' "S6 data/Account.kt stores something"
mutate debug-op-dropped "$J/debugapi/SearchDebugApi.kt" 's.replace("\"calc\" -> calc(Services.get(app), q).toString()", "")' "S7 /api/<group>/calc"
mutate debug-op-state "$J/debugapi/SearchDebugApi.kt" 's.replace("\"verticals\" -> verticals(", "\"state\" -> verticals(")' "S7 SearchDebugApi names an op state"
mutate mock-shipped "$J/ui/VerticalPages.kt" 's + "\nprivate val mockData = listOf(\"Modern Loft in Downtown\")\n"' "S8 VerticalPages.kt carries mock data"
mutate placeholder-image "$C/Listing.kt" 's + "\nprivate const val IMG = \"https://placehold.co/400x200\"\n"' "S8 Listing.kt carries mock data"
mutate colour-literal "$J/ui/SearchTheme.kt" 's + "\nprivate val x = androidx.compose.ui.graphics.Color(0xFF000000)\n"' "S8 SearchTheme.kt names a colour literal"

echo "── S1-S8 + mutations: $FAILURES failure(s) ──"
[ "$FAILURES" -eq 0 ]
