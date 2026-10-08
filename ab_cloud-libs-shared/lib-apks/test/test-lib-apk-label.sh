#!/usr/bin/env bash
# Every shared lib APK is built from ONE shell manifest. Its label used to be the
# literal "Cloud Library" — so under Settings › VPN the owner read "Cloud Library ·
# Connected" for the mesh tunnel (the net-wg engine) and could not tell which of the
# 24 libs that was. The label is now the lib's fleet label, cloud-lib-<module>, the
# name the Store and the fleet manifest already show.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok   $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL $1"; }
M="$ROOT/app/src/main/AndroidManifest.xml"; G="$ROOT/app/build.gradle"
grep -q 'android:label="${libLabel}"' "$M" && ok "the shell manifest's label is the libLabel placeholder" || bad "the shell manifest still carries a literal label"
grep -q '"Cloud Library"' "$M" && bad "the generic 'Cloud Library' label is still in the shell manifest" || ok "no generic label left in the shell manifest"
grep -q 'manifestPlaceholders = \[libLabel: "cloud-lib-${name}"\]' "$G" && ok "every flavour sets libLabel = cloud-lib-<module>" || bad "build.gradle does not set libLabel per flavour"
F="$ROOT/../../aa_cloud-superapp/data/constellation-fleet.json"
bad_labels="$(jq -r '[.. | objects | select(has("package")) | select(.package|test("cloudlib")) | select(.id|startswith("lib-")) | select(.label != ("cloud-" + .id)) | "\(.id)=\(.label)"] | join(" ")' "$F" 2>/dev/null)"
[ -z "$bad_labels" ] && ok "the fleet labels every lib-<module> as cloud-lib-<module>, the same name the APK now carries" || bad "fleet labels that are not cloud-lib-<module>: $bad_labels"
echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
