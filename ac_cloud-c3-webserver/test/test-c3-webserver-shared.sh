#!/usr/bin/env bash
# cloud-c3-webserver — :8002's UI is ONE copy: the Linux my-webserver frontend.
#
# The shared server (ab_cloud-libs-shared/libs/fleet-webserver) bundles vendor/, a byte-identical,
# sha256-listed mirror of cloud-u-linux/da_my-webserver/src. This tester proves the mirror cannot
# drift and that the guard itself can fail:
#   V1  vendor/ matches vendor/VENDOR.sha256 (sync.sh --check).
#   V2  vendor/ is byte-identical to the Linux repo's files ($LINUX_REPO, sync.sh --check --from).
#   V3  M: one byte changed in vendor/ goes red (hash list).
#   V4  M: one byte changed in the LINUX copy goes red (drift from the source of truth).
#   V5  M: a file added to / removed from the sync list without a re-sync goes red.
#   V6  the app's server crate carries no copy: no vendored UI file name under ac_cloud-c3-webserver.
# NO SKIP: LINUX_REPO unset or not a checkout is an ERROR. Reads ab_cloud-libs-shared, so it is
# kept out of test-c3-webserver.sh (which stays own-source).
set -uo pipefail
APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIB="$(cd "$APP/../ab_cloud-libs-shared/libs/fleet-webserver" && pwd)" || { echo "ERROR the shared server is missing"; exit 1; }
FAILURES=0
pass() { echo "  PASS  $*"; }; fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
LINUX="${LINUX_REPO:-}"
[ -f "$LINUX/da_my-webserver/src/lib/browse.html" ] || { echo "ERROR LINUX_REPO must be a checkout of diegonmarcos/cloud-u-linux (got '${LINUX}') — an unchecked mirror is not a pass"; exit 1; }

echo "── cloud-c3-webserver: one UI, mirrored byte for byte ──"
"$LIB/sync.sh" --check >/dev/null 2>&1 && pass "V1 vendor/ matches VENDOR.sha256" || fail "V1 vendor/ does not match VENDOR.sha256"
"$LIB/sync.sh" --check --from "$LINUX" >/dev/null 2>&1 && pass "V2 vendor/ is byte-identical to cloud-u-linux da_my-webserver/src" || fail "V2 vendor/ drifted from the Linux repo"

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
cp -R "$LIB" "$W/lib"; rm -rf "$W/lib/target"
printf 'x' >> "$W/lib/vendor/browse.html"
"$W/lib/sync.sh" --check >/dev/null 2>&1 && fail "V3 M: an edited vendor/browse.html stayed GREEN" || pass "V3 M: an edited vendor/browse.html goes red"

mkdir -p "$W/linux/da_my-webserver"; cp -R "$LINUX/da_my-webserver/src" "$W/linux/da_my-webserver/src"
printf 'x' >> "$W/linux/da_my-webserver/src/lib/browse.html"
"$LIB/sync.sh" --check --from "$W/linux" >/dev/null 2>&1 && fail "V4 M: a changed Linux copy stayed GREEN" || pass "V4 M: a changed Linux copy goes red (drift)"

cp -R "$LIB" "$W/lib2"; rm -rf "$W/lib2/target"; sed -i '/marked.min.js/d' "$W/lib2/vendor/VENDOR.sha256"
"$W/lib2/sync.sh" --check >/dev/null 2>&1 && fail "V5 M: a file dropped from VENDOR.sha256 stayed GREEN" || pass "V5 M: a file dropped from VENDOR.sha256 goes red"

# V5 is only meaningful if the sync list and the hash list name the same files
n_sync="$(grep -cE '^  "[a-z.-]+ [a-z./-]+"$' "$LIB/sync.sh")"; n_sum="$(wc -l < "$LIB/vendor/VENDOR.sha256")"
[ "$n_sync" = "$n_sum" ] && pass "V5b sync.sh and VENDOR.sha256 name the same $n_sum files" || fail "V5b sync.sh lists $n_sync files, VENDOR.sha256 $n_sum"

stray="$(find "$APP" \( -name browse.html -o -name 'marked.min.js' -o -name 'js-yaml.min.js' -o -name 'github-markdown-dark.css' -o -name 'eruda.min.js' -o -name 'my-webserver.cjs' \) -not -path '*/target/*' -not -path '*/build/*' 2>/dev/null)"
[ -z "$stray" ] && pass "V6 this app carries no copy of the UI" || fail "V6 a copy of the UI lives in this app: $stray"

echo
if [ "$FAILURES" -eq 0 ]; then echo "── test-c3-webserver-shared: all green ──"; else echo "── test-c3-webserver-shared: $FAILURES FAILED ──"; exit 1; fi
