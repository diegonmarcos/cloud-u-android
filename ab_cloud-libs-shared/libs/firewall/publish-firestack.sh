#!/usr/bin/env bash
# Build the firestack netstack aar from OUR OWN source and publish it as the
# pinned artifact the SuperApp and lib-apks consume.
#
# This is the PRODUCER half of the decoupling. build-firestack.sh compiles the
# aar; fetch-firestack.sh consumes a published one; this script is the bridge,
# and it is the only thing that ever puts an aar where a consumer can see it.
#
# WHY A SEPARATE, IMMUTABLE TAG PER PUBLISH
#
# The consumer pins a checksum. If publishes reused one rolling tag, then every
# republish would change the bytes under a pin that had not moved, and
# fetch-firestack.sh would correctly refuse them — turning a firestack success
# into an APK failure, which is the coupling this whole change exists to remove,
# merely inverted. So each publish creates a NEW tag, the old assets stay
# exactly as they were, and a consumer keeps building until somebody
# deliberately advances the pin. That is also why nothing here edits build.json:
# advancing the pin is a commit a person or agent makes on purpose, having looked
# at what they are adopting. See PIN ADVANCE below.
#
# Usage:  publish-firestack.sh [--tag TAG] [--no-publish]
#           --tag TAG      publish under this tag instead of a generated one
#           --no-publish   build + checksum + print the pin, upload nothing
#                          (this is what makes the script testable without
#                          creating releases)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"          # libs/firewall
SHARED_ROOT="$(cd "$HERE/../.." && pwd)"                       # ab_cloud-libs-shared
CFG="$SHARED_ROOT/build.json"
SRC="$HERE/firestack"

log()    { printf '  \033[36m•\033[0m %s\n' "$*"; }
errlog() { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; }

TAG=""
PUBLISH=1
while [ $# -gt 0 ]; do
  case "$1" in
    --tag)        TAG="${2:-}"; shift 2 ;;
    --no-publish) PUBLISH=0; shift ;;
    *) errlog "publish-firestack: unknown option: $1"; exit 2 ;;
  esac
done

command -v jq        >/dev/null 2>&1 || { errlog "firestack: jq is required"; exit 1; }
command -v sha256sum >/dev/null 2>&1 || { errlog "firestack: sha256sum is required"; exit 1; }
[ -f "$CFG" ] || { errlog "firestack: no $CFG"; exit 1; }

cfgv() { jq -r "$1 // empty" "$CFG"; }

AAROUT="$(cfgv '.firestack.build.aar_out')"
TEMPLATE="$(cfgv '.firestack.artifact.asset_template')"
REPO="$(cfgv '.firestack.artifact.release_repo')"
MAXBYTES="$(cfgv '.firestack.artifact.max_asset_bytes')"
TAGPREFIX="$(cfgv '.firestack.artifact.release_tag_prefix')"
for _pair in "aar_out:$AAROUT" "asset_template:$TEMPLATE" "release_repo:$REPO" \
             "max_asset_bytes:$MAXBYTES" "release_tag_prefix:$TAGPREFIX"; do
  [ -n "${_pair#*:}" ] || { errlog "firestack: build.json is missing .firestack.artifact.${_pair%%:*}"; exit 1; }
done
unset _pair

[ -n "$TAG" ] || TAG="${TAGPREFIX}$(date -u +%Y%m%d.%H%M%S)"

# The variants to build, from the SAME data that drives the gomobile bind, so a
# new ABI is added in one place. The "" key is the default alias for one of the
# real ones and would publish a duplicate asset, so it is skipped.
VARIANTS="$(jq -r '.firestack.build.gomobile_targets | keys[] | select(. != "")' "$CFG")"
[ -n "$VARIANTS" ] || { errlog "firestack: .firestack.build.gomobile_targets declares no variants"; exit 1; }

STAGE="$SHARED_ROOT/.cache/firestack-publish"
rm -rf "$STAGE"; mkdir -p "$STAGE"

# ── build one aar per ABI ──────────────────────────────────────────
# --force every time: build-firestack.sh short-circuits on an existing aar, and
# the aar path is the same file for every ABI, so without --force the second
# variant would publish the first variant's bytes under the second's name. That
# is a silent, checksum-consistent lie, and the only place it would surface is
# a phone failing to load the library at runtime.
ASSETS=""
for v in $VARIANTS; do
  log "firestack: building aar for variant '$v'"
  SUPERAPP_VARIANT="$v" bash "$HERE/build-firestack.sh" --force

  built="$SRC/$AAROUT"
  [ -f "$built" ] || { errlog "firestack: build produced no $built for variant '$v'"; exit 1; }

  asset="${TEMPLATE//\{variant\}/$v}"
  cp "$built" "$STAGE/$asset"

  bytes="$(stat -c%s "$STAGE/$asset")"
  # The fleet rule is that no file may approach GitHub's 100 MB per-blob cap.
  # Measured on the 2026-09-09 green run: 6.8 MB arm64, 7.4 MB amd64 — two
  # orders of magnitude clear. Asserted rather than assumed because a gomobile
  # flag change (dropping -w -s, or restoring all four ABIs) could move it a
  # long way, and the failure at the cap is a rejected upload nobody expects.
  [ "$bytes" -lt "$MAXBYTES" ] || {
    errlog "firestack: $asset is $bytes bytes, at or over the $MAXBYTES limit for a release asset"
    exit 1
  }

  sum="$(sha256sum "$STAGE/$asset" | cut -d' ' -f1)"
  log "firestack: $asset — $bytes bytes, sha256 $sum"
  ASSETS="$ASSETS$v	$asset	$sum
