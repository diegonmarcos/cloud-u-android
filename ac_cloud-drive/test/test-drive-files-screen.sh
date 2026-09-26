#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #579 — FILES is the X-plore replacement: two real panes with tabs, a     ║
# ║ collapsible tree, archives browsed as folders, copy/move between panes  ║
# ║ with live progress, a selection bar, bookmarks, breadcrumbs, sort/filter,║
# ║ a storage bar and the shared store first — and the layout tree is pinned ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
#   F1  the pane's layout tree, IN ORDER inside `private fun Pane(`: TabStrip →
#       Breadcrumbs → PaneToolbar → StorageBar → (Loading|Error|Empty|EntryList);
#       every element carries its DriveTags tag.
#   F2  dual pane: PaneId.A and PaneId.B are both composed; the inactive pane
#       collapses to PaneHeader in portrait; side by side at ≥ 600.dp; the toggle
#       persists (DrivePrefs.setDualPane).
#   F3  tree view: ViewMode.TREE, treeRows over the pane's expanded set, a chevron
#       toggling FilesReducer.toggleExpanded, indent per depth.
#   F4  archives as folders: Location.Archive, ArchiveFs.list, entering a zip from
#       the row tap, extraction through the Zip-Slip guard BEFORE any write.
#   F5  the selection bar: copy/move → other pane with progress (transferSelection,
#       ProgressCard from controller.jobs), delete, zip, rename (single / pattern),
#       share, properties, select all, invert; read-only inside an archive.
#   F6  bookmarks, breadcrumbs, sort/filter from the declaration, storage bar,
#       Places sheet with the shared store hero first, search with cancel.
#   F7  the IO core has no Android import (pure, JVM-tested); the reducers are pure
#       and Serializable (process-death survival); the JVM tests name them.
#   M   mutation-proof: a crumb row dropped from Pane → F1 red; the guard call
#       dropped from extract → F4 red; the unmutated tree stays green.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
SCREEN="$SRC/files/FilesScreen.kt"
CTRL="$SRC/files/FilesController.kt"
STATE="$SRC/files/FilesState.kt"
OPS="$SRC/files/FileOps.kt"
ARCH="$SRC/files/ArchiveFs.kt"
PLACES="$SRC/files/Places.kt"
CHROME="$SRC/ui/Chrome.kt"
TESTS="$APP/app/src/test/java/com/diegonmarcos/clouddrive"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$SCREEN" "$CTRL" "$STATE" "$OPS" "$ARCH" "$PLACES" "$CHROME"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

# f1 <FilesScreen.kt> : the Pane composable composes its tree in the declared order
f1() {
    python3 - "$1" <<'PYTHON'
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"private fun Pane\((.*?)\n\}\n", src, re.S)
if not m: print("    no `private fun Pane(`"); sys.exit(1)
body = m.group(1)
order = ["TabStrip(", "Breadcrumbs(", "PaneToolbar(", "StorageBar(", "LoadingState(", "ErrorState(", "EmptyState(", "EntryList("]
pos = [body.find(o) for o in order]
if any(p < 0 for p in pos): print("    missing in Pane: %s" % [o for o, p in zip(order, pos) if p < 0]); sys.exit(1)
if pos != sorted(pos): print("    Pane composes out of order: %s" % list(zip(order, pos))); sys.exit(1)
# #603 FILES_STORAGE_BAR is applied by the chrome's shared StorageBar (ui/Chrome.kt), which
# Home draws too; the pane's own StorageBar delegates to it, and the delegation is asserted
# in F6. Every other tag is still this screen's to apply.
tags = ["FILES_PANE_A", "FILES_PANE_B", "FILES_TAB_STRIP", "FILES_BREADCRUMBS", "FILES_TOOLBAR", "FILES_LIST", "FILES_ROW", "FILES_SELECTION_BAR", "FILES_PLACES_SHEET", "FILES_SEARCH_BAR", "FILES_PANE_HEADER"]
missing = [t for t in tags if ("DriveTags.%s" % t) not in src]
if missing: print("    tags never applied: %s" % missing); sys.exit(1)
PYTHON
}

