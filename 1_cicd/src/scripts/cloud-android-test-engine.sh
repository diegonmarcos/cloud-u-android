#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-test-engine — run an app's tests, and MEAN it      ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# Every ship workflow in this repository built an APK, published it, and cut a
# release without executing a single test. Both test systems were dead in CI:
# the shell testers under <app>/test/*.sh were only ever run by hand by agents,
# and the JVM unit tests under <app>/*/src/test/ were never named by any gradle
# invocation — the workflows call assembleRelease and nothing else. So a green
# pipeline asserted only that the code COMPILED, while reports cited the
# assertions as evidence they had passed. This script is what makes the green
# mean what the owner already believed it meant.
#
#   shell <app-dir>   run <app-dir>/test/*.sh   — static assertions, no build
#   unit  <app-dir>   run the JVM unit test task via the app's own gradlew
#   lint  <app-dir>   structural guard against assertions that CANNOT fail
#
# Everything is data-driven from <app-dir>/build.json::tests — never a list
# hardcoded here (FIRE RULE #6). An app with no `tests` block is SKIPPED
# LOUDLY, printing what it did not cover, because a test run that silently
# covers nothing is the same lie in a new place.
#
# ── ANDROID UNIT TESTS DO NOT NEED AN EMULATOR ────────────────────────────
# The `unit` phase runs the JVM test source set (src/test/), which executes on
# the runner's own JVM. Only src/androidTest/ needs a device, and that suite
# has its own workflow (test-cloud-nav.yml). If a comment anywhere claims
# these need hardware, it is wrong and it is why they were skipped.
#
# ── THE QUARANTINE IS SELF-EXPIRING ───────────────────────────────────────
# build.json::tests.shell.quarantine maps a tester's filename to the REASON it
# is allowed to fail. A quarantined tester that fails is a loud warning, not a
# fatal. But a quarantined tester that PASSES is FATAL: the entry has outlived
# its reason and must be deleted. That is what stops an allowed-failure list
# from quietly becoming a blanket tolerance, which is the failure mode a
# `continue-on-error` has and this does not.
set -eu

CMD="${1:-}"
APP_DIR="${2:-}"
[ -n "$CMD" ] && [ -n "$APP_DIR" ] || {
    echo "usage: cloud-android-test-engine.sh <shell|unit|lint> <app-dir>" >&2; exit 2; }
[ -d "$APP_DIR" ] || { echo "ERROR no such app dir: $APP_DIR" >&2; exit 2; }

BUILD_JSON="$APP_DIR/build.json"
APP_NAME="$(basename "$APP_DIR")"

# GitHub Actions surfaces ::error:: / ::warning:: in the run summary. Outside
# GHA they are still readable lines, so the script behaves the same locally —
# which is the only reason it could be demonstrated before being pushed.
err()  { echo "::error::$*"; }
warn() { echo "::warning::$*"; }

_json() {  # _json <jq-filter> — empty string when absent or no build.json
    [ -f "$BUILD_JSON" ] || { printf ''; return 0; }
    jq -r "$1 // empty" "$BUILD_JSON" 2>/dev/null || printf ''
}

# ── coverage ledger ───────────────────────────────────────────────────────
# Printed by every phase, including the phases that ran nothing. A partial run
# must never be readable as a full one.
_uncovered() { echo "COVERAGE-GAP [$APP_NAME] $*"; }

case "$CMD" in

# ── shell: the static testers ─────────────────────────────────────────────
shell)
    dir="$(_json '.tests.shell.dir')"
    [ -n "$dir" ] || dir="test"
    tdir="$APP_DIR/$dir"

    if [ ! -d "$tdir" ]; then
        _uncovered "no $dir/ directory — zero shell testers ran"
        exit 0
    fi

    # ── tool preflight ───────────────────────────────────────────────────
    # A tester that shells out to a tool which is not installed does not fail
    # — it produces a VERDICT FROM THE TOOL'S ABSENCE. In this repo
    # test-no-conflict-markers.sh captures `HITS="$(rg ...)"` and passes when
    # HITS is empty, so with no rg on PATH it reports "no conflict markers"
    # over a tree that has them; test-comms-tile-retarget.sh's absent() helper
    # does the same in reverse, reporting every "no dead reference survives"
    # check as PASSING. Both suppress stderr, so "command not found" is
    # invisible. Missing tooling must be LOUD AND FATAL, never a green tick.
    missing=""
    for tool in $(_json '.tests.shell.requires[]'); do
        command -v "$tool" >/dev/null 2>&1 || missing="$missing $tool"
    done
    if [ -n "$missing" ]; then
        err "[$APP_NAME] required test tooling missing:$missing — assertions built on it would report a verdict from the tool's ABSENCE, not from the code. Refusing to run."
        exit 1
    fi

    total=0; failed=0; quarantined=0; revived=0
    for t in "$tdir"/test-*.sh; do
        [ -e "$t" ] || continue
        base="$(basename "$t")"
        total=$((total + 1))
        reason="$(_json ".tests.shell.quarantine[\"$base\"]")"

        # A tester is a program: its exit status is the verdict. Nothing here
        # swallows it — no `|| true`, no `continue-on-error`.
        #
        # Run it through ITS OWN shebang, never a hardcoded `sh`. Every tester
        # here is `#!/usr/bin/env bash` and uses bash-only forms (<<< herestrings,
        # arrays); forcing `sh` on a system where /bin/sh is dash turns them into
        # "Syntax error: redirection unexpected" and exit 2 — a failure that
        # looks like a real verdict and is not one.
        [ -x "$t" ] || chmod +x "$t" 2>/dev/null || true
        if "$t"; then rc=0; else rc=$?; fi

        if [ -n "$reason" ]; then
            if [ "$rc" -eq 0 ]; then
                # The entry outlived its reason. Fatal ON PURPOSE.
                err "$base is QUARANTINED but now PASSES — delete its entry from build.json::tests.shell.quarantine. Reason on file: $reason"
                revived=$((revived + 1))
            else
                warn "$base FAILED but is quarantined (exit $rc): $reason"
                quarantined=$((quarantined + 1))
            fi
            continue
        fi

        [ "$rc" -eq 0 ] || { err "$base FAILED (exit $rc)"; failed=$((failed + 1)); }
    done

    echo "── shell testers [$APP_NAME]: $total ran, $failed failed, $quarantined quarantined, $revived revived ──"
    [ "$quarantined" -eq 0 ] || _uncovered "$quarantined tester(s) allowed to fail — see ::warning:: lines above"
    [ "$failed" -eq 0 ] && [ "$revived" -eq 0 ]
    ;;

