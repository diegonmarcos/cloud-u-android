#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ test-tools-app — Cloud Tools Small LLMs, statically              ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The JVM suite (core/ ToolsCoreTest, run by `gradle test`) proves the logic against recorded
# OpenRouter answers. This proves what a JVM test cannot see, from the sources alone:
#   A1  every icon build.json names (ui.sections, tools.tasks) is a branch of ui/IconCatalog.kt
#   A2  the token is stored ONLY through EncryptedSharedPreferences under a Keystore AES-256-GCM
#       master key, in the store fleet-config.json declares as `encrypted` / `secret`
#   A3  the token is never logged: no android.util.Log, println or printStackTrace anywhere in the
#       app or core sources, and the vault's toString is its mask
#   A4  decisions-use guard compliance: nothing here calls the fleet Account's key reveal
#       (revealAiKey) or the engine's DecisionsLink; the Account is asked only aiRoutingSnapshot
#   A5  the JVM suite uses recorded fixtures, never the network: every fixture it names exists,
#       and no test source opens a URL of its own
#   A6  no key-shaped literal in the module (the leak scan's sk-or- shape)
#   A7  every section id a Kotlin source names is one build.json::ui.sections declares
#   A8  the model data (assets/tools-models.json): one file, both tiers plus retrieval, every field typed, macro in the
#       declared set, chat=false exactly for retrieval, the ~20% overhead stated, every role/macro/app icon in the shared
#       catalog, every app step names declared macros and models; Models declares Small, Medium and Retrieval pages
#       and locations (device, ollama) and the 4/8/16-bit tags are declared
#   A9  the shell draws the shared bars (first row with Profile -> Cloud Account, second row, bottom nav, theme
#       background), the bottom nav is Apps, Models, AI, Store, Costs, the AI chat is libs:ai's over the gateway
#       with its picker fed ONLY by the chat rows of the data file, the four views and the apps page exist, and chat
#       calls are recorded into the Costs ledger
# then plants mutations in a scratch copy and requires RED for each.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {   # check <module dir> -> prints FAIL lines, exit 1 on any
python3 - "$1" "$ROOT" <<'PY'
import json, os, re, sys
app, root = sys.argv[1], sys.argv[2]
bad = []
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def code(p):
    t = re.sub(r"/\*.*?\*/", "", read(p), flags=re.S)
    return "\n".join(re.sub(r"(?<![:\"])//.*$", "", l) for l in t.splitlines())
def sources(*dirs):
    for d in dirs:
        for dp, dn, fn in os.walk(os.path.join(app, d)):
            dn[:] = [x for x in dn if x != "build"]
            for f in fn:
                if f.endswith((".kt", ".java")): yield os.path.join(dp, f)
bj = json.loads(read(os.path.join(app, "build.json")))
kt = os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudtoolssmallllms")
main = list(sources("app/src/main", "core/src/main"))

# A1
cat = code(os.path.join(kt, "ui/IconCatalog.kt"))
branches = set(re.findall(r'"([a-z_]+)"\s*->', cat))
for i in sorted({t["icon"] for t in bj["tools"]["tasks"]} - branches): bad.append("A1 icon %r is not a branch of IconCatalog: it would draw the fallback" % i)
uiicons = read(os.path.join(root, "ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt"))
def navicons(sections):
    for s in sections:
        yield s["icon"]
        for i in navicons(s["pages"]): yield i
for i in sorted(set(navicons(bj["ui"]["sections"]))):
    if '"%s"' % i not in uiicons: bad.append("A1 nav icon %r is not in libs:ui-icons" % i)

# A2
sp = code(os.path.join(kt, "data/SecretPrefs.kt"))
# #1106 SecretPrefs opens the store through libs:crash SafeEncryptedPrefs, which holds the encrypted-prefs schemes.
safe = code(os.path.join(root, "ab_cloud-libs-shared/libs/crash/src/main/java/com/diegonmarcos/superapp/crash/SafeEncryptedPrefs.kt"))
if "SafeEncryptedPrefs.prefs(" not in sp: bad.append("A2 SecretPrefs does not use SafeEncryptedPrefs.prefs(")
for need in ("EncryptedSharedPreferences.create(", "MasterKey.KeyScheme.AES256_GCM", "PrefValueEncryptionScheme.AES256_GCM"):
    if need not in safe: bad.append("A2 SafeEncryptedPrefs does not use %s" % need)
