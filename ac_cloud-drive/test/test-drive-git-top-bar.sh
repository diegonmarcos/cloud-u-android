#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #1315 THE TOP BAR IS DATA. Two action rows (one a Kotlin enum, one       ║
# ║ declared), 23 filter chips with no counts and a legend pushed the first  ║
# ║ repository row ~20 lines down a phone. Now ui.sync.git.top_bar declares  ║
# ║ the split menus, the search, the counters; the filters declare their    ║
# ║ predicates; the page dispatches on `kind` alone in ONE sticky block.    ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#   T1 kinds both ways: every top_bar item kind is in Declarations.TOP_KINDS, and every kind the page's `when (item.kind)`
#      handles is declared at least once; a depth item names a scope-all depth action; a compound item names a compound id.
#   T2 no Kotlin bar: RepoActions has no `enum class Bulk`, strings.xml no repo_bulk_*, no RepoBulkBar / DepthBar / RepoFilterRow;
#      the page's sticky block holds GitTopBar, the job line, the search, the quick rail, the summary and the column header.
#   F1 filters are predicates: every option (but `all` / the everything option of a non-all default) declares one; RepoFilters
#      names no Kind; `archived` defaults to hidden; at most 6 quick options.
#   M  mutations: an item with kind "bogus" in build.json turns T1 red; a `"dirty" ->` branch in RepoFilters turns F1 red.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
SYNC="$SRC/sync"
BJ="$APP/build.json"
PAGE="$SYNC/GitReposScreen.kt"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

t1() { python3 - "$1" "$2" "$SRC/Declarations.kt" <<'PY'
import json, re, sys
g = json.load(open(sys.argv[1]))["ui"]["sync"]["git"]; page = open(sys.argv[2]).read(); decl = open(sys.argv[3]).read()
known = set(re.search(r'val TOP_KINDS = setOf\(([^)]*)\)', decl).group(1).replace('"', '').replace(' ', '').split(','))
items = [i for grp in g["top_bar"]["groups"] for i in grp["items"]]
bad = 0
for i in items:
    if i["kind"] not in known: print("    undeclared kind:", i["id"], i["kind"]); bad = 1
block = page[page.index("fun runTopItem("):page.index("fun onTopItem(")]
handled = set(re.findall(r'"([a-z_]+)" ->', block))
for k in handled:
    if k not in known: print("    the page handles an unknown kind:", k); bad = 1
for k in known:
    if k not in handled: print("    the page has no branch for the declared kind:", k); bad = 1
depth_ids = {a["id"] for a in g["depth_actions"] if a["scope"] == "all"}
for i in items:
    if i["kind"] == "depth" and i.get("action") not in depth_ids: print("    depth item names no scope-all depth action:", i["id"]); bad = 1
    if i["kind"] == "compound" and i.get("op") not in ("sync_no_winner", "sync_remote_wins", "sync_local_wins", "stash_push"): print("    compound item names no compound id:", i["id"]); bad = 1
    if i.get("danger") and not i.get("confirm"): print("    danger without confirm:", i["id"]); bad = 1
sys.exit(bad)
PY
}
t2() {
  local bad=0
  grep -q 'enum class Bulk' "$SYNC/RepoActions.kt" && { echo "    RepoActions still carries enum Bulk"; bad=1; }
  grep -q 'repo_bulk_' "$APP/app/src/main/res/values/strings.xml" && { echo "    strings.xml still carries repo_bulk_*"; bad=1; }
  grep -rqE 'fun (RepoBulkBar|DepthBar|RepoFilterRow)\(' "$SYNC" && { echo "    an old bar composable survives"; bad=1; }
  local block; block=$(sed -n '/stickyHeader(key = "table-top")/,/^        }$/p' "$1")
  for t in 'GitTopBar(page.topBar' 'GitJobLine(' 'GitSearchRow(' 'QuickFilterRail(' 'Text(summaryLine' 'RepoTableHeader(sortColumn'; do grep -qF -- "$t" <<<"$block" || { echo "    the sticky block lacks $t"; bad=1; }; done
  grep -q 'item(key = "table-bulk")' "$1" && { echo "    a plain item still sits above the sticky block"; bad=1; }
  return $bad
}
f1() { python3 - "$1" "$2" <<'PY'
import json, re, sys
filters = json.load(open(sys.argv[1]))["ui"]["sync"]["git"]["filters"]; src = open(sys.argv[2]).read()
preds = ("kinds", "cloned", "lfs", "private", "hub", "archived", "level"); bad = 0
for f in filters:
    for o in f["options"]:
        if o["id"] != "all" and f.get("default", "all") == "all" and not any(k in o for k in preds): print("    option without a predicate:", f["id"], o["id"]); bad = 1
if re.search(r'"(dirty|clean|ahead|behind|conflict|diverged)"\s*->', src): print("    RepoFilters names a Kind"); bad = 1
if "val HANDLED" in src: print("    RepoFilters carries a hard-coded HANDLED set"); bad = 1
arch = next((f for f in filters if f["id"] == "archived"), None)
if not arch or arch.get("default") != "hidden": print("    archived must default to hidden"); bad = 1
if sum(1 for f in filters for o in f["options"] if o.get("quick")) > 6: print("    more than 6 quick options"); bad = 1
sys.exit(bad)
PY
}

