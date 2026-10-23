#!/usr/bin/env bash
# Emulator checks for the offline-first vault, networking OFF.
#
# Plain mode (no OLD_APK): the installed app is already signed in (seeded); it opens straight to the biometric prompt and
# unlocks with no network, and the vault header says "Offline - last synced".
#
# Upgrade mode (OLD_APK and NEW_APK set, CI): enrol a fingerprint, install the OLD apk, sign in to the seeded Vaultwarden
# (http://vaultwarden.app, resolved to the host by test/emulator/dns-stub.py); the app wraps the key with the fingerprint by itself after that first unlock, then
# `adb install -r` the NEW apk, turn the network OFF and open it with the fingerprint: the cached vault item must show.
#
# SKIPs (exit 0) without an emulator, unless REQUIRE_EMULATOR=1 (CI sets it), where a skip is a failure.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
ui="python3 $here/emulator/uidrv.py"
pkg=com.diegonmarcos.cloudvault
export OUT_DIR=${OUT_DIR:-/tmp}; mkdir -p "$OUT_DIR"
item=${VAULT_ITEM:-CI Seed Login}
if ! { command -v adb >/dev/null && adb -e get-state >/dev/null 2>&1; }; then
  [ "${REQUIRE_EMULATOR:-0}" = 1 ] && { echo "FAIL: no emulator, REQUIRE_EMULATOR=1"; exit 1; }
  echo "SKIP: no emulator"; exit 0
fi
shot() { # screencap to a file on the device, then pull (exec-out piping came back empty on the CI emulator); retry once
  for _ in 1 2 3; do
    adb -e wait-for-device
    adb -e shell screencap -p /sdcard/shot.png && adb -e pull /sdcard/shot.png "$OUT_DIR/$1.png" >/dev/null && [ -s "$OUT_DIR/$1.png" ] && return 0
    echo "shot diag: $(adb devices | tr '\n' ' ')" >&2
    sleep 2
  done
  echo "WARN: screenshot $1 failed" >&2
}
prefs_keys() { # key NAMES only (never values) of the app's shared prefs, to see what state the app is in; needs a rootable emulator image
  adb -e root >/dev/null 2>&1; sleep 2
  for f in $(adb -e shell "ls /data/data/$pkg/shared_prefs/ 2>/dev/null"); do
    echo "== $f"; adb -e shell "cat /data/data/$pkg/shared_prefs/$f" | grep -o -E 'name="[^"]*"' | sed -E 's/(userKey|Key|key|token|Token)_?[A-Za-z0-9-]{8,}/\1<id>/' | head -80
  done
}
die() { shot "fail-$1"; prefs_keys > "$OUT_DIR/fail-$1.prefs-keys.txt" 2>&1; $ui dump "$OUT_DIR/fail-$1.xml"; adb -e logcat -d -t 400 > "$OUT_DIR/fail-$1.logcat" 2>&1; echo "FAIL: $2"; exit 1; }
launch() { adb -e shell am force-stop $pkg; adb -e shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }
touch_finger() { adb -e emu finger touch 1; }
offline() { adb -e shell svc wifi disable; adb -e shell svc data disable; adb -e shell cmd connectivity airplane-mode enable; sleep 3; }

enrol_fingerprint() {
  adb -e shell locksettings set-pin 1234 >/dev/null 2>&1
  adb -e shell am start -a android.settings.BIOMETRIC_ENROLL >/dev/null 2>&1
  sleep 4
  $ui has 'PIN|password|pattern' && { adb -e shell input text 1234; adb -e shell input keyevent 66; sleep 3; }
  for _ in 1 2 3 4 5 6; do
    $ui tap '^I agree$|^Agree$' 3 2>/dev/null || $ui tap '^Next$|^Get started$|^Yes$|^Start$|^Done$' 3 2>/dev/null || $ui tap '^More$' 2 2>/dev/null || true
    sleep 1
  done
  for _ in $(seq 1 14); do touch_finger; sleep 1.2; done
  $ui tap 'Done|Next|Not now|Skip' 6 2>/dev/null || true
  shot enrolled
  adb -e shell input keyevent 3
}

