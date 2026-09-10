#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ foreign-gate.test — nothing outside an application's own source   ║
# ║ may fail that application's release, and its own source still can ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. cloud-android-test-engine.sh now declines to fail a release
# over a tester whose verdict was reached with source the application does not
# own. That is a mechanism for NOT failing, which is the most dangerous kind of
# change this repository makes: a gate that never fires looks exactly like a
# gate that works, and this tree has shipped an assertion comparing an
# expression to itself, a pipeline's status read through `tail`, and four
# testers that passed only because ripgrep was missing.
#
# So every direction is exercised against a fixture this file builds, and the
# two that matter are exercised in BOTH polarities:
#
#   1. own-source failure          → FATAL. The disease is a stale APK; a rule
#                                    that stops everything failing is worse.
#   2. foreign failure             → NOT fatal, and LOUD: warning, banner, and
#                                    a coverage-gap line naming the path.
#   3. foreign tester that PASSES  → unchanged. Reach alone downgrades nothing.
#   4. no derivable own-path set   → REFUSES TO RUN. With an empty set every
#                                    path reads as foreign and the whole suite
#                                    silently stops being able to go red. This
#                                    is the catastrophic failure mode.
#   5. untraceable tester          → FATAL even when it does read foreign
#                                    source. Fails closed on a missing tool.
#   6. quarantined + foreign       → a stranger's commit reviving a cross-app
#                                    assertion must not kill the release.
#   7. the two run KINDS           → a foreign-downgraded green and a broken
#                                    application red must not read alike.
#
# bash, sh, awk, jq. No gradle, no gh, no network.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
ENGINE="$ROOT/1_cicd/src/scripts/cloud-android-test-engine.sh"

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

[ -f "$ENGINE" ] || { echo "  FAIL: no engine at $ENGINE"; exit 1; }

FIX="$(mktemp -d)"
trap 'rm -rf "$FIX"' EXIT

# ── the fixture repository ────────────────────────────────────────────────
# A real enough tree for cloud-android-source-identity.sh to answer `paths`:
# a .git marker, a ship workflow declaring WORK_DIR, and the app it names.
# CLOUD_ANDROID_ROOT points every engine in the chain at it.
mkdir -p "$FIX/1_cicd/src/cicd" "$FIX/xx_app/test" "$FIX/yy_stranger"
: >"$FIX/.git"
cat >"$FIX/1_cicd/src/cicd/ship-xx-app.yml" <<'YML'
on:
  push:
    paths:
      - "xx_app/**"
env:
  WORK_DIR: xx_app
YML
echo 'the stranger owns this line' >"$FIX/yy_stranger/thing.txt"
echo 'this application owns this line' >"$FIX/xx_app/mine.txt"

_build_json() {  # _build_json <extra-tests-shell-json>
    cat >"$FIX/xx_app/build.json" <<JSON
{ "tests": { "shell": { "dir": "test" ${1:-} } } }
JSON
}
_build_json

# Every fixture tester derives its paths from \$0 exactly as the real ones do.
_tester() {  # _tester <name> <shebang> <body>
    cat >"$FIX/xx_app/test/$1" <<TESTER
$2
APP="\$(cd "\$(dirname "\$0")/.." && pwd)"
ROOT="\$(cd "\$APP/.." && pwd)"
$3
TESTER
    chmod +x "$FIX/xx_app/test/$1"
}

_run() {  # _run → engine stdout+stderr in OUT, status in RC
    OUT="$(cd "$FIX" && CLOUD_ANDROID_ROOT="$FIX" sh "$ENGINE" shell xx_app 2>&1)"
    RC=$?
}

_clean() { rm -f "$FIX"/xx_app/test/test-*.sh; }

echo "== foreign gate: what may and may not fail an application's release =="

# ── 1. own-source failure is FATAL ────────────────────────────────────────
# The control. If this ever goes green the mechanism has eaten the disease's
# cure along with the disease.
_clean
_tester test-own.sh '#!/usr/bin/env bash' 'grep -q "NOT PRESENT" "$APP/mine.txt" || exit 1'
_run
[ "$RC" -ne 0 ] \
    && ok "a tester that fails on the application's OWN source still fails the release (exit $RC)" \
    || bad "own-source failure did NOT fail the release — the gate has stopped working entirely"
