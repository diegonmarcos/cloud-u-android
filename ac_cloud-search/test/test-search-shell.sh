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
#   S3  every declared icon (vertical, engine, calculator) has a branch in IconCatalog — a misspelt
#       name would draw the fallback — and every declared colour name (engine accent, vertical
#       chart_color) is a colors.xml entry (#797).
#   S4  every enabled api source names a parser Parsers.parse dispatches, every declared calculator
#       is a Calculators.run branch, every declared series names a Series.parse branch (#797), and
#       no dispatch has a branch nothing declares.
#   S5  one network door: only core/Engine.kt (UrlHttp) opens a connection; no app or other core
#       source names java.net, HttpURLConnection, OkHttp or a socket, and the app declares INTERNET.
#   S6  one token door: only data/Account.kt calls revealAiKey; it and data/ChatFlow.kt never log,
#       and Account.kt writes nothing (no SharedPreferences, no file) — the fleet Account is the
#       only store of the OpenRouter token.
#   S7  /api/<group>/verticals, query, calc, analysis and feed are documented and answered under
#       build.json::ui.debug_api.group, and no op is named `state` (GET /api/state's key).
#   S8  no mock data ships: no placeholder image host, lorem ipsum or `mock` identifier in app or
#       core sources, and no Kotlin colour literal (colours are res/values/colors.xml).
#   S9  (#797) the Phosphor icon set is one list: app/tools/phosphor.json, the generated
#       res/drawable/ph_*.xml (each carrying the generator's header) and every R.drawable.ph_* the
#       Kotlin names agree, both ways.
#   S10 (#797) Cloud Search draws its OWN chrome (the owner's mockup): neither the fleet bottom-nav
#       island (libs:bottomnav) nor the fleet kit (libs:ui-kit) is in build.json's module graph or
#       imported by any Kotlin source, and the shell draws Glass.kt's BottomNav from the verticals.
#   S11 (#803) the app icon is the mockup's central nav icon: res/drawable/ic_launcher_foreground.xml
#       and ic_launcher_monochrome.xml are exactly what app/tools/phosphor2vd.py makes of
#       phosphor.json::launcher (the Phosphor glyph, the .ai-nav-icon gradient from colors.xml), and
#       both adaptive icons declare the foreground and the monochrome layer.
#   S12 (#803) the Search tab is ONE page, as the owner's renderUnifiedSearchChat: exactly one
#       vertical holds the `assistant` subpage and it declares no other (so no sub-nav, no second
#       page); AssistantPage draws it through libs:search-page's SearchChatPage (#823, shared with
#       Cloud Browser) with every declared engine, each filled by Templates.fill and opened through
#       SearchHost.openUrl = Browser.open; SearchChatPage itself draws the engine boxes (Welcome, a
#       box per engine) and the chat (Conversation + ChatBar); and SearchShellTest keeps the UI test
#       that asserts both parts inside that one page.
#   MUT each property, broken on a copy (and the edit proven to have landed), goes red.
#
# Reads ac_cloud-search and, for S12, the one lib it hosts its Search page from
# (ab_cloud-libs-shared/libs/search-page). python3 + grep.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
LIB="$(cd "$APP/../ab_cloud-libs-shared/libs/search-page" && pwd)"
K='app/src/main/java/com/diegonmarcos/cloudsearch'
C='core/src/main/kotlin/com/diegonmarcos/cloudsearch/core'
for f in build.json app/src/main/AndroidManifest.xml core/pap/Lohnsteuer2026.xml core/tools/pap2kt.py \
         "$C/tax/Lohnsteuer2026.kt" "$C/Listing.kt" "$C/Calculators.kt" "$C/Engine.kt" "$C/Market.kt" \
         "$K/ui/SearchShell.kt" "$K/ui/IconCatalog.kt" "$K/data/Account.kt" "$K/debugapi/SearchDebugApi.kt"; do
    [ -f "$APP/$f" ] || { echo "ERROR missing source: $APP/$f — this tester is unrun, not passing"; exit 1; }
done

CHECK="$(mktemp)"
trap 'rm -f "$CHECK"; rm -rf "${WORK:-}"' EXIT
cat > "$CHECK" <<'PY'
import glob, json, os, re, subprocess, sys

