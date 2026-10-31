#!/bin/bash
# #1358 On-device proof of the git sync engine's All files access: install the engine's instrumented test APK (it carries
# the engine's manifest, MANAGE_EXTERNAL_STORAGE included), grant it the way the phone does
# (`adb shell appops set <pkg> MANAGE_EXTERNAL_STORAGE allow`), then run EngineSharedStorageTest: clone a public repo,
# commit, push to a local bare remote and pull, all in /storage/emulated/0 through the engine's own calls.
# Usage: emulator-engine-proof.sh <engine-test.apk> <outdir>
set -u
APK=${1:?test apk}; OUT=${2:-out}
PKG=com.diegonmarcos.cloudlib.gitsync.engine.test
mkdir -p "$OUT"
fail() { echo "::error title=Engine proof::$*"; adb exec-out screencap -p > "$OUT/fail.png" 2>/dev/null; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; exit 1; }
adb install -r -t -g "$APK" || fail "install failed"
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || fail "appops set failed"
adb shell appops get "$PKG" MANAGE_EXTERNAL_STORAGE | tr -d '\r' | tee "$OUT/appops.txt"
grep -q allow "$OUT/appops.txt" || fail "the grant did not take"
adb shell am instrument -w -r "$PKG/androidx.test.runner.AndroidJUnitRunner" | tr -d '\r' | tee "$OUT/instrument.txt"
adb exec-out screencap -p > "$OUT/after.png" 2>/dev/null
grep -q '^OK (2 tests)' "$OUT/instrument.txt" || fail "EngineSharedStorageTest did not pass (see instrument.txt)"
echo "OK engine proof: granted, cloned, committed, pushed to a local bare remote, pulled — on shared storage"