store = re.search(r'PREFS\s*=\s*"([^"]+)"', sp)
fc = json.loads(read(os.path.join(root, "ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json")))
decl = fc["stores"].get(store.group(1)) if store else None
if not decl or decl.get("kind") != "encrypted" or decl.get("class") != "secret":
    bad.append("A2 the token store %r is not declared encrypted/secret in fleet-config.json" % (store.group(1) if store else None))
for f in main:
    c = code(f)
    if "getSharedPreferences(" in c and "openrouter_token" in c:
        bad.append("A2 %s writes the token to plain SharedPreferences" % os.path.relpath(f, app))
    if "SecretPrefs(" in c and not f.endswith(("SecretPrefs.kt", "ToolsModel.kt")):
        bad.append("A2 %s holds the secret store; only the model's vault may" % os.path.relpath(f, app))

# A3
for f in main:
    c = code(f)
    for w in ("android.util.Log", "Log.d(", "Log.i(", "Log.w(", "Log.e(", "Log.v(", "println(", "printStackTrace("):
        if w in c: bad.append("A3 %s calls %s: this app logs nothing" % (os.path.relpath(f, app), w))
vault = code(os.path.join(app, "core/src/main/kotlin/com/diegonmarcos/cloudtoolssmallllms/core/TokenVault.kt"))
if not re.search(r'override fun toString\(\): String = "TokenVault\(\$\{masked\(\)\}\)"', vault):
    bad.append("A3 TokenVault.toString is not its mask")

# A4
for f in main:
    c = code(f)
    if "revealAiKey(" in c: bad.append("A4 %s reads the fleet Account's key (decisions-use D5)" % os.path.relpath(f, app))
    if "DecisionsLink" in c: bad.append("A4 %s calls the decisions engine; this app pays with its own token" % os.path.relpath(f, app))
acc = code(os.path.join(kt, "data/AccountStatus.kt"))
if "aiRoutingSnapshot()" not in acc: bad.append("A4 AccountStatus no longer asks the key-free aiRoutingSnapshot")

# A5
tests = list(sources("core/src/test"))
fx = os.path.join(app, "core/src/test/resources/fixtures")
for f in tests:
    t = read(f)
    for name in re.findall(r'fixture\("([^"]+)"\)', t):
        if not os.path.isfile(os.path.join(fx, name)): bad.append("A5 fixture %s is missing" % name)
    if re.search(r"UrlHttp|openConnection|HttpURLConnection|java\.net\.URL\(", t):
        bad.append("A5 %s reaches the network" % os.path.relpath(f, app))
if not tests: bad.append("A5 no JVM test sources")

# A6
key = re.compile(r"sk-or-[A-Za-z0-9-]{8,}")
for dp, dn, fn in os.walk(app):
    dn[:] = [x for x in dn if x not in ("build", ".gradle")]
    for f in fn:
        p = os.path.join(dp, f)
        try: t = read(p)
        except (UnicodeDecodeError, OSError): continue
        if key.search(t): bad.append("A6 %s holds a key-shaped literal" % os.path.relpath(p, app))

# A7
sections = {s["id"] for s in bj["ui"]["sections"]}
for f in sources("app/src/main"):
    for lit in re.findall(r'section\s*=\s*"([a-z_-]+)"', code(f)) + re.findall(r'^\s*"([a-z_-]+)"\s*->\s*\w+Page\(', code(f), re.M):
        if lit not in sections: bad.append("A7 %s names section %r, which build.json does not declare" % (os.path.relpath(f, app), lit))

# A8
md = json.loads(read(os.path.join(app, "app/src/main/assets/tools-models.json")))
macros = [m["id"] for m in md["macros"]]
if macros != ["retrieval", "gatekeeping", "routing", "understanding", "acting", "thinking", "producing", "seeing", "judging"]: bad.append("A8 macros are %r" % macros)
for m in md["macros"]:
    if '"%s"' % m["icon"] not in uiicons: bad.append("A8 macro %r icon %r is not in libs:ui-icons" % (m["id"], m["icon"]))