echo "── T1 kinds both ways ──"; t1 "$BJ" "$PAGE" && pass "every declared kind has a branch, every branch a declared kind; depth / compound items resolve" || fail "kinds"
echo "── T2 no Kotlin bar ──";   t2 "$PAGE" && pass "the bar is GitTopBar in ONE sticky block; the enum, the strings and the old composables are gone" || fail "Kotlin bar survives"
echo "── F1 predicates ──";     f1 "$BJ" "$SYNC/RepoFilters.kt" && pass "every option declares its predicate, RepoFilters names no Kind, archived hidden by default, rail <= 6" || fail "filters"
echo "── M mutations ──"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["sync"]["git"]["top_bar"]["groups"][0]["items"].append({"id":"x","kind":"bogus","label":"x"}); json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$tmp/b.json"
t1 "$tmp/b.json" "$PAGE" >/dev/null 2>&1 && fail "T1 passed an undeclared kind (vacuous)" || pass "undeclared kind → T1 RED"
printf '%s\n' "$(cat "$SYNC/RepoFilters.kt")" '    private fun rogue(k: String) = when (k) { "dirty" -> 1; else -> 0 }' > "$tmp/F.kt"
f1 "$BJ" "$tmp/F.kt" >/dev/null 2>&1 && fail "F1 passed a Kind named in Kotlin (vacuous)" || pass "a Kind in RepoFilters → F1 RED"
t1 "$BJ" "$PAGE" >/dev/null && t2 "$PAGE" >/dev/null && f1 "$BJ" "$SYNC/RepoFilters.kt" >/dev/null && pass "unmutated tree is still green" || fail "the unmutated tree is red"


# T3 #1356 no Git title row, compact search: ui.sections[git].title_row is false and GitPageScreen draws no ToolbarIsland
#    (nor the terminal action it carried); the search is GitSearchField at ui.sync.git.top_bar.search_height_dp (40), never an
#    OutlinedTextField; Home ▸ Apps carries the Termux tile (fleet termux) that replaced the row's terminal icon.
t3() { python3 - "$BJ" "$1" "$2" "$APP/data/drive-apps.json" <<'PY'
import json, os, re, sys
bj = json.load(open(sys.argv[1])); screen = open(sys.argv[2]).read(); bar = open(sys.argv[3]).read(); apps = json.load(open(sys.argv[4]))["apps"]
git = next(s for s in bj["ui"]["sections"] if s["id"] == "git"); h = bj["ui"]["sync"]["git"]["top_bar"].get("search_height_dp")
m = re.search(r'fun GitPageScreen\((.*?)\n}\n', screen, re.S); body = m.group(1) if m else ""
bad = []
if git.get("title_row") is not False: bad.append("ui.sections[git].title_row is not false")
if not body: bad.append("GitPageScreen not found")
if "ToolbarIsland(" in body or "TerminalIslandAction(" in body: bad.append("GitPageScreen still draws a title row / terminal icon")
if h != 40: bad.append("search_height_dp is %r, want 40" % h)
chrome = open(os.path.join(os.path.dirname(sys.argv[1]), "app/src/main/java/com/diegonmarcos/clouddrive/ui/Chrome.kt")).read()
if "val height = DriveMetrics.rowHeight" not in bar or not re.search(r'val rowHeight = %s\.dp' % h, chrome): bad.append("GitSearchMetrics.height is not DriveMetrics.rowHeight = search_height_dp")
if "OutlinedTextField" in bar or "GitSearchField(query" not in bar: bad.append("the Git search is not the compact GitSearchField")
if "Icons.Filled.Search" not in bar or "Icons.Filled.Close" not in bar: bad.append("no inline magnifier or clear (x)")
if not any(a.get("fleet") == "termux" and a.get("label") == "Termux" for a in apps): bad.append("no Termux tile (fleet termux) on Home ▸ Apps")
print("\n".join(bad)); sys.exit(1 if bad else 0)
PY
}
echo "== T3 no Git title row, compact search, Termux tile =="
out=$(t3 "$SYNC/SyncScreen.kt" "$SYNC/GitTopBar.kt") && pass "Git page has no title row; search is 40dp compact; Termux tile on Home ▸ Apps" || fail "T3: $out"
sed 's|NavBarTopSecondRow(pages = pages|ToolbarIsland(title = ""); NavBarTopSecondRow(pages = pages|' "$SYNC/SyncScreen.kt" > "$tmp/S.kt"
t3 "$tmp/S.kt" "$SYNC/GitTopBar.kt" >/dev/null 2>&1 && fail "T3 passed a title row (vacuous)" || pass "a ToolbarIsland back on the Git page → T3 RED"
sed 's|GitSearchField(query, onQuery|OutlinedTextField(query, onQuery|' "$SYNC/GitTopBar.kt" > "$tmp/B.kt"
t3 "$SYNC/SyncScreen.kt" "$tmp/B.kt" >/dev/null 2>&1 && fail "T3 passed an outlined search (vacuous)" || pass "an OutlinedTextField search → T3 RED"

