#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ cloud-c3-webserver — the pages, rendered at Galaxy A37 density.         ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# THE FAILURE THIS EXISTS FOR. Every check of the shell and the ported
# renderers so far reads strings or runs the pure block under node; none of
# them draws a page. A tab that overflows the owner's screen, a switch too small
# to hit, a Markdown page that renders blank, all pass those. This starts the
# REAL server (server/examples/demo.rs: the real routes, engines and ports; a
# stub of the two Android hooks), loads its pages in headless Chrome at the
# owner's phone — Samsung Galaxy A37, 384 x 832 dp at 450 dpi, device pixel
# ratio 2.8125, a 1080 x 2340 px screenshot — and asserts:
#
#   S1  EVERY SHOT IS A37-SIZED. Each PNG is exactly 1080 x 2340 px and not a
#       near-empty file.
#   S2  NOTHING OVERFLOWS SIDEWAYS. In a 384 dp wide frame no page scrolls
#       horizontally (Pages, API, Home, Files, Configs ▸ About, Configs ▸
#       Engines, the file browser, the Markdown and JSON renderers, the
#       diagnostics page).
#   S3  THE PAGES ARE THE FEATURES. Pages draws a block per served endpoint with
#       a name link and a port link each; Configs draws its two pages; Engines
#       draws a row per registry entry with a switch where the registry says
#       one makes sense; the Markdown and JSON pages carry their bundled
#       renderers' output; the diagnostics page draws the bridge's data.
#   S4  THE TAPS CAN BE HIT. Every link and switch of Pages and Engines is at
#       least 28 dp tall and wide (the bottom nav is 56).
#   S5  NOTHING IS FETCHED FROM THE INTERNET. No page of the set requests a host
#       that is not the phone's own (checked on the page's resource list).
#
# The shots are written to $SHOTS_DIR (default: a temp dir, printed), so a run
# can be looked at and kept. SKIPPED with a message when no Chrome/Chromium is
# on the machine (CI's ubuntu has google-chrome); python3 and cargo are needed.
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
  for c in /opt/pw-browsers/chromium_headless_shell-*/chrome-linux/headless_shell /opt/pw-browsers/chromium-*/chrome-linux/chrome /opt/pw-browsers/chromium-*/chrome-linux64/chrome; do
    [ -x "$c" ] && { echo "$c"; return 0; }
  done
  return 1
}
CHROME_BIN="$(find_chrome)" || { echo "  SKIP  no Chrome/Chromium on this machine — the A37 shots are not taken (set CHROME=…)"; exit 0; }
command -v cargo >/dev/null 2>&1 || { echo "  SKIP  cargo absent — the demo server cannot be built"; exit 0; }
command -v python3 >/dev/null 2>&1 || { echo "ERROR python3 absent"; exit 1; }

WORK="$(mktemp -d)"
SHOTS="${SHOTS_DIR:-$WORK/shots}"; mkdir -p "$SHOTS"
SRV_PID=""
cleanup() { [ -n "$SRV_PID" ] && kill "$SRV_PID" 2>/dev/null; rm -rf "$WORK"; }
trap cleanup EXIT

# A37: 1080 x 2340 px at 450 dpi -> 384 x 832 dp, device pixel ratio 450/160.
W_DP=384; H_DP=832; DPR=2.8125; W_PX=1080; H_PX=2340

# ── the served root: a Markdown file, a JSON file, a YAML file, a dotfile, an index-less folder ──
ROOT="$WORK/drive"; mkdir -p "$ROOT/Documents" "$ROOT/DCIM"
cat > "$ROOT/Documents/readme.md" <<'EOF'
# Trip notes

