#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #1288 the Git page's per-folder commands (Rename / Archive / Delete) and ║
# ║ the folder tables' header parity. Gradle proves it compiles; the JVM     ║
# ║ suite (FolderActionsTest) proves the rules; this proves the shape.       ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#   K1 kinds are DATA: ui.sync.git.folder_actions kinds == Declarations FOLDER_KINDS == the coordinator's handlers, both ways.
#   R1 the runner is the depth runner: runFolderAction goes through startDepth + bounded, logs to Activity Logs; no folder
#      command touches a remote (no push / gh / api delete); scripts never glob.
#   C1 rename never overwrites: the coordinator names through FolderActions.renameTarget (___CONFLICT), the move refuses an
#      existing destination.
#   H1 header parity: Parentless and Unlisted draw FolderRepoTable, which uses the main RepoTableHeader / RepoCells /
#      RepoStatusCell; the one header iterates RepoTable.COLUMNS; no other column header exists.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
COORD="$SRC/sync/GitSyncCoordinator.kt"
DECL="$SRC/Declarations.kt"
TABLE="$SRC/sync/FolderTableUi.kt"
RULES="$SRC/sync/FolderActions.kt"
TESTS="$APP/app/src/test/java/com/diegonmarcos/clouddrive"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

k1() { python3 - "$1" "$2" "$3" <<'PY'
import json, re, sys
g = json.load(open(sys.argv[1]))["ui"]["sync"]["git"]
coord = open(sys.argv[2]).read(); decl = open(sys.argv[3]).read()
bad = 0
acts = g.get("folder_actions") or []
declared = {a["kind"] for a in acts}
consts = dict((v, k) for k, v in re.findall(r'const val (FOLDER_[A-Z_]+) = "([a-z_]+)"', decl))
m = re.search(r"val FOLDER_KINDS = setOf\(([^)]*)\)", decl)
known = set(m.group(1).replace(" ", "").split(",")) if m else set()
if {"rename_to_repo", "archive", "delete_local"} - declared: print("    folder_actions lacks", sorted({"rename_to_repo", "archive", "delete_local"} - declared)); bad = 1
body = re.search(r"fun runFolderAction\(.*?\n    }\n", coord, re.S)
body = body.group(0) if body else ""
for k in declared:
    c = consts.get(k)
    if not c or c not in known: print("    declared kind has no constant in FOLDER_KINDS:", k); bad = 1
    elif not re.search(r"Declarations\." + c + r" ->", body): print("    runFolderAction has no handler for", k); bad = 1
for c in known:
    if next((k for k, v in consts.items() if v == c), None) not in declared: print("    handled kind is not declared:", c); bad = 1
handled = set(re.findall(r"Declarations\.(FOLDER_[A-Z_]+) ->", body))
if handled - known: print("    a handler names an undeclared kind:", sorted(handled - known)); bad = 1
if any(not a.get("confirm", True) for a in acts): print("    every folder command asks first"); bad = 1
root = g.get("archive_root", "")
if not root or root.startswith("/") or ".." in root.split("/"): print("    archive_root must be a relative safe path:", root); bad = 1
sys.exit(bad)
PY
}
r1() { for t in 'return startDepth(run)' 'bounded(run, targets)' 'history.append(SyncEvent(' 'fun asRun()'; do grep -qF "$t" "$1" "$2" || { echo "    lacks $t"; return 1; }; done
    python3 - "$1" "$3" <<'PY' || return 1
import re, sys
c = open(sys.argv[1]).read(); r = open(sys.argv[2]).read()
a = c.find("// ── #1288"); b = c.find("fun requestFolderStatus", a)
seg = c[a:b] if a >= 0 and b > a else ""
if not seg: print("    no #1288 section"); sys.exit(1)
if re.search(r"\bpush\b|GhEngine|deleteRepo|\"DELETE\"", seg): print("    a folder command reaches a remote"); sys.exit(1)
for fn in ("moveScript", "deleteScript"):
    m = re.search(r"fun " + fn + r"\(.*?\n\n", r, re.S)
    if not m or "*" in m.group(0).split("=", 1)[1]: print("    script globs or missing:", fn); sys.exit(1)
if "] || exit 17;" not in r: print("    the move does not refuse an existing destination"); sys.exit(1)
PY
}
c1() { grep -qF 'FolderActions.renameTarget(' "$1" && grep -qF 'FolderActions.renameTarget(' "$2" && grep -qF 'if (to.exists()) return GitOpResult(false' "$1" || { echo "    rename does not name through renameTarget / refuse an existing destination"; return 1; }; }
h1() { python3 - "$SRC/sync" <<'PY'
import os, re, sys
d = sys.argv[1]; rd = lambda f: open(os.path.join(d, f)).read()
bad = 0
t = rd("FolderTableUi.kt")
for need in ("RepoTableHeader(", "RepoCells(", "RepoStatusCell(", "FolderTable.rowOf("):
    if need not in t: print("    FolderRepoTable lacks", need); bad = 1
if "FolderRepoTable(" not in rd("ParentlessPanel.kt"): print("    Parentless is not the folder table"); bad = 1
if not re.search(r"fun UnlistedPanel\([^{]*\{[^}]*FolderRepoTable\(", rd("GitDepthUi.kt"), re.S): print("    Unlisted is not the folder table"); bad = 1
ui = rd("RepoTableUi.kt")
if not re.search(r"fun RepoTableHeader\(.*?RepoTable\.COLUMNS\.forEach", ui, re.S): print("    the header does not iterate RepoTable.COLUMNS"); bad = 1
for f in os.listdir(d):
    if f.endswith(".kt") and re.search(r"RepoColumn\.entries\.forEach", rd(f)): print("    a second header loop in", f); bad = 1
if "val COLUMNS: List<RepoColumn> get() = RepoTable.COLUMNS" not in rd("RepoTable.kt"): print("    FolderTable.COLUMNS is not RepoTable.COLUMNS"); bad = 1
sys.exit(bad)
PY
}

