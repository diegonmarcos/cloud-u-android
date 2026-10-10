#!/usr/bin/env bash
# Tester: the battery has ONE Source of Truth, and nothing outside it reads
# the battery's current, voltage or counters.
#
# WHY THIS EXISTS. The owner's rule: one battery model computes everything —
# rate, power, time to empty/full, since-last-charge — and the Battery badge,
# the home-screen popup, Configs › About › Battery, the debug API and Battery
# Usage Details all read its report (libs:battery BatteryRepository.report →
# BatteryTruth). Before it, five places each read CURRENT_NOW and EXTRA_VOLTAGE
# and did their own arithmetic, and the numbers disagreed. The cheapest way a
# second calculation comes back is someone reading BatteryManager again "just
# here", so that read is what this forbids:
#
#   T1  outside libs:battery's BatteryRepository.kt, no SuperApp source (the app
#       or any shared lib) references BATTERY_PROPERTY_CURRENT_NOW / _AVERAGE /
#       _CHARGE_COUNTER / _ENERGY_COUNTER, EXTRA_VOLTAGE or
#       computeChargeTimeRemaining (comments and string literals are blanked);
#   T2  the calculations the SoT replaced stay deleted;
#   T3  the surfaces read the report;
#   PROVE  a planted violation in a sandbox copy turns T1 red, so T1 can fail.
#
# Static tester (no device, no build).
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # -> aa_cloud-superapp
ROOT="$(cd "$APP/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

SCAN="$(cat <<'PY'
import os, re, sys
root = sys.argv[1]
SOT = os.path.join("ab_cloud-libs-shared", "libs", "battery", "src", "main", "java",
                   "com", "diegonmarcos", "superapp", "battery", "BatteryRepository.kt")
TOKENS = re.compile(r"\b(BATTERY_PROPERTY_CURRENT_NOW|BATTERY_PROPERTY_CURRENT_AVERAGE|"
                    r"BATTERY_PROPERTY_CHARGE_COUNTER|BATTERY_PROPERTY_ENERGY_COUNTER|"
                    r"EXTRA_VOLTAGE|computeChargeTimeRemaining)\b")
def code(text):
    text = re.sub(r"/\*.*?\*/", lambda m: re.sub(r"[^\n]", " ", m.group(0)), text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return re.sub(r'"(?:\\.|[^"\\\n])*"', '""', text)
trees = [os.path.join(root, "aa_cloud-superapp", "app", "src", "main")]
libs = os.path.join(root, "ab_cloud-libs-shared", "libs")
trees += [os.path.join(libs, d, "src", "main") for d in sorted(os.listdir(libs))]
hits, scanned = [], 0
for t in trees:
    for dp, dirs, fs in os.walk(t):
        dirs[:] = [d for d in dirs if d not in ("build", ".gradle")]
        for f in fs:
            if not f.endswith((".kt", ".java")): continue
            p = os.path.join(dp, f); rel = os.path.relpath(p, root)
            scanned += 1
            if rel == SOT: continue
            for n, line in enumerate(code(open(p, errors="replace").read()).split("\n"), 1):
                m = TOKENS.search(line)
                if m: hits.append("%s:%d %s" % (rel, n, m.group(1)))
if scanned < int(sys.argv[2] if len(sys.argv) > 2 else 50): print("scanned only %d files — the scan is not reaching the sources" % scanned)
elif hits: print("battery read outside the SoT: " + "; ".join(hits[:8]))
else: print("OK")
PY
)"

echo "== T1: nothing outside BatteryRepository reads the battery's current, voltage or counters =="
r="$(python3 -c "$SCAN" "$ROOT")"
[ "$r" = OK ] && ok "CURRENT_NOW / EXTRA_VOLTAGE / counters are read by libs:battery BatteryRepository only" || bad "$r"

echo "== T2: the replaced calculations stay deleted =="
LIB="$ROOT/ab_cloud-libs-shared/libs/battery/src/main/java/com/diegonmarcos/superapp/battery"
NC="$APP/app/src/main/java/com/diegonmarcos/superapp/notificationcenter"
back=""
for f in "$LIB/BatterySessionStats.kt" "$LIB/PowerFlow.kt" "$LIB/BatteryHistoryStore.kt" "$NC/BatteryEstimator.kt"; do
  [ -e "$f" ] && back="$back $(basename "$f")"
done
[ -z "$back" ] && ok "BatterySessionStats, PowerFlow, BatteryHistoryStore, BatteryEstimator are gone" \
  || bad "a second battery calculation is back:$back"

echo "== T3: every battery surface reads the SoT report =="
miss=""
for f in "$NC/BatteryBadgeService.kt" "$LIB/BatteryEstimatePopup.kt" "$LIB/EnergyUsageDialog.kt" \
         "$LIB/EnergyWatchdog.kt" "$LIB/ChargeSnapshot.kt" \
         "$APP/app/src/main/java/com/diegonmarcos/superapp/batterystats/BatteryStatsFragment.kt" \
         "$APP/app/src/main/java/com/diegonmarcos/superapp/devcontrol/DevControlServer.kt" \
         "$APP/app/src/main/java/com/diegonmarcos/superapp/devcontrol/DevControlFragment.kt"; do
  grep -q 'BatteryRepository\.report(' "$f" || miss="$miss $(basename "$f")"
done
[ -z "$miss" ] && ok "badge, popup, stats page, About, debug API, Usage Details, watchdog and snapshots read BatteryRepository.report()" \
  || bad "not reading the SoT:$miss"
grep -q 'BatteryTruth\.powerFlow(' "$LIB/EnergyUsageDialog.kt" \
  && ok "the power in/out card is BatteryTruth.powerFlow over the report" \
  || bad "the power in/out card does not come from BatteryTruth.powerFlow"

echo "== PROVE: a planted CURRENT_NOW read outside the SoT turns T1 red =="
SB="$(mktemp -d)"; trap 'rm -rf "$SB"' EXIT
mkdir -p "$SB/aa_cloud-superapp/app/src/main/java/x" "$SB/ab_cloud-libs-shared/libs"
cp -r "$ROOT/ab_cloud-libs-shared/libs/battery" "$SB/ab_cloud-libs-shared/libs/"
r0="$(python3 -c "$SCAN" "$SB" 5)"
printf 'package x\nval a = android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW\n' > "$SB/aa_cloud-superapp/app/src/main/java/x/Planted.kt"
r1="$(python3 -c "$SCAN" "$SB" 5)"
if [ "$r0" = OK ] && [ "${r1#battery read outside the SoT: }" != "$r1" ] && printf '%s' "$r1" | grep -q 'Planted.kt'; then
  ok "the sandbox is green, and red once one file reads CURRENT_NOW"
else
  bad "the scan cannot fail (clean: '$r0', planted: '$r1')"
fi

echo
echo "battery-one-sot: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
