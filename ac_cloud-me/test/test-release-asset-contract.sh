#!/usr/bin/env bash
# Cloud Me release contract: every name in build.json::release that decides
# WHICH FILE the phone ends up with must be the same file.
#
# WHY THIS IS THIS APPLICATION'S FIRST REAL TEST.
# Cloud Me's ship workflow asked exactly one question until today — did Gradle
# exit 0 — and it is the application with the most repeat commits in the fleet
# after the two that already have testers. Its failure mode is not a crash: it
# is a green run whose APK nobody installs. Four separate names have to agree
# for a build to reach the phone, and NOTHING checks that they do:
#
#   release.artifact.debug            what build.sh stages into dist/
#   release.variants[].artifact_debug what the per-ABI build stages
#   release.variants[].gh_asset       what is uploaded, and what the publish
#                                     gate stamps its .source sidecar beside
#   release.gh_release.asset_name     what /releases/latest/download/<name>
#                                     resolves to — the in-app updater's URL
#   release.phone_install.asset_name  what the phone-side installer fetches
#
# Change one and not the others and the run is GREEN: the APK builds, uploads
# under one name, and the updater keeps fetching the other — which is the
# 2026-09-05 shape (#200: "installs succeed silently and the fleet reports the
# app outdated forever") arriving through a one-word edit instead of a skipped
# step. The published-nothing guard cannot see this: the publish step SUCCEEDS.
#
# OWN SOURCE ONLY. Reads ac_cloud-me/build.json and nothing else, so its verdict
# is this application's alone and stays fatal — a tester that reached into
# another app's tree would be downgraded to a warning by the foreign-source rule
# and could never fail this release.
set -euo pipefail
cd "$(dirname "$0")/.."

python3 - <<'PY'
import json, sys

cfg = json.load(open("build.json"))
rel = cfg.get("release") or {}
bad = []

ghr      = rel.get("gh_release") or {}
asset    = ghr.get("asset_name") or ""
rolling  = ghr.get("rolling_tag") or ""
phone    = (rel.get("phone_install") or {}).get("asset_name") or ""
artifact = (rel.get("artifact") or {}).get("debug") or ""
variants = rel.get("variants") or []

# Spelled as explicit comparisons against the LITERAL requirement, never
# `a == a`: this repository has shipped an assertion that compared an
# expression to itself and could not fail.
if not asset:
    bad.append("release.gh_release.asset_name is empty — nothing names the file "
               "/releases/latest/download/ resolves to")
if not rolling:
    bad.append("release.gh_release.rolling_tag is empty — the rolling release has no tag")
if not variants:
    bad.append("release.variants[] is empty — no ABI variant is declared, so no "
               "asset name resolves and the publish gate falls back to the whole app")

if asset and phone != asset:
    bad.append("release.phone_install.asset_name=%r but release.gh_release.asset_name=%r "
               "— the phone-side installer would fetch a file the ship never uploads" % (phone, asset))
if asset and artifact != asset:
    bad.append("release.artifact.debug=%r but release.gh_release.asset_name=%r — build.sh "
               "would stage one file and upload another" % (artifact, asset))

seen = {}
default = None
for v in variants:
    vid = v.get("id") or "<unnamed>"
    for field in ("id", "gh_asset", "artifact_debug", "update_tag"):
        if not v.get(field):
            bad.append("release.variants[%s] declares no %s" % (vid, field))
    ga = v.get("gh_asset")
    if ga:
        if ga in seen:
            bad.append("release.variants[%s] and [%s] both publish as %r — one "
                       "overwrites the other on the release" % (seen[ga], vid, ga))
        seen[ga] = vid
    if v.get("artifact_debug") and ga and v["artifact_debug"] != ga:
        bad.append("release.variants[%s] builds %r but publishes it as %r"
                   % (vid, v["artifact_debug"], ga))
    # The variant with no GHCR tag suffix is the default one: the publish gate
    # resolves ITS gh_asset when no variant is named, and that is the asset the
    # in-app updater downloads.
    if v.get("ghcr_tag_suffix", "") == "":
        if default is not None:
            bad.append("release.variants[%s] and [%s] both claim the empty "
                       "ghcr_tag_suffix — two variants cannot both be the default"
                       % (default, vid))
        default = vid
        if ga and asset and ga != asset:
            bad.append("the default variant [%s] publishes %r but "
                       "release.gh_release.asset_name is %r — the updater's URL "
                       "points at a file this build never produces" % (vid, ga, asset))
    # update_tag is what the fleet store polls. It is the rolling tag, suffixed
    # for a non-default ABI; anything else polls a release that does not exist.
    want = rolling + v.get("ghcr_tag_suffix", "")
    if rolling and v.get("update_tag") and v["update_tag"] != want:
        bad.append("release.variants[%s].update_tag=%r but rolling_tag+suffix is %r "
                   "— the updater would poll a tag nothing publishes to"
                   % (vid, v["update_tag"], want))

if variants and default is None:
    bad.append("no variant carries an empty ghcr_tag_suffix — nothing is the default, "
               "so build.sh and the publish gate resolve no asset name at all")

for b in bad:
    print("  FAIL: " + b)
print("── cloud-me release contract: %d problem(s) ──" % len(bad))
sys.exit(1 if bad else 0)
PY
