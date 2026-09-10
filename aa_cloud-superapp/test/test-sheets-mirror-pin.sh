#!/usr/bin/env bash
# Tester for the Collabora Office mirror pin (ac_cloud-sheets).
#
# WHY THIS EXISTS. ac_cloud-sheets is the one fleet entry we do not build: it
# republishes an official Collabora APK verified against a sha256 pinned in
# ac_cloud-sheets/build.json. Every other app in the constellation has exactly
# one publisher — us — so "the installed bytes differ from the published bytes"
# can only ever mean the device is behind. For a MIRROR that inference is
# false: the same package is also published by Collabora's own F-Droid repo,
# the device can follow THAT, and then the difference means the device is
# AHEAD.
#
# When it is ahead, the Constellation AppStore compares sha256(installed
# base.apk) against the published Cloud-Sheets.apk.sha256, sees a difference,
# and reports "update available" — while Fleet.commit refuses to install,
# because a lower versionCode over a higher one is a downgrade and Android
# rejects it. The store then re-offers the same bytes on every pass, forever.
# That is the "says outdated, installs with no error, still says outdated"
# loop, and nothing in the repo could see it coming: ship-cloud-sheets.yml
# hard-fails on a sha or size mismatch, but a pin that is merely OLD matches
# its own sha perfectly. The pin sat at versionCode 115 from the day it was
# added while upstream moved to 155.
#
# So the assertion that matters is T3: our pin must be upstream's NEWEST
# arm64-v8a release. A mirror that lags upstream is not a stale convenience,
# it is a store that cannot ever satisfy its own verdict.
#
# Usage: ./test-sheets-mirror-pin.sh     (T2/T3/T4 need network)
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
UNIX="$(cd "$APP/.." && pwd)"                    # → repo root
BJ="$UNIX/ac_cloud-sheets/build.json"
FLEET="$APP/data/constellation-fleet.json"
UPD="$UNIX/ab_cloud-libs-shared/libs/updater/src/main/java/com/diegonmarcos/superapp/updater"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -qF "$2" "$1" 2>/dev/null && ok "$3" || bad "$3"; }

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 2; }
[ -f "$BJ" ] || { echo "ERROR: $BJ missing" >&2; exit 2; }

P_NAME=$(jq -r '.upstream.version_name' "$BJ")
P_CODE=$(jq -r '.upstream.version_code' "$BJ")
P_APK=$(jq  -r '.upstream.apk_name'     "$BJ")
P_URL=$(jq  -r '.upstream.url'          "$BJ")
P_SHA=$(jq  -r '.upstream.sha256'       "$BJ")
P_SIZE=$(jq -r '.upstream.size'         "$BJ")
P_PKG=$(jq  -r '.upstream.package_name' "$BJ")
INDEX=$(jq  -r '.upstream.fdroid_index' "$BJ")

echo "== T1: the pin describes ONE artifact, with no field left behind =="
# Bumping the sha alone is the failure mode the README calls out by name: a sha
# that no longer matches its declared version is a pin that documents nothing.
case "$P_URL" in
  */"$P_APK") ok "upstream.url ends in upstream.apk_name ($P_APK)" ;;
  *)          bad "upstream.url does not end in upstream.apk_name ($P_URL vs $P_APK)" ;;
esac
[ "${#P_SHA}" -eq 64 ] && ok "upstream.sha256 is a 64-char digest" \
  || bad "upstream.sha256 is not a 64-char digest (${#P_SHA} chars)"
[ "$P_SIZE" -gt 0 ] 2>/dev/null && ok "upstream.size is a positive byte count ($P_SIZE)" \
  || bad "upstream.size is not a positive byte count ($P_SIZE)"
[ "$P_CODE" -gt 0 ] 2>/dev/null && ok "upstream.version_code is a positive integer ($P_CODE)" \
  || bad "upstream.version_code is not a positive integer ($P_CODE)"
# The whole ABI dimension for this app: Collabora ships one artifact per ABI and
# we mirror exactly one of them. The owner's phone (SM-G996B) is arm64.
[ "$(jq -r '.android.abi_filters | join(",")' "$BJ")" = "arm64-v8a" ] \
  && ok "android.abi_filters is arm64-v8a only" \
  || bad "android.abi_filters is not arm64-v8a only"

echo "== T2: LIVE — the pin names a real entry in upstream's own index =="
IDX=""
if command -v curl >/dev/null 2>&1; then
  IDX="$(mktemp)"
  if curl -fsSL --max-time 120 -o "$IDX" "$INDEX"; then
    ok "fetched $INDEX"
  else
    bad "could not fetch $INDEX"; rm -f "$IDX"; IDX=""
  fi
else
  echo "  SKIP: curl unavailable — T2/T3 need the live index"
