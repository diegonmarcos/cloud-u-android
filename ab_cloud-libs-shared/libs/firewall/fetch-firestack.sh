#!/usr/bin/env bash
# Put the PINNED, PREBUILT firestack netstack aar where libs:firewall expects it.
#
# WHY THIS SCRIPT EXISTS AT ALL
#
# Until 2026-09-10 the SuperApp APK job built this aar from Go source, inline,
# as the first thing every gradle invocation did (build.sh::run_gradle called
# _ensure_firestack before in_nix gradle). That made a Go library the owner does
# not maintain, wrapping a third-party project the owner does not own, a VETO on
# every Android change in the fleet. It was exercised: commit 9a0ad74b8 changed
# how the aar resolved its dependencies, `gomobile bind` then died on
# "go: error reading go.mod: missing module declaration" on every run, the APK
# job died with it, `Publish to GitHub Releases` was skipped, and the owner's
# phone served an APK from 2026-09-09 16:35 for a day and a half while roughly
# forty commits of unrelated work — theme fixes, Spanish translations, crash
# fixes, the control panel — sat stranded in the repository.
#
# The owner's ruling: firestack "should be a lib that would never block superapp
# release". So the aar is now a PREBUILT ARTIFACT, produced on its own cadence by
# ship-firestack-aar.yml and consumed here by version and checksum. A red
# firestack build now delays firestack and nothing else: the APK goes on
# consuming the last aar that was deliberately pinned.
#
# THIS SCRIPT NEVER BUILDS FROM SOURCE. NOT AS A FALLBACK, NOT ON A CACHE MISS,
# NOT WHEN THE DOWNLOAD FAILS.
#
# That is the whole point and it is the one thing that must not be "helpfully"
# relaxed later. A source-build fallback would quietly restore the exact
# coupling this removes, and the next outage would look identical to the last
# one — the APK job running Go, dying in it, and taking the publish down. When
# the pinned artifact cannot be had, this script fails LOUDLY and the APK job
# stops. That is a firestack problem being reported as a firestack problem, and
# it is fixed by advancing or correcting the pin, never by compiling here.
#
# The source build still exists — build-firestack.sh, invoked by
# `build.sh firestack` and by ship-firestack-aar.yml. It is simply no longer on
# the path between a Kotlin change and the owner's phone.
#
# Usage:  fetch-firestack.sh [--force]
#           --force   re-download even when the local aar already matches the pin
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"          # libs/firewall
SHARED_ROOT="$(cd "$HERE/../.." && pwd)"                       # ab_cloud-libs-shared
CFG="$SHARED_ROOT/build.json"
SRC="$HERE/firestack"                                          # owned source AND aar home

log()    { printf '  \033[36m•\033[0m %s\n' "$*"; }
errlog() { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; }

command -v jq       >/dev/null 2>&1 || { errlog "firestack: jq is required";       exit 1; }
command -v curl     >/dev/null 2>&1 || { errlog "firestack: curl is required";     exit 1; }
command -v sha256sum>/dev/null 2>&1 || { errlog "firestack: sha256sum is required"; exit 1; }
[ -f "$CFG" ] || { errlog "firestack: no $CFG"; exit 1; }

cfgv() { jq -r "$1 // empty" "$CFG"; }

AAROUT="$(cfgv '.firestack.build.aar_out')"
[ -n "$AAROUT" ] || { errlog "firestack: build.json has no .firestack.build.aar_out"; exit 1; }
AAR="$SRC/$AAROUT"

# ── the pin ────────────────────────────────────────────────────────
# REQUIRED, not defaulted. An absent pin used to mean "build it from source",
# and that is precisely the coupling being removed: a later edit that dropped
# these keys would silently put the Go build back on the APK's critical path
# and nobody would notice until the next outage. So a missing pin is a hard
# error that names the file to fix.
REPO="$(cfgv '.firestack.artifact.release_repo')"
TAG="$(cfgv '.firestack.artifact.release_tag')"
[ -n "$REPO" ] && [ -n "$TAG" ] || {
  errlog "firestack: no prebuilt-aar pin in $CFG (.firestack.artifact.release_repo / .release_tag)."
  errlog "firestack: the APK consumes a PINNED aar and never builds one. Publish an aar with"
  errlog "firestack:   ship-firestack-aar.yml   (or ./build.sh firestack-publish)"
  errlog "firestack: then record its tag and checksums under .firestack.artifact."
  exit 1
}

