#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ translate-key-handoff.test — compile and RUN the shipped key     ║
# ║ handoff, then prove the test can fail on EITHER half             ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY A COMPILER AND NOT A GREP. What #355 part 2 decided is a behaviour —
# tapping the host application's field releases the keys, typing in the panel
# does not — so it is executed here rather than pattern-matched.
# translate-key-handoff.test.kt is compiled together with the SHIPPED
# KeyHandoff.kt, by path, and run. Same reason analytics-retry.test.sh does it:
# an assertion anchored on source text reports on spelling, not on the rule.
#
# WHY TWO MUTATIONS AND NOT ONE. This feature has two halves and they fail in
# opposite directions, so one mutation can only ever prove half the test is
# alive:
#
#   mutation A — the release path is broken. The keys never go back to the host
#                field. Tapping the app does nothing. Case 2 must go red.
#   mutation B — the release fires on EVERY selection change, self-caused ones
#                included. The panel hands the keys back the instant the user
#                types into it. Cases 3, 4 and 7 must go red.
#
# Mutation B is the careless fix this ticket was warned about, and a test that
# only carried mutation A would have shipped it green.
#
# Each mutation is applied to a mktemp SANDBOX copy and never to the working
# tree: this repository is shared with other live agents.
#
# Each mutation is also VERIFIED to have changed something. A sed that matched
# nothing would leave phase 2 testing the unmutated file, which passes phase 1
# and would be reported as "the test can fail" — the exact false green this
# phase exists to prevent.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
HANDOFF="$ROOT/ab_cloud-libs-shared/libs/translate/src/main/java/com/diegonmarcos/superapp/translate/KeyHandoff.kt"
TEST="$ROOT/1_cicd/src/scripts/test/translate-key-handoff.test.kt"

# The paths are INPUTS to this test, not assumptions inside it: if the engine is
# moved or renamed, say so, rather than reporting a pass for a file that is no
# longer there.
for f in "$HANDOFF" "$TEST"; do
  if [ ! -f "$f" ]; then
    printf 'FAIL   missing input: %s\n' "${f#"$ROOT"/}"
    exit 1
  fi
done

# KeyHandoff is an engine and must stay compilable without a device — that is
# what makes this test possible. An Android import creeping in would break the
# compile below with a confusing message, so it is called out by name first.
if command grep -qE '^import +android' "$HANDOFF"; then
  printf 'FAIL   KeyHandoff.kt imports android — it is an engine and must stay device-free\n'
  printf '       (libs/ = engines, and this file is compiled standalone by this test)\n'
  exit 1
fi

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
build_and_run() {   # build_and_run <handoff.kt> <jar> ; echoes output, returns test's status
  local src="$1" jar="$2"
  if ! "${KOTLINC[@]}" "$src" "$TEST" -include-runtime -d "$jar" >"$WORK/kotlinc.log" 2>&1; then
    printf 'COMPILE-FAILED\n'
    cat "$WORK/kotlinc.log"
    return 2
  fi
  java -cp "$jar" TranslateKeyHandoffTest
}

# mutate <name> <sed-expression> <description>
# Applies the mutation to a sandbox copy, proves it changed the file, then
# demands the test go red against it.
mutate() {
  local name="$1" expr="$2" desc="$3"
  local mutant="$WORK/$name/KeyHandoff.kt"
  mkdir -p "$WORK/$name"
  sed "$expr" "$HANDOFF" >"$mutant"

  if diff -q "$HANDOFF" "$mutant" >/dev/null; then
    printf 'FAIL   mutation %s changed nothing — KeyHandoff.kt no longer matches the pattern\n' "$name"
    printf '       this phase would otherwise be testing the unmutated file\n'
    return 1
  fi
  printf 'mutation %s applied: %s\n' "$name" "$desc"

  build_and_run "$mutant" "$WORK/$name.jar"
  local status=$?
  if [ "$status" -eq 2 ]; then
    printf '\nFAIL   mutant %s did not compile, so nothing was proved about the test\n' "$name"
    return 1
  fi
  if [ "$status" -eq 0 ]; then
    printf '\nFAIL   mutation %s was applied and the test STILL PASSED\n' "$name"
    printf '       a test that cannot fail is not evidence of anything\n'
    return 1
  fi
  printf 'mutation %s: test went red, as it must\n\n' "$name"
  return 0
}

printf '══ phase 1: the shipped handoff must PASS ══\n'
build_and_run "$HANDOFF" "$WORK/shipped.jar"
if [ $? -ne 0 ]; then
  printf '\nFAIL   the shipped key handoff does not satisfy the handoff test\n'
  exit 1
fi

printf '\n══ phase 2a: break the RELEASE path — the test must go RED ══\n'
# The keys are never handed back: onSelectionChange still reports host-caused,
# but stops actually releasing. Deleting the assignment is the smallest edit
# that restores "the bar holds the keys forever", which is the behaviour before
# this ticket.
mutate release 's/^\( *\)heldByBox = false$/\1\/\/ mutated away/' \
  'onSelectionChange no longer releases the keys' || exit 1

printf '══ phase 2b: release on EVERY selection change — the test must go RED ══\n'
# The careless fix: drop the self-caused guard so any selection change hands the
# keys back, including the echo of the bar's own writes.
mutate always 's/if (selfWritePending) {/if (false) {/' \
  'the self-caused guard never fires, so every update releases' || exit 1

printf 'PASS   the host field takes the keys on a tap, the panel keeps them while typing,\n'
printf '       and the test goes red on either half being broken\n'
