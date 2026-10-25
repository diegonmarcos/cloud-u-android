#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ test-superintel-app — Cloud SuperIntel (SI), statically          ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The chat itself (page, streaming, sessions, Markdown) is libs:ai's and is proven there (AiProtocolTest,
# AiMarkdownTest). This proves, from the sources alone, that this app only ASSEMBLES it, on the fleet's bars:
#   B1  every section and page id Decl.kt dispatches on is declared by build.json::ui, and every declared page is dispatched (both ways);
#       the bottom nav is Notebook, SI, News; the SI pages are exactly assets/si/personas.json and the News pages exactly assets/si/news.json (both ways)
#   B2  the shell draws the shared bars: first-row scaffold (Profile -> Cloud Account launcher), second row, bottom nav host,
#       theme-background; the nav libs are in build.json modules AND in the app's build script
#   B3  the chat is libs:ai's: AiChatPage + the sessions drawer panel in the top bar's drawer + Markdown on; no chat of this app's own
#   B4  the backend is the fleet gateway: the gateway script applied, BuildConfig.AI_GATEWAY_URL baked and read, and no token handling at all
#       (no revealAiKey, no EncryptedSharedPreferences, no key-shaped literal): decisions-use guard D5
#   B6  the three sections are data and honest: the news backend address is baked and read, the notebook refuses out of scope and cites,
#       no article or answer is spelled in Kotlin
#   B5  Profile opens Cloud Account, the manifest can see its package, the launcher shows the app and Share > SI is declared
# then plants a mutation per rule in a scratch copy and requires RED for each.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {   # check <module dir> -> prints FAIL lines, exit 1 on any
python3 - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
bad = []
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def code(p):
    t = re.sub(r"/\*.*?\*/", "", read(p), flags=re.S)
    return "\n".join(re.sub(r"(?<![:\"])//.*$", "", l) for l in t.splitlines())
bj = json.loads(read(os.path.join(app, "build.json")))
kt = os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudsuperintel")
decl, shell, ai, main = (code(os.path.join(kt, f)) for f in ("Decl.kt", "ui/SiShell.kt", "SiAi.kt", "MainActivity.kt"))
script = code(os.path.join(app, "app/build." + "gradle"))
manifest = read(os.path.join(app, "app/src/main/AndroidManifest.xml"))

# B1
declared_sections = {s["id"] for s in bj["ui"]["sections"]}
DATA_SECTIONS = ("si", "news")   # their pages are the ids of assets/si/*.json, not Kotlin constants
declared_pages = {p["id"] for s in bj["ui"]["sections"] if s["id"] not in DATA_SECTIONS for p in s["pages"]}
named_sections = set(re.findall(r'SECTION_\w+\s*=\s*"([^"]+)"', decl))
named_pages = set(re.findall(r'PAGE_\w+\s*=\s*"([^"]+)"', decl))
for x in sorted(named_sections - declared_sections): bad.append("B1 Decl.kt names section %r that build.json::ui does not declare" % x)
for x in sorted(named_pages - declared_pages): bad.append("B1 Decl.kt names page %r that build.json::ui does not declare" % x)
for x in sorted(declared_pages - named_pages): bad.append("B1 page %r is declared but Decl.kt has no id for it (the shell would draw nothing)" % x)
for x in sorted(set(bj["ui"]["bottom_nav"]) - declared_sections): bad.append("B1 bottom_nav id %r is no section" % x)
if bj["ui"]["bottom_nav"] != ["notebook", "si", "news"]: bad.append("B1 the bottom nav must be Notebook, SI, News, in that order")
def ids_of(section): return [p["id"] for s in bj["ui"]["sections"] if s["id"] == section for p in s["pages"]]
def data(name): return json.loads(read(os.path.join(app, "app/src/main/assets/si", name)))
if ids_of("si") != [p["id"] for p in data("personas.json")["personas"]]: bad.append("B1 the SI pages are not exactly the personas of personas.json")
if ids_of("news") != [t["id"] for t in data("news.json")["topics"]]: bad.append("B1 the News pages are not exactly the topics of news.json")
for s in bj["ui"]["sections"]:
    for p in s["pages"]:
        if not p.get("icon"): bad.append("B1 page %r has no icon (the second row draws one per tab)" % p["id"])

# B2
for need in ("NavBarTopFirstRowScaffold(", "NavBarTopSecondRow(", "NavBarBottomHost(", "themeBackground(", "rememberAccountLauncher()", "titleHierarchy("):
    if need not in shell: bad.append("B2 SiShell does not call %s" % need)
mods = bj["modules"]
for lib in ("libs:ui-nav-bars", "libs:ui-nav-bars", "libs:ui-nav-bars", "libs:theme-background", "libs:bottomnav", "libs:ai"):
    if lib not in mods: bad.append("B2 build.json modules lack %s" % lib)
    if "project(':%s')" % lib not in script: bad.append("B2 the app's build script does not link %s" % lib)

# B3
for need in ("AiChatPage(", "AiSessionsPanel(", "TopBarDrawer", "markdown = true"):
    if need not in shell: bad.append("B3 SiShell lacks %s" % need)
if re.search(r"\b(LazyColumn|BasicTextField|OutlinedTextField)\b", shell): bad.append("B3 SiShell draws list or input widgets of its own: the chat is libs:ai's")