# f4 <FileOps.kt> : extract validates EVERY entry through zipEntryTarget before the first write
f4() {
    python3 - "$1" <<'PYTHON'
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"fun extract\(.*?\n    \}\n", src, re.S)
if not m: print("    no extract()"); sys.exit(1)
body = m.group(0)
guard = body.find("zipEntryTarget(destination, rel) == null) throw")
write = body.find("FileOutputStream(target)")
if guard < 0 or write < 0 or guard > write: print("    the Zip-Slip guard does not run over every entry before the first write"); sys.exit(1)
if "fun zipEntryTarget(destination: File, entryName: String): File?" not in src: print("    zipEntryTarget is not the pure top-level function"); sys.exit(1)
PYTHON
}

echo "── F1 the pane's layout tree ──"
f1 "$SCREEN" && pass "Pane composes TabStrip → Breadcrumbs → PaneToolbar → StorageBar → list states, every element tagged" || fail "the pane's tree is not the declared one"
for t in FILES_PANE_A FILES_TAB_STRIP FILES_BREADCRUMBS FILES_TOOLBAR FILES_STORAGE_BAR FILES_LIST FILES_ROW FILES_SELECTION_BAR FILES_PLACES_SHEET FILES_SEARCH_BAR; do
    grep -qE "const val $t = \"files_[a-z_]+\"" "$CHROME" && pass "DriveTags.$t declared" || fail "DriveTags.$t not declared"
done

echo "── F2 two real panes ──"
if grep -qE 'paneContent\(PaneId\.A, Modifier\.weight\(1f\)\.fillMaxHeight\(\)\)' "$SCREEN" && grep -qE 'paneContent\(PaneId\.B, Modifier\.weight\(1f\)\.fillMaxHeight\(\)\)' "$SCREEN"; then pass "both panes composed side by side"; else fail "no side-by-side composition of pane A and B"; fi
if grep -qE 'val wide = maxWidth >= 600\.dp' "$SCREEN" && grep -qE 'PaneHeader\(ui\.otherId, ui, listings, controller\)' "$SCREEN"; then pass "portrait collapses the inactive pane to a header; ≥600dp goes side by side"; else fail "the portrait / wide layouts are not both there"; fi
if grep -qE 'fun setDual\(dual: Boolean\)' "$CTRL" && grep -qE 'prefs\.setDualPane\(dual\)' "$CTRL" && grep -qE 'fun toggleDual\(s: FilesUiState\)' "$STATE"; then pass "the dual toggle is a reducer and persists"; else fail "dual pane toggle not persisted"; fi
if grep -qE 'val otherId: PaneId' "$STATE" && grep -qE 'transferSelection\(ui\.active, otherLocal, move = false\)' "$SCREEN" && grep -qE 'transferSelection\(ui\.active, otherLocal, move = true\)' "$SCREEN"; then pass "copy → other pane and move → other pane target the OTHER pane's folder"; else fail "cross-pane copy/move missing"; fi

echo "── F3 tree view ──"
if grep -qE 'enum class ViewMode \{ LIST, TREE \}' "$STATE" && grep -qE 'fun toggleExpanded\(' "$STATE" && grep -qE 'private fun treeRows\(' "$SCREEN" && grep -qE 'e\.key in pane\.expanded' "$SCREEN" && grep -qE 'DriveMetrics\.treeIndent \* depth' "$SCREEN"; then pass "tree mode: expanded set, lazily walked rows, indent per depth"; else fail "tree view incomplete"; fi
if grep -qE 'IconButton\(onClick = onToggleExpand' "$SCREEN"; then pass "a folder's chevron toggles expansion"; else fail "no chevron toggle"; fi

echo "── F4 archives as folders ──"
if grep -qE 'data class Archive\(val zipPath: String, val inner: String = ""\) : Location\(\)' "$STATE" && grep -qE 'fun list\(location: Location\.Archive' "$ARCH" && grep -qE 'is Location\.Archive -> Listing\(ArchiveFs\.list\(loc, mimes\)' "$CTRL"; then pass "an archive is a Location and lists through ArchiveFs"; else fail "archive browsing not wired"; fi
if grep -qE 'controller\.open\(pane, Location\.Archive\(local\.path\)\)' "$SCREEN"; then pass "tapping a zip enters it as a folder"; else fail "a zip does not open as a folder"; fi
f4 "$OPS" && pass "extract runs the Zip-Slip guard over every entry BEFORE the first write; the guard is pure" || fail "extraction is not guarded first"
if grep -qE 'onlyUnder = prefixes' "$CTRL" && grep -qE 'files_selected_readonly' "$SCREEN"; then pass "copy out of an archive extracts the selection; the bar says read-only inside"; else fail "archive selection semantics missing"; fi

