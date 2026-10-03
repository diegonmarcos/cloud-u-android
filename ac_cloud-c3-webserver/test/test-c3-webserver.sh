#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ cloud-c3-webserver — the route table is the ONE declaration, the tabs   ║
# ║ are derived from it, the app is Rust + web (Tauri), the rename holds.   ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# THE FAILURES THIS EXISTS FOR. (1) The previous app's whole landing surface
# was one hand-written page; a Pages/API tab that was typed by hand would drift
# from the server the moment a route changed, and nothing would notice. (2) An
# earlier five-tab APK in this family shipped tabs that drew "not built yet"
# behind green checks. (3) A rename that leaves one copy of the old name behind
# publishes to the wrong asset, package or workflow, and the guard for that is
# a ledger, not a hope.
#
#   T1  ONE ROUTE TABLE. Every "/__…__" route literal anywhere in server/src is a
#       path of a ROUTES row (or its prefix in find/check_table), dispatch is a
#       walk over ROUTES, and no handler is attached by comparing a path outside
#       routes.rs::find.
#   T2  FIVE TABS, HOME CENTRE, DECLARED IN THE TABLE. The tab captions read from
#       ROUTES in order are Pages, API, Home, Files, Configs; each tab row's path
#       is /__tab__/<id>; and the shell has a view for every tab id and a tab id
#       for every view (both directions, so an orphan either way is red).
#   T3  THE SHELL IS DERIVED. shell.html spells no tab caption and no /__tab__/
#       href; its only source is fetch('/__api__/routes'); and its PURE render
#       block, run under node, draws a planted route and a planted tab and does
#       not draw a removed one — the mutation pair at the layer the user sees.
#       Home draws the Configs ▸ About shape: an Index cell per macro group,
#       every group closing with "Go Back Up to Index", and "Copy All Infos"
#       carrying every row (whose labels mirror superapp's own: see
#       test-c3-webserver-about-mirror.sh, kept apart because it reads
#       superapp's source and this file must stay own-source to stay fatal).
#   T4  NO PLACEHOLDER IN A SHIPPED TAB: no not-built wording in ui/ or src/.
#   T5  TAURI SHAPE. build.json::tauri maps every shipped ABI to a tauri target;
#       the app crate's [lib] is the declared lib_name and links the server
#       crate; tauri.conf.json carries build.json's identity and declares NO
#       window (one is opened in code only after start() has bound the port);
#       lib.rs binds first, takes port and root from build.json via build.rs,
#       asks the storage permission from Rust; the template's gradle reads
#       build.json for identity, SDK levels and label; no service, no exec, no
#       server binary, no rootfs residue. (The no-Kotlin pin itself is
#       test-c3-webserver-no-kotlin.sh.)
#   T6  RENAME LEDGER. The retired spellings (the old cloud- id without the c3-
#       family, its asset and image names) appear nowhere in this app.
#   T7  ROUTE <-> TAB END TO END (needs cargo). The compiled /__api__/routes of
#       the real routes.rs, rendered by the shell's own nav code under node, is
#       the five tabs; a tab row planted in a copy of routes.rs appears in the
#       nav, the Configs row removed from a copy disappears.
#   M   mutation-proof: each defect above is planted in a copy and shown red.
#
# OWN-SOURCE ONLY: reads this application's directory and nothing else.
# python3, node, grep, jq.
set -uo pipefail

APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for tool in python3 node grep jq; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR $tool absent — a verdict from a missing tool is not a verdict"; exit 1; }
done
for f in "$APP/build.json" "$APP/server/src/routes.rs" "$APP/server/src/lib.rs" "$APP/server/ui/shell.html" \
         "$APP/src-tauri/src/lib.rs" "$APP/src-tauri/tauri.conf.json" "$APP/src-tauri/gen/android/app/build.gradle.kts"; do
  [ -f "$f" ] || { echo "ERROR missing source: $f — this tester is unrun, not passing"; exit 1; }
done