fields = {"role": str, "model": str, "params": str, "disk_gb_fp16": float, "ram_gb_fp16": float, "ram_gb_q8": float, "ram_gb_q4": float, "tier": str, "macro": str, "chat": bool}
for r in md["models"]:
    for k, t in fields.items():
        if k not in r or not isinstance(r[k], (t, int) if t is float else t): bad.append("A8 model %r lacks %s of type %s" % (r.get("model"), k, t.__name__))
    if r.get("tier") not in ("small", "medium"): bad.append("A8 model %r has tier %r" % (r.get("model"), r.get("tier")))
    if r.get("role") not in md["roles"]: bad.append("A8 role %r has no icon and purpose" % r.get("role"))
    if r.get("macro") not in macros: bad.append("A8 model %r has macro %r" % (r.get("model"), r.get("macro")))
    if r.get("chat") != (r.get("macro") != "retrieval"): bad.append("A8 model %r: chat must be false exactly for retrieval models" % r.get("model"))
    if r.get("macro") == "retrieval" and not r.get("task"): bad.append("A8 retrieval model %r has no task" % r.get("model"))
for t in ("small", "medium"):
    if not [r for r in md["models"] if r["tier"] == t and r["macro"] != "retrieval"]: bad.append("A8 tier %s has no models" % t)
    if not [r for r in md["models"] if r["tier"] == t and r["macro"] == "retrieval"]: bad.append("A8 tier %s has no retrieval models" % t)
    if t not in md["quant_guidance"]: bad.append("A8 no quantization guidance for %s" % t)
if len({r["model"] for r in md["models"]}) != len(md["models"]): bad.append("A8 duplicate model names")
if md.get("overhead_pct") != 20 or "20%" not in md.get("overhead_note", ""): bad.append("A8 the 20% overhead assumption is not stated in the data")
for n, ro in md["roles"].items():
    if '"%s"' % ro["icon"] not in uiicons: bad.append("A8 role %r icon %r is not in libs:ui-icons" % (n, ro["icon"]))
names = {r["model"] for r in md["models"]}
cats = [c["id"] for c in md["app_categories"]]
if cats != ["search", "documents", "communication", "agents", "reasoning", "safety", "personal"]: bad.append("A8 app categories are %r" % cats)
for c in md["app_categories"]:
    if '"%s"' % c["icon"] not in uiicons: bad.append("A8 category %r icon %r is not in libs:ui-icons" % (c["id"], c["icon"]))
for a in md["apps"]:
    if a.get("category") not in cats: bad.append("A8 app %r has category %r" % (a["id"], a.get("category")))
if [cats.index(a["category"]) for a in md["apps"] if a.get("category") in cats] != sorted(cats.index(a["category"]) for a in md["apps"] if a.get("category") in cats): bad.append("A8 apps are not ordered by category")
if len(md["apps"]) < 25: bad.append("A8 only %d apps declared (25 expected)" % len(md["apps"]))
for a in md["apps"]:
    if '"%s"' % a["icon"] not in uiicons: bad.append("A8 app %r icon %r is not in libs:ui-icons" % (a["id"], a["icon"]))
    if not a["pipeline"]: bad.append("A8 app %r has no pipeline" % a["id"])
    for st in a["pipeline"]:
        if st["macro"] not in macros: bad.append("A8 app %r step macro %r" % (a["id"], st["macro"]))
        if not st["models"] and not st.get("note"): bad.append("A8 app %r has a step with neither models nor a note" % a["id"])
        for m in st["models"]:
            if m not in names: bad.append("A8 app %r names unknown model %r" % (a["id"], m))
if sorted(l["kind"] for l in md["locations"]) != ["device", "ollama"]: bad.append("A8 locations are not one device and one ollama runtime")
if sorted(md["quant_tags"]) != ["16", "4", "8"]: bad.append("A8 quant_tags do not cover 4, 8 and 16 bit")
for a in md["apps"]:
    for st in a["pipeline"]:
        if st.get("backend", "local") not in ("local", "openrouter"): bad.append("A8 app %r step backend %r" % (a["id"], st.get("backend")))
