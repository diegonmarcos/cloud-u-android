#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ fleet-alerts-guard.test — prove the guard FAILS when an app      ║
# ║ posts an alert of its own, and passes on the real fleet          ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# #777. A guard only ever watched succeeding is indistinguishable from
# `exit 0`. The fixture is the REAL fleet's exempt posters (copied, so the
# green case is the world CI runs) and every mutation must turn it red:
#   M1 a new app posts its own notification            -> F1
#   M2 the pre-#777 Store worker comes back (lib tree) -> F1
#   M3 a second post sneaks into an exempt file        -> F2
#   M4 an exempt file is deleted                       -> F3
#   M5 an exemption is classified as an `alert`        -> F4
#   M6 nothing to scan                                 -> F5
# and two controls must stay green: comments naming notify(), and Java's
# Object.notify() with no argument, are not posts.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-fleet-alerts-guard.py"
POLICY="$ROOT/1_cicd/src/data/fleet-alerts-guard.json"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fixture() {   # $1 = dir: every exempt poster of the real fleet, byte for byte
  local t="$1"
  mkdir -p "$t"
  python3 - "$POLICY" <<'PY' | while IFS= read -r p; do mkdir -p "$t/$(dirname "$p")"; cp "$ROOT/$p" "$t/$p"; done
import json, sys
for e in json.load(open(sys.argv[1]))["posters"]: print(e["path"])
PY
}

expect() {    # $1 = name, $2 = want rc (0|1), $3 = fixture, [$4 = policy], [$5 = code the output must name]
  local out rc
  out="$(python3 "$GUARD" "$3" "${4:-$POLICY}" 2>&1)"; rc=$?
  if [ "$rc" -ne "$2" ]; then fail "$1 (rc=$rc, want $2): $out"; return; fi
  if [ -n "${5:-}" ] && ! grep -qF "$5" <<<"$out"; then fail "$1 (red, but not for $5): $out"; return; fi
  ok "$1"
}

echo "== the real fleet =="
expect "the real tree is green" 0 "$ROOT"

echo "== control: the fixture is the real fleet's exempt posters, green =="
G="$WORK/green"; fixture "$G"
expect "fixture green" 0 "$G"

echo "== controls that must NOT read as a post =="
C="$WORK/ctl"; fixture "$C"
mkdir -p "$C/ac_cloud-example/app/src/main/java/x"
cat > "$C/ac_cloud-example/app/src/main/java/x/Quiet.kt" <<'EOF'
// nm.notify(1, n) used to live here, before FleetAlerts.
/* NotificationManagerCompat.from(ctx).notify(7, n) */
class Quiet { private val lock = Object(); fun wake() = synchronized(lock) { lock.notify() } }
EOF
expect "comments and Object.notify() are not posts" 0 "$C"

echo "== mutations: each must be RED =="
M="$WORK/m1"; fixture "$M"; mkdir -p "$M/ac_cloud-example/app/src/main/java/x"
cat > "$M/ac_cloud-example/app/src/main/java/x/Backup.kt" <<'EOF'
class Backup { fun failed(nm: android.app.NotificationManager, n: android.app.Notification) { nm.notify(42, n) } }
EOF
expect "M1 a new app posts its own alert" 1 "$M" "" "F1 ac_cloud-example/"

M="$WORK/m2"; fixture "$M"; mkdir -p "$M/ab_cloud-libs-shared/libs/appstore/src/main/java/x"
cat > "$M/ab_cloud-libs-shared/libs/appstore/src/main/java/x/ConstellationWorker.kt" <<'EOF'
private fun notify(ctx: Context, id: Int, title: String, text: String) {
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(id, Notification.Builder(ctx, "constellation").setContentTitle(title).build())
}
EOF
expect "M2 the pre-#777 Store worker in a lib tree" 1 "$M" "" "F1 ab_cloud-libs-shared/"

M="$WORK/m3"; fixture "$M"
F="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["posters"][0]["path"])' "$POLICY")"
printf '\nfun sneaky(nm: android.app.NotificationManager, n: android.app.Notification) { nm.notify(99, n) }\n' >> "$M/$F"
expect "M3 a second post in an exempt file" 1 "$M" "" "F2 $F"

M="$WORK/m4"; fixture "$M"; rm -f "$M/$F"
expect "M4 an exempt file is gone" 1 "$M" "" "F3 $F"

M="$WORK/m5"; fixture "$M"
python3 - "$POLICY" "$WORK/alert-kind.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])); d["posters"][0]["kind"] = "alert"; json.dump(d, open(sys.argv[2], "w"))
PY
expect "M5 an exemption classified as an alert" 1 "$M" "$WORK/alert-kind.json" "F4"

mkdir -p "$WORK/empty"
expect "M6 nothing to scan is not a pass" 1 "$WORK/empty" "" "F5"

echo
if [ "$FAILURES" -ne 0 ]; then echo "fleet-alerts-guard.test: $FAILURES failure(s)"; exit 1; fi
echo "fleet-alerts-guard.test: all cases behaved"