# ── t1 <app> : one route table ────────────────────────────────────────────
t1() {
  python3 - "$1" <<'PY'
import glob, os, re, sys
app = sys.argv[1]
routes = open(os.path.join(app, "server/src/routes.rs")).read()
table = re.search(r"pub const ROUTES: &\[Route\] = &\[(.*?)\n\];", routes, re.S)
if not table:
    print("    routes.rs has no `pub const ROUTES: &[Route]` table"); sys.exit(1)
declared = re.findall(r'path:\s*"([^"]+)"', table.group(1))
declared += ["/*"] if "path: CATCH_ALL" in table.group(1) else []
bad = []
if len(declared) != len(set(declared)):
    bad.append("a path is declared twice: %s" % sorted({d for d in declared if declared.count(d) > 1}))
# find() must be a walk over ROUTES and the only place a path is compared.
find = re.search(r"pub fn find\(path: &str\) -> &'static Route \{(.*?)\n\}", routes, re.S)
if not find or "for r in ROUTES" not in find.group(1):
    bad.append("routes.rs::find does not iterate ROUTES — dispatch must be a walk over the table")
prefixes = ("/__tab__/",)
for src in sorted(glob.glob(os.path.join(app, "server/src/*.rs"))):
    text = open(src).read()
    # strip the test modules and comments: tests may plant literals on purpose
    body = re.split(r"#\[cfg\(test\)\]", text)[0]
    body = re.sub(r"//.*", "", body)
    for lit in re.findall(r'"(/__[a-z_-]+__(?:/[a-z_-]*)?)"', body):
        if lit in declared or lit in prefixes:
            continue
        bad.append("%s spells route %r, which is not a ROUTES path" % (os.path.relpath(src, app), lit))
    if os.path.basename(src) != "routes.rs":
        for m in re.finditer(r"(req\.path|path)\s*==\s*\"", body):
            bad.append("%s compares a request path to a literal outside routes.rs::find — a handler attached outside the table" % os.path.relpath(src, app))
    else:
        outside_find = body.replace(find.group(0), "") if find else body
        for m in re.finditer(r"req\.path\s*==\s*\"", outside_find):
            bad.append("routes.rs compares req.path to a literal outside find() — a handler attached outside the table")
for b in bad:
    print("    " + b)
sys.exit(1 if bad else 0)
PY
}

# ── t2 <app> : five tabs, Home centre, views both ways ────────────────────
t2() {
  python3 - "$1" <<'PY'
import os, re, sys
app = sys.argv[1]
routes = open(os.path.join(app, "server/src/routes.rs")).read()
table = re.search(r"pub const ROUTES: &\[Route\] = &\[(.*?)\n\];", routes, re.S).group(1)
rows = re.findall(r'path:\s*"([^"]+)",\s*tab:\s*(Some\("([^"]+)"\)|None)', table)
tabs = [(p, t[2]) for p, t, _ in [(r[0], r, r[2]) for r in rows] if t[2]]
captions = [c for _, c in tabs]
bad = []
if captions != ["Pages", "API", "Home", "Files", "Configs"]:
    bad.append("tab captions in table order are %r, want Pages, API, Home, Files, Configs" % captions)
ids = []
for path, cap in tabs:
    m = re.fullmatch(r"/__tab__/([a-z0-9-]+)", path)
    if not m:
        bad.append("tab %r sits at %r, not /__tab__/<id>" % (cap, path)); continue
    ids.append(m.group(1))
shell = open(os.path.join(app, "server/ui/shell.html")).read()
views = re.search(r"var views = \{(.*?)\n  \};", shell, re.S)
if not views:
    bad.append("shell.html has no `var views = {...}` map")
else:
    keys = re.findall(r"^\s*([a-z0-9-]+):\s*function", views.group(1), re.M)
    for i in ids:
        if i not in keys:
            bad.append("tab %r is declared by the server but shell.html has no view for it — it would render an error" % i)
    for k in keys:
        if k not in ids:
            bad.append("shell.html has a view %r that no tab in ROUTES declares — an orphan view" % k)
for b in bad:
    print("    " + b)
sys.exit(1 if bad else 0)
PY
}

