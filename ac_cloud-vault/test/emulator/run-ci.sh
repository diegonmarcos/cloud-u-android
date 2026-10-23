#!/usr/bin/env bash
# Runs inside the emulator job: seeded Vaultwarden + DNS stub on the host, then the upgrade/offline test.
# Usage: run-ci.sh OLD_APK NEW_APK OUT_DIR   (DNS stub address comes from $DNS_IP, the same one the emulator got)
set -uo pipefail
old=$(realpath "$1"); new=$(realpath "$2"); mkdir -p "$3"; export OUT_DIR=$(realpath "$3")
cd "$(dirname "$0")/../.."
export VAULT_EMAIL="ci-seed-$(date +%s)@example.com" VAULT_PW="CiSeed$(date +%s)xYz"
docker run -d --name vw -p 80:80 -e I_REALLY_WANT_VOLATILE_STORAGE=true -e LOG_LEVEL=debug -e EXTENDED_LOGGING=true -e SIGNUPS_ALLOWED=true -e DOMAIN=http://vaultwarden.app vaultwarden/server:1.32.7 >/dev/null || exit 1
for _ in $(seq 1 40); do curl -fs http://127.0.0.1/alive >/dev/null && break; sleep 2; done
pip install --quiet cryptography >/dev/null 2>&1
python3 test/emulator/seed-vaultwarden.py http://127.0.0.1 "$VAULT_EMAIL" "$VAULT_PW" "CI Seed Login" || { echo "FAIL: seeding"; docker logs vw | tail -20; exit 1; }
# release builds allow cleartext only to vaultwarden.app, so the emulator (started with -dns-server $DNS_IP) resolves it via the stub
sudo DNS_BIND="$DNS_IP" python3 -u test/emulator/dns-stub.py >"$OUT_DIR/dns-stub.log" 2>&1 &
sleep 1
adb -e shell "ping -c1 -W3 vaultwarden.app" >"$OUT_DIR/net-check.txt" 2>&1
adb -e shell "printf 'GET /alive HTTP/1.0\r\nHost: vaultwarden.app\r\n\r\n' | nc -w 4 vaultwarden.app 80" >>"$OUT_DIR/net-check.txt" 2>&1
adb -e logcat -c
REQUIRE_EMULATOR=1 OLD_APK="$old" NEW_APK="$new" bash test/test-vault-offline-emulator.sh
rc=$?
docker logs vw 2>&1 | sed -E "s/(access_token|refresh_token|password|Authorization)[^ ,&]*/\\1=REDACTED/gI" | tail -150 > "$OUT_DIR/vaultwarden.log"
adb -e logcat -d > "$OUT_DIR/logcat.txt" 2>&1
exit $rc
