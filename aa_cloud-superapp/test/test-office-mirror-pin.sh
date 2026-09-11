#!/usr/bin/env bash
# Tester for Cloud Office's identity and pins (ac_cloud-office).
#
# ── WHAT CHANGED UNDER THIS FILE ──────────────────────────────────────────
# This tester was written for a MIRROR. ac_cloud-sheets used to republish
# Collabora's official APK, verified against a sha256 pinned in its build.json,
# and every assertion here was built on that pin: upstream.url, upstream.apk_name,
# upstream.sha256, upstream.size, upstream.version_code, upstream.package_name,
# upstream.fdroid_index.
#
# Commit 05b0c6c15 ended the mirror. ac_cloud-sheets is now a SOURCE BUILD from
# two pinned Collabora Gerrit revisions (upstream.online, which we patch, and
# upstream.core, which we do not), and de0042377 made ship-cloud-sheets.yml
# refuse rather than try to download an APK that is no longer described. Every
# field the old assertions read is gone, so all of them read `null` and the file
# failed 9 of 16 — which is what blocked the SuperApp's publish, because this
# tester lives in aa_cloud-superapp/test/ and the SuperApp's ship workflow runs
# the whole directory.
#
# The tester was not wrong about the world; the world moved. Each assertion was
# therefore sorted into one of three piles, and the piles are recorded here
# because "which of these did you delete and why" is the only question worth
# asking of a tester that just lost half its subject:
#
#   STILL MEANFUL, UNCHANGED — T4's install-path checks and T5's naming
#   agreement never depended on the mirror at all. They are here verbatim.
#
#   MEANINGFUL, READ FROM A NEW LOCATION — the pin did not disappear, it
#   changed shape. "The pin is a full-length digest, not an abbreviation that
#   drifts" now means the two 40-character Gerrit revisions (T1) instead of a
#   64-character APK sha256. "The launcher and the taxonomy agree on one
#   package" (T6) now reads that package from the superapp's own hub_package,
#   because upstream.package_name no longer exists to read it from.
#
#   GENUINELY OBSOLETE — the F-Droid index lookup (old T2), the "our pin is
#   upstream's newest release" check (old T3) and the published-bytes-match-the-
#   pin check (old T4) all defended ONE property: that a mirror must not lag the
#   OTHER publisher of the same package. There is no other publisher now. We
#   build com.diegonmarcos.cloudoffice, we are its only source, and a phone
#   cannot be AHEAD of us via Collabora's F-Droid repo because that repo ships a
#   different package id. The downgrade loop those assertions existed to catch
#   is not currently possible, so they are deleted rather than rewired.
#
#   Old T7's "hub_package must still be UPSTREAM's" is deleted for the sharper
#   version of the same reason: its stated invariant is now FALSE. It read "we
#   mirror upstream's bytes, so renaming the package would point the store at an
#   app that does not exist." We no longer mirror upstream's bytes, and
#   build.json now says the package CANNOT be upstream's — a rebuilt app signed
#   with the fleet key cannot claim com.collabora.libreoffice, and Android would
#   refuse the install on signature mismatch. An assertion whose premise has
#   inverted is not relocatable; T2 asserts the new invariant instead.
#
# ── WHAT THIS FILE DELIBERATELY DOES NOT ASSERT ───────────────────────────
# It does not compare the superapp's launcher entry against the sheets package.
# They disagree right now, on purpose and visibly: aa_cloud-superapp/build.json
# still points cloud-sheets at com.collabora.libreoffice and Cloud-Sheets.apk,
# because that is the artifact actually on the owner's phone, while
# ac_cloud-sheets/build.json declares com.diegonmarcos.cloudoffice and
# Cloud-Office.apk, which NOTHING HAS BUILT YET — build.host is null and
# ship-cloud-sheets.yml refuses to run. Asserting agreement would demand a
# migration nobody has performed, and performing it would point the phone at a
# release asset that 404s. That migration is the owner's call and is reported
# rather than made here.
#
# Usage: ./test-office-mirror-pin.sh     (no network — every check is static)
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
UNIX="$(cd "$APP/.." && pwd)"                    # → repo root
# One constant; every other name below is derived from it or from build.json.
OFFICE_DIR="ac_cloud-office"
OFFICE_ID="${OFFICE_DIR#ac_cloud-}"
BJ="$UNIX/$OFFICE_DIR/build.json"
UPD="$UNIX/ab_cloud-libs-shared/libs/updater/src/main/java/com/diegonmarcos/superapp/updater"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -qF "$2" "$1" 2>/dev/null && ok "$3" || bad "$3"; }

