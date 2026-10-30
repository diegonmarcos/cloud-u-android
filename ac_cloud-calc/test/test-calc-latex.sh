#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #1329 Cloud Calc > Calculator > LaTeX: declared, wired, bundled, offline  ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
#   T1  the page and its modes are DECLARED: ui.sections[calculator].pages reads calc, latex, convert (in that order),
#       the latex tab holds the latex_library and latex_editor modes, the page icon is in IconCatalog, and build.json::latex
#       declares the renderer paths, the catalog, the palette and Evaluate's rules.
#   T2  the declaration is WIRED: ModeScreen dispatches both kinds, app/build.gradle bakes build.json::latex and adds the
#       data/assets srcDir, the share cache directory is a FileProvider path.
#   T3  the renderer is BUNDLED: every declared path exists, the stylesheet's fonts are all there, viewer.html loads the
#       declared script and style, viewer.js speaks to the declared bridge name.
#   T4  it is OFFLINE: no address in the viewer, its script or the stylesheet; the bundled KaTeX names only the two XML
#       namespace URIs it must (MathML, SVG), loads nothing; the WebView blocks network loads; no Kotlin of the page spells an address.
#   T5  the LIBRARY is the owner's: 100 formulas in 9 categories, generated from data/latex/100-formulas.md (gen-formulas.mjs
#       --check: no drift), and every one renders in the bundled KaTeX with throwOnError (gen-formulas.mjs --render).
#   T6  the licence: NOTICE.md carries the KaTeX entry (name, version, MIT), the vendored LICENSE is KaTeX's MIT text, and the
#       licence inventory covers the directory and the fonts.
#   T7  declarative: no formula, symbol or palette entry is spelled in the page's Kotlin.
#   T8  #1336 the Graph page is DECLARED: the strip reads Library, Edit, Graph (latex_graph, icon from the catalog), build.json::latex.graph
#       declares the viewer, its three scripts, the bridge, the modes, the speed limits and the Plot-my-formula rules, ModeScreen dispatches
#       latex_graph, the detail opens it (openLatexGraph) and every string the plot UI uses exists.
#   T9  the plots are BUNDLED and OFFLINE: graph.html loads expr.js, model.js and graph.js; graph.js speaks to the declared bridge; no address
#       in any plot asset; no eval / Function / dynamic code; both WebViews block network loads; NOTICE says the plots are own code.
#   T10 the plots are DATA: plots.json holds one spec per formula (gen-formulas.mjs --check/--plots/--render: shapes, defaults in range, every
#       expression parsed by the safe parser, every plot sampled over its default grid with no NaN/Infinity flood), the owner's 100-formulas.md
#       is byte-for-byte untouched, the catalog is 125 formulas in 10 categories with a pi category of 25 (+3 referenced), test-plots.mjs
#       (the expression vectors incl. every reject, and every formula drawn and probed by the real graph.js in a fake DOM) passes.
#   MUT each property, broken on a copy (and the edit proven to have landed), goes red.
#
# OWN-SOURCE ONLY (reads ac_cloud-calc and licenses/): python3 + node + grep.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
command -v python3 >/dev/null 2>&1 || { echo "ERROR python3 required"; exit 2; }
command -v node >/dev/null 2>&1 || { echo "ERROR node required (the library is rendered headlessly)"; exit 2; }

# The files a copy needs for the checks (relative to the app).
J='app/src/main/java/com/diegonmarcos/cloudcalc'
FILES="build.json NOTICE.md app/build.gradle app/src/main/res/xml/file_paths.xml app/src/main/res/values/strings.xml test/test-plots.mjs $J/Latex.kt $J/LatexPlot.kt $J/PlotExpr.kt $J/ui/ModeScreens.kt $J/ui/LatexScreens.kt $J/ui/CalcState.kt $J/ui/IconCatalog.kt"

# check <app-copy> <licences.json copy>: prints one FAIL line per broken property.
CHECK="$(mktemp)"
cat > "$CHECK" <<'PY'
import json, os, re, subprocess, sys
app, inv = sys.argv[1], sys.argv[2]
J = os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudcalc")
A = os.path.join(app, "data/assets")
bad = []
def read(p):
    try:
        return open(p, encoding="utf-8").read()
    except OSError:
        bad.append("missing file %s" % os.path.relpath(p, app)); return ""
