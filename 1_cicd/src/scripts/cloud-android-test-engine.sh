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
#
# ── A FOREIGN FAILURE IS REPORTED, NOT OBEYED ─────────────────────────────
# Quarantine is a decision someone makes about a NAMED tester. It cannot
# describe the failure this repository keeps having, which is a tester nobody
# has looked at yet going red because a DIFFERENT application changed. Four
# outages in, that has never once been a statement about the application whose
# release it blocked.
#
# So the `shell` phase derives, per tester and from the trace of what that
# tester actually executed, whether its verdict was reached with source outside
# this application. If it was, a failure is a loud warning and the APK still
# ships. See the long comment on that block for what "outside" means, why the
# answer comes from execution rather than from reading the tester's text, and
# every direction in which it fails closed.
set -eu

CMD="${1:-}"
APP_DIR="${2:-}"
[ -n "$CMD" ] && [ -n "$APP_DIR" ] || {
    echo "usage: cloud-android-test-engine.sh <shell|unit|lint> <app-dir>" >&2; exit 2; }
[ -d "$APP_DIR" ] || { echo "ERROR no such app dir: $APP_DIR" >&2; exit 2; }

BUILD_JSON="$APP_DIR/build.json"
APP_NAME="$(basename "$APP_DIR")"

# Same walk-up as cloud-android-source-identity.sh and cloud-android-publish-gate.sh,
# and overridable the same way, so all three agree on what "the repository" is.
ROOT="${CLOUD_ANDROID_ROOT:-$(_d="$(cd "$(dirname "$0")" && pwd)"; while [ "$_d" != "/" ] && [ ! -e "$_d/.git" ]; do _d="$(dirname "$_d")"; done; printf '%s' "$_d")}"
SELF_DIR="$(cd "$(dirname "$0")" && pwd)"

# GitHub Actions surfaces ::error:: / ::warning:: in the run summary. Outside
# GHA they are still readable lines, so the script behaves the same locally —
# which is the only reason it could be demonstrated before being pushed.
err()  { echo "::error::$*"; }
warn() { echo "::warning::$*"; }

