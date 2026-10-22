#!/usr/bin/env bash
# ╔═════════════════════════════════════════════════════════════════╗
# ║ cloud-c3-webserver — :8002 rendered at Galaxy A37 density.        ║
# ╚═════════════════════════════════════════════════════════════════╝
#
# THE FAILURE THIS EXISTS FOR. :8002 served a custom page the owner never asked for. The owner's
# rule: it serves ONLY /storage/emulated/0, with the EXACT web UI of the Linux webserver — a folder
# tree on the LEFT, a viewer on the RIGHT, a TOOLBAR on top. String checks cannot tell a drawn page
# from a page that merely contains the right ids, so this starts the REAL server (server/examples/
# demo.rs: the real `start`, the real build.json ports, a stub of the two Android hooks) and loads
# it in headless Chrome with MOBILE emulation at the owner's phone — Samsung Galaxy A37, 384 x 832 dp
# at 450 dpi, device pixel ratio 2.8125, a 1080 x 2340 px screenshot — through test/render-a37.mjs:
#
#   R1  `/` IS THE LINUX FRONTEND. Its title is "File Browser", there is no custom nav or tab, the
#       folder tree is the full-width panel on a phone (left of the viewer on a desktop: see
#       test-c3-webserver-responsive.sh), laid out at the device width, the toolbar (search, Dot files, Raw) is on top of the
#       viewer, the tree lists the served root, and the PNG is 1080 x 2340 and drawn.
#   R2  A MARKDOWN FILE OPENED FROM THE TREE RENDERS IN THE RIGHT PANE (the vendored marked + css).
#   R3  :8003 is still the diagnostics page.
#   R4  NOTHING LEAVES THE PHONE: every request of every page is to 127.0.0.1.
#
# NO SKIP: a machine without Chrome, node, cargo or python3 is an ERROR, not a pass. The shots go to
# $SHOTS_DIR (default: a temp dir, printed) so a run can be looked at and kept.
set -uo pipefail

APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

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
CHROME_BIN="$(find_chrome)" || { echo "ERROR no Chrome/Chromium on this machine (set CHROME=…): the A37 render cannot be taken, and an untaken render is not a pass"; exit 1; }
for t in cargo node python3; do
  command -v "$t" >/dev/null 2>&1 || { echo "ERROR $t absent — a verdict from a missing tool is not a verdict"; exit 1; }
done

WORK="$(mktemp -d)"
SHOTS="${SHOTS_DIR:-$WORK/shots}"; mkdir -p "$SHOTS"
SRV_PID=""
cleanup() { [ -n "$SRV_PID" ] && kill "$SRV_PID" 2>/dev/null; rm -rf "$WORK"; }
trap cleanup EXIT

ROOT="$WORK/drive"; mkdir -p "$ROOT/Documents" "$ROOT/DCIM"
printf '# Trip notes\n\nA **bold** line and `inline code`.\n\n| stop | km |\n|------|----|\n| A | 12 |\n' > "$ROOT/Documents/readme.md"
printf '{"device":"A37","ok":true}\n' > "$ROOT/Documents/data.json"
printf 'photo\n' > "$ROOT/DCIM/img.txt"; printf 'x\n' > "$ROOT/.hidden"

echo "── cloud-c3-webserver :8002 at Galaxy A37 density (384x832 dp @ 2.8125, 1080x2340 px) ──"
( cd "$APP/server" && CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$WORK/target}" cargo build -q --example demo 2>"$WORK/build.log" ) \
  || { echo "ERROR the demo server did not build:"; tail -20 "$WORK/build.log"; exit 1; }
"${CARGO_TARGET_DIR:-$WORK/target}/debug/examples/demo" "$ROOT" >"$WORK/server.log" 2>&1 &
SRV_PID=$!
for _ in $(seq 1 100); do grep -q '^READY' "$WORK/server.log" 2>/dev/null && break; sleep 0.1; done
read -r _ DRIVE DIAG < <(grep -m1 '^READY' "$WORK/server.log") || { echo "ERROR the demo server did not start:"; cat "$WORK/server.log"; exit 1; }

# The hard facts before any browser: `/` on the drive port is the Linux UI and no custom page.
BODY="$(python3 - "$DRIVE" <<'PY'
import sys, urllib.request
print(urllib.request.urlopen("http://127.0.0.1:%s/" % sys.argv[1]).read().decode())
PY
)"
case "$BODY" in *"<title>File Browser</title>"*"id=\"sidebar\""*"id=\"viewer-toolbar\""*) pass "H1 GET / on :$DRIVE is the Linux file-browser page" ;; *) fail "H1 GET / on :$DRIVE is not the Linux file-browser page" ;; esac
for custom in '<nav' '__tab__' 'Configs' 'Engines' '__api__/routes'; do
  case "$BODY" in *"$custom"*) fail "H2 the root page carries custom UI: $custom" ;; esac
done
[ "$FAILURES" -eq 0 ] && pass "H2 the root page carries no custom nav, tab, Configs or Engines"

OUT="$(node "$APP/test/render-a37.mjs" "$CHROME_BIN" "http://127.0.0.1:$DRIVE/" "http://127.0.0.1:$DIAG/" "$SHOTS" readme.md Documents "Trip notes" 2>"$WORK/render.err")"
RC=$?
echo "$OUT" | python3 -c '
import json, sys
for l in sys.stdin:
    l = l.strip()
    if not l.startswith("{"): continue
    r = json.loads(l)
    print("  %s  %s %s" % ("PASS" if r["ok"] else "FAIL", r["id"], r["msg"]))
'
[ "$RC" -eq 0 ] || { FAILURES=$((FAILURES + 1)); tail -5 "$WORK/render.err"; }

echo "  shots: $SHOTS"; ls "$SHOTS" | sed 's/^/    /'
echo
if [ "$FAILURES" -eq 0 ]; then echo "── test-c3-webserver-render: all green ──"; else echo "── test-c3-webserver-render: $FAILURES FAILED ──"; exit 1; fi