secs = {s["id"]: s for s in bj["ui"]["sections"]}
if [p["id"] for p in secs["apps"]["pages"]] != ["apps_grid", "apps_list"]: bad.append("A8 Apps pages are not grid, list")
if [p["id"] for p in secs["store"]["pages"]] != ["installed", "loaded", "all"]: bad.append("A8 Store pages are not installed, loaded, all")
if [p["id"] for p in secs["models"]["pages"]] != ["small", "medium", "retrieval"]: bad.append("A8 Models pages are not small, medium, retrieval")
decl = code(os.path.join(kt, "Decl.kt"))
named = set(re.findall(r'SECTION_\w+\s*=\s*"([^"]+)"', decl)); namedp = set(re.findall(r'PAGE_\w+\s*=\s*"([^"]+)"', decl))
declp = {p["id"] for s in bj["ui"]["sections"] for p in s["pages"]}
for x in sorted(named ^ set(secs)): bad.append("A8 Decl.kt and build.json::ui disagree on section %r" % x)
for x in sorted(namedp ^ declp): bad.append("A8 Decl.kt and build.json::ui disagree on page %r" % x)

# A9
rt = code(os.path.join(kt, "data/ModelRuntime.kt"))
iface = re.search(r"interface ModelRuntime \{.*?\n\}", rt, re.S)
for need in ("fun state(): RuntimeState", "fun install(", "fun uninstall(", "fun load(", "fun unload("):
    if not iface or need not in iface.group(0): bad.append("A9 the engine contract (interface ModelRuntime) lacks %s" % need)
for need in ("class OllamaRuntime(", "class PhoneRuntime("):
    if need not in rt: bad.append("A9 ModelRuntime.kt lacks %s" % need)
store = code(os.path.join(kt, "ui/StorePage.kt"))
for need in ("it.install(", "it.uninstall(", "it.load(", "it.unload(", "store_fits", "store_not_installed", "store_loaded", "store_installed", "listOf(4, 8, 16)"):
    if need not in store: bad.append("A9 StorePage lacks %s" % need)
if re.search(r"getSharedPreferences|mutableStateOf\(\s*listOf", store): bad.append("A9 StorePage keeps model state of its own: it must come from the runtimes")
if bj["ui"]["bottom_nav"] != ["apps", "models", "ai", "store", "costs"]: bad.append("A9 bottom_nav is %r" % bj["ui"]["bottom_nav"])
shell = code(os.path.join(kt, "ui/ToolsShell.kt"))
for need in ("NavBarTopFirstRowScaffold(", "NavBarTopSecondRow(", "NavBarBottomHost(", "themeBackground(", "rememberAccountLauncher()", "titleHierarchy(", "AiChatPage(", "AppsPage(", "ModelsPage("):
    if need not in shell: bad.append("A9 ToolsShell does not call %s" % need)
apg = code(os.path.join(kt, "ui/AppsPage.kt"))
for need in ("appRamQ4(", "appDiskFp16(", "appModels(", "phoneCapable(", "AppsList(", "AppsGrid(", "app_totals", "app_phone_icon", "appCategories", "s.escalation", "s.alternatives"):
    if need not in apg: bad.append("A9 AppsPage lacks %s (list view, computed totals, badges)" % need)
if len(re.findall(r"app_totals, gb\(data\.appRamQ4\(a\)\), gb\(data\.appDiskFp16\(a\)\)", apg)) < 2: bad.append("A9 the list and the detail do not both show the computed totals")
pages = code(os.path.join(kt, "ui/ModelsPages.kt"))
for v in ("TABLE", "GRID", "DETAIL", "MACRO"):
    if not re.search(r"ModelsView\.%s\s*->\s*Models%s\(" % (v, v.title()), pages): bad.append("A9 the %s view is not drawn" % v)
if "AiChatPage(" not in shell or re.search(r"\b(BasicTextField|OutlinedTextField)\b", shell): bad.append("A9 the AI page is not libs:ai's alone")
script = code(os.path.join(app, "app/build." + "gradle"))
for lib in ("libs:nav-bar-top-first-row", "libs:nav-bar-top-second-row", "libs:nav-bar-bottom", "libs:theme-background", "libs:ui-icons", "libs:ai"):
    if lib not in bj["modules"]: bad.append("A9 build.json modules lack %s" % lib)
    if "project(':%s')" % lib not in script: bad.append("A9 the app's build script does not link %s" % lib)
