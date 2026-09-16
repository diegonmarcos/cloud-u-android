#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ analytics-retry.test — compile and RUN the shipped retry queue,  ║
# ║ then prove the test can actually fail                            ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY A COMPILER AND NOT A GREP. The thing under test is what the code DOES
# when one of two analytics backends is down. That is a behaviour, so it is
# executed here rather than pattern-matched: analytics-retry.test.kt is
# compiled together with the SHIPPED SinkQueue.kt, by path, and run.
#
# WHY BY PATH AND NOT A COPY. Same reason publish-gate-blast-radius.test.sh
# drives the real lib-apks/build.sh through a symlink: a copy passes for
# exactly as long as it takes the two to drift, and then the test is green
# about code nobody runs.
#
# WHY PHASE 2 EXISTS. A test that has only ever been watched succeeding is
# indistinguishable from a test that returns 0 unconditionally — and the defect
# this covers is itself a thing that spent its whole life looking like it
# worked, because `or` really did call both sinks. So phase 2 puts the defect
# BACK, in a throwaway sandbox copy, and demands the test go red. If the
# regression can be reintroduced without this test noticing, this script fails
# even though phase 1 passed. The break happens in a mktemp sandbox and never
# in the working tree: these repositories are shared with other agents.
#
# WHY NIX FOR THE COMPILER. The runner has no Kotlin compiler, and this repo
# does not install toolchains imperatively. setup-deps already establishes Nix
# with flakes, so the compiler is pinned by nixpkgs like every other tool here
# instead of by an apt-get that drifts. `nix shell` is ephemeral — nothing is
# installed into a profile, and the runner is left as it was found.
#
# SinkQueue is `internal`. Compiling both files in ONE kotlinc invocation puts
# them in the same Kotlin module, which is what makes it visible to the test —
# no visibility was widened in shipped code just to be testable.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
SINK="$ROOT/ab_cloud-libs-shared/libs/analytics/src/main/java/com/diegonmarcos/superapp/analytics/SinkQueue.kt"
TEST="$ROOT/1_cicd/src/scripts/test/analytics-retry.test.kt"

# The paths are INPUTS to this test, not assumptions inside it: if the module
# is moved or renamed, say so, rather than reporting a pass for a file that is
# no longer there.
for f in "$SINK" "$TEST"; do
  if [ ! -f "$f" ]; then
    printf 'FAIL   missing input: %s\n' "${f#"$ROOT"/}"
    exit 1
  fi
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

KOTLINC=(nix shell nixpkgs#kotlin -c kotlinc)
if ! command -v nix >/dev/null 2>&1; then
  if command -v kotlinc >/dev/null 2>&1; then
    KOTLINC=(kotlinc)
  else
    printf 'FAIL   no Kotlin compiler and no nix to fetch one\n'
    printf '       refusing to report a pass for assertions that never executed\n'
    exit 1
  fi
fi

# Invoked by the class name the test pins with @file:JvmName, NOT via the jar
# manifest: which class a Main-Class ends up naming is the compiler's business,
# and a test runner should not be guessing at it.
build_and_run() {   # build_and_run <sink.kt> <jar> ; echoes output, returns test's status
  local sink="$1" jar="$2"
  if ! "${KOTLINC[@]}" "$sink" "$TEST" -include-runtime -d "$jar" >"$WORK/kotlinc.log" 2>&1; then
    printf 'COMPILE-FAILED\n'
    cat "$WORK/kotlinc.log"
    return 2
  fi
  java -cp "$jar" AnalyticsRetryTest
}

printf '══ phase 1: the shipped queue must PASS ══\n'
build_and_run "$SINK" "$WORK/shipped.jar"
SHIPPED_STATUS=$?
if [ "$SHIPPED_STATUS" -ne 0 ]; then
  printf '\nFAIL   the shipped retry queue does not satisfy the retry test\n'
  exit 1
fi

printf '\n══ phase 2: reintroduce the defect — the test must go RED ══\n'
# The regression, stated as a mutation: a failed send no longer puts its event
# back, so the copy is dropped exactly as it was when one boolean spoke for
# both sinks. Deleting the requeue is the smallest edit that restores the old
# user-visible behaviour.
MUTANT="$WORK/SinkQueue.kt"
sed '/pending.addFirst(event)/d' "$SINK" >"$MUTANT"

# The mutation is itself verified. A sed that silently matched nothing would
# make phase 2 a test of the unmodified file, which would pass phase 1's
# assertions and be reported as "the test can fail" — the precise false green
# this phase exists to prevent.
if diff -q "$SINK" "$MUTANT" >/dev/null; then
  printf 'FAIL   the mutation changed nothing — SinkQueue.kt no longer has the requeue\n'
  printf '       this phase would otherwise be testing the unmutated file\n'
  exit 1
fi
printf 'mutation applied: the failed event is no longer requeued\n'

build_and_run "$MUTANT" "$WORK/mutant.jar"
MUTANT_STATUS=$?
if [ "$MUTANT_STATUS" -eq 2 ]; then
  printf '\nFAIL   the mutant did not compile, so nothing was proved about the test\n'
  exit 1
fi
if [ "$MUTANT_STATUS" -eq 0 ]; then
  printf '\nFAIL   the defect was reintroduced and the test still passed\n'
  printf '       a test that cannot fail is not evidence of anything\n'
  exit 1
fi

printf '\nPASS   shipped queue retries a failed sink, and the test goes red without it\n'
