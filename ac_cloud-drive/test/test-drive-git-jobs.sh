#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #1315 ONE EXECUTOR. Sync all / Fetch all / Clone all used to launch one   ║
# ║ coroutine per repository with no bound, beside two other runners with    ║
# ║ their own busy flags. Now every git and folder job is a GitJobs spec:   ║
# ║ bounded, locked per folder, exclusive, cancellable, accounted.          ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#   J1 declared: ui.sync.git.jobs.workers (1..4) parsed by Declarations (GitJobsDecl).
#   J2 one runner: the coordinator submits through GitJobs (pull all, the bulk verbs, the single ops, the clone, the depth and
#      folder commands) and no longer carries startDepth / bounded / its own bulk Semaphore; no `repos.value.forEach { run...`
#      fan-out survives.
#   J3 the executor is pure and tested: sync/jobs/GitJobs.kt binds nothing (no Context, no StoreExec) and GitJobsTest covers
#      the bound, the lock, the exclusivity, the cancel and the accounting.
#   M  mutations: a coordinator with `repos.value.forEach { runCompound(it, id) }` turns J2 red; workers 0 in build.json turns J1 red.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SYNC="$APP/app/src/main/java/com/diegonmarcos/clouddrive/sync"
C="$SYNC/GitSyncCoordinator.kt"
BJ="$APP/build.json"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

j1() { python3 -c 'import json,sys; w=json.load(open(sys.argv[1]))["ui"]["sync"]["git"]["jobs"]["workers"]; assert isinstance(w,int) and 1<=w<=4, w' "$1"; }
j2() {
  local f="$1" bad=0
  for t in 'jobs.submit(GitJobs.Spec(' 'val jobs: GitJobs' 'private fun bulkOps(' 'private fun single(' 'private fun <T> depth(' 'jobs.latest(*DEPTH_JOB_KINDS)' 'Declarations.sync.git.jobs.workers'; do
    grep -qF -- "$t" "$f" || { echo "    coordinator lacks $t"; bad=1; }
  done
  for t in 'fun startDepth(' 'CoroutineScope.bounded(' 'private var depthJob' 'bulkRunning = java.util.concurrent.atomic.AtomicBoolean' 'Semaphore(Declarations.sync.git.status.concurrency)'; do
    grep -qF -- "$t" "$f" && { echo "    coordinator still carries $t"; bad=1; }
  done
  grep -qE 'repos\.value\.forEach \{ (runOp|runCompound)' "$f" && { echo "    an unbounded fan-out survives"; bad=1; }
  return $bad
}

echo "── J1 declared ──"
j1 "$BJ" 2>/dev/null && pass "ui.sync.git.jobs.workers in 1..4" || fail "jobs.workers"
grep -q 'GitJobsDecl' "$APP/app/src/main/java/com/diegonmarcos/clouddrive/Declarations.kt" && grep -q 'o\["jobs"\]' "$APP/app/src/main/java/com/diegonmarcos/clouddrive/Declarations.kt" && pass "Declarations parses it" || fail "Declarations does not parse ui.sync.git.jobs"

echo "── J2 one runner ──"
if out=$(j2 "$C"); then pass "every job goes through GitJobs; the old runners are gone"; else fail "one runner:"; printf '%s\n' "$out"; fi

echo "── J3 pure and tested ──"
G="$SYNC/jobs/GitJobs.kt"
[ -f "$G" ] || fail "sync/jobs/GitJobs.kt missing"
if grep -qE 'android\.|StoreExec|\bContext\b' "$G"; then fail "GitJobs is not pure (Android / exec inside)"; else pass "GitJobs is pure Kotlin over coroutines"; fi
T="$APP/app/src/test/java/com/diegonmarcos/clouddrive/sync/jobs/GitJobsTest.kt"
for fx in "at most workers" "locked path is skipped" "exclusive job refuses" "cancel stops" "every outcome is accounted"; do grep -q -- "$fx" "$T" && pass "GitJobsTest: $fx" || fail "GitJobsTest lacks: $fx"; done

echo "── M mutations ──"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
printf '%s\n' "$(cat "$C")" '    fun rogue(id: String) { repos.value.forEach { runCompound(it, id) } }' > "$tmp/C.kt"
if j2 "$tmp/C.kt" >/dev/null 2>&1; then fail "J2 passed an unbounded fan-out (vacuous)"; else pass "unbounded fan-out turns J2 red"; fi
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["sync"]["git"]["jobs"]["workers"]=0; json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$tmp/b.json"
if j1 "$tmp/b.json" >/dev/null 2>&1; then fail "J1 passed workers 0 (vacuous)"; else pass "workers 0 turns J1 red"; fi

[ "$FAILURES" -eq 0 ] && { echo "test-drive-git-jobs: all green"; exit 0; } || { echo "test-drive-git-jobs: $FAILURES failure(s)"; exit 1; }
