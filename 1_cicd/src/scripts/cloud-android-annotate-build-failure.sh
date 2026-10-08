#!/bin/sh
# cloud-android-annotate-build-failure — a red build must say WHY in the run's own
# annotations: the raw job log needs a token the owner's phone does not hold, and
# "Process completed with exit code 1" is not a reason. Re-emits the Kotlin/Gradle
# error lines of a captured build log as ::error:: lines (the unit-test engine does
# the same for its runs). Usage: <captured log> [annotation title]
log="${1:?captured build log}"; title="${2:-build}"
[ -f "$log" ] || { echo "::error title=$title::no build log was captured at $log"; exit 0; }
grep -nE '^e: |error:|> Task .* FAILED|What went wrong|Execution failed|Manifest merger|Unresolved reference|FAILURE: |Could not ' "$log" \
    | head -40 | tr -d '\r' | cut -c1-900 | sed 's/%/%25/g' \
    | while IFS= read -r l; do echo "::error title=$title::$l"; done
exit 0
