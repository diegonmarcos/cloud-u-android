#!/usr/bin/env bash
# Tester (#1331): Cloud > Apps > Lnktree (was "Projects W") is Omniverse | FinTerm | CAD Sim2Real, each tile an in-app
# page declared as data, and every entry on those pages is honest: a target read from a DECLARED source, or "planned" and greyed.
#
#   T1  the row is titled "Lnktree" (no "Projects W" row left); its tiles, separators aside, are exactly
#       Omniverse, FinTerm, CAD, Sim2Real, in that order, with catalog icons (libs:ui-icons) and targets that exist
#   T2  Omniverse lists Galaxy, Game Store, VR Store; CAD lists MCAD, BIM, SolWrk; Sim2Real lists SAP2000, MATLAB, Ansys
#   T3  FinTerm has six tabs in order FI, EQ, EX, NW, PM, MY-PM and each tab declares the owner's entries (FX carries its
#       description, PM carries the model portfolios 40/60 70/30 60/40 30/70 and the DM/EM family, MY-PM is planned)
#   T4  no URL is typed on a page: an entry names ONE source (linktree label, registry code, repo) and every source resolves
#       (the label is a single link of data/linktree.json, the registry code is a key of ui.app_urls.registry_sites and equals
#       the webapp of that node in projects-repos.json when that file is reachable)
#   T5  planned entries are greyed: marked planned, carry a note, name no source; the renderer greys an unresolved entry
#       and LnktreeTargets answers null for a planned one; an entry with a source is not planned
#   T6  the code side: Sections reads the entry fields, the renderer is the Compose kind=entry (no TextView), and the
#       resolver and the renderer hold no http(s) literal
#   M   every check above is proven red by a mutation of its own input (the checker is run on the broken copy and must object)
#
# Overrides for mutation runs: BUILD, LINKTREE, REGISTRY, PANELS, SECTIONS, TARGETS, ICONS.
# Usage: ./test-lnktree-row.sh   (static only, no network, no device)
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
L="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher"
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 required" >&2; exit 2; }

python3 - "${BUILD:-$APP/build.json}" "${LINKTREE:-$APP/data/linktree.json}" "${REGISTRY:-}" \
    "${PANELS:-$L/AggregatorPanels.kt}" "${SECTIONS:-$L/Sections.kt}" "${TARGETS:-$L/LnktreeTargets.kt}" \
    "${ICONS:-$ROOT/ab_cloud-libs-shared/libs/ui-icons/src/main/kotlin/com/diegonmarcos/superapp/uiicons/UiIcons.kt}" <<'PY'
import copy, json, os, re, sys

build_p, lt_p, reg_p, panels_p, sections_p, targets_p, icons_p = sys.argv[1:]
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def code(text):
    t = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(re.sub(r"(?<![:\"])//.*$", "", l) for l in t.splitlines())

if not reg_p:
    for c in ("/home/user/git-repos-master/0_registry/projects-repos.json",
              os.path.join(os.path.dirname(build_p), "../../_git-repos-master/0_registry/projects-repos.json")):
        if os.path.isfile(c): reg_p = c; break
res_dir = os.path.join(os.path.dirname(build_p), "app/src/main/res/drawable")
SOURCES = ("site", "registry", "repo")
TABS = [("fi", "FI"), ("eq", "EQ"), ("ex", "EX"), ("nw", "NW"), ("pm", "PM"), ("my-portfolio", "MY-PM")]
PAGES = {  # page -> (where its stack lives, the entry titles in order)
    "omniverse": ["Galaxy", "Game Store", "VR Store"],
    "cad": ["MCAD", "BIM", "SolWrk"],
    "sim2real": ["SAP2000", "MATLAB", "Ansys"],
}
FT = {
    "fi": ["Yield Curves", "Forward Rates", "World Interest Rate Probabilities", "World Bonds"],
    "eq": ["World Equity Indices"],
    "ex": ["FX", "Onshore USD Rates"],
    "nw": ["News"],
    "pm": ["Portfolio & Risk Analytics", "40/60", "70/30", "60/40", "30/70", "70/30 DM/EM"],  # these must appear, in this order
    "my-portfolio": ["Personal portfolio"],
}
FX_DESC = "Comprehensive real-time currency pricing, matrix crosses, spot rates, and forwards"