echo "── F5 the selection bar and its jobs ──"
for verb in 'fun transferSelection\(from: PaneId, destination: Location\.Local, move: Boolean\)' 'fun deleteSelection\(id: PaneId\)' 'fun zipSelection\(id: PaneId, archiveName: String\)' 'fun extractHere\(entry: FileOps\.Entry\)' 'fun applyRenamePlan\(' 'fun cancel\(jobId: Long\)'; do
    grep -qE "$verb" "$CTRL" && pass "controller: $verb" || fail "controller lacks $verb"
done
if grep -qE 'jobs\.forEach \{ job ->' "$SCREEN" && grep -qE 'ProgressCard\(job\.title, job\.detail' "$SCREEN" && grep -qE 'FileOps\.NotEnoughSpace' "$CTRL"; then pass "every running job is a ProgressCard with Cancel; the free-space refusal has its own sentence"; else fail "transfer progress not rendered from controller.jobs"; fi
if grep -qE 'FilesReducer\.selectAll' "$SCREEN" && grep -qE 'FilesReducer\.invert' "$SCREEN" && grep -qE 'BulkRenameDialog\(' "$SCREEN" && grep -qE 'detectDragGesturesAfterLongPress' "$SCREEN"; then pass "select all / invert / rename by pattern / drag range-select"; else fail "selection verbs incomplete"; fi
if grep -qE 'Modifier\.combinedClickable\(onClick = onTap, onLongClick = onLongPress\)' "$SCREEN"; then pass "long-press starts a selection"; else fail "no long-press selection"; fi

echo "── F6 bookmarks, crumbs, sort/filter, storage bar, places, search ──"
if grep -qE 'controller\.prefs\.toggleBookmark' "$SCREEN" && grep -qE 'fun toggleBookmark\(path: String\)' "$SRC/DrivePrefs.kt" && grep -qE 'Places\.bookmarks\(prefs\)' "$SCREEN"; then pass "bookmarks: toggle from the toolbar/tab, listed in the Places sheet"; else fail "bookmarks incomplete"; fi
if grep -qE 'fun crumbs\(\): List<Location>' "$STATE" && grep -qE 'Places\.rootLabel\(ctx, it\.path\)' "$SCREEN"; then pass "breadcrumbs from Location.crumbs, volume roots named by their place"; else fail "breadcrumbs incomplete"; fi
if grep -qE 'Declarations\.files\.sortKeys\.forEach' "$SCREEN" && grep -qE 'Declarations\.files\.filters\.forEach' "$SCREEN" && grep -qE 'fun visibleEntries\(pane: PaneState, entries: List<FileOps\.Entry>, filters: List<Declarations\.FilterDecl>\)' "$CTRL"; then pass "sort keys and filters are the declared ones, applied by one pure function"; else fail "sort/filter not from the declaration"; fi
if grep -qE 'private fun StorageBar\(usage: Pair<Long, Long>\)' "$SCREEN" && grep -qE 'if \(listing\?\.usage != null && rootLabel != null\) StorageBar\(listing\.usage\)' "$SCREEN"; then pass "the storage bar shows at a volume or store root only"; else fail "storage bar rule missing"; fi
# #603 ONE storage bar in the fleet's chrome, drawn by both the pane and Home: the pane's
# private wrapper only supplies this pane's caption, and the tag lives with the component.
if grep -qE 'com\.diegonmarcos\.clouddrive\.ui\.StorageBar\(' "$SCREEN" && grep -qE 'fun StorageBar\(usage: Pair<Long, Long>, label: String' "$CHROME" && grep -qE 'tag: String = DriveTags\.FILES_STORAGE_BAR' "$CHROME"; then pass "the pane delegates to the chrome's ONE StorageBar, which carries the tag"; else fail "the storage bar is not the chrome's shared component"; fi
# #603 the sheet is sectioned (ui.files.sections): one header per declared section, and the
# store is still the HERO card — now under its own Cloud-Drive-Storage header.
if grep -qE '"shared_root" -> SharedStore\.root\(\)' "$PLACES" && grep -qE 'Declarations\.files\.sections\.forEach \{ section ->' "$SCREEN" && grep -qE 'declared\.filter \{ it\.section == section\.id \}' "$SCREEN" && grep -qE 'if \(p\.hero\) item \{ DriveCard\(' "$SCREEN" && grep -qE 'hero = true' "$SCREEN"; then pass "the Places sheet is one header per declared section, with the shared store as its hero"; else fail "the sectioned Places sheet is not built from ui.files.sections"; fi
if grep -qE 'fun startSearch\(root: Location\.Local, query: String, contentToo: Boolean\)' "$CTRL" && grep -qE 'fun cancelSearch\(\)' "$CTRL" && grep -qE 'SEARCH_RESULT_CEILING = 300' "$OPS"; then pass "search is cancellable and capped"; else fail "search incomplete"; fi
if grep -qE 'Pane\(id, ui, controller, listings, prefs, isActive' "$SCREEN" && grep -qE 'controller\.activate\(id\)' "$SCREEN"; then pass "tapping a pane activates it"; else fail "pane activation missing"; fi