# B4
if "ai-gateway" not in script or "AI_GATEWAY_URL" not in script: bad.append("B4 the app's build script does not bake the gateway address")
if "BuildConfig.AI_GATEWAY_URL" not in ai: bad.append("B4 SiAi does not read BuildConfig.AI_GATEWAY_URL")
for dp, dn, fn in os.walk(os.path.join(app, "app/src/main")):
    for f in fn:
        if f.endswith((".kt", ".java")):
            t = code(os.path.join(dp, f))
            for pat in (r"revealAiKey", r"EncryptedSharedPreferences", r"sk-or-[A-Za-z0-9]", r"android\.util\.Log", r"Authorization"):
                if pat == "Authorization" and f == "FleetNews.kt": continue   # the one declared place the fleet bearer is read on use and sent to the public origin; never stored or logged
                if re.search(pat, t): bad.append("B4 %s matches %s: this app holds and logs no token" % (f, pat))

# B5
if 'android:name="com.diegonmarcos.cloudaccount"' not in manifest: bad.append("B5 the manifest cannot see Cloud Account (queries)")
if "android.intent.category.LAUNCHER" not in manifest: bad.append("B5 no launcher activity")
if "android.intent.action.SEND" not in manifest or "ACTION_SEND" not in main: bad.append("B5 Share > SI is not declared and handled")
if bj["android"]["application_id"] != "com.diegonmarcos.cloudsuperintel": bad.append("B5 application_id drifted")

# B6
if "NEWS_MESH_URL" not in script or "fleetMeshUrl('news')" not in script: bad.append("B6 the app's build script does not bake NEWS_MESH_URL from the fleet news entry")
if "topics.json" in script: bad.append("B6 the app's build script reads topics.json for the news address: it is declared in fleet_endpoints")
if "BuildConfig.NEWS_MESH_URL" not in code(os.path.join(kt, "FleetNews.kt")): bad.append("B6 FleetNews does not read BuildConfig.NEWS_MESH_URL")
nb = data("notebook.json")
if not nb["refusal"].strip() or "{{refusal}}" not in nb["grounding"]["system"]: bad.append("B6 the notebook declares no refusal line in its grounding prompt")
notebook_kt = code(os.path.join(kt, "Notebook.kt"))
if "fun check(" not in notebook_kt or "cfg.refusal" not in notebook_kt: bad.append("B6 Notebook.kt does not hold answers to their citations")
for dp, dn, fn in os.walk(os.path.join(app, "app/src/main")):
    for f in fn:
        if f.endswith(".kt"):
            t = code(os.path.join(dp, f))
            if re.search(r'Article\(\s*"https?://', t) or re.search(r'Story\(\s*"', t): bad.append("B6 %s spells a news article in Kotlin: no mock data" % f)

for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }

echo "[superintel] assembly rules"
out="$(check "$APP")" && ok "B1-B5 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

# mutations on a scratch copy: each rule must go red
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
SCRIPT="app/build.gra""dle"
mutant() {   # mutant <name> <shell edit run in the copy> <needle expected in the output>
  rm -rf "$W/app"; cp -r "$APP" "$W/app"
  ( cd "$W/app" && eval "$2" )
  if out="$(check "$W/app" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
KT=app/src/main/java/com/diegonmarcos/cloudsuperintel
mutant "page dropped from Decl"        "sed -i '/PAGE_MODELS/d' $KT/Decl.kt"                                  "B1 page 'models' is declared"
mutant "shell loses the second row"    "sed -i 's/NavBarTopSecondRow(/Foo(/' $KT/ui/SiShell.kt"               "B2 SiShell does not call NavBarTopSecondRow("
mutant "drawer loses the sessions"     "sed -i 's/AiSessionsPanel(/Foo(/' $KT/ui/SiShell.kt"                  "B3 SiShell lacks AiSessionsPanel("
mutant "markdown off"                  "sed -i 's/markdown = true/markdown = false/' $KT/ui/SiShell.kt"        "B3 SiShell lacks markdown = true"
mutant "bottom nav loses News"          "python3 -c \"import json;p='build.json';d=json.load(open(p));d['ui']['bottom_nav']=['notebook','si'];json.dump(d,open(p,'w'))\"" "B1 the bottom nav must be"
mutant "persona page not in data"      "sed -i 's/\"id\": \"cto\"/\"id\": \"cto2\"/' app/src/main/assets/si/personas.json"  "B1 the SI pages are not exactly"
mutant "news topic not in ui"          "sed -i 's/\"id\": \"arts\"/\"id\": \"arts2\"/' app/src/main/assets/si/news.json"  "B1 the News pages are not exactly"
mutant "news address not baked"        "sed -i 's/NEWS_MESH_URL/NOPE/g' $SCRIPT"                               "B6 the app's build script does not bake NEWS_MESH_URL"
mutant "stale topics.json read creeps in" "echo \"def x = file('../../ac_cloud-news/data/topics.json')\" >> $SCRIPT"  "B6 the app's build script reads topics.json"
mutant "refusal line dropped"          "sed -i 's/exactly: {{refusal}}/exactly: nothing/' app/src/main/assets/si/notebook.json"  "B6 the notebook declares no refusal"
mutant "article spelled in Kotlin"     "echo 'val x = Article(\"https://a\", \"t\", \"\", \"\", \"d\")' >> $KT/SiNews.kt"  "B6 SiNews.kt spells a news article"
mutant "token read creeps in"          "echo 'val x = revealAiKey' >> $KT/SiAi.kt"                             "B4 SiAi.kt matches revealAiKey"
mutant "gateway not baked"             "sed -i 's/AI_GATEWAY_URL/NOPE/g' $SCRIPT"                              "B4 the app's build script does not bake"
mutant "account not visible"           "sed -i 's/com.diegonmarcos.cloudaccount/x.y/' app/src/main/AndroidManifest.xml" "B5 the manifest cannot see Cloud Account"

[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
