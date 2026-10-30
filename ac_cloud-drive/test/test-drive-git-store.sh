#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #1315 ONE STORE MODEL. The Git layer reaches the shared store through    ║
# ║ sync/store/ alone: one route (host <-> guest path form), one runner of    ║
# ║ scripts (CLOUD_EXEC). Repos that ARE in the store were not found because  ║
# ║ four readers reached it four ways; this guard keeps the number at one.    ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#   S1 no file of sync/ outside sync/store/ binds CLOUD_EXEC (`StoreExec(`) or translates a path itself (`.guestPath(`,
#      `StoreAccess.paths.inStore(`); every guest path and every script run goes through StoreRoute / StoreShell.
#   S2 sync/store/ is small and pure-tested: StoreRoute has a JVM suite with the host alias, the escape and both directions.
#   M  mutation: a copy of the tree with one `StoreExec(ctx)` added to a sync/ file turns S1 red.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SYNC="$APP/app/src/main/java/com/diegonmarcos/clouddrive/sync"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

# s1 <sync dir>: prints the offending lines; exit 1 when any.
s1() {
  local dir="$1" hits
  hits=$(grep -rn --include='*.kt' -E 'StoreExec\(|\.guestPath\(|StoreAccess\.paths\.inStore\(' "$dir" | grep -v "/store/" || true)
  if [ -n "$hits" ]; then printf '%s\n' "$hits"; return 1; fi
  return 0
}

echo "── S1 one runner, one route ──"
if out=$(s1 "$SYNC"); then pass "no StoreExec( / guestPath( / paths.inStore( outside sync/store"; else fail "store reached behind the facade's back:"; printf '%s\n' "$out"; fi

echo "── S2 the facade exists and is tested ──"
[ -f "$SYNC/store/StoreRoute.kt" ] && [ -f "$SYNC/store/StoreShell.kt" ] && pass "sync/store/StoreRoute.kt + StoreShell.kt" || fail "sync/store missing"
T="$APP/app/src/test/java/com/diegonmarcos/clouddrive/sync/store/StoreRouteTest.kt"
if [ -f "$T" ] && grep -q "data-data alias" "$T" && grep -q "escape is refused" "$T" && grep -q "maps both ways" "$T"; then pass "StoreRouteTest covers alias, escape, both directions"; else fail "StoreRouteTest incomplete"; fi
grep -q "fun run(script" "$SYNC/store/StoreShell.kt" && grep -q "fun job(script" "$SYNC/store/StoreShell.kt" && pass "StoreShell has run and job" || fail "StoreShell API"
n=$(grep -c "StoreExec(" "$SYNC/store/StoreShell.kt"); [ "$n" -ge 1 ] && pass "the shell is the one binder ($n sites)" || fail "the shell binds nothing"

echo "── M mutation ──"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
cp -r "$SYNC" "$tmp/sync"
printf 'package x\nclass Rogue(ctx: android.content.Context) { fun go() = com.diegonmarcos.cloudlib.sharedstore.StoreExec(ctx).run("true") }\n' > "$tmp/sync/Rogue.kt"
if s1 "$tmp/sync" >/dev/null; then fail "S1 passed a rogue StoreExec( (vacuous)"; else pass "rogue StoreExec( in sync/ turns S1 red"; fi

[ "$FAILURES" -eq 0 ] && { echo "test-drive-git-store: all green"; exit 0; } || { echo "test-drive-git-store: $FAILURES failure(s)"; exit 1; }
