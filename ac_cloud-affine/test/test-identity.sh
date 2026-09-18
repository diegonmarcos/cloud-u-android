#!/usr/bin/env bash
# ac_cloud-affine tester: application identity is OUR package id, and the
# app is local-first (no Firebase, no Apollo module). Runs without a build.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAIL=0

ok()   { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAIL=1; }

# 1. capacitor config and gradle agree on OUR applicationId
cap="$(grep -o "appId: '[^']*'" "$ROOT/packages/frontend/apps/android/capacitor.config.ts" | head -1 | sed "s/appId: '//;s/'//")"
gradle="$(grep -o 'applicationId = "[^"]*"' "$ROOT/packages/frontend/apps/android/App/app/build.gradle" | head -1 | sed 's/applicationId = "//;s/"//')"
if [ "$cap" = "com.diegonmarcos.affine" ] && [ "$gradle" = "com.diegonmarcos.affine" ]; then
  ok "applicationId com.diegonmarcos.affine in capacitor.config.ts AND app/build.gradle"
else
  fail "applicationId mismatch: capacitor=$cap gradle=$gradle"
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
  printf '  cloud-affine identity tester: ALL PASS\n'
else
  printf '  cloud-affine identity tester: FAIL\n' >&2
  exit 1
fi