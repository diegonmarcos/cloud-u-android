#!/usr/bin/env bash
# Refresh (or check) the privileged-browser allowlist Cloud Vault ships.
#
#   app/src/main/assets/fido2_privileged_google.json
#
# Source: Google's published Credential Manager privileged-apps list
#   https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json
# It names the browsers (Chrome, Brave, Firefox, Edge, Samsung Internet, DuckDuckGo, ...) whose
# package + signing-certificate pair may report a web origin. Vault hands it to
# CallingAppInfo.getOrigin(allowlist); without a matching entry the origin is null and the
# passkey request fails. Browsers Google does not list go in fido2_privileged_community.json
# (same format, not refreshed by this script) or the user's own "Trust" list at runtime.
#
# Usage:
#   scripts/refresh-fido2-privileged-google.sh          # download, validate, replace the asset
#   scripts/refresh-fido2-privileged-google.sh --check  # exit 1 if the shipped copy has drifted
set -euo pipefail

SOURCE_URL="https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ASSET="$HERE/../app/src/main/assets/fido2_privileged_google.json"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

curl -fsS --max-time 60 -o "$TMP" "$SOURCE_URL"

# Validate before it can replace anything: it must parse, and must still cover the browsers
# the app is tested against (an empty or truncated download would silently break passkeys).
python3 - "$TMP" <<'PY'
import json, sys
apps = json.load(open(sys.argv[1]))["apps"]
pkgs = {a["info"]["package_name"] for a in apps if a["type"] == "android"}
assert all(a["info"]["signatures"] for a in apps if a["type"] == "android"), "entry without signatures"
for required in ("com.android.chrome", "com.brave.browser", "org.mozilla.firefox", "com.microsoft.emmx"):
    assert required in pkgs, f"{required} missing from downloaded list"
print(f"ok: {len(pkgs)} android packages")
PY

if [[ "${1:-}" == "--check" ]]; then
  cmp -s "$TMP" "$ASSET" || { echo "drift: $ASSET differs from $SOURCE_URL" >&2; exit 1; }
  echo "up to date"
else
  cp "$TMP" "$ASSET"
  echo "updated $ASSET"
fi