# ── t3 <app> : the shell is derived, proven under node ─────────────────────
t3() {
  python3 - "$1" <<'PY'
import os, re, subprocess, sys, tempfile
app = sys.argv[1]
shell = open(os.path.join(app, "server/ui/shell.html")).read()
routes = open(os.path.join(app, "server/src/routes.rs")).read()
table = re.search(r"pub const ROUTES: &\[Route\] = &\[(.*?)\n\];", routes, re.S).group(1)
captions = re.findall(r'tab:\s*Some\("([^"]+)"\)', table)
bad = []
for cap in captions:
    if re.search(r"""['">]%s[<'"]""" % re.escape(cap), shell):
        bad.append("shell.html spells the tab caption %r — captions come from /__api__/routes" % cap)
if re.search(r"""href=["']/__tab__/""", shell):
    bad.append("shell.html hand-writes an href to a /__tab__/ page — the nav must be built from the route table")
if "'/__api__/routes'" not in shell and '"/__api__/routes"' not in shell:
    bad.append("shell.html never fetches /__api__/routes — where would its tabs come from?")
pure = re.search(r'<script id="pure">(.*?)</script>', shell, re.S)
if not pure:
    bad.append("shell.html has no <script id=\"pure\"> block for node to run"); print("\n".join("    " + b for b in bad)); sys.exit(1)
if re.search(r"\b(document|window|fetch|location)\b", pure.group(1)):
    bad.append("the pure block touches document/window/fetch/location — it must be renderable under node")
with tempfile.TemporaryDirectory() as d:
    js = os.path.join(d, "pure.js")
    open(js, "w").write(pure.group(1))
    harness = r"""
const p = require(process.argv[2]);
const base = [
  {kind:'page', path:'/', tab:null, summary:'shell'},
  {kind:'page', path:'/__tab__/alpha', tab:'Alpha', summary:'a'},
  {kind:'page', path:'/__tab__/home', tab:'Home', summary:'h'},
  {kind:'api',  path:'/__api__/one', tab:null, summary:'one'},
  {kind:'page', path:'/*', tab:null, summary:'files'},
];
const planted = base.concat([{kind:'api', path:'/__api__/planted', tab:null, summary:'PLANTED'},
                             {kind:'page', path:'/__tab__/planted', tab:'Planted', summary:'pt'}]);
const removed = base.filter(r => r.path !== '/__api__/one');
function must(c, m){ if(!c){ console.log('    ' + m); process.exitCode = 1; } }
const nav = p.renderNav(p.tabsFrom(planted), 'home');
must(nav.includes('Planted') && nav.includes('Alpha'), 'renderNav does not draw a tab planted in the route table');
must(p.tabsFrom(planted).map(t=>t.id).join(',') === 'alpha,home,planted', 'tabsFrom does not keep table order: ' + p.tabsFrom(planted).map(t=>t.id).join(','));
must(p.renderRoutes(planted, 'api').includes('/__api__/planted'), 'the API tab does not draw a planted API route');
must(p.renderRoutes(planted, 'page').includes('/__tab__/planted'), 'the Pages tab does not draw a planted page route');
must(!p.renderRoutes(removed, 'api').includes('/__api__/one'), 'the API tab still draws a route removed from the table');
must(!p.renderRoutes(base, 'api').includes('/__tab__/'), 'the API tab draws page routes');
must(!p.renderRoutes(base, 'page').includes('/__api__/'), 'the Pages tab draws API routes');
must(p.renderRoutes(base, 'page').includes('href="/"'), 'the catch-all row links to / rather than to a literal *');
must(p.tabFromPath('/__tab__/files') === 'files' && p.tabFromPath('/') === null, 'tabFromPath misreads the URL');
must(p.defaultTab(p.tabsFrom(base)) === 'home', 'defaultTab is not home when home is declared');
must(p.renderSections([{title:'T', rows:[['k','<v>']]}]).includes('&lt;v&gt;'), 'renderSections does not escape values');
must(p.renderListing([{name:'a<b', isDir:true}], '/x').includes('data-dir="/x/a&lt;b"'), 'renderListing does not escape or join paths');
// Home = the Configs ▸ About shape, whatever groups the server sends.
const about = [{macro:'M-ONE', sections:[{title:'S1', rows:[['k1','v1']]}]},
               {macro:'M-TWO', sections:[{title:'S2', rows:[['k2','<v2>']]}, {title:'S3', rows:[['k3','v3']]}]}];
const home = p.renderAbout(about);
const cells = (home.match(/class="cell" data-jump="macro-\d+"/g) || []).length;
must(cells === about.length, 'the Home index has ' + cells + ' cells for ' + about.length + ' macro groups');
const anchors = (home.match(/<h2 class="macro" id="macro-\d+">/g) || []).length;
must(anchors === about.length, 'Home draws ' + anchors + ' macro headers for ' + about.length + ' groups');
const backs = (home.match(/data-jump="about-index">Go Back Up to Index/g) || []).length;
must(backs === about.length, 'Home has ' + backs + ' "Go Back Up to Index" links for ' + about.length + ' groups');
must(home.indexOf('M-ONE') < home.indexOf('M-TWO'), 'Home reorders the server\'s macro groups');
must(home.includes('id="about-index"') && home.includes('data-copy-about'), 'Home has no Index anchor or no Copy All Infos button');
must(home.includes('&lt;v2&gt;') && home.includes('>S3<'), 'Home drops or fails to escape a section');
const text = p.aboutText(about);
must(['M-ONE','M-TWO','S1','S2','S3','k1: v1','k2: <v2>','k3: v3'].every(x => text.includes(x)), 'Copy All Infos omits a group, a section or a row: ' + JSON.stringify(text));
"""
    h = os.path.join(d, "h.js"); open(h, "w").write(harness)
    r = subprocess.run(["node", h, js], capture_output=True, text=True)
    if r.returncode != 0 or r.stdout.strip():
        bad.append("node run of the pure block failed:\n" + r.stdout + r.stderr)
for b in bad:
    print("    " + b)
sys.exit(1 if bad else 0)
PY
}

