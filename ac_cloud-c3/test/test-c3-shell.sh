#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #648 — cloud-c3's chrome is DECLARED, and the extraction left no orphan  ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. This app was EXTRACTED from aa_cloud-superapp's C3 section, and
# the characteristic failure of an extraction is not a crash — it is a surface that
# exists with nothing linking to it, or a link pointing at a surface that no longer
# exists. Neither is visible: an orphaned page compiles, ships, installs and is
# simply never reached, and a dangling link is a tap that does nothing. So the
# properties below are asserted, not reviewed, and every one of them is asserted on
# the MESSAGE a check prints rather than on an exit status.
#
#   T1  ONE tab declaration: build.json::ui.tabs is exactly the five the owner
#       named — Topology · Observ · Home · Apps · Configs — in that order, with
#       Home in the CENTRE (index 2), the position every fleet five-tab shell
#       gives it, and ui.default_tab naming a tab that exists.
#   T2  NO ORPHANS, BOTH DIRECTIONS: every declared tab id has a branch in
#       C3Screens' dispatch and every branch answers a declared id. This is the
#       check that makes "no page exists with nothing linking to it" a property.
#   T3  the declaration is the ONLY order: no Kotlin file holds a second list of
#       tab ids, so reordering the array in build.json reorders the nav and
#       changes nothing else. A parallel list is the #405 group_members mistake.
#   T4  every declared icon name is in IconCatalog's vocabulary, INCLUDING
#       ui.icon_default — a declared glyph that silently falls back is the
#       #170/#380 shape, two lists agreeing by luck.
#   T5  the Apps tab LAUNCHES the three real sibling APKs: each declared package
#       equals that sibling's own build.json application_id, each id IS that
#       sibling's fleet name, no tile carries a display `label` beside a fleet
#       package (#351's one name, the regression #224 reverted, and the rule the
#       superapp's test-app-names-pattern.sh T4 already enforces repo-wide), and
#       each package is declared in AndroidManifest <queries> — without which API
#       30+ reports an absent package for an app that IS installed, so the tile's
#       "Not installed" would be a lie.
#   T6  no Kotlin file outside the chrome declaration holds a dp/sp literal, and
#       no screen holds a user-facing caption literal: a caption is a declaration
#       or an R.string.
#   T7  libs:bottomnav is REUSED, not forked: the four shared symbols are the ones
#       imported, and cloud-mail's `bottomNavItems` table is NOT — consuming that
#       would couple this app's nav to another shipping app's tabs.
#   M   mutation-proof: a sixth tab, a dropped branch, a branch for an undeclared
#       tab, a parallel id list, an unknown icon, a wrong sibling package, a
#       dropped <queries> entry and an imported bottomNavItems each turn a check
#       RED, and the unmutated tree stays GREEN.
#
# OWN-SOURCE ONLY. python3, jq and grep.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-c3"
BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/cloudc3"
SCREENS="$SRC/ui/C3Screens.kt"
SHELL_KT="$SRC/ui/C3Shell.kt"
ICONS="$SRC/ui/IconCatalog.kt"
CHROME="$SRC/ui/Chrome.kt"
THEME="$SRC/ui/C3Theme.kt"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

# A tester whose subject is missing is UNRUN, not passing (#330).
for required in "$BJ" "$SCREENS" "$SHELL_KT" "$ICONS" "$CHROME" "$THEME" "$MANIFEST"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done
command -v jq >/dev/null 2>&1 || { echo "ERROR jq absent — a verdict from a missing tool is not a verdict"; exit 1; }

# ── the checks as functions of their inputs, so the mutation block runs them on copies ──