bj = json.loads(read(os.path.join(app, "build.json")) or "{}")
ui = bj.get("ui", {}); lx = bj.get("latex", {})

# T1
calc = next((s for s in ui.get("sections", []) if s.get("id") == "calculator"), {})
pages = [p.get("id") for p in calc.get("pages", [])]
if pages[:3] != ["calc", "latex", "convert"]:
    bad.append("T1 the Calculator pages must read calc, latex, convert first, found %s" % pages)
modes = [m for m in ui.get("modes", []) if m.get("tab") == "latex"]
if [(m.get("id"), m.get("kind")) for m in modes] != [("latex_library", "latex_library"), ("latex_editor", "latex_editor"), ("latex_graph", "latex_graph")]:
    bad.append("T1 tab latex must hold the latex_library, latex_editor then latex_graph mode, found %s" % [(m.get("id"), m.get("kind")) for m in modes])
icons = read(os.path.join(J, "ui/IconCatalog.kt"))
for pg in calc.get("pages", []):
    if pg.get("id") == "latex" and '"%s" ->' % pg.get("icon") not in icons:
        bad.append("T1 the latex page icon %s has no branch in IconCatalog" % pg.get("icon"))
for k in ("renderer", "catalog", "palette", "eval", "mine_category", "favourites_category", "share", "preview_debounce_ms", "history_kind"):
    if k not in lx:
        bad.append("T1 build.json::latex.%s is not declared" % k)
r = lx.get("renderer", {})
for k in ("viewer", "script", "auto_render", "style", "fonts_dir", "license_file", "bridge"):
    if not r.get(k):
        bad.append("T1 build.json::latex.renderer.%s is not declared" % k)
if not lx.get("palette") or any(not g.get("symbols") for g in lx.get("palette", [])):
    bad.append("T1 build.json::latex.palette must hold groups that each hold symbols")
if not lx.get("eval", {}).get("commands"):
    bad.append("T1 build.json::latex.eval.commands is empty")
if lx.get("history_kind") != "latex":
    bad.append("T1 history entries must be kind latex")

# T2
ms = read(os.path.join(J, "ui/ModeScreens.kt"))
for kind, fn in (("latex_library", "LatexLibraryMode"), ("latex_editor", "LatexEditorMode")):
    if '"%s" -> %s(mode)' % (kind, fn) not in ms:
        bad.append("T2 ModeScreen does not dispatch kind %s to %s" % (kind, fn))
gr = read(os.path.join(app, "app/build.gradle"))
if "def latexConfigB64 = b64(buildJson.latex)" not in gr or '"LATEX_CONFIG_B64", "\\"${latexConfigB64}\\""' not in gr:
    bad.append("T2 app/build.gradle does not bake build.json::latex as LATEX_CONFIG_B64")
if "../data/assets" not in gr:
    bad.append("T2 app/build.gradle does not add the data/assets srcDir to the APK assets")
fp = read(os.path.join(app, "app/src/main/res/xml/file_paths.xml"))
if not re.search(r'<cache-path[^>]*path="%s/"' % re.escape(lx.get("share", {}).get("cache_dir", "?")), fp):
    bad.append("T2 file_paths.xml has no cache-path for the share directory")

# T3
for k in ("viewer", "script", "auto_render", "style", "license_file"):
    p = os.path.join(A, r.get(k, "?"))
    if not os.path.isfile(p):
        bad.append("T3 renderer asset %s (%s) is not in the APK assets" % (k, r.get(k)))
css = read(os.path.join(A, r.get("style", "?")))
fonts_dir = os.path.join(A, r.get("fonts_dir", "?"))
used = set(re.findall(r"url\(fonts/([^)]+)\)", css))
if not used:
    bad.append("T3 the stylesheet names no font")
for f in sorted(used):
    if not os.path.isfile(os.path.join(fonts_dir, f)):
        bad.append("T3 the stylesheet names font %s, which is not bundled" % f)
html = read(os.path.join(A, r.get("viewer", "?")))
vdir = os.path.dirname(r.get("viewer", ""))
for k in ("script", "auto_render", "style"):
    rel = os.path.relpath(r.get(k, "?"), vdir) if vdir else r.get(k, "?")
    if rel not in html:
        bad.append("T3 viewer.html does not load the declared %s (%s)" % (k, rel))
