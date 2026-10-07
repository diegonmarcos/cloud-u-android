#!/bin/sh
# <dns-bridge-owner> — #889 the shells' DNS port (libs/sysdns data/sysdns.json::
# bridge_port, where proot -p lands every lookup of a rootfs shell) is OWNED BY A
# TERMINAL, never by another fleet process. On 2026-10-07 the Store's
# FleetDnsBridge had bound it first, so each terminal's lookups hung 15 s per
# name whenever the Store was cached or frozen. Asserts:
#   - libs/sysdns FleetDnsBridge binds an ephemeral port only (never bridge_port);
#   - ac_cloud-termux CloudDnsBridge and ac_cloud-nix-on-droid TermuxApplication
#     bind bridge_port, and when it is taken retry on a daemon thread until it is
#     theirs (the same 3 s in both, the same thread name);
#   - the data file documents the rule.
# grep-based. Any miss exits 1.
set -u
DIR="$(cd "$(dirname "$0")/.." && pwd)"                    # ac_cloud-termux
ANDROID="$(cd "$DIR/.." && pwd)"
TERMUX="$DIR/app/src/main/java/com/termux/cloud/CloudDnsBridge.java"
NIX="$ANDROID/ac_cloud-nix-on-droid/app/src/main/java/com/termux/app/TermuxApplication.java"
FLEET="$ANDROID/ab_cloud-libs-shared/libs/sysdns/src/main/java/com/diegonmarcos/cloudlib/sysdns/FleetDnsBridge.kt"
DATA="$ANDROID/ab_cloud-libs-shared/libs/sysdns/data/sysdns.json"
fails=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1"; fails=$((fails+1)); }

echo "== libs/sysdns: a non-terminal process never holds the shells' port =="
grep -q 'SystemDnsBridge(0, upstream, log)' "$FLEET" && ! grep -q 'SystemDnsBridge(port, upstream, log)' "$FLEET" \
  && ok "FleetDnsBridge binds an ephemeral port only" || bad "FleetDnsBridge still binds bridge_port"
grep -q "is the terminals' shell port (#889)" "$FLEET" && ok "its state says whose the port is" || bad "no #889 note in the bind state"

for f in "$TERMUX" "$NIX"; do
  n="$(basename "$f")"
  echo "== $n: binds the shells' port and retries until it owns it =="
  grep -q 'new SystemDnsBridge(BuildConfig.CLOUD_DNS_BRIDGE_PORT, SystemDnsBridge.android()' "$f" \
    && ok "$n binds bridge_port (baked as CLOUD_DNS_BRIDGE_PORT)" || bad "$n does not bind bridge_port"
  grep -q 'RETRY_MS = 3000' "$f" && grep -q '"sysdns-rebind"' "$f" && grep -q 'setDaemon(true)' "$f" \
    && ok "$n retries every 3 s on the daemon thread sysdns-rebind" || bad "$n has no rebind retry"
  grep -q 'retrying every' "$f" && grep -q '(#889)' "$f" \
    && ok "$n's /api/sysdns/state says it is retrying" || bad "$n's state does not say it retries"
done

echo "== data: the rule is written where the port is declared =="
python3 - "$DATA" <<'PY' && ok "sysdns.json::_doc_bridge_port names the owner rule (#889)" || bad "sysdns.json does not document #889"
import json, sys
d = json.load(open(sys.argv[1]))
sys.exit(0 if ("#889" in d["_doc_bridge_port"] and "ONLY A TERMINAL BINDS IT" in d["_doc_bridge_port"] and d["bridge_port"] == 2053) else 1)
PY

if [ "$fails" -eq 0 ]; then echo "test-dns-bridge-owner: all green"; exit 0; fi
echo "test-dns-bridge-owner: $fails failure(s)"; exit 1