# ── t4 <app> : no placeholder in a shipped tab ─────────────────────────────
t4() {
  local hits
  hits="$(grep -rniE "not built( yet)?|coming soon|placeholder|TODO_PAGE|under construction|lorem ipsum" "$1/server/ui" "$1/server/src" 2>/dev/null | grep -v "^.*://" || true)"
  if [ -n "$hits" ]; then printf '    %s\n' "$hits"; return 1; fi
  return 0
}

# ── t5 <app> : the Tauri shape ─────────────────────────────────────────────
t5() {
  python3 - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
bj = json.load(open(os.path.join(app, "build.json")))
bad = []
def read(rel):
    p = os.path.join(app, rel)
    return open(p).read() if os.path.isfile(p) else ""
tauri = bj.get("tauri") or {}
TARGETS = {"aarch64", "armv7", "i686", "x86_64"}   # cargo-mobile2's Android target keys
for v in bj["release"]["variants"]:
    for abi in v["abis"]:
        t = (tauri.get("targets") or {}).get(abi) or {}
        if t.get("tauri") not in TARGETS:
            bad.append("build.json::tauri.targets[%s].tauri = %r is not a tauri android target %s" % (abi, t.get("tauri"), sorted(TARGETS)))
        if not str(t.get("rust", "")).startswith(str(t.get("tauri")) + "-linux-android"):
            bad.append("build.json::tauri.targets[%s].rust = %r is not the Android triple of %r" % (abi, t.get("rust"), t.get("tauri")))
for stale in ("server", "bake"):
    if stale in bj:
        bad.append("build.json still carries a %r block — the exec'd binary and the rootfs are retired" % stale)
crate = tauri.get("crate_dir", "")
cargo = read(os.path.join(crate, "Cargo.toml"))
if not re.search(r'\[lib\][^\[]*name\s*=\s*"%s"' % re.escape(tauri.get("lib_name", "\0")), cargo, re.S):
    bad.append("%s/Cargo.toml [lib] name is not build.json::tauri.lib_name — Tauri's activity would load a library that is not there" % crate)
if not re.search(r'crate-type\s*=\s*\[[^\]]*"cdylib"', cargo):
    bad.append("%s/Cargo.toml does not build a cdylib — nothing for Android to load" % crate)
if not re.search(r'c3-webserver\s*=\s*\{\s*path\s*=\s*"\.\./%s"' % re.escape(tauri.get("server_crate", "\0")), cargo):
    bad.append("%s/Cargo.toml does not link the server crate build.json::tauri.server_crate names" % crate)
conf = json.loads(read(os.path.join(crate, "tauri.conf.json")) or "{}")
if conf.get("identifier") != bj["android"]["application_id"]:
    bad.append("tauri.conf.json identifier %r != build.json::android.application_id %r — the APK would change package" % (conf.get("identifier"), bj["android"]["application_id"]))
if conf.get("productName") != bj["name"]:
    bad.append("tauri.conf.json productName %r != build.json::name %r" % (conf.get("productName"), bj["name"]))
if (conf.get("app") or {}).get("windows") != []:
    bad.append("tauri.conf.json declares a window — Tauri opens declared windows BEFORE setup, so the webview would race the server's bind")
lib = re.sub(r"//.*", "", read(os.path.join(crate, "src/lib.rs")))
i_start, i_win = lib.find("c3_webserver::start("), lib.find("WebviewWindowBuilder::new(")
if i_start < 0 or i_win < 0 or i_start > i_win:
    bad.append("src-tauri/src/lib.rs must call c3_webserver::start( before WebviewWindowBuilder::new( — bind first, then open the window")
if 'env!("C3_WEBSERVER_PORT")' not in lib or 'env!("C3_WEBSERVER_SERVE_ROOT")' not in lib:
    bad.append("src-tauri/src/lib.rs does not take port and root from build.json (env! set by build.rs)")
if re.search(r"\b%d\b" % bj["runtime"]["port"], lib) or bj["runtime"]["serve_root"] in lib:
    bad.append("src-tauri/src/lib.rs types the port or the root — they come from build.json::runtime")
if "READ_EXTERNAL_STORAGE" not in lib or "requestPermissions" not in lib:
    bad.append("src-tauri/src/lib.rs does not ask for the storage permission — the served root would be unreadable")
if "build.json" not in read(os.path.join(crate, "build.rs")):
    bad.append("src-tauri/build.rs does not read build.json")
proj = tauri.get("android_project", "")
gradle = read(os.path.join(proj, "app/build.gradle.kts"))
for want, why in ((r'JsonSlurper\(\)\s*\.parse\(\s*file\("[^"]*build\.json"\)', "read build.json"),
                  (r'applicationId\s*=\s*androidJson\["application_id"\]', "take applicationId from build.json"),
                  (r'namespace\s*=\s*androidJson\["application_id"\]', "take the namespace from build.json"),
                  (r'targetSdk\s*=\s*\(androidJson\["target_sdk"\]', "take targetSdk from build.json"),
                  (r'resValue\("string",\s*"app_name",\s*buildJson\["name"\]', "set the launcher label from build.json::name"),
                  (r'useLegacyPackaging\s*=\s*true', "store the Rust library compressed (the size budget)"),
                  (r'manifestPlaceholders\["usesCleartextTraffic"\]\s*=\s*"true"', "allow cleartext to the loopback server in every build type")):
    if not re.search(want, gradle):
        bad.append("%s/app/build.gradle.kts does not %s" % (proj, why))
pkg_dir = os.path.join(proj, "app/src/main/java", bj["android"]["application_id"].replace(".", "/"))
if not os.path.isdir(os.path.join(app, pkg_dir)):
    bad.append("%s is missing — `cargo tauri android build` refuses a project whose package dir does not match the identifier" % pkg_dir)
manifest = read(os.path.join(proj, "app/src/main/AndroidManifest.xml"))
if "android.permission.READ_EXTERNAL_STORAGE" not in manifest:
    bad.append("the manifest does not declare READ_EXTERNAL_STORAGE")
if "<service" in manifest:
    bad.append("the manifest declares a service — there is no hand-written Android component in this app")
for stale in ("resolve_runtime.py", "test/test-webserver-rootfs.sh", "app", "settings.gradle", "build.gradle"):
    if os.path.exists(os.path.join(app, stale)):
        bad.append("%s still exists — the Kotlin shell / Route D residue" % stale)
for b in bad:
    print("    " + b)
sys.exit(1 if bad else 0)
PY
}