trace() { echo "[$1] $($ui texts)" >> "$OUT_DIR/steps.txt"; }

signin_selfhosted() {
  $ui wait 'Continue|Log in|Email' 240 || die login-screen "app did not reach the login screen"
  trace s1
  $ui has 'Create account' && $ui tap '^Log in$|Log in' 15   # onboarding carousel -> email screen
  # point the app at the seeded server: environment menu -> Self-hosted -> server URL -> Save
  $ui tap 'Logging in on|Region|Server|\.com|diegonmarcos' 20 || die env-menu "no environment selector"
  trace s2
  $ui tap 'Self-hosted' 15 || die env-selfhosted "no Self-hosted entry"
  trace s3
  $ui type "${VAULT_URL:-http://vaultwarden.app}"
  trace s4
  $ui tap '^Save$|Save' 15 || die env-save "no Save"
  trace s5
  sleep 1
  $ui type "$VAULT_EMAIL"
  trace s6
  $ui tap 'Continue' 15 || die email-continue "no Continue"
  trace s7
  $ui wait 'Master password' 30 || die master-pw "no master password screen"
  trace s8
  $ui type "$VAULT_PW"
  trace s9
  $ui tap 'Log in with master password|Log in|Unlock' 15 || die master-login "no log in button"
  trace s10
}

if [ -n "${OLD_APK:-}" ]; then
  : "${NEW_APK:?NEW_APK required}" "${VAULT_EMAIL:?}" "${VAULT_PW:?}"
  adb -e shell settings put global window_animation_scale 0
  enrol_fingerprint
  adb -e install -r -d -g "$OLD_APK" >/dev/null || die install-old "install of the OLD apk failed"
  launch
  signin_selfhosted
  # first (master password) unlock offers the biometric wrap: approve it with the fingerprint
  for t in 2 4 8; do sleep $t; trace after-login-$t; done; shot old-after-login
  touch_finger; sleep 3; trace after-finger-1; touch_finger; sleep 3; trace after-finger-2
  $ui wait "$item" 90 || die old-vault "seeded vault item not shown after sign-in on the OLD apk"
  shot old-vault
  # no Settings toggle: the first master-password unlock enrols the fingerprint itself (one prompt, touched above)
  $ui tap '^Close$' 4 2>/dev/null || true
  # diagnostic: does the OLD app offer the fingerprint on a cold start before the update?
  launch; sleep 8; trace old-cold-start
  if $ui has 'Touch the fingerprint sensor|Biometric verification'; then touch_finger; sleep 4; trace old-cold-unlocked; fi
  adb -e shell am force-stop $pkg
  # update in place: same package + signature, user data kept
  adb -e install -r -d "$NEW_APK" >/dev/null || die install-new "update install (install -r) failed"
  echo "updated: $(adb -e shell dumpsys package $pkg | grep -m1 versionName)"
fi

offline
launch
sleep 6
shot offline-open
trace offline-launch
# the biometric prompt can take a while on the CI emulator: touch the sensor only once it is on screen
if $ui wait 'Touch the fingerprint sensor|Biometric verification' 40 2>/dev/null; then
  trace offline-prompt
  for _ in 1 2 3; do touch_finger; sleep 2; $ui has 'Touch the fingerprint sensor|Biometric verification' || break; done
else
  trace offline-no-prompt
fi
sleep 3
trace offline-after-touch
shot offline-unlocked
uidump=$(adb -e exec-out uiautomator dump /dev/tty 2>/dev/null)
if grep -q -i -E "Log in|server" <<<"$uidump" && ! grep -q -i "Search vault\|My vault" <<<"$uidump"; then
  die server-login "server login shown offline"
fi
if [ -n "${OLD_APK:-}" ]; then
  $ui wait "$item" 30 || die cached-vault "cached vault item missing after the update, offline"
  $ui wait 'Offline — last synced' 30 || die offline-label "no 'Offline — last synced' line in the vault header"
  shot offline-vault
fi
echo "PASS: opened offline with the fingerprint (screenshots in $OUT_DIR)"
