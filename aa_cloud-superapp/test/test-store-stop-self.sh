#!/usr/bin/env bash
# #859 Stop works on the SuperApp's own row.
#
# a0fe00263 put Stop on the Store row together with a self-guard that refused
# it with "Stopping SuperApp would close this store" — a UX remark, not a
# protection. It is removed from every Stop in libs:appstore (Store list, Phone
# Apps, Apps Mesh). The host's own Stop goes to SelfStop.stop, which finishes
# and removes this app's tasks and kills the process so the next launch is a
# normal cold start. The one real hazard, an install in flight, gets a brief
# notice and Stop still proceeds.
#
# Proven red by mutation: the old toast-and-return guard re-added to any Stop
# handler, a disabled-reason for self restored in PhoneAppActions, SelfStop
# turned into a refusal (return before the kill), or the kill / task removal
# dropped.
#
# Usage: ./test-store-stop-self.sh   (static, no network)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
LIB="${STORE_LIB:-$ROOT/ab_cloud-libs-shared/libs/appstore/src/main}"
SRC="$LIB/java/com/diegonmarcos/superapp/appstore"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$SRC/SelfStop.kt" "$SRC/StoreCloudFragment.kt" "$SRC/PhoneAppActions.kt" "$SRC/StorePhoneFragment.kt" "$SRC/AppsMesh.kt"; do
  [ -f "$f" ] || { echo "  FAIL: missing $f"; echo "== RESULT: 0 passed, 1 failed =="; exit 1; }
done
strip() {
  python3 - "$1" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
s = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), s, flags=re.S)
sys.stdout.write(re.sub(r'//[^\n]*', '', s))
PY
}

# ── 1. no Stop handler refuses the host ────────────────────────────────────
HITS=""
for f in "$SRC"/*.kt; do
  code="$(strip "$f")"
  if printf '%s' "$code" | grep -Eq 'store_phone_why_self|packageName\) *return +(Toast|toast)'; then HITS="$HITS $(basename "$f")"; fi
done
[ -z "$HITS" ] && ok "no Stop handler refuses the SuperApp's own row" || bad "a self-stop refusal is back in:$HITS"

# ── 2. every Stop path routes the host to SelfStop.stop ────────────────────
for f in StoreCloudFragment.kt StorePhoneFragment.kt AppsMesh.kt; do
  if strip "$SRC/$f" | grep -q 'SelfStop.isSelf(.*SelfStop.stop('; then ok "$f: Stop on the host runs SelfStop.stop"
  else bad "$f: Stop on the host does not run SelfStop.stop"; fi
done
if strip "$SRC/PhoneAppActions.kt" | grep -q 'SelfStop.isSelf(ctx, pkg) -> null'; then
  ok "Phone Apps draws Stop live on the host's row"
else bad "Phone Apps disables Stop on the host's row"; fi

# ── 3. SelfStop.stop really stops, never refuses ───────────────────────────
STOP="$(strip "$SRC/SelfStop.kt" | sed -n '/fun stop(/,/^    }/p')"
if printf '%s' "$STOP" | grep -q 'finishAndRemoveTask()' &&
   printf '%s' "$STOP" | grep -q 'Process.killProcess(Process.myPid())' &&
   ! printf '%s' "$STOP" | grep -Eq '\breturn\b'; then
  ok "SelfStop.stop finishes this app's tasks and kills the process, with no early return"
else bad "SelfStop.stop can refuse, or does not end the tasks and the process"; fi
if printf '%s' "$STOP" | grep -q 'installRunning()' && printf '%s' "$STOP" | grep -q 'Toast'; then
  ok "an install in flight gets a brief notice, not a refusal"
else bad "no notice for an install in flight"; fi

echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