app = sys.argv[1]
splib = sys.argv[2]
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
declared_icons = [("vertical", v["id"], v.get("icon")) for v in S["verticals"]]
declared_icons += [("engine", e["id"], e.get("icon")) for e in S["engines"]]
declared_icons += [("calculator", k, c.get("icon")) for k, c in S["calculators"].items() if not k.startswith("_")]
for kind, ident, icon in declared_icons:
    if icon not in icons:
        bad.append("S3 %s %s icon %r has no IconCatalog branch — it would draw the fallback" % (kind, ident, icon))
colors = set(re.findall(r'<color name="([\w]+)"', open(os.path.join(app, "app", "src", "main", "res", "values", "colors.xml"), encoding="utf-8").read()))
declared_colors = [("engine", e["id"], e.get("accent")) for e in S["engines"]]
declared_colors += [("vertical", v["id"], v.get("chart_color")) for v in S["verticals"] if v.get("analysis", "none") != "none"]
for kind, ident, name in declared_colors:
    if name not in colors:
        bad.append("S3 %s %s colour %r is not in colors.xml — it would draw the accent instead" % (kind, ident, name))

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
declared_calcs = {k for k in S["calculators"] if not k.startswith("_")}
for c in sorted(declared_calcs - calcs):
    bad.append("S4 calculator %s is declared but Calculators.run does not compute it" % c)
for c in sorted(calcs - declared_calcs):
    bad.append("S4 Calculators.run computes %s, which no calculator declares — dead branch" % c)
sparsers = branches(os.path.join(core, "Market.kt"), "parse")
sused = {x["parser"] for k, x in S.get("series", {}).items() if not k.startswith("_")}
if not sparsers:
    bad.append("S4 Series.parse has no dispatch this tester can read")
for p in sorted(sused - sparsers):
    bad.append("S4 a series uses parser %s, which Series.parse does not have" % p)
for p in sorted(sparsers - sused):
    bad.append("S4 Series.parse has parser %s, which no series uses — dead parser" % p)

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
for op in ("verticals", "query", "calc", "analysis", "feed"):
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

# S9
ph = json.load(open(os.path.join(app, "app", "tools", "phosphor.json"), encoding="utf-8"))
listed = {"ph_" + n.replace("-", "_") for n in ph["regular"]} | {"ph_" + n.replace("-", "_") + "_fill" for n in ph["fill"]}
draw = os.path.join(app, "app", "src", "main", "res", "drawable")
on_disk = {f[:-4] for f in os.listdir(draw) if f.startswith("ph_") and f.endswith(".xml")}
for d in sorted(listed - on_disk):
    bad.append("S9 phosphor.json lists %s but res/drawable has no %s.xml — run app/tools/phosphor2vd.py" % (d, d))
for d in sorted(on_disk - listed):
    bad.append("S9 res/drawable/%s.xml is not in phosphor.json — an icon nothing regenerates" % d)
for d in sorted(on_disk):
    if "GENERATED by app/tools/phosphor2vd.py" not in open(os.path.join(draw, d + ".xml"), encoding="utf-8").read():
        bad.append("S9 res/drawable/%s.xml was not written by phosphor2vd.py — hand-drawn icons drift from the set" % d)
named = set()
for p in kts:
    named |= set(re.findall(r"R\.drawable\.(ph_\w+)", code(p)))
for d in sorted(named - on_disk):
    bad.append("S9 Kotlin draws R.drawable.%s, which phosphor.json does not generate" % d)

# S10
deps = bj["modules"]["app"]["depends_on"]
for lib in ("libs:bottomnav", "libs:ui-kit"):
    if lib in deps or lib in bj["modules"]:
        bad.append("S10 build.json links %s — Cloud Search draws its own chrome from the owner's mockup" % lib)
for p in kts:
    hit = re.search(r"import com\.diegonmarcos\.superapp\.(bottomnav|uikit)\.", code(p))
    if hit:
        bad.append("S10 %s imports the fleet %s — the app's own Glass.kt draws its chrome" % (os.path.basename(p), hit.group(1)))
