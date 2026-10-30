#!/usr/bin/env bash
# test-me-health-tracked: Health > Tracked (Google Fit / Samsung Health style) and the Workout Plan's place.
#   T1 data/health/health_tracked.json declares the eight cards (steps, active minutes, calories, distance, heart rate,
#      sleep, weight, water), each id mapped in TrackedHc (record type, aggregate, unit value, writer), no more no less
#   T2 every card can be written: its record's WRITE permission is in build.json::ui.permissions.health.write, its READ in read
#   T3 the page is declared (Trackers group, first), has its stack file, and HealthPage dispatches it
#   T4 the detail: Day / Week / Month ranges, Health Connect aggregate reads, a manual entry inserted into Health Connect
#   T5 the Workout Plan is on the Plan line, not the Trackers line
# Then each mutation below is planted in a scratch copy and must turn the check RED.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; APP="$(cd "$HERE/.." && pwd)"
FAILS=0; ok() { echo "  PASS: $1"; }; bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }
CHECK="$(mktemp -d)/check.py"; S="$(mktemp -d)"; trap 'rm -rf "$S" "$(dirname "$CHECK")"' EXIT
cat > "$CHECK" <<'PY'
import json, re, sys
app = sys.argv[1]; h = app + "/app/src/main/java/com/diegonmarcos/cloudme/health"
out = []
def bad(m): out.append(m)
WANT = ["steps", "active", "calories", "distance", "heart", "sleep", "weight", "water"]
PERM = {"StepsRecord": "STEPS", "ExerciseSessionRecord": "EXERCISE", "ActiveCaloriesBurnedRecord": "ACTIVE_CALORIES_BURNED", "DistanceRecord": "DISTANCE",
        "HeartRateRecord": "HEART_RATE", "SleepSessionRecord": "SLEEP", "WeightRecord": "WEIGHT", "HydrationRecord": "HYDRATION"}
ids = [m["id"] for m in json.load(open(app + "/data/health/health_tracked.json"))["metrics"]]
if sorted(ids) != sorted(WANT): bad("T1 cards are %s, want %s" % (ids, WANT))
k = open(h + "/TrackedHc.kt").read()
def branches(fn):
    body = k.split(fn, 1)[1].split("else ->", 1)[0]
    return re.findall(r'^\s+"([a-z]+)" ->', body, re.M)
for fn in ("fun type(", "private fun metric(", "private fun value(", "fun record("):
    if sorted(branches(fn)) != sorted(ids): bad("T1 %s maps %s, the declaration has %s" % (fn, branches(fn), ids))
iv = re.search(r'val IDS = listOf\(([^)]*)\)', k)
if not iv or sorted(re.findall(r'"([a-z]+)"', iv.group(1))) != sorted(ids): bad("T1 TrackedHc.IDS differs from the declaration")
perms = json.load(open(app + "/build.json"))["ui"]["permissions"]["health"]
types = dict(re.findall(r'^\s+"([a-z]+)" -> (\w+Record)::class', k.split("fun type(", 1)[1].split("else ->", 1)[0], re.M))
for i in ids:
    t = types.get(i); p = PERM.get(t)
    if not p: bad("T2 %s has no known record type (%s)" % (i, t)); continue
    if "android.permission.health.WRITE_" + p not in perms["write"]: bad("T2 %s cannot be written: WRITE_%s is not declared" % (i, p))
    if "android.permission.health.READ_" + p not in perms["read"]: bad("T2 %s cannot be read: READ_%s is not declared" % (i, p))
sec = [s for s in json.load(open(app + "/build.json"))["ui"]["sections"] if s["id"] == "health"][0]
groups = {g["id"]: g["pages"] for g in sec["groups"]}
if groups.get("trackers", [None])[0] != "tracked": bad("T3 Tracked is not the first Trackers page")
if "tracked" not in [p["id"] for p in sec["pages"]]: bad("T3 Tracked is not a declared page")
try:
    if json.load(open(app + "/data/ui/health/tracked.json"))[0].get("page") != "tracked": bad("T3 tracked.json hosts another page")