# T4 #1356 the top nav is Check | Sync | Clone | Logs | More ..... Settings: those five groups in that order, the first four ONE
#    item each (check_full, sync_all, clone_missing, the open_logs item) run on tap, More keeps every other action of the bar
#    (none lost), Settings sits after the groups' weighted row; Logs declares the tabs repos, app and App is Activity Logs.
KEEP="check_light check_remote check_sizes scan_inventory cancel_checks pull_all force_pull_all stash_push_all fetch_all mirror_all shrink_all last_report show_archived legend"
t4() { python3 - "$1" "$2" "$3" "$KEEP" <<'PY'
import json, sys
g = json.load(open(sys.argv[1]))["ui"]["sync"]["git"]; bar = open(sys.argv[2]).read(); ui = open(sys.argv[3]).read(); keep = sys.argv[4].split()
groups = g["top_bar"]["groups"]; bad = []
if [x["id"] for x in groups] != ["check", "sync", "clone", "logs", "more"]: bad.append("groups are %s" % [x["id"] for x in groups])
else:
    want = [("check_full", "check_full"), ("sync_all", "compound"), ("clone_missing", "depth"), ("logs", "open_logs")]
    got = [(x["items"][0]["id"], x["items"][0]["kind"]) if len(x["items"]) == 1 else None for x in groups[:4]]
    if got != want: bad.append("the four buttons are %s, want %s" % (got, want))
    more = [i["id"] for i in groups[4]["items"]]
    lost = [k for k in keep if k not in more]
    if lost: bad.append("More lost %s" % lost)
if [t["id"] for t in g.get("logs", {}).get("tabs", [])] != ["repos", "app"]: bad.append("logs.tabs is not repos, app")
if "val single = g.items.singleOrNull()" not in bar or "onItem(single)" not in bar: bad.append("a one-item group does not run on tap")
if bar.find("Modifier.weight(1f).horizontalScroll") < 0 or bar.find("Modifier.weight(1f).horizontalScroll") > bar.find("SYNC_AUTO_SETTINGS"): bad.append("Settings is not after the groups' weighted row")
if '"app" -> Column(' not in ui or "ActivityLogsView(events, fmt)" not in ui or "RepoLogsList(" not in ui or "runFeed(" not in ui or "loadDetails(" not in ui: bad.append("Logs lacks App = Activity Logs or Repos = commits + CI")
print("\n".join(bad)); sys.exit(1 if bad else 0)
PY
}
echo "== T4 Check | Sync | Clone | Logs | More ..... Settings =="
out=$(t4 "$BJ" "$SYNC/GitTopBar.kt" "$SYNC/GitDepthUi.kt") && pass "the top nav is the five declared groups, More keeps every other action, Logs = Repos | App" || fail "T4: $out"
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); g=d["ui"]["sync"]["git"]["top_bar"]["groups"]; g[4]["items"]=[i for i in g[4]["items"] if i["id"]!="legend"]; json.dump(d,open(sys.argv[2],"w"))' "$BJ" "$tmp/bj4.json"
t4 "$tmp/bj4.json" "$SYNC/GitTopBar.kt" "$SYNC/GitDepthUi.kt" >/dev/null 2>&1 && fail "T4 passed a More that lost an action (vacuous)" || pass "an action dropped from More → T4 RED"

