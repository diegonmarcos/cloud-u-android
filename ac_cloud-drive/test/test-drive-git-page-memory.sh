#!/usr/bin/env bash
# #1356 THE GIT PAGE KEEPS ITS EXACT STATE across a bottom-nav switch and a process death (open repo sheet and its sub-menu,
# settings sheet, status window, Logs and its tab, search, Filters sheet, list position; the filter selection is the
# coordinator's, already persisted). Explorer keeps its list position per pane and folder the same way.
#   S1 the store: GitPageMemoryStore lives in an activity ViewModel that reads and writes SavedStateHandle; MainActivity provides it.
#   S2 the Git page: every piece of page state starts from the store (never a bare "" / false) and one snapshotFlow writes it back;
#      the list scrolls on the remembered LazyListState; the sheet reopens on its sub-menu.
#   S3 Explorer: EntryList's list state comes from the store, keyed by pane and folder.
#   M  mutations: each of the above, broken once, turns its check red; the unmutated tree is green.
# GitPageMemoryTest (JVM) proves the store's behaviour: open a repo, navigate away and back, the same repo is open.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
SRC="$ROOT/ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive"
MEM="$SRC/sync/GitPageMemory.kt"; PAGE="$SRC/sync/GitReposScreen.kt"; ACT="$SRC/MainActivity.kt"; FILES="$SRC/files/FilesScreen.kt"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

check() { python3 - "$1" "$2" "$3" "$4" <<'PY'
import re, sys
mem, page, act, files = (open(p, encoding="utf-8").read() for p in sys.argv[1:5])
bad = []
# S1
if "class GitPageViewModel(handle: SavedStateHandle) : ViewModel()" not in mem: bad.append("S1 no activity ViewModel over SavedStateHandle")
if "handle.setSavedStateProvider(KEY)" not in mem or "GitPageMemoryStore.decode(handle.get<Bundle>(KEY)" not in mem: bad.append("S1 the store is not saved to / restored from SavedStateHandle")
if "by viewModels()" not in act or "LocalGitPageMemory provides gitPage.store" not in act: bad.append("S1 MainActivity does not provide the activity-scoped store")
# S2
for v in ("settingsFor", "statusFor", "showActivity", "logsTab", "expanded", "panelRepo", "panel", "query", "showFilters"):
    if not re.search(r"var %s by remember \{ mutableStateOf\(memory\.%s\) \}" % (v, v), page): bad.append("S2 %s does not start from the store" % v)
if "memoryStore.put(memoryKey, it)" not in page or "androidx.compose.runtime.snapshotFlow" not in page: bad.append("S2 the page never writes its state back")
if "rememberLazyListState(memory.scrollIndex, memory.scrollOffset)" not in page or "state = listState," not in page: bad.append("S2 the list position is not remembered")
if 'initialPanel = if (panelRepo == e.cloned.id) panel else ""' not in page or "mutableStateOf(initialPanel)" not in page: bad.append("S2 the sheet does not reopen on its sub-menu")
# S3
if "memoryStore.get(scrollKey)" not in files or "memoryStore.put(scrollKey" not in files: bad.append("S3 Explorer's list position is not kept")
print("\n".join("    " + b for b in bad)); sys.exit(1 if bad else 0)
PY
}

echo "== S1-S3 the Git page and Explorer keep their state =="
check "$MEM" "$PAGE" "$ACT" "$FILES" && pass "store in an activity ViewModel + SavedStateHandle; the Git page and Explorer read and write it" || fail "the page state is not kept"

echo "== M mutations =="
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
mut() { # <label> <file var> <python replace old> <new>
    local label=$1 which=$2 old=$3 new=$4 m="$MEM" p="$PAGE" a="$ACT" f="$FILES"
    local src; case $which in mem) src=$MEM;; page) src=$PAGE;; act) src=$ACT;; files) src=$FILES;; esac
    python3 -c 'import sys; s=open(sys.argv[1]).read(); assert sys.argv[2] in s, "mutation anchor gone"; open(sys.argv[4],"w").write(s.replace(sys.argv[2], sys.argv[3]))' "$src" "$old" "$new" "$tmp/$which.kt" || { fail "mutation '$label' has no anchor"; return; }
    case $which in mem) m=$tmp/$which.kt;; page) p=$tmp/$which.kt;; act) a=$tmp/$which.kt;; files) f=$tmp/$which.kt;; esac
    check "$m" "$p" "$a" "$f" >/dev/null && fail "M $label stayed green (vacuous)" || pass "M $label → RED"
}
mut "the open repo back to a composable-local remember" page 'var expanded by remember { mutableStateOf(memory.expanded) }' 'var expanded by remember { mutableStateOf("") }'
mut "the page stops writing its state" page 'memoryStore.put(memoryKey, it)' 'Unit'
mut "the scroll position dropped" page 'rememberLazyListState(memory.scrollIndex, memory.scrollOffset)' 'rememberLazyListState()'
mut "no SavedStateHandle provider" mem 'handle.setSavedStateProvider(KEY)' 'run'
mut "MainActivity provides no store" act 'LocalGitPageMemory provides gitPage.store' 'LocalGitPageMemory provides com.diegonmarcos.clouddrive.sync.GitPageMemoryStore()'
mut "Explorer forgets its position" files 'memoryStore.get(scrollKey)' 'com.diegonmarcos.clouddrive.sync.GitPageMemory()'
check "$MEM" "$PAGE" "$ACT" "$FILES" >/dev/null && pass "the unmutated tree is still green" || fail "the unmutated tree is red"

[ "$FAILURES" -eq 0 ] && { echo "test-drive-git-page-memory: all green"; exit 0; } || { echo "test-drive-git-page-memory: $FAILURES failure(s)"; exit 1; }
