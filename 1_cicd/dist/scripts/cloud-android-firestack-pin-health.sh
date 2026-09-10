# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-firestack-pin-health.sh ───
#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-firestack-pin-health                               ║
# ║                                                                  ║
# ║ REPORTS on the prebuilt firestack aar the APK pins. Never gates. ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS, AND WHY IT IS ONLY A REPORT.
#
# Until 2026-09-10 the SuperApp APK job compiled the firestack netstack aar from
# Go source before every gradle invocation. That made a library the owner does
# not maintain a VETO on every Android change: on 2026-09-09 commit 9a0ad74b8
# broke the aar build, `Build APK (engine)` died inside it, the publish step was
# skipped, and the owner's phone served an APK from 16:35 for a day and a half.
#
# The aar is now a pinned prebuilt artifact and a red firestack cannot stop an
# APK. The price of that is silence: a red firestack used to be impossible to
# miss precisely BECAUSE it took the phone down. This script is what is paid
# instead — it says, on every APK build, which aar is pinned, how old it is, and
# whether firestack's own last build was red.
#
# IT MUST NEVER BECOME A GATE. The workflow calls it with continue-on-error and
# after the publish, and it exits 0 on every path including its own internal
# failures. That is not sloppiness, it is the contract: this fleet has twice
# wired something that should report as something that can veto — an inline Go
# build, and a live third-party pricing page, both on this same publish step —
# and both cost the owner an APK. If the pinned aar is stale that is information
# for the owner, not grounds to withhold an APK carrying forty other commits.
#
# It exits 0 even when it cannot answer, but it never SAYS things are fine when
# it does not know: an unanswerable question is printed as a warning, because
# "the tool was missing" and "everything is healthy" must not look the same.
#
# Usage:  cloud-android-firestack-pin-health.sh <repo-slug>
set -u

ROOT="${CLOUD_ANDROID_ROOT:-$(_d="$(cd "$(dirname "$0")" && pwd)"; while [ "$_d" != "/" ] && [ ! -e "$_d/.git" ]; do _d="$(dirname "$_d")"; done; printf '%s' "$_d")}"
REPO="${1:-}"
BJ="$ROOT/ab_cloud-libs-shared/build.json"

warn() { printf '::warning title=%s::%s\n' "$1" "$2"; }

[ -n "$REPO" ] || { warn "firestack pin health" "called with no repository slug — nothing checked"; exit 0; }

if ! command -v jq >/dev/null 2>&1; then
    warn "firestack pin health" "jq is not available, so the pin could not be read. This is not a statement that the pin is healthy."
    exit 0
fi
if [ ! -f "$BJ" ]; then
    warn "firestack pin health" "no $BJ — the pin could not be read."
    exit 0
fi

PIN_TAG="$(jq -r '.firestack.artifact.release_tag // empty' "$BJ" 2>/dev/null)"
if [ -z "$PIN_TAG" ]; then
    warn "firestack pin absent" "ab_cloud-libs-shared/build.json declares no firestack.artifact.release_tag, so this APK is not consuming a pinned aar. Until it does, a red firestack can still take the APK down."
    exit 0
fi
printf 'firestack: this APK is pinned to aar release %s\n' "$PIN_TAG"

command -v gh >/dev/null 2>&1 || {
    warn "firestack pin health" "gh is not available, so neither the pin's age nor firestack's own build status could be read."
    exit 0
}

# ── how old is the aar we adopted ──────────────────────────────────
# Reported, never judged. There is deliberately NO staleness threshold: "how
# stale is too stale" is the owner's call, and a number invented here would
# eventually be enforced by somebody, which is how a report becomes a gate.
PIN_DATE="$(gh release view "$PIN_TAG" --repo "$REPO" --json publishedAt --jq '.publishedAt' 2>/dev/null)"
if [ -n "$PIN_DATE" ]; then
    printf 'firestack: that aar was published %s\n' "$PIN_DATE"
else
    warn "firestack pin unreadable" "could not read release $PIN_TAG from $REPO — the pin may name a release that has been deleted, which would fail the next cold APK build."
fi

# ── is firestack's own build red right now ─────────────────────────
# EMPTY OUTPUT IS NOT SUCCESS. `gh run list` answering nothing means the
# question was not answered, and reporting that as green is precisely the
# false-pass shape this repository has already shipped several of. So the empty
# case is its own branch and says what it means.
LATEST="$(gh run list --repo "$REPO" --workflow ship-firestack-aar.yml --branch main --limit 1 \
            --json conclusion,url --jq '.[0] | "\(.conclusion) \(.url)"' 2>/dev/null)"
CONCLUSION="${LATEST%% *}"
RUN_URL="${LATEST#* }"

if [ -z "$LATEST" ]; then
    printf 'firestack: no ship-firestack-aar.yml run on main yet — nothing to report\n'
elif [ "$CONCLUSION" = "success" ]; then
    printf 'firestack: its own last build on main was green (%s)\n' "$RUN_URL"
else
    warn "firestack's own build is red" \
         "ship-firestack-aar.yml last concluded '$CONCLUSION' ($RUN_URL). THIS APK IS UNAFFECTED and shipped normally from the pinned aar $PIN_TAG. firestack itself needs attention before a newer aar can be adopted. Do NOT restore an inline source build in the APK job to work around it."
fi

# Always 0. See the contract at the top of this file.
exit 0
