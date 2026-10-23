#!/usr/bin/env bash
# Runs inside the emulator job: seeded Vaultwarden + DNS stub on the host, then the upgrade/offline test.
# Usage: run-ci.sh OLD_APK NEW_APK OUT_DIR   (DNS stub address comes from $DNS_IP, the same one the emulator got)
set -uo pipefail
old=$(realpath "$1"); new=$(realpath "$2"); mkdir -p "$3"; export OUT_DIR=$(realpath "$3")
cd "$(dirname "$0")/../.."
export VAULT_EMAIL="ci-seed-$(date +%s)@example.com" VAULT_PW="CiSeed$(date +%s)xYz"
docker run -d --name vw --network host -e I_REALLY_WANT_VOLATILE_STORAGE=true -e LOG_LEVEL=debug -e EXTENDED_LOGGING=true -e SIGNUPS_ALLOWED=true -e DOMAIN=https://vault.diegonmarcos.com vaultwarden/server:latest >/dev/null || exit 1
for _ in $(seq 1 40); do curl -fs http://127.0.0.1/alive >/dev/null && break; sleep 2; done
pip install --quiet cryptography >/dev/null 2>&1
python3 test/emulator/seed-vaultwarden.py http://127.0.0.1 "$VAULT_EMAIL" "$VAULT_PW" "CI Seed Login" || { echo "FAIL: seeding"; docker logs vw | tail -20; exit 1; }
# sanity-check the seed with the real Bitwarden CLI (diagnostic only; prints counts, never secrets)
{ mkdir -p "$HOME/bwcli" && (cd "$HOME/bwcli" && npm init -y >/dev/null 2>&1 && npm install --no-progress --legacy-peer-deps @bitwarden/cli@2025.5.0 semver 2>&1 | tail -3)
  bw() { node "$HOME/bwcli/node_modules/@bitwarden/cli/build/bw.js" "$@"; }
  export BITWARDENCLI_APPDATA_DIR="$OUT_DIR/bwcli" BW_PW="$VAULT_PW"
  bw config server http://127.0.0.1 >/dev/null 2>&1
  S=$(bw login "$VAULT_EMAIL" --passwordenv BW_PW --raw 2>&1) && echo "bw login ok" || echo "bw login failed: $(echo "$S" | head -c 200)"
  [ -n "${S:-}" ] && echo "bw items: $(bw list items --session "$S" 2>&1 | python3 -c 'import sys,json
try: print(len(json.load(sys.stdin)))
except Exception as e: print("unreadable", e)')"
  rm -rf "$OUT_DIR/bwcli"; } > "$OUT_DIR/bw-check.txt" 2>&1
# the vault has ONE URL, https://vault.diegonmarcos.com, so the emulator test serves it over TLS: a throwaway CA signs a cert for the name,
# socat terminates TLS on :443 in front of the seeded Vaultwarden (:80), the CA goes into the emulator's user store (the app trusts user CAs),
# and the DNS stub (the emulator is started with -dns-server $DNS_IP) resolves the name to the host. Release builds permit no cleartext to it.
T="$OUT_DIR/tls"; mkdir -p "$T"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$T/ca.key" -out "$T/ca.pem" -days 2 -subj "/CN=ci-vault-test-ca" >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -keyout "$T/srv.key" -out "$T/srv.csr" -subj "/CN=vault.diegonmarcos.com" >/dev/null 2>&1
printf 'subjectAltName=DNS:vault.diegonmarcos.com\nbasicConstraints=CA:FALSE\nextendedKeyUsage=serverAuth\n' > "$T/ext.cnf"
openssl x509 -req -in "$T/srv.csr" -CA "$T/ca.pem" -CAkey "$T/ca.key" -CAcreateserial -out "$T/srv.pem" -days 2 -extfile "$T/ext.cnf" >/dev/null 2>&1 || { echo "FAIL: test certificate"; exit 1; }
cat "$T/srv.pem" "$T/ca.pem" > "$T/chain.pem"
command -v socat >/dev/null || sudo apt-get install -y -qq socat >/dev/null 2>&1
sudo socat OPENSSL-LISTEN:443,reuseaddr,fork,cert="$T/chain.pem",key="$T/srv.key",verify=0 TCP:127.0.0.1:80 >"$OUT_DIR/socat.log" 2>&1 &
sudo DNS_BIND="$DNS_IP" python3 -u test/emulator/dns-stub.py >"$OUT_DIR/dns-stub.log" 2>&1 &
sleep 1
H=$(openssl x509 -in "$T/ca.pem" -subject_hash_old -noout)
adb -e root >/dev/null 2>&1; adb -e wait-for-device
adb -e shell "mkdir -p /data/misc/user/0/cacerts-added" && adb -e push "$T/ca.pem" "/data/misc/user/0/cacerts-added/$H.0" >/dev/null 2>&1
adb -e shell "chmod 644 /data/misc/user/0/cacerts-added/$H.0; restorecon -R /data/misc/user/0/cacerts-added" >/dev/null 2>&1
rm -f "$T/ca.key" "$T/srv.key"
adb -e shell "ping -c1 -W3 vault.diegonmarcos.com" >"$OUT_DIR/net-check.txt" 2>&1
curl -s --cacert "$T/ca.pem" --resolve vault.diegonmarcos.com:443:127.0.0.1 https://vault.diegonmarcos.com/alive >>"$OUT_DIR/net-check.txt" 2>&1 && echo "host tls ok" >>"$OUT_DIR/net-check.txt"
{ echo "--- host listeners"; ss -ltn | grep -E ':(80|443) '; } >>"$OUT_DIR/net-check.txt" 2>&1
adb -e logcat -c
REQUIRE_EMULATOR=1 OLD_APK="$old" NEW_APK="$new" bash test/test-vault-offline-emulator.sh
rc=$?
docker logs vw 2>&1 | sed -E "s/(access_token|refresh_token|password|Authorization)[^ ,&]*/\\1=REDACTED/gI" | tail -150 > "$OUT_DIR/vaultwarden.log"
adb -e logcat -d > "$OUT_DIR/logcat.txt" 2>&1
exit $rc