if not re.search(r"\bBottomNav\(\s*entries = state\.cfg\.verticals\.map", shell):
    bad.append("S10 SearchShell does not draw Glass.kt's BottomNav from the declared verticals")

# S11
res = os.path.join(app, "app", "src", "main", "res")
try:
    import importlib.util
    spec_ = importlib.util.spec_from_file_location("phosphor2vd", os.path.join(app, "app", "tools", "phosphor2vd.py"))
    gen_ = importlib.util.module_from_spec(spec_)
    spec_.loader.exec_module(gen_)
    want_files = gen_.launcher_files(res, ph)
except Exception as e:  # a missing glyph, colour or key is a red, not a crash
    want_files = {}
    bad.append("S11 phosphor2vd.py cannot make the launcher icon from phosphor.json::launcher: %r" % e)
for rel, text in sorted(want_files.items()):
    p = os.path.join(res, rel)
    if not os.path.isfile(p) or open(p, encoding="utf-8").read() != text:
        bad.append("S11 res/%s is not what phosphor2vd.py makes of phosphor.json::launcher — run `python3 app/tools/phosphor2vd.py --launcher`, never edit it" % rel)
for icon in ("ic_launcher.xml", "ic_launcher_round.xml"):
    x = open(os.path.join(res, "mipmap-anydpi-v26", icon), encoding="utf-8").read()
    for layer in ('<foreground android:drawable="@drawable/ic_launcher_foreground"', '<monochrome android:drawable="@drawable/ic_launcher_monochrome"'):
        if layer not in x:
            bad.append("S11 mipmap-anydpi-v26/%s lacks %s" % (icon, layer.split()[0][1:]))

# S12
kinds = {sp["id"]: sp["kind"] for sp in S["subpages"]}
holders = [v for v in S["verticals"] if any(kinds.get(x) == "assistant" for x in v["subpages"])]
if len(holders) != 1:
    bad.append("S12 %d verticals hold the assistant subpage — the Search tab is exactly one" % len(holders))
for v in holders:
    if len(v["subpages"]) != 1:
        bad.append("S12 vertical %s declares subpages %s — the Search tab is ONE page (engines + chat), not a split" % (v["id"], v["subpages"]))
ap = code(os.path.join(src, "ui", "AssistantPages.kt"))
lp = code(os.path.join(splib, "src", "main", "kotlin", "com", "diegonmarcos", "superapp", "searchpage", "SearchPage.kt"))
host = code(os.path.join(src, "data", "SearchHost.kt"))
def body(fn, text):
    m = re.search(r"fun %s\(.*?\n\}" % fn, text, re.S)
    return m.group(0) if m else ""
page, shared, welcome = body("AssistantPage", ap), body("SearchChatPage", lp), body("Welcome", lp)
if "SearchChatPage(" not in page:
    bad.append("S12 AssistantPage does not draw libs:search-page's SearchChatPage")
if not re.search(r"engines = state\.cfg\.engines\.map", page) or "Templates.fill(" not in page:
    bad.append("S12 AssistantPage does not hand the page every declared engine, filled by Templates.fill")
for call in ("Welcome(", "Conversation(", "ChatBar("):
    if call not in shared:
        bad.append("S12 SearchChatPage does not draw %s) — both halves of the Search tab live in the one page" % call[:-1])
if not re.search(r"engines\.forEachIndexed", welcome) or "host::openUrl" not in welcome:
    bad.append("S12 Welcome does not draw a box per declared engine opening its results through the host")
if not re.search(r"override fun openUrl\(url: String\) = Browser\.open\(", host):
    bad.append("S12 SearchHost does not open a result in cloud-browser (Browser.open)")
ui_test = open(os.path.join(app, "app", "src", "test", "java", "com", "diegonmarcos", "cloudsearch", "SearchShellTest.kt"), encoding="utf-8").read()
if not re.search(r"@Test fun theSearchTabIsOnePageWithTheEnginesAndTheChat\(\)", ui_test) \
        or 'hasAnyAncestor(hasTestTag(Tags.page("assistant")))' not in ui_test:
    bad.append("S12 SearchShellTest lost theSearchTabIsOnePageWithTheEnginesAndTheChat, the UI test of the one page")

for b in bad:
    print("  FAIL  " + b)