def sec(bj, sid): return next((s for s in bj["ui"]["sections"] if s.get("id") == sid), {})
def entries(stack): return [p for p in (stack or []) if p.get("kind") == "entry"]
def all_entry_lists(bj):
    cloud, ft = sec(bj, "cloud"), sec(bj, "finterm")
    for pid in PAGES: yield "cloud/" + pid, cloud.get("stack_" + pid, [])
    for tid, _ in TABS: yield "finterm/" + tid, ft.get("stack_" + tid, [])

def problems(bj, lt, reg, panels, sections, targets, icons):
    """Every violated rule, each prefixed with its test tag."""
    out = []
    cloud, ft = sec(bj, "cloud"), sec(bj, "finterm")
    # T1
    groups = cloud.get("tile_groups", [])
    titles = [g.get("title") for g in groups]
    if "Lnktree" not in titles: out.append("T1 no tile group titled 'Lnktree'")
    if "Projects W" in titles: out.append("T1 the row is still called 'Projects W'")
    row = next((g for g in groups if g.get("title") == "Lnktree"), {"tiles": []})
    tiles = [t for t in row["tiles"] if not t.get("separator")]
    if [t.get("label") for t in tiles] != ["Omniverse", "FinTerm", "CAD", "Sim2Real"]:
        out.append("T1 tiles are %r, want Omniverse FinTerm CAD Sim2Real" % [t.get("label") for t in tiles])
    want_t = {"Omniverse": "page:cloud/omniverse", "FinTerm": "section:finterm", "CAD": "page:cloud/cad", "Sim2Real": "page:cloud/sim2real"}
    for t in tiles:
        if t.get("target") != want_t.get(t.get("label")): out.append("T1 tile %s targets %r" % (t.get("label"), t.get("target")))
    catalog = set(re.findall(r'"([a-z0-9_]+)"\s+to\s', icons))
    # nav declarations (the FinTerm section and its tabs, the three hidden pages) take catalog names; a Cloud tile takes a drawable
    for t in [p for p in cloud.get("pages", []) if p.get("id") in PAGES] + ft.get("pages", []) + [ft]:
        if t.get("icon") not in catalog: out.append("T1 icon %r of %s is not in the libs:ui-icons catalog" % (t.get("icon"), t.get("id")))
    for t in tiles:
        if not os.path.isfile(os.path.join(res_dir, t.get("icon", "") + ".xml")): out.append("T1 tile icon %r of %s has no drawable" % (t.get("icon"), t.get("id")))
    icons_seen = [t.get("icon") for g in groups for t in g.get("tiles", []) if t.get("icon")]
    for t in tiles:
        if icons_seen.count(t.get("icon")) > 1: out.append("T1 tile icon %r of %s is shared with another Cloud tile" % (t.get("icon"), t.get("id")))
    for t in row["tiles"]:
        if t.get("separator") and t.get("target"): out.append("T1 a separator carries a target")
    # T2
    for pid, want in PAGES.items():
        page = next((p for p in cloud.get("pages", []) if p.get("id") == pid), None)
        if not page or not page.get("facet"): out.append("T2 cloud has no facet page %s" % pid)
        got = [e.get("title") for e in entries(cloud.get("stack_" + pid))]
        if got != want: out.append("T2 page %s lists %r, want %r" % (pid, got, want))
    # T3
    if [p.get("id") for p in ft.get("pages", [])] != [t for t, _ in TABS] or [p.get("label") for p in ft.get("pages", [])] != [l for _, l in TABS]:
        out.append("T3 FinTerm tabs are %r, want FI EQ EX NW PM MY-PM" % [p.get("label") for p in ft.get("pages", [])])
    if not ft.get("tabs"): out.append("T3 finterm is not a tabbed section")
    if ft.get("parent") != "cloud": out.append("T3 finterm does not go back to cloud")
    for tid, want in FT.items():
        got = [e.get("title") for e in entries(ft.get("stack_" + tid))]
        if tid == "pm":
            if [g for g in got if g in want] != want: out.append("T3 PM lacks %r in order (has %r)" % (want, got))
            for need in ("40/60", "70/30", "60/40", "30/70", "70/30 DM/EM"):
                if need not in got: out.append("T3 PM lacks model portfolio %s" % need)
            if not [g for g in got if g.endswith(" DM/EM")]: out.append("T3 PM has no DM/EM portfolios")
        elif got != want: out.append("T3 tab %s lists %r, want %r" % (tid, got, want))
    fx = next((e for e in entries(ft.get("stack_ex")) if e.get("title") == "FX"), {})
    if fx.get("subtitle") != FX_DESC: out.append("T3 FX lacks the owner's description")
    my = entries(ft.get("stack_my-portfolio"))
    if not my or not all(e.get("planned") for e in my): out.append("T3 MY-PM must be a planned entry (no editable-list renderer exists)")
    # T4 / T5
    links = {}
    def walk(o):
        if isinstance(o, dict):
            if "label" in o and "url" in o: links.setdefault(o["label"], set()).add(o["url"])
            for v in o.values(): walk(v)
        elif isinstance(o, list):
            for v in o: walk(v)
    walk(lt)
    cfg = bj["ui"].get("app_urls", {})
    for where, stack in all_entry_lists(bj):
        titles_seen = []
        for e in stack:
            if e.get("kind") != "entry": continue
            name = "%s %s" % (where, e.get("title"))
            titles_seen.append(e.get("title"))
            for k, v in e.items():
                if isinstance(v, str) and k != "_doc" and re.search(r"https?://", v): out.append("T4 %s: a URL is typed in field %s" % (name, k))
            srcs = [k for k in SOURCES if e.get(k)]
            if len(srcs) > 1: out.append("T4 %s names several sources %r" % (name, srcs))
            if not e.get("subtitle"): out.append("T4 %s has no one-line description" % name)
            if e.get("planned"):
                if srcs: out.append("T5 %s is planned but names the source %r" % (name, srcs))
                if not e.get("note"): out.append("T5 %s is planned without a short note" % name)
            else:
                if not srcs: out.append("T5 %s has no source and is not marked planned" % name)
                if e.get("note"): out.append("T5 %s has a note but is not planned" % name)
            if e.get("site") and len(links.get(e["site"], set())) != 1:
                out.append("T4 %s: linktree.json has no single link labelled %r" % (name, e["site"]))
            if e.get("registry"):
                url = (cfg.get("registry_sites") or {}).get(e["registry"])
                if not url: out.append("T4 %s: registry code %s is not in ui.app_urls.registry_sites" % (name, e["registry"]))
            if e.get("repo") and not str(cfg.get("repo_base", "")).startswith("https://"): out.append("T4 %s: ui.app_urls.repo_base missing" % name)
        if len(titles_seen) != len(set(titles_seen)): out.append("T4 %s: duplicate entry titles" % where)
    if reg:
        node = {}
        def find(o, code_):
            if isinstance(o, dict):
                if o.get("code") == code_: node.update(o)
                for v in o.values(): find(v, code_)
            elif isinstance(o, list):
                for v in o: find(v, code_)
        for code_, url in (cfg.get("registry_sites") or {}).items():
            node.clear(); find(reg, code_)
            if node.get("webapp") != url: out.append("T4 registry_sites[%s]=%r but projects-repos.json says %r" % (code_, url, node.get("webapp")))
    # T5 renderer
    pc, tc, sc = code(panels), code(targets), code(sections)
    m = re.search(r"private fun PageEntry\(.*?\n}\n", pc, re.S)
    body = m.group(0) if m else ""
    if not m: out.append("T5 AggregatorPanels has no PageEntry composable")
    elif not re.search(r"alpha\(\s*if\s*\(\s*url\s*!=\s*null\s*\)\s*1f\s*else\s*0\.\d+f\s*\)", body): out.append("T5 PageEntry does not grey an unresolved entry")
    elif not re.search(r"clickable[^\n]*onOpen\(url\)", body) or "url != null" not in body: out.append("T5 PageEntry may open a planned entry")
    if "panel.planned" not in tc or not re.search(r"if \(panel\.planned\) return null", tc): out.append("T5 LnktreeTargets resolves a planned entry")
    # T6
    for f in ("site", "registry", "repo", "path", "planned", "note"):
        if not re.search(r'\b%s\s*=\s*p\.opt(String|Boolean)\("%s"' % (f, f), sc): out.append("T6 Sections does not read the entry field %s" % f)
    if 'panel.kind == "entry"' not in pc: out.append("T6 PagePanel does not route kind=entry")
    for name, c in (("AggregatorPanels PageEntry", body), ("LnktreeTargets", tc)):
        if re.search(r"https?://", c): out.append("T6 %s holds a URL literal" % name)
    if "TextView(" in body or "TextView(" in tc: out.append("T6 a TextView( was added (compose ratchet)")
    return out

