#!/usr/bin/env bash
#
# Cloud Office — does the patch series still apply to the pinned upstream?
#
# THIS IS THE ASSERTION THE WHOLE CLONE STRUCTURE RESTS ON. The source is not in
# this repository; it is fetched at a pinned revision and these patches are
# applied to it. If a patch stops applying, the build either loses a feature
# silently or produces something nobody reviewed. Neither may pass quietly.
#
# It talks to the network on purpose — the pin is a claim about a remote object,
# and a test that mocked the remote would assert nothing about the thing that can
# actually break. A fetch failure is a FAILURE here, not a skip: this fleet has
# already shipped a CI step that network-fetched and died on a hiccup, and the
# lesson was to be loud, not to be lenient.
#
#   ./tests/patches-apply.sh          # fetch the pin, apply the series
#   ./tests/patches-apply.sh --self-test   # also prove the failure path fails
#
set -uo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_JSON="$APP_DIR/build.json"
WORK="${TMPDIR:-/tmp}/cloud-office-patch-check.$$"

fail() { printf '\033[0;31mFAIL: %s\033[0m\n' "$1" >&2; exit 1; }
pass() { printf '\033[0;32mok: %s\033[0m\n' "$1"; }

command -v jq  >/dev/null 2>&1 || fail "jq is not on PATH"
command -v git >/dev/null 2>&1 || fail "git is not on PATH"
[ -f "$BUILD_JSON" ] || fail "no build.json at $BUILD_JSON"

REPO="$(jq -r '.upstream.online.url // empty'      "$BUILD_JSON")"
REF="$(jq  -r '.upstream.online.ref // empty'      "$BUILD_JSON")"
PIN="$(jq  -r '.upstream.online.revision // empty' "$BUILD_JSON")"

[ -n "$REPO" ] || fail "build.json::upstream.online.url is empty"
[ -n "$REF"  ] || fail "build.json::upstream.online.ref is empty"
[ -n "$PIN"  ] || fail "build.json::upstream.online.revision is empty"

# A branch name in the revision slot is the exact bug this pin exists to prevent:
# it looks pinned, it reads as pinned in review, and it moves under you.
case "$PIN" in
    [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]*) : ;;
    *) fail "upstream.online.revision is '$PIN' — that is not a commit sha" ;;
esac
[ "${#PIN}" -eq 40 ] || fail "upstream.online.revision must be a full 40-char sha, got ${#PIN} chars"

trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK"

# ── fetch the pin ──────────────────────────────────────────────────────
# By SHA where the server allows it. Fetching a branch and trusting its tip is
# how this fleet already lost a build to a rolling-tag hash race.
#
# GERRIT WILL NOT DO EITHER OF THE FAST THINGS, measured rather than assumed:
# it answers `upload-pack: not our ref` to a sha fetch and
# `filtering not recognized by server, ignoring` to --filter=blob:none. So the
# fallback fetches the REF with enough depth to still contain the pin after some
# upstream movement, then checks the pin object is actually there. That check is
# the assertion; the fetch is only how it gets something to assert about.
#
# Budget for it: --depth 1 of co-26.04-mobile measured 471 MB and about ten
# minutes from this container. CI needs a cache, not optimism.
#
# CLOUD_OFFICE_SRC=<dir> points at an existing checkout and skips the network
# entirely — for iterating on a patch, never for proving a pin.
if [ -n "${CLOUD_OFFICE_SRC:-}" ]; then
    [ -d "$CLOUD_OFFICE_SRC/.git" ] || fail "CLOUD_OFFICE_SRC=$CLOUD_OFFICE_SRC is not a git checkout"
    git clone -qs "$CLOUD_OFFICE_SRC" "$WORK/online" 2>/dev/null || fail "cannot clone $CLOUD_OFFICE_SRC"
    git -C "$WORK/online" cat-file -e "$PIN^{commit}" 2>/dev/null \
        || fail "CLOUD_OFFICE_SRC does not contain $PIN — it is not a checkout of the pin"
    git -C "$WORK/online" -c advice.detachedHead=false checkout -q "$PIN"
    printf '\033[0;33mnote: used CLOUD_OFFICE_SRC, the pin was NOT fetched from %s\033[0m\n' "$REPO"
