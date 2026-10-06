#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #875 — Files ▸ "Copy path": every row, the selection bar and the crumb   ║
# ║ copy the folder's path through ONE PathFormatter                         ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
#   C1  the action is DECLARED: build.json ui.files.row_actions[copy_path] has
#       label, icon (known to IconCatalog) and a snack that carries no path.
#   C2  PathFormatter is the one formatter, pure (no android import), with the
#       three cases: Local → absolute path, Saf → content URI, Rclone → remote:path.
#   C3  the screen offers it three ways: the row overflow menu, the selection
#       bar's menu, and tap-hold on a crumb — all from the declaration, all through
#       copyPath → actions.copyText(PathFormatter...).
#   C4  the snack is the declared confirmation and never echoes the value.
#   C5  dense Compose: the menu icon is sized by DriveMetrics, no dp/sp literal.
#   C6  the JVM suite names the three cases and the declaration.
#   M   mutation-proof: formatter case dropped, snack echoing the path, crumb
#       long-press removed, declaration removed → each RED; unmutated stays green.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
SCREEN="$SRC/files/FilesScreen.kt"
FMT="$SRC/files/PathFormatter.kt"
BUILD="$APP/build.json"
ICONS="$SRC/ui/IconCatalog.kt"
TESTS="$APP/app/src/test/java/com/diegonmarcos/clouddrive"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$SCREEN" "$FMT" "$BUILD" "$ICONS" "$TESTS/files/PathFormatterTest.kt"; do
    [ -f "$required" ] || { echo "ERROR missing: $required — this tester is unrun, not passing"; exit 1; }
done

# c1 <build.json> <IconCatalog.kt>
c1() {
    python3 - "$1" "$2" <<'PYTHON'
import json, re, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
acts = d.get("ui", {}).get("files", {}).get("row_actions", [])
a = next((x for x in acts if x.get("id") == "copy_path"), None)
if not a: print("    ui.files.row_actions has no copy_path"); sys.exit(1)
for k in ("label", "icon", "snack"):
    if not a.get(k): print("    copy_path has no %s" % k); sys.exit(1)
if re.search(r"[/:]|%s|\{", a["snack"]): print("    the snack carries a path-like or templated value: %r" % a["snack"]); sys.exit(1)
if '"%s" ->' % a["icon"] not in open(sys.argv[2], encoding="utf-8").read(): print("    icon %r is not in IconCatalog" % a["icon"]); sys.exit(1)
PYTHON
}

# c2 <PathFormatter.kt>
c2() {
    python3 - "$1" <<'PYTHON'
import re, sys
s = open(sys.argv[1], encoding="utf-8").read()
if re.search(r"^import android\.", s, re.M): print("    PathFormatter imports android.*"); sys.exit(1)
need = [r"is Target\.Local -> target\.path\.trimEnd\('/'\)", r"is Target\.Saf -> target\.uri\.trim\(\)", r'is Target\.Rclone -> .*\+ ":" \+', r"fun forLocation\(location: Location\)", r"fun forLocations\("]
miss = [n for n in need if not re.search(n, s)]
if miss: print("    PathFormatter lacks: %s" % miss); sys.exit(1)
PYTHON
}

# c3 <FilesScreen.kt>
c3() {
    python3 - "$1" <<'PYTHON'
import re, sys
s = open(sys.argv[1], encoding="utf-8").read()
checks = {
 "copyPath goes through the formatter and the clipboard": r"actions\.copyText\(PathFormatter\.forLocations\(locs\)\)",
 "row menu item": r"onClick = \{ menu = false; onMenu\(EntryAction\.COPY_PATH\) \}",
 "row action handled": r"EntryAction\.COPY_PATH -> \{ copyPath\(listOf\(e\.location\)\); null \}",
 "selection bar item": r"onClick = \{ more = false; onCopyPath\(selected\) \}",
 "selection bar wired": r"onCopyPath = \{ entries -> copyPath\(entries\.map \{ it\.location \}\) \}",
 "crumb tap-hold": r"onLongClick = if \(copyDeclared\) \(\{ onCopyPath\(crumb\) \}\) else null",
 "pane wires the crumb": r"onCopyPath = \{ loc -> copyPath\(listOf\(loc\)\) \}",
 "menu entries come from the declaration": r"Declarations\.files\.rowAction\(COPY_PATH\)\?\.let \{ a -> DropdownMenuItem\(text = \{ Text\(a\.label\) \}",
}
bad = [k for k, v in checks.items() if not re.search(v, s)]
if bad: print("    missing: %s" % bad); sys.exit(1)
if len(re.findall(r"Declarations\.files\.rowAction\(COPY_PATH\)\?\.let \{ a -> DropdownMenuItem", s)) < 2: print("    both menus must be declaration-driven"); sys.exit(1)
PYTHON
}

# c4 <FilesScreen.kt> : the snack is the declared text, never the path
c4() {
    python3 - "$1" <<'PYTHON'
import re, sys
s = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"val copyPath: \(List<Location>\) -> Unit = \{ locs ->(.*?)\n    \}\n", s, re.S)
if not m: print("    no copyPath lambda"); sys.exit(1)
b = m.group(1)
if "rowAction(COPY_PATH)?.snack" not in b: print("    the snack is not the declared one"); sys.exit(1)
shows = re.findall(r"showSnackbar\(([^)]*)\)", b)
if shows != ["msg"]: print("    showSnackbar is fed something other than the declared msg: %s" % shows); sys.exit(1)
PYTHON
}

