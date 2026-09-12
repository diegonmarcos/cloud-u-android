#!/usr/bin/env bash
#
# #290 — "wireless debugging keeps switching itself off although WiFi never dropped".
#
# The setting was armed in exactly two places, both of them once-per-process:
# App.onCreate and BOOT_COMPLETED, each enqueueing PrivilegedPlaneWorker as
# one-time work. Nothing re-armed it in between, so the first time the platform
# cleared `adb_wifi_enabled` mid-session it stayed cleared until the next cold
# start. WirelessDebugKeeper closes that hole on a 15-minute period.
#
# What this pins, and why each one can regress quietly:
#
#   T1  the keeper exists and actually writes adb_wifi_enabled=1
#   T2  it is enqueued as PERIODIC unique work, not one-time. Swapping
#       enqueueUniquePeriodicWork back to enqueueUniqueWork compiles, runs, and
#       reproduces the exact bug — with the class still present and looking fixed.
#   T3  the period is 15 MINUTES. WorkManager's floor is 15; a request for 5 is
#       silently clamped up, so a "more responsive" edit reads as an improvement
#       and changes nothing. Pin the number that is actually in force.
#   T4  the policy is KEEP. REPLACE would restart the period on every cold start,
#       and an app opened often enough would never reach a single run.
#   T5  PrivilegedPlaneWorker is NOT the thing on the timer. It carries the
#       pairing/autoconnect loop — up to eight attempts with sleeps between them.
#       Putting THAT on a quarter-hour period is the obvious "simplification" and
#       it costs real battery forever on a phone that has a battery-hogs page.
#   T6  doWork checks WRITE_SECURE_SETTINGS before writing. Without it every run
#       throws SecurityException on a device where the permission was never
#       granted — four times an hour, forever.
#   T7  doWork reads before it writes. Unconditional putInt would rewrite the
#       setting every 15 minutes whether or not anything cleared it, and each
#       write wakes the framework's adb listeners.
#   T8  doWork never returns Result.retry(). A retry on a phone with no
#       permission backs off and re-runs on top of the period, which is the
#       battery cost of T5 arriving through the back door.
#
# FAIL CLOSED, per the sibling testers: if python3 is missing or a file has
# moved, this tester has proven nothing and says so rather than passing.

set -uo pipefail

APP="$(cd "$(dirname "$0")/.." && pwd)"          # -> aa_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

KEEPER="$APP/app/src/main/java/com/diegonmarcos/superapp/system/WirelessDebugKeeper.kt"
APPKT="$APP/app/src/main/java/com/diegonmarcos/superapp/App.kt"

if ! command -v python3 >/dev/null 2>&1; then
  echo "  FAIL: python3 is not on PATH — this tester proves nothing without it"
  echo "== RESULT: 0 passed, 1 failed =="
  exit 1
fi
for f in "$KEEPER" "$APPKT"; do
  if [ ! -f "$f" ]; then
    echo "  FAIL: missing file $f — the tree is not what this tester was written against"
    echo "== RESULT: 0 passed, 1 failed =="
    exit 1
  fi
done

# code <file> — the file with comment lines removed. The KDoc on WirelessDebugKeeper
# spells out "adb_wifi_enabled", "PrivilegedPlaneWorker" and "15 minutes" in prose,
# so a plain grep would find the explanation and report it as the implementation.
code() { grep -vE '^[[:space:]]*(//|\*|/\*)' "$1"; }

# dowork — WirelessDebugKeeper.doWork's body only, comments stripped. Scoped by
# indentation: everything inside the function is indented deeper than its own
# `override fun` line. Assertions below must not be satisfiable by the companion
# object 20 lines further down, which legitimately contains the string
# "adb_wifi_enabled" as a constant.
dowork() {
  code "$KEEPER" | python3 -c '
import sys
lines = sys.stdin.read().split("\n")
start = None
for i, l in enumerate(lines):
    if "fun doWork" in l:
        start = i
        col = len(l) - len(l.lstrip())
        break
if start is None:
    sys.exit(0)
out = [lines[start]]
for l in lines[start+1:]:
    if l.strip() and (len(l) - len(l.lstrip())) <= col:
        break
    out.append(l)
print("\n".join(out))
'
}

echo "== #290 wireless debugging re-arm =="

# ── T1 ── the keeper writes the setting on
if dowork | grep -q 'Settings\.Global\.putInt' && dowork | grep -q 'ADB_WIFI_ENABLED, 1'; then
  ok "T1 doWork writes adb_wifi_enabled=1"
else
  bad "T1 doWork does not write adb_wifi_enabled=1 — the keeper keeps nothing"
fi

# ── T2 ── periodic, not one-time
if code "$APPKT" | grep -q 'enqueueUniquePeriodicWork'; then
  ok "T2 the keeper is enqueued as periodic unique work"
else
  bad "T2 no enqueueUniquePeriodicWork in App.kt — nothing re-arms between launches, which IS #290"
fi
if code "$APPKT" | grep -q 'PeriodicWorkRequestBuilder<.*WirelessDebugKeeper>'; then
  ok "T2b the periodic request is built for WirelessDebugKeeper"
else
  bad "T2b the periodic request is not for WirelessDebugKeeper"
fi

# ── T3 ── the period is the 15-minute floor, stated in minutes
if code "$APPKT" | grep -A2 'PeriodicWorkRequestBuilder<.*WirelessDebugKeeper>' \
   | grep -qE '\b15,[[:space:]]*java\.util\.concurrent\.TimeUnit\.MINUTES'; then
  ok "T3 the period is 15 MINUTES — WorkManager's floor, so it is the period actually in force"
else
  bad "T3 the period is not 15 minutes; anything below the floor is silently clamped and the edit does nothing"
fi

# ── T4 ── KEEP, so a cold start does not restart the period
if code "$APPKT" | grep -q 'ExistingPeriodicWorkPolicy\.KEEP'; then
  ok "T4 the periodic policy is KEEP"
else
  bad "T4 the periodic policy is not KEEP — REPLACE restarts the 15 minutes on every app launch"
fi

# ── T5 ── the heavy worker is NOT on the timer
if code "$APPKT" | grep -q 'PeriodicWorkRequestBuilder<.*PrivilegedPlaneWorker>'; then
  bad "T5 PrivilegedPlaneWorker is on a period — that is the pairing/autoconnect loop running forever"
else
  ok "T5 PrivilegedPlaneWorker is not on a period; only the cheap re-arm repeats"
fi

# ── T6 ── permission checked before writing
if dowork | python3 -c '
import sys
b = sys.stdin.read()
perm = b.find("WRITE_SECURE_SETTINGS")
put  = b.find("Settings.Global.putInt")
sys.exit(0 if perm != -1 and put != -1 and perm < put else 1)
'; then
  ok "T6 WRITE_SECURE_SETTINGS is checked before the write"
else
  bad "T6 the write is not guarded by a permission check — SecurityException four times an hour"
fi

# ── T7 ── read before write, so an already-on setting is left alone
if dowork | grep -q 'Settings\.Global\.getInt'; then
  ok "T7 the current value is read before writing"
else
  bad "T7 no read — the setting is rewritten every 15 minutes whether or not anything cleared it"
fi

# ── T8 ── never retry
if dowork | grep -q 'Result\.retry'; then
  bad "T8 doWork can return Result.retry() — backoff re-runs stack on top of the period"
else
  ok "T8 doWork never retries"
fi

echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
