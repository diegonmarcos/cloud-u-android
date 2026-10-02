#!/usr/bin/env bash
# publish-qalc.sh — build libqalc.so for every declared ABI and publish the set
# as ONE immutable release, printing the pin to adopt.
#
# The producer half of the native split (data/qalc-native.json::_doc): the
# Cloud Libs build never compiles C++, it downloads the pinned asset. Each
# publish gets its own tag (release_tag_prefix + UTC timestamp) so a new build
# never changes bytes under a pin that has not moved, and nothing here edits
# the pin: adopting a build is a commit someone makes on purpose, after reading
# the release notes this script writes. Same shape and reasons as
# libs/firewall/publish-firestack.sh.
#
#   publish-qalc.sh [--no-publish]   --no-publish: build + checksum + print the pin only
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CFG="$HERE/../data/qalc-native.json"
WORK="${QALC_WORK:-$(cd "$HERE/../../.." && pwd)/.cache/qalc}"
export QALC_OUT="$WORK/out"

log() { printf '  \033[36m•\033[0m %s\n' "$*" >&2; }
die() { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }
cfg() { jq -er "$1" "$CFG"; }

PUBLISH=1
[ "${1:-}" = "--no-publish" ] && PUBLISH=0

REPO="$(cfg '.publish.release_repo')"
TAG="$(cfg '.publish.release_tag_prefix')$(date -u +%Y%m%d.%H%M%S)"
TEMPLATE="$(cfg '.publish.asset_template')"
MAXBYTES="$(cfg '.publish.max_asset_bytes')"
SONAME="$(cfg '.build.soname')"
STAGE="$WORK/publish"
rm -rf "$STAGE"; mkdir -p "$STAGE"

ROWS=""
for abi in $(jq -r '.build.abis | keys[]' "$CFG"); do
    bash "$HERE/build-qalc.sh" "$abi"
    asset="${TEMPLATE//\{abi\}/$abi}"
    cp "$QALC_OUT/$abi/$SONAME" "$STAGE/$asset"
    bytes="$(stat -c%s "$STAGE/$asset")"
    [ "$bytes" -lt "$MAXBYTES" ] || die "$asset is $bytes bytes, at or over the $MAXBYTES release-asset limit"
    sum="$(sha256sum "$STAGE/$asset" | cut -d' ' -f1)"
    log "$asset — $bytes bytes, sha256 $sum"
    ROWS="$ROWS$abi	$asset	$sum	$bytes
"
done

# The pin, as jq builds it from what was measured, so it cannot drift from the upload.
PIN="$(printf '%s' "$ROWS" | jq -Rs --arg tag "$TAG" '
  [ split("\n")[] | select(length > 0) | split("\t") ] as $rows
  | { release_tag: $tag,
      assets: ( [ $rows[] | { key: .[0], value: { asset: .[1], sha256: .[2], bytes: (.[3] | tonumber) } } ] | from_entries ) }')"
printf '%s\n' "$PIN" > "$STAGE/pin.json"
printf '%s\n' "$PIN"

if [ "$PUBLISH" = 1 ]; then
    command -v gh > /dev/null || die "gh is required to publish"
    versions="$(jq -r '.build.order[] as $n | "\($n) \(.build.sources[$n].version) (\(.build.sources[$n].licence))"' "$CFG")"
    # --latest=false: this dated artifact must never take /releases/latest/
    # from the fleet's rolling `latest` release (the firestack 2026-09-10 404).
    gh release create "$TAG" --repo "$REPO" --latest=false \
        --title "Cloud-Lib-Calc native engine $TAG" \
        --notes "libqalc.so per ABI: qalc_core + JNI glue statically linked with
$versions
plus libc++ (NDK $(cfg '.build.ndk'), API $(cfg '.build.api')). Combined work: GPL-3.0-or-later.
Built and tested by ship-lib-calc-native.yml (golden.tsv + libqalculate's own make check on the host build).
Adopt by pasting into ab_cloud-libs-shared/libs/calc/data/qalc-pin.json:
\`\`\`json
$PIN
\`\`\`" \
        "$STAGE"/libqalc-*.so || die "gh release create failed for $TAG"
    log "published $TAG"
else
    log "--no-publish: built and checksummed, uploaded nothing"
fi
