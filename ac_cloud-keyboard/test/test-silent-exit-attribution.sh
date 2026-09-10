#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ No user-facing action in this APK can end without saying why     ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS FILE EXISTS RATHER THAN JUST THE WORKFLOW. silence-guard.yml already
# runs the guard on every push, and that is worth having — but it gates NOTHING
# about this app's release. ship-cloud-keyboard.yml builds the APK, pushes it to
# GHCR and attaches it to a GitHub Release, and until this directory existed it
# ran no assertion of any kind on the way. A guard in a neighbouring workflow
# cannot stop a broken keyboard from reaching the owner's phone; a tester the
# ship workflow runs can. That is the whole of this file's job.
#
# The property itself lives in the guard and its entry points live in
# 1_cicd/src/data/silence-guard.json, so this adds no second copy of either.

set -uo pipefail

# Anchored to the repo root by upward search, NOT by counting ../ from here.
# Markers written as if the CWD were the app directory have already matched
# nothing in this repository and reported green for it. The test engine runs
# testers from the repository root; a developer runs them from anywhere.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-silence-guard.py"

# Fail CLOSED on missing tooling. Four testers in this repository passed only
# because ripgrep was absent and their invocation failed open; the same shape
# with python3 would certify this keyboard on the strength of python3 not being
# installed. build.json::tests.shell.requires also names python3, so the engine
# refuses before reaching here — this is the second lock, for a hand run.
if ! command -v python3 >/dev/null 2>&1; then
    echo "FAIL  python3 is not installed — this tester cannot reach a verdict and will not pretend to"
    exit 1
fi
if [ ! -f "$GUARD" ]; then
    echo "FAIL  $GUARD is missing — the ship gate would otherwise pass by running nothing"
    exit 1
fi

CLOUD_ANDROID_ROOT="$ROOT" python3 "$GUARD"
STATUS=$?

# The guard's exit code IS the verdict, taken directly and not through a pipe.
# Reading a pipeline's status through `tail` reports tail's status, which is how
# a tester in this repository reported success for a command that had failed.
exit "$STATUS"
