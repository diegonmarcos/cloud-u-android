#!/bin/bash
# #1358 Emulator test: the git engine's MISSING All files access is said up front, once, and its button opens the right page.
#   1. Cloud Drive + the git sync engine installed; Drive holds its own All files access, the ENGINE does not;
#   2. the first-run prompt says "Grant All files access to <engine>" (next to Drive's own grant); "Later" closes it;
#   3. the Git page shows the ONE banner with the same sentence and an "Open settings" button;
#   4. the button lands on Settings (the engine's All files access page);
#   5. `appops set <engine> MANAGE_EXTERNAL_STORAGE allow`, back to Drive: the banner is gone.
# Screenshots of every step land in <outdir>.
# Usage: emulator-engine-grant.sh <drive.apk> <engine.apk> <outdir>
set -u
DRIVE=${1:?drive apk}; ENGINE_APK=${2:?engine apk}; OUT=${3:-out}
PKG=com.diegonmarcos.clouddrive
ENG=com.diegonmarcos.cloudlib.gitsyncengine
SENTENCE='Grant All files access to'
mkdir -p "$OUT"
shot() { adb exec-out screencap -p > "$OUT/$1" 2>/dev/null; }
fail() { echo "::error title=Engine grant banner::$*"; shot fail.png; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; exit 1; }

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; cat "$OUT/ui.xml"; }
# tap the centre of the first node whose text or content-desc matches the regex ($1)
tap() { dump | python3 -I -c '
import re,sys
x=sys.stdin.read(); pat=re.compile(sys.argv[1],re.I)
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0); t=re.search(r"\btext=\"([^\"]*)\"",n); d=re.search(r"content-desc=\"([^\"]*)\"",n); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
    if b and ((t and pat.search(t.group(1))) or (d and pat.search(d.group(1)))):
        a=list(map(int,b.groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break
else: sys.exit(1)' "$1" | { read -r px py && adb shell input tap "$px" "$py"; }; }
# wait up to $2 seconds for text $1 on screen
seen() { for _ in $(seq 1 "$2"); do dump | grep -qF "$1" && return 0; sleep 1; done; return 1; }

adb install -r -g "$ENGINE_APK" || fail "engine install failed"
adb install -r -g "$DRIVE" || fail "drive install failed"
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || fail "cannot grant Drive"
adb shell appops set "$ENG" MANAGE_EXTERNAL_STORAGE default || true
{ echo "drive:  $(adb shell appops get "$PKG" MANAGE_EXTERNAL_STORAGE | tr -d '\r')"; echo "engine: $(adb shell appops get "$ENG" MANAGE_EXTERNAL_STORAGE | tr -d '\r')"; } > "$OUT/appops-before.txt"
cat "$OUT/appops-before.txt"

act=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)
timeout 120 adb shell am start -W -n "$act" >/dev/null; sleep 8
# The emulator has no cld.termux: Drive's startup gate offers "Continue with local files".
for _ in 1 2 3; do tap '^Continue with local files$' && break; sleep 2; done || true
sleep 3

# ── 2. the first-run prompt ──
seen "$SENTENCE" 40 || fail "no first-run prompt saying '$SENTENCE <engine>'"
grep -o "text=\"$SENTENCE[^\"]*\"" "$OUT/ui.xml" | head -1 | tee "$OUT/prompt.txt"
shot 1-first-run-prompt.png
tap '^Later$' || fail "the prompt has no Later button"
sleep 2

# ── 3. the Git page banner (Drive opens on Git; else Sync > Git) ──
if ! seen "$SENTENCE" 10; then
  for _ in 1 2 3; do tap '^Sync$' && break; sleep 2; done
  sleep 2; tap '^Git$' || true; sleep 3
fi
seen "$SENTENCE" 30 || fail "the Git page shows no '$SENTENCE' banner"
grep -o "text=\"$SENTENCE[^\"]*\"" "$OUT/ui.xml" | head -1 | tee "$OUT/banner.txt"
[ "$(grep -o "text=\"$SENTENCE" "$OUT/ui.xml" | wc -l)" -eq 1 ] || fail "the banner is not shown exactly once"
grep -q 'text="Open settings"' "$OUT/ui.xml" || fail "the banner has no Open settings button"
shot 2-git-banner.png

# ── 4. the button opens Settings ──
tap '^Open settings$' || fail "cannot tap Open settings"
sleep 4
adb shell dumpsys window | tr -d '\r' | grep -m1 mCurrentFocus | tee "$OUT/after-button.txt"
grep -qi 'settings' "$OUT/after-button.txt" || fail "Open settings did not land on Settings"
shot 3-settings-page.png

# ── 5. grant: the banner goes ──
adb shell appops set "$ENG" MANAGE_EXTERNAL_STORAGE allow || fail "cannot grant the engine"
adb shell appops get "$ENG" MANAGE_EXTERNAL_STORAGE | tr -d '\r' > "$OUT/appops-after.txt"
timeout 60 adb shell am start -W -n "$act" >/dev/null; sleep 8
dump >/dev/null
if grep -qF "$SENTENCE" "$OUT/ui.xml"; then fail "the banner is still shown after the grant"; fi
shot 4-granted-no-banner.png
echo "OK engine grant banner: prompt, one banner, button to Settings, gone after the grant"
