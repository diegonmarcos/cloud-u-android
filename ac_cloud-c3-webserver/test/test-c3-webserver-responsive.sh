#!/usr/bin/env bash
# ╔═════════════════════════════════════════════════════════════════╗
# ║ cloud-c3-webserver — every page responsive: no sideways scroll at 360 px. ║
# ╚═════════════════════════════════════════════════════════════════╝
#
# THE FAILURE THIS EXISTS FOR. The pages were served in a forced desktop viewport (the file browser's
# <meta viewport content="width=1200"> and html{min-width:900px}), so on a phone they were shrunk or
# scrolled sideways. This starts the REAL server (server/examples/demo.rs) and loads every page in
# headless Chrome (test/responsive-check.mjs) at 360x800 (phone) and 1440x900 (desktop):
#   - FAILS when any page at 360 px scrolls horizontally, lays out wider than 360 px, or has a viewport
#     meta other than width=device-width;
#   - FAILS when the desktop file browser loses its side-by-side tree and viewer, or the phone one does not
#     give the viewer the full width after a file opens.
# One PNG per page and size lands in $SHOTS_DIR (the ship workflow uploads it). NO SKIP: a machine without
# Chrome, node or cargo is an ERROR, not a pass.
set -uo pipefail

APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILURES=0
find_chrome() {
  local c
  for c in "${CHROME:-}" google-chrome google-chrome-stable chromium chromium-browser; do
    [ -n "$c" ] && command -v "$c" >/dev/null 2>&1 && { command -v "$c"; return 0; }
  done
  for c in /opt/pw-browsers/chromium-*/chrome-linux/chrome /opt/pw-browsers/chromium-*/chrome-linux64/chrome; do
    [ -x "$c" ] && { echo "$c"; return 0; }
  done
  return 1
}
CHROME_BIN="$(find_chrome)" || { echo "ERROR no Chrome/Chromium on this machine (set CHROME=…): an untaken render is not a pass"; exit 1; }
for t in cargo node python3; do
  command -v "$t" >/dev/null 2>&1 || { echo "ERROR $t absent — a verdict from a missing tool is not a verdict"; exit 1; }
done

WORK="$(mktemp -d)"
SHOTS="${SHOTS_DIR:-$WORK/shots}"; mkdir -p "$SHOTS"
SRV_PID=""
cleanup() { [ -n "$SRV_PID" ] && kill "$SRV_PID" 2>/dev/null; rm -rf "$WORK"; }
trap cleanup EXIT

ROOT="$WORK/drive"; mkdir -p "$ROOT/Documents" "$ROOT/DCIM"
printf '# Trip notes\n\n| stop | km | a very long column header | another long header |\n|---|---|---|---|\n| A | 12 | some quite long cell content to force the table wide | more more |\n\n```\n%s\n```\n' "$(printf 'x%.0s' $(seq 1 300))" > "$ROOT/Documents/readme.md"
printf '{"device":"A37","ok":true,"rows":[{"a":1,"b":"x"},{"a":2,"b":"y"}]}\n' > "$ROOT/Documents/data.json"
python3 - "$ROOT/Documents/wide.json" <<'PY'
import json, sys
json.dump([{"col_number_%d" % i: "value_with_some_long_text_%d_xxxxxxxxxxxxxxxx" % i for i in range(8)} for _ in range(3)], open(sys.argv[1], "w"))
PY
printf 'a: 1\nb:\n  c: 2\n' > "$ROOT/Documents/c.yaml"; printf 'photo\n' > "$ROOT/DCIM/img.txt"

echo "── cloud-c3-webserver responsive: 360x800 and 1440x900, every page ──"
( cd "$APP/server" && CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$WORK/target}" cargo build -q --example demo 2>"$WORK/build.log" ) \
  || { echo "ERROR the demo server did not build:"; tail -20 "$WORK/build.log"; exit 1; }
"${CARGO_TARGET_DIR:-$WORK/target}/debug/examples/demo" "$ROOT" >"$WORK/server.log" 2>&1 &
SRV_PID=$!
for _ in $(seq 1 100); do grep -q '^READY' "$WORK/server.log" 2>/dev/null && break; sleep 0.1; done
read -r _ DRIVE DIAG < <(grep -m1 '^READY' "$WORK/server.log") || { echo "ERROR the demo server did not start:"; cat "$WORK/server.log"; exit 1; }

OUT="$(node "$APP/test/responsive-check.mjs" "$CHROME_BIN" "http://127.0.0.1:$DRIVE/" "http://127.0.0.1:$DIAG/" "$SHOTS" 2>"$WORK/resp.err")"
RC=$?
echo "$OUT" | python3 -c '
import json, sys
for l in sys.stdin:
    l = l.strip()
    if l.startswith("{"):
        r = json.loads(l); print("  %s  %s %s" % ("PASS" if r["ok"] else "FAIL", r["id"], r["msg"]))
'
[ "$RC" -eq 0 ] || { FAILURES=$((FAILURES + 1)); tail -5 "$WORK/resp.err"; }
echo "  shots: $SHOTS"
if [ "$FAILURES" -eq 0 ]; then echo "── test-c3-webserver-responsive: all green ──"; else echo "── test-c3-webserver-responsive: $FAILURES FAILED ──"; exit 1; fi