grep -q 'test-own.sh FAILED' <<<"$OUT" \
    && ok "the own-source failure is reported as a failure" \
    || bad "the own-source failure was not reported"

# ── 2. foreign failure is NOT fatal, and is loud ──────────────────────────
# Byte-for-byte the same assertion as (1), pointed at the stranger's file. The
# ONLY difference between the two is whose source the verdict was reached with,
# which is precisely the distinction the rule draws.
_clean
_tester test-foreign.sh '#!/usr/bin/env bash' 'grep -q "NOT PRESENT" "$ROOT/yy_stranger/thing.txt" || exit 1'
_run
[ "$RC" -eq 0 ] \
    && ok "a tester that fails on ANOTHER application's source does not fail this release" \
    || bad "foreign failure still failed the release (exit $RC) — the rule is not enforced"
grep -q 'FOREIGN to xx_app' <<<"$OUT" \
    && ok "the foreign failure is announced as foreign, not swallowed" \
    || bad "the foreign failure produced no ::warning:: — it became invisible, which is worse"
grep -q 'yy_stranger' <<<"$OUT" \
    && ok "the warning NAMES the foreign path, so the owner knows whose it is" \
    || bad "the warning does not name the foreign path"
grep -q 'COVERAGE-GAP .* reached outside xx_app' <<<"$OUT" \
    && ok "the downgrade is counted as a coverage gap, not forgotten" \
    || bad "no coverage-gap line for the downgraded tester"

# ── 3. reach alone downgrades nothing ─────────────────────────────────────
# A passing tester that reads the whole fleet must keep counting as a pass —
# and, more importantly, the NEXT own-source failure beside it must still be
# fatal. Otherwise "touches foreign" would become a blanket amnesty.
_clean
_tester test-foreign-ok.sh '#!/usr/bin/env bash' 'grep -q "stranger" "$ROOT/yy_stranger/thing.txt" || exit 1'
_tester test-own-too.sh    '#!/usr/bin/env bash' 'grep -q "NOT PRESENT" "$APP/mine.txt" || exit 1'
_run
[ "$RC" -ne 0 ] \
    && ok "an own-source failure beside a foreign-reaching PASS is still fatal" \
    || bad "one foreign-reaching tester amnestied the whole suite"
grep -q '0 foreign' <<<"$OUT" \
    && ok "a foreign-reaching tester that passes is not counted as downgraded" \
    || bad "a passing foreign-reaching tester was miscounted as a downgrade"

# ── 3b. running git must not make a tester foreign ────────────────────────
# `[ ! -e "$ROOT/.git" ]` is how a tester WALKS UP TO THE REPOSITORY ROOT, and
# five real testers here do exactly that, so .git was the single most common
# "foreign" path in the tree. It is not any application's source, and counting
# it would have downgraded a genuine own-source failure in every one of them —
# the mechanism failing OPEN, the one direction it must never fail.
#
# The assertion names .git the way the real testers do, on a command line,
# because that is what the trace can see: a path opened INSIDE git never
# appears there, so a fixture that merely ran `git` would pass whether or not
# the exemption existed. This one was watched failing with the exemption
# removed before it was trusted passing with it.
_clean
_tester test-own-git.sh '#!/usr/bin/env bash' '[ ! -e "$ROOT/.git" ] && exit 0; grep -q "NOT PRESENT" "$APP/mine.txt" || exit 1'
_run
[ "$RC" -ne 0 ] \
    && ok "a tester that runs git is still judged on its OWN source, not amnestied by .git" \
    || bad ".git counted as foreign source — any tester using git would be silently downgraded"

# ── 4. an underivable own-path set REFUSES TO RUN ─────────────────────────
# The catastrophic mode. An empty set marks every path foreign, so every
# failure downgrades and the suite can never go red again — while reporting a
# cheerful green. It must refuse instead.
_clean
_tester test-own.sh '#!/usr/bin/env bash' 'exit 1'
OUT="$(cd "$FIX" && CLOUD_ANDROID_ROOT="$FIX" PUBLISH_GATE_WORKFLOW="1_cicd/src/cicd/ship-does-not-exist.yml" sh "$ENGINE" shell xx_app 2>&1)"; RC=$?
[ "$RC" -ne 0 ] \
    && ok "the engine refuses to run when it cannot derive the application's own paths" \
    || bad "an underivable own-path set ran anyway — every tester would be downgraded silently"
