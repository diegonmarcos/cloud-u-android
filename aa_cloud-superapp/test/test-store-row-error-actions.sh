#!/usr/bin/env bash
# A Store row whose download failed (DNS: "cannot resolve github.com …") used
# to show ONLY the folded error text: paint() hides the ⬇/⬆ quick button on a
# failed row, and in Cloud Store the DNS button was never drawn because the app
# has no DNS page of its own. No way to retry, no way to the direct download.
#
# Held here, statically (no build, no device, no network):
#  1. The row's error area offers three buttons: Retry (the quick button's own
#     next stage, through the same next() path — no second install path),
#     APK↗ (ACTION_VIEW on the per-ABI release URL, the plain release URL as
#     the fallback) and the DNS button; the DNS button is shown for a DNS
#     failure when a page exists to open.
#  2. Cloud Store names SuperApp's launcher as its DNS page target
#     (AppStoreHost.dnsPagePackage) with the SAME extras SuperApp's own App.kt
#     puts on its launcher for the DNS page — read from SuperApp, not retyped.
#  3. openDnsPage resolves the target through dnsPageIntent: the package's
#     launch intent when dnsPagePackage is set, launchActivity otherwise, with
#     the extras and NEW_TASK on it.
#
# Usage: ./test-store-row-error-actions.sh   (static, no network)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
LIB="${STORE_LIB:-$ROOT/ab_cloud-libs-shared/libs/appstore/src/main}"
SRC="$LIB/java/com/diegonmarcos/superapp/appstore"
STORE_APP="$ROOT/ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/App.kt"
SUPER_APP="$ROOT/aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/App.kt"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$SRC/StoreCloudFragment.kt" "$SRC/StoreRowError.kt" "$SRC/AppStoreHost.kt" "$STORE_APP" "$SUPER_APP"; do
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

FR="$(strip "$SRC/StoreCloudFragment.kt")"
RE="$(strip "$SRC/StoreRowError.kt")"
HOST="$(strip "$SRC/AppStoreHost.kt")"

# ── 1. the error area: Retry + APK↗ + DNS ──────────────────────────────────
AREA="$(printf '%s' "$FR" | sed -n '/private fun errorArea(/,/^    }/p')"
if printf '%s' "$RE" | grep -q 'const val RETRY_BUTTON = "↻ Retry"' &&
   printf '%s' "$RE" | grep -q 'const val APK_BUTTON = "APK↗"' &&
   printf '%s' "$RE" | grep -q 'const val DNS_BUTTON = '; then
  ok "StoreRowError names the three ways out of a failed row: Retry, APK↗, DNS"
else bad "StoreRowError does not declare RETRY_BUTTON / APK_BUTTON / DNS_BUTTON"; fi
if printf '%s' "$AREA" | grep -q 'btn(ctx, StoreRowError.RETRY_BUTTON, .*) { next(ctx, app) }'; then
  ok "Retry runs next(ctx, app): the quick button's own next stage, no second install path"
else bad "the error area has no Retry button, or it does not go through next()"; fi
if printf '%s' "$AREA" | grep -q 'btn(ctx, StoreRowError.APK_BUTTON' &&
   printf '%s' "$AREA" | grep -q 'Intent(Intent.ACTION_VIEW, Uri.parse(app.abiReleaseUrl.ifEmpty { app.releaseUrl }))'; then
  ok "APK↗ opens the per-ABI release URL in the browser (release URL as the fallback)"
else bad "the error area has no APK↗ button on the direct download"; fi
if printf '%s' "$AREA" | grep -q 'btn(ctx, StoreRowError.DNS_BUTTON, .*) { openDnsPage(ctx) }' &&
   printf '%s' "$AREA" | grep -q 'buttonRow(ctx, retry, apk, dns)'; then
  ok "the DNS button sits in the same row as Retry and APK↗"
else bad "the DNS button is missing from the error area's button row"; fi
SHOW="$(printf '%s' "$FR" | sed -n '/private fun showError(/,/^    }/p')"
if printf '%s' "$SHOW" | grep -q 'getChildAt(2).visibility' &&
   printf '%s' "$SHOW" | grep -q 'look.dnsButton && dnsPageIntent(box.context) != null'; then
  ok "showError shows the DNS button for a DNS failure exactly when a page exists to open"
else bad "showError does not gate the DNS button on look.dnsButton + a resolvable DNS page"; fi
if printf '%s' "$FR" | grep -q 'errBoxes\[app.id\] = errorArea(ctx, app)'; then
  ok "every row's error area is built with its app, so Retry and APK↗ know what to run"
else bad "errorArea is not built with the row's app"; fi

# ── 2. Cloud Store → SuperApp's DNS page, with SuperApp's own extras ───────
if printf '%s' "$HOST" | grep -q 'var dnsPagePackage: String? = null'; then
  ok "AppStoreHost.dnsPagePackage exists and defaults to null (launchActivity takes the extras)"
else bad "AppStoreHost has no dnsPagePackage"; fi
SUPER_EXTRAS="$(grep -o 'dnsPageExtras = mapOf([^)]*)' "$SUPER_APP" | head -1)"
STORE_EXTRAS="$(grep -o 'dnsPageExtras = mapOf([^)]*)' "$STORE_APP" | head -1)"
if [ -n "$SUPER_EXTRAS" ] && [ "$SUPER_EXTRAS" = "$STORE_EXTRAS" ]; then
  ok "Cloud Store puts SuperApp's own DNS-page extras on the intent ($SUPER_EXTRAS)"
else bad "Cloud Store's dnsPageExtras ('$STORE_EXTRAS') differ from SuperApp's ('$SUPER_EXTRAS')"; fi
if grep -q 'dnsPagePackage = "com.diegonmarcos.superapp"' "$STORE_APP"; then
  ok "Cloud Store names com.diegonmarcos.superapp as the app that owns the DNS page"
else bad "Cloud Store does not set dnsPagePackage to SuperApp"; fi

# ── 3. openDnsPage: package launcher first, launchActivity otherwise ───────
DNSI="$(printf '%s' "$FR" | sed -n '/private fun dnsPageIntent(/,/^    }/p')"
if printf '%s' "$DNSI" | grep -q 'if (AppStoreHost.dnsPageExtras.isEmpty()) return null' &&
   printf '%s' "$DNSI" | grep -q 'ctx.packageManager.getLaunchIntentForPackage(pkg)' &&
   printf '%s' "$DNSI" | grep -q 'AppStoreHost.launchActivity?.let { Intent(ctx, it) }' &&
   printf '%s' "$DNSI" | grep -q 'AppStoreHost.dnsPageExtras.forEach { (k, v) -> putExtra(k, v) }' &&
   printf '%s' "$DNSI" | grep -q 'Intent.FLAG_ACTIVITY_NEW_TASK'; then
  ok "dnsPageIntent: no extras → nothing; dnsPagePackage's launcher when set, else launchActivity; extras + NEW_TASK on it"
else bad "dnsPageIntent does not resolve the package fallback with the extras and NEW_TASK"; fi
OPEN="$(printf '%s' "$FR" | sed -n '/private fun openDnsPage(/,/^    }/p')"
if printf '%s' "$OPEN" | grep -q 'dnsPageIntent(ctx) ?: return'; then
  ok "openDnsPage starts exactly the intent dnsPageIntent resolves"
else bad "openDnsPage does not go through dnsPageIntent"; fi

echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
