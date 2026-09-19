#!/usr/bin/env bash
# test-home-avatar-icon-cache.sh — the Home shade's group avatars must never
# load a foreign APK's resources on the main thread.
#
# WHY THIS EXISTS (#498-ANR, 2026-09-18): groupAvatar called
# packageManager.getApplicationIcon per group on EVERY shade re-render, on
# MAIN. A cold call loads the whole foreign APK's resources
# (ApkAssets.loadFromPath) — and that is verbatim the main-thread stack in all
# three HomeActivity ANRs the OS recorded that evening (21:28:12 / 21:28:42 /
# 21:56:31, "Input dispatching timed out", read via /api/diagnostics/exits).
# CrashLogger showed NOTHING for any of them: an ANR is not an uncaught throw,
# which is why this shipped and crashed for a day before the exits endpoint
# existed. The fix: a process-wide per-package cache, misses loaded once on
# Dispatchers.IO, monogram shown meanwhile.
set -u
cd "$(dirname "$0")/.."
FRAG="app/src/main/java/com/diegonmarcos/superapp/launcher/AggregatorStackFragment.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { [ "$1" = "OK" ] && ok "$2" || bad "$2 — $1"; }

echo "== T1: getApplicationIcon appears in code exactly once, inside the Dispatchers.IO block =="
check "$(python3 - "$FRAG" <<'PY'
import re, sys
lines = open(sys.argv[1]).read().splitlines()
code_hits = [i for i, l in enumerate(lines)
             if "getApplicationIcon" in l and not l.strip().startswith(("//", "*", "/*"))]
if len(code_hits) != 1:
    print("PROBLEM: %d code call(s) to getApplicationIcon (want exactly 1 — the cached IO load); lines %s"
          % (len(code_hits), [i + 1 for i in code_hits]))
else:
    i = code_hits[0]
    window = "\n".join(lines[max(0, i - 4):i + 1])
    if "Dispatchers.IO" not in window:
        print("PROBLEM: the getApplicationIcon call at line %d is not inside a withContext(Dispatchers.IO) block — foreign APK resources load on MAIN again, the exact #498 ANR" % (i + 1))
    else:
        print("OK")
PY
)" "the one icon load runs on Dispatchers.IO"

echo "== T2: a process-wide cache is consulted before loading and written after =="
check "$(python3 - "$FRAG" <<'PY'
import sys
t = open(sys.argv[1]).read()
p = []
if "appIconCache[" not in t:
    p.append("no appIconCache read/write — every render pays the APK-resources load again")
elif t.count("appIconCache[") < 2:
    p.append("appIconCache referenced once — cache is read XOR written, not both")
if "appIconMisses" not in t:
    p.append("no negative cache — an uninstalled package is re-probed on every shade render")
print("; ".join(p) or "OK")
PY
)" "cache read + write + negative cache all present"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