js = read(os.path.join(A, vdir, "viewer.js"))
if 'window.%s' % r.get("bridge", "?") not in js:
    bad.append("T3 viewer.js does not use the declared bridge window.%s" % r.get("bridge"))

# T4
addr = re.compile(r"https?://|//[a-z0-9.-]+\.[a-z]{2,}/|@import|ftp://", re.I)
for name, text in (("viewer.html", html), ("viewer.js", js), ("katex.min.css", css)):
    m = addr.search(text)
    if m:
        bad.append("T4 %s names an address (%s): the renderer must load nothing" % (name, m.group(0)))
allowed = {"http://www.w3.org/1998/Math/MathML", "http://www.w3.org/2000/svg"}
for k in ("script", "auto_render"):
    t = read(os.path.join(A, r.get(k, "?")))
    for u in set(re.findall(r"https?://[^\"'\s)]+", t)):
        if u not in allowed:
            bad.append("T4 bundled %s names address %s" % (r.get(k), u))
    if re.search(r"XMLHttpRequest|importScripts|WebSocket|sendBeacon|\.src\s*=\s*[\"']http", t):
        bad.append("T4 bundled %s can load over the network" % r.get(k))
ls = read(os.path.join(J, "ui/LatexScreens.kt"))
if "blockNetworkLoads = true" not in ls:
    bad.append("T4 LatexScreens.kt does not set blockNetworkLoads = true")
for name, text in (("LatexScreens.kt", ls), ("Latex.kt", read(os.path.join(J, "Latex.kt")))):
    code = re.sub(r"//.*", "", text)
    if re.search(r"https?://|java\.net\.|HttpURLConnection|URL\(|Socket\(", code):
        bad.append("T4 %s spells an address or opens the network" % name)

# T5
gen = os.path.join(app, "data/latex/gen-formulas.mjs")
for mode in ("--check", "--render"):
    p = subprocess.run(["node", gen, mode], capture_output=True, text=True)
    if p.returncode != 0:
        bad.append("T5 gen-formulas.mjs %s failed: %s" % (mode, (p.stderr or p.stdout).strip().replace("\n", " | ")[:300]))
try:
    cat = json.loads(read(os.path.join(A, lx.get("catalog", "?"))))
    fs, cs = cat["formulas"], cat["categories"]
    if len(fs) != 125 or len(cs) != 10:
        bad.append("T5 the catalog must hold 125 formulas (the owner's 100 + 25 pi) in 10 categories, found %d in %d" % (len(fs), len(cs)))
    if len({f["id"] for f in fs}) != len(fs):
        bad.append("T5 catalog ids are not unique")
except (ValueError, KeyError):
    bad.append("T5 the catalog does not parse")

# T6
notice = read(os.path.join(app, "NOTICE.md"))
if not re.search(r"KaTeX[^\n]*%s[^\n]*\| MIT" % re.escape(r.get("version", "?")), notice):
    bad.append("T6 NOTICE.md has no KaTeX %s | MIT row" % r.get("version"))
lic = read(os.path.join(A, r.get("license_file", "?")))
if "MIT License" not in lic or "Khan Academy" not in lic:
    bad.append("T6 the vendored KaTeX LICENSE is not the MIT text")
if r.get("license") != "MIT":
    bad.append("T6 build.json::latex.renderer.license must be MIT")
if inv != "-":
    ents = json.loads(read(inv)).get("entries", {})
    key = "vendored:ac_cloud-calc/data/assets/latex/katex"
    if ents.get(key, {}).get("licence") != "MIT":
        bad.append("T6 the licence inventory has no MIT entry for %s" % key)
    fonts = [k for k in ents if k.startswith("asset:ac_cloud-calc/data/assets/latex/katex/fonts/")]
    if len(fonts) < len(used):
        bad.append("T6 the licence inventory covers %d of %d bundled fonts" % (len(fonts), len(used)))

# T7
code = re.sub(r"//.*", "", ls)
for lit in ("Pythagorean", "Euler", "Schr", "\\\\alpha", "\\\\int", "Maxwell"):
    if lit in code:
        bad.append("T7 LatexScreens.kt spells %s: formulas and the palette are data" % lit)

