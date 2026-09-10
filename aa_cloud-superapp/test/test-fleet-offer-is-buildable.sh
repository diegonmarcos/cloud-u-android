#!/usr/bin/env bash
# The Constellation store must never offer an install that cannot happen.
#
# ── THE FAILURE THIS EXISTS FOR ───────────────────────────────────────────
# constellation-fleet.json is GENERATED. aa_cloud-superapp/app/build.gradle runs
# data/regen.sh --constellation-only on every build, and regen.sh derives each
# entry from that app's own build.json: label from .name, package from
# .android.application_id, asset and release_url from
# .release.gh_release.asset_name. So the catalogue cannot disagree with the
# build.json — but neither of them is checked against whether anything was ever
# PUBLISHED, and that is the gap.
#
# Commit 05b0c6c15 turned ac_cloud-sheets from a mirror of the official
# Collabora APK into a source build. Its build.json correctly began declaring
# Cloud-Office.apk, com.diegonmarcos.cloudoffice and the cloud-office GHCR
# image. Every one of those is right, and every one of them is a 404: build.host
# is null, ship-cloud-sheets.yml refuses to run without one, and the only office
# artifact on the rolling `latest` release is still the 267 MB Cloud-Sheets.apk
# from the mirror era. regen.sh hardcoded `blocked: false` for every top-level
# app, so the next SuperApp APK would have shipped a tile promising an install
# of an APK that has never existed. The mirror-era fleet entry was not "stale
# and wrong" — it described the only thing installable. What was wrong was that
# nothing could say "correct, and not publishable yet".
#
# ── WHY THESE ASSERTIONS AND NOT "THE JSON PARSES" ────────────────────────
# A schema check passes on every one of the states above. What has to hold is
# the RELATIONSHIP between three things that live in different files: what the
# build declares, what the catalogue offers, and whether a publish path exists.
#
# Usage: ./test-fleet-offer-is-buildable.sh   (no network — every check is static)
set -u

# Resolve from $0, never from the caller's cwd. cloud-android-test-engine.sh
# runs these from the repository root, agents run them from this directory, and
# a tester whose paths only resolve in one of those matches nothing and passes.
# Every file below is existence-checked as a hard error for the same reason.
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"                    # → aa_cloud-superapp
UNIX="$(cd "$APP/.." && pwd)"                    # → repo root
FLEET="$APP/data/constellation-fleet.json"
OFFICE="$UNIX/ac_cloud-sheets/build.json"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 2; }
for f in "$FLEET" "$OFFICE"; do
  [ -f "$f" ] || { echo "ERROR: $f missing — this tester resolved a path to nothing" >&2; exit 2; }
done

# The fleet id regen.sh derives for ac_cloud-sheets: the directory basename with
# its ac_cloud- prefix stripped.
ENTRY="$(jq -c '.apps[] | select(.id == "sheets")' "$FLEET")"
[ -n "$ENTRY" ] || { echo "ERROR: no fleet entry with id 'sheets' — regen.sh no longer emits it under that id" >&2; exit 2; }

echo "== T1: the catalogue offers exactly what the build declares =="
# regen.sh derives all three, so a mismatch means the committed snapshot has
# drifted from the build.json it was generated from — the APK would then ship a
# catalogue describing an older identity than the one being built.
for pair in "label:.name" "package:.android.application_id" "asset:.release.gh_release.asset_name"; do
  field="${pair%%:*}"; source_path="${pair#*:}"
  offered="$(jq -r --arg f "$field" '.[$f] // ""' <<<"$ENTRY")"
  declared="$(jq -r "$source_path // \"\"" "$OFFICE")"
  if [ -n "$declared" ] && [ "$offered" = "$declared" ]; then
    ok "fleet .$field agrees with ac_cloud-sheets/build.json $source_path ($offered)"
  else
    bad "fleet .$field is '$offered' but ac_cloud-sheets/build.json $source_path is '$declared' — the store would name a different app than the build produces"
  fi
done

echo "== T2: the download URL names the same file as the asset =="
# release_url is built by string concatenation from asset_name. If the two ever
# disagree the store shows one filename and fetches another, which surfaces on
# the phone as a digest mismatch rather than as a wrong URL.
URL="$(jq -r '.release_url // ""' <<<"$ENTRY")"
ASSET="$(jq -r '.asset // ""'     <<<"$ENTRY")"
if [ -n "$ASSET" ] && [ "${URL##*/}" = "$ASSET" ]; then
  ok "release_url ends in the offered asset ($ASSET)"
else
  bad "release_url tail is '${URL##*/}' but the offered asset is '$ASSET' — the store would display one filename and download another"
fi

echo "== T3: an app that cannot be built is not offered for install =="
# build.host present-but-null IS the declaration "this app requires a dedicated
# build host and none has been established". Fleet.kt::status short-circuits to
# State.Blocked and ConstellationFragment hides Install/Update and Direct, so a
# blocked row still opens an installed copy but stops promising a download.
HAS_HOST_KEY="$(jq -r '(.build // {}) | has("host")' "$OFFICE")"
HOST="$(jq -r '.build.host // "null"' "$OFFICE")"
BLOCKED="$(jq -r '.blocked' <<<"$ENTRY")"
if [ "$HAS_HOST_KEY" = "true" ] && [ "$HOST" = "null" ]; then
  [ "$BLOCKED" = "true" ] \
    && ok "build.host is declared and unset, and the fleet entry is blocked" \
    || bad "build.host is declared and unset — nothing can produce $ASSET — yet the fleet entry is blocked=$BLOCKED, so the store offers an install that 404s"
