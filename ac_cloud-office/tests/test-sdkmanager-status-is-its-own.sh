#!/usr/bin/env bash
# THE SDK INSTALL STEP MUST FAIL WHEN SDKMANAGER FAILS, AND ONLY THEN.
#
# Run 35034695984 died twelve seconds into "Build + sign with the shared
# constellation key" with one line of evidence:
#
#     yes: standard output: Broken pipe
#
# and nothing else. Every package in build.json::build.sdk_packages was already
# present on the runner image, so sdkmanager finished at once while `yes` was
# still writing; `yes` took EPIPE, exited 1, and `set -o pipefail` handed that
# status to the step. A perfectly good install failed the build. The mirror of
# the same defect is worse and silent: sdkmanager's own exit status was never
# what the pipeline reported, so a genuine "Failed to find package" could have
# been masked by any producer that happened to exit 0.
#
# FEEDING A FIXED NUMBER OF LINES DOES NOT FIX THIS, AND THAT IS NOT A GUESS.
# The first fix here was `printf 'y\n%.0s' "${packages[@]}" | sdkmanager`, on the
# reasoning that six bytes always fit the pipe buffer. They do — but only if the
# reader has not already closed the read end, and a stub that exits at once often
# has. That version passed, then FAILED on its second run under the test engine.
# The shape is the bug: any second process in a pipeline can hand `pipefail` a
# status that is not sdkmanager's. So the acceptances go to a FILE and sdkmanager
# is not in a pipeline at all. T2 below is what holds that line.
#
# So this tester does not read the engine and agree with it. It LIFTS THE REAL
# INVOCATION OUT OF THE VENDORED ENGINE — the exact copy CI runs — and runs it
# against stub sdkmanagers whose exit status is known, with the real package list
# out of build.json. If somebody puts a producer back, or drops the redirection
# that keeps sdkmanager's output, the lifted lines change and these go red.
#
# T0 IS A CONTROL AND IT IS NOT OPTIONAL. It reproduces the original defect in
# this shell. If `yes | <stub that exits 0>` does NOT fail here, this machine
# cannot observe the bug at all, and every "ok" below would be meaningless — so
# the tester exits 2 (unknown) rather than reporting a green it did not earn.
#
# Usage: ./test-sdkmanager-status-is-its-own.sh        (offline, no network)
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
BJ="$APP/build.json"
# The VENDORED engine, because build.json::vendored_engine says CI runs this
# copy. Asserting the pristine 1_cicd/src/ copy would prove nothing about the
# file that actually installs the SDK.
ENGINE="$APP/build.sh"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo -e "  \033[0;32mok\033[0m: $1"; }
bad() { FAIL=$((FAIL+1)); echo -e "  \033[0;31mFAIL\033[0m: $1"; }
die() { echo "ERROR: $1" >&2; exit 2; }

for t in jq timeout; do
    command -v "$t" >/dev/null 2>&1 \
        || die "$t is not on PATH — refusing to report a verdict this run never computed"
done
[ -f "$BJ" ] || die "$BJ missing"
[ -f "$ENGINE" ] || die "$ENGINE missing — build.json::vendored_engine says CI runs it"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/cloud-office-sdkstatus.XXXXXX")" || die "cannot mktemp"
trap 'rm -rf "$TMP"' EXIT

# Three stubs standing in for sdkmanager. The first is the one that mattered:
# it exits at once WITHOUT READING STDIN, exactly as sdkmanager does when every
# requested package is already installed.
printf '#!/bin/sh\nexit 0\n'                  > "$TMP/exits-0-unread"
printf '#!/bin/sh\necho "Warning: Failed to find package" \nexit 1\n' > "$TMP/exits-1"
printf '#!/bin/sh\ncat >/dev/null\nexit 0\n'  > "$TMP/drains-then-exits-0"
chmod +x "$TMP/exits-0-unread" "$TMP/exits-1" "$TMP/drains-then-exits-0"

mapfile -t packages < <(jq -r '.build.sdk_packages[]' "$BJ")
[ "${#packages[@]}" -gt 0 ] \
    || die "build.json::build.sdk_packages is empty — there is no pipeline to test"

echo "== T0 (control): the original defect is reproducible in this shell =="
# `yes`'s own stderr is discarded: it prints the very "Broken pipe" line this
# tester exists to abolish, and a green run that still logs it sends the next
# reader hunting the bug that was already fixed.
( set -o pipefail; yes 2>/dev/null | "$TMP/exits-0-unread" >/dev/null 2>&1 )
if [ "$?" -eq 0 ]; then
    die "\`yes | (exit 0)\` returned 0 under pipefail — this shell cannot observe the failure of run 35034695984, so no assertion below would mean anything"
fi
ok "\`yes | (exit 0)\` fails under pipefail, as it did on the runner"

echo "== T1: the engine no longer feeds sdkmanager from an endless producer =="
# The comment filter runs on `grep -n` output, so it has to allow for the line
# number `grep -n` prefixes — matching '^[[:space:]]*#' against "216:    # ..."
# never fires, and the engine's own comment ABOUT the old `yes | sdkmanager`
# reddened this on its first run.
YES_HITS="$(grep -nE '(^|[^[:alnum:]_])yes[[:space:]]*\|' "$ENGINE" | grep -vE '^[0-9]+:[[:space:]]*#')"
if [ -n "$YES_HITS" ]; then
    bad "$ENGINE still pipes \`yes\` into something:"
    printf '%s\n' "$YES_HITS" >&2
