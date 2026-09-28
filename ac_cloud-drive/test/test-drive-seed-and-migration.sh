#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #629 — the first-run seed is COMPLETE, RESUMABLE and PER-REPO REPORTED,  ║
# ║ and the stray-clone migration keeps the COMPLETE copy                    ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. Measured on the owner's device: the store held NINE of the
# TWELVE declared seed repositories — missing front-assets-cdn, front-data and
# front-diegonmarcos, the alphabetical tail — while the seed worker had reported
# success. The cause is in the code, not in the manifest: a clone failure was
# logged with Log.w and skipped, and the pass returned Result.success() whatever
# the tally was. WorkManager's execution window is ten minutes and twelve shallow
# clones do not fit it, so the worker is stopped mid-clone, every remaining
# repository fails instantly against the torn-down thread, and the UNIQUE work
# ends TERMINALLY GREEN with a permanently truncated store. A green that verified
# nothing, one layer down.
#
# The same device also proved the #606 migration wrong. Both copies of a
# repository exist and both are partly complete, in either direction:
# cloud-u-linux was complete at the ROOT with an empty husk in git/, and
# cloud / cloud-infra were the reverse. "Never overwrite an existing clone"
# therefore stranded the good copy at the root forever, and "always move" would
# have destroyed the good copy in git/.
#
#   S1  the seed loop attempts EVERY declared repository: no early return, and a
#       failure is recorded rather than swallowed.
#   S2  an incomplete pass returns Result.retry(), never Result.success() — this
#       is the one line that makes the seed resumable.
#   S3  every outcome is REPORTED: one line per repository, logged and persisted.
#   S4  SeedReport.complete counts the DECLARED set, so a missing outcome is a
#       defect and not a pass.
#   M1  the migration decides by COMPLETENESS (clone · resolvable HEAD ·
#       populated worktree), not by position.
#   M2  a copy is deleted ONLY when the survivor scores COMPLETE, and the move is
#       verified before the loser is dropped.
#   M3  the migration still runs before the seed loop and is manifest-driven.
#   MUT mutation-proof: restoring Result.success(), restoring the swallow-and-skip
#       loop, dropping the completeness ladder and deleting without the COMPLETE
#       guard each turn a check RED; the unmutated tree stays green.
#
# OWN-SOURCE ONLY. python3 and grep only, no network, no build.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
SEED="$SRC/StoreSeed.kt"
REPORT="$SRC/SeedReport.kt"
MIGRATION="$SRC/StoreMigration.kt"
MANIFEST="$APP/data/drive-git-repos.json"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$SEED" "$REPORT" "$MIGRATION" "$MANIFEST"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

# ── the checks as functions of their inputs, so the mutation block runs them on copies ──

# s1 <StoreSeed.kt> : every declared repository is attempted and no outcome is dropped
s1() {
    local f="$1" bad=0
    grep -qE 'val declared = Declarations\.seedRepos' "$f" || { echo "    the loop does not read the declared seed set"; bad=1; }
    grep -qE 'declared\.map \{' "$f" || { echo "    the pass is not a map over the declared set — an outcome can be missing"; bad=1; }
    # The swallow-and-skip shape that lost the tail: a logged failure followed by `return@forEach`.
    if grep -qE 'Log\.w\(TAG' "$f" && grep -qE 'return@forEach' "$f"; then
        echo "    a clone failure is logged and skipped (Log.w + return@forEach) — that is the defect"; bad=1
    fi
    grep -qE 'SeedOutcome\.FAILED' "$f" || { echo "    a clone failure does not become a recorded outcome"; bad=1; }
    grep -qE 'SeedOutcome\.DEFERRED' "$f" || { echo "    a repository the stopped worker never reached is not recorded"; bad=1; }
    return $bad
}

# s2 <StoreSeed.kt> : an incomplete pass RETRIES
s2() {
    local f="$1" bad=0
    grep -qE 'return if \(report\.complete\) Result\.success\(\) else Result\.retry\(\)' "$f" \
        || { echo "    the pass does not return retry() when the report is incomplete"; bad=1; }
    # A bare `return Result.success()` at the end of the seed pass is the original defect.
    if awk '/val report = SeedReport/{seen=1} seen && /^ *return Result\.success\(\)/{found=1} END{exit !found}' "$f"; then
        echo "    the pass still ends in an unconditional Result.success()"; bad=1
    fi
    return $bad
}