echo "── K kinds ──"
k1 "$BJ" "$COORD" "$DECL" && pass "declared folder kinds == FOLDER_KINDS == handlers" || fail "folder kinds drift"
echo "── R runner ──"
r1 "$COORD" "$DECL" "$RULES" && pass "depth runner, Activity Logs, no remote, no glob, refusing move" || fail "runner"
echo "── C rename ──"
c1 "$COORD" "$SRC/sync/LocalRepos.kt" && pass "rename names via ___CONFLICT and never overwrites" || fail "rename conflict"
echo "── H header parity ──"
h1 && pass "Parentless/Unlisted use the main header, cells and status icons" || fail "header parity"
echo "── JVM ──"
grep -q 'header' "$TESTS/sync/FolderActionsTest.kt" && grep -q '___CONFLICT3' "$TESTS/sync/FolderActionsTest.kt" && pass "JVM FolderActionsTest (conflict, archive, guard, parity)" || fail "missing FolderActionsTest"

echo "── M mutation-proof ──"
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["sync"]["git"]["folder_actions"].append({"id":"x","kind":"bogus"}); json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/b.json"
k1 "$TMP/b.json" "$COORD" "$DECL" >/dev/null && fail "K1 passed an undeclared kind (vacuous)" || pass "bogus kind → K1 RED"
sed 's/Declarations.FOLDER_ARCHIVE -> archiveFolder(t)//' "$COORD" > "$TMP/c.kt"; k1 "$BJ" "$TMP/c.kt" "$DECL" >/dev/null && fail "K1 passed a missing handler (vacuous)" || pass "handler removed → K1 RED"
sed 's/|| exit 17//' "$RULES" > "$TMP/r.kt"; r1 "$COORD" "$DECL" "$TMP/r.kt" >/dev/null && fail "R1 passed a merging move (vacuous)" || pass "refusal removed → R1 RED"
k1 "$BJ" "$COORD" "$DECL" >/dev/null && r1 "$COORD" "$DECL" "$RULES" >/dev/null && h1 >/dev/null && pass "unmutated tree is still green" || fail "the unmutated tree is red"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-git-folder-actions: all checks passed"; else echo "test-drive-git-folder-actions: $FAILURES check(s) FAILED"; exit 1; fi
