#!/usr/bin/env bash
# #861 The SuperApp can ALWAYS update itself, even with the Store page or its
# auto-update chain broken. Its self-update (UpdateWorker → UpdateChecker
# check → download → UpdateInstaller install prompt) lives in libs:updater and
# must not import, call or wait on the Store's catalogue refresh, the
# updates-first gate (StorePriority / StoreAuto / libs:appstore) or any custom
# DNS (FleetDns, StoreDns, a ProxySelector, an OkHttp Dns, DnsResolver): it
# resolves with Android's system resolver, plain InetAddress via
# HttpURLConnection. StoreDns (#860) must answer DIRECT from the system
# resolver before it reads any preset.
#
# Proven red by mutation: an `import ...appstore.StorePriority` or a
# FleetDns call planted in UpdateChecker, or StoreDns.select reading the
# preset before the system lookup, each fail a check below.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
U="${UPDATER_SRC:-$ROOT/ab_cloud-libs-shared/libs/updater/src/main/java/com/diegonmarcos/superapp/updater}"
SD="${STORE_DNS:-$ROOT/aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/network/StoreDns.kt}"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
strip() {
  python3 - "$1" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
s = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), s, flags=re.S)
sys.stdout.write(re.sub(r'//[^\n]*', '', s))
PY
}
FORBID='appstore|StorePriority|StoreAuto|startUpdatesAsync|checkAll|FleetDns|StoreDns|ProxySelector|okhttp3\.Dns|DnsResolver|ConstellationWorker'
for f in UpdateWorker.kt Updater.kt source/UpdateChecker.kt source/Download.kt install/UpdateInstaller.kt ApkInstallWorker.kt; do
  [ -f "$U/$f" ] || { bad "missing $U/$f"; continue; }
  hits="$(strip "$U/$f" | grep -nE "$FORBID" || true)"
  if [ -z "$hits" ]; then ok "self-update path $f: no Store gate, no custom DNS"
  else bad "self-update path $f depends on the Store gate or custom DNS: $hits"; fi
done

W="$(strip "$U/UpdateWorker.kt")"
if printf '%s' "$W" | grep -q 'UpdateChecker(applicationContext).available()' &&
   printf '%s' "$W" | grep -q 'UpdateChecker(applicationContext).download('; then
  ok "UpdateWorker checks and downloads the SuperApp through UpdateChecker"
else bad "UpdateWorker no longer self-updates through UpdateChecker"; fi

if [ -f "$SD" ]; then
  SEL="$(strip "$SD" | sed -n '/override fun select(/,/^            }/p')"
  SYS_L="$(printf '%s' "$SEL" | grep -n 'InetAddress.getAllByName(host)' | head -1 | cut -d: -f1)"
  ROUTE_L="$(printf '%s' "$SEL" | grep -n 'route(app, host)' | head -1 | cut -d: -f1)"
  if [ -n "$SYS_L" ] && [ -n "$ROUTE_L" ] && [ "$SYS_L" -lt "$ROUTE_L" ]; then
    ok "StoreDns answers DIRECT from Android's system resolver before reading any preset"
  else bad "StoreDns reads the preset before the system resolver (self-update would depend on FleetDns)"; fi
fi
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