except Exception as e: bad("T3 data/ui/health/tracked.json: %s" % e)
if '"tracked" -> TrackedPage(env)' not in open(h + "/HealthPage.kt").read(): bad("T3 HealthPage does not dispatch tracked")
ui = open(h + "/HealthTracked.kt").read()
rng = re.search(r'enum class TrackedRange[^{]*\{([^}]*)\}', k)
if not rng or [x for x in re.findall(r'\b(DAY|WEEK|MONTH)\(', rng.group(1))] != ["DAY", "WEEK", "MONTH"]: bad("T4 the detail ranges are not Day / Week / Month")
for need, why in (("aggregateGroupByPeriod(", "daily buckets"), ("aggregateGroupByDuration(", "hourly buckets"), ("insertRecords(", "manual entry write")):
    if need not in k: bad("T4 no %s (%s)" % (need, why))
for need in ("TrackedHc.add(", "TrackedDetail(", "Tracked.history(", "BarChart(", "Ring("):
    if need not in ui: bad("T4 the page lacks " + need)
if "workout" not in groups.get("plan", []) or "workout" in groups.get("trackers", []): bad("T5 the Workout Plan is not on the Plan line")
print("\n".join(out)); sys.exit(1 if out else 0)
PY
echo "[ac_cloud-me] Health > Tracked"
r="$(python3 "$CHECK" "$APP" 2>&1)" && ok "eight cards, mapped, writable, declared, with detail and the Workout Plan on Plan" || bad "$r"
FILES="build.json data/health/health_tracked.json data/ui/health/tracked.json app/src/main/java/com/diegonmarcos/cloudme/health/TrackedHc.kt app/src/main/java/com/diegonmarcos/cloudme/health/HealthTracked.kt app/src/main/java/com/diegonmarcos/cloudme/health/HealthPage.kt"
plant() {
  rm -rf "$S/t"; for f in $FILES; do mkdir -p "$S/t/$(dirname "$f")"; cp "$APP/$f" "$S/t/$f"; done
  python3 - "$S/t" <<PY
import sys
T = sys.argv[1]; H = T + "/app/src/main/java/com/diegonmarcos/cloudme/health"
def sub(path, a, b):
    s = open(path).read(); assert a in s, "mutation anchor missing: " + a; open(path, "w").write(s.replace(a, b, 1))
$2
PY
  if python3 "$CHECK" "$S/t" >/dev/null 2>&1; then bad "mutation stayed green: $1"; else ok "red: $1"; fi
}
plant "a card is dropped"                       'sub(T + "/data/health/health_tracked.json", "    {\"id\": \"water\"", "    {\"id\": \"zz\"")'
plant "a card loses its writer"                 'sub(H + "/TrackedHc.kt", "            \"weight\" -> WeightRecord(time", "            \"wt\" -> WeightRecord(time")'
plant "a write permission is not declared"      'sub(T + "/build.json", "\"android.permission.health.WRITE_HYDRATION\"", "\"android.permission.health.WRITE_X\"")'
plant "the Workout Plan goes back to Trackers"  'sub(T + "/build.json", "\"tracked\",\n              \"activities\"", "\"tracked\",\n              \"workout\",\n              \"activities\"")'
plant "HealthPage does not dispatch tracked"    'sub(H + "/HealthPage.kt", "\"tracked\" -> TrackedPage(env)", "")'
plant "the manual entry is not written"         'sub(H + "/TrackedHc.kt", "c.insertRecords(", "c.toString(")'
plant "the Month range is gone"                 'sub(H + "/TrackedHc.kt", ", MONTH(\"Month\", 30)", "")'
echo; [ "$FAILS" -eq 0 ] && { echo "OK me health tracked"; exit 0; } || { echo "FAILED $FAILS"; exit 1; }