# T8 (#1336)
if [m.get("short") for m in modes] != ["Library", "Edit", "Graph"] or [m.get("label") for m in modes] != ["Library", "Edit", "Graph"]:
    bad.append("T8 the strip must read Library | Edit | Graph, found %s" % [m.get("label") for m in modes])
gm = next((m for m in modes if m.get("id") == "latex_graph"), {})
if gm.get("icon") and '"%s" ->' % gm.get("icon") not in icons:
    bad.append("T8 the Graph mode icon %s has no branch in IconCatalog" % gm.get("icon"))
if not gm.get("icon"):
    bad.append("T8 the Graph mode declares no icon")
gr_cfg = lx.get("graph", {})
for k in ("renderer", "modes", "default_mode", "speed", "zoom_step", "default_formula", "plot_commands", "greek", "ignored", "custom"):
    if k not in gr_cfg:
        bad.append("T8 build.json::latex.graph.%s is not declared" % k)
gren = gr_cfg.get("renderer", {})
for k in ("viewer", "scripts", "bridge"):
    if not gren.get(k):
        bad.append("T8 build.json::latex.graph.renderer.%s is not declared" % k)
if gr_cfg.get("modes") != ["both", "2d", "3d"]:
    bad.append("T8 build.json::latex.graph.modes must be both, 2d, 3d")
if '"latex_graph" -> LatexGraphMode(mode)' not in ms:
    bad.append("T8 ModeScreen does not dispatch kind latex_graph to LatexGraphMode")
if "openLatexGraph(" not in ls or "fun openLatexGraph" not in read(os.path.join(J, "ui/CalcState.kt")):
    bad.append("T8 the formula detail does not open the Graph page (openLatexGraph)")
strings = read(os.path.join(app, "app/src/main/res/values/strings.xml"))
for ref in sorted(set(re.findall(r"R\.string\.(latex_\w+)", ls))):
    if 'name="%s"' % ref not in strings:
        bad.append("T8 LatexScreens.kt uses R.string.%s, which strings.xml lacks" % ref)

# T9 (#1336)
PL = os.path.join(A, "latex/plot")
ghtml = read(os.path.join(A, gren.get("viewer", "?")))
gdir = os.path.dirname(gren.get("viewer", "?"))
for sc in gren.get("scripts", []):
    if not os.path.isfile(os.path.join(A, sc)):
        bad.append("T9 plot script %s is not in the APK assets" % sc)
    rel = os.path.relpath(sc, gdir) if gdir else sc
    if 'src="%s"' % rel not in ghtml:
        bad.append("T9 graph.html does not load the declared script %s" % rel)
gjs = read(os.path.join(PL, "graph.js"))
if 'root.%s' % gren.get("bridge", "?") not in gjs:
    bad.append("T9 graph.js does not use the declared bridge %s" % gren.get("bridge"))
for name in ("graph.html", "graph.js", "expr.js", "model.js"):
    text = read(os.path.join(PL, name))
    m = addr.search(text)
    if m:
        bad.append("T9 plot asset %s names an address (%s): the plots must load nothing" % (name, m.group(0)))
    code = re.sub(r"//.*", "", text)
    if re.search(r"\beval\s*\(|new\s+Function|\bFunction\s*\(|XMLHttpRequest|importScripts|WebSocket|sendBeacon|fetch\s*\(", code):
        bad.append("T9 plot asset %s reaches for eval / Function / the network" % name)
if len(re.findall(r"blockNetworkLoads = true", ls)) < 2:
    bad.append("T9 LatexScreens.kt must block network loads in BOTH WebViews (formula renderer and plot viewer)")
if "cfg.graph.viewer" not in ls:
    bad.append("T9 the plot WebView does not load the declared graph viewer")
if not re.search(r"own code, no third-party\s+library", notice):
    bad.append("T9 NOTICE.md does not say the plots are own code with no third-party library")
for f in ("PlotExpr.kt", "LatexPlot.kt"):
    code = re.sub(r"//.*", "", read(os.path.join(J, f)))
    if re.search(r"https?://|java\.net\.|URL\(|Class\.forName|ScriptEngine|\.invoke\(", code):
        bad.append("T9 %s spells an address, reflection or a script engine" % f)