# ── t6 <app> : rename ledger ───────────────────────────────────────────────
t6() {
  # The retired spellings, assembled so this file does not itself trip the grep.
  local old_id="cloud-web""server" old_asset="Cloud-Web""Server" hits
  hits="$(grep -rnI --exclude-dir=test --exclude-dir=target --exclude-dir=build --exclude-dir=dist --exclude-dir=.gradle \
           -e "$old_id" -e "$old_asset" "$1" 2>/dev/null || true)"
  if [ -n "$hits" ]; then printf '    %s\n' "$hits"; return 1; fi
  jq -e --arg n "$(basename "$1" | sed 's/^ac_//')" '.name == $n' "$1/build.json" >/dev/null 2>&1 \
    || { echo "    build.json::name is not the directory's name without ac_ — the fleet id would not match"; return 1; }
  return 0
}

# ── t7 <app> : route <-> tab, end to end ───────────────────────────────────
# The chain the user sees: routes.rs ROUTES -> (compiled) /__api__/routes JSON
# -> the shell's own tabsFrom/renderNav under node -> the bottom-nav buttons.
# On copies: the real table draws exactly Pages, API, Home, Files, Configs; a
# tab row PLANTED in routes.rs appears in the nav; the Configs row REMOVED from
# routes.rs disappears from it. Needs cargo (the crate is compiled).
nav_of() {  # nav_of <app> -> one caption per line, as rendered
  local out json
  out="$(cd "$1/server" && CARGO_TARGET_DIR="$T7_TARGET" cargo test -q --lib dump_routes_json -- --ignored --nocapture 2>/dev/null)" || return 1
  json="$(printf '%s' "$out" | sed -n 's/.*ROUTES_JSON_BEGIN\(.*\)ROUTES_JSON_END.*/\1/p')"
  [ -n "$json" ] || return 1
  python3 - "$1/server/ui/shell.html" "$json" <<'PY'
import os, re, subprocess, sys, tempfile
pure = re.search(r'<script id="pure">(.*?)</script>', open(sys.argv[1]).read(), re.S).group(1)
with tempfile.TemporaryDirectory() as d:
    js = os.path.join(d, "pure.js"); open(js, "w").write(pure)
    h = os.path.join(d, "h.js"); open(h, "w").write(
        "const p=require(process.argv[2]);const r=JSON.parse(process.argv[3]);"
        "const t=p.tabsFrom(r);const n=p.renderNav(t,p.defaultTab(t));"
        "(n.match(/<button[^>]*>[^<]*<\\/button>/g)||[]).forEach(b=>console.log(b.replace(/<[^>]*>/g,'')));")
    r = subprocess.run(["node", h, js, sys.argv[2]], capture_output=True, text=True)
    sys.stdout.write(r.stdout); sys.exit(r.returncode)
PY
}
t7() {
  local copy nav rc=0
  T7_TARGET="$WORK7/target"
  nav="$(nav_of "$1" | tr '\n' ',')"
  [ "$nav" = "Pages,API,Home,Files,Configs," ] || { echo "    real table: nav is '$nav', want Pages,API,Home,Files,Configs"; rc=1; }
  copy="$WORK7/plant"; rm -rf "$copy"; mkdir -p "$copy"; cp -R "$1/server" "$copy/"; rm -rf "$copy/server/target"
  python3 - "$copy/server/src/routes.rs" <<'PY' || { echo "    plant did not apply"; return 1; }
import sys; p = sys.argv[1]; s = open(p).read()
anchor = '    Route { kind: Kind::Api, path: "/__api__/routes"'
assert anchor in s
s = s.replace(anchor, '    Route { kind: Kind::Page, path: "/__tab__/planted", tab: Some("Planted"), summary: "planted", handler: page_shell },\n' + anchor, 1)
open(p, "w").write(s)
PY
  nav="$(nav_of "$copy" | tr '\n' ',')"
  [ "$nav" = "Pages,API,Home,Files,Configs,Planted," ] || { echo "    tab row planted: nav is '$nav', want ...,Configs,Planted"; rc=1; }
  copy="$WORK7/remove"; rm -rf "$copy"; mkdir -p "$copy"; cp -R "$1/server" "$copy/"; rm -rf "$copy/server/target"
  python3 - "$copy/server/src/routes.rs" <<'PY' || { echo "    remove did not apply"; return 1; }
import re, sys; p = sys.argv[1]; s = open(p).read()
n = re.sub(r'\n    Route \{ kind: Kind::Page, path: "/__tab__/configs".*?\},', "", s, count=1)
assert n != s; open(p, "w").write(n)
PY
  nav="$(nav_of "$copy" | tr '\n' ',')"
  [ "$nav" = "Pages,API,Home,Files," ] || { echo "    Configs row removed: nav is '$nav', want Pages,API,Home,Files"; rc=1; }
  return $rc
}