echo "── F7 pure core, saveable state, JVM tests ──"
if grep -qE '^import android\.' "$OPS" "$STATE" "$ARCH"; then fail "the IO core or the state model imports android.*"; else pass "FileOps, FilesState and ArchiveFs are pure Kotlin"; fi
if grep -qE '^@Serializable\s*$' "$STATE" && grep -qE 'data class FilesUiState\(' "$STATE" && grep -qE 'fun encode\(\): String' "$STATE" && grep -qE 'FilesUiState\.decode\(saved\.value\)' "$SRC/MainActivity.kt"; then pass "the Files state is Serializable and restored by MainActivity"; else fail "state not saveable/restored"; fi
for t in "$TESTS/files/FilesStateTest.kt" "$TESTS/files/FileOpsTest.kt"; do [ -f "$t" ] && pass "exists: ${t#$APP/}" || fail "missing: ${t#$APP/}"; done
if grep -qE 'FilesReducer\.back\(' "$TESTS/files/FilesStateTest.kt" && grep -qE 'FilesUiState\.decode\(text\)' "$TESTS/files/FilesStateTest.kt" && grep -qE 'zipEntryTarget\(dest, "\.\./x"\)' "$TESTS/files/FileOpsTest.kt" && grep -qE 'NotEnoughSpace' "$TESTS/files/FileOpsTest.kt" && grep -qE 'ArchiveFs\.list\(' "$TESTS/files/FileOpsTest.kt"; then pass "the JVM suite proves back, the saveable round trip, the guard, the refusal and archive browsing"; else fail "the JVM suite does not cover the core"; fi

echo "── M mutation-proof ──"
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
grep -v '^        Breadcrumbs(id, loc, controller)$' "$SCREEN" > "$TMP/no-crumbs.kt"
cmp -s "$SCREEN" "$TMP/no-crumbs.kt" && fail "the crumbs mutation changed nothing (tester is stale)"
f1 "$TMP/no-crumbs.kt" >/dev/null && fail "F1 passed a pane without breadcrumbs (tester is vacuous)" || pass "breadcrumbs dropped from Pane → F1 RED"
python3 - "$SCREEN" "$TMP/swapped.kt" <<'PYTHON'
import sys
s = open(sys.argv[1], encoding="utf-8").read()
s = s.replace("        TabStrip(id, pane, controller, onOpenPlaces)\n        Breadcrumbs(id, loc, controller)\n", "        Breadcrumbs(id, loc, controller)\n        TabStrip(id, pane, controller, onOpenPlaces)\n")
open(sys.argv[2], "w", encoding="utf-8").write(s)
PYTHON
cmp -s "$SCREEN" "$TMP/swapped.kt" && fail "the swap mutation changed nothing (tester is stale)"
f1 "$TMP/swapped.kt" >/dev/null && fail "F1 passed a pane with crumbs above the tab strip (tester is vacuous)" || pass "tab strip and crumbs swapped → F1 RED"
sed 's/if (rel != null \&\& zipEntryTarget(destination, rel) == null) throw/if (false) throw/' "$OPS" > "$TMP/unguarded.kt"
cmp -s "$OPS" "$TMP/unguarded.kt" && fail "the guard mutation changed nothing (tester is stale)"
f4 "$TMP/unguarded.kt" >/dev/null && fail "F4 passed an unguarded extract (tester is vacuous)" || pass "Zip-Slip guard dropped → F4 RED"
f1 "$SCREEN" >/dev/null && f4 "$OPS" >/dev/null && pass "unmutated tree is still green" || fail "the unmutated tree is red"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-files-screen: all checks passed"; else echo "test-drive-files-screen: $FAILURES check(s) FAILED"; exit 1; fi
