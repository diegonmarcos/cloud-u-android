#!/usr/bin/env bash
# Tester (#1144, #1150): Cloud Me's seven-section bar, the Samsung Health page tabs, the fleet top bar (hamburger,
# AI, Profile, Configs), the Achieves text, the tap-only side menu (no edge-swipe), the Account launch and the removed
# Wallet link, plus the LLC / Buro / Duties page shapes.
# Structure and data only; the RENDERED strip, bar and top bar are measured by app/src/test/.../MeTabsTest.
# Every check is proven red by its own mutation of a copy of the tree (the tail of this file).
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

# check_tree <app dir> <libs dir> : prints one "ID<TAB>problem" line per failure, nothing when green.
check_tree() {
python3 - "$1" "$2" <<'PY'
import json, os, re, sys

app, libs = sys.argv[1], sys.argv[2]
bj = json.load(open(os.path.join(app, "build.json")))
ui = bj["ui"]
secs = {s["id"]: s for s in ui["sections"]}
MAIN = os.path.join(app, "app/src/main")
out = []
def bad(i, m): out.append(f"{i}\t{m}")

def code(path):
    t = open(path, encoding="utf-8").read()
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    return re.sub(r"(?m)^\s*//.*$", "", t)

def kt(root):
    for r, _, fs in os.walk(root):
        for f in fs:
            if f.endswith(".kt"):
                yield os.path.join(r, f)

def pages(ps):
    for p in ps or []:
        yield p
        yield from pages(p.get("pages"))

def strings(o):
    if isinstance(o, str): yield o
    elif isinstance(o, dict):
        for v in o.values(): yield from strings(v)
    elif isinstance(o, list):
        for v in o: yield from strings(v)

# ---- N1 nav order
want_ids = ["health", "study", "achieves", "me", "duties", "buro", "llc"]
want_labels = ["Health", "Study", "Achieves", "Profile", "Duties", "Buro", "LLC"]
if ui.get("bottom_nav") != want_ids:
    bad("nav-order", f"ui.bottom_nav is {ui.get('bottom_nav')}, wanted {want_ids}")
if [secs[i]["label"] for i in ui.get("bottom_nav", []) if i in secs] != want_labels:
    bad("nav-order", f"the bar labels are not {want_labels}")

# ---- N2 tab style: icon-only, the selected one also shows its name
# #1154 the strip is libs:ui-nav-bars's (the owner moved the second row out of libs:bottomnav)
strip = os.path.join(libs, "ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/navbartop2/NavBarTopSecondRow.kt")
tabs = strip
if not os.path.exists(strip):
    bad("tab-style", "libs:ui-nav-bars has no NavBarTopSecondRow.kt")
else:
    t = code(strip)
    if "SecondRowTags.icon(" not in t:
        bad("tab-style", "the icon pill does not draw an icon for every tab")
    # #1159 (owner, supersedes #1144's selected-only name): at rest EVERY tab shows its name, scrolled down (compact) only the selected one.
    if "named = !compact || page.id == selectedId" not in t:
        bad("tab-style", "the tab name is not gated by `named = !compact || page.id == selectedId` - every tab shows it at rest, only the selected one when compact")
    if not re.search(r"AnimatedVisibility\(\s*named", t):
        bad("tab-style", "the tab name is not drawn inside AnimatedVisibility(named)")
    if len(re.findall(r"page\.label", t)) != len(re.findall(r"AnimatedVisibility\(\s*named,[^{]*\{\s*Text\(\s*page\.label", t)) + len(re.findall(r"contentDescription = page\.label", t)):
        bad("tab-style", "a tab draws its name outside the `named` gate")
if "iconFor" not in code(tabs):
    bad("tab-style", "the second row does not take iconFor (icon-only pills)")
sec = code(os.path.join(MAIN, "java/com/diegonmarcos/cloudme/SectionScreen.kt"))
i = sec.find("NavBarTopSecondRow(")
if i < 0 or not re.search(r"\biconFor\s*=", sec[i:i + 2500]):
    bad("tab-style", "SectionScreen draws its strips without iconFor (no icon-only pills)")
drawables = {n[:-4] for n in os.listdir(os.path.join(MAIN, "res/drawable")) if n.endswith(".xml")}
# A tab icon is an app drawable OR a name of the shared catalog (libs:ui-icons), which the second row draws when no drawable exists.
_cat = os.path.join(app, "../ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt")
if os.path.isfile(_cat):
    drawables |= set(re.findall(r'"([a-z0-9_]+)"\s+to\s', open(_cat, encoding="utf-8").read()))
for s in secs.values():
    if len(s.get("pages", [])) > 1 or any(len(p.get("pages", [])) > 1 for p in pages(s.get("pages"))):
        for p in pages(s["pages"]):
            if not p.get("icon") or p["icon"] not in drawables:
                bad("tab-style", f"tab {s['id']}/{p['id']} has no drawable icon ({p.get('icon')!r}); an icon-only tab would be blank")

# ---- N3 top bar: the fleet's AppTopBar, inline actions; ui.settings names the sections Profile and Configs open
st = ui.get("settings")
if st != ["me", "configs"] or [secs[i]["label"] for i in (st or []) if i in secs] != ["Profile", "Configs"]:
    bad("settings", f"ui.settings is {st}; wanted the sections Profile and Configs, in that order")
for p in kt(MAIN):
    if re.search(r"\bDropdownMenu\b|Icons\.Filled\.Settings|Icons\.[A-Za-z.]*\.Menu\b", code(p)):
        bad("settings", f"{os.path.relpath(p, app)} draws a Settings dropdown or its own hamburger")
bar_src = code(os.path.join(MAIN, "java/com/diegonmarcos/cloudme/MeTopBar.kt"))
if not re.search(r"inlineActions\s*=\s*true", bar_src) or not re.search(r"drawer\s*=\s*true", bar_src):
    bad("settings", "MeTopBar does not declare the hamburger and the inline AI / Profile / Configs icons")
if "NavBarTopFirstRowScaffold(" not in code(os.path.join(MAIN, "java/com/diegonmarcos/cloudme/MeShell.kt")):
    bad("settings", "MeShell does not draw the fleet first row (NavBarTopFirstRowScaffold)")
if "MeShell(" not in code(os.path.join(MAIN, "java/com/diegonmarcos/cloudme/MainActivity.kt")):
    bad("settings", "MainActivity does not draw MeShell")

# ---- N4 achieves: the owner's text, byte for byte
ach = {}
for pid in ("personal", "pursuits", "international"):
    f = os.path.join(app, f"data/ui/achieves/{pid}.json")
    ach[pid] = json.load(open(f, encoding="utf-8")) if os.path.exists(f) else []
    if not ach[pid]:
        bad("achieves", f"data/ui/achieves/{pid}.json is missing or empty")
if [p["id"] for p in secs.get("achieves", {}).get("pages", [])] != ["personal", "pursuits", "international", "trips", "experiences"] or \
   [p["label"] for p in secs.get("achieves", {}).get("pages", [])] != ["Personal", "Personal Pursuits", "International Experience", "Trips", "Experiences"]:
    bad("achieves", "Achieves pages are not Personal, Personal Pursuits, International Experience, Trips, Experiences")
EXPECT = {
 "personal": [
  "B1) Personal",
  "Modern Physics (Quantum and Particle), Global Macro, Mediterranean and Vegetarian Cooking, Backpacking, Jazzy Music.",
  "Hiking, Rock Climbing, Cycling, Skydiving and Bungee Jump.",
  "Tennis (5y), Handball (3y), Rugby (1y) and Waterpolo (1y).",
  "Aikido, Yoga and Meditation.",
  "Oceans Seven Endurance, pre-Tour de France cycling and Seven Summits hike.",
  "INTERESTS", "ENDURANCE", "COMPETING", "MINDFULNESS", "PURSUITS"],
 "pursuits": [
  "B2) Personal Pursuits", "@brief", "@projects",
  "Modern Physics (Quantum and Particle), Global Macro, Mediterranean and Vegetarian Cooking, Backpacking, Jazzy Music.",
  "It is not death that a man should fear, but he should fear never beginning to live.", "Epictetus",
  "🪂 High Adrenaline Sports", "🏂 Adrenaline Sports", "🏔️ Nature Sports and Activities", "🥋 Martial Arts", "⚽️ Collective Sports", "🏃 Individual Sports",
  "My Bucket 50 (things before 50)",
  "🌿 Personal Growth, 🌐 Travel & Adventure, 🌍 Health & Wellness, 💼 Career and Finance, ❤️ Relationship & Community, ✨ Creativity & Fun",
  "🌱 (By 30) Foundational goals, 🌳 (By 40) Mastery & long-term planning, 🍁 (By 50) Legacy goals",
  "🚵🏻‍♂️ Trail Moto | 🪂 Skydiving | 🧗🏻‍♂️ Rock Climbing | 🏂🏻 Snowboarding",
  "🥾 Hiking | 🏄🏻‍♂️ Wakeboard | 🛹 Skating",
  "🏕️ Camping | 🏄🏻‍♂️ Surfing | ⚓ Scuba Diving | ⛵ Sailing",
  "🥋 Aikido",
  "🤾🏻‍♂️ Handball | 🏐 Volleyball | 🏉 Rugby | 🤽🏻‍♂️ Waterpolo",
  "⚽ Futebol",
  "🎾 Tennis | 🏊🏻‍♂️ Swimming | 🚲 Cycling | 🏃🏻‍♂️ Running | 🚴🏻‍♂️🏊🏻‍♂️ Biathlon",
  "⛳ Golf | ⛸️ Ice Lake Skying"],
 "international": [
  "B3) International Experience (Dec 2020 – Present)", "@brief", "@projects",
  "For 5 years, travelled to 180+ cities (110+ NM regions) over 45+ countries across 5 continents; lived in 5 European cities, born and raised in São Paulo.",
  "A ship in harbor is safer, but that is not what ships are built for.", "John A. Shedd",
  "Portuguese, English and Spanish", "Gallego", "Deutsche", "Catalán, French and Italian.",
  "Born and raised in São Paulo, the 5th biggest city in the world",
  "On long stays lived (studying and working remotely) in 5 European cities: Berlin and Barcelona; London, Valencia and Paris",
  "Also took long stays in: Rio de Janeiro, Casablanca and Chiang Mai",
  "On short stays traveled over 180+ cities, 110+ NomadMania Regions, 45+ countries across 5 continents",
  "Visited: World Heritage Sites (60+), New Seven Wonder+Pyramids (3/8) and The Seven Natural WW (1/7)",
  "Places {175 Cities (108 NM) | 43 Countries}",
  "EU", "102 (70)", "⚙️ Germanic", "🪆 Slavic", "🏛️ Romanic-Greek", "🏔️ Non-Euro", "NORTH/SOUTH AMERICA", "51 (22)",
  "🗽 Anglo-Franco", "🪭 Spanish-Aztec/Inca", "🎉 Romance-African", "OCEANIA", "🦘 Anglo-Aborigenal", "🪶 Anglo-Maori", "🗿 Poly-Micronesia",
  "ASIA", "13 (9)", "🕯️ Abrahamic", "🧘🏻‍♂️ Hindu;Buddhist;Tao/Conf", "AFRICA", "9 (4)", "🫖 North-Maghreb", "🥁 Francosphere", "💎 Lusosphere", "🪨 Anglosphere"],
}
for pid, want in EXPECT.items():
    have = set(strings(ach[pid]))
    for w in want:
        if w not in have:
            bad("achieves", f"{pid}: the owner's text is missing or altered: {w!r}")
tbl = [b for b in ach["international"] if isinstance(b, dict) and b.get("kind") == "table"]
if not tbl or (tbl[0].get("link") or {}).get("url") != "https://nomadmania.com/profile/73889":
    bad("achieves", "the NomadMania region table has no link to https://nomadmania.com/profile/73889")
elif len(tbl[0].get("rows", [])) != 21:
    bad("achieves", f"the NomadMania table has {len(tbl[0].get('rows', []))} rows, the owner's text has 21")
stack = code(os.path.join(MAIN, "java/com/diegonmarcos/cloudme/StackScreen.kt"))
kinds = set(re.findall(r'^\s*"([a-z_]+)"(?:\s*,\s*"[a-z_]+")*\s*->', stack, flags=re.M))
for pid, blocks in ach.items():
    for b in blocks:
        if isinstance(b, dict) and b.get("kind") not in kinds:
            bad("achieves", f"{pid}: block kind {b.get('kind')!r} has no renderer in StackScreen")

# ---- N5 the edge-swipe drawer is gone for good
FORBID = r"ModalNavigationDrawer|DismissibleNavigationDrawer|PermanentNavigationDrawer|rememberDrawerState|DrawerValue|ModalDrawerSheet|NavigationDrawerItem|detectHorizontalDragGestures|detectDragGestures|\bswipeable\b|\banchoredDraggable\b|\bdraggable\b|GestureDetector|OnTouchListener|setOnTouchListener|DrawerLayout|ic_hamburger|gesturesEnabled"
for p in kt(MAIN):
    m = re.search(FORBID, code(p))
    if m:
        bad("no-drawer", f"{os.path.relpath(p, app)} carries `{m.group(0)}`: a swipe-to-open drawer handler")
if re.search(r"fun\s+drawer\s*\(", code(os.path.join(MAIN, "java/com/diegonmarcos/cloudme/Sections.kt"))):
    bad("no-drawer", "Sections still declares a drawer()")
html = open(os.path.join(app, "data/web/mysocials/linkedin.html"), encoding="utf-8").read()
m = re.search(r"touchstart|touchmove|touchend|swipe|drawer|hamburger", html, flags=re.I)
if m:
    bad("no-drawer", f"the Profile page (linkedin.html) carries `{m.group(0)}`: a touch or drawer handler")
for r, _, fs in os.walk(os.path.join(MAIN, "res")):
    for f in fs:
        if "drawer" in f.lower() or "hamburger" in f.lower():
            bad("no-drawer", f"resource {f} is a drawer leftover")

# the lib's side menu opens by tap: no drag / swipe API in the top bar that draws it
lib_bar = os.path.join(libs, "bottomnav/src/main/kotlin/com/diegonmarcos/superapp/bottomnav/AppTopBar.kt")
lib_row = os.path.join(libs, "ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/navbartop1/NavBarTopFirstRow.kt")
for lib_bar in (lib_bar, lib_row):
  if os.path.exists(lib_bar):
    m = re.search(r"detectHorizontalDragGestures|detectDragGestures|\bswipeable\b|\banchoredDraggable\b|\bdraggable\b|GestureDetector|gesturesEnabled", code(lib_bar))
    if m:
        bad("no-drawer", f"the lib AppTopBar carries `{m.group(0)}`: the side menu must open by tap only")

# ---- N7a House is gone and Work is LLC; Tasks moved from Profile to Duties; Trips is under Achieves
if "home" in secs:
    bad("pages", "the Home section is back")
for gone in ("house", "work"):
    if gone in secs or gone in ui.get("bottom_nav", []):
        bad("pages", f"the retired {gone} section is declared again")
    if os.path.isdir(os.path.join(app, "data/ui", gone)):
        bad("pages", f"data/ui/{gone} is back")
if [p["id"] for p in secs.get("me", {}).get("pages", [])] != ["professional", "leafy", "nexus", "stark", "personal", "social", "health", "dd", "astro", "lnktree"]:
    bad("pages", "Profile pages are not Professional, Leaf.y, Nexus.z, Stark.x, Personal, Social, Health, DD, Astro, Lnktree")
_me = secs.get("me", {})
if _me.get("nav_mode") != "horizontal_two_rows" or {g["id"]: g["pages"] for g in _me.get("groups", [])} != {"prof": ["professional", "leafy", "nexus", "stark"], "pers": ["personal", "social", "health", "dd", "astro", "lnktree"]}:
    bad("pages", "Profile is not the two rows Prof / Pers")
if [p["id"] for p in _me.get("pages", []) if p.get("separator_before")] != ["leafy", "health", "lnktree"]:
    bad("pages", "Profile row separators are not before Leaf.y, Health, Lnktree")
for _pid, _u in (("leafy", "https://diegonmarcos.github.io/leafy/"), ("nexus", "https://diegonmarcos.github.io/nexus/"), ("stark", "https://diegonmarcos.github.io/stark/"), ("astro", "https://diegonmarcos.github.io/myAstro/"), ("lnktree", "https://linktree.diegonmarcos.com/")):
    _f = os.path.join(app, f"data/ui/me/{_pid}.json")
    _b = json.load(open(_f, encoding="utf-8")) if os.path.exists(_f) else []
    if len(_b) != 1 or _b[0].get("id") != "web" or _b[0].get("url") != _u:
        bad("pages", f"Profile > {_pid} is not the embedded page {_u}")
_ex = os.path.join(app, "data/ui/achieves/experiences.json")
_eb = json.load(open(_ex, encoding="utf-8")) if os.path.exists(_ex) else []
if len(_eb) != 1 or _eb[0].get("kind") != "ledger" or _eb[0].get("store") != "experiences_user" or [l["collection"] for l in _eb[0].get("ledgers", [])] != ["done", "todo"]:
    bad("pages", "Achieves > Experiences is not the ledger over experiences_user with done and todo")
_fc = json.load(open(os.path.join(libs, "fleetconfig-model/src/main/assets/fleet-config.json"), encoding="utf-8"))
_st = _fc.get("stores", {}).get("experiences_user")
if not _st or "ac_cloud-me" not in _st.get("used_by", []) or _st.get("class") != "config":
    bad("pages", "experiences_user is not a declared config store of ac_cloud-me (the backup would miss it)")
if [p["id"] for p in secs.get("duties", {}).get("pages", [])] != ["tasks"]:
    bad("pages", "Duties is not the single Tasks page")
if os.path.isdir(os.path.join(app, "data/ui/home")):
    bad("pages", "data/ui/home is back")
blob_all = "".join(open(os.path.join(r, f), encoding="utf-8").read() for r, _, fs in os.walk(os.path.join(app, "data/ui")) for f in fs if f.endswith(".json"))
for dead in ("page:home/", "page:house/", "page:work/", "page:me/tasks"):
    if dead in blob_all:
        bad("pages", f"a page still targets {dead}...")

# the many_rows contract of Buro and LLC: every flat page in exactly one group, groups have a label and an icon from libs:ui-icons
icons = open(os.path.join(libs, "ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt"), encoding="utf-8").read()
want_groups = {
    "buro": {"ids": ["ids", "dd", "wallet", "account"], "docs": ["assets", "services", "liabilities"], "reports": ["budgeting", "cashflow"], "calendar": ["calendar"]},
    "llc": {"income": ["employed", "side-hustles"], "manage": ["assets", "portfolio"], "calendar": ["calendar"]},
}
for sid, want in want_groups.items():
    sec = secs.get(sid, {})
    if sec.get("nav_mode") not in ("horizontal_two_rows", "many_rows"):
        bad("pages", f"{sid} is not on the horizontal_two_rows nav mode (alias many_rows)")
    got = {g["id"]: g["pages"] for g in sec.get("groups", [])}
    if got != want:
        bad("pages", f"{sid} groups are {got}, wanted {want}")
    flat = [p["id"] for p in sec.get("pages", [])]
    allg = [x for g in sec.get("groups", []) for x in g["pages"]]
    if sorted(flat) != sorted(allg):
        bad("pages", f"{sid}: every page must be in exactly one group")
    for g in sec.get("groups", []):
        if not g.get("label") or f'"{g.get("icon")}"' not in icons:
            bad("pages", f"{sid} group {g.get('id')} has no label or an icon that libs:ui-icons does not hold")
    for p in sec.get("pages", []):
        if f'"{p.get("icon")}"' not in icons:
            bad("pages", f"{sid} page {p['id']} icon {p.get('icon')} is not in libs:ui-icons")

# Buro and LLC user data: every ledger block names a declared store, typed fields, and only known report ids
known_reports = {"budget_vs_actual", "accounts_vs_cashflow", "portfolio", "income"}
stores = {"buro": "buro_user", "llc": "llc_user"}
fc = json.load(open(os.path.join(libs, "fleetconfig-model/src/main/assets/fleet-config.json"), encoding="utf-8"))
for sid, store in stores.items():
    st = fc.get("stores", {}).get(store)
    if not st or "ac_cloud-me" not in st.get("used_by", []) or st.get("class") != "config":
        bad("pages", f"{store} is not a declared config store of ac_cloud-me in fleet-config.json (the backup would miss it)")
    for pg in secs.get(sid, {}).get("pages", []):
        f = os.path.join(app, f"data/ui/{sid}/{pg['id']}.json")
        for blk in json.load(open(f, encoding="utf-8")) if os.path.exists(f) else []:
            if blk.get("kind") != "ledger":
                continue
            if blk.get("store") != store:
                bad("pages", f"{f} ledger names store {blk.get('store')}, wanted {store}")
            for r in blk.get("reports", []):
                if r not in known_reports:
                    bad("pages", f"{f} names an unknown report {r}")
            for led in blk.get("ledgers", []):
                for fld in led.get("fields", []):
                    if fld.get("type") not in ("text", "money", "date", "choice") or (fld["type"] == "choice" and not fld.get("options")):
                        bad("pages", f"{f} field {fld.get('id')} has a bad type or no options")
                if any(k in json.dumps(led) for k in ("sample", "mock", "demo")):
                    bad("pages", f"{f} carries sample/mock/demo text: ledgers are empty until typed into")

# ---- N6 Account: renamed from Vault and launching Cloud Account
buro = secs["buro"]
acct = [p for p in buro["pages"] if p["id"] == "account"]
if not acct or acct[0].get("label") != "Account":
    bad("account", "Buro has no page `account` labelled Account")
elif acct[0].get("action") != "extapp:cloud-account":
    bad("account", "Buro > Account is not a launch tab for extapp:cloud-account")
if any(p.get("label") == "Vault" or p["id"] == "vault" for p in pages(buro["pages"])):
    bad("account", "Buro still has a Vault tab")
ea = {a["id"]: a["package"] for a in ui.get("external_apps", [])}
if ea.get("cloud-account") != "com.diegonmarcos.cloudaccount":
    bad("account", "ui.external_apps does not map cloud-account to com.diegonmarcos.cloudaccount")
fleet = os.path.join(app, "../aa_cloud-superapp/data/constellation-fleet.json")
if os.path.exists(fleet):
    pk = {a["id"]: a["package"] for a in json.load(open(fleet))["apps"]}
    if pk.get("account") != ea.get("cloud-account"):
        bad("account", "the fleet manifest's account package differs from ui.external_apps")
mf = open(os.path.join(MAIN, "AndroidManifest.xml")).read()
if "com.diegonmarcos.cloudaccount" not in mf:
    bad("account", "AndroidManifest <queries> does not list com.diegonmarcos.cloudaccount (the launch would be invisible on API 30+)")
af = os.path.join(app, "data/ui/buro/account.json")
if not os.path.exists(af) or "extapp:cloud-account" not in open(af).read():
    bad("account", "data/ui/buro/account.json has no extapp:cloud-account tile")
if "page:buro/vault" in "\n".join(strings([json.load(open(os.path.join(r, f))) for r, _, fs in os.walk(os.path.join(app, "data/ui")) for f in fs if f.endswith(".json")])):
    bad("account", "a page still targets page:buro/vault")
sec_src = code(os.path.join(MAIN, "java/com/diegonmarcos/cloudme/SectionScreen.kt"))
if "tab.action" not in sec_src:
    bad("account", "SectionScreen never dispatches a launch tab's action")

# ---- N7 Wallet link removed
if "wallet" in ui.get("bottom_nav", []) or "wallet" in secs:
    bad("no-wallet", "the Wallet bar section is still declared")
if "cloud-wallet" in ea or "com.diegonmarcos.cloudwallet" in mf:
    bad("no-wallet", "Cloud Wallet is still a declared external app / queried package")
blob = json.dumps(ui["sections"]) + "\n".join(
    open(os.path.join(r, f), encoding="utf-8").read() for r, _, fs in os.walk(os.path.join(app, "data/ui")) for f in fs if f.endswith(".json"))
if "extapp:cloud-wallet" in blob:
    bad("no-wallet", "something still launches extapp:cloud-wallet")

# ---- N8 the new section pages
want_pages = {
    "health": ["Index", "Anthropometrics", "Circadian", "Biomarkers", "Timeline", "Objectives", "Matrix", "Culinary", "Absorption clash", "Thermal", "Hemodynamics", "Supermarket", "Dossier", "Workout plan", "Activities", "Body comp", "HR & HRV", "Sleep"],
    "study": ["CV", "Roadmap"],
    "llc": ["Employed", "Side hustles", "Assets", "Portfolio", "Calendar"],
    "buro": ["IDs", "Personal DD", "Wallet", "Account", "Assets", "Services", "Liabilities & Taxes", "Budgeting", "Accounts vs Cashflow", "Calendar"],
}
for sid, labels in want_pages.items():
    if [p["label"] for p in secs.get(sid, {}).get("pages", [])] != labels:
        bad("pages", f"{sid} pages are not {labels}")
for sid, pg in (("study", "cv"), ("study", "roadmap"), ("llc", "employed"), ("llc", "side-hustles"), ("duties", "tasks"), ("achieves", "trips")):
    f = os.path.join(app, f"data/ui/{sid}/{pg}.json")
    if not os.path.exists(f) or not json.load(open(f, encoding="utf-8")):
        bad("pages", f"data/ui/{sid}/{pg}.json is missing or empty")

for line in out: print(line)
PY
}