# s3 <StoreSeed.kt> : per-repository reporting, logged AND persisted
s3() {
    local f="$1" bad=0
    grep -qE 'report\.lines\(\)\.forEach \{ Log\.i\(TAG, it\) \}' "$f" || { echo "    the report is not logged line by line"; bad=1; }
    grep -qE 'reportFile\(applicationContext\)\.writeText\(report\.text\(\)\)' "$f" || { echo "    the report is not persisted for the UI to read"; bad=1; }
    return $bad
}

# s4 <SeedReport.kt> : the verdict counts the DECLARED set
s4() {
    local f="$1" bad=0
    grep -qE 'outcomes\.size == declared && resumable\.isEmpty\(\)' "$f" \
        || { echo "    complete is not 'every declared repository, none resumable'"; bad=1; }
    grep -qE 'RESUMABLE = setOf\(FAILED, DEFERRED\)' "$f" || { echo "    failed/deferred are not the resumable kinds"; bad=1; }
    return $bad
}

# m1 <StoreMigration.kt> : the completeness ladder exists and is the three real things
m1() {
    local f="$1" bad=0
    grep -qE 'fun completeness\(dir: File\): Int' "$f" || { echo "    no completeness ladder — the migration decides by position"; bad=1; }
    grep -qE 'fun headResolves\(dir: File\): Boolean' "$f" || { echo "    a dangling HEAD is not detected, so a husk scores as a clone"; bad=1; }
    grep -qE 'fun hasWorktree\(dir: File\): Boolean' "$f" || { echo "    an empty worktree is not detected"; bad=1; }
    grep -qE 'fun isComplete\(dir: File\): Boolean = completeness\(dir\) == COMPLETE' "$f" || { echo "    isComplete is not the ladder's top"; bad=1; }
    grep -qE 'strayScore > destScore' "$f" || { echo "    the better copy does not win"; bad=1; }
    return $bad
}

# m2 <StoreMigration.kt> : deletion is guarded by a COMPLETE survivor, and the move is verified
m2() {
    local f="$1" bad=0
    grep -qE 'if \(destScore == COMPLETE\)' "$f" || { echo "    the root copy can be deleted without the git/ copy being proven complete"; bad=1; }
    grep -qE 'completeness\(dest\) < strayScore' "$f" || { echo "    the move is not verified before the loser is dropped"; bad=1; }
    grep -qE 'parked\.renameTo\(dest\)' "$f" || { echo "    a failed move does not restore what was there"; bad=1; }
    # Every deleteRecursively must be reachable only under a guard; the husk-vs-husk arm deletes nothing.
    grep -qE 'NEITHER copy is complete' "$f" || { echo "    two husks are not handled — one of them would be deleted"; bad=1; }
    return $bad
}

echo "── S the seed ──"
s1 "$SEED" && pass "every declared repository is attempted and every outcome recorded" || fail "the seed loop can still drop a repository silently"
s2 "$SEED" && pass "an incomplete pass returns retry(), so the tail is resumed" || fail "an incomplete seed still reports success — the store stays truncated forever"
s3 "$SEED" && pass "the pass is reported per repository, logged and persisted" || fail "the seed's outcome is not visible per repository"
s4 "$REPORT" && pass "the verdict is measured against the DECLARED set" || fail "SeedReport.complete can pass a truncated store"

echo "── M the migration ──"
m1 "$MIGRATION" && pass "the migration picks the COMPLETE copy, wherever it starts" || fail "the migration still decides by position"
m2 "$MIGRATION" && pass "nothing is deleted unless the survivor is proven complete" || fail "the migration can delete a copy that is the only usable one"
if grep -qE 'StoreMigration\.migrate\(SharedStore\.root\(\), BuildConfig\.GIT_SUBDIR, family\.repos\.map \{ it\.name \}\.toSet\(\)\)' "$SEED" \
   && awk '/StoreMigration\.migrate/{m=NR} /val declared = Declarations\.seedRepos/{s=NR} END{exit !(m && s && m < s)}' "$SEED"; then
    pass "the migration runs, manifest-driven, BEFORE the seed loop"
