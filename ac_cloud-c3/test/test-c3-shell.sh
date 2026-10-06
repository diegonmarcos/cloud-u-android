#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #648 — cloud-c3's chrome is DECLARED, every declared page is REAL, and   ║
# ║ the shell reads the cutout inset instead of consuming it                 ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. This app was EXTRACTED from aa_cloud-superapp, and an extraction
# fails in three ways that all look fine from outside:
#
#   (1) a surface exists with nothing linking to it — invisible, working, dead;
#   (2) a link points at a surface that no longer exists — a tap that does nothing;
#   (3) THE ONE THAT ACTUALLY HAPPENED: the tab exists, the link works, the ship is
#       green, and the page is EMPTY. The first cut of this app shipped a five-tab
#       APK whose two content tabs rendered a "not built yet" placeholder, and the
#       green ship was reported as done. A guard for (1) and (2) cannot see that.
#
# So T6 is the centre of this file: a page the declaration lists must resolve to a
# real fragment, and no placeholder body may exist for one to fall back into.
#
#   T1  ONE tab declaration: exactly the five tabs, in declared order, Home in the
#       CENTRE, and ui.default_section naming one that exists.
#   T2  NO ORPHANS, BOTH DIRECTIONS: every declared tab has a branch in the shell's
#       dispatch and every branch answers a declared id.
#   T3  the declaration is the ONLY order: no Kotlin file holds a second list of tab
#       ids, so reordering build.json reorders the nav and nothing else.
#   T4  every declared icon is a real DRAWABLE and every drawable is declared. The
#       shell is Views and resolves icons by name with getIdentifier, so a name with
#       no file resolves to 0 and draws a blank square — silently.
#   T5  the Apps tab LAUNCHES the three real sibling APKs: each package equals that
#       sibling's own application_id, each id IS its fleet name, no tile carries a
#       display `label` (#351, the copy #224 reverted), and each package is declared
#       in AndroidManifest <queries> or API 30+ reports an installed app as absent.
#   T6  NO PLACEHOLDER IN A SHIPPED TAB. Every declared page of every content tab
#       resolves to a real fragment in that tab's dispatch AND every dispatched page
#       id is declared; no "not built"/"coming soon" body exists anywhere; and the
#       one stated error fragment is reachable only from an UNDECLARED id, never
#       from a declared one. There is also no `implemented:` style flag in the
#       declaration — an escape hatch is how the empty APK shipped.
#   T7  the shell HOSTS FRAGMENTS and reuses libs:bottomnav's own View contract
#       (BottomNavIslandView), and does NOT consume cloud-mail's bottomNavItems
#       table. The pages are the SuperApp's Fragments, moved, not reauthored.
#   T8  the TOP-OVERFLOW fix is the declared one (#407/#477): the shell reads
#       systemBars UNION displayCutout in a NON-consuming listener and pads the
#       content; it returns the insets untouched, and holds no hardcoded top margin
#       or padding. Consuming would starve the island below of its own inset, which
#       is exactly how #477's bug worked.
#   M   mutation-proof: every check above is shown able to go RED.
#
# OWN-SOURCE ONLY. python3, jq and grep.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-c3"
BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/cloudc3"
MAIN="$SRC/MainActivity.kt"
NAVKT="$SRC/C3BottomNav.kt"
TABS="$SRC/pages/TabFragments.kt"
DRAWABLE="$APP/app/src/main/res/drawable"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"
SHELL_XML="$APP/app/src/main/res/layout/activity_main.xml"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$BJ" "$MAIN" "$NAVKT" "$TABS" "$MANIFEST" "$SHELL_XML"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done
[ -d "$DRAWABLE" ] || { echo "ERROR missing $DRAWABLE — unrun, not passing"; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "ERROR jq absent — a verdict from a missing tool is not a verdict"; exit 1; }

# ── the checks as functions of their inputs, so the mutation block runs them on copies ──