echo "── C1 declared ──"
c1 "$BUILD" "$ICONS" && pass "ui.files.row_actions[copy_path]: label, icon known to IconCatalog, snack without a path" || fail "copy_path is not declared properly"
grep -q 'row_actions' "$SRC/Declarations.kt" && grep -q 'RowActionDecl' "$SRC/Declarations.kt" && pass "Declarations parses row_actions" || fail "Declarations does not parse row_actions"

echo "── C2 one pure formatter ──"
c2 "$FMT" && pass "PathFormatter: Local → path, Saf → content URI, Rclone → remote:path; pure" || fail "PathFormatter is incomplete"

echo "── C3 three entry points ──"
c3 "$SCREEN" && pass "row overflow, selection bar and crumb tap-hold all copy through PathFormatter" || fail "an entry point is missing"

echo "── C4 the snack ──"
c4 "$SCREEN" && pass "the snack is the declared 'Path copied' and never the value" || fail "the snack may echo the value"

echo "── C5 dense ──"
if grep -qE '[0-9]\.?[0-9]*\.(dp|sp)\b' <<<"$(grep -nE 'COPY_PATH|copyPath|PathFormatter' "$SCREEN")"; then fail "a dp/sp literal in the copy-path code"; else pass "no dp/sp literal; the icon is DriveMetrics.icon"; fi
grep -q 'IconCatalog.vectorOrDefault(a.icon), null, Modifier.size(DriveMetrics.icon)' "$SCREEN" && pass "menu glyph sized by the density token" || fail "menu glyph not sized by DriveMetrics"

echo "── C6 JVM suite ──"
T="$TESTS/files/PathFormatterTest.kt"
if grep -q 'Target.Local' "$T" && grep -q 'Target.Saf' "$T" && grep -q 'Target.Rclone' "$T" && grep -q 'content://' "$T" && grep -q 'gdrive:' "$T" && grep -q 'copy_path' "$TESTS/DeclarationsTest.kt"; then pass "PathFormatterTest names the three cases; DeclarationsTest names the declaration"; else fail "the JVM suite does not cover the three cases"; fi

echo "── M mutation-proof ──"
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
mut() { # mut <src> <out> <python replace expr: old> <new>
    python3 - "$1" "$2" "$3" "$4" <<'PYTHON'
import sys
s = open(sys.argv[1], encoding="utf-8").read()
if sys.argv[3] not in s: sys.exit(3)
open(sys.argv[2], "w", encoding="utf-8").write(s.replace(sys.argv[3], sys.argv[4], 1))
PYTHON
}
mut "$FMT" "$TMP/f1.kt" 'is Target.Saf -> target.uri.trim()' 'is Target.Saf -> "file://" + target.uri' || fail "SAF mutation changed nothing (stale)"
c2 "$TMP/f1.kt" >/dev/null && fail "C2 passed a SAF case that is not the content URI (vacuous)" || pass "SAF case altered → C2 RED"
printf 'import android.os.Build\n' | cat - "$FMT" > "$TMP/f2.kt"
c2 "$TMP/f2.kt" >/dev/null && fail "C2 passed an android import (vacuous)" || pass "android import in the formatter → C2 RED"
mut "$SCREEN" "$TMP/s1.kt" 'onLongClick = if (copyDeclared) ({ onCopyPath(crumb) }) else null' 'onLongClick = null' || fail "crumb mutation changed nothing (stale)"
c3 "$TMP/s1.kt" >/dev/null && fail "C3 passed a crumb without tap-hold (vacuous)" || pass "crumb tap-hold removed → C3 RED"
mut "$SCREEN" "$TMP/s2.kt" 'msg -> scope.launch { snackbar.showSnackbar(msg) }' 'msg -> scope.launch { snackbar.showSnackbar(PathFormatter.forLocations(locs)) }' || fail "snack mutation changed nothing (stale)"
c4 "$TMP/s2.kt" >/dev/null && fail "C4 passed a snack that echoes the path (vacuous)" || pass "snack echoing the path → C4 RED"
python3 - "$BUILD" "$TMP/b1.json" <<'PYTHON'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8")); d["ui"]["files"]["row_actions"] = []
json.dump(d, open(sys.argv[2], "w"))
PYTHON
c1 "$TMP/b1.json" "$ICONS" >/dev/null && fail "C1 passed an undeclared action (vacuous)" || pass "declaration removed → C1 RED"
python3 - "$BUILD" "$TMP/b2.json" <<'PYTHON'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8")); d["ui"]["files"]["row_actions"][0]["snack"] = "Copied /mnt/vol"
json.dump(d, open(sys.argv[2], "w"))
PYTHON
c1 "$TMP/b2.json" "$ICONS" >/dev/null && fail "C1 passed a snack with a path (vacuous)" || pass "snack with a path declared → C1 RED"
c1 "$BUILD" "$ICONS" >/dev/null && c2 "$FMT" >/dev/null && c3 "$SCREEN" >/dev/null && c4 "$SCREEN" >/dev/null && pass "unmutated tree is still green" || fail "the unmutated tree is red"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-files-copy-path: all checks passed"; else echo "test-files-copy-path: $FAILURES check(s) FAILED"; exit 1; fi