fi

if [ -n "$IDX" ]; then
  ENTRY="$(jq -c --arg p "$P_PKG" --arg a "$P_APK" \
    '.packages[$p][]? | select(.apkName == $a)' "$IDX")"
  if [ -n "$ENTRY" ]; then
    ok "index has an entry for $P_APK"
    [ "$(printf '%s' "$ENTRY" | jq -r '.hash')"        = "$P_SHA"  ] && ok "index hash == upstream.sha256"        || bad "index hash != upstream.sha256"
    [ "$(printf '%s' "$ENTRY" | jq -r '.size')"        = "$P_SIZE" ] && ok "index size == upstream.size"          || bad "index size != upstream.size"
    [ "$(printf '%s' "$ENTRY" | jq -r '.versionCode')" = "$P_CODE" ] && ok "index versionCode == upstream.version_code" || bad "index versionCode != upstream.version_code"
    [ "$(printf '%s' "$ENTRY" | jq -r '.versionName')" = "$P_NAME" ] && ok "index versionName == upstream.version_name" || bad "index versionName != upstream.version_name"
    printf '%s' "$ENTRY" | jq -e '.nativecode == ["arm64-v8a"]' >/dev/null \
      && ok "index entry is the arm64-v8a artifact" || bad "index entry is not the arm64-v8a artifact"
  else
    bad "index has no entry named $P_APK (upstream rotated it?)"
  fi

  echo "== T3: LIVE — the pin IS upstream's newest arm64-v8a release =="
  # THE ASSERTION THIS FILE EXISTS FOR. Lagging upstream is not cosmetic: a
  # phone that followed Collabora's repo is on the newer versionCode, our
  # mirror can only offer the older one, and Fleet.commit refuses it as a
  # downgrade — so the store reports "update available" it can never satisfy.
  NEWEST="$(jq -r --arg p "$P_PKG" \
    '[.packages[$p][]? | select(.nativecode == ["arm64-v8a"]) | .versionCode] | max' "$IDX")"
  NEWEST_NAME="$(jq -r --arg p "$P_PKG" --argjson c "${NEWEST:-0}" \
    'first(.packages[$p][]? | select(.nativecode == ["arm64-v8a"] and .versionCode == $c) | .versionName)' "$IDX")"
  if [ "$P_CODE" = "$NEWEST" ]; then
    ok "pinned versionCode $P_CODE is upstream's newest arm64-v8a ($P_NAME)"
  else
    bad "pinned versionCode $P_CODE is BEHIND upstream's newest arm64-v8a $NEWEST ($NEWEST_NAME) — every device that follows Collabora's repo reads as permanently outdated in the AppStore, and the mirror can only offer it a downgrade"
  fi
  rm -f "$IDX"
fi

echo "== T4: LIVE — what we actually published agrees with the pin =="
if command -v curl >/dev/null 2>&1; then
  ASSET="$(jq -r '.release.gh_release.asset_name' "$BJ")"
  REL="$(jq -r --arg a "$ASSET" '.apps[] | select(.asset == $a) | .release_url' "$FLEET" | head -1)"
  if [ -n "$REL" ]; then
    ok "fleet manifest carries the release URL for $ASSET"
    # The sidecar is the exact value Fleet.releaseStatus compares the installed
    # APK's sha256 against — the right-hand side of the whole "outdated" verdict.
    # RED HERE IS EXPECTED BETWEEN A PIN BUMP AND ITS CI RUN, and that is the
    # point: this pair is the only thing that says whether the fix has actually
    # reached the phones. ship-cloud-sheets.yml republishes on a change to
    # ac_cloud-sheets/**, so these go green when that run finishes. Still red
    # long after it, and the workflow did not run or did not upload.
    SIDECAR="$(curl -fsSL --max-time 60 "$REL.sha256" 2>/dev/null | tr -d '[:space:]')"
    [ "$SIDECAR" = "$P_SHA" ] \
      && ok "published $ASSET.sha256 == upstream.sha256 (the verdict's right-hand side)" \
      || bad "published $ASSET.sha256 is $SIDECAR but the pin says $P_SHA — the release still carries the PREVIOUS pin, so every phone is still comparing itself against the old bytes. Expected until ship-cloud-sheets.yml republishes; a lasting mismatch means it did not"
    LEN="$(curl -fsSLI --max-time 60 "$REL" 2>/dev/null | awk 'BEGIN{IGNORECASE=1} /^content-length:/ {v=$2} END{gsub(/\r/,"",v); print v}')"
    [ "$LEN" = "$P_SIZE" ] \
      && ok "published $ASSET is $LEN bytes == upstream.size" \
      || bad "published $ASSET is $LEN bytes but the pin says $P_SIZE — same cause as above: the release has not been republished against the current pin yet"
  else
    bad "no fleet entry carries a release_url for $ASSET"
  fi
