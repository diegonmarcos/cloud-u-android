#!/usr/bin/env bash
# store-dns-ladder.test — the Store's downloads survive a dead DNS bridge, and the test can fail.
#
# MEASURED 2026-10-07, Cloud Store's fleet pass: every leg of morpheus, c3-watchdog
# and c3-watchtower read "cannot resolve github.com (active resolver: bridge
# 127.0.0.1:2053)" while cloud-browser downloaded in the same pass. The bridge was
# the Store's ONLY resolver: when it did not answer, the connection went DIRECT to
# the Android resolver the bridge had just failed on. The fix is a ladder (bridge,
# system, DoH by IP, mesh), re-walked on every connection, every attempt named.
#
# Phase 1 compiles libs:appstore's SHIPPED DnsLadder.kt (by path) with
# store-dns-ladder.test.kt and runs real downloads through the real tunnel against
# a simulated dead bridge. Phase 2 plants each regression in a mktemp copy and
# demands the test go red, so a green phase 1 means something.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
SRC="$ROOT/ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/DnsLadder.kt"
TEST="$ROOT/1_cicd/src/scripts/test/store-dns-ladder.test.kt"
for f in "$SRC" "$TEST"; do [ -f "$f" ] || { echo "FAIL   missing input: ${f#"$ROOT"/}"; exit 1; }; done
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

KOTLINC=(nix shell nixpkgs#kotlin -c kotlinc)
if ! command -v nix >/dev/null 2>&1; then
  if command -v kotlinc >/dev/null 2>&1; then KOTLINC=(kotlinc)
  else echo "FAIL   no Kotlin compiler and no nix to fetch one"; exit 1; fi
fi

build_and_run() {   # <DnsLadder.kt> <jar>: compile, run; 2 = does not compile
  "${KOTLINC[@]}" "$1" "$TEST" -include-runtime -d "$2" >"$WORK/kotlinc.log" 2>&1 || { echo COMPILE-FAILED; cat "$WORK/kotlinc.log"; return 2; }
  timeout 180 java -cp "$2" StoreDnsLadderTest 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS'
  return "${PIPESTATUS[0]}"
}

echo "== phase 1: the shipped ladder must PASS =="
build_and_run "$SRC" "$WORK/shipped.jar" || { echo "FAIL   the shipped ladder does not survive a dead bridge"; exit 1; }

echo; echo "== phase 2: each regression must turn the test RED =="
MISSED=0
mutate() {   # <label> <sed expression>
  local label="$1" expr="$2" m="$WORK/DnsLadder.kt"
  sed -E "$expr" "$SRC" >"$m"
  if diff -q "$SRC" "$m" >/dev/null; then echo "FAIL   mutation '$label' changed nothing"; MISSED=$((MISSED+1)); return; fi
  build_and_run "$m" "$WORK/mutant.jar" >"$WORK/mutant.out"; local st=$?
  if [ "$st" -eq 2 ]; then echo "FAIL   mutant '$label' did not compile"; MISSED=$((MISSED+1))
  elif [ "$st" -eq 0 ]; then echo "FAIL   mutant '$label' still passed"; MISSED=$((MISSED+1))
  else echo "  caught: $label"; fi
}
mutate "no DoH rung (the bridge is the last word again)" 's/doh\.map \{ \(label, endpoint\) -> doh\(label, endpoint\) \} \+/emptyList<Rung>() +/'
mutate "system resolver asked before the bridge" 's/listOf\(Rung\(bridgeLabel, bridge\), Rung\("system", system\)\)/listOf(Rung("system", system), Rung(bridgeLabel, bridge))/'
mutate "a failed rung is cached as bad and skipped" 's/val budget = if \(recent\) reprobeBudgetMs else rungBudgetMs/if (recent) { attempts += Attempt(r.label, false, "cached as bad"); continue }; val budget = rungBudgetMs/'
mutate "the choice is cached for the pass (retry does not re-resolve)" "s/(val key = host.trimEnd\('.'\).lowercase\(\))/\\1; if (pending.containsKey(key) \&\& skip.isEmpty()) return true/"
mutate "an unreachable answer is final (no next rung)" '/if \(resolveFor\(host, setOf\(via\.rung\)\)\)/d'
mutate "the row error loses its trail" 's/failures\[key\] = t; lastFailure = t/failures[key] = "DNS"; lastFailure = "DNS"/'
mutate "a hung rung is never abandoned" 's/job\.get\(budget, TimeUnit\.MILLISECONDS\)/job.get()/'
[ "$MISSED" -eq 0 ] || { echo "FAIL   $MISSED mutation(s) not caught"; exit 1; }
echo; echo "PASS   a dead bridge no longer fails the download, every attempt is named, and the test goes red without the fix"