# T5 #1356 no reserved height: with ui.top_bar declared (the shell's first row clears the status bar) every section strip is
#    drawn with underTopChrome = false (no second status-bar band above Local/Cloud, Me/Cloud); title_row false -> the
#    strip is the Git page's first child (no Spacer, island or top padding before it).
t5() { python3 - "$BJ" "$1" "$2" <<'PY'
import json, os, re, sys
bj = json.load(open(sys.argv[1])); src = sys.argv[2]; git = open(sys.argv[3]).read(); bad = []
if bj["ui"].get("top_bar"):
    for d, _, fs in os.walk(src):
        for f in fs:
            if not f.endswith(".kt"): continue
            t = open(os.path.join(d, f)).read()
            for m in re.finditer(r"NavBarTopSecondRow\(", t):
                depth, i = 0, m.end() - 1
                while True:
                    c = t[i]; depth += c == "("; depth -= c == ")"; i += 1
                    if depth == 0: break
                if "underTopChrome = false" not in t[m.start():i]: bad.append("%s: a strip reserves the status-bar inset under the top bar" % f)
for x in bj["ui"]["sections"]:
    if x.get("title_row") is not False: bad.append("ui.sections[%s].title_row is not false" % x["id"])
# no tab draws a title row (the island reserves its height + the status-bar inset); the viewer and the agent sub-page are not tabs
for rel in ("home/HomeScreen.kt", "configs/ConfigsScreen.kt", "volumes/VolumesScreen.kt", "sync/FsyncPage.kt", "sync/MountsPage.kt",
            "sync/SyncScreen.kt", "manage/ManageScreen.kt", "files/FilesScreen.kt", "files/ExplorerScreen.kt"):
    t = open(os.path.join(src, rel)).read() if os.path.exists(os.path.join(src, rel)) else ""
    if "ToolbarIsland(" in t or "TerminalIslandAction(" in t: bad.append("%s still draws a title row" % rel)
g = next(x for x in bj["ui"]["sections"] if x["id"] == "git")
if g.get("title_row") is False:
    body = git[git.index("Column(modifier.fillMaxSize()) {", git.index("fun GitPageScreen(")):]
    first = [l.strip() for l in body.splitlines()[1:] if l.strip() and not l.strip().startswith("//")][0]
    if not first.startswith("NavBarTopSecondRow("): bad.append("the Git page reserves height above its strip: " + first[:80])
print("\n".join(bad)); sys.exit(1 if bad else 0)
PY
}
echo "== T5 no reserved height above the section strips =="
out=$(t5 "$SRC" "$SYNC/SyncScreen.kt") && pass "every strip under the top bar adds no inset; the Git strip is the page's first child" || fail "T5: $out"
mkdir -p "$tmp/src5/files"; sed 's/, underTopChrome = false)/)/' "$SRC/files/ExplorerScreen.kt" > "$tmp/src5/files/ExplorerScreen.kt"
t5 "$tmp/src5" "$SYNC/SyncScreen.kt" >/dev/null 2>&1 && fail "T5 passed a strip that reserves the inset (vacuous)" || pass "Explorer's strip back on the inset → T5 RED"
mkdir -p "$tmp/src6/home"; sed 's#^\(\s*\)// \#1356 no title row.*#\1ToolbarIsland(title = "Home")#' "$SRC/home/HomeScreen.kt" > "$tmp/src6/home/HomeScreen.kt"
grep -q 'ToolbarIsland(title = "Home")' "$tmp/src6/home/HomeScreen.kt" || fail "T5 mutation anchor gone in HomeScreen"
t5 "$tmp/src6" "$SYNC/SyncScreen.kt" >/dev/null 2>&1 && fail "T5 passed a title row back on Home (vacuous)" || pass "a title row back on Home → T5 RED"
sed 's/^\(        \)NavBarTopSecondRow(pages = pages/\1Spacer(Modifier.height(DriveMetrics.islandHeight))\n\1NavBarTopSecondRow(pages = pages/' "$SYNC/SyncScreen.kt" > "$tmp/S5.kt"
t5 "$SRC" "$tmp/S5.kt" >/dev/null 2>&1 && fail "T5 passed a Spacer above the Git strip (vacuous)" || pass "a Spacer above the Git strip → T5 RED"

[ "$FAILURES" -eq 0 ] && { echo "test-drive-git-top-bar: all green"; exit 0; } || { echo "test-drive-git-top-bar: $FAILURES failure(s)"; exit 1; }