WORK7="$(mktemp -d)"
echo "── cloud-c3-webserver ──"
t1 "$APP" && pass "T1 one route table: every route literal is a ROUTES path and dispatch walks the table" || fail "T1 a route lives outside the table"
t2 "$APP" && pass "T2 five tabs, Home centre, declared in the table; every tab has a view and every view a tab" || fail "T2 tabs and views disagree"
t3 "$APP" && pass "T3 the shell is derived: no caption or /__tab__/ href typed, and under node it draws a planted route and drops a removed one" || fail "T3 the shell hand-lists or misrenders"
t4 "$APP" && pass "T4 no placeholder wording in a shipped tab" || fail "T4 a shipped tab can render a placeholder"
t5 "$APP" && pass "T5 Tauri shape: ABIs mapped, lib + server crate linked, identity from build.json, bind before window, storage asked from Rust, no shell residue" || fail "T5 the Tauri shape is broken"
if command -v cargo >/dev/null 2>&1; then
  t7 "$APP" && pass "T7 route<->tab end to end: a tab row planted in routes.rs appears in the rendered nav, a removed one disappears" || fail "T7 the nav does not follow routes.rs"
else
  echo "  SKIP  T7 cargo absent — the route<->tab chain is unverified here"
fi
t6 "$APP" && pass "T6 rename ledger clean: no retired spelling anywhere in this app" || fail "T6 a retired spelling survives"