else
  [ "$BLOCKED" = "false" ] \
    && ok "build.host resolves to '$HOST', and the fleet entry is offered" \
    || bad "build.host resolves to '$HOST' so the app is buildable, yet the fleet entry is blocked=$BLOCKED — a publishable app is being hidden from the store"
fi

echo "== T4: the block rule is narrow — it must not take the fleet with it =="
# The predicate lives in regen.sh and applies to EVERY top-level app. Written
# wrongly (`.build.host == null` without the has() guard) it is true for all 35
# build.json, every app in the store goes blocked, and the phone silently stops
# updating anything. This is the assertion that catches that, and it is why the
# rule is keyed on the KEY existing rather than on the value being null.
#
# THIS USED TO READ `[ "$BLOCKED_IDS" = "sheets" ]`, which is a hardcoded list
# wearing an assertion's clothes. It stated "only sheets may be blocked", but
# the invariant that actually matters is "nothing is blocked WITHOUT A REASON IN
# ITS OWN build.json" — and the two differ the day a second app legitimately
# declares build.host: null. On that day the hardcoded form goes red inside the
# SuperApp's release, for a correct change to a different app, and the fix
# available to whoever is paged is to edit this line — which is how a tester
# stops being evidence. So the expectation is DERIVED from the same data
# regen.sh reads. The regression it exists to catch is unaffected: if the
# predicate goes true for all 35, 34 of them have no null build.host to explain
# it and every one of those is named below.
unexplained=""
for id in $(jq -r '.apps[] | select(.blocked) | .id' "$FLEET"); do
  # regen.sh derives the fleet id from the directory basename with an
  # ac_cloud- or ac_c3- prefix stripped, so invert exactly that, and accept a
  # fork entry's own blocked_on as an equally declared reason.
  bj=""
  for cand in "$UNIX/ac_cloud-$id/build.json" "$UNIX/ac_c3-$id/build.json" "$UNIX/$id/build.json"; do
    [ -f "$cand" ] && { bj="$cand"; break; }
  done
  if [ -z "$bj" ]; then
    unexplained="$unexplained $id(no-build.json)"
  elif jq -e '((.build // {}) | has("host")) and (.build.host == null)' "$bj" >/dev/null 2>&1; then
    :   # declared: this app requires a build host and none has been established
  elif jq -e '[(.forks // {}) | to_entries[] | select(.value|type=="object") | .value.blocked_on] | any(. != null)' "$bj" >/dev/null 2>&1; then
    :   # declared: a fork carrying its own blocked_on
  else
    unexplained="$unexplained $id"
  fi
done
BLOCKED_IDS="$(jq -r '[.apps[] | select(.blocked) | .id] | sort | join(",")' "$FLEET")"
[ -z "$unexplained" ] \
  && ok "every blocked entry is explained by its own build.json (blocked: ${BLOCKED_IDS:-none})" \
  || bad "blocked with nothing in their build.json to justify it:$unexplained — a block rule that catches other apps stops their updates fleet-wide"

echo "== T5: a third party's artefact is never offered as ours =="
# THE DEFECT #266 NAMES. ac_cloud-sheets declares Cloud-Office.apk and
# com.diegonmarcos.cloudoffice, and nothing has ever built either: the only
# office artefact on the rolling `latest` release is Cloud-Sheets.apk, which is
# byte-identical to Collabora's own signed arm64 build — package
# com.collabora.libreoffice, signed by Collabora Productivity Limited, verified
# against their F-Droid index by sha256, size and signing certificate.
# build.json::published_artifact records that, and this asserts the one rule
# that must hold while it does: an artefact this fleet did not build must not be
# offered in the store as though it did. It is not "sheets is blocked" — it is
# keyed on the declaration, so it keeps its meaning for any future app in the
# same position, and it goes quiet on its own the day published_artifact says
# built_by_this_fleet: true.
# NOT `.published_artifact.built_by_this_fleet // "absent"`. jq's alternative
# operator fires on false as well as on null, so the one value this assertion
# exists to catch — built_by_this_fleet: false — would have read back as
# "absent" and taken the branch that says nothing is wrong. Ask whether the
# record EXISTS, then read it.
BUILT_BY_US="$(jq -r 'if (.published_artifact | type) == "object"
                      then (.published_artifact.built_by_this_fleet | tostring)
                      else "absent" end' "$OFFICE")"
if [ "$BUILT_BY_US" = "false" ]; then
  FOREIGN_PKG="$(jq -r '.published_artifact.package // ""'   "$OFFICE")"
  FOREIGN_BY="$(jq  -r '.published_artifact.publisher // ""' "$OFFICE")"
  [ "$BLOCKED" = "true" ] \
    && ok "the published artefact is $FOREIGN_BY's own build ($FOREIGN_PKG) and the store does not offer it as ours" \
    || bad "the published artefact is $FOREIGN_BY's own build ($FOREIGN_PKG), not this fleet's, yet the fleet entry is blocked=$BLOCKED — the store presents a third party's signed APK as a Cloud app"
elif [ "$BUILT_BY_US" = "absent" ]; then
  ok "no published_artifact record — nothing claims a foreign artefact is on the shelf"
else
  ok "published_artifact declares this fleet built it"
fi

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