# T10 (#1336)
for mode in ("--plots", "--render"):
    p = subprocess.run(["node", gen, mode], capture_output=True, text=True)
    if p.returncode != 0:
        bad.append("T10 gen-formulas.mjs %s failed: %s" % (mode, (p.stderr or p.stdout).strip().replace("\n", " | ")[:300]))
import hashlib
owner = hashlib.sha256(open(os.path.join(app, "data/latex/100-formulas.md"), "rb").read()).hexdigest()
if owner != "c7fb3de9a37afb8ba734d807dd1ba3ff030b8ce5c06663d493f24e6e727a062c":
    bad.append("T10 the owner's 100-formulas.md was changed: it must stay verbatim")
try:
    plots = json.loads(read(os.path.join(app, "data/latex/plots.json")))["plots"]
    if set(plots) != {f["id"] for f in fs}:
        bad.append("T10 plots.json must hold exactly one spec per formula")
    pic = [f for f in fs if f["category"] == "pi"]
    if len(pic) != 25 or cs[-1]["id"] != "pi" or len(cs[-1].get("includes", [])) != 3:
        bad.append("T10 the pi category must be last, hold 25 formulas of its own and reference 3 of the owner's")
    if any(not f.get("plot") or not f["plot"].get("d2") or not f["plot"].get("d3") or not f["plot"].get("caption") for f in fs):
        bad.append("T10 every formula needs a plot with a 2D view, a 3D view and a caption")
except (ValueError, KeyError):
    bad.append("T10 plots.json or the catalog does not parse")
p = subprocess.run(["node", os.path.join(app, "test/test-plots.mjs")], capture_output=True, text=True)
if p.returncode != 0:
    bad.append("T10 test-plots.mjs failed: %s" % (p.stdout + p.stderr).strip().replace("\n", " | ")[:300])

for b in bad:
    print("  FAIL  " + b)
sys.exit(1 if bad else 0)
PY

FAILURES=0
echo "── T1-T10 against the tree ──"
if python3 -I "$CHECK" "$APP" "$ROOT/licenses/inventory.json"; then echo "  PASS  T1-T10"; else FAILURES=$((FAILURES + 1)); fi