# t1 <build.json> : the five declared tabs, in order, Home centre, default resolvable
t1() {
    python3 - "$1" <<'PYTHON'
import json, sys
ui = json.load(open(sys.argv[1], encoding="utf-8"))["ui"]
tabs = ui.get("tabs") or []
ids = [t.get("id") for t in tabs]
labels = [t.get("label") for t in tabs]
want_labels = ["Topology", "Observ", "Home", "Apps", "Configs"]
bad = []
if len(tabs) != 5:
    bad.append("ui.tabs declares %d tabs, not the five the owner named: %s" % (len(tabs), ids))
if labels != want_labels:
    bad.append("ui.tabs labels are %s, not %s in that order" % (labels, want_labels))
if len(ids) == 5 and ids[2] != "home":
    bad.append("the CENTRE tab (index 2) is %r, not home — every fleet five-tab shell puts Home centre" % ids[2])
d = ui.get("default_tab")
if d not in ids:
    bad.append("ui.default_tab %r is not one of the declared ids %s" % (d, ids))
if len(set(ids)) != len(ids):
    bad.append("two tabs share an id: %s" % ids)
for t in tabs:
    for k in ("id", "label", "icon"):
        if not t.get(k):
            bad.append("tab %r declares no %s" % (t.get("id"), k))
for b in bad:
    print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t2 <build.json> <C3Screens.kt> : declaration <-> dispatch, BOTH directions
t2() {
    python3 - "$1" "$2" <<'PYTHON'
import json, re, sys
declared = [t["id"] for t in (json.load(open(sys.argv[1], encoding="utf-8"))["ui"].get("tabs") or [])]
text = open(sys.argv[2], encoding="utf-8").read()
m = re.search(r"when \(tabId\) \{(.*?)\n        \}", text, re.S)
if not m:
    print("    no `when (tabId)` in C3Screens — there is no single dispatch to diff"); sys.exit(1)
body = m.group(1)
# The branch LABELS, not a companion list: the dispatch is the only statement of
# what Kotlin answers, so it is read out of the source.
branches = re.findall(r'^\s*"([^"]+)"\s*->', body, re.M)
bad = []
for tab in declared:
    if tab not in branches:
        bad.append("tab %r is DECLARED but C3Screens dispatches no branch for it — "
                   "it would render the unknown-tab state, an orphaned declaration" % tab)
for br in branches:
    if br not in declared:
        bad.append("C3Screens dispatches %r but ui.tabs does not declare it — "
                   "an orphaned SCREEN, reachable from nothing" % br)
if len(set(branches)) != len(branches):
    bad.append("C3Screens has a duplicate branch: %s" % branches)
for b in bad:
    print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t3 <build.json> <src dir> : no SECOND list of tab ids anywhere in Kotlin
t3() {
    python3 - "$1" "$2" <<'PYTHON'
import json, os, re, sys
declared = [t["id"] for t in (json.load(open(sys.argv[1], encoding="utf-8"))["ui"].get("tabs") or [])]
root = sys.argv[2]
bad = []
for dirpath, _dirs, files in os.walk(root):
    for f in files:
        if not f.endswith(".kt"):
            continue
        p = os.path.join(dirpath, f)
        text = open(p, encoding="utf-8").read()
        rel = os.path.relpath(p, root)
        # A listOf/arrayOf/setOf holding two or more declared tab ids is a parallel
        # ordering: the nav would then read one list and something else another.
        for lit in re.findall(r"(?:listOf|arrayOf|setOf)\s*\(([^)]*)\)", text, re.S):
            found = [t for t in declared if '"%s"' % t in lit]
            if len(found) >= 2:
                bad.append("%s holds a second list of tab ids %s — reordering ui.tabs "
                           "would no longer reorder the nav" % (rel, found))
for b in bad:
    print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t4 <build.json> <IconCatalog.kt> : every declared glyph is in the vocabulary
t4() {
    python3 - "$1" "$2" <<'PYTHON'
import json, re, sys
ui = json.load(open(sys.argv[1], encoding="utf-8"))["ui"]
text = open(sys.argv[2], encoding="utf-8").read()
m = re.search(r"fun vector\(name: String\): ImageVector\? = when \(name\) \{(.*?)\n    \}", text, re.S)
if not m:
    print("    no `fun vector(name)` when-block in IconCatalog — there is no vocabulary to hold declarations to")
    sys.exit(1)
known = set(re.findall(r'^\s*"([^"]+)"\s*->', m.group(1), re.M))
used = set()
for t in (ui.get("tabs") or []):
    used.add(t.get("icon"))
for a in (ui.get("external_apps") or []):
    used.add(a.get("icon"))
for key in ("topology", "observ", "configs"):
    for p in ((ui.get(key) or {}).get("pages") or []):
        used.add(p.get("icon"))
if ui.get("icon_default"):
    used.add(ui["icon_default"])
used = {u for u in used if u}
bad = []
for name in sorted(used - known):
    bad.append("declared icon %r is not in IconCatalog's vocabulary — it would silently "
               "fall back to the default glyph" % name)
if ui.get("icon_default") and ui["icon_default"] not in known:
    bad.append("ui.icon_default %r is itself unknown, so the FALLBACK has no glyph" % ui["icon_default"])
for b in bad:
    print("    " + b)
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
bad = []
want = {"c3-watchdog": "ac_c3-watchdog", "c3-morpheus": "ac_c3-morpheus", "c3-watchtower": "ac_c3-watchtower"}
ids = [t.get("id") for t in tiles]
if sorted(ids) != sorted(want):
    bad.append("the Apps tab declares %s, not the three the owner named %s" % (ids, sorted(want)))
queried = set(re.findall(r'<package android:name="([^"]+)"', manifest))
for t in tiles:
    tid, pkg = t.get("id"), t.get("package")
    # #351/#224 THE ONE NAME. A tile may not carry a display label beside a fleet
    # package: that is a second statement of the application's name and it drifts.
    # The superapp's test-app-names-pattern.sh T4 fails the build on it, and this
    # check fails here too so the rule is asserted where the declaration lives.
    if "label" in t:
        bad.append("Apps tile %r carries a label %r beside a fleet package — an application has "
                   "ONE name (#351) and the id IS it; #224 reverted exactly this"
                   % (tid, t.get("label")))
    sib = want.get(tid)
    if sib is None:
        continue
    sib_bj = os.path.join(root, sib, "build.json")
    if not os.path.isfile(sib_bj):
        bad.append("%s names sibling %s, which has no build.json — the tile opens nothing" % (tid, sib))
        continue
    sib_json = json.load(open(sib_bj, encoding="utf-8"))
    real = (sib_json.get("android") or {}).get("application_id")
    if pkg != real:
        bad.append("%s declares package %r but %s/build.json's application_id is %r — "
                   "a wrong package compiles, ships, installs and leaves a tab that opens nothing"
                   % (tid, pkg, sib, real))
    # The id must BE that sibling's fleet name, which is what the tile shows.
    if sib_json.get("name") != tid:
        bad.append("Apps tile id %r is not %s/build.json::name %r — the tile would show a name "
                   "the fleet does not use" % (tid, sib, sib_json.get("name")))
    if pkg not in queried:
        bad.append("%s's package %r is not in AndroidManifest <queries> — on API 30+ an unqueried "
                   "package is INVISIBLE, so an installed app would read 'Not installed'" % (tid, pkg))
for b in bad:
    print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t6 <src dir> <chrome> <theme> : sizes and captions live in ONE place each
t6() {
    python3 - "$1" "$2" "$3" <<'PYTHON'
import os, re, sys
root, chrome, theme = sys.argv[1], os.path.basename(sys.argv[2]), os.path.basename(sys.argv[3])
allowed = {chrome, theme}
bad = []
for dirpath, _dirs, files in os.walk(root):
    for f in sorted(files):
        if not f.endswith(".kt") or f in allowed:
            continue
        p = os.path.join(dirpath, f)
        rel = os.path.relpath(p, root)
        lines = open(p, encoding="utf-8").read().split("\n")
        for i, line in enumerate(lines, 1):
            code = line.split("//", 1)[0]
            if re.search(r"\b\d+(?:\.\d+)?\.(?:dp|sp)\b", code):
                bad.append("%s:%d holds a size literal — every size is a C3Metrics member "
                           "so the app's density is one edit: %s" % (rel, i, code.strip()))
for b in bad:
    print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t7 <C3Shell.kt> : the shared island is reused, cloud-mail's table is not
t7() {
    python3 - "$1" <<'PYTHON'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
# CODE ONLY. The first cut of this check searched the whole file and went red on the
# KDoc sentence explaining that bottomNavItems is deliberately NOT used — an assertion
# anchored on PROSE rather than on the rule (#362/#363). Block comments and line
# comments are stripped before anything is asserted.
code = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
code = re.sub(r"//[^\n]*", "", code)
bad = []
for sym in ("BottomNavEntry", "BottomNavIsland", "bottomNavInsets", "rememberBottomNavCollapse"):
    if not re.search(r"^import com\.diegonmarcos\.superapp\.bottomnav\.%s$" % sym, code, re.M):
        bad.append("C3Shell does not import the shared %s — the fleet's one nav geometry is "
                   "being re-implemented rather than reused" % sym)
if re.search(r"\bbottomNavItems\b", code):
    bad.append("C3Shell references bottomNavItems, which is cloud-MAIL's item table: this app's "
               "nav would then be coupled to another shipping app's tabs")
for b in bad:
    print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

echo "── #648 cloud-c3 chrome: declared, and no orphan left by the extraction ──"
t1 "$BJ"                        && pass "T1 ui.tabs is the five declared tabs, in order, Home centre" || fail "T1 the tab declaration is not the five in order"
t2 "$BJ" "$SCREENS"             && pass "T2 declaration <-> dispatch agree in BOTH directions: no orphaned tab, no orphaned screen" || fail "T2 declaration and dispatch disagree"
t3 "$BJ" "$SRC"                 && pass "T3 no parallel list of tab ids: reordering ui.tabs reorders the nav" || fail "T3 a second list of tab ids exists"
t4 "$BJ" "$ICONS"               && pass "T4 every declared icon is in IconCatalog's vocabulary" || fail "T4 a declared icon would silently fall back"
t5 "$BJ" "$MANIFEST" "$ROOT"    && pass "T5 the Apps tab launches the three REAL sibling APKs, each queried" || fail "T5 an Apps tile cannot open what it names"
t6 "$SRC" "$CHROME" "$THEME"    && pass "T6 no size literal outside the chrome declaration" || fail "T6 a screen sizes itself"
t7 "$SHELL_KT"                  && pass "T7 libs:bottomnav reused (four shared symbols), cloud-mail's table not" || fail "T7 the shared nav is not reused as declared"

# ── MUTATION PROOF ─────────────────────────────────────────────────────────
# Each mutation is applied to a COPY that is verified GREEN FIRST, so a red caused
# by a broken edit is reported VOID rather than counted as coverage (#646).
echo
echo "── mutation proof: each check must be able to go RED ──"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
MUT_FAIL=0
mutate() { # <name> <check-fn> <setup-fn>
    local name="$1" check="$2" setup="$3"
    rm -rf "$WORK/t"; mkdir -p "$WORK/t"
    cp -r "$APP" "$WORK/t/ac_cloud-c3"
    for sib in ac_c3-watchdog ac_c3-morpheus ac_c3-watchtower; do
        mkdir -p "$WORK/t/$sib"
        [ -f "$ROOT/$sib/build.json" ] && cp "$ROOT/$sib/build.json" "$WORK/t/$sib/build.json"
    done
    local M_BJ="$WORK/t/ac_cloud-c3/build.json"
    local M_SRC="$WORK/t/ac_cloud-c3/app/src/main/java/com/diegonmarcos/cloudc3"
    local M_MAN="$WORK/t/ac_cloud-c3/app/src/main/AndroidManifest.xml"
    # GREEN FIRST: an unmutated copy must pass, or the mutation proves nothing.
    if ! "$check" "$WORK/t" >/dev/null 2>&1; then
        echo "  VOID  $name — the unmutated COPY is already red, so this mutation proves nothing"
        MUT_FAIL=$((MUT_FAIL + 1)); return
    fi
    # A mutation whose edit FAILED (a target string that moved) would leave the copy
    # unmutated and be reported HOLLOW, blaming the check for the harness's own miss.
    if ! "$setup" "$WORK/t" >/dev/null 2>&1; then
        echo "  VOID  $name — the mutation itself failed to apply, so it proves nothing"
        MUT_FAIL=$((MUT_FAIL + 1)); return
    fi
    if "$check" "$WORK/t" >/dev/null 2>&1; then
        echo "  HOLLOW  $name — the check stayed GREEN under the mutation, so it proves nothing"
        MUT_FAIL=$((MUT_FAIL + 1))
    else
        echo "  RED     $name"
    fi
}

# check wrappers, each taking a tree root
c_t1() { t1 "$1/ac_cloud-c3/build.json"; }
c_t2() { t2 "$1/ac_cloud-c3/build.json" "$1/ac_cloud-c3/app/src/main/java/com/diegonmarcos/cloudc3/ui/C3Screens.kt"; }
c_t3() { t3 "$1/ac_cloud-c3/build.json" "$1/ac_cloud-c3/app/src/main/java/com/diegonmarcos/cloudc3"; }
c_t4() { t4 "$1/ac_cloud-c3/build.json" "$1/ac_cloud-c3/app/src/main/java/com/diegonmarcos/cloudc3/ui/IconCatalog.kt"; }
c_t5() { t5 "$1/ac_cloud-c3/build.json" "$1/ac_cloud-c3/app/src/main/AndroidManifest.xml" "$1"; }
c_t6() { local s="$1/ac_cloud-c3/app/src/main/java/com/diegonmarcos/cloudc3"; t6 "$s" "$s/ui/Chrome.kt" "$s/ui/C3Theme.kt"; }
c_t7() { t7 "$1/ac_cloud-c3/app/src/main/java/com/diegonmarcos/cloudc3/ui/C3Shell.kt"; }

# mutations
m_sixth_tab() { python3 - "$1/ac_cloud-c3/build.json" <<'PY'
import json,sys,collections
p=sys.argv[1]; d=json.load(open(p),object_pairs_hook=collections.OrderedDict)
d["ui"]["tabs"].append(collections.OrderedDict([("id","extra"),("label","Extra"),("icon","stack")]))
json.dump(d,open(p,"w"),indent=2)
PY
}
m_reorder_home() { python3 - "$1/ac_cloud-c3/build.json" <<'PY'
import json,sys,collections
p=sys.argv[1]; d=json.load(open(p),object_pairs_hook=collections.OrderedDict)
t=d["ui"]["tabs"]; t[2],t[4]=t[4],t[2]   # Home off centre
json.dump(d,open(p,"w"),indent=2)
PY
}
m_drop_branch() { python3 - "$1/ac_cloud-c3/app/src/main/java/com/diegonmarcos/cloudc3/ui/C3Screens.kt" <<'PY'
import re,sys
p=sys.argv[1]; s=open(p).read()
s=re.sub(r'\n\s*"configs" -> ConfigsScreen\(reselectTick\)','',s)
open(p,"w").write(s)
PY
}
m_orphan_screen() { python3 - "$1/ac_cloud-c3/app/src/main/java/com/diegonmarcos/cloudc3/ui/C3Screens.kt" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
s=s.replace('"configs" -> ConfigsScreen(reselectTick)',
            '"configs" -> ConfigsScreen(reselectTick)\n            "ghost" -> ConfigsScreen(reselectTick)')
open(p,"w").write(s)
PY
}
m_parallel_list() { python3 - "$1/ac_cloud-c3/app/src/main/java/com/diegonmarcos/cloudc3/ui/C3Screens.kt" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
s=s.replace("object C3Screens {",
  'object C3Screens {\n    val order = listOf("topology", "observ", "home", "apps", "configs")\n')
open(p,"w").write(s)
PY
}
m_unknown_icon() { python3 - "$1/ac_cloud-c3/build.json" <<'PY'
import json,sys,collections
p=sys.argv[1]; d=json.load(open(p),object_pairs_hook=collections.OrderedDict)
d["ui"]["tabs"][0]["icon"]="no_such_glyph"
json.dump(d,open(p,"w"),indent=2)
PY
}
m_wrong_package() { python3 - "$1/ac_cloud-c3/build.json" <<'PY'
import json,sys,collections
p=sys.argv[1]; d=json.load(open(p),object_pairs_hook=collections.OrderedDict)
d["ui"]["external_apps"][0]["package"]="com.diegonmarcos.c3watchdog"
json.dump(d,open(p,"w"),indent=2)
PY
}
m_add_label() { python3 - "$1/ac_cloud-c3/build.json" <<'PY'
import json,sys,collections
p=sys.argv[1]; d=json.load(open(p),object_pairs_hook=collections.OrderedDict)
# The exact regression #224 reverted: a private display name beside a fleet package.
d["ui"]["external_apps"][0]["label"]="Watchdog"
json.dump(d,open(p,"w"),indent=2)
PY
}
m_rename_id() { python3 - "$1/ac_cloud-c3/build.json" <<'PY'
import json,sys,collections
p=sys.argv[1]; d=json.load(open(p),object_pairs_hook=collections.OrderedDict)
d["ui"]["external_apps"][0]["id"]="watchdog"   # not the sibling's fleet name
json.dump(d,open(p,"w"),indent=2)
PY
}
m_drop_query() { python3 - "$1/ac_cloud-c3/app/src/main/AndroidManifest.xml" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
s=s.replace('        <package android:name="com.diegonmarcos.watchdog" />\n','')
open(p,"w").write(s)
PY
}
m_size_literal() { python3 - "$1/ac_cloud-c3/app/src/main/java/com/diegonmarcos/cloudc3/home/HomeScreen.kt" <<'PY'
import sys
# Writes a dp literal back into a SCREEN, which is precisely the regression T6 exists to
# catch. It mutates the C3Metrics REFERENCE the screen now holds, and asserts the edit
# landed: the first cut of this mutation still targeted the `8.dp` literal that the fix
# had already replaced, so it changed nothing and T6 was reported HOLLOW when it was in
# fact fine. A mutation that does not mutate is the #639 hollow-green shape in the
# tester's own harness.
p=sys.argv[1]; s=open(p).read()
before=s
s=s.replace("Arrangement.spacedBy(C3Metrics.gap)", "Arrangement.spacedBy(37.dp)")
assert s != before, "mutation target absent: HomeScreen no longer spaces by C3Metrics.gap"
open(p,"w").write(s)
PY
}
m_mail_table() { python3 - "$1/ac_cloud-c3/app/src/main/java/com/diegonmarcos/cloudc3/ui/C3Shell.kt" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
s=s.replace("    val tabs = Declarations.tabs",
            "    val tabs = Declarations.tabs\n    val borrowed = bottomNavItems")
open(p,"w").write(s)
PY
}
m_drop_shared_import() { python3 - "$1/ac_cloud-c3/app/src/main/java/com/diegonmarcos/cloudc3/ui/C3Shell.kt" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
s=s.replace("import com.diegonmarcos.superapp.bottomnav.rememberBottomNavCollapse\n","")
open(p,"w").write(s)
PY
}

mutate "a SIXTH tab appears in the declaration"            c_t1 m_sixth_tab
mutate "Home is moved off the centre position"             c_t1 m_reorder_home
mutate "a declared tab loses its dispatch branch"          c_t2 m_drop_branch
mutate "a screen is dispatched that nothing declares"      c_t2 m_orphan_screen
mutate "a parallel list of tab ids is added"               c_t3 m_parallel_list
mutate "a tab declares an icon the catalog lacks"          c_t4 m_unknown_icon
mutate "an Apps tile names a package no sibling has"       c_t5 m_wrong_package
mutate "a sibling package is dropped from <queries>"       c_t5 m_drop_query
mutate "an Apps tile regains a display label (#224)"       c_t5 m_add_label
mutate "an Apps tile id stops being the fleet name"        c_t5 m_rename_id
mutate "a screen sizes itself with a dp literal"           c_t6 m_size_literal
mutate "cloud-mail's bottomNavItems table is consumed"     c_t7 m_mail_table
mutate "a shared bottomnav symbol stops being imported"    c_t7 m_drop_shared_import

echo
if [ "$FAILURES" -ne 0 ] || [ "$MUT_FAIL" -ne 0 ]; then
    echo "FAIL  $FAILURES assertion(s) red, $MUT_FAIL mutation(s) void or hollow"
    exit 1
fi
echo "PASS  7 properties asserted, 13 mutations each proved able to go red"
