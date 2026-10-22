#!/bin/bash
# emulator-local-names.sh — runs ON a booted emulator (adb) from .github/workflows/test-local-names-emulator.yml.
#
#   $1 Cloud Browser APK   $2 Cloud C3 WebServer APK   $3 output dir (screenshots, logcat, ui dumps)
#
# What it proves, in the order it runs:
#   E1 force-stop the WebServer, its drive (tcp 8002) and DNS (udp $DNS_PORT) are gone
#   E2 Cloud Browser opens http://emulated.app: the page loads, the browser's own "private DNS did not answer"
#      page is NOT shown, and the keep-alive service is in the foreground with the servers bound again
#   E3 the same after a BOOT_COMPLETED and a MY_PACKAGE_REPLACED broadcast to the stopped app (the boot path)
#   E4 http://127.0.0.1:8002/#/ renders in Cloud Browser like in a stock browser (Chrome, when the image has it):
#      console output of both is captured, the two screenshots are compared
set -u
BROWSER_APK="$1"; WS_APK="$2"; OUT="$3"; mkdir -p "$OUT"
BPKG=com.diegonmarcos.cloudbrowser; WPKG=com.diegonmarcos.cloudwebserver
fails=0
# The private DNS port is the declaration's (ac_cloud-c3-webserver/build.json::serving.dns.port), never typed here.
DNS_PORT=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["serving"]["dns"]["port"])' "$(dirname "$0")/../../ac_cloud-c3-webserver/build.json")
ok()  { echo "PASS  $1"; }
bad() { echo "::error title=local names emulator test::$1"; echo "FAIL  $1"; fails=$((fails + 1)); }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml 2>/dev/null > "$OUT/$1.xml" || true; }
# Anchored to 127.0.0.1: the system's own mDNS daemon binds udp $DNS_PORT on every address, so an unanchored match is always true.
tcp_listening() { local h; h=$(printf '%04X' "$1"); adb shell cat /proc/net/tcp 2>/dev/null | tr -d '\r' | awk -v p="0100007F:$h" '$4=="0A" && toupper($2)==p {f=1} END{exit !f}'; }
udp_bound() { local h; h=$(printf '%04X' "$1"); adb shell cat /proc/net/udp 2>/dev/null | tr -d '\r' | awk -v p="0100007F:$h" 'toupper($2)==p {f=1} END{exit !f}'; }
# Tap the first node of the current screen whose text matches $1 (a regex); returns 1 when none does.
tap_text() {
  adb shell uiautomator dump /sdcard/t.xml >/dev/null 2>&1
  local xy; xy=$(adb shell cat /sdcard/t.xml 2>/dev/null | python3 -c '
import re, sys
x = sys.stdin.read()
for m in re.finditer(r"<node[^>]*?text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", x):
    if re.fullmatch(sys.argv[1], m.group(1)):
        a, b, c, d = map(int, m.groups()[1:]); print((a + c) // 2, (b + d) // 2); break
' "$1")
  [ -n "$xy" ] && adb shell input tap $xy
}
wait_for() { local n="$1"; shift; for _ in $(seq 1 "$n"); do "$@" && return 0; sleep 1; done; return 1; }
fg_service() { adb shell dumpsys activity services "$WPKG" 2>/dev/null | tr -d '\r' | grep -q "isForeground=true"; }

adb logcat -c || true
adb install -r -g "$BROWSER_APK" >/dev/null || { bad "install of Cloud Browser failed"; exit 2; }
adb install -r -g "$WS_APK" >/dev/null || { bad "install of Cloud C3 WebServer failed"; exit 2; }
adb shell settings put global window_animation_scale 0 || true
# The browser logs the console and layout numbers of loopback pages only when this global is 1 (off in every shipped build).
adb shell settings put global cloud_browser_page_log 1 || true

# ── E1 ───────────────────────────────────────────────────────────────────────
adb shell am start -W -n "$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$WPKG" | tr -d '\r' | tail -1)" >/dev/null
wait_for 40 tcp_listening 8002 && ok "E0 the WebServer app binds 8002 when opened" || bad "E0 the WebServer app never bound tcp 8002"
adb shell input keyevent KEYCODE_HOME
adb shell am force-stop "$WPKG"; sleep 2
if tcp_listening 8002 || udp_bound "$DNS_PORT"; then bad "E1 the servers are still bound after force-stop"; else ok "E1 force-stopped: tcp 8002 and udp $DNS_PORT are gone"; fi

# ── E2 ───────────────────────────────────────────────────────────────────────
adb shell am start -W -a android.intent.action.VIEW -d http://emulated.app -p "$BPKG" >/dev/null
wait_for 30 tcp_listening 8002 && ok "E2 opening emulated.app brought the servers back (browser started the service)" || bad "E2 tcp 8002 never came back after the browser opened emulated.app"
sleep 12
tap_text "Cancel|CANCEL" || true; sleep 1
shot e2-emulated-app; dump e2-emulated-app
if grep -q "did not answer\|private name of this phone" "$OUT/e2-emulated-app.xml" 2>/dev/null; then bad "E2 the browser shows its private-name error page"; else ok "E2 no private-name error page"; fi
if grep -qiE "emulated|storage|File Browser|Index of" "$OUT/e2-emulated-app.xml" 2>/dev/null; then ok "E2 the drive page content is on screen"; else bad "E2 nothing of the drive page is on screen (see e2-emulated-app.png)"; fi
fg_service && ok "E2 KeepAliveService is a foreground service" || bad "E2 KeepAliveService is not in the foreground"
udp_bound "$DNS_PORT" && ok "E2 the private DNS (udp $DNS_PORT) is bound again" || bad "E2 udp $DNS_PORT is not bound"

# ── E3 ───────────────────────────────────────────────────────────────────────
# After a force-stop the package is in the "stopped" state and gets no broadcast until something starts it, so the
# boot path is tested the way a real boot reaches it: the app is not stopped. First the process DIES (am crash does
# not set the stopped state): START_STICKY must bring the servers back with nobody's help.
adb shell am crash "$WPKG" >/dev/null 2>&1
sleep 2
if wait_for 40 tcp_listening 8002 && wait_for 10 udp_bound "$DNS_PORT"; then ok "E3 the service restarted itself after its process died"; else bad "E3 the servers did not come back after the process died"; fi
# Then the boot and update broadcasts reach the receiver (protected broadcasts: only root may send them, so adb root first).
adb root >/dev/null 2>&1; adb wait-for-device; sleep 3
# Then the boot and update broadcasts reach the receiver, which starts the service (its log line is the proof).
for act in android.intent.action.BOOT_COMPLETED android.intent.action.MY_PACKAGE_REPLACED; do
  adb logcat -c || true
  adb shell am broadcast -a "$act" -n "$WPKG/.BootReceiver" 2>&1 | tr -d '\r' | tee "$OUT/e3-broadcast-${act##*.}.txt"
  sleep 3
  if adb logcat -d -s C3BootReceiver:I | grep -q "start keep-alive service on $act"; then ok "E3 the receiver ran for $act and started the service"; else bad "E3 the receiver did not run for $act"; fi
  wait_for 20 tcp_listening 8002 && wait_for 5 udp_bound "$DNS_PORT" || bad "E3 the servers are not bound after $act"
done

# ── E4 ───────────────────────────────────────────────────────────────────────
# The servers must be up for the comparison to mean anything (a refused connection renders alike in both).
wait_for 5 tcp_listening 8002 || { adb shell am start -W -a android.intent.action.VIEW -d http://emulated.app -p "$BPKG" >/dev/null; wait_for 30 tcp_listening 8002 || bad "E4 the servers are not up for the render comparison"; }
adb shell am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:8002/#/' -p "$BPKG" >/dev/null
sleep 12
tap_text "Cancel|CANCEL" || true; sleep 1
shot e4-cloud-browser; dump e4-cloud-browser
adb logcat -d -v threadtime > "$OUT/logcat.txt"
adb logcat -d -s LocalPageConsole:D LocalPageMetrics:D > "$OUT/console-cloud-browser.txt" 2>/dev/null || true
echo "--- Cloud Browser console and layout metrics for the page:"; cat "$OUT/console-cloud-browser.txt"
if grep -E "LocalPageConsole" "$OUT/console-cloud-browser.txt" | grep -qE "ERROR|Uncaught|ReferenceError|SyntaxError|TypeError|Failed to load"; then
  bad "E4 Cloud Browser logged page errors for 127.0.0.1:8002 (console-cloud-browser.txt)"
else ok "E4 no console errors in Cloud Browser for 127.0.0.1:8002"; fi
if grep -q "LocalPageMetrics" "$OUT/console-cloud-browser.txt"; then ok "E4 the page reported its layout metrics"; else bad "E4 no layout metrics from the page (it never finished loading?)"; fi

CHROME=com.android.chrome
if adb shell pm list packages "$CHROME" | grep -q "$CHROME"; then
  adb shell am force-stop "$CHROME"
  adb shell 'echo "chrome --no-first-run --disable-fre --no-default-browser-check --disable-notifications" > /data/local/tmp/chrome-command-line; chmod 755 /data/local/tmp/chrome-command-line' || true
  adb shell am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:8002/#/' -p "$CHROME" >/dev/null
  sleep 10
  for _ in 1 2 3 4; do tap_text "No thanks|NO THANKS|Use without an account|Got it|Not now|Skip|Accept & continue|No, thanks" || break; sleep 2; done
  sleep 6; shot e4-chrome; dump e4-chrome
  if grep -q "ERR_CONNECTION_REFUSED\|Webpage not available" "$OUT/e4-cloud-browser.xml" "$OUT/e4-chrome.xml" 2>/dev/null; then
    bad "E4 a browser shows a connection error instead of the page (the comparison would be vacuous)"
  elif python3 "$(dirname "$0")/compare_renders.py" "$OUT/e4-cloud-browser.png" "$OUT/e4-chrome.png" "$OUT/e4-diff.txt"; then
    ok "E4 Cloud Browser renders 127.0.0.1:8002/#/ like Chrome ($(cat "$OUT/e4-diff.txt"))"
  else bad "E4 Cloud Browser's render differs from Chrome's ($(cat "$OUT/e4-diff.txt" 2>/dev/null))"; fi
else
  bad "E4 this system image has no Chrome to compare against"
fi

# ── E5 (#1112) the embedded WebView of Cloud C3 WebServer uses the phone layout, not a forced desktop one ──
adb shell am force-stop "$WPKG"; sleep 2
adb shell am start -W -n "$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$WPKG" | tr -d '\r' | tail -1)" >/dev/null
sleep 10; shot e5-webserver-embedded; dump e5-webserver-embedded
SCREEN_W=$(adb shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f1)
# every on-screen element must sit inside the screen width: a desktop viewport shrinks or overflows the page
WIDEST=$(grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,' "$OUT/e5-webserver-embedded.xml" | grep -oE '\]\[[0-9]+,' | tr -dc '0-9\n' | sort -n | tail -1)
if [ -n "$WIDEST" ] && [ -n "$SCREEN_W" ] && [ "$WIDEST" -le "$SCREEN_W" ]; then ok "E5 the embedded view lays out within the ${SCREEN_W}px screen (widest element ends at ${WIDEST}px)"
else bad "E5 the embedded view is wider than the screen (widest ${WIDEST:-?} vs ${SCREEN_W:-?}); see e5-webserver-embedded.png"; fi

adb logcat -d -v threadtime > "$OUT/logcat.txt"
[ "$fails" -eq 0 ] || { echo "$fails check(s) failed"; exit 1; }
echo "all local-name emulator checks passed"
