#!/usr/bin/env bash
# Emulator check, networking OFF: a vault already signed in (seeded) opens straight to the biometric prompt and
# unlocks with no network. Skips (exit 0) when no emulator is attached; CI runs it where one is.
set -uo pipefail
command -v adb >/dev/null && adb -e get-state >/dev/null 2>&1 || { echo "SKIP: no emulator"; exit 0; }
pkg=com.diegonmarcos.cloudvault
out=${OUT_DIR:-/tmp}/vault-offline.png
adb -e shell svc wifi disable; adb -e shell svc data disable; adb -e shell cmd connectivity airplane-mode enable
adb -e shell am force-stop $pkg
adb -e shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 5
adb -e emu finger touch 1
sleep 3
adb -e exec-out screencap -p > "$out"
ui=$(adb -e exec-out uiautomator dump /dev/tty 2>/dev/null)
if grep -q -i -E 'Log in|server' <<<"$ui" && ! grep -q -i 'Search vault\|My vault' <<<"$ui"; then
  echo "FAIL: server login shown offline (screenshot $out)"; exit 1
fi
echo "PASS: opened offline (screenshot $out)"
