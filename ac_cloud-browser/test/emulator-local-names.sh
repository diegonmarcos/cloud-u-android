#!/bin/bash
# emulator-local-names.sh — runs ON a booted emulator (adb) from .github/workflows/test-local-names-emulator.yml.
#
#   $1 Cloud Browser APK   $2 Cloud C3 WebServer APK   $3 output dir (screenshots, logcat, ui dumps)
#
# What it proves, in the order it runs:
#   E1 force-stop the WebServer, its drive (tcp 8002) and DNS (udp 5353) are gone
#   E2 Cloud Browser opens http://emulated.app: the page loads, the browser's own "private DNS did not answer"
#      page is NOT shown, and the keep-alive service is in the foreground with the servers bound again
#   E3 the same after a BOOT_COMPLETED and a MY_PACKAGE_REPLACED broadcast to the stopped app (the boot path)
#   E4 http://127.0.0.1:8002/#/ renders in Cloud Browser like in a stock browser (Chrome, when the image has it):
#      console output of both is captured, the two screenshots are compared
set -u
BROWSER_APK="$1"; WS_APK="$2"; OUT="$3"; mkdir -p "$OUT"
BPKG=com.diegonmarcos.cloudbrowser; WPKG=com.diegonmarcos.cloudwebserver
fails=0
ok()  { echo "PASS  $1"; }
bad() { echo "::error title=local names emulator test::$1"; echo "FAIL  $1"; fails=$((fails + 1)); }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml 2>/dev/null > "$OUT/$1.xml" || true; }
tcp_listening() { local h; h=$(printf '%04X' "$1"); adb shell cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | tr -d '\r' | awk -v p=":$h" '$4=="0A" && toupper($2) ~ p"$" {f=1} END{exit !f}'; }
udp_bound() { local h; h=$(printf '%04X' "$1"); adb shell cat /proc/net/udp /proc/net/udp6 2>/dev/null | tr -d '\r' | awk -v p=":$h" 'toupper($2) ~ p"$" {f=1} END{exit !f}'; }
wait_for() { local n="$1"; shift; for _ in $(seq 1 "$n"); do "$@" && return 0; sleep 1; done; return 1; }
fg_service() { adb shell dumpsys activity services "$WPKG" 2>/dev/null | tr -d '\r' | grep -q "isForeground=true"; }

adb logcat -c || true
adb install -r -g "$BROWSER_APK" >/dev/null || { bad "install of Cloud Browser failed"; exit 2; }
adb install -r -g "$WS_APK" >/dev/null || { bad "install of Cloud C3 WebServer failed"; exit 2; }
adb shell settings put global window_animation_scale 0 || true

# ── E1 ───────────────────────────────────────────────────────────────────────
adb shell am start -W -n "$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$WPKG" | tr -d '\r' | tail -1)" >/dev/null
wait_for 40 tcp_listening 8002 && ok "E0 the WebServer app binds 8002 when opened" || bad "E0 the WebServer app never bound tcp 8002"
adb shell input keyevent KEYCODE_HOME
adb shell am force-stop "$WPKG"; sleep 2
if tcp_listening 8002 || udp_bound 5353; then bad "E1 the servers are still bound after force-stop"; else ok "E1 force-stopped: tcp 8002 and udp 5353 are gone"; fi

# ── E2 ───────────────────────────────────────────────────────────────────────
adb shell am start -W -a android.intent.action.VIEW -d http://emulated.app -p "$BPKG" >/dev/null
wait_for 30 tcp_listening 8002 && ok "E2 opening emulated.app brought the servers back (browser started the service)" || bad "E2 tcp 8002 never came back after the browser opened emulated.app"
sleep 6; shot e2-emulated-app; dump e2-emulated-app
if grep -q "did not answer\|private name of this phone" "$OUT/e2-emulated-app.xml" 2>/dev/null; then bad "E2 the browser shows its private-name error page"; else ok "E2 no private-name error page"; fi
if grep -qiE "emulated|storage|File Browser|Index of" "$OUT/e2-emulated-app.xml" 2>/dev/null; then ok "E2 the drive page content is on screen"; else bad "E2 nothing of the drive page is on screen (see e2-emulated-app.png)"; fi
fg_service && ok "E2 KeepAliveService is a foreground service" || bad "E2 KeepAliveService is not in the foreground"
udp_bound 5353 && ok "E2 the private DNS (udp 5353) is bound again" || bad "E2 udp 5353 is not bound"

# ── E3 ───────────────────────────────────────────────────────────────────────
for act in android.intent.action.BOOT_COMPLETED android.intent.action.MY_PACKAGE_REPLACED; do
  adb shell am force-stop "$WPKG"; sleep 2
  adb shell am broadcast -a "$act" -n "$WPKG/.BootReceiver" >/dev/null 2>&1
  if wait_for 25 tcp_listening 8002 && wait_for 5 udp_bound 5353; then ok "E3 $act started the servers with no UI"; else bad "E3 $act did not start the servers"; fi
done

# ── E4 ───────────────────────────────────────────────────────────────────────
adb shell am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:8002/#/' -p "$BPKG" >/dev/null
sleep 8; shot e4-cloud-browser; dump e4-cloud-browser
adb logcat -d -v threadtime > "$OUT/logcat.txt"
BPID=$(adb shell pidof "$BPKG" | tr -d '\r' | awk '{print $1}')
grep -E "chromium|CONSOLE|Console" "$OUT/logcat.txt" | grep -E " $BPID +[0-9]+ " > "$OUT/console-cloud-browser.txt" || true
if grep -qE "Uncaught|ReferenceError|SyntaxError|TypeError|Failed to load resource|ERR_" "$OUT/console-cloud-browser.txt"; then
  bad "E4 Cloud Browser logged page errors for 127.0.0.1:8002 (console-cloud-browser.txt)"; head -20 "$OUT/console-cloud-browser.txt"
else ok "E4 no console errors in Cloud Browser for 127.0.0.1:8002"; fi

CHROME=com.android.chrome
if adb shell pm list packages "$CHROME" | grep -q "$CHROME"; then
  adb shell am force-stop "$CHROME"
  adb shell 'echo "chrome --no-first-run --disable-fre --no-default-browser-check --disable-notifications" > /data/local/tmp/chrome-command-line; chmod 755 /data/local/tmp/chrome-command-line' || true
  adb shell am start -W -a android.intent.action.VIEW -d 'http://127.0.0.1:8002/#/' -p "$CHROME" >/dev/null
  sleep 12; shot e4-chrome; dump e4-chrome
  if python3 "$(dirname "$0")/compare_renders.py" "$OUT/e4-cloud-browser.png" "$OUT/e4-chrome.png" "$OUT/e4-diff.txt"; then
    ok "E4 Cloud Browser renders 127.0.0.1:8002/#/ like Chrome ($(cat "$OUT/e4-diff.txt"))"
  else bad "E4 Cloud Browser's render differs from Chrome's ($(cat "$OUT/e4-diff.txt" 2>/dev/null))"; fi
else
  bad "E4 this system image has no Chrome to compare against"
fi

adb logcat -d -v threadtime > "$OUT/logcat.txt"
[ "$fails" -eq 0 ] || { echo "$fails check(s) failed"; exit 1; }
echo "all local-name emulator checks passed"