# jq is how EVERY answer in this script is read out of build.json, including the
# tests.shell.requires list that exists to make missing tooling fatal. Without jq
# every _json call returns empty and the script carries on with its defaults: the
# requires list reads as empty, so the preflight that refuses to run on missing
# tooling passes by having no tooling to check, and the quarantine map reads as
# empty, so a quarantined tester's failure becomes a build failure with no
# explanation. That is a verdict drawn from a tool's absence — the exact shape
# every comment in this file warns about — sitting in the reader itself.
command -v jq >/dev/null 2>&1 || {
    err "jq is not installed — every value this script reads out of build.json would come back empty and it would test the DEFAULTS rather than what the app declares. Refusing to run."
    exit 1
}

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

    # ── THE RELEASE GATE DOES NOT ENFORCE A THIRD PARTY'S LIVE STATE ─────
    # A tester makes two different kinds of claim, and only one of them
    # belongs in the gate that decides whether the owner's phone gets an APK:
    #
    #   about OUR code   — the router resolves a stored id, the picker stores
    #                      an id, an absent value renders the unknown marker.
    #                      Nobody outside this repository can change the
    #                      answer. FATAL, always.
    #   about SOMEONE     — the baked price still matches what OpenRouter
    #   ELSE'S SERVICE     charges today. A stranger editing a number on their
    #                      pricing page changes the answer, with no commit
    #                      here. That is a MONITOR's question, not a gate's.
    #
    # Conflating them cost the owner every APK for a day and a half: run
    # 34462765453 built a correct SuperApp, then refused to publish it because
    # z-ai/glm-5.3-flash had doubled in price overnight — a fact with nothing
    # to say about the launcher, the Inboxes screen or notification handling.
    #
    # So the gate ANNOUNCES ITSELF and each tester decides which of its own
    # assertions that silences. The engine cannot make that call: only the
    # tester knows which of its lines reach off-box. Whole-file quarantine
    # cannot express it either — it would have silenced all 53 assertions
    # about our own code to tolerate the one about theirs.
    #
    # Nothing here is skipped quietly. The monitor that DOES enforce these
    # runs on a schedule, because drift arrives without a commit and a
    # push-triggered check can only catch what someone pushed on top of.
    CLOUD_RELEASE_GATE=1
    export CLOUD_RELEASE_GATE

    # ── NOTHING OUTSIDE THIS APPLICATION'S OWN SOURCE MAY FAIL ITS RELEASE ──
    #
    # The rule above is the third-party half of a bigger one. The other half is
    # the fleet itself, and it has cost the owner four outages of the same
    # shape: a stale Cloud Sheets tester blocked the SuperApp (#242); a live
    # price check vetoed the phone's APK (#247); firestack's aar build broke and
    # took the SuperApp's release with it (#250); and cloud-writer — a NEW
    # application, breaking nothing — turned a SuperApp tester red merely by
    # existing, because that tester counted how many build.json files declare a
    # summary prompt and the count went from two to three.
    #
    # In every case the application being released was fine and something with
    # no say over it decided the owner could not install his own launcher.
    #
    # WHAT COUNTS AS "OWN SOURCE" IS NOT REDEFINED HERE. It is exactly the set
    # cloud-android-source-identity.sh already computes — this app's ship
    # workflow `on: push: paths` list, which the workflow generator manages from
    # the build.json module map, and which the publish gate hashes to decide
    # whether these bytes are new. Deriving a second answer to the same question
    # is how two lists drift apart, so this asks the first one.
    #
    # HOW THE VERDICT IS ATTRIBUTED. Not by reading the tester's text: this
    # repository's own testers name other applications in prose on purpose (the
    # AI-registry files each explain that they began as a copy of another), and
    # a grep cannot tell a sentence from a call — it would silence a tester over
    # a comment. Instead each tester runs under `bash -x` with the trace on its
    # own descriptor, so what is classified is the set of paths that ACTUALLY
    # EXECUTED commands named, fully expanded. Comments never appear there and
    # neither does a branch that did not run.
    #
    # FAILS CLOSED, EVERYWHERE. If the path set cannot be derived, if bash is
    # missing, or if a tester is not a bash script and so cannot be traced, the
    # tester keeps its old fatal-on-failure behaviour and the reason is printed.
    # An empty own-path set would mark every path foreign and quietly downgrade
    # the entire suite — the exact "gate that never fires" this file warns about
    # in three other places — so that case refuses to run at all.
    OWN_PATHS="$(mktemp)"
    TRACE="$(mktemp)"
    trap 'rm -f "$OWN_PATHS" "$TRACE"' EXIT INT TERM

    # BOTH the status and the emptiness. A refusal that still printed something
    # would otherwise look like an answer — which is exactly what
    # cloud-android-source-identity.sh's `paths` used to do, printing the app
    # dir and returning 0 because its `exit 3` died inside a pipeline subshell.
    # Its stderr is left alone so the refusal's own wording reaches the log;
    # folding it into the file would turn an error message into a "path".
    own_rc=0
    sh "$SELF_DIR/cloud-android-source-identity.sh" paths "$APP_DIR" >"$OWN_PATHS" || own_rc=$?
    if [ "$own_rc" -ne 0 ] || [ ! -s "$OWN_PATHS" ]; then
        err "[$APP_NAME] cannot derive this application's own source paths (cloud-android-source-identity.sh paths '$APP_DIR' produced nothing). With an empty set EVERY path reads as foreign and every failing tester would be downgraded to a warning — a suite that cannot go red. Refusing to run."
        exit 1
    fi
    can_trace=yes
    command -v bash >/dev/null 2>&1 || {
        can_trace=no
        warn "[$APP_NAME] bash is not installed, so no tester's file reach can be observed — every failure stays fatal, including one caused by another application's source."
    }

    # Every repo-relative path an executed command named, minus the ones under
    # this application's own source, trimmed to two components so the OWNER of
    # the foreign path is what gets printed rather than a wall of file names.
    # index() is a fixed-string search: the repository root is a path, and
    # feeding it to a regex would let a character in the checkout directory's
    # name change the answer.
    _foreign_reach() {  # _foreign_reach <trace-file>
        awk -v root="$ROOT/" '
            NR == FNR { own[FNR] = $0; nown = FNR; next }
            {
                s = $0
                while ((i = index(s, root)) > 0) {
                    s = substr(s, i + length(root))
                    n = match(s, /[^A-Za-z0-9_.\/-]/)
                    p = (n > 0 ? substr(s, 1, n - 1) : s)
                    if (p == "") continue
                    hit = 0
                    for (k = 1; k <= nown; k++)
                        if (p == own[k] || index(p, own[k] "/") == 1) { hit = 1; break }
                    if (hit) continue
                    m = index(p, "/")
                    if (m > 0) {
                        rest = substr(p, m + 1); m2 = index(rest, "/")
                        if (m2 > 0) p = substr(p, 1, m + m2 - 1)
                    }
                    seen[p] = 1
                }
            }
            END { for (p in seen) print p }
        ' "$OWN_PATHS" "$1" | LC_ALL=C sort | tr '\n' ' '
    }

    # A tester that hands the repository ROOT ITSELF to another program has a
    # reach this engine cannot bound — whatever that program opens is invisible
    # to the trace. REPORTED, NEVER USED TO DOWNGRADE: turning an unbounded
    # reach into a non-fatal verdict would silence a tester on the strength of
    # something not observed, which is the same mistake as trusting a grep.
    _unbounded_reach() {  # _unbounded_reach <trace-file>
        awk -v root="$ROOT" '
            { n = split($0, a, " "); for (i = 2; i <= n; i++) if (a[i] == root) { print "yes"; exit } }
        ' "$1"
    }

    total=0; failed=0; quarantined=0; revived=0; flaky=0; foreign=0; unbounded=0
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

        # Traced ONLY when the tester's own shebang is already bash, so the
        # interpreter it runs under does not change — `bash -x FILE` and running
        # FILE through a bash shebang are the same execution. Anything else runs
        # exactly as before and is classified as unobservable, which keeps it
        # fatal. BASH_XTRACEFD keeps the trace off stderr, so a tester's own
        # output is unchanged and nothing downstream sees the tracing.
        : >"$TRACE"
        traced=no
        if [ "$can_trace" = yes ]; then
            case "$(head -n 1 "$t" 2>/dev/null)" in
                '#!'*bash*) traced=yes ;;
            esac
        fi
        if [ "$traced" = yes ]; then
            if BASH_XTRACEFD=9 bash -x "$t" 9>"$TRACE"; then rc=0; else rc=$?; fi
        else
            if "$t"; then rc=0; else rc=$?; fi
        fi

        # Spelled as an `if`, not `[ … ] && reach=…`: under `set -eu` an AND-OR
        # list that ends false is itself a failing statement and would exit the
        # script on the first untraced tester. This file's own sibling engine
        # carries that scar in a comment; no reason to earn it twice.
        reach=""
        if [ "$traced" = yes ]; then reach="$(_foreign_reach "$TRACE")"; fi
        if [ "$traced" = yes ] && [ -n "$(_unbounded_reach "$TRACE")" ]; then
            warn "$base hands the repository root to another program — whatever that program reads cannot be seen from here, so this tester's reach is UNBOUNDED and its verdict is treated as its own regardless."
            unbounded=$((unbounded + 1))
        fi

        # ── unstable: the verdict itself is not trustworthy ──────────────
        # Distinct from quarantine ON PURPOSE. Quarantine says "this FAILS,
        # here is why" and turns fatal the moment it passes. That cannot
        # describe a tester which passes on one machine and fails on another
        # over byte-identical input: quarantining it would go fatal wherever it
        # passes, and not quarantining it goes fatal wherever it fails. Such a
        # tester currently proves nothing either way, and saying so out loud on
        # every run is more honest than picking whichever colour is convenient.
        # Both outcomes are logged; neither is fatal; the gap is counted.
        unstable="$(_json ".tests.shell.unstable[\"$base\"]")"
        if [ -n "$unstable" ]; then
            warn "$base is UNSTABLE (exit $rc — proves nothing either way): $unstable"
            flaky=$((flaky + 1))
            continue
        fi

        if [ -n "$reason" ]; then
            if [ "$rc" -eq 0 ]; then
                # The entry outlived its reason. Fatal ON PURPOSE — EXCEPT when
                # this tester reads another application's source, because then
                # the revival is not necessarily this application's doing: a
                # stranger's commit can flip a cross-application assertion to
                # passing, and killing the release over a stale bookkeeping
                # entry is the very thing the rule above forbids. Still loud,
                # still asks for the entry to go.
                if [ -n "$reach" ]; then
                    warn "$base is QUARANTINED but now PASSES, and it reads source outside $APP_NAME ($reach) — so the revival may be a stranger's commit, not this application's. Delete its entry from build.json::tests.shell.quarantine. Reason on file: $reason"
                    foreign=$((foreign + 1))
                else
                    err "$base is QUARANTINED but now PASSES — delete its entry from build.json::tests.shell.quarantine. Reason on file: $reason"
                    revived=$((revived + 1))
                fi
            else
                warn "$base FAILED but is quarantined (exit $rc): $reason"
                quarantined=$((quarantined + 1))
            fi
            continue
        fi

        if [ "$rc" -ne 0 ] && [ -n "$reach" ]; then
            # FOREIGN. The tester ran, it failed, and its verdict was reached
            # with source this application does not own. It keeps running and it
            # keeps reporting — nothing is skipped, ignored or deleted — but it
            # does not decide whether the owner's phone gets an APK.
            #
            # This downgrades the WHOLE tester, including any assertion in it
            # that IS about this application. That is a real cost and it is the
            # honest reading of the rule: once a stranger's commit can change
            # what this file returns, this file's exit status is no longer a
            # statement about this application alone. The named path is what to
            # split out to get the fatal half back.
            warn "$base FAILED (exit $rc) but is FOREIGN to $APP_NAME — it reached: $reach. Not fatal: nothing outside an application's own source may fail that application's release. The failure above is real and still needs fixing, by whoever owns that path."
            foreign=$((foreign + 1))
            continue
        fi

        if [ "$rc" -ne 0 ] && [ "$traced" = no ]; then
            err "$base FAILED (exit $rc) — its file reach could not be observed (not a bash tester, or bash is absent), so it is treated as $APP_NAME's own and stays fatal."
            failed=$((failed + 1))
            continue
        fi

        [ "$rc" -eq 0 ] || { err "$base FAILED (exit $rc)"; failed=$((failed + 1)); }
    done

    echo "── shell testers [$APP_NAME]: $total ran, $failed failed, $quarantined quarantined, $flaky unstable, $revived revived, $foreign foreign ──"

    # ONE LINE THAT SAYS WHICH KIND OF RUN THIS IS, because the two outcomes
    # this whole mechanism creates have to be told apart at a glance:
    #
    #   FOREIGN-DOWNGRADED — green, an APK ships, and something outside this
    #                        application is broken and named.
    #   this application   — red, at this step, and nothing publishes.
    #
    # Written to the job summary as well as the log when GitHub gives us one,
    # so it is on the run's front page rather than 400 lines into a step.
    if [ "$foreign" -gt 0 ]; then
        _banner="FOREIGN-DOWNGRADED [$APP_NAME]: $foreign tester(s) failed on source this application does not own. The APK still ships. Nothing here says $APP_NAME is broken — see the ::warning:: lines for which path and whose it is."
        echo "$_banner"
        [ -z "${GITHUB_STEP_SUMMARY:-}" ] || echo "$_banner" >>"$GITHUB_STEP_SUMMARY"
        _uncovered "$foreign tester(s) reached outside $APP_NAME and were NOT allowed to fail this release — their findings are real and belong to whoever owns the named path"
    fi
    [ "$unbounded" -eq 0 ] || _uncovered "$unbounded tester(s) hand the repository root to another program — their reach cannot be observed, so they were judged as this application's own"
    [ "$quarantined" -eq 0 ] || _uncovered "$quarantined tester(s) allowed to fail — see ::warning:: lines above"
    [ "$flaky" -eq 0 ] || _uncovered "$flaky tester(s) marked UNSTABLE — their result is not evidence in either direction"
    # Standing, unconditional, and counted as a gap even on a fully green run:
    # what the gate declines to check has to stay as visible as what it checks,
    # or "we never enforced this" decays into "this passed".
    _uncovered "assertions against a live third-party service were NOT enforced here (CLOUD_RELEASE_GATE) — the scheduled 'Test → AI model registry' workflow is what enforces them; a red run there means a baked price is wrong even though this one is green"
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