A **bold** line, a [link](https://example.invalid/) and `inline code`.

| stop | km |
|------|----|
| A    | 12 |
| B    | 30 |

- first
- second
EOF
printf '{"device":"A37","apps":[{"id":"browser","port":8002},{"id":"termux","port":8000}],"ok":true}\n' > "$ROOT/Documents/data.json"
printf 'name: demo\nitems:\n  - a\n  - b\n' > "$ROOT/Documents/conf.yaml"
printf 'x\n' > "$ROOT/.hidden"; printf 'photo\n' > "$ROOT/DCIM/img.txt"

echo "── cloud-c3-webserver at Galaxy A37 density (${W_DP}x${H_DP} dp @ ${DPR}, ${W_PX}x${H_PX} px) ──"
( cd "$APP/server" && CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$WORK/target}" cargo build -q --example demo 2>"$WORK/build.log" ) \
  || { echo "ERROR the demo server did not build:"; tail -20 "$WORK/build.log"; exit 1; }
DEMO="${CARGO_TARGET_DIR:-$WORK/target}/debug/examples/demo"
"$DEMO" "$ROOT" "$WORK/state" >"$WORK/server.log" 2>&1 &
SRV_PID=$!
for _ in $(seq 1 100); do grep -q '^READY' "$WORK/server.log" 2>/dev/null && break; sleep 0.1; done
read -r _ DRIVE DIAG < <(grep -m1 '^READY' "$WORK/server.log") || { echo "ERROR the demo server did not start:"; cat "$WORK/server.log"; exit 1; }

# chrome-headless-shell honours a 384 px window; full Chrome's new headless has a ~500 px minimum, so its shots are
# wider than the phone (the probe below measures in a real 384 px frame either way).
HEADLESS=--headless=new; case "$CHROME_BIN" in *headless_shell*|*headless-shell*) HEADLESS=--headless ;; esac
shoot() {  # shoot <name> <url>
  "$CHROME_BIN" $HEADLESS --no-sandbox --disable-gpu --hide-scrollbars --disable-dev-shm-usage \
    --window-size="$W_DP,$H_DP" --force-device-scale-factor="$DPR" --virtual-time-budget=6000 \
    --screenshot="$SHOTS/$1.png" "$2" >/dev/null 2>"$WORK/chrome-$1.log"
}
png_size() { python3 -c "
import struct,sys
b=open(sys.argv[1],'rb').read(32)
print(*struct.unpack('>II', b[16:24])) if b[:8]==b'\x89PNG\r\n\x1a\n' else print(0,0)" "$1"; }

PAGES="pages:/__tab__/pages api:/__tab__/api home:/__tab__/home files:/__tab__/files configs-about:/__tab__/configs configs-engines:/__tab__/configs#engines"
PAGES="$PAGES browse:/__browse__ markdown:/Documents/readme.md json:/Documents/data.json yaml:/Documents/conf.yaml"
for p in $PAGES; do shoot "${p%%:*}" "http://127.0.0.1:$DRIVE${p#*:}"; done
shoot diagnostics "http://127.0.0.1:$DIAG/"

for f in "$SHOTS"/*.png; do
  read -r w h < <(png_size "$f"); n="$(basename "$f" .png)"
  if [ "$w" = "$W_PX" ] && [ "$h" = "$H_PX" ] && [ "$(stat -c %s "$f")" -gt 12000 ]; then :; else fail "S1 $n.png is ${w}x${h} (want ${W_PX}x${H_PX}, a drawn page): $(tail -2 "$WORK/chrome-$n.log")"; fi
done
[ "$FAILURES" -eq 0 ] && pass "S1 all $(ls "$SHOTS"/*.png | wc -l) shots are ${W_PX}x${H_PX} px (A37) and drawn"

# ── the probe: a page in the served root that frames each page at 384 dp and measures it ──
cat > "$ROOT/probe.html" <<EOF
<!doctype html><meta charset=utf-8><body style="margin:0"><pre id=out>running</pre><script>
var PAGES = ${PROBE_PAGES:-[]};
var out = {};
function measure(name, url, port, next) {
  var f = document.createElement('iframe');
  f.style.cssText = 'width:${W_DP}px;height:${H_DP}px;border:0;position:absolute;left:0;top:0';
  f.src = (port ? 'http://127.0.0.1:' + port : '') + url;
  f.onload = function () {
    setTimeout(function () {
      try {
        var d = f.contentDocument, w = f.contentWindow;
        var small = [];
        d.querySelectorAll('a[data-open], .sw, nav button, #gear, .seg button').forEach(function (e) {
          var r = e.getBoundingClientRect(); if (r.width && (r.height < 28 || r.width < 28)) small.push((e.className || e.tagName) + ':' + Math.round(r.width) + 'x' + Math.round(r.height));
        });
        out[name] = { sw: d.documentElement.scrollWidth, iw: w.innerWidth,
          rows: d.querySelectorAll('.row').length, open: d.querySelectorAll('[data-open]').length,
          eng: d.querySelectorAll('.eng').length, sws: d.querySelectorAll('.sw').length, seg: d.querySelectorAll('.seg button').length,
          text: (d.body.innerText || '').length, small: small,
          md: !!d.querySelector('.markdown-body h1'), table: d.querySelectorAll('table').length,
          res: w.performance.getEntriesByType('resource').map(function (x) { return x.name; }).filter(function (n) { return !/^https?:\/\/127\.0\.0\.1[:\/]/.test(n); }) };
      } catch (e) { out[name] = { error: String(e) }; }
      f.remove(); next();
    }, 1500);
  };
  document.body.appendChild(f);
}
var i = 0;
(function step() {
  if (i >= PAGES.length) { document.getElementById('out').textContent = 'PROBE ' + JSON.stringify(out); return; }
  var p = PAGES[i++]; measure(p[0], p[1], p[2], step);
})();
</script>
EOF
PROBE_JSON="[[\"pages\",\"/__tab__/pages\"],[\"api\",\"/__tab__/api\"],[\"home\",\"/__tab__/home\"],[\"files\",\"/__tab__/files\"],[\"configs-about\",\"/__tab__/configs\"],[\"configs-engines\",\"/__tab__/configs#engines\"],[\"browse\",\"/__browse__\"],[\"markdown\",\"/Documents/readme.md\"],[\"json\",\"/Documents/data.json\"],[\"yaml\",\"/Documents/conf.yaml\"],[\"diagnostics\",\"/\",$DIAG]]"
sed -i "s|var PAGES = \[\];|var PAGES = $PROBE_JSON;|" "$ROOT/probe.html"
"$CHROME_BIN" $HEADLESS --no-sandbox --disable-gpu --disable-dev-shm-usage --virtual-time-budget=60000 --dump-dom \
  --disable-web-security --user-data-dir="$WORK/profile" "http://127.0.0.1:$DRIVE/probe.html" >"$WORK/probe.dom" 2>"$WORK/probe.err"
python3 - "$WORK/probe.dom" "$ROOT" <<'PY' || FAILURES=$((FAILURES + 1))
import html, json, re, sys
dom = open(sys.argv[1], encoding="utf-8", errors="replace").read()
m = re.search(r'PROBE (\{.*?\})\s*</pre>', dom, re.S)
if not m:
    print("  FAIL  S2 the probe did not finish: " + dom[-300:].replace("\n", " ")); sys.exit(1)
res = json.loads(html.unescape(m.group(1)))
bad = []
want = ["pages", "api", "home", "files", "configs-about", "configs-engines", "browse", "markdown", "json", "yaml", "diagnostics"]
for k in want:
    if k not in res: bad.append("S2 page %s was not measured" % k); continue
    r = res[k]
    if "error" in r: bad.append("S2 %s: %s" % (k, r["error"])); continue
    if r["sw"] > r["iw"]: bad.append("S2 %s scrolls sideways: content %d px in a %d px frame" % (k, r["sw"], r["iw"]))
    if r["text"] < 40: bad.append("S3 %s drew almost nothing (%d characters)" % (k, r["text"]))
    if r["res"]: bad.append("S5 %s requests off-phone resources: %s" % (k, r["res"][:3]))
p = res.get("pages", {})
if p.get("open", 0) != 8: bad.append("S3 Pages draws %s [data-open] links, want 8 (a name and a port per served endpoint, four endpoints)" % p.get("open"))
if p.get("small"): bad.append("S4 Pages has taps under 28 dp: %s" % p["small"][:4])
c = res.get("configs-about", {})
if c.get("seg") != 2: bad.append("S3 Configs draws %s page buttons, want About and Engines" % c.get("seg"))
e = res.get("configs-engines", {})
if e.get("seg") != 2: bad.append("S3 Configs ▸ Engines lost its page buttons")
if e.get("eng", 0) < 20: bad.append("S3 Engines draws %s rows, want the whole registry" % e.get("eng"))
if e.get("sws", 0) < 11: bad.append("S3 Engines draws %s switches, want a switch per switchable engine" % e.get("sws"))
if e.get("small"): bad.append("S4 Engines has taps under 28 dp: %s" % e["small"][:4])
if not res.get("markdown", {}).get("md"): bad.append("S3 the Markdown page did not render the document (no .markdown-body h1)")
if res.get("json", {}).get("table", 0) < 1: bad.append("S3 the JSON page drew no table")
if res.get("diagnostics", {}).get("rows", 0) < 6: bad.append("S3 the diagnostics page drew %s rows from the bridge" % res.get("diagnostics", {}).get("rows"))
for b in bad: print("  FAIL  " + b)
if not bad:
    print("  PASS  S2 no page scrolls sideways in a 384 dp frame (11 pages)")
    print("  PASS  S3 Pages: %d links to %d served pages; Configs: About + Engines (%d rows, %d switches); Markdown, JSON and diagnostics render" % (p["open"], p["open"] // 2, e["eng"], e["sws"]))
    print("  PASS  S4 every tap on Pages and Engines is at least 28 dp each way")
    print("  PASS  S5 no page requests a host that is not the phone's own")
sys.exit(1 if bad else 0)
PY

echo "  shots: $SHOTS"
ls "$SHOTS" | sed 's/^/    /'
if [ -n "${KEEP_SHOTS_TO:-}" ]; then mkdir -p "$KEEP_SHOTS_TO" && cp "$SHOTS"/*.png "$KEEP_SHOTS_TO"/ && echo "  kept in $KEEP_SHOTS_TO"; fi
echo
if [ "$FAILURES" -eq 0 ]; then echo "── test-c3-webserver-screenshot: all green ──"; else echo "── test-c3-webserver-screenshot: $FAILURES FAILED ──"; exit 1; fi