bj, lt = json.loads(read(build_p)), json.loads(read(lt_p))
reg = json.loads(read(reg_p)) if reg_p and os.path.isfile(reg_p) else None
panels, sections, targets, icons = read(panels_p), read(sections_p), read(targets_p), read(icons_p)

passed = failed = 0
def check(cond, msg):
    global passed, failed
    if cond: passed += 1; print("  PASS: " + msg)
    else:    failed += 1; print("  FAIL: " + msg)

print("== T1-T6: the Lnktree row, its pages and their entries ==")
real = problems(bj, lt, reg, panels, sections, targets, icons)
for tag in ("T1", "T2", "T3", "T4", "T5", "T6"):
    mine = [p for p in real if p.startswith(tag + " ")]
    check(not mine, "%s holds%s" % (tag, "" if not mine else ": " + "; ".join(mine)))
print("  (projects-repos.json %s)" % ("checked: " + reg_p if reg else "not reachable here: registry_sites not cross-checked"))
if not reg: check(True, "T4 registry cross-check skipped, reachable only on a machine with _git-repos-master")

print("== M: every check goes red on a broken input ==")
def mut(name, tag, fn):
    b, l, r = copy.deepcopy(bj), copy.deepcopy(lt), copy.deepcopy(reg)
    args = {"p": panels, "s": sections, "t": targets, "i": icons}
    fn(b, l, r, args)
    got = problems(b, l, r, args["p"], args["s"], args["t"], args["i"])
    check(any(p.startswith(tag + " ") for p in got), "mutation '%s' is caught by %s" % (name, tag))
