#!/usr/bin/env bash
# Tester: every constellation app's published artifact can be fetched BY A
# PHONE THAT HAS NO CREDENTIALS.
#
# This is the check that was missing on 2026-09-10, when the owner tapped
# update on Cloud Terminal (Nix) and got a bare "HTTP 403". Nothing in the repo
# asked the one question that mattered — "can an anonymous client actually get
# this APK?" — so the answer was discovered by the owner instead of by CI.
#
# WHY ANONYMOUS IS THE WHOLE POINT
# The phone is not logged in to anything. A probe that carries credentials
# answers a different question than the one the owner is asking, and answers it
# more favourably: an authenticated GET of a PRIVATE GHCR package returns 200,
# so a credentialed check reports green on precisely the artifact nobody can
# install. Every request below therefore sends `-H "Authorization:"`, which
# tells curl to send the header EMPTY and so suppresses any ambient credential
# (a ~/.curlrc, a CI environment default, a proxy that injects one). Do not
# remove it, and do not add a token "just to avoid rate limits" — a green from
# a credentialed probe here is a lie about the only user who matters.
#
# WHAT IS FATAL AND WHAT IS ADVISORY
# cloud-android-test-engine.sh globs test-*.sh, so this file runs inside the
# SuperApp ship path and a red here CAN stop the owner's APK. That is the
# constraint the split below is built around, not an afterthought.
#
# T1/T2 are static assertions about our own data and are always fatal.
# T3 is the live question, and it is fatal on exactly one condition: an app
# with NO anonymously reachable artifact on EITHER channel — an app nobody can
# install. One channel being down is advisory, because an app whose release
# asset serves 200 is installable whether or not it also has a registry
# package; that case prints WARN so a silently-degraded app is visible before
# it becomes the app with no channels left. And every fatal verdict is gated on
# a per-channel reachability control (see T3), so a runner with no route
# reports "unverified" instead of blaming the fleet. The fleet's ruling that a
# third party's live behaviour must not veto the owner's publish is honoured by
# that control, not by declining to assert anything.
#
# Usage:
#   ./test-artifact-anonymously-fetchable.sh
#   FLEET_JSON=/path/to/other-fleet.json ./test-artifact-anonymously-fetchable.sh
#
# The FLEET_JSON seam exists so this tester can be watched failing on purpose
# against a manifest doctored to name an artifact that is not public. An
# assertion nobody has seen go red is decoration.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"                        # → aa_cloud-superapp
FLEET="${FLEET_JSON:-$APP/data/constellation-fleet.json}"

PASS=0; FAIL=0; WARN=0
ok()   { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
warn() { WARN=$((WARN+1)); echo "  WARN: $1"; }

# FAIL CLOSED ON A MISSING TOOL. Four testers in this repo once passed only
# because ripgrep was absent and their `rg` call failed open, so a missing
# dependency here is an error and not a skip: we cannot answer the question, and
# saying nothing is how the question stopped being asked.
for tool in curl jq awk; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: $tool required, refusing to report green without it" >&2; exit 2; }
done
[ -f "$FLEET" ] || { echo "ERROR: fleet manifest not found: $FLEET" >&2; exit 2; }
jq -e '.apps | type == "array" and length > 0' "$FLEET" >/dev/null 2>&1 \
  || { echo "ERROR: $FLEET has no .apps array — nothing to check" >&2; exit 2; }

# One anonymous HEAD, redirects followed, credentials suppressed. Echoes the
# final status code, or 000 when curl itself could not complete the request —
# which is distinguishable from any real HTTP status and so cannot be mistaken
# for a success.
anon_status() {
  curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
       --head --location --max-time 45 -H "Authorization:" "$1" 2>/dev/null || echo 000
}

# GHCR needs a pull token before it will answer for a manifest. Anonymously,
# ghcr.io/token issues one for a PUBLIC package and answers 403 DENIED for a
# package that is private OR does not exist — it will not distinguish the two,
# because confirming absence would leak private package names. So "no token"
# is reported as exactly that rather than guessed at.
ghcr_status() {
  local registry="$1" namespace="$2" image="$3" token
  token="$(curl --silent --max-time 30 -H "Authorization:" \
            "https://$registry/token?service=$registry&scope=repository:$namespace/$image:pull" \
           2>/dev/null | jq -r '.token // ""')"
  [ -n "$token" ] || { echo 403-notoken; return; }
  curl --silent --output /dev/null --write-out '%{http_code}' --max-time 30 \
       -H "Authorization: Bearer $token" \
       -H "Accept: application/vnd.oci.image.manifest.v1+json" \
       "https://$registry/v2/$namespace/$image/manifests/latest" 2>/dev/null || echo 000
}

echo "== T1: no release URL uses GitHub's magic 'latest release' route =="
# /releases/latest/download/<asset> resolves to whichever release is newest,
# not to the release tagged `latest`, so every per-app tagged release published
# in this repo steals it and the asset 404s until the next rolling publish.
# /releases/download/<tag>/<asset> names the tag and cannot be hijacked.
MAGIC="$(jq -r '.apps[] | select(.release_url // "" | contains("/releases/latest/download/")) | .id' "$FLEET")"
if [ -z "$MAGIC" ]; then
  ok "no fleet entry uses /releases/latest/download/"
