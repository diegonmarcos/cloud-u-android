#!/usr/bin/env bash
# #861 The SuperApp can ALWAYS update itself, even with the Store page or its
# auto-update chain broken. Its self-update (UpdateWorker → UpdateChecker
# check → download → UpdateInstaller install prompt) lives in libs:updater and
# must not import, call or wait on the Store's catalogue refresh, the
# updates-first gate (StorePriority / StoreAuto / libs:appstore) or any custom
# DNS (FleetDns, StoreDns, a ProxySelector, an OkHttp Dns, DnsResolver): it
# opens plain HttpURLConnections. #874: those go through the process's one DNS
# bridge (libs:sysdns FleetDnsBridge, whose Mirror default ends on Android's
# system resolver) via StoreDns's selector — which must fall through to DIRECT
# (Android's resolver) whenever the bridge answers nothing or is not wired, and
# must never import FleetDns itself, so a self-update never waits on the DNS page.
#
# Proven red by mutation: an `import ...appstore.StorePriority` or a
# FleetDns call planted in UpdateChecker, or StoreDns.select returning a
# proxy for a host the bridge could not resolve, each fail a check below.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
U="${UPDATER_SRC:-$ROOT/ab_cloud-libs-shared/libs/updater/src/main/java/com/diegonmarcos/superapp/updater}"
SD="${STORE_DNS:-$ROOT/ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreDns.kt}"
LD="${DNS_LADDER:-$ROOT/ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/DnsLadder.kt}"
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

if [ ! -f "$SD" ] || [ ! -f "$LD" ]; then bad "StoreDns / DnsLadder not found at $SD, $LD"; else
  SEL="$(strip "$LD" | sed -n '/override fun select(/,/^                }/p')"
  if printf '%s' "$SEL" | grep -q 'accepts(host) && resolveFor(host)' &&
     printf '%s' "$SEL" | grep -q 'return previous?.select(uri) ?: listOf(Proxy.NO_PROXY)'; then
    ok "the selector proxies only a host the ladder resolved; otherwise DIRECT (Android's resolver), bridge wired or not"
  else bad "the selector no longer falls through to DIRECT when no rung answers (self-update would depend on it)"; fi
  if { strip "$SD"; strip "$LD"; } | grep -q 'FleetDns\b'; then bad "StoreDns reads FleetDns itself"; else ok "StoreDns never reads FleetDns: the host hands it a resolver"; fi
fi
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