# ── mutations: each must go red, for the right reason ─────────────────────────
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK" "$CHECK"' EXIT
mutate() {  # name, python expression over s applied to <file> (relative to the app), expected message fragment
    local name="$1" rel="$2" expr="$3" want="$4" copy="$WORK/$1"
    mkdir -p "$copy"
    (cd "$APP" && cp --parents $FILES "$copy/" && cp -r --parents data/assets/latex data/latex "$copy/")
    if ! python3 -I - "$copy/$rel" "$expr" <<'PY'
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
    out="$(python3 -I "$CHECK" "$copy" - 2>&1)"
    if [ $? -eq 0 ]; then
        echo "  FAIL  MUT $name: the check passed a broken tree"; FAILURES=$((FAILURES + 1))
    elif [[ "$out" != *"$want"* ]]; then
        echo "  FAIL  MUT $name: red for the wrong reason: $out"; FAILURES=$((FAILURES + 1))
    else
        echo "  PASS  MUT $name"
    fi
}
L='data/assets/latex'
mutate page-id-renamed build.json 's.replace("\"id\": \"latex\",\n            \"label\": \"LaTeX\"", "\"id\": \"latexx\",\n            \"label\": \"LaTeX\"")' "T1 the Calculator pages must read calc, latex, convert"
mutate editor-mode-gone build.json 's.replace("\"kind\": \"latex_editor\"", "\"kind\": \"expression\"")' "T1 tab latex must hold"
mutate page-icon-misspelt build.json 's.replace("\"icon\": \"edit\"\n          },\n          {\n            \"id\": \"convert\"", "\"icon\": \"edt\"\n          },\n          {\n            \"id\": \"convert\"")' "T1 the latex page icon"
mutate palette-emptied build.json 's.replace("\"palette\": [", "\"palettes\": [")' "T1 build.json::latex.palette"
mutate dispatch-dropped "$J/ui/ModeScreens.kt" 's.replace("\"latex_editor\" -> LatexEditorMode(mode)", "")' "T2 ModeScreen does not dispatch kind latex_editor"
mutate config-not-baked app/build.gradle 's.replace("def latexConfigB64 = b64(buildJson.latex)", "def latexConfigB64 = \"\"")' "T2 app/build.gradle does not bake"
mutate assets-not-wired app/build.gradle 's.replace("assets.srcDirs += [\x27../data/assets\x27]", "")' "T2 app/build.gradle does not add the data/assets srcDir"
mutate share-path-gone app/src/main/res/xml/file_paths.xml 's.replace("<cache-path name=\"latex\" path=\"latex/\" />", "")' "T2 file_paths.xml has no cache-path"
mutate katex-script-missing build.json 's.replace("latex/katex/katex.min.js", "latex/katex/katex.js")' "T3 renderer asset script"
mutate font-not-bundled "$L/katex/katex.min.css" 's.replace("url(fonts/KaTeX_Main-Regular.woff2)", "url(fonts/KaTeX_Main-Gone.woff2)")' "which is not bundled"
mutate viewer-drops-script "$L/viewer.html" 's.replace("<script src=\"katex/katex.min.js\"></script>", "")' "T3 viewer.html does not load the declared script"
mutate bridge-renamed "$L/viewer.js" 's.replace("window.CalcTex", "window.Other")' "T3 viewer.js does not use the declared bridge"
mutate viewer-online "$L/viewer.html" 's.replace("href=\"katex/katex.min.css\"", "href=\"https://cdn.example.org/katex.min.css\"")' "T4 viewer.html names an address"
mutate css-import "$L/katex/katex.min.css" 's + "@import url(x.css);"' "T4 katex.min.css names an address"
mutate katex-fetches "$L/katex/katex.min.js" 's + "\nvar x = new XMLHttpRequest();"' "can load over the network"
mutate katex-phones-home "$L/katex/auto-render.min.js" 's + "\nvar u = \"https://evil.example/x\";"' "names address https://evil.example/x"
mutate network-not-blocked "$J/ui/LatexScreens.kt" 's.replace("blockNetworkLoads = true", "blockNetworkLoads = false")' "T4 LatexScreens.kt does not set blockNetworkLoads"
mutate kotlin-opens-url "$J/Latex.kt" 's + "\nprivate val u = java.net.URL(\"x\")\n"' "T4 Latex.kt spells an address"
mutate catalog-drifts "$L/formulas.json" 's.replace("\"id\": \"the-pythagorean-theorem\"", "\"id\": \"pythagoras\"")' "T5 gen-formulas.mjs --check failed"
mutate formula-dropped data/latex/100-formulas.md 's.replace("### 100. The Logistic Map", "### 100. The Logistic Map\n$$x$$\nx\n\n### 101. Extra")' "T5 gen-formulas.mjs"
mutate formula-does-not-render data/latex/100-formulas.md 's.replace("$$a^2 + b^2 = c^2$$", "$$a^2 + \\\\frac{b}{ = c^2$$")' "does not render"
mutate katex-notice-gone NOTICE.md 's.replace("| KaTeX 0.19.1", "| Something 0.19.1")' "T6 NOTICE.md has no KaTeX"
mutate licence-text-swapped "$L/katex/LICENSE" 's.replace("The MIT License (MIT)", "All rights reserved")' "T6 the vendored KaTeX LICENSE is not the MIT text"
mutate formula-in-kotlin "$J/ui/LatexScreens.kt" 's + "\nprivate val f = \"Euler identity\"\n"' "T7 LatexScreens.kt spells Euler"


