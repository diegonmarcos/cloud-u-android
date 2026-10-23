#!/usr/bin/env bash
# Emulator checks for the offline-first vault, networking OFF.
#
# Plain mode (no OLD_APK): the installed app is already signed in (seeded); it opens straight to the biometric prompt and
# unlocks with no network, and the vault header says "Offline - last synced".
#
# Upgrade mode (OLD_APK and NEW_APK set, CI): enrol a fingerprint, install the OLD apk, sign in to the seeded Vaultwarden
# (http://vaultwarden.app, resolved to the host by test/emulator/dns-stub.py), wrap the key with the fingerprint, then
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
die() { shot "fail-$1"; $ui dump "$OUT_DIR/fail-$1.xml"; adb -e logcat -d -t 400 > "$OUT_DIR/fail-$1.logcat" 2>&1; echo "FAIL: $2"; exit 1; }
launch() { adb -e shell am force-stop $pkg; adb -e shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }
touch_finger() { adb -e emu finger touch 1; }
offline() { adb -e shell svc wifi disable; adb -e shell svc data disable; adb -e shell cmd connectivity airplane-mode enable; sleep 3; }

enrol_fingerprint() {
  adb -e shell locksettings set-pin 1234 >/dev/null 2>&1
  adb -e shell am start -a android.settings.BIOMETRIC_ENROLL >/dev/null 2>&1
  sleep 4
  $ui has 'PIN|password|pattern' && { adb -e shell input text 1234; adb -e shell input keyevent 66; sleep 3; }
  for _ in 1 2 3 4 5 6; do
    $ui tap 'I agree|Agree|Next|Get started|More|Yes|Start' 4 2>/dev/null || true
    sleep 1
  done
  for _ in $(seq 1 14); do touch_finger; sleep 1.2; done
  $ui tap 'Done|Next|Not now|Skip' 6 2>/dev/null || true
  shot enrolled
  adb -e shell input keyevent 3
}

signin_selfhosted() {
  $ui wait 'Continue|Log in|Email' 240 || die login-screen "app did not reach the login screen"
  $ui has 'Create account' && $ui tap '^Log in$|Log in' 15   # onboarding carousel -> email screen
  # point the app at the seeded server: environment menu -> Self-hosted -> server URL -> Save
  $ui tap 'Logging in on|Region|Server|\.com|diegonmarcos' 20 || die env-menu "no environment selector"
  $ui tap 'Self-hosted' 15 || die env-selfhosted "no Self-hosted entry"
  $ui type "http://vaultwarden.app"
  $ui tap '^Save$|Save' 15 || die env-save "no Save"
  sleep 1
  $ui type "$VAULT_EMAIL"
  $ui tap 'Continue' 15 || die email-continue "no Continue"
  $ui wait 'Master password' 30 || die master-pw "no master password screen"
  $ui type "$VAULT_PW"
  $ui tap 'Log in with master password|Log in|Unlock' 15 || die master-login "no log in button"
}

if [ -n "${OLD_APK:-}" ]; then
  : "${NEW_APK:?NEW_APK required}" "${VAULT_EMAIL:?}" "${VAULT_PW:?}"
  adb -e shell settings put global window_animation_scale 0
  enrol_fingerprint
  adb -e install -r -d -g "$OLD_APK" >/dev/null || die install-old "install of the OLD apk failed"
  launch
  signin_selfhosted
  # first (master password) unlock offers the biometric wrap: approve it with the fingerprint
  sleep 8; shot old-after-login
  touch_finger; sleep 3; touch_finger; sleep 3
  $ui wait "$item" 90 || die old-vault "seeded vault item not shown after sign-in on the OLD apk"
  shot old-vault
  adb -e shell am force-stop $pkg
  # update in place: same package + signature, user data kept
  adb -e install -r -d "$NEW_APK" >/dev/null || die install-new "update install (install -r) failed"
  echo "updated: $(adb -e shell dumpsys package $pkg | grep -m1 versionName)"
fi

offline
launch
sleep 6
shot offline-open
touch_finger
sleep 4
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
