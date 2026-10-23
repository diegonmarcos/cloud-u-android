#!/usr/bin/env bash
# Runs inside the emulator job: seeded Vaultwarden + DNS stub on the host, then the upgrade/offline test.
# Usage: run-ci.sh OLD_APK NEW_APK OUT_DIR   (DNS stub address comes from $DNS_IP, the same one the emulator got)
set -uo pipefail
old=$(realpath "$1"); new=$(realpath "$2"); mkdir -p "$3"; export OUT_DIR=$(realpath "$3")
cd "$(dirname "$0")/../.."
export VAULT_EMAIL="ci-seed-$(date +%s)@example.com" VAULT_PW="CiSeed$(date +%s)xYz"
docker run -d --name vw -p 80:80 -e I_REALLY_WANT_VOLATILE_STORAGE=true -e SIGNUPS_ALLOWED=true -e DOMAIN=http://10.0.2.2 vaultwarden/server:1.32.7 >/dev/null || exit 1
for _ in $(seq 1 40); do curl -fs http://127.0.0.1/alive >/dev/null && break; sleep 2; done
pip install --quiet cryptography >/dev/null 2>&1
python3 test/emulator/seed-vaultwarden.py http://127.0.0.1 "$VAULT_EMAIL" "$VAULT_PW" "CI Seed Login" || { echo "FAIL: seeding"; docker logs vw | tail -20; exit 1; }
# the emulator reaches the host Vaultwarden at 10.0.2.2 directly (the DNS stub is not needed)
export VAULT_URL=http://10.0.2.2
adb -e shell "ping -c1 -W3 10.0.2.2" >"$OUT_DIR/net-check.txt" 2>&1
adb -e logcat -c
REQUIRE_EMULATOR=1 OLD_APK="$old" NEW_APK="$new" bash test/test-vault-offline-emulator.sh
rc=$?
adb -e logcat -d > "$OUT_DIR/logcat.txt" 2>&1
exit $rc