"
done

# ── the pin, ready to paste ────────────────────────────────────────
# Built with jq from the measured values rather than printf'd by hand, so it is
# valid JSON by construction and cannot drift from what was actually uploaded.
PIN="$(printf '%s' "$ASSETS" | jq -Rs --arg tag "$TAG" --arg repo "$REPO" '
  [ split("\n")[] | select(length > 0) | split("\t") ] as $rows
  | { release_repo: $repo,
      release_tag: $tag,
      assets: ( [ $rows[] | { key: .[0], value: { asset: .[1], sha256: .[2] } } ] | from_entries ) }')"

# The "" default entry: the variant the APK builds when SUPERAPP_VARIANT is
# unset. Taken from gomobile_targets' own "" alias so the two cannot disagree.
DEFAULT_VARIANT="$(jq -r '
  .firestack.build.gomobile_targets as $t
  | ($t[""] // empty) as $d
  | ([ $t | to_entries[] | select(.key != "" and .value == $d) | .key ] | first) // empty' "$CFG")"
if [ -n "$DEFAULT_VARIANT" ]; then
  PIN="$(printf '%s' "$PIN" | jq --arg d "$DEFAULT_VARIANT" '.assets[""] = .assets[$d]')"
fi

# ── publish ────────────────────────────────────────────────────────
if [ "$PUBLISH" = "1" ]; then
  command -v gh >/dev/null 2>&1 || { errlog "firestack: gh is required to publish"; exit 1; }
  log "firestack: creating release $TAG in $REPO"
  # --notes carries the pin so the release page itself records what to pin,
  # which is the one place a reader always has when adopting an old artifact.
  gh release create "$TAG" --repo "$REPO" \
     --title "firestack netstack aar $TAG" \
     --notes "Prebuilt firestack netstack aar (MPL-2.0, celzero/firestack lineage; this tree is owned outright by this repository).

Consumed by aa_cloud-superapp and ab_cloud-libs-shared/lib-apks through
ab_cloud-libs-shared/libs/firewall/fetch-firestack.sh. The APK does NOT build
this from source, so a red firestack build cannot stop an APK publish.

Pin this by pasting into ab_cloud-libs-shared/build.json::firestack.artifact:

\`\`\`json
$PIN
\`\`\`
" \
     "$STAGE"/* || { errlog "firestack: gh release create failed for $TAG"; exit 1; }
  log "firestack: published $TAG"
else
  log "firestack: --no-publish — built and checksummed, uploaded nothing"
fi

# ── PIN ADVANCE ────────────────────────────────────────────────────
# Deliberately NOT automatic. Nothing here commits, opens a pull request or
# rewrites build.json. The whole failure this change addresses was a firestack
# problem flowing straight into the APK without anyone choosing it; an
# auto-advancing pin would restore that with extra steps, just moving the
# breakage from "the aar would not compile" to "the aar compiled and is wrong".
#
# So the aar publishes on its own cadence and then WAITS. A person or an agent
# adopts it by copying the block below into
# ab_cloud-libs-shared/build.json::firestack.artifact and committing that —
# one small, reviewable, reverting-friendly diff whose whole content is "the
# APK now uses this aar". Because build.json is in the SuperApp workflow's
# trigger paths, that commit is also what rebuilds and republishes the APK.
printf '\n'
log "firestack: PIN ADVANCE — paste this into $CFG :: firestack.artifact to adopt this aar"
printf '\n%s\n\n' "$PIN"

# GitHub renders the job summary on the run page, which is where whoever
# triggered this build is already looking.
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    printf '## firestack aar published: `%s`\n\n' "$TAG"
    printf 'To make the SuperApp use it, paste into `ab_cloud-libs-shared/build.json` under `firestack.artifact`:\n\n'
    printf '```json\n%s\n```\n\n' "$PIN"
    printf 'Until that commit lands the APK keeps building against the aar it already pins — which is the point.\n'
  } >> "$GITHUB_STEP_SUMMARY"
fi
