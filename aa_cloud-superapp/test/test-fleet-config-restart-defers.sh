#!/usr/bin/env bash
# A FleetConfig import restarts the receiving app so cached singletons cannot write the old
# values back. On 2026-10-08 the new-phone migration wrote 3 values into cld.termux and that
# restart killed the owner's terminal with the Claude session running inside its proot.
# The provider now defers the restart while the process serves a foreground session and
# says so in its reply; the values are on disk and apply at the next natural start.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
P="$ROOT/../ab_cloud-libs-shared/libs/core/src/main/java/com/diegonmarcos/superapp/core/FleetConfigProvider.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok   $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL $1"; }
grep -q 'fun servingForeground(ctx: Context): Boolean' "$P" && ok "the provider asks Android whether this process serves a foreground session" || bad "no servingForeground() in the provider"
grep -q 'IMPORTANCE_FOREGROUND_SERVICE' "$P" && grep -q 'it.pid == Process.myPid()' "$P" && ok "…by the live importance of its own pid, not a remembered flag" || bad "servingForeground does not read the own pid's importance"
grep -q 'if (servingForeground(ctx)) r.put("restart", "deferred' "$P" && ok "a deferred restart is said in the reply" || bad "the reply does not say the restart was deferred"
grep -q 'else Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, RESTART_DELAY_MS)' "$P" && ok "an app serving nothing still restarts after a write" || bad "the restart for idle apps is gone"
echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
