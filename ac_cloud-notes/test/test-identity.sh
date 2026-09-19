#!/usr/bin/env bash
# ac_cloud-notes tester: application identity is OUR package id, and the
# app is local-first (no Firebase, no Apollo module). Runs without a build.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAIL=0

ok()   { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAIL=1; }

# 1. applicationId is DECLARED ONCE in build.json and DERIVED everywhere else.
# app/build.gradle reads it via JsonSlurper (#495) and capacitor.config.ts reads
# it via readFileSync (#522); neither may restate it as a literal, because a
# second copy is what lets the two disagree silently.
bj_id="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['android']['application_id'])" "$ROOT/build.json")"
cap_src="$ROOT/packages/frontend/apps/android/capacitor.config.ts"
if [ "$bj_id" = "com.diegonmarcos.cloudnotes" ]; then
  ok "build.json::android.application_id is com.diegonmarcos.cloudnotes"
else
  fail "build.json::android.application_id is '$bj_id', expected com.diegonmarcos.cloudnotes"
fi
if grep -q "appId: buildJson.android.application_id" "$cap_src"; then
  ok "capacitor.config.ts derives appId from build.json"
else
  fail "capacitor.config.ts does not derive appId from build.json (it restates it)"
fi
if grep -qE "appId: *'" "$cap_src"; then
  fail "capacitor.config.ts still carries a literal appId — that is a second declaration"
else
  ok "no literal appId in capacitor.config.ts"
fi

# 2. upstream applicationId must not survive as a SHIPPED identity. The
# namespace may stay app.affine.pro (it does not ship); only the applicationId
# that the APK is signed/installed under must be ours.
if grep -q 'applicationId = "app.affine.pro"' "$ROOT/packages/frontend/apps/android/App/app/build.gradle"; then
  fail "upstream applicationId still in app/build.gradle"
else
  ok "no app.affine.pro applicationId in app/build.gradle"
fi

# 3. de-clouding: no Firebase plugin, no :service Apollo module
if grep -q "google-services\|firebase" "$ROOT/packages/frontend/apps/android/App/app/build.gradle"; then
  fail "Firebase/Google-services still referenced in app/build.gradle"
else
  ok "no Firebase/Google-services in app/build.gradle"
fi
if [ -d "$ROOT/packages/frontend/apps/android/App/service" ]; then
  fail ":service Apollo module still present"
else
  ok ":service Apollo module gone"
fi

# 4. the English-locale filter the fleet requires is declared for this APK
if grep -q 'resConfigs "en"' "$ROOT/packages/frontend/apps/android/App/app/build.gradle"; then
  ok "resConfigs \"en\" declared (fleet rule #299)"
else
  fail "resConfigs \"en\" missing from app/build.gradle"
fi

if [ "$FAIL" -eq 0 ]; then
  printf '  cloud-notes identity tester: ALL PASS\n'
else
  printf '  cloud-notes identity tester: FAIL\n' >&2
  exit 1
fi