# A pinned revision is a FULL 40-character lowercase hex sha and nothing else.
# Short shas and branch names are the two things that make a pin drift silently,
# and both are what this rejects.
is_full_sha() {
  [ "${#1}" -eq 40 ] || return 1
  case "$1" in *[!0-9a-f]*) return 1 ;; esac
  return 0
}

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 2; }
[ -f "$BJ" ] || { echo "ERROR: $BJ missing" >&2; exit 2; }

ONLINE_REV=$(jq -r '.upstream.online.revision      // ""' "$BJ")
ONLINE_REF=$(jq -r '.upstream.online.ref           // ""' "$BJ")
ENGINE_SHA=$(jq -r '.upstream.engine.source.sha256 // ""' "$BJ")
ENGINE_SIZE=$(jq -r '.upstream.engine.source.size  // 0'  "$BJ")
APP_ID=$(jq     -r '.android.application_id   // ""' "$BJ")
ASSET=$(jq      -r '.release.gh_release.asset_name // ""' "$BJ")
OURS=$(jq       -r '.name                     // ""' "$BJ")

echo "== T1: both pins exist and neither can drift =="
# THIS TEST WAS ASSERTING A KEY THAT NO LONGER EXISTS, and said nothing about it.
# It was written when the app pinned TWO git revisions, upstream.online and
# upstream.core, because it expected to COMPILE the LibreOffice engine. On
# 2026-09-11 upstream.core was replaced by upstream.engine — a sha256-pinned
# PREBUILT binary — because patches/0001 touches no C++ at all. `jq` answers
# "" for a key that is gone, so the two core assertions below read an empty
# string and failed on every run; the only reason that did not stop a release is
# that this tester is FOREIGN source to aa_cloud-superapp and the test engine
# declines to fail the SuperApp's publish over it. A tester that fails for a
# reason that is not a defect trains everyone to ignore its output.
#
# The property is unchanged: NEITHER PIN MAY DRIFT. What changed is that the two
# pins now hash different things — a commit sha over a tree's history, and a
# sha256 over Collabora's published bytes — so each is checked in its own shape.
is_full_sha "$ONLINE_REV" \
  && ok "upstream.online.revision is a full 40-char commit sha" \
  || bad "upstream.online.revision is not a full 40-char commit sha ('$ONLINE_REV') — a short sha or a branch name lets the patched tree move under a fixed pin"
# The engine half keeps the ORIGINAL mirror-era guarantee: exact bytes, by
# digest. 64 lowercase hex and a positive byte count, or it is not a pin.
case "$ENGINE_SHA" in
  [0-9a-f]*) [ "${#ENGINE_SHA}" -eq 64 ] && ok "upstream.engine.source.sha256 is a full 64-char digest" \
               || bad "upstream.engine.source.sha256 is ${#ENGINE_SHA} chars, not 64 ('$ENGINE_SHA') — a truncated digest is not a pin" ;;
  *)         bad "upstream.engine.source.sha256 is missing or not lowercase hex ('$ENGINE_SHA') — the engine is taken prebuilt, so this digest IS its supply chain" ;;
esac
[ "$ENGINE_SIZE" -gt 0 ] 2>/dev/null \
  && ok "upstream.engine.source.size is recorded ($ENGINE_SIZE bytes)" \
  || bad "upstream.engine.source.size is '$ENGINE_SIZE' — size is checked alongside the digest because a same-size collision has already fooled this fleet once"
# The ref is not the fetch identity, but it names the release LINE the sha came
# from, and losing it makes a pin bump unauditable.
[ -n "$ONLINE_REF" ] \
  && ok "the online pin names its upstream ref ($ONLINE_REF)" \
  || bad "upstream.online.ref is empty — nothing records which release line the sha came from"