else
  bad "these entries use the hijackable magic route: $(echo "$MAGIC" | awk '{printf "%s ", $0}')"
fi

echo "== T2: the generator cannot reintroduce it =="
# The manifest is generated, so a clean T1 proves only that somebody ran
# regen.sh after the fix. Guard the source too, or the next regen undoes it.
REGEN="$APP/data/regen.sh"
if [ -f "$REGEN" ]; then
  if grep -q '"/latest/download/"' "$REGEN"; then
    bad "regen.sh still builds the magic route — a regen will undo T1"
  else
    ok "regen.sh builds no /latest/download/ URL"
  fi
  grep -q '"/download/"' "$REGEN" \
    && ok "regen.sh builds the explicit /releases/download/<tag>/ form" \
    || bad "regen.sh builds neither form — has the URL construction moved?"
else
  bad "regen.sh not found at $REGEN"
fi

echo "== T3: LIVE — each artifact is fetchable with NO credentials =="
# REACHABILITY CONTROL, PER CHANNEL, BEFORE ANY VERDICT.
# This tester is globbed into the SuperApp ship path by
# cloud-android-test-engine.sh, so a fatal verdict here can stop the owner's
# APK. The fleet's ruling on that is settled: a third party's live behaviour
# must never veto the owner's publish (it cost a day and a half once as an
# inline Go build, and a publish before that as a pricing page). But "our
# artifact is gone" and "this runner has no route to the internet" are
# different facts and only the first is ours, so ask a control endpoint on each
# channel rather than exempting the channel wholesale. Control up ⇒ an
# unreachable artifact is a real finding and fatal. Control down ⇒ the runner
# cannot answer the question, which is reported and not charged to the fleet.
#
# Deliberately NOT "did any app answer?": with one app in the manifest that
# test cannot tell an outage from a genuinely dead artifact, and it would have
# turned the single-app case into a false green.
GH_CONTROL="$(anon_status https://github.com)"
# ghcr.io/v2/ answers 401 to an anonymous caller — a 401 from the registry root
# PROVES the registry is reachable and talking, so it counts as control-up.
# Only 000 (curl could not complete) means no route.
GHCR_CONTROL="$(curl --silent --output /dev/null --write-out '%{http_code}' \
                     --max-time 30 -H "Authorization:" https://ghcr.io/v2/ 2>/dev/null || echo 000)"
[ "$GH_CONTROL" = "200" ] && REL_FATAL=yes || REL_FATAL=no
[ "$GHCR_CONTROL" != "000" ] && REG_FATAL=yes || REG_FATAL=no
echo "  control: github.com=$GH_CONTROL (release verdicts fatal: $REL_FATAL), ghcr.io/v2/=$GHCR_CONTROL (registry verdicts fatal: $REG_FATAL)"
if [ "$REL_FATAL" = "no" ] && [ "$REG_FATAL" = "no" ]; then
  warn "no route to github.com or ghcr.io from this runner — cannot answer whether artifacts are fetchable"
fi

UNREACHABLE=""
while IFS=$'\t' read -r id registry namespace image release_url; do
  [ -n "$id" ] || continue

  rel="skip"
  if [ -n "$release_url" ] && [ "$release_url" != "null" ]; then
    rel="$(anon_status "$release_url")"
  fi

  reg="skip"
  if [ -n "$image" ] && [ "$image" != "null" ] && [ -n "$registry" ] && [ "$registry" != "null" ]; then
    reg="$(ghcr_status "$registry" "$namespace" "$image")"
  fi

  if [ "$rel" = "200" ] || [ "$reg" = "200" ]; then
    # Installable. Say which channels answered, so a silently-degraded app is
    # visible before it becomes the app with no channels left.
    if [ "$rel" = "200" ] && [ "$reg" = "200" ]; then
      ok "$id — release 200, registry 200"
    elif [ "$rel" = "200" ]; then
      warn "$id — release 200, registry $reg (no registry fallback: a release hiccup surfaces as a raw error)"
    else
      warn "$id — release $rel, registry 200 (release asset missing: the primary channel is down)"
    fi
  elif [ "$REL_FATAL" = "yes" ] || [ "$REG_FATAL" = "yes" ]; then
    # At least one channel's control answered, so at least one of these
    # non-200s is a fact about our artifact and not about the network.
    bad "$id — NOT ANONYMOUSLY FETCHABLE (release $rel, registry $reg)"
    UNREACHABLE="$UNREACHABLE $id"
  else
    warn "$id — unverified (release $rel, registry $reg) — no route to either control"
  fi
done < <(jq -r '.apps[]
                | select(.blocked != true)
                | [ .id,
                    (.registry // ""),
                    (.namespace // ""),
                    (.image // ""),
                    (.release_url // "") ]
                | @tsv' "$FLEET")

echo
if [ -n "$UNREACHABLE" ]; then
  echo "APPS NOBODY CAN INSTALL:$UNREACHABLE"
  echo "  A 403 from ghcr.io means private OR never pushed — the registry will"
  echo "  not say which. Check the app's ship workflow for a GHCR push step"
  echo "  before assuming it is a permissions problem."
fi
echo "== RESULT: $PASS passed, $FAIL failed, $WARN advisory =="
[ "$FAIL" -eq 0 ]