else
  echo "  SKIP: curl unavailable — T4 needs the published release"
fi

echo "== T5: this device's ABI resolves the arm64 asset, not another one =="
# SM-G996B is arm64-v8a. AbiUpdateTag walks Build.SUPPORTED_ABIS and takes the
# first ABI present in the entry's asset map, so arm64-v8a must be in it.
jq -e --arg a "$(jq -r '.release.gh_release.asset_name' "$BJ")" \
  '.apps[] | select(.asset == $a) | .assets["arm64-v8a"] == $a' "$FLEET" >/dev/null 2>&1 \
  && ok "fleet assets map resolves arm64-v8a to the pinned asset" \
  || bad "fleet assets map does not resolve arm64-v8a to the pinned asset"

echo "== T6: no install path may report a failure as a success =="
# Both halves of the loop the owner reported. A `pm install` that answers
# anything other than Success must decline, and a candidate older than what is
# installed must be refused BEFORE it is committed — not discovered from
# PackageInstaller's async error.
has "$UPD/install/InstallChannel.kt" 'if (out.startsWith("Success")) null' \
  "ShellInstall decides on the pm output, not on having run pm"
has "$UPD/Fleet.kt" 'identity.versionCode < installedCode' \
  "commit() refuses a candidate older than the installed versionCode"
has "$UPD/Fleet.kt" 'apk.file.delete()' \
  "a refused downgrade drops the cached artifact instead of re-offering it"

echo "== T7: every name the fleet OWNS agrees, and none of them is upstream's =="
# WHY. The rebrand to "Cloud Office" is data, spread over two build.json files
# that no engine reconciles: ac_cloud-sheets/build.json::name feeds the
# Constellation AppStore row (via regen.sh -> constellation-fleet.json::label),
# while the superapp holds the launcher tile and the updater's notification
# label. Rename one and the store, the home screen and the install notification
# disagree about what the user just tapped. Derived from build.json rather than
# spelled out, so the NEXT rename needs no edit here.
SUP="$APP/build.json"
OURS=$(jq -r '.name' "$BJ")
TILE=$(jq -r '[.. | objects | select(.target? == "extapp:cloud-sheets") | .label] | first // ""' "$SUP")
ROSTER=$(jq -r '.ui.external_apps[] | select(.id == "cloud-sheets") | .label' "$SUP")
[ -n "$OURS" ] && [ "$OURS" != "null" ] \
  && ok "ac_cloud-sheets/build.json::name is set ($OURS)" \
  || bad "ac_cloud-sheets/build.json::name is missing — the AppStore row would fall back to the id"
[ "$TILE" = "$OURS" ] && ok "launcher tile label matches ($TILE)" \
  || bad "launcher tile says '$TILE' but the fleet name is '$OURS' — home screen and AppStore disagree"
[ "$ROSTER" = "$OURS" ] && ok "ui.external_apps[cloud-sheets].label matches ($ROSTER)" \
  || bad "external_apps label says '$ROSTER' but the fleet name is '$OURS' — the install notification names a different app"

# The package id is UPSTREAM's and must survive every rebrand: we mirror
# upstream's bytes, so renaming the package would point the store at an app
# that does not exist. This is the assertion that stops a "full rebrand" from
# reaching too far.
[ "$(jq -r '.ui.external_apps[] | select(.id=="cloud-sheets") | .hub_package' "$SUP")" = "$P_PKG" ] \
  && ok "hub_package is still upstream's $P_PKG" \
  || bad "hub_package no longer matches upstream.package_name ($P_PKG) — a rebrand renamed the package we do not build"

echo "== T8: the central classification knows this package =="
# The launcher grid files Cloud Office from ui.external_apps[cloud-sheets].folder,
# but PhoneTaxonomy - which Notify filters by - reads ui.phone_folders alone. The
# two only agree if the package is named in both. It cannot arrive by metadata:
# PhoneAppClassifier refuses CATEGORY_UNDEFINED and upstream declares no
# android:appCategory, so a missing keyword means the sink, silently.
FOLDER=$(jq -r '.ui.external_apps[] | select(.id == "cloud-sheets") | .folder' "$SUP")
jq -e --arg f "$FOLDER" --arg p "pkg:$P_PKG" \
  '.ui.phone_folders[] | select(.id == $f) | .match_keywords | index($p)' "$SUP" >/dev/null 2>&1 \
  && ok "phone_folders[$FOLDER] names pkg:$P_PKG" \
  || bad "phone_folders[$FOLDER] does not name pkg:$P_PKG — the grid files it under $FOLDER while Notify drops it in the sink"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