else
    fail "StoreMigration does not run over every declared name before the seed loop"
fi
if grep -qE 'if \(strayScore == 0\)' "$MIGRATION" && grep -qE 'is not a clone' "$MIGRATION"; then
    pass "a folder of the user's that shares a declared name is never moved and never deleted"
else
    fail "the migration can act on a directory that is not a clone"
fi

echo "── D the declared seed set is the whole set ──"
python3 - "$MANIFEST" <<'PYTHON'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
repos = d["repos"]
seed = [r["name"] for r in repos if r.get("seed") and not r.get("private")]
bad = 0
if len(seed) < 2:
    print("    the manifest declares almost nothing to seed — the set is derived, so this is a defect"); bad += 1
for r in repos:
    if r.get("seed") and r.get("private"):
        print("    %s is marked seed AND private — an anonymous clone of it always fails" % r["name"]); bad += 1
    if r.get("seed") and not r.get("public"):
        print("    %s is marked seed without the public fact the flag rests on" % r["name"]); bad += 1
    # The notes must not contradict the flag: that is how three repositories came to say
    # "carries no seed flag" while carrying one, and a reader trusted the prose.
    if r.get("seed") and "no seed flag" in (r.get("notes") or ""):
        print("    %s carries seed: true and notes saying it does not — the prose contradicts the declaration" % r["name"]); bad += 1
print("    declared seed set (%d): %s" % (len(seed), ", ".join(sorted(seed))))
sys.exit(1 if bad else 0)
PYTHON
if [ $? -eq 0 ]; then pass "every declared seed repository is public and its prose agrees with its flag"; else fail "the seed manifest contradicts itself"; fi

echo "── MUT mutations ──"
MUT="$(mktemp -d)"
trap 'rm -rf "$MUT"' EXIT
mutate() { # <name> <check-fn> <source> <python-edit>
    local name="$1" fn="$2" src="$3" edit="$4"
    local copy="$MUT/$(basename "$src")"
    cp "$src" "$copy"
    python3 - "$copy" <<PYTHON
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
$edit
open(p, "w", encoding="utf-8").write(s)
PYTHON
    if "$fn" "$copy" >/dev/null 2>&1; then fail "MUT $name: the mutation passed — the check does not hold"; else pass "MUT $name goes RED"; fi
}

mutate "an unconditional Result.success() at the end of the pass" s2 "$SEED" \
    's = s.replace("return if (report.complete) Result.success() else Result.retry()", "return Result.success()")'
mutate "the swallow-and-skip failure loop restored" s1 "$SEED" \
    's = s.replace("SeedOutcome.FAILED", "Log.w(TAG, why).let { return@forEach }")'
mutate "the completeness ladder removed" m1 "$MIGRATION" \
    's = s.replace("fun completeness(dir: File): Int", "fun scoreless(dir: File): Int")'
mutate "deletion without the COMPLETE guard" m2 "$MIGRATION" \
    's = s.replace("if (destScore == COMPLETE)", "if (destScore >= 0)")'
mutate "the verdict ignores the declared count" s4 "$REPORT" \
    's = s.replace("outcomes.size == declared && resumable.isEmpty()", "resumable.isEmpty()")'

# the unmutated tree must still be green, or every mutation above proves nothing
s1 "$SEED" >/dev/null 2>&1 && s2 "$SEED" >/dev/null 2>&1 && m1 "$MIGRATION" >/dev/null 2>&1 && m2 "$MIGRATION" >/dev/null 2>&1 \
    && pass "MUT control: the unmutated sources pass every mutated check" \
    || fail "MUT control: the unmutated sources do NOT pass — the mutations above prove nothing"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-seed-and-migration: all checks passed"; else echo "test-drive-seed-and-migration: $FAILURES check(s) FAILED"; exit 1; fi
