#!/usr/bin/env bash
# Offline-first guard: a failed token refresh (401/403/invalid_grant, edge gate, no network) must never
# wipe the cached encrypted vault, keys or KDF parameters. Local unlock is the gate.
set -euo pipefail
cd "$(dirname "$0")/.."
f=app/src/main/kotlin/com/x8bit/bitwarden/data/auth/repository/AuthRepositoryImpl.kt
fn=$(awk '/override fun refreshAccessTokenSynchronously/{p=1} p{print} /override fun logout\(reason/{if(p)exit}' "$f")
[ -n "$fn" ] || { echo "FAIL: refreshAccessTokenSynchronously not found"; exit 1; }
if grep -q -E 'softLogout|userLogoutManager\.logout' <<<"$fn"; then
  echo "FAIL: refresh path logs out / wipes the vault"; exit 1
fi
grep -q -E 'coVerify\(exactly = 0\) \{ userLogoutManager.softLogout' app/src/test/kotlin/com/x8bit/bitwarden/data/auth/repository/AuthRepositoryTest.kt \
  || { echo "FAIL: unit tests no longer assert the vault is kept"; exit 1; }
echo "PASS: refresh failure keeps the cached vault"