else
    ok "no \`yes |\` pipeline in the vendored engine outside its comments"
fi

echo "== T2: sdkmanager is not in a pipeline, and its invocation can be lifted =="
# THE ROOT-CAUSE ASSERTION. Not "which producer" — whether there is one at all.
# Any second process in the pipeline can hand `pipefail` a status that is not
# sdkmanager's, whether it writes forever or once.
# Lifted BROADLY — anything between `if ! ` and `; then` that names sdkmanager —
# so that a pipeline reshape is reported as the failure it is, instead of as
# "cannot find the invocation", which reads like a broken tester rather than a
# reintroduced bug.
INVOKE="$(sed -n 's/^[[:space:]]*if ! \(.*sdkmanager.*\); then$/\1/p' "$ENGINE")"
[ -n "$INVOKE" ] \
    || die "cannot find the sdkmanager invocation in $ENGINE — the step was reshaped and this tester no longer asserts what runs"
[ "$(printf '%s\n' "$INVOKE" | wc -l)" -eq 1 ] \
    || die "more than one sdkmanager invocation in $ENGINE — ambiguous, refusing a verdict"
case "$INVOKE" in
    *'|'*)
        bad "sdkmanager is back in a pipeline, so the step's status is still not its own: $INVOKE"
        echo
        echo "passed: $PASS   failed: $FAIL"
        # Everything below reads the acceptances FILE, which a pipeline does not
        # write. Reporting those as failures too would bury the one that matters.
        exit 1 ;;
esac
ACCEPTS="$(sed -n "s/^[[:space:]]*\(printf 'y.*> \"\$sdk_accepts\"\)$/\\1/p" "$ENGINE")"
[ -n "$ACCEPTS" ] \
    || die "sdkmanager is not in a pipeline, but the acceptances file is not written either — refusing to guess how licences are answered"
ok "no pipeline: $INVOKE"

echo "== T3: sdkmanager's output is kept, not sent to /dev/null =="
# "Failed to find package" goes to sdkmanager's STDOUT. Discarding it is what
# made a real failure and a spurious one look identical in the job log.
case "$INVOKE" in
    *'>/dev/null'*|*'> /dev/null'*)
        bad "the invocation discards sdkmanager's output — a real failure would say nothing" ;;
    *'>"$sdk_log"'*)
        ok "sdkmanager's stdout and stderr are captured to \$sdk_log" ;;
    *)  bad "the invocation does not redirect to \$sdk_log: $INVOKE" ;;
esac

# Both lifted lines, wrapped in the smallest script that gives them the names
# they read, under the engine's own pipefail. Under `timeout`, because a bad
# shape here can HANG rather than fail — an unbounded feed into a reader that
# drains it never ends, and a suite that hangs is a suite that gets turned off.
# A timeout surfaces as exit 124, which is red, which is the point.
printf '%s\n' \
    '#!/usr/bin/env bash' \
    'set -uo pipefail' \
    'sdkmanager="$1"; sdk_log="$2"; BJ="$3"; sdk_accepts="$4"' \
    'mapfile -t packages < <(jq -r ".build.sdk_packages[]" "$BJ")' \
    "$ACCEPTS" \
    "$INVOKE" > "$TMP/run-lifted.sh"
bash -n "$TMP/run-lifted.sh" \
    || die "the lifted lines are not valid bash: $ACCEPTS / $INVOKE"

run_lifted() {  # run_lifted <stub> → the invocation's exit status
    timeout 30 bash "$TMP/run-lifted.sh" \
        "$1" "$TMP/sdkmanager.log" "$BJ" "$TMP/sdkmanager.accepts"
}

echo "== T4: a successful sdkmanager that never reads stdin gives status 0 =="
# RUN MANY TIMES, because the defect this replaced was a RACE and a single green
# run is what let it through: the fixed-feed version passed here once and failed
# on the next run. A shape that cannot race passes every time; one that can will
# lose at least once in this many.
RUNS=200
losses=0
for _ in $(seq "$RUNS"); do
    run_lifted "$TMP/exits-0-unread" || losses=$((losses + 1))
done
if [ "$losses" -eq 0 ]; then
    ok "status 0 in all $RUNS runs — the case that failed run 35034695984 cannot race"
else
    bad "$losses of $RUNS runs did not return 0 — something other than sdkmanager can still set the status"
fi

echo "== T5: a failing sdkmanager is NOT masked =="
run_lifted "$TMP/exits-1"; s=$?
if [ "$s" -ne 0 ]; then
    ok "status $s — sdkmanager's own failure reaches the step"
else
    bad "status 0 — a failing sdkmanager was reported as a success, which is the worse half of the defect"
fi
if grep -q 'Failed to find package' "$TMP/sdkmanager.log" 2>/dev/null; then
    ok "sdkmanager's message survives in \$sdk_log for the engine to print"
else
    bad "\$sdk_log does not hold sdkmanager's message — the engine would die without saying why"
fi

echo "== T6: the feed terminates even when sdkmanager drains all of it =="
# An unbounded feed into a draining reader never ends. This must return, and
# the only way it can is a feed of finite length.
run_lifted "$TMP/drains-then-exits-0"; s=$?
if [ "$s" -eq 0 ]; then
    ok "status 0 against a reader that consumes every byte — the feed is finite"
else
    bad "status $s against a draining reader"
fi

echo
echo "passed: $PASS   failed: $FAIL"
[ "$FAIL" -eq 0 ] || exit 1