# ── unit: the JVM test source set ─────────────────────────────────────────
unit)
    task="$(_json '.tests.unit.task')"
    enabled="$(_json '.tests.unit.enabled')"

    if [ -z "$task" ] || [ "$enabled" = "false" ]; then
        n=$(find "$APP_DIR" -path '*/src/test/*' \( -name '*.kt' -o -name '*.java' \) 2>/dev/null | wc -l)
        _uncovered "JVM unit tests NOT run ($n test source file(s) present); build.json::tests.unit.task is unset or disabled"
        exit 0
    fi

    [ -x "$APP_DIR/gradlew" ] || chmod +x "$APP_DIR/gradlew" 2>/dev/null || true
    [ -f "$APP_DIR/gradlew" ] || { err "$APP_NAME declares tests.unit.task=$task but has no gradlew"; exit 1; }

    echo "── unit tests [$APP_NAME]: ./gradlew --no-daemon $task ──"
    # No `|| true`. Gradle's exit status IS the gate; a failing test returns 1
    # and that 1 is what this script returns.
    ( cd "$APP_DIR" && ./gradlew --no-daemon "$task" )
    ;;

# ── lint: assertions that cannot fail ─────────────────────────────────────
# Two vacuous-assertion shapes have already reached main in this repo: one
# compared an expression to itself, and one searched a context window so wide
# it ran past the code it meant to check and matched something else. Neither
# needs mutation testing to catch — both are visible in the text of the
# assertion. This is deliberately a structural grep and nothing more.
lint)
    dir="$(_json '.tests.shell.dir')"
    [ -n "$dir" ] || dir="test"
    tdir="$APP_DIR/$dir"
    [ -d "$tdir" ] || { _uncovered "no $dir/ to lint"; exit 0; }

    # A context window this wide stops being "near the match" and starts being
    # "somewhere in the file", which is what let a passing grep prove nothing.
    WINDOW_MAX=40
    bad=0; soft=0
    for t in "$tdir"/test-*.sh; do
        [ -e "$t" ] || continue
        base="$(basename "$t")"

        # (1) self-comparison: [ "$x" = "$x" ], [ "$a" == "$a" ]
        if grep -nE '\[+[[:space:]]+"?\$\{?([A-Za-z_][A-Za-z_0-9]*)\}?"?[[:space:]]+==?[[:space:]]+"?\$\{?\1\}?"?[[:space:]]+\]+' "$t"; then
            err "$base compares an expression to itself — that assertion cannot fail"
            bad=$((bad + 1))
        fi

        # (2) over-wide grep context window
        if grep -nE -- "-[ABC][[:space:]]?[0-9]{2,}" "$t" | awk -v m="$WINDOW_MAX" -F'-[ABC] ?' '{n=$2+0; if (n>m) print}' | grep -q .; then
            grep -nE -- "-[ABC][[:space:]]?[0-9]{2,}" "$t" \
              | awk -v m="$WINDOW_MAX" -F'-[ABC] ?' '{n=$2+0; if (n>m) print "    " $0}'
            err "$base greps a context window wider than $WINDOW_MAX lines — a match that wide does not prove proximity"
            bad=$((bad + 1))
        fi
        # (3) a verdict drawn from a command whose stderr is thrown away. A
        # missing file or missing tool then exits non-zero with the reason
        # hidden, and the &&/|| turns that into a clean pass or a clean fail
        # indistinguishable from a real one — for the `hasnt`/`absent` polarity
        # it is a false PASS. This is the shape that let
        # test-no-conflict-markers.sh certify a tree with a planted conflict
        # marker in it.
        #
        # REPORTED, NOT FATAL, and deliberately so: `grep -q … 2>/dev/null && ok
        # || bad` is the standard has()/hasnt() helper idiom in nearly every
        # tester here, so failing on it would block the whole suite over a
        # cleanup the owner has to schedule. The MISSING-TOOL half of this risk
        # is already fatal, at the tests.shell.requires preflight above, which
        # is where it can be enforced without a repo-wide rewrite.
        if grep -nE '2>/dev/null.*(&&|\|\|)[[:space:]]*(ok|bad|pass|fail)\b' "$t" >/dev/null; then
            warn "$base decides a verdict from a command with stderr suppressed — a missing file or tool becomes a test result"
            soft=$((soft + 1))
        fi
    done

    echo "── vacuous-assertion lint [$APP_NAME]: $bad fatal, $soft advisory ──"
    [ "$soft" -eq 0 ] || _uncovered "$soft tester(s) draw a verdict through suppressed stderr — see ::warning:: lines"
    [ "$bad" -eq 0 ]
    ;;

*)  echo "ERROR unknown command: $CMD" >&2; exit 2 ;;
esac
