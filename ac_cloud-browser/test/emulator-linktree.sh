#!/bin/bash
# emulator-linktree.sh (#1140) — runs ON a booted emulator (adb) from .github/workflows/test-linktree-emulator.yml.
#
#   $1 Cloud Browser APK   $2 output dir (screenshot, ui dump, console, logcat)   $3 page url (default the live linktree)
#
# Proves in a REAL WebView (not Chromium) that the linktree's JS-driven link cards render:
#   L1 the page finishes loading and reports its layout (LocalPageMetrics)
#   L2 the WebView console of the page is captured (LocalPageConsole) and carries no uncaught error
#   L3 more links are on the page than its static HTML holds: the cards are mounted by script.js, so equal counts mean
#      the script did not run to the end
#   L4 a link-card label from the site's own data is on screen text (uiautomator dump)
set -u
APK="$1"; OUT="$2"; URL="${3:-https://linktree.diegonmarcos.com/}"; mkdir -p "$OUT"
BPKG=com.diegonmarcos.cloudbrowser
fails=0
ok()  { echo "PASS  $1"; }
bad() { echo "::error title=linktree emulator test::$1"; echo "FAIL  $1"; fails=$((fails + 1)); }

# The runner can hand over an emulator adb still reports offline (seen as "adb: device offline" then a hung wait): reconnect until it answers.
for _ in $(seq 1 60); do
  [ "$(timeout 10 adb get-state 2>/dev/null | tr -d '\r')" = device ] && [ "$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
  timeout 20 adb reconnect offline >/dev/null 2>&1 || true; sleep 5
done
[ "$(timeout 10 adb get-state 2>/dev/null | tr -d '\r')" = device ] || { bad "L0 the emulator never came online"; exit 2; }
adb logcat -c || true
adb install -r -g "$APK" >/dev/null || { bad "install of Cloud Browser failed"; exit 2; }
adb shell settings put global window_animation_scale 0 || true
# 2 = the page log for ANY page (1 = loopback only); a global nothing in the app writes, off in every shipped build.
adb shell settings put global cloud_browser_page_log 2 || true

# The static anchors the HTML itself holds (what a page without JS shows).
STATIC=$(curl -fsS "$URL" | grep -o '<a [^>]*href=' | wc -l)
echo "static anchors in $URL: $STATIC"

timeout 120 adb shell am start -W -a android.intent.action.VIEW -d "$URL" -p "$BPKG" >/dev/null || bad "L0 the emulator did not take the start command (offline or hung)"
sleep 40   # the loader's own deadline is 6s, plus network on a software-rendered emulator
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml 2>/dev/null > "$OUT/linktree.xml" || true
adb exec-out screencap -p > "$OUT/linktree.png" 2>/dev/null || true
adb logcat -d -v threadtime > "$OUT/logcat.txt"
adb logcat -d -s LocalPageConsole:D LocalPageMetrics:D > "$OUT/console.txt" 2>/dev/null || true
echo "--- WebView console and metrics:"; cat "$OUT/console.txt"

M=$(grep "LocalPageMetrics" "$OUT/console.txt" | grep -F "${URL%/}" | tail -1)
if [ -n "$M" ]; then ok "L1 the page finished loading: $M"; else bad "L1 no layout metrics from $URL (never finished loading?)"; fi
if grep "LocalPageConsole" "$OUT/console.txt" | grep -qE "Uncaught|ReferenceError|SyntaxError|TypeError|NotFoundError"; then
  bad "L2 the WebView logged an uncaught error (console.txt)"
else ok "L2 no uncaught error in the WebView console"; fi
LINKS=$(echo "$M" | grep -oE 'links[^0-9]*[0-9]+' | grep -oE '[0-9]+' | tail -1)
if [ -n "$LINKS" ] && [ "$LINKS" -gt "$STATIC" ]; then ok "L3 $LINKS links on the page, $STATIC in the static HTML: the cards were mounted"
else bad "L3 links on the page: ${LINKS:-?}, static HTML: $STATIC - script.js did not mount the cards"; fi
# A card label from the site's own declared data (first project/profile link label), never typed here.
LABEL=$(curl -fsS "${URL%/}/data-projects.json.js" | grep -oE '"label": *"[^"]{4,}"' | head -1 | sed -E 's/.*: *"(.*)"/\1/')
if [ -n "$LABEL" ] && grep -qF "$LABEL" "$OUT/linktree.xml"; then ok "L4 the card label \"$LABEL\" is on screen"
else echo "NOTE  L4 label \"${LABEL:-?}\" not in the first screen (cards can be below the fold); L3 is the gate"; fi
[ "$fails" -eq 0 ] || { echo "$fails check(s) failed"; exit 1; }
echo "all linktree emulator checks passed"
