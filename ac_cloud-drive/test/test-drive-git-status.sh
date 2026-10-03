#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #850 — SYNC ▸ GIT: every row ENDS. One bounded, cached, per-repository    ║
# ║ status reader; Force pull all / per row; Auto pull on open (Wi-Fi only)   ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. Every row of the public group sat on "reading…" for ever:
# GitSyncCoordinator.refresh() read every clone's status serially in ONE block,
# with no bound, and published nothing until all had returned — one clone whose
# status never came back held every row. The JVM suite (GitStatusReaderTest)
# executes the reader against a hanging fixture; this file pins the WIRING the
# JVM suite cannot see, so the serial unbounded read cannot creep back.
#
#   S1  the coordinator reads status ONLY through the one reader: refresh()
#       asks statusReader.request, and no e.status() glance loop is left in it.
#   S2  the reader is bounded and per-repository: withTimeoutOrNull, a
#       Semaphore, an in-flight claim, and a probe on its own thread.
#   S3  the bounds and the auto-pull setting are DATA: ui.sync.git.status
#       (timeout_seconds, concurrency, ttl_seconds) and ui.sync.git.auto_pull
#       (default_on, require_unmetered_network), parsed by Declarations.
#   S4  the page asks once per entry (LaunchedEffect(Unit) → onPageOpened) and
#       offers Force pull all (confirmed), Pull all and the Auto pull toggle.
#   S5  the JVM suite holds the hanging-status fixture ending in error(timeout).
#   S6  /api/git/status and /api/git/pull drive the page's own reader/pullAll.
#   M   mutants: the timeout removed → S2 red; the serial read restored in
#       refresh → S1 red; auto_pull dropped from build.json → S3 red; the
#       page-open effect keyed on state (restarting on recomposition) → S4 red.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
COORD="$SRC/sync/GitSyncCoordinator.kt"
READER="$SRC/sync/GitStatusReader.kt"
PAGE="$SRC/sync/GitReposScreen.kt"
DECL="$SRC/Declarations.kt"
API="$SRC/debugapi/DriveDebugApi.kt"
JVM="$APP/app/src/test/java/com/diegonmarcos/clouddrive/sync/GitStatusReaderTest.kt"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

s1() { python3 - "$1" <<'PY'
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"fun refresh\(.*?\n    }\n", src, re.S)
if not m: print("    refresh() not found"); sys.exit(1)
body = m.group(0)
if "statusReader.request(" not in body: print("    refresh() does not ask the one status reader"); sys.exit(1)
if re.search(r"\.status\(\)", body): print("    refresh() reads git status itself again"); sys.exit(1)
if "val statusReader: GitStatusReader by lazy" not in src: print("    no process-wide reader"); sys.exit(1)
sys.exit(0)
PY
}
s2() { for t in 'withTimeoutOrNull(timeoutMs)' 'Semaphore(' 'inFlight' 'isDaemon = true' 'State.ERROR' 'State.NOT_CLONED'; do grep -qF "$t" "$1" || { echo "    reader lacks $t"; return 1; }; done; }
s3() { python3 - "$1" "$2" <<'PY'
import json, sys
g = json.load(open(sys.argv[1]))["ui"]["sync"]["git"]
st, ap = g.get("status"), g.get("auto_pull")
bad = 0
if not isinstance(st, dict) or not all(isinstance(st.get(k), int) and st[k] > 0 for k in ("timeout_seconds", "concurrency")) or not isinstance(st.get("ttl_seconds"), int):
    print("    ui.sync.git.status must declare timeout_seconds, concurrency, ttl_seconds"); bad = 1
if not isinstance(ap, dict) or ap.get("require_unmetered_network") is not True or not isinstance(ap.get("default_on"), bool):
    print("    ui.sync.git.auto_pull must declare default_on and require_unmetered_network: true (Wi-Fi only)"); bad = 1
d = open(sys.argv[2]).read()
for t in ('o["status"]', 'o["auto_pull"]', '"timeout_seconds"', '"require_unmetered_network"'):
    if t not in d: print("    Declarations does not parse " + t); bad = 1
sys.exit(bad)
PY
}
s4() {
    grep -qE '^    LaunchedEffect\(Unit\) \{ coordinator\.onPageOpened\(\) \}' "$1" || { echo "    the page does not ask once per entry"; return 1; }
    [ "$(grep -c 'onPageOpened()' "$1")" = 1 ] || { echo "    onPageOpened called more than once"; return 1; }
    for t in 'coordinator.pullAll(force = true)' 'R.string.git_confirm_force_pull_all' 'coordinator.setAutoPullOnOpen(' 'R.string.git_pull_all_progress'; do
        grep -qF "$t" "$1" || { echo "    the page lacks $t"; return 1; }; done
}