# ── M: mutation-proof ──────────────────────────────────────────────────────
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK" "$WORK7"' EXIT
mutate() {
  local title="$1" check="$2" mut="$3" copy="$WORK/m$RANDOM$RANDOM"
  mkdir -p "$copy"; cp -R "$APP/." "$copy/"
  rm -rf "$copy/server/target" "$copy/src-tauri/target" "$copy/build" "$copy/dist" \
         "$copy/src-tauri/gen/android/app/build" "$copy/src-tauri/gen/android/build" "$copy/src-tauri/gen/android/.gradle"
  "$mut" "$copy" || { fail "M: mutation '$title' did not apply — a mutation that does not mutate proves nothing"; return; }
  if "$check" "$copy" >/dev/null 2>&1; then fail "M: '$title' stayed GREEN — the check is vacuous"; else pass "M: '$title' goes red"; fi
}
m_route_outside() { python3 - "$1" <<'PY'
import sys,re; p=sys.argv[1]+"/server/src/lib.rs"; s=open(p).read()
new=s.replace("    let route = routes::find(&req.path);", "    if req.path == \"/__planted__/x\" { return Resp::text(200, \"planted\"); }\n    let route = routes::find(&req.path);")
assert new!=s; open(p,"w").write(new)
PY
}
m_nav_hardcoded() { python3 - "$1" <<'PY'
import sys; p=sys.argv[1]+"/server/ui/shell.html"; s=open(p).read()
new=s.replace('<nav id="nav" aria-label="tabs"></nav>', '<nav id="nav" aria-label="tabs"><a href="/__tab__/pages">Pages</a></nav>')
assert new!=s; open(p,"w").write(new)
PY
}
m_placeholder() { python3 - "$1" <<'PY'
import sys; p=sys.argv[1]+"/server/ui/shell.html"; s=open(p).read()
new=s.replace('<main id="main">', '<main id="main"><div>not built yet</div>')
assert new!=s; open(p,"w").write(new)
PY
}
m_no_legacy_packaging() { python3 - "$1" <<'PY'
import sys,re; p=sys.argv[1]+"/src-tauri/gen/android/app/build.gradle.kts"; s=open(p).read()
new=re.sub(r"useLegacyPackaging\s*=\s*true", "useLegacyPackaging = false", s)
assert new!=s; open(p,"w").write(new)
PY
}
m_old_asset() { python3 - "$1" <<'PY'
import sys,json; p=sys.argv[1]+"/build.json"; d=json.load(open(p))
d["release"]["gh_release"]["asset_name"]="Cloud-Web"+"Server.apk"; json.dump(d,open(p,"w"),indent=2)
PY
}
m_drop_home_tab() { python3 - "$1" <<'PY'
import sys,re; p=sys.argv[1]+"/server/src/routes.rs"; s=open(p).read()
new=re.sub(r'\n    Route \{ kind: Kind::Page, path: "/__tab__/home".*?\},', "", s, count=1, flags=re.S)
assert new!=s; open(p,"w").write(new)
PY
}
m_view_orphan() { python3 - "$1" <<'PY'
import sys; p=sys.argv[1]+"/server/ui/shell.html"; s=open(p).read()
new=s.replace("    configs: function ()", "    ghost: function () { return Promise.resolve(''); },\n    configs: function ()")
assert new!=s; open(p,"w").write(new)
PY
}
m_render_drops_route() { python3 - "$1" <<'PY'
import sys; p=sys.argv[1]+"/server/ui/shell.html"; s=open(p).read()
new=s.replace("var rows = routes.filter(function (r) { return r.kind === kind; });", "var rows = routes.filter(function (r) { return r.kind === kind && r.path.indexOf('planted') < 0; });")
assert new!=s; open(p,"w").write(new)
PY
}
m_rust_port() { python3 - "$1" <<'PY'
import sys; p=sys.argv[1]+"/src-tauri/src/lib.rs"; s=open(p).read()
new=s.replace('env!("C3_WEBSERVER_PORT").parse()?', '"8000".parse()?')
assert new!=s; open(p,"w").write(new)
PY
}
m_window_before_bind() { python3 - "$1" <<'PY'
import sys,json; p=sys.argv[1]+"/src-tauri/tauri.conf.json"; d=json.load(open(p))
d["app"]["windows"]=[{"url":"http://127.0.0.1:8000/"}]; json.dump(d,open(p,"w"),indent=2)
PY
}
m_identifier_drift() { python3 - "$1" <<'PY'
import sys,json; p=sys.argv[1]+"/src-tauri/tauri.conf.json"; d=json.load(open(p))
d["identifier"]="com.diegonmarcos.cloudc3webserver"; json.dump(d,open(p,"w"),indent=2)
PY
}
m_about_no_back_link() { python3 - "$1" <<'PY'
import sys; p=sys.argv[1]+"/server/ui/shell.html"; s=open(p).read()
new=s.replace("""renderSections(g.sections) +
      '<div class="back"><a data-jump="about-index">Go Back Up to Index</a></div>';""", "renderSections(g.sections);")
assert new!=s; open(p,"w").write(new)
PY
}
m_copy_all_drops_rows() { python3 - "$1" <<'PY'
import sys; p=sys.argv[1]+"/server/ui/shell.html"; s=open(p).read()
new=s.replace("return '# ' + s.title + '\\n' + s.rows.map(", "return '# ' + s.title + '\\n' + s.rows.slice(1).map(")
assert new!=s; open(p,"w").write(new)
PY
}
m_json_drops_tab() { python3 - "$1" <<'PY'
import sys; p=sys.argv[1]+"/server/src/routes.rs"; s=open(p).read()
new=s.replace('match r.tab { Some(t) => jstr(t), None => "null".to_string() }', '"null".to_string()')
assert new!=s; open(p,"w").write(new)
PY
}
if command -v cargo >/dev/null 2>&1; then
  mutate "/__api__/routes stops carrying the tab caption"  t7 m_json_drops_tab
fi
mutate "a handler attached outside the route table"        t1 m_route_outside
mutate "the Home tab removed from the table"                t2 m_drop_home_tab
mutate "a view with no declared tab"                        t2 m_view_orphan
mutate "a hand-written tab link in the nav"                 t3 m_nav_hardcoded
mutate "the render function silently drops a route"         t3 m_render_drops_route
mutate "a not-built body in the shell"                      t4 m_placeholder
mutate "Home loses its Go Back Up to Index links"          t3 m_about_no_back_link
mutate "Copy All Infos drops a row"                         t3 m_copy_all_drops_rows
mutate "the Rust library stored uncompressed"               t5 m_no_legacy_packaging
mutate "the port typed into the Tauri app"                  t5 m_rust_port
mutate "a window declared in tauri.conf.json (races bind)"  t5 m_window_before_bind
mutate "tauri.conf.json identifier drifts from build.json"  t5 m_identifier_drift
mutate "the retired asset name put back"                    t6 m_old_asset

echo
if [ "$FAILURES" -eq 0 ]; then echo "── test-c3-webserver: all green ──"; else echo "── test-c3-webserver: $FAILURES FAILED ──"; exit 1; fi
