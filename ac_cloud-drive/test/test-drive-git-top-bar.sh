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

[ "$FAILURES" -eq 0 ] && { echo "test-drive-git-top-bar: all green"; exit 0; } || { echo "test-drive-git-top-bar: $FAILURES failure(s)"; exit 1; }