echo "── S1 one reader ──";   s1 "$COORD" && pass "refresh() asks the one process-wide reader; no serial status loop" || fail "the serial unbounded read is back"
echo "── S2 bounded ──";      s2 "$READER" && pass "timeout, limited concurrency, in-flight claim, probe off-thread, terminal states" || fail "the reader is not bounded"
echo "── S3 declared ──";     s3 "$BJ" "$DECL" && pass "status bounds and auto pull are declared and parsed" || fail "status/auto_pull not declared as data"
echo "── S4 page ──";         s4 "$PAGE" && pass "asked once per entry; Force pull all (confirmed), Pull all, Auto pull toggle, progress" || fail "page wiring missing"
echo "── S5 JVM ──"
grep -qF 'fun hangingStatusEndsInTimeoutErrorAndDoesNotHoldTheOthers()' "$JVM" && grep -qF 'reason!!.startsWith("timeout")' "$JVM" \
    && pass "the JVM suite holds the hanging fixture ending in error(timeout)" || fail "no hanging-status fixture"
grep -qF 'fun aStuckProbeIsNeverStartedTwice()' "$JVM" && pass "the JVM suite proves a stuck probe is never restarted" || fail "no stuck-probe test"
echo "── S6 debug routes ──"
grep -qF '"status" -> statusJson(ctx, query)' "$API" && grep -qF 'GitSyncCoordinator.statusReader.statuses' "$API" && grep -qF '"pull" -> pullJson(ctx, query)' "$API" && grep -qF 'c.pullAll(force = force' "$API" \
    && pass "/api/git/status and /api/git/pull reuse the page's reader and pullAll" || fail "debug routes missing or re-implemented"

echo "── M mutation-proof ──"
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
sed 's/withTimeoutOrNull(timeoutMs)/run/' "$READER" > "$TMP/r.kt"; cmp -s "$READER" "$TMP/r.kt" && fail "reader mutation changed nothing"
s2 "$TMP/r.kt" >/dev/null && fail "S2 passed an unbounded reader (vacuous)" || pass "timeout removed → S2 RED"
python3 - "$COORD" "$TMP/c.kt" <<'PY'
import sys
s = open(sys.argv[1]).read()
s = s.replace("statusReader.request(list.map", "list.forEach { GitEngine(File(it.path)).use { e -> e.status() } }; statusReader.request(list.map", 1)
open(sys.argv[2], "w").write(s)
PY
s1 "$TMP/c.kt" >/dev/null && fail "S1 passed a serial read in refresh (vacuous)" || pass "serial status read restored in refresh → S1 RED"
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); del b["ui"]["sync"]["git"]["auto_pull"]; json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/b.json"
s3 "$TMP/b.json" "$DECL" >/dev/null && fail "S3 passed with no auto_pull (vacuous)" || pass "auto_pull dropped → S3 RED"
sed 's/LaunchedEffect(Unit) { coordinator.onPageOpened() }/LaunchedEffect(repos) { coordinator.onPageOpened() }/' "$PAGE" > "$TMP/p.kt"; cmp -s "$PAGE" "$TMP/p.kt" && fail "page mutation changed nothing"
s4 "$TMP/p.kt" >/dev/null && fail "S4 passed a page-open keyed on state (vacuous)" || pass "page-open effect keyed on state → S4 RED"
s1 "$COORD" >/dev/null && s2 "$READER" >/dev/null && s3 "$BJ" "$DECL" >/dev/null && s4 "$PAGE" >/dev/null && pass "unmutated tree is still green" || fail "the unmutated tree is red"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-git-status: all checks passed"; else echo "test-drive-git-status: $FAILURES check(s) FAILED"; exit 1; fi