# The whole ABI dimension for this app: every additional ABI is another full NDK
# megabuild of LibreOffice core, and the owner's phone (SM-G996B) is arm64.
[ "$(jq -r '.android.abi_filters | join(",")' "$BJ")" = "arm64-v8a" ] \
  && ok "android.abi_filters is arm64-v8a only" \
  || bad "android.abi_filters is not arm64-v8a only"

echo "== T2: the package id is OURS, and it cannot be upstream's =="
# INVERTED FROM THE MIRROR ERA, and that inversion is the point. While we
# republished Collabora's signed bytes the package HAD to stay theirs. Now we
# compile and sign with the fleet key, so it MUST be ours: Android refuses a
# signature-mismatched update, and Text Enhance binds to the Cloud Keyboard over
# ITextTools behind CONSTELLATION_DATA at protectionLevel=signature, which only
# resolves inside the fleet's own namespace.
case "$APP_ID" in
  com.diegonmarcos.*) ok "android.application_id is in the fleet namespace ($APP_ID)" ;;
  "")                 bad "android.application_id is unset — the app has no identity to sign or install under" ;;
  *)                  bad "android.application_id is '$APP_ID', outside com.diegonmarcos.* — signed with the fleet key it cannot claim that id, Android refuses the update on signature mismatch, and the signature-gated Text Enhance bind fails" ;;
esac

echo "== T3: the release identity is internally consistent =="
# What the old T4/T5 reached for through constellation-fleet.json, asserted on
# the SOURCE instead. That file is DERIVED — aa_cloud-superapp/app/build.gradle
# regenerates it from these very fields on every build — so asserting on the
# committed snapshot tested whether somebody remembered to run regen.sh, not
# whether the release is coherent. These are the fields regen.sh reads to build
# the fleet entry's asset, release_url and per-ABI assets map, and AbiUpdateTag
# matches Build.SUPPORTED_ABIS against that map's keys.
[ "$(jq -r '.release.gh_release.enabled' "$BJ")" = "true" ] \
  && ok "release.gh_release.enabled is true — the app has a publish path" \
  || bad "release.gh_release.enabled is not true — regen.sh would build a fleet entry with no release_url"
[ -n "$ASSET" ] \
  && ok "release.gh_release.asset_name is set ($ASSET)" \
  || bad "release.gh_release.asset_name is unset — the fleet entry's asset and release_url both collapse"
# One variant, and its gh_asset must be the same name the release publishes,
# or the store offers a filename the release does not carry.
V_ASSETS=$(jq -r '[.release.variants[]?.gh_asset] | unique | join(",")' "$BJ")
[ "$V_ASSETS" = "$ASSET" ] \
  && ok "every release variant publishes the asset_name ($V_ASSETS)" \
  || bad "release.variants gh_asset set is '$V_ASSETS' but gh_release.asset_name is '$ASSET' — the fleet's per-ABI map and its flat asset would name different files"
jq -e '[.release.variants[]? | select((.supported_abis // .abis // []) | index("arm64-v8a"))] | length == 1' "$BJ" >/dev/null 2>&1 \
  && ok "exactly one variant serves arm64-v8a — AbiUpdateTag resolves it unambiguously" \
  || bad "arm64-v8a is served by none or several variants — AbiUpdateTag takes the first ABI match, so the phone's asset becomes order-dependent"

echo "== T4: no install path may report a failure as a success =="
# UNCHANGED FROM THE MIRROR ERA — these never depended on it. Both halves of the
# loop the owner reported. A `pm install` that answers anything other than
# Success must decline, and a candidate older than what is installed must be
# refused BEFORE it is committed, not discovered from PackageInstaller's async
# error.
has "$UPD/install/InstallChannel.kt" 'if (out.startsWith("Success")) null' \
  "ShellInstall decides on the pm output, not on having run pm"