else
    git init -q -b main "$WORK/online" 2>/dev/null || git init -q "$WORK/online"
    git -C "$WORK/online" remote add origin "$REPO"
    if ! git -C "$WORK/online" fetch -q --filter=blob:none --depth 1 origin "$PIN" 2>"$WORK/fetch.err"; then
        if ! git -C "$WORK/online" fetch -q --depth 200 origin "$REF" 2>>"$WORK/fetch.err"; then
            sed 's/^/    /' "$WORK/fetch.err" >&2
            fail "cannot fetch $REF from $REPO — refusing to report a green check on an unfetched pin"
        fi
        # The ref's tip is ALLOWED to have moved; the pin still has to be in what
        # we got. If it is not, either upstream rewrote history or the pin is
        # older than the fetch depth, and both mean a human has to look.
        git -C "$WORK/online" cat-file -e "$PIN^{commit}" 2>/dev/null \
            || fail "$PIN is not reachable within 200 commits of $REF — re-pin and re-verify the series"
    fi
    git -C "$WORK/online" -c advice.detachedHead=false checkout -q "$PIN"
fi
pass "checked out pinned revision $PIN"

# ── apply the series ───────────────────────────────────────────────────
# In numeric order, and stopping at the first failure. --keep-non-patch because
# git am otherwise eats a bracketed prefix out of the subject line.
shopt -s nullglob
patches=("$APP_DIR"/patches/[0-9][0-9][0-9][0-9]-*.patch)
[ "${#patches[@]}" -gt 0 ] || fail "no numbered patches in $APP_DIR/patches"

for p in "${patches[@]}"; do
    # NOT in a pipeline. `git am ... | tail` reports tail's exit status, which is
    # always 0 — that is a test that passes while the thing it tests fails, and
    # this repo shipped one of those last week.
    if git -C "$WORK/online" am --keep-non-patch "$p" >"$WORK/am.out" 2>&1; then
        pass "applies: $(basename "$p")"
    else
        sed 's/^/    /' "$WORK/am.out" >&2
        git -C "$WORK/online" am --abort >/dev/null 2>&1
        fail "$(basename "$p") does not apply to $PIN — rebase the patch or re-pin; do NOT --skip it"
    fi
done

# ── what the series must have produced ─────────────────────────────────
# Applying cleanly is not the same as landing the right thing. These are the
# claims a reviewer would otherwise have to take on trust.
src="$WORK/online"

grep -q "isEnhanceRequest" "$src/browser/src/map/Clipboard.js" \
    || fail "Clipboard.js has no isEnhanceRequest — leg 1's one-shot flag is missing"
pass "leg 1: Clipboard carries the one-shot enhance flag"

# The selection must come from core's answer to gettextselection — the same
# source copy uses. If this ever reads the hidden contenteditable instead, it is
# reading the blank buffer that defeated the keyboard, and it will look like it
# works right up until someone selects something.
grep -q "gettextselection" "$src/browser/src/control/Control.Menubar.ts" \
    || fail "the Text Enhance action does not ask core for the selection — it is reading something else"
pass "leg 1: the trigger asks core for the real selection"

grep -q "enhanceWith" "$src/android/lib/src/main/java/org/libreoffice/androidlib/CloudTextEnhance.java" \
    || fail "the shell does not call ITextTools.enhanceWith — the enhancer is being reimplemented"
pass "leg 2: the shell calls the shared enhancer over the binder"

# The API key must never be read, stored or logged on this side.
if grep -nE "revealAiKey|apiKey|API_KEY" "$src/android/lib/src/main/java/org/libreoffice/androidlib/CloudTextEnhance.java"; then
    fail "the shell touches the provider credential — only the serving app may"
fi
pass "leg 2: no credential is read on this side"

grep -q "activity.paste(" "$src/android/lib/src/main/java/org/libreoffice/androidlib/CloudTextEnhance.java" \
    || fail "the replacement does not go through core's paste — it will not be one undo step"
pass "leg 3: the replacement is core's own paste"

# Silence is the defect. Every failure branch has to reach the screen.
for branch in "Select the text you want to enhance first" \
              "Text Enhance failed: " \
              "returned nothing"; do
    grep -qF "$branch" "$src/android/lib/src/main/java/org/libreoffice/androidlib/CloudTextEnhance.java" \
        || fail "no visible message for: $branch"
done
pass "every failure branch produces a visible message"

# The owner's document text must not reach logcat: this fleet uploads it.
grep -q "not logged" "$src/android/lib/src/main/java/org/libreoffice/androidlib/LOActivity.java" \
    || fail "the selection payload is still being written to logcat"
pass "the selection is never written to logcat"

printf '\n\033[0;32mALL CHECKS PASSED\033[0m — series applies to %s\n' "$PIN"