grep -qi 'own source paths\|refusing to run' <<<"$OUT" \
    && ok "the refusal says why" \
    || bad "the refusal is silent about its reason"

# ── 5. an unobservable tester stays FATAL ─────────────────────────────────
# A /bin/sh tester cannot be traced without changing the interpreter it runs
# under, so its reach is unknown. Unknown must mean fatal: a missing tool may
# never buy a pass. This one READS THE STRANGER and still has to fail.
_clean
_tester test-untraceable.sh '#!/bin/sh' 'grep -q "NOT PRESENT" "$ROOT/yy_stranger/thing.txt" || exit 1'
_run
[ "$RC" -ne 0 ] \
    && ok "a tester whose reach cannot be observed stays fatal even when it does read foreign source" \
    || bad "an unobservable tester was downgraded — the mechanism fails OPEN on a missing tool"
grep -q 'could not be observed' <<<"$OUT" \
    && ok "the engine says the reach was unobservable rather than implying it checked" \
    || bad "the unobservable case is not explained"

# ── 6. a stranger reviving a quarantined cross-app tester must not kill it ─
# quarantine's self-expiry is FATAL on purpose: an entry that outlived its
# reason must be deleted. But when the tester reads another application, the
# thing that made it pass may be a stranger's commit, and failing the release
# over stale bookkeeping is the very shape this rule forbids.
_clean
_build_json ', "quarantine": { "test-foreign-q.sh": "fixture reason" }'
_tester test-foreign-q.sh '#!/usr/bin/env bash' 'grep -q "stranger" "$ROOT/yy_stranger/thing.txt" || exit 1'
_run
[ "$RC" -eq 0 ] \
    && ok "a quarantined CROSS-APPLICATION tester that starts passing does not fail the release" \
    || bad "a stranger's commit reviving a quarantined foreign tester killed the release (exit $RC)"
grep -q 'QUARANTINED but now PASSES' <<<"$OUT" \
    && ok "the stale quarantine entry is still reported and still asked for" \
    || bad "the revival went unreported — the entry would live forever"

# ...and the same self-expiry stays FATAL for a tester that is this app's own.
_clean
_build_json ', "quarantine": { "test-own-q.sh": "fixture reason" }'
_tester test-own-q.sh '#!/usr/bin/env bash' 'grep -q "owns" "$APP/mine.txt" || exit 1'
_run
[ "$RC" -ne 0 ] \
    && ok "quarantine self-expiry is still fatal for an OWN-source tester" \
    || bad "the foreign carve-out disabled quarantine self-expiry across the board"
_build_json

# ── 7. the two kinds of run must not read alike ───────────────────────────
# "Nothing published because a foreign gate was downgraded" and "nothing
# published because the application is broken" have to be distinguishable at a
# glance, or the mechanism trades one silent failure for another.
_clean
_tester test-foreign.sh '#!/usr/bin/env bash' 'grep -q "NOT PRESENT" "$ROOT/yy_stranger/thing.txt" || exit 1'
_run
FOREIGN_OUT="$OUT"
_clean
_tester test-own.sh '#!/usr/bin/env bash' 'grep -q "NOT PRESENT" "$APP/mine.txt" || exit 1'
_run
OWN_OUT="$OUT"
grep -q 'FOREIGN-DOWNGRADED' <<<"$FOREIGN_OUT" \
    && ok "the foreign-downgraded run carries the FOREIGN-DOWNGRADED banner" \
    || bad "no banner on the downgraded run — it looks like an ordinary green"
grep -q 'FOREIGN-DOWNGRADED' <<<"$OWN_OUT" \
    && bad "the BROKEN-APPLICATION run also claims FOREIGN-DOWNGRADED — the two are indistinguishable" \
    || ok "the broken-application run carries no such banner, so the two cannot be confused"

echo "── foreign gate: $PASS ok, $FAIL failed ──"
[ "$FAIL" -eq 0 ]