if "AI_GATEWAY_URL" not in script or "ai-gateway.gradle" not in script: bad.append("A9 the gateway address is not baked")
tm = code(os.path.join(kt, "ui/ToolsModel.kt"))
if "AiGatewayBackend(" not in tm or "BuildConfig.AI_GATEWAY_URL" not in tm: bad.append("A9 the chat is not routed through the fleet gateway")
if "modelSource = { pickerModels() }" not in tm or "fun pickerRows(): List<ModelRow> = models.chatRows().filter" not in tm: bad.append("A9 the chat picker is not fed by the data file's chat rows only")
if "row?.wireId ?: req.model" not in tm: bad.append("A9 the gateway is not sent the data's openrouter_id")
for need in ("fun chooseBackend(", "else it.openrouterId != null }", "it.model in loadedNames()", "recordLocal(LocalCall(", "LocalChatBackend("):
    if need not in tm: bad.append("A9 ToolsModel lacks %s" % need)
if "BACKEND_LOCAL" not in shell or "BACKEND_OPENROUTER" not in shell or "header = {" not in shell: bad.append("A9 the AI page has no OpenRouter | Local toggle in its header")
if "localCalls" not in code(os.path.join(kt, "ui/CostsPage.kt")): bad.append("A9 Costs does not show local usage")
if "charge(row.model" not in tm or "stores.saveLedger(l)" not in tm: bad.append("A9 chat calls are not recorded into the Costs ledger")

for b in bad: print("  FAIL: " + b)
sys.exit(1 if bad else 0)
PY
}

PASS=0; FAILS=0
echo "[ac_cloud-tools-small-llms] static checks"
if check "$APP"; then PASS=$((PASS + 1)); echo "  PASS: A1-A7 hold on the tree"; else FAILS=$((FAILS + 1)); fi