expect_green() { # name
  local r; r="$(check_tree "$APP" "$APP/../ab_cloud-libs-shared/libs")"
  if [ -z "$r" ]; then ok "$1"; else bad "$1"; echo "$r" | sed 's/^/        /'; fi
}
only() { # <check id>: nothing but that id is red in the real tree
  local r; r="$(check_tree "$APP" "$APP/../ab_cloud-libs-shared/libs" | cut -f1 | sort -u | grep -x "$1" || true)"
  [ -z "$r" ]
}
for t in "nav-order|the bar reads Health, Study, Achieves, Profile, Duties, Buro, LLC" \
         "tab-style|page tabs show every name at rest and only the selected name when scrolled down (#1159)" \
         "settings|the top bar is the fleet AppTopBar: hamburger, AI, Profile, Configs, no dropdown" \
         "achieves|Achieves renders the owner's text (B1, B2, B3, region table with its link)" \
         "no-drawer|no swipe-to-open drawer handler anywhere (shell, the lib top bar, Profile page, resources)" \
         "account|Buro > Account launches Cloud Account through extapp, Vault is gone" \
         "no-wallet|the Wallet link is gone" \
         "pages|Health, Study, LLC, Buro, Duties and Trips pages are declared with their data, House and Work are gone"; do
  id="${t%%|*}"; name="${t#*|}"
  if only "$id"; then ok "$name"; else bad "$name"; check_tree "$APP" "$APP/../ab_cloud-libs-shared/libs" | grep "^$id" | sed 's/^/        /'; fi
