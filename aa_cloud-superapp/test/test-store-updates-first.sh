#!/usr/bin/env bash
# #857 Wi-Fi + Auto-update ON => every pending update is queued and downloaded
# FIRST, at the highest priority, before the Store page's catalogue refresh,
# icons or details.
#
# The rule is DECLARED in libs:appstore/assets/appstore-priority.json and
# evaluated by StorePriority; the Store page (StoreCloudFragment.renderFleet)
# must reach its catalogue refresh (checkAll) only through
# StorePriority.runUpdatesFirst, which starts the StoreAuto chain and holds the
# refresh until the chain's download phase is over.
#
# Proven red by mutation: priority "normal", network "metered", `first` dropped,
# or a bare checkAll(ctx, list) back in renderFleet each fail a check below.
#
# Usage: ./test-store-updates-first.sh   (static, no network)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
LIB="${STORE_LIB:-$ROOT/ab_cloud-libs-shared/libs/appstore/src/main}"
SRC="$LIB/java/com/diegonmarcos/superapp/appstore"
DECL="$LIB/assets/appstore-priority.json"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

for f in "$DECL" "$SRC/StorePriority.kt" "$SRC/StoreCloudFragment.kt" "$SRC/StoreAuto.kt"; do
  [ -f "$f" ] || { echo "  FAIL: missing $f"; echo "== RESULT: 0 passed, 1 failed =="; exit 1; }
done

# Strip comments so prose quoting the rule cannot satisfy it.
strip() {
  python3 - "$1" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
s = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), s, flags=re.S)
sys.stdout.write(re.sub(r'//[^\n]*', '', s))
PY
}

# ── 1. the declaration: Wi-Fi + auto-update => updates first, highest ──────
if python3 - "$DECL" <<'PY'
import json, sys
u = json.load(open(sys.argv[1]))["updates_first"]
w = u.get("when", {})
assert u.get("enabled") is True, "enabled"
assert u.get("priority") == "highest", "priority must be highest"
assert w.get("auto_update") is True, "when.auto_update must be true"
assert w.get("network") == "unmetered", "when.network must be unmetered"
assert u.get("first") == "download_all_updates", "first must be download_all_updates"
after = u.get("after", [])
for k in ("catalogue_refresh", "icons", "details"):
    assert k in after, k + " must wait in `after`"
assert u["first"] not in after
PY
then ok "appstore-priority.json: Wi-Fi + Auto-update queues all updates first at the highest priority"
else bad "appstore-priority.json does not declare updates first on Wi-Fi + Auto-update"; fi

# ── 2. the evaluator is the declaration's, and gates on both conditions ────
PRI="$(strip "$SRC/StorePriority.kt")"
FN="$(printf '%s' "$PRI" | sed -n '/fun updatesFirst(/,/^$/p')"
if printf '%s' "$PRI" | grep -q 'appstore-priority.json' &&
   printf '%s' "$FN" | grep -q 'autoOn' && printf '%s' "$FN" | grep -q 'unmetered' &&
   printf '%s' "$FN" | grep -q 'HIGHEST'; then
  ok "StorePriority.updatesFirst reads the asset and requires auto-update, unmetered and highest"
else bad "StorePriority.updatesFirst does not gate on auto-update + unmetered + highest"; fi

RUN="$(printf '%s' "$PRI" | sed -n '/fun runUpdatesFirst(/,/^    }/p')"
CHAIN_L="$(printf '%s' "$RUN" | grep -n 'StoreAuto.run(' | head -1 | cut -d: -f1)"
THEN_L="$(printf '%s' "$RUN" | grep -n '^ *then()$' | tail -1 | cut -d: -f1)"
if [ -n "$CHAIN_L" ] && [ -n "$THEN_L" ] && [ "$CHAIN_L" -lt "$THEN_L" ] &&
   printf '%s' "$RUN" | grep -q 'MAX_PRIORITY' && printf '%s' "$RUN" | grep -q 'downloading('; then
  ok "runUpdatesFirst starts the auto chain at max priority and holds the page's work until downloads finish"
else bad "runUpdatesFirst does not run the update chain before the page's work"; fi

# ── 3. the Store page's catalogue refresh goes through the gate ─────────────
FRAG="$(strip "$SRC/StoreCloudFragment.kt")"
RF="$(printf '%s' "$FRAG" | sed -n '/private fun renderFleet(/,/^    }/p')"
if printf '%s' "$RF" | grep -q 'StorePriority.runUpdatesFirst(' &&
   ! printf '%s' "$RF" | grep -E '^ *checkAll\(ctx, list\) *$' >/dev/null; then
  ok "renderFleet's catalogue refresh runs only after StorePriority's updates-first gate"
else bad "renderFleet refreshes the catalogue without waiting for the updates"; fi

echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