def row(b): return next(g for g in sec(b, "cloud")["tile_groups"] if g.get("title") == "Lnktree")
def ent(b, where, title):
    s, k = where.split("/"); return next(e for e in sec(b, s)["stack_" + k] if e.get("title") == title)
def rename(b, l, r, a): row(b)["title"] = "Projects W"
mut("row renamed back to Projects W", "T1", rename)
def swap(b, l, r, a): t = row(b)["tiles"]; t[0], t[2] = t[2], t[0]
mut("Omniverse and FinTerm swapped", "T1", swap)
def fifth(b, l, r, a): row(b)["tiles"].append({"id": "x", "label": "Extra", "icon": "ic_robot", "target": "page:cloud/cad"})
mut("a fifth tile", "T1", fifth)
def baticon(b, l, r, a): row(b)["tiles"][0]["icon"] = "ic_not_in_catalog"
mut("tile icon with no drawable", "T1", baticon)
def dupicon(b, l, r, a): row(b)["tiles"][0]["icon"] = "ic_robot"
mut("tile icon shared with another Cloud tile", "T1", dupicon)
def batnav(b, l, r, a): sec(b, "finterm")["icon"] = "ic_not_in_catalog"
mut("FinTerm icon outside the catalog", "T1", batnav)
def dropvr(b, l, r, a): sec(b, "cloud")["stack_omniverse"] = [e for e in sec(b, "cloud")["stack_omniverse"] if e["title"] != "VR Store"]
mut("VR Store dropped", "T2", dropvr)
def renmatlab(b, l, r, a): ent(b, "cloud/sim2real", "MATLAB")["title"] = "Matlab"
mut("MATLAB renamed", "T2", renmatlab)
def droptab(b, l, r, a): sec(b, "finterm")["pages"] = [p for p in sec(b, "finterm")["pages"] if p["id"] != "nw"]
mut("NW tab dropped", "T3", droptab)
def swaptab(b, l, r, a): p = sec(b, "finterm")["pages"]; p[0], p[1] = p[1], p[0]
mut("FI and EQ tabs swapped", "T3", swaptab)
def dropport(b, l, r, a): sec(b, "finterm")["stack_pm"] = [e for e in sec(b, "finterm")["stack_pm"] if e.get("title") != "60/40"]
mut("model portfolio 60/40 dropped", "T3", dropport)
def fxdesc(b, l, r, a): ent(b, "finterm/ex", "FX")["subtitle"] = "FX"
mut("FX description lost", "T3", fxdesc)
def mypm(b, l, r, a): e = ent(b, "finterm/my-portfolio", "Personal portfolio"); e.pop("planned"); e.pop("note"); e["site"] = "Fin Terminal"
mut("MY-PM pretends to have a target", "T3", mypm)
def typed(b, l, r, a): ent(b, "cloud/omniverse", "Galaxy")["url"] = "https://example.org/galaxy/"
mut("a URL typed on an entry", "T4", typed)
def badsite(b, l, r, a): ent(b, "finterm/nw", "News")["site"] = "No Such Link"
mut("site label that names no link", "T4", badsite)
def badreg(b, l, r, a): ent(b, "cloud/omniverse", "Galaxy")["registry"] = "9.9.9"
mut("registry code that is not declared", "T4", badreg)
def drift(b, l, r, a): b["ui"]["app_urls"]["registry_sites"]["2.1.1"] = "https://example.org/other/"
if reg: mut("registry_sites drifting from projects-repos.json", "T4", drift)
def twosrc(b, l, r, a): ent(b, "finterm/fi", "Yield Curves")["registry"] = "2.1.1"
mut("two sources on one entry", "T4", twosrc)
def nonote(b, l, r, a): ent(b, "cloud/omniverse", "Game Store").pop("note")
mut("planned entry without a note", "T5", nonote)
def plannedsrc(b, l, r, a): ent(b, "cloud/omniverse", "VR Store")["site"] = "Fin Terminal"
mut("planned entry that names a source", "T5", plannedsrc)
def unplanned(b, l, r, a): ent(b, "cloud/cad", "SolWrk").pop("planned")
mut("entry with neither source nor planned", "T5", unplanned)
def nogrey(b, l, r, a): a["p"] = re.sub(r"\.alpha\(if \(url != null\) 1f else 0\.45f\)", "", a["p"])
mut("renderer no longer greys", "T5", nogrey)
def plannedopens(b, l, r, a): a["t"] = a["t"].replace("if (panel.planned) return null", "")
mut("resolver opens planned entries", "T5", plannedopens)
def noread(b, l, r, a): a["s"] = a["s"].replace('planned         = p.optBoolean("planned", false),', "")
mut("Sections stops reading planned", "T6", noread)
def lit(b, l, r, a): a["t"] += '\nval x = "https://example.org/"\n'
mut("URL literal in the resolver", "T6", lit)
def tv(b, l, r, a): a["p"] = a["p"].replace("private fun PageEntry(", "private fun PageEntry(").replace("Text(if (url != null)", "TextView(if (url != null)")
mut("TextView( in the renderer", "T6", tv)

print("-- %d passed, %d failed" % (passed, failed))
sys.exit(1 if failed else 0)
PY