done

echo "== mutations: each check must go red on its own break =="
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mkdir -p "$W/base/ac_cloud-me" "$W/base/ab_cloud-libs-shared/libs/bottomnav/src"
for l in ui-nav-bars; do mkdir -p "$W/base/ab_cloud-libs-shared/libs/$l/src"; cp -r "$APP/../ab_cloud-libs-shared/libs/$l/src/main" "$W/base/ab_cloud-libs-shared/libs/$l/src/"; done
mkdir -p "$W/base/ab_cloud-libs-shared/libs/ui-icons/src" "$W/base/ab_cloud-libs-shared/libs/fleetconfig-model/src/main"
cp -r "$APP/../ab_cloud-libs-shared/libs/ui-icons/src/main" "$W/base/ab_cloud-libs-shared/libs/ui-icons/src/"
cp -r "$APP/../ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets" "$W/base/ab_cloud-libs-shared/libs/fleetconfig-model/src/main/"
cp -r "$APP/build.json" "$APP/app" "$APP/data" "$W/base/ac_cloud-me/" 2>/dev/null
rm -rf "$W/base/ac_cloud-me/app/build"
cp -r "$APP/../ab_cloud-libs-shared/libs/bottomnav/src/commonMain" "$APP/../ab_cloud-libs-shared/libs/bottomnav/src/main" "$W/base/ab_cloud-libs-shared/libs/bottomnav/src/"
mutant() { # id description sh-command-run-in-copy
  local id="$1" desc="$2" cmd="$3" d="$W/m"
  rm -rf "$d"; cp -r "$W/base" "$d"
  ( cd "$d/ac_cloud-me" && eval "$cmd" ) >/dev/null 2>&1
  if check_tree "$d/ac_cloud-me" "$d/ab_cloud-libs-shared/libs" | cut -f1 | grep -qx "$id"; then ok "red: $desc"; else bad "NOT red: $desc"; fi
}
BJ='python3 - "$@"'
mutant nav-order "bar order swapped" "python3 -c \"import json;d=json.load(open('build.json'));b=d['ui']['bottom_nav'];b[0],b[1]=b[1],b[0];json.dump(d,open('build.json','w'))\""
mutant tab-style "name drawn on every tab even when compact" "sed -i 's/named = !compact || page.id == selectedId/named = true/' ../ab_cloud-libs-shared/libs/ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/navbartop2/NavBarTopSecondRow.kt"
mutant tab-style "SectionScreen back to text pills" "sed -i 's/iconFor = /xxx = /' app/src/main/java/com/diegonmarcos/cloudme/SectionScreen.kt"
mutant settings "Settings entries reduced to one" "python3 -c \"import json;d=json.load(open('build.json'));d['ui']['settings']=['configs'];json.dump(d,open('build.json','w'))\""
mutant settings "a dropdown comes back" "echo 'val x = DropdownMenu' >> app/src/main/java/com/diegonmarcos/cloudme/MeTopBar.kt"
mutant no-drawer "a drag handler is back in the lib top bar" "echo 'val d = detectHorizontalDragGestures' >> ../ab_cloud-libs-shared/libs/bottomnav/src/main/kotlin/com/diegonmarcos/superapp/bottomnav/AppTopBar.kt"
mutant settings "the top bar loses its inline actions" "sed -i 's/inlineActions = true/inlineActions = false/' app/src/main/java/com/diegonmarcos/cloudme/MeTopBar.kt"
mutant pages "the Home section is back" "python3 -c \"import json;d=json.load(open('build.json'));d['ui']['sections'].append({'id':'home','label':'Home','pages':[]});json.dump(d,open('build.json','w'))\""
mutant pages "Tasks page dropped from Duties" "python3 -c \"import json;d=json.load(open('build.json'));[s['pages'].pop() for s in d['ui']['sections'] if s['id']=='duties'];json.dump(d,open('build.json','w'))\""
mutant pages "House comes back" "python3 -c \"import json;d=json.load(open('build.json'));d['ui']['sections'].append({'id':'house','label':'House','pages':[]});json.dump(d,open('build.json','w'))\""
mutant pages "a Buro page leaves its group" "python3 -c \"import json;d=json.load(open('build.json'));[s['groups'][1]['pages'].pop() for s in d['ui']['sections'] if s['id']=='buro'];json.dump(d,open('build.json','w'))\""
mutant pages "an LLC group icon is not a ui-icons name" "sed -i 's/\"icon\": \"payments\"/\"icon\": \"no_such_icon\"/' build.json"
mutant pages "a Buro ledger names the wrong store" "sed -i 's/\"store\": \"buro_user\"/\"store\": \"llc_user\"/' data/ui/buro/assets.json"
mutant pages "a ledger names an unknown report" "sed -i 's/budget_vs_actual/made_up_report/' data/ui/buro/budgeting.json"
mutant pages "the llc store is undeclared in fleet-config" "sed -i 's/\"llc_user\"/\"llc_gone\"/' ../ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json"
mutant achieves "one owner sentence altered" "sed -i 's/Aikido, Yoga and Meditation\\./Aikido and Yoga./' data/ui/achieves/personal.json"
mutant achieves "NomadMania link dropped" "sed -i 's#https://nomadmania.com/profile/73889#https://example.com#' data/ui/achieves/international.json"
mutant no-drawer "the drawer is back in the shell" "echo 'val d = ModalNavigationDrawer' >> app/src/main/java/com/diegonmarcos/cloudme/MainActivity.kt"
mutant no-drawer "a drag handler is back in the shell" "echo 'val d = detectHorizontalDragGestures' >> app/src/main/java/com/diegonmarcos/cloudme/StackScreen.kt"
mutant no-drawer "Profile page gains a swipe handler" "echo '<script>document.addEventListener(\"touchstart\",function(){})</script>' >> data/web/mysocials/linkedin.html"
mutant account "Account tab loses its launch action" "python3 -c \"import json;d=json.load(open('build.json'));[p.pop('action',None) for s in d['ui']['sections'] if s['id']=='buro' for p in s['pages']];json.dump(d,open('build.json','w'))\""
mutant account "Account renamed back to Vault" "sed -i 's/\"label\": \"Account\"/\"label\": \"Vault\"/' build.json"
mutant no-wallet "Wallet link comes back" "sed -i 's#extapp:cloud-account#extapp:cloud-wallet#' data/ui/configs/configs.json"
mutant no-wallet "Wallet section comes back" "python3 -c \"import json;d=json.load(open('build.json'));d['ui']['sections'].append({'id':'wallet','label':'Wallet','pages':[]});json.dump(d,open('build.json','w'))\""
mutant pages "a Health page is dropped" "python3 -c \"import json;d=json.load(open('build.json'));[s['pages'].pop(3) for s in d['ui']['sections'] if s['id']=='health'];json.dump(d,open('build.json','w'))\""

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