t1() {
    python3 - "$1" <<'PYTHON'
import json, sys
ui = json.load(open(sys.argv[1], encoding="utf-8"))["ui"]
secs = {s.get("id"): s for s in (ui.get("sections") or [])}
ids = ui.get("bottom_nav") or []
tabs = [secs.get(i) or {"id": i} for i in ids]
labels = [t.get("label") for t in tabs]
want = ["Topology", "Observ", "Home", "Apps", "Configs"]
bad = []
if len(ids) != 5: bad.append("ui.bottom_nav declares %d tabs, not five: %s" % (len(ids), ids))
if labels != want: bad.append("ui.bottom_nav labels are %s, not %s in that order" % (labels, want))
for i in ids:
    if i not in secs: bad.append("ui.bottom_nav names %r, which is no ui.sections id" % i)
if len(ids) == 5 and ids[2] != "home":
    bad.append("the CENTRE tab is %r, not home — every fleet five-tab shell puts Home centre" % ids[2])
if ui.get("default_section") not in ids:
    bad.append("ui.default_section %r is not a declared id %s" % (ui.get("default_section"), ids))
if len(set(ids)) != len(ids): bad.append("two tabs share an id: %s" % ids)
for t in tabs:
    for k in ("id", "label", "icon"):
        if not t.get(k): bad.append("tab %r declares no %s" % (t.get("id"), k))
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t2 <build.json> <MainActivity.kt> : tab declaration <-> shell dispatch, both ways
t2() {
    python3 - "$1" "$2" <<'PYTHON'
import json, re, sys
declared = list(json.load(open(sys.argv[1], encoding="utf-8"))["ui"].get("bottom_nav") or [])
text = open(sys.argv[2], encoding="utf-8").read()
m = re.search(r"val fragment = when \(tabId\) \{(.*?)\n        \}", text, re.S)
if not m:
    print("    no `when (tabId)` in MainActivity — there is no single dispatch to diff"); sys.exit(1)
branches = re.findall(r'^\s*"([^"]+)"\s*->', m.group(1), re.M)
bad = []
for tab in declared:
    if tab not in branches:
        bad.append("tab %r is DECLARED but the shell dispatches no fragment for it — "
                   "an orphaned declaration, a tab that opens nothing" % tab)
for br in branches:
    if br not in declared:
        bad.append("the shell dispatches %r but ui.bottom_nav does not declare it — "
                   "an orphaned SCREEN, reachable from nothing" % br)
if len(set(branches)) != len(branches):
    bad.append("the shell has a duplicate branch: %s" % branches)
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t3 <build.json> <src dir> : no SECOND list of tab ids
t3() {
    python3 - "$1" "$2" <<'PYTHON'
import json, os, re, sys
declared = list(json.load(open(sys.argv[1], encoding="utf-8"))["ui"].get("bottom_nav") or [])
bad = []
for dirpath, _d, files in os.walk(sys.argv[2]):
    for f in files:
        if not f.endswith(".kt"): continue
        p = os.path.join(dirpath, f)
        text = open(p, encoding="utf-8").read()
        code = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        code = re.sub(r"//[^\n]*", "", code)
        for lit in re.findall(r"(?:listOf|arrayOf|setOf)\s*\(([^)]*)\)", code, re.S):
            found = [t for t in declared if '"%s"' % t in lit]
            if len(found) >= 2:
                bad.append("%s holds a second list of tab ids %s — reordering ui.bottom_nav would no "
                           "longer reorder the nav" % (os.path.relpath(p, sys.argv[2]), found))
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t4 <build.json> <drawable dir> <src dir> : declared icon <-> drawable file, BOTH directions.
# #648 "declared" now also covers the carried c3 section (ui.sections — every `icon` value
# anywhere inside it, the stacks' tiles included) and the drawables Kotlin names directly
# (R.drawable.X, and the quoted "ic_*" names iconResFor-style lookups resolve at runtime) —
# the carried pages resolve icons both ways, and either one missing draws a BLANK square.
t4() {
    python3 - "$1" "$2" "$3" <<'PYTHON'
import json, os, re, sys
ui = json.load(open(sys.argv[1], encoding="utf-8"))["ui"]
declared = set()
declared.update(a.get("icon") for a in (ui.get("external_apps") or []))
if ui.get("icon_default"): declared.add(ui["icon_default"])
def walk_icons(node):
    if isinstance(node, dict):
        for k, v in node.items():
            if k in ("icon", "icon_apps", "icon_admin") and isinstance(v, str):
                declared.add(v)
            else:
                walk_icons(v)
    elif isinstance(node, list):
        for item in node:
            walk_icons(item)
walk_icons(ui.get("sections") or [])
walk_icons(ui.get("carried_c3") or {})
# Kotlin-side references: compile-time R.drawable ids and runtime getIdentifier names.
for dirpath, _d, files_ in os.walk(sys.argv[3]):
    for f in files_:
        if not f.endswith(".kt"): continue
        text = open(os.path.join(dirpath, f), encoding="utf-8").read()
        code = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        code = re.sub(r"//[^\n]*", "", code)
        declared.update(re.findall(r"R\.drawable\.([a-z0-9_]+)", code))
        declared.update(re.findall(r'"(ic_[a-z0-9_]+)"', code))
declared = {d for d in declared if d}
files = {f[:-4] for f in os.listdir(sys.argv[2]) if f.endswith(".xml")}
launcher = {"ic_launcher_background", "ic_launcher_foreground"}
bad = []
for name in sorted(declared - files):
    bad.append("declared icon %r has no res/drawable/%s.xml — the shell resolves icons by "
               "name with getIdentifier, so this resolves to 0 and draws a BLANK square" % (name, name))
for name in sorted(files - declared - launcher):
    bad.append("res/drawable/%s.xml is declared by nothing — a resource no declaration names "
               "is dead weight moved for no reason" % name)
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t5 <build.json> <manifest> <repo root> : the Apps tiles are the REAL siblings
t5() {
    python3 - "$1" "$2" "$3" <<'PYTHON'
import json, os, re, sys
ui = json.load(open(sys.argv[1], encoding="utf-8"))["ui"]
manifest = open(sys.argv[2], encoding="utf-8").read()
root = sys.argv[3]
tiles = ui.get("external_apps") or []
# The REQUIRED Apps-tab membership: the three C3 siblings and the cloud-c3-webserver
# sub-app, which launches from here and not from the SuperApp's Configs grid. Each
# app's directory is DERIVED (ac_<id>), never a second table.
want = {i: "ac_" + i for i in ("c3-watchdog", "c3-morpheus", "c3-watchtower",
                               "cloud-c3-webserver")}
ids = [t.get("id") for t in tiles]
bad = []
if sorted(ids) != sorted(want):
    bad.append("the Apps tab declares %s, not the required apps %s" % (ids, sorted(want)))
queried = set(re.findall(r'<package android:name="([^"]+)"', manifest))
for t in tiles:
    tid, pkg = t.get("id"), t.get("package")
    if "label" in t:
        bad.append("Apps tile %r carries a label %r beside a fleet package — an application has "
                   "ONE name (#351) and the id IS it; #224 reverted exactly this" % (tid, t.get("label")))
    sib = want.get(tid)
    if sib is None: continue
    bj = os.path.join(root, sib, "build.json")
    if not os.path.isfile(bj):
        bad.append("%s names sibling %s, which has no build.json" % (tid, sib)); continue
    sj = json.load(open(bj, encoding="utf-8"))
    real = (sj.get("android") or {}).get("application_id")
    if pkg != real:
        bad.append("%s declares package %r but %s's application_id is %r — a wrong package "
                   "compiles, ships, installs and leaves a tab that opens nothing" % (tid, pkg, sib, real))
    if sj.get("name") != tid:
        bad.append("Apps tile id %r is not %s::name %r" % (tid, sib, sj.get("name")))
    if pkg not in queried:
        bad.append("%s's package %r is not in AndroidManifest <queries> — on API 30+ an unqueried "
                   "package is INVISIBLE, so an installed app would read 'Not installed'" % (tid, pkg))
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t6 <build.json> <TabFragments.kt> <src dir> : NO PLACEHOLDER IN A SHIPPED TAB
t6() {
    python3 - "$1" "$2" "$3" <<'PYTHON'
import json, os, re, sys
ui = json.load(open(sys.argv[1], encoding="utf-8"))["ui"]
tabsrc = open(sys.argv[2], encoding="utf-8").read()
srcdir = sys.argv[3]
bad = []

# (a) each paged tab's DECLARED pages must each resolve to a real fragment, and every
#     dispatched page id must be declared. Read out of each pageFragment() when-block.
for tab, cls in (("topology", "TopologyFragment"), ("observ", "ObservFragment"),
                 ("configs", "ConfigsFragment")):
    sec = next((s for s in (ui.get("sections") or []) if s.get("id") == tab), {})
    declared = [p["id"] for p in (sec.get("pages") or [])]
    m = re.search(r"class %s : PagedFragment\(\).*?when \(pageId\) \{(.*?)\n    \}" % cls,
                  tabsrc, re.S)
    if not m:
        bad.append("no pageFragment when-block found for %s — nothing to diff the %s pages against"
                   % (cls, tab))
        continue
    body = m.group(1)
    branches = re.findall(r'^\s*"([^"]+)"\s*->', body, re.M)
    if not declared:
        bad.append("ui.sections[%s] declares NO pages, so the %s tab would open empty — a tab the nav "
                   "shows must have something in it" % (tab, tab))
    for pid in declared:
        if pid not in branches:
            bad.append("ui.sections[%s] declares page %r but %s resolves no fragment for it — the tab "
                       "would draw the stated-bug body, which is a PLACEHOLDER in a shipped tab"
                       % (tab, pid, cls))
    for br in branches:
        if br not in declared:
            bad.append("%s resolves page %r which ui.sections[%s] does not declare — an orphaned page"
                       % (cls, br, tab))

# (b) no placeholder body may EXIST for a declared page to fall back into.
banned = ("NotBuiltYet", "not built yet", "notBuiltYet", "ComingSoon", "coming soon",
          "TODO_PAGE", "Placeholder")
for dirpath, _d, files in os.walk(srcdir):
    for f in files:
        if not f.endswith(".kt"): continue
        p = os.path.join(dirpath, f)
        text = open(p, encoding="utf-8").read()
        code = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        code = re.sub(r"//[^\n]*", "", code)
        for b in banned:
            if b in code:
                bad.append("%s contains %r — a not-built body is what let an empty APK ship "
                           "green; a page is DECLARED when it is implemented and not before"
                           % (os.path.relpath(p, srcdir), b))

# (c) no escape-hatch flag in the declaration: `implemented`, `stub`, `wip`, `placeholder`
for tab in ("topology", "observ", "configs"):
    sec = next((s for s in (ui.get("sections") or []) if s.get("id") == tab), {})
    for p in (sec.get("pages") or []):
        for k in p:
            if k.lower() in ("implemented", "stub", "wip", "placeholder", "not_built", "built"):
                bad.append("ui.sections[%s] page %r declares %r — a flag that excuses an empty page is "
                           "how the empty APK shipped; there is no such flag" % (tab, p.get("id"), k))
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t7 <MainActivity.kt> <C3BottomNav.kt> <src dir> : fragment host, shared island reused
t7() {
    python3 - "$1" "$2" "$3" <<'PYTHON'
import os, re, sys
main = open(sys.argv[1], encoding="utf-8").read()
nav = open(sys.argv[2], encoding="utf-8").read()
def code(t):
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    return re.sub(r"//[^\n]*", "", t)
mc, nc = code(main), code(nav)
bad = []
if "AppCompatActivity" not in mc:
    bad.append("MainActivity is not an AppCompatActivity, so it cannot host the SuperApp's Fragments")
if "supportFragmentManager" not in mc:
    bad.append("MainActivity never touches supportFragmentManager — it is not a fragment host, "
               "and the pages this app exists to show ARE fragments")
for sym in ("BottomNavIslandView", "BottomNavViewItem"):
    if sym not in nc:
        bad.append("C3BottomNav does not use the shared %s — libs:bottomnav's own View-side "
                   "contract is the supported path for an XML shell, not a workaround" % sym)
if re.search(r"\bbottomNavItems\b", nc) or re.search(r"\bbottomNavItems\b", mc):
    bad.append("the shell references bottomNavItems, which is cloud-MAIL's item table: this "
               "app's nav would be coupled to another shipping app's tabs")
# No @Composable anywhere: the shell is Views, and a Compose screen here would be the
# architecture choice that turned a spin-off into a rewrite.
for dirpath, _d, files in os.walk(sys.argv[3]):
    for f in files:
        if not f.endswith(".kt"): continue
        p = os.path.join(dirpath, f)
        if "@Composable" in code(open(p, encoding="utf-8").read()):
            bad.append("%s declares a @Composable — the pages are the SuperApp's Fragments and "
                       "the shell hosts them; a Compose screen means a page got reauthored"
                       % os.path.relpath(p, sys.argv[3]))
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t8 <MainActivity.kt> <activity_main.xml> : read the cutout inset, never consume it
t8() {
    python3 - "$1" "$2" <<'PYTHON'
import re, sys
main = open(sys.argv[1], encoding="utf-8").read()
xml = open(sys.argv[2], encoding="utf-8").read()
code = re.sub(r"/\*.*?\*/", "", main, flags=re.S)
code = re.sub(r"//[^\n]*", "", code)
bad = []
if "setOnApplyWindowInsetsListener" not in code:
    bad.append("the shell attaches no window-inset listener, so the top offset cannot be the "
               "real one (#407 replaced a tuned margin with a measured inset)")
if "displayCutout" not in code:
    bad.append("the inset listener never reads displayCutout — on this device the cutout is "
               "taller than the status bar, so systemBars alone leaves content clipped (#407)")
if "systemBars" not in code:
    bad.append("the inset listener never reads systemBars")
# NON-CONSUMING: the listener must hand the insets back. CONSUMED / .consumeSystemWindowInsets()
# / returning WindowInsetsCompat.CONSUMED starves the island of its own bottom inset (#477).
m = re.search(r"setOnApplyWindowInsetsListener\s*\([^)]*\)\s*\{(.*?)\n        \}", code, re.S)
if m:
    body = m.group(1)
    if "CONSUMED" in body or "consumeSystemWindowInsets" in body:
        bad.append("the inset listener CONSUMES the insets — that starves every view below it, "
                   "which is exactly how #477's bug worked; the island reads its own bottom inset")
    if not re.search(r"\n\s*insets\s*$", body):
        bad.append("the inset listener does not return the insets unchanged as its last "
                   "expression — anything else is a consumed or rebuilt inset")
# fitsSystemWindows in the shell layout would consume them before the listener ever runs.
if re.search(r'fitsSystemWindows\s*=\s*"true"', xml):
    bad.append("activity_main declares fitsSystemWindows=true, which CONSUMES the insets before "
               "the listener runs — the shell reads them instead")
# and no tuned top offset anywhere in the shell
for m2 in re.finditer(r'android:(?:layout_marginTop|paddingTop)\s*=\s*"(\d+)dp"', xml):
    bad.append("activity_main hardcodes a top offset of %sdp — the top offset is the measured "
               "inset, never a tuned number (#407)" % m2.group(1))
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

echo "── #648 cloud-c3: declared, implemented, and not drawing under the cutout ──"
t1 "$BJ"                              && pass "T1 ui.bottom_nav is the five declared tabs, in order, Home centre" || fail "T1 the tab declaration is wrong"
t2 "$BJ" "$MAIN"                      && pass "T2 declaration <-> shell dispatch agree BOTH ways: no orphan either side" || fail "T2 declaration and dispatch disagree"
t3 "$BJ" "$SRC"                       && pass "T3 no parallel list of tab ids" || fail "T3 a second list of tab ids exists"
t4 "$BJ" "$DRAWABLE" "$SRC"           && pass "T4 every declared icon is a real drawable, and every drawable is declared" || fail "T4 an icon would draw blank, or a drawable is dead"
t5 "$BJ" "$MANIFEST" "$ROOT"          && pass "T5 the Apps tab launches the REAL siblings + cloud-c3-webserver, each queried, none renamed" || fail "T5 an Apps tile cannot open what it names"
t6 "$BJ" "$TABS" "$SRC"               && pass "T6 NO PLACEHOLDER: every declared page resolves to a real fragment, and no not-built body exists" || fail "T6 a shipped tab can render a placeholder"
t7 "$MAIN" "$NAVKT" "$SRC"            && pass "T7 the shell HOSTS FRAGMENTS and reuses libs:bottomnav's View contract" || fail "T7 the shell is not a fragment host, or forks the nav"
t8 "$MAIN" "$SHELL_XML"               && pass "T8 the top inset is READ (systemBars u displayCutout) and never consumed" || fail "T8 the top-overflow fix is not the declared one"

# ── MUTATION PROOF ─────────────────────────────────────────────────────────
echo
echo "── mutation proof: each check must be able to go RED ──"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
MUT_FAIL=0
mutate() { # <name> <check-fn> <setup-fn>
    local name="$1" check="$2" setup="$3"
    rm -rf "$WORK/t"; mkdir -p "$WORK/t"
    cp -r "$APP" "$WORK/t/ac_cloud-c3"
    for sib in $(jq -r '.ui.external_apps[].id | "ac_" + .' "$APP/build.json"); do
        mkdir -p "$WORK/t/$sib"
        [ -f "$ROOT/$sib/build.json" ] && cp "$ROOT/$sib/build.json" "$WORK/t/$sib/build.json"
    done
    if ! "$check" "$WORK/t" >/dev/null 2>&1; then
        echo "  VOID  $name — the unmutated COPY is already red, so this mutation proves nothing"
        MUT_FAIL=$((MUT_FAIL + 1)); return
    fi
    if ! "$setup" "$WORK/t" >/dev/null 2>&1; then
        echo "  VOID  $name — the mutation itself failed to apply, so it proves nothing"
        MUT_FAIL=$((MUT_FAIL + 1)); return
    fi
    if "$check" "$WORK/t" >/dev/null 2>&1; then
        echo "  HOLLOW  $name — the check stayed GREEN under the mutation"
        MUT_FAIL=$((MUT_FAIL + 1))
    else
        echo "  RED     $name"
    fi
}

A="/ac_cloud-c3"
c_t1() { t1 "$1$A/build.json"; }
c_t2() { t2 "$1$A/build.json" "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/MainActivity.kt"; }
c_t3() { t3 "$1$A/build.json" "$1$A/app/src/main/java/com/diegonmarcos/cloudc3"; }
c_t4() { t4 "$1$A/build.json" "$1$A/app/src/main/res/drawable" "$1$A/app/src/main/java/com/diegonmarcos/cloudc3"; }
c_t5() { t5 "$1$A/build.json" "$1$A/app/src/main/AndroidManifest.xml" "$1"; }
c_t6() { t6 "$1$A/build.json" "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/pages/TabFragments.kt" "$1$A/app/src/main/java/com/diegonmarcos/cloudc3"; }
c_t7() { t7 "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/MainActivity.kt" "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/C3BottomNav.kt" "$1$A/app/src/main/java/com/diegonmarcos/cloudc3"; }
c_t8() { t8 "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/MainActivity.kt" "$1$A/app/src/main/res/layout/activity_main.xml"; }

jqset() { python3 - "$1" "$2" <<'PY'
import json,sys,collections
p,expr=sys.argv[1],sys.argv[2]
d=json.load(open(p),object_pairs_hook=collections.OrderedDict)
exec(expr,{"d":d,"collections":collections,"sec":lambda i:next(s for s in d["ui"]["sections"] if s["id"]==i)})
json.dump(d,open(p,"w"),indent=2)
PY
}

m_sixth_tab()   { jqset "$1$A/build.json" 'd["ui"]["sections"].append(collections.OrderedDict([("id","extra"),("label","Extra"),("icon","ic_home")])); d["ui"]["bottom_nav"].append("extra")'; }
m_home_offset() { jqset "$1$A/build.json" 't=d["ui"]["bottom_nav"]; t[2],t[4]=t[4],t[2]'; }
m_drop_branch() { python3 - "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/MainActivity.kt" <<'PY'
import re,sys
p=sys.argv[1]; s=open(p).read(); b=s
s=re.sub(r'\n\s*"configs" -> ConfigsFragment\(\)','',s)
assert s!=b, "branch not found"
open(p,"w").write(s)
PY
}
m_orphan_branch() { python3 - "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/MainActivity.kt" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read(); b=s
s=s.replace('"configs" -> ConfigsFragment()','"configs" -> ConfigsFragment()\n            "ghost" -> HomeFragment()')
assert s!=b
open(p,"w").write(s)
PY
}
m_parallel()    { python3 - "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/C3BottomNav.kt" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read(); b=s
s=s.replace("object C3BottomNav {",'object C3BottomNav {\n    val order = listOf("topology", "observ", "home", "apps", "configs")\n')
assert s!=b
open(p,"w").write(s)
PY
}
m_bad_icon()    { jqset "$1$A/build.json" 'd["ui"]["sections"][0]["icon"]="ic_does_not_exist"'; }
m_dead_drawable() { cp "$1$A/app/src/main/res/drawable/ic_home.xml" "$1$A/app/src/main/res/drawable/ic_unused_ghost.xml"; }
m_wrong_pkg()   { jqset "$1$A/build.json" 'd["ui"]["external_apps"][0]["package"]="com.diegonmarcos.c3watchdog"'; }
m_add_label()   { jqset "$1$A/build.json" 'd["ui"]["external_apps"][0]["label"]="Watchdog"'; }
m_drop_query()  { python3 - "$1$A/app/src/main/AndroidManifest.xml" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read(); b=s
s=s.replace('        <package android:name="com.diegonmarcos.watchdog" />\n','')
assert s!=b
open(p,"w").write(s)
PY
}
m_drop_websrv() { jqset "$1$A/build.json" 'd["ui"]["external_apps"]=[a for a in d["ui"]["external_apps"] if a["id"]!="cloud-c3-webserver"]'; }
m_websrv_pkg()  { jqset "$1$A/build.json" 'next(a for a in d["ui"]["external_apps"] if a["id"]=="cloud-c3-webserver")["package"]="com.diegonmarcos.webserver"'; }
m_websrv_noq()  { python3 - "$1$A/app/src/main/AndroidManifest.xml" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read(); b=s
s=s.replace('        <package android:name="com.diegonmarcos.cloudwebserver" />\n','')
assert s!=b
open(p,"w").write(s)
PY
}
# T6: the three shapes of "a shipped tab is empty"
m_declare_unbuilt() { jqset "$1$A/build.json" 'sec("observ")["pages"].append(collections.OrderedDict([("id","workflows"),("label","Workflows"),("icon","ic_p_c3_workflows")]))'; }
m_placeholder_body() { python3 - "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/pages/TabFragments.kt" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read(); b=s
s=s.replace('        "health" -> C3HealthFragment.newInstance(C3HealthFragment.SCOPE_ALL)',
            '        "health" -> NotBuiltYet()')
assert s!=b
open(p,"w").write(s)
PY
}
m_escape_flag() { jqset "$1$A/build.json" 'sec("observ")["pages"][0]["implemented"]=False'; }
m_empty_tab()   { jqset "$1$A/build.json" 'sec("observ")["pages"]=[]'; }
# T7 / T8
m_compose_screen() { printf 'package com.diegonmarcos.cloudc3.pages\nimport androidx.compose.runtime.Composable\n@Composable\nfun Reauthored() {}\n' > "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/pages/Reauthored.kt"; }
m_consume_insets() { python3 - "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/MainActivity.kt" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read(); b=s
s=s.replace("            insets\n        }","            WindowInsetsCompat.CONSUMED\n        }")
assert s!=b
open(p,"w").write(s)
PY
}
m_drop_cutout() { python3 - "$1$A/app/src/main/java/com/diegonmarcos/cloudc3/MainActivity.kt" <<'PY'
import re,sys
p=sys.argv[1]; s=open(p).read(); b=s
s=s.replace("            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())\n","")
s=re.sub(r"maxOf\(bars\.(\w+), cutout\.\w+\)", r"bars.\1", s)
assert s!=b
open(p,"w").write(s)
PY
}
m_hardcode_top() { python3 - "$1$A/app/src/main/res/layout/activity_main.xml" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read(); b=s
s=s.replace('android:orientation="vertical"','android:orientation="vertical"\n    android:paddingTop="36dp"',1)
assert s!=b
open(p,"w").write(s)
PY
}

mutate "a SIXTH tab appears in the declaration"           c_t1 m_sixth_tab
mutate "Home is moved off the centre position"            c_t1 m_home_offset
mutate "a declared tab loses its dispatch branch"         c_t2 m_drop_branch
mutate "a screen is dispatched that nothing declares"     c_t2 m_orphan_branch
mutate "a parallel list of tab ids is added"              c_t3 m_parallel
mutate "a tab declares an icon with no drawable"          c_t4 m_bad_icon
mutate "a drawable is added that nothing declares"        c_t4 m_dead_drawable
mutate "an Apps tile names a package no sibling has"      c_t5 m_wrong_pkg
mutate "an Apps tile regains a display label (#224)"      c_t5 m_add_label
mutate "a sibling package is dropped from <queries>"      c_t5 m_drop_query
mutate "cloud-c3-webserver is dropped from the Apps tab"  c_t5 m_drop_websrv
mutate "the webserver tile names the wrong package"       c_t5 m_websrv_pkg
mutate "the webserver package is dropped from <queries>"  c_t5 m_websrv_noq
mutate "an UNBUILT page is declared (the empty tab)"      c_t6 m_declare_unbuilt
mutate "a declared page resolves to a not-built body"     c_t6 m_placeholder_body
mutate "an 'implemented:false' escape hatch is added"     c_t6 m_escape_flag
mutate "a content tab is declared with zero pages"        c_t6 m_empty_tab
mutate "a page is reauthored as a Composable"             c_t7 m_compose_screen
mutate "the inset listener CONSUMES the insets (#477)"    c_t8 m_consume_insets
mutate "the cutout is dropped from the inset read (#407)" c_t8 m_drop_cutout
mutate "a tuned top padding is hardcoded in the shell"    c_t8 m_hardcode_top

echo
if [ "$FAILURES" -ne 0 ] || [ "$MUT_FAIL" -ne 0 ]; then
    echo "FAIL  $FAILURES assertion(s) red, $MUT_FAIL mutation(s) void or hollow"
    exit 1
fi
echo "PASS  8 properties asserted, 18 mutations each proved able to go red"