# A STALE ASSERTION, NOT A DEFECT, and it had been failing silently. The literal
# comparison this used to grep for was refactored into VersionOrder.isDowngrade;
# the property is intact, the string is gone. Assert the CALL and the comparison
# it stands on, so the check survives the next refactor of either one alone.
has "$UPD/Fleet.kt" 'VersionOrder.isDowngrade(identity.versionCode, installedCode)' \
  "commit() asks VersionOrder whether the candidate is older than what is installed"
has "$UPD/VersionOrder.kt" 'compare(candidateCode, installedCode) == Order.OLDER' \
  "VersionOrder.isDowngrade really compares the two codes"
has "$UPD/Fleet.kt" 'apk.file.delete()' \
  "a refused downgrade drops the cached artifact instead of re-offering it"

echo "== T5: every name the fleet OWNS agrees =="
# UNCHANGED. The rebrand to "Cloud Office" is data spread over two build.json
# files that no engine reconciles: ac_cloud-sheets/build.json::name feeds the
# Constellation AppStore row (via regen.sh -> constellation-fleet.json::label),
# while the superapp holds the launcher tile and the updater's notification
# label. Rename one and the store, the home screen and the install notification
# disagree about what the user just tapped. Derived from build.json rather than
# spelled out, so the NEXT rename needs no edit here.
SUP="$APP/build.json"
TILE=$(jq -r --arg t "extapp:cloud-$OFFICE_ID" '[.. | objects | select(.target? == $t) | .label] | first // ""' "$SUP")
ROSTER=$(jq -r --arg i "cloud-$OFFICE_ID" '.ui.external_apps[] | select(.id == $i) | .label' "$SUP")
[ -n "$OURS" ] && [ "$OURS" != "null" ] \
  && ok "$OFFICE_DIR/build.json::name is set ($OURS)" \
  || bad "$OFFICE_DIR/build.json::name is missing — the AppStore row would fall back to the id"
[ "$TILE" = "$OURS" ] && ok "launcher tile label matches ($TILE)" \
  || bad "launcher tile says '$TILE' but the fleet name is '$OURS' — home screen and AppStore disagree"
[ "$ROSTER" = "$OURS" ] && ok "ui.external_apps[cloud-$OFFICE_ID].label matches ($ROSTER)" \
  || bad "external_apps label says '$ROSTER' but the fleet name is '$OURS' — the install notification names a different app"

echo "== T6: the launcher grid and the phone taxonomy agree on ONE package =="
# The property the old T8 defended, reading the package from where it now lives.
# It used to compare ui.phone_folders against ac_cloud-sheets' upstream.package_name;
# that field is gone with the mirror, and the superapp's own hub_package is the
# launcher's authority for this app regardless of which package it names. The
# failure this catches is unchanged and is on the record in build.json's own
# _doc_match_keywords: the launcher grid files this app from
# ui.external_apps[].folder, while PhoneTaxonomy — which Notify filters by —
# reads ui.phone_folders alone. It cannot arrive by metadata, because
# PhoneAppClassifier refuses CATEGORY_UNDEFINED and upstream declares no
# android:appCategory. Move hub_package without moving the keyword and the app
# lands in the `others` sink for Notify while the grid still shows it under its
# folder — the two surfaces disagreeing about the same app.
HUB=$(jq -r --arg i "cloud-$OFFICE_ID" '.ui.external_apps[] | select(.id == $i) | .hub_package' "$SUP")
FOLDER=$(jq -r --arg i "cloud-$OFFICE_ID" '.ui.external_apps[] | select(.id == $i) | .folder' "$SUP")
[ -n "$HUB" ] && [ "$HUB" != "null" ] \
  && ok "ui.external_apps[cloud-$OFFICE_ID].hub_package is set ($HUB)" \
  || bad "ui.external_apps[cloud-$OFFICE_ID].hub_package is unset — the launcher cannot tell whether the app is installed"
jq -e --arg f "$FOLDER" --arg p "pkg:$HUB" \
  '.ui.phone_folders[] | select(.id == $f) | .match_keywords | index($p)' "$SUP" >/dev/null 2>&1 \
  && ok "phone_folders[$FOLDER] names pkg:$HUB" \
  || bad "phone_folders[$FOLDER] does not name pkg:$HUB — the grid files it under $FOLDER while Notify drops it in the sink"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
