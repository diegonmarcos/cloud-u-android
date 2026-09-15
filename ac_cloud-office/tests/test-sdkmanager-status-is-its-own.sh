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
# So this tester does not read the engine and agree with it. It LIFTS THE REAL
# PIPELINE OUT OF THE VENDORED ENGINE — the exact copy CI runs — and runs it
# under `set -o pipefail` against stub sdkmanagers whose exit status is known,
# with the real package list out of build.json. If somebody puts an endless
# producer back, or drops the redirection that keeps sdkmanager's output, the
# lifted line changes and these assertions go red.
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
( set -o pipefail; yes | "$TMP/exits-0-unread" >/dev/null 2>&1 )
if [ "$?" -eq 0 ]; then
    die "\`yes | (exit 0)\` returned 0 under pipefail — this shell cannot observe the failure of run 35034695984, so no assertion below would mean anything"
fi
ok "\`yes | (exit 0)\` fails under pipefail, as it did on the runner"

echo "== T1: the engine no longer feeds sdkmanager from an endless producer =="
if grep -nE '(^|[^[:alnum:]_])yes[[:space:]]*\|' "$ENGINE" | grep -v '^[[:space:]]*#' >/dev/null; then
    bad "$ENGINE still pipes \`yes\` into something:"
    grep -nE '(^|[^[:alnum:]_])yes[[:space:]]*\|' "$ENGINE" >&2
else
    ok "no \`yes |\` pipeline anywhere in the vendored engine"
fi

echo "== T2: the sdkmanager pipeline can be lifted out of the engine =="
# Everything between `if ! ` and `; then`. Fails closed: a reshaped line yields
# an empty match and the tester refuses to guess what shipped.
PIPE="$(sed -n 's/^[[:space:]]*if ! \(printf .*|.*sdkmanager.*\); then$/\1/p' "$ENGINE")"
[ -n "$PIPE" ] \
    || die "cannot find the sdkmanager pipeline in $ENGINE — it was reshaped and this tester no longer asserts what runs"
[ "$(printf '%s\n' "$PIPE" | wc -l)" -eq 1 ] \
    || die "more than one sdkmanager pipeline in $ENGINE — ambiguous, refusing a verdict"
ok "lifted: $PIPE"

echo "== T3: sdkmanager's output is kept, not sent to /dev/null =="
# "Failed to find package" goes to sdkmanager's STDOUT. Discarding it is what
# made a real failure and a spurious one look identical in the job log.
case "$PIPE" in
    *'>/dev/null'*|*'> /dev/null'*)
        bad "the pipeline discards sdkmanager's output — a real failure would say nothing" ;;
    *'>"$sdk_log"'*)
        ok "sdkmanager's stdout and stderr are captured to \$sdk_log" ;;
    *)  bad "the pipeline does not redirect to \$sdk_log: $PIPE" ;;
esac

# The lifted line, wrapped in the smallest script that gives it the three names
# it reads — $sdkmanager, $sdk_log and $packages — under the engine's own
# pipefail. Under `timeout`, because the defect this tester guards against has a
# shape that HANGS rather than fails: an unbounded feed into a reader that
# drains it never ends, and a suite that hangs is a suite that gets turned off.
# A timeout surfaces as exit 124, which is red, which is the point.
printf '%s\n' \
    '#!/usr/bin/env bash' \
    'set -uo pipefail' \
    'sdkmanager="$1"; sdk_log="$2"; BJ="$3"' \
    'mapfile -t packages < <(jq -r ".build.sdk_packages[]" "$BJ")' \
    "$PIPE" > "$TMP/run-lifted.sh"
bash -n "$TMP/run-lifted.sh" || die "the lifted pipeline is not valid bash: $PIPE"

run_lifted() {  # run_lifted <stub> → the pipeline's exit status
    timeout 30 bash "$TMP/run-lifted.sh" "$1" "$TMP/sdkmanager.log" "$BJ"
}

echo "== T4: a successful sdkmanager that never reads stdin gives status 0 =="
run_lifted "$TMP/exits-0-unread"; s=$?
if [ "$s" -eq 0 ]; then
    ok "status 0 — the case that failed run 35034695984 now passes"
else
    bad "status $s — the producer is still outliving sdkmanager and stealing the status"
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
