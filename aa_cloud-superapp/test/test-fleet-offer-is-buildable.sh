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
BLOCKED_IDS="$(jq -r '[.apps[] | select(.blocked) | .id] | sort | join(",")' "$FLEET")"
[ "$BLOCKED_IDS" = "sheets" ] \
  && ok "exactly one fleet entry is blocked, and it is the one with no build host (sheets)" \
  || bad "blocked entries are '$BLOCKED_IDS' — expected only 'sheets'; a block rule that catches other apps stops their updates fleet-wide"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