sys.exit(1 if bad else 0)
PY

FAILURES=0
echo "── S1-S12 against the tree ──"
if python3 "$CHECK" "$APP" "$LIB"; then echo "  PASS  S1-S12"; else FAILURES=$((FAILURES + 1)); fi

# ── mutations: each must go red, for the right reason ─────────────────────────
WORK="$(mktemp -d)"
mutate() {  # name, file (relative to the app), python expression over s, expected message fragment
    local name="$1" rel="$2" expr="$3" want="$4" copy="$WORK/$1"
    mkdir -p "$copy"
    cp -r "$APP/build.json" "$APP/app" "$APP/core" "$copy/"
    cp -r "$LIB" "$copy/search-page"
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
    out="$(python3 "$CHECK" "$copy" "$copy/search-page" 2>&1)"
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
mutate dead-page "$J/ui/SearchShell.kt" 's.replace("\"assistant\" -> AssistantPage(v)", "\"assistant\" -> AssistantPage(v)\n            \"atlas\" -> AssistantPage(v)")' "S2 SearchShell draws kind atlas"
mutate subpage-undeclared build.json 's.replace("\"listing\",\n          \"analysis\",", "\"listing\",\n          \"analysiz\",")' "names subpage analysiz"
mutate icon-misspelt build.json 's.replace("\"icon\": \"groceries\"", "\"icon\": \"grocerys\"")' "S3 vertical groceries icon"
mutate engine-icon-misspelt build.json 's.replace("\"icon\": \"bird\"", "\"icon\": \"birb\"")' "S3 engine duckduckgo icon"
mutate calc-icon-misspelt build.json 's.replace("\"icon\": \"wallet\"", "\"icon\": \"walet\"")' "S3 calculator max_rent icon"
mutate accent-missing build.json 's.replace("\"accent\": \"engine_brave\"", "\"accent\": \"engine_bravo\"")' "S3 engine brave colour"
mutate chart-colour-missing app/src/main/res/values/colors.xml 's.replace("<color name=\"chart_jobs\">", "<color name=\"chart_job\">")' "S3 vertical jobs colour"
mutate phosphor-unlisted app/tools/phosphor.json 's.replace("\"warning\", ", "")' "S9 res/drawable/ph_warning.xml is not in phosphor.json"
mutate phosphor-listed-missing app/tools/phosphor.json 's.replace("\"wallet\",", "\"wallet\", \"rocket\",")' "S9 phosphor.json lists ph_rocket"
mutate hand-drawn-icon app/src/main/res/drawable/ph_x.xml 's.replace("GENERATED by app/tools/phosphor2vd.py", "drawn by hand")' "S9 res/drawable/ph_x.xml was not written"
mutate icon-not-generated "$J/ui/SearchShell.kt" 's.replace("R.drawable.ph_caret_down", "R.drawable.ph_caret_up")' "S9 Kotlin draws R.drawable.ph_caret_up"
mutate fleet-nav-linked build.json 's.replace("\"libs:core\",\n        \"libs:text-tools\"", "\"libs:core\",\n        \"libs:bottomnav\",\n        \"libs:text-tools\"")' "S10 build.json links libs:bottomnav"
mutate fleet-kit-imported "$J/ui/SearchTheme.kt" 's.replace("import com.diegonmarcos.cloudsearch.R\n", "import com.diegonmarcos.cloudsearch.R\nimport com.diegonmarcos.superapp.uikit.KitCard\n")' "S10 SearchTheme.kt imports the fleet uikit"
mutate own-nav-dropped "$J/ui/SearchShell.kt" 's.replace("BottomNav(\n                        entries = state.cfg.verticals.map", "NavRail(\n                        entries = state.cfg.verticals.map")' "S10 SearchShell does not draw"
mutate parser-missing "$C/Listing.kt" 's.replace("\"open_prices\" -> openPrices(body, source)", "")' "S4 a source uses parser open_prices"
mutate dead-parser "$C/Listing.kt" 's.replace("\"ba\" -> ba(body, source)", "\"ba\" -> ba(body, source)\n        \"immo\" -> ba(body, source)")' "S4 Parsers.parse has parser immo"
mutate series-parser-missing "$C/Market.kt" 's.replace("\"jsonstat\" -> jsonStat(body)", "")' "S4 a series uses parser jsonstat"
mutate dead-series-parser "$C/Market.kt" 's.replace("\"sdmx_json\" -> sdmxJson(body)", "\"sdmx_json\" -> sdmxJson(body)\n        \"csv\" -> sdmxJson(body)")' "S4 Series.parse has parser csv"
mutate debug-feed-dropped "$J/debugapi/SearchDebugApi.kt" 's.replace("\"feed\" -> feed(Services.get(app), q).toString()", "")' "S7 /api/<group>/feed"
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

mutate launcher-hand-edited app/src/main/res/drawable/ic_launcher_foreground.xml 's.replace("#FFFF416C", "#FFFF0000")' "S11 res/drawable/ic_launcher_foreground.xml is not what"
mutate launcher-icon-changed app/tools/phosphor.json 's.replace("\"icon\": \"shooting-star\"", "\"icon\": \"robot\"")' "S11 res/drawable/ic_launcher_foreground.xml is not what"
mutate launcher-colour-moved app/src/main/res/values/colors.xml 's.replace("<color name=\"ai_nav_2\">#FF8A2387", "<color name=\"ai_nav_2\">#FF8A2388")' "S11 res/drawable/ic_launcher_foreground.xml is not what"
mutate launcher-glyph-missing app/tools/phosphor.json 's.replace("\"icon\": \"shooting-star\"", "\"icon\": \"comet\"")' "S11 phosphor2vd.py cannot make the launcher icon"
mutate monochrome-dropped app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml 's.replace("    <monochrome android:drawable=\"@drawable/ic_launcher_monochrome\" />\n", "")' "S11 mipmap-anydpi-v26/ic_launcher_round.xml lacks monochrome"
mutate search-split build.json 's.replace("\"subpages\": [\n          \"assistant\"\n        ]", "\"subpages\": [\n          \"assistant\",\n          \"listing\"\n        ]")' "S12 vertical search declares subpages"
mutate assistant-twice build.json 's.replace("\"subpages\": [\n          \"listing\"\n        ],\n        \"sources\": [\n          \"open-prices\"", "\"subpages\": [\n          \"assistant\"\n        ],\n        \"sources\": [\n          \"open-prices\"")' "S12 2 verticals hold the assistant subpage"
SP=search-page/src/main/kotlin/com/diegonmarcos/superapp/searchpage
mutate chat-moved-out "$SP/SearchPage.kt" 's.replace("        ChatBar(\n            text,", "        NoBar(\n            text,")' "S12 SearchChatPage does not draw ChatBar"
mutate engines-moved-out "$SP/SearchPage.kt" 's.replace("if (chat.session.messages.isEmpty() && !chat.sending) Welcome(", "if (chat.session.messages.isEmpty() && !chat.sending) Unit; if (false) Wx(")' "S12 SearchChatPage does not draw Welcome"
mutate engine-boxes-dropped "$SP/SearchPage.kt" 's.replace("engines.forEachIndexed", "emptyList<SpEngine>().forEachIndexed")' "S12 Welcome does not draw a box per declared engine"
mutate page-not-shared "$J/ui/AssistantPages.kt" 's.replace("    SearchChatPage(\n", "    OwnPage(\n")' "S12 AssistantPage does not draw libs:search-page"
mutate engines-not-handed "$J/ui/AssistantPages.kt" 's.replace("engines = state.cfg.engines.map", "engines = emptyList<SearchConfig.Engine>().map")' "S12 AssistantPage does not hand the page every declared engine"
mutate result-not-in-browser "$J/data/SearchHost.kt" 's.replace("override fun openUrl(url: String) = Browser.open(", "override fun openUrl(url: String) = println(")' "S12 SearchHost does not open"
mutate one-page-test-dropped app/src/test/java/com/diegonmarcos/cloudsearch/SearchShellTest.kt 's.replace("fun theSearchTabIsOnePageWithTheEnginesAndTheChat()", "fun theSearchTabComposes()")' "S12 SearchShellTest lost"

echo "── S1-S12 + mutations: $FAILURES failure(s) ──"
[ "$FAILURES" -eq 0 ]