# Mutations: each must turn the check RED, or the check cannot fail.
S="$(mktemp -d)"; trap 'rm -rf "$S"' EXIT
mutate() {   # mutate <name> <python statement on M (scratch module dir)>
  rm -rf "$S/m"; mkdir -p "$S/m"
  (cd "$APP" && tar --exclude=build --exclude=.gradle -cf - build.json app core) | (cd "$S/m" && tar -xf -)
  python3 - "$S/m" <<PY
import re, sys
M = sys.argv[1]
K = M + "/app/src/main/java/com/diegonmarcos/cloudtoolssmallllms"
def sub(p, a, b):
    s = open(p).read(); assert a in s, (p, a); open(p, "w").write(s.replace(a, b, 1))
$2
PY
  if check "$S/m" >/dev/null 2>&1; then FAILS=$((FAILS + 1)); echo "  FAIL: mutation '$1' stayed green"; else PASS=$((PASS + 1)); echo "  PASS: mutation '$1' is caught"; fi
}
mutate "unknown task icon" 'sub(M + "/build.json", "\"icon\": \"translate\"", "\"icon\": \"tranzlate\"")'
mutate "plain prefs token" 'sub(K + "/data/SecretPrefs.kt", "SafeEncryptedPrefs.prefs(", "PlainPrefs.create(")'
mutate "a log line" 'sub(K + "/data/Catalogue.kt", "    companion object {", "    fun dbg(t: String) = android.util.Log.d(\"tools\", t)\n\n    companion object {")'
mutate "Account key read" 'sub(K + "/data/AccountStatus.kt", "client.aiRoutingSnapshot() }", "client.revealAiKey(PROVIDER).text }")'
mutate "vault prints the token" 'D = chr(36); sub(M + "/core/src/main/kotlin/com/diegonmarcos/cloudtoolssmallllms/core/TokenVault.kt", "TokenVault(" + D + "{masked()})", "TokenVault(" + D + "{token()})")'
mutate "test goes online" 'sub(M + "/core/src/test/kotlin/com/diegonmarcos/cloudtoolssmallllms/core/ToolsCoreTest.kt", "import java.io.IOException", "import java.io.IOException\nimport com.diegonmarcos.superapp.decisions.UrlHttp")'
mutate "missing fixture" 'sub(M + "/core/src/test/kotlin/com/diegonmarcos/cloudtoolssmallllms/core/ToolsCoreTest.kt", "fixture(\"jev-route.json\")", "fixture(\"jev-gone.json\")")'
mutate "undeclared section" 'sub(K + "/ui/ToolsShell.kt", "Decl.SECTION_STORE -> StorePage(model, pageId ?: Decl.PAGE_INSTALLED)", "\"storez\" -> StorePage(model, \"\")")'
mutate "section dropped from Decl" 'sub(K + "/Decl.kt", "    const val SECTION_STORE = \"store\"\n", "")'
mutate "model data loses its guidance" 'sub(M + "/app/src/main/assets/tools-models.json", "\"medium\": \"Q4 is", "\"mediumz\": \"Q4 is")'
mutate "overhead note dropped" 'sub(M + "/app/src/main/assets/tools-models.json", "\"overhead_pct\": 20", "\"overhead_pct\": 0")'
mutate "retrieval model becomes a chat model" 'sub(M + "/app/src/main/assets/tools-models.json", "\"chat\": false", "\"chat\": true")'
mutate "app names an unknown model" 'sub(M + "/app/src/main/assets/tools-models.json", "\"bge-m3\"\n", "\"bge-m4\"\n")'
mutate "shell loses the second row" 'sub(K + "/ui/ToolsShell.kt", "NavBarTopSecondRow(", "Foo(")'
mutate "apps lose the computed totals" 'sub(K + "/ui/AppsPage.kt", "app_totals, gb(data.appRamQ4(a)), gb(data.appDiskFp16(a))", "app_totals, \"0\", \"0\"")'
mutate "app loses its category" 'sub(M + "/app/src/main/assets/tools-models.json", "\"category\": \"search\"", "\"category\": \"nowhere\"")'
mutate "apps list page dropped" 'sub(M + "/build.json", "\"id\": \"apps_list\"", "\"id\": \"apps_listz\"")'
mutate "shell loses the apps page" 'sub(K + "/ui/ToolsShell.kt", "AppsPage(model, pageId ?: Decl.PAGE_APPS_GRID)", "Unit")'
mutate "macro view undrawn" 'sub(K + "/ui/ModelsPages.kt", "ModelsView.MACRO -> ModelsMacro", "ModelsView.MACRO -> ModelsGrid")'
mutate "chat picker leaves the chat rows" 'sub(K + "/ui/ToolsModel.kt", "models.chatRows().filter { if (backend", "models.rows.filter { if (backend")'
mutate "openrouter id not sent" 'sub(K + "/ui/ToolsModel.kt", "row?.wireId ?: req.model", "req.model")'
mutate "chat calls not recorded" 'sub(K + "/ui/ToolsModel.kt", "stores.saveLedger(l)", "")'
mutate "openrouter picker not filtered by id" 'sub(K + "/ui/ToolsModel.kt", "it.openrouterId != null }", "true }")'
mutate "local picker not filtered by loaded" 'sub(K + "/ui/ToolsModel.kt", "it.model in loadedNames()", "true")'
mutate "local usage not recorded" 'sub(K + "/ui/ToolsModel.kt", "recordLocal(LocalCall", "Unit; (LocalCall")'
mutate "engine loses unload" 'sub(K + "/data/ModelRuntime.kt", "    fun unload(loaded: LoadedModel): String?\n", "")'
mutate "store keeps its own state" 'sub(K + "/ui/StorePage.kt", "val data = model.models", "val data = model.models; val own = context.getSharedPreferences(\"x\", 0)")'
mutate "store pages reordered" 'sub(M + "/build.json", "\"id\": \"installed\"", "\"id\": \"installedz\"")'
mutate "bottom nav reordered" 'sub(M + "/build.json", "\"apps\",\n      \"models\",\n      \"ai\"", "\"models\",\n      \"apps\",\n      \"ai\"")'

echo "-- test-tools-app: $PASS passed, $FAILS failed"
[ "$FAILS" -eq 0 ]
