#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ cloud-c3-webserver — the route table is the ONE declaration, the tabs   ║
# ║ are derived from it, the binary ships natively, the rename is complete. ║
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
#   T4  NO PLACEHOLDER IN A SHIPPED TAB: no not-built wording in ui/ or src/.
#   T5  NATIVE, NOT PROOT. build.json::server names a lib*.so jni_name and a musl
#       target per shipped ABI; gradle stages into jniLibs with legacy packaging
#       and refuses a PT_INTERP; Kotlin execs nativeLibraryDir/<BuildConfig name>
#       and holds no port, root or path literal; no bake/rootfs/proot remains.
#   T6  RENAME LEDGER. The retired spellings (the old cloud- id without the c3-
#       family, its asset and image names) appear nowhere in this app.
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
for f in "$APP/build.json" "$APP/server/src/routes.rs" "$APP/server/src/main.rs" "$APP/server/ui/shell.html" "$APP/app/build.gradle"; do
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

# ── t5 <app> : native binary, exec'd from nativeLibraryDir ─────────────────
t5() {
  python3 - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
bj = json.load(open(os.path.join(app, "build.json")))
bad = []
srv = bj.get("server") or {}
if not re.fullmatch(r"lib[a-z0-9_]+\.so", srv.get("jni_name", "")):
    bad.append("build.json::server.jni_name %r is not lib*.so — only that shape is extracted to nativeLibraryDir" % srv.get("jni_name"))
for v in bj["release"]["variants"]:
    for abi in v["abis"]:
        t = (srv.get("targets") or {}).get(abi, "")
        if not t.endswith("-unknown-linux-musl"):
            bad.append("build.json::server.targets[%s] = %r is not a static musl target" % (abi, t))
if "bake" in bj:
    bad.append("build.json still carries a bake block — the rootfs route is retired")
gradle = open(os.path.join(app, "app/build.gradle")).read()
if not re.search(r"useLegacyPackaging\s*=\s*true", gradle):
    bad.append("app/build.gradle does not set jniLibs.useLegacyPackaging = true — the binary would stay compressed in the APK and never reach nativeLibraryDir")
if "hasProgramInterpreter" not in gradle or "PT_INTERP" not in gradle:
    bad.append("app/build.gradle does not refuse a PT_INTERP — a dynamically linked binary would die ENOENT on the phone")
if "jniLibs.srcDirs" not in gradle:
    bad.append("app/build.gradle does not add the staged directory to jniLibs")
for key in ("SERVER_JNI_NAME", "SERVER_PORT", "SERVE_ROOT"):
    if key not in gradle:
        bad.append("app/build.gradle bakes no BuildConfig.%s" % key)
kt_dir = os.path.join(app, "app/src/main/java")
kts = {}
for base, _, files in os.walk(kt_dir):
    for f in files:
        if f.endswith(".kt"):
            kts[f] = open(os.path.join(base, f)).read()
allkt = "\n".join(kts.values())
if "applicationInfo.nativeLibraryDir" not in allkt or "BuildConfig.SERVER_JNI_NAME" not in allkt:
    bad.append("no Kotlin file execs applicationInfo.nativeLibraryDir/BuildConfig.SERVER_JNI_NAME")
for f, text in kts.items():
    code = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    code = re.sub(r"//.*", "", code)
    if re.search(r"\b%d\b" % bj["runtime"]["port"], code):
        bad.append("%s types the port %d — it must come from BuildConfig" % (f, bj["runtime"]["port"]))
    if "/storage/" in code or "/nix/" in code or "proot" in code.lower() or "rootfs" in code.lower():
        bad.append("%s holds a path or a rootfs word — the app knows only BuildConfig" % f)
for stale in ("resolve_runtime.py", "test/test-webserver-rootfs.sh"):
    if os.path.exists(os.path.join(app, "app", stale)) or os.path.exists(os.path.join(app, stale)):
        bad.append("%s still exists — Route D residue" % stale)
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

echo "── cloud-c3-webserver ──"
t1 "$APP" && pass "T1 one route table: every route literal is a ROUTES path and dispatch walks the table" || fail "T1 a route lives outside the table"
t2 "$APP" && pass "T2 five tabs, Home centre, declared in the table; every tab has a view and every view a tab" || fail "T2 tabs and views disagree"
t3 "$APP" && pass "T3 the shell is derived: no caption or /__tab__/ href typed, and under node it draws a planted route and drops a removed one" || fail "T3 the shell hand-lists or misrenders"
t4 "$APP" && pass "T4 no placeholder wording in a shipped tab" || fail "T4 a shipped tab can render a placeholder"
t5 "$APP" && pass "T5 static musl binary staged as lib*.so with legacy packaging, PT_INTERP refused, exec'd from nativeLibraryDir, no rootfs residue" || fail "T5 the native shape is broken"
t6 "$APP" && pass "T6 rename ledger clean: no retired spelling anywhere in this app" || fail "T6 a retired spelling survives"

# ── M: mutation-proof ──────────────────────────────────────────────────────
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
mutate() {
  local title="$1" check="$2" mut="$3" copy="$WORK/m$RANDOM$RANDOM"
  mkdir -p "$copy"; cp -R "$APP/." "$copy/"
  rm -rf "$copy/server/target" "$copy/app/build" "$copy/build" "$copy/dist"
  "$mut" "$copy" || { fail "M: mutation '$title' did not apply — a mutation that does not mutate proves nothing"; return; }
  if "$check" "$copy" >/dev/null 2>&1; then fail "M: '$title' stayed GREEN — the check is vacuous"; else pass "M: '$title' goes red"; fi
}
m_route_outside() { python3 - "$1" <<'PY'
import sys,re; p=sys.argv[1]+"/server/src/main.rs"; s=open(p).read()
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
import sys,re; p=sys.argv[1]+"/app/build.gradle"; s=open(p).read()
new=re.sub(r"packaging \{ jniLibs \{ useLegacyPackaging = true \} \}", "", s)
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
m_kotlin_port() { python3 - "$1" <<'PY'
import sys,glob; p=glob.glob(sys.argv[1]+"/app/src/main/java/**/ServerProcess.kt", recursive=True)[0]; s=open(p).read()
new=s.replace("val port: Int = BuildConfig.SERVER_PORT", "val port: Int = 8000")
assert new!=s; open(p,"w").write(new)
PY
}
mutate "a handler attached outside the route table"        t1 m_route_outside
mutate "the Home tab removed from the table"                t2 m_drop_home_tab
mutate "a view with no declared tab"                        t2 m_view_orphan
mutate "a hand-written tab link in the nav"                 t3 m_nav_hardcoded
mutate "the render function silently drops a route"         t3 m_render_drops_route
mutate "a not-built body in the shell"                      t4 m_placeholder
mutate "legacy jni packaging removed"                       t5 m_no_legacy_packaging
mutate "the port typed into Kotlin"                         t5 m_kotlin_port
mutate "the retired asset name put back"                    t6 m_old_asset

echo
if [ "$FAILURES" -eq 0 ]; then echo "── test-c3-webserver: all green ──"; else echo "── test-c3-webserver: $FAILURES FAILED ──"; exit 1; fi