# The aar is PER-ABI — measured, not assumed: the 2026-09-09 green run produced
# 6.8 MB for android/arm64 and 7.4 MB for android/amd64. One checksum for both
# would therefore be wrong for one of them, so the pin is keyed by variant with
# the same key shape (and the same "" default) as .firestack.build.gomobile_targets.
VARIANT="${SUPERAPP_VARIANT:-}"
ASSET="$(jq -r --arg v "$VARIANT" \
  '.firestack.artifact.assets[$v].asset // .firestack.artifact.assets[""].asset // empty' "$CFG")"
WANT="$(jq -r --arg v "$VARIANT" \
  '.firestack.artifact.assets[$v].sha256 // .firestack.artifact.assets[""].sha256 // empty' "$CFG")"
[ -n "$ASSET" ] && [ -n "$WANT" ] || {
  errlog "firestack: no pinned aar for SUPERAPP_VARIANT='$VARIANT' in $CFG"
  errlog "firestack: .firestack.artifact.assets needs an entry for '$VARIANT' (or a \"\" default)"
  errlog "firestack: with both an 'asset' name and its 'sha256'."
  exit 1
}

# ── already correct? ───────────────────────────────────────────────
# Compared against the PIN, not merely tested for existence. build-firestack.sh
# skipped on presence alone, which is right for a thing it had just built and
# wrong for a thing that has to match a declared checksum: a stale aar left in
# the tree by an earlier variant's build would otherwise be silently compiled
# into the APK for the wrong ABI.
_have() { [ -f "$AAR" ] && [ "$(sha256sum "$AAR" | cut -d' ' -f1)" = "$WANT" ]; }

if [ "${1:-}" != "--force" ] && _have; then
  log "firestack: pinned aar already present and matches $WANT (abi='${VARIANT:-default}') — skipping"
  exit 0
fi

URL="https://github.com/$REPO/releases/download/$TAG/$ASSET"
log "firestack: fetching pinned aar $TAG/$ASSET (abi='${VARIANT:-default}')"

mkdir -p "$SRC"
TMP="$(mktemp "$SRC/.firestack-aar.XXXXXX")"
# The temp file is removed on EVERY exit path. Leaving a partial download beside
# the real aar is how a truncated artifact gets picked up by a later run.
trap 'rm -f "$TMP"' EXIT

# --fail so an HTML 404 page is an error rather than 8 KB of markup that then
# fails the checksum with a confusing message; --retry for transport flakiness,
# which is not the same thing as a wrong pin and should not read like one.
if ! curl -fLsS --retry 3 --retry-delay 2 -o "$TMP" "$URL"; then
  errlog "firestack: could not fetch the pinned aar: $URL"
  errlog "firestack: THIS IS A FIRESTACK PROBLEM, NOT AN APK PROBLEM. The APK is not built"
  errlog "firestack: from firestack source any more and must not be: fix the artifact or the"
  errlog "firestack: pin. Check that release '$TAG' in $REPO still carries asset '$ASSET'."
  exit 1
fi

GOT="$(sha256sum "$TMP" | cut -d' ' -f1)"
if [ "$GOT" != "$WANT" ]; then
  errlog "firestack: pinned aar FAILED verification — refusing to build with it."
  errlog "firestack:   url      $URL"
  errlog "firestack:   expected $WANT"
  errlog "firestack:   actual   $GOT"
  errlog "firestack: a release asset changed under a pin that is supposed to be immutable."
  errlog "firestack: publish a NEW tag and advance .firestack.artifact; do NOT relax this check."
  exit 1
fi

# Only now does it become the real aar. Verifying in place would leave a
# rejected artifact sitting at the path gradle reads.
mv "$TMP" "$AAR"
trap - EXIT

# Same shape check build-firestack.sh runs on what it produces. A checksum
# proves we got the bytes that were published; it does not prove the bytes that
# were published are an aar. unzip absence is FATAL here rather than a skip —
# a tester that passes because its tool is missing is the false green this
# repository has already shipped four of.
command -v unzip >/dev/null 2>&1 || {
  errlog "firestack: unzip is required to verify the aar's shape (a skipped check is a false pass)"; exit 1; }
unzip -l "$AAR" | grep -q "classes.jar"         || { errlog "firestack: pinned aar has no classes.jar"; exit 1; }
unzip -l "$AAR" | grep -q "AndroidManifest.xml" || { errlog "firestack: pinned aar has no AndroidManifest.xml"; exit 1; }

log "firestack: → $AAR ($(du -h "$AAR" 2>/dev/null | cut -f1), sha256 $GOT, from $TAG)"