# ── #1336: the Graph page and the plots ──
mutate graph-mode-gone build.json 's.replace("\"kind\": \"latex_graph\"", "\"kind\": \"expression\"")' "T1 tab latex must hold"
mutate strip-label-renamed build.json 's.replace("\"label\": \"Edit\",", "\"label\": \"Editor\",")' "T8 the strip must read Library | Edit | Graph"
mutate graph-icon-misspelt build.json 's.replace("\"icon\": \"chart\",\n        \"short\": \"Graph\",\n        \"tab\": \"latex\"", "\"icon\": \"chrt\",\n        \"short\": \"Graph\",\n        \"tab\": \"latex\"")' "T8 the Graph mode icon"
mutate graph-config-gone build.json 's.replace("\"zoom_step\"", "\"zoom_stp\"")' "T8 build.json::latex.graph.zoom_step"
mutate graph-dispatch-dropped "$J/ui/ModeScreens.kt" 's.replace("\"latex_graph\" -> LatexGraphMode(mode)", "")' "T8 ModeScreen does not dispatch kind latex_graph"
mutate detail-graph-button-gone "$J/ui/LatexScreens.kt" 's.replace("state.openLatexGraph(", "state.noGraph(")' "T8 the formula detail does not open the Graph page"
mutate plot-string-missing app/src/main/res/values/strings.xml 's.replace("name=\"latex_plot_reset\"", "name=\"latex_plot_rst\"")' "T8 LatexScreens.kt uses R.string.latex_plot_reset"
mutate plot-script-unloaded "$L/plot/graph.html" 's.replace("<script src=\"model.js\"></script>", "")' "T9 graph.html does not load the declared script model.js"
mutate plot-bridge-renamed "$L/plot/graph.js" 's.replace("root.CalcPlot", "root.Other")' "T9 graph.js does not use the declared bridge"
mutate plot-online "$L/plot/graph.html" 's.replace("<script src=\"graph.js\"></script>", "<script src=\"https://cdn.example.org/graph.js\"></script>")' "T9 plot asset graph.html names an address"
mutate plot-uses-eval "$L/plot/expr.js" 's + "\nvar leak = eval(\"1\");"' "T9 plot asset expr.js reaches for eval"
mutate plot-view-online "$J/ui/LatexScreens.kt" 's.replace("blockNetworkLoads = true", "blockNetworkLoads = false", 1)' "T9 LatexScreens.kt must block network loads in BOTH WebViews"
mutate plots-not-own-code NOTICE.md 's.replace("own code, no third-party\nlibrary", "built on a library")' "T9 NOTICE.md does not say the plots are own code"
mutate kotlin-parser-reflects "$J/PlotExpr.kt" 's + "\nprivate val k = Class.forName(\"x\")\n"' "T9 PlotExpr.kt spells an address, reflection"
mutate parser-takes-any-identifier "$L/plot/expr.js" 's.replace("throw new ExprError(\"unknown identifier \" + name);", "return function () { return 0; };")' "test-plots.mjs failed"
mutate viewer-loses-probe "$L/plot/graph.js" 's.replace("report(\"probe\", \"2d\", text);", "")' "test-plots.mjs failed"
mutate sampler-breaks "$L/plot/model.js" 's.replace("var xs = lin(xr[0], xr[1], n)", "var xs = lin(xr[0], NaN, n)").replace("xs = lin(xr[0], xr[1], n);", "xs = lin(xr[0], NaN, n);")' "T10 gen-formulas.mjs --plots failed"
mutate plot-spec-missing data/latex/plots.json 's.replace("\"the-pythagorean-theorem\": {", "\"the-pythagorean-theoremX\": {")' "T10 gen-formulas.mjs --plots failed"
mutate plot-default-out-of-range data/latex/plots.json 's.replace("[\"Leg a\",3,0.5,10,0.1]", "[\"Leg a\",30,0.5,10,0.1]")' "outside"
mutate plot-expression-forbidden data/latex/plots.json 's.replace("sqrt(a^2+b^2)", "eval(a)", 1)' "unknown function eval"
mutate plot-caption-dropped data/latex/plots.json 's.replace("\"caption\": \"Hypotenuse c", "\"caption\": \"\", \"x\": \"Hypotenuse c")' "needs a one-line caption"
mutate owner-markdown-edited data/latex/100-formulas.md 's.replace("a^2 + b^2 = c^2", "a^2 + b^2 = c^3")' "T10 the owner's 100-formulas.md was changed"
mutate pi-category-lost data/latex/pi-formulas.md 's.replace("## 10. \u03c0 (Pi)", "## 10. Other")' "the last category must be pi"
mutate pi-entry-unplotted data/latex/pi-formulas.md 's.replace("{#pi-madhava-series}", "{#pi-madhava}")' "pi-madhava"
mutate catalog-plot-drifts "$L/formulas.json" 's.replace("Hypotenuse c =", "Hypotenuse d =")' "T5 gen-formulas.mjs --check failed"

# the formula-dropped mutation also needs the generator to see 100 -> 101: it must fail on the count, covered above.
if [ "$FAILURES" -eq 0 ]; then echo "== RESULT: all latex checks green, every mutation proven red =="; exit 0; fi
echo "== RESULT: $FAILURES failure(s) =="; exit 1
