#!/usr/bin/env bash
# The terminal is the app that died from it: on 2026-10-08 the new-phone migration wrote
# 3 values into cld.termux through libs:core's FleetConfigProvider, whose import restarts
# the receiving app, and the owner's terminal session (proot, Claude) went down with it.
# This terminal links that provider by reference; it must be the one that defers its
# restart while the process serves a foreground session (a terminal session always does).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
P="$ROOT/../ab_cloud-libs-shared/libs/core/src/main/java/com/diegonmarcos/superapp/core/FleetConfigProvider.kt"
fails=0
ok()  { echo "  ok   — $1"; }
bad() { echo "::error::FAIL — $1"; fails=$((fails + 1)); }
grep -q "':libs:core'" "$ROOT/settings.gradle" && ok "this terminal links libs:core (the FleetConfig provider) by reference" || bad "settings.gradle no longer includes :libs:core"
grep -q 'fun servingForeground(ctx: Context): Boolean' "$P" && ok "the provider asks Android whether its process serves a foreground session" || bad "no servingForeground() in the provider"
grep -q 'if (servingForeground(ctx)) r.put("restart", "deferred' "$P" && ok "a write into a serving app defers the restart and says so in the reply" || bad "the provider still restarts a serving app"
echo
[ "$fails" -eq 0 ] && echo "ALL GREEN — a fleet-config import never takes a terminal session down" || { echo "RED — $fails failing check(s)"; exit 1; }
