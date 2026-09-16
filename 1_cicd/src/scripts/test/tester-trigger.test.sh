#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ tester-trigger — a tester edit must START the run that executes  ║
# ║ it, and must NOT publish an APK (#370)                           ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# Twelve of the thirty-one ship workflows carried `!<app>/<tests.shell.dir>/**`
# in `on: push: paths`. That marker was answering two different questions with
# one NO:
#
#   may this START a run?      It must. This workflow's first step is the ONLY
#                              thing in the repository that executes that
#                              tester, so excluding it meant editing a tester
#                              ran nothing at all — an agent who improved a
#                              tester got a green board for the trouble.
#   may this PUBLISH an APK?   It must not. A tester cannot change the bytes.
#                              Run 34850225588 republished the SuperApp for a
#                              commit that touched only aa_cloud-superapp/test/.
#
# The two answers now come from one declaration, build.json::tests.shell.dir,
# read by cloud-android-ship-repo-workflow-engine.sh (which writes the trigger)
# and by cloud-android-source-identity.sh (which computes the identity the gate
# hashes). This asserts that they still disagree in the ONE direction they are
# supposed to, for every app, DERIVED FROM DISK — a hardcoded list that has to
# agree with a directory glob is this repository's most recurring bug.
#
# The last block is the mutation test that the fix cannot quietly rebuild the
# bug it fixes: a suite that runs ZERO testers must exit non-zero.
set -uo pipefail

ROOT="$(_d="$(cd "$(dirname "$0")" && pwd)"; while [ "$_d" != "/" ] && [ ! -e "$_d/.git" ]; do _d="$(dirname "$_d")"; done; printf '%s' "$_d")"
cd "$ROOT"

fail=0
ok()  { echo "  PASS  $*"; }
bad() { echo "  FAIL  $*"; fail=1; }

IDENTITY=1_cicd/src/scripts/cloud-android-source-identity.sh
ENGINE=1_cicd/src/scripts/cloud-android-test-engine.sh

# ── the app set comes from the disk, never from a list in here ──────
checked=0
for bj in */build.json; do
    app="$(dirname "$bj")"
    tdir="$(jq -r '.tests.shell.dir // empty' "$bj")"
    tdir="${tdir#/}"; tdir="${tdir%/}"
    [ -n "$tdir" ] && [ -d "$app/$tdir" ] || continue

    # The app's own ship workflow, found the same way the identity script finds
    # it: by declared WORK_DIR, not by filename.
    wf=""
    for f in 1_cicd/src/cicd/ship-*.yml; do
        grep -qx "  WORK_DIR: $app" "$f" && { wf="$f"; break; }
    done
    [ -n "$wf" ] || continue
    checked=$((checked + 1))

    block="$(sed -n '/^    paths:$/,/^[^ ]/p' "$wf" | sed -n 's/^      - "\(.*\)"$/\1/p')"

    # (1) NOTHING may exclude the tester directory from the trigger.
    if printf '%s\n' "$block" | grep -q "^!$app/$tdir"; then
        bad "$wf excludes $app/$tdir from on:push:paths — editing a tester would run nothing"
    else
        ok "$wf watches $app/$tdir"
    fi

    # (2) …and some POSITIVE entry must actually match a file inside it.
    matched=no
    while IFS= read -r e; do
        case "$e" in ""|"!"*) continue ;; esac
        p="${e%/\*\*}"; p="${p%/\*}"; p="${p%\*\*}"; p="${p%/}"
        case "$app/$tdir" in "$p"|"$p"/*) matched=yes ;; esac
    done <<< "$block"
    [ "$matched" = yes ] \
        && ok "$wf has a positive trigger covering $app/$tdir" \
        || bad "$wf has NO positive trigger matching $app/$tdir — the directory is watched by nothing"

    # (3) …and the publish identity must still NOT see it, or every tester edit
    #     puts a byte-identical APK on every phone in the fleet.
    if sh "$IDENTITY" paths "$app" | grep -qx -e "$app/$tdir" -e "$app/$tdir/.*"; then
        bad "$IDENTITY hashes $app/$tdir — a tester edit would republish an unchanged APK"
    else
        ok "$IDENTITY excludes $app/$tdir from the publish identity"
    fi
done

[ "$checked" -gt 0 ] \
    && ok "$checked application(s) declare a shell-tester directory and were checked" \
    || bad "NO application was checked — this tester asserted nothing, which is the failure it exists to catch"

# ── the fix must not rebuild the bug inside itself ───────────────────
# A run that executes zero testers and reports success is the same green-that-
# verified-nothing one layer in, and it is reachable two ways: the declared
# directory is missing, or it holds no test-*.sh. Both directions are asserted —
# a guard proven in one direction is not proven.
#
# A FIXTURE, not a real app: mutating a real tester directory to prove a point
# is how a shared checkout loses someone's work. PUBLISH_GATE_WORKFLOW points
# the identity lookup at a real workflow so the engine's own preflight (which
# refuses to run on an empty own-path set) is satisfied.
fix=".f3-tester-trigger-fixture"
# Removed on EVERY exit path, not just the happy one: this runs inside a
# checkout other agents share, and an untracked directory left behind by a
# tester that died halfway is how somebody else's `git add` picks up a fixture.
trap 'rm -rf "$ROOT/$fix"' EXIT INT TERM
rm -rf "$fix"; mkdir -p "$fix/test"
printf '{"tests":{"shell":{"dir":"test"}}}\n' > "$fix/build.json"
export PUBLISH_GATE_WORKFLOW="1_cicd/src/cicd/publish-gate-guard.yml"

_engine() { sh "$ENGINE" shell "$fix" >/dev/null 2>&1; echo $?; }

[ "$(_engine)" != 0 ] \
    && ok "declared suite, directory present, zero test-*.sh → non-zero" \
    || bad "declared suite with NO testers reported SUCCESS — the fix rebuilt the bug"

cat > "$fix/test/test-fixture-passes.sh" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$fix/test/test-fixture-passes.sh"
[ "$(_engine)" = 0 ] \
    && ok "one passing tester restored → zero (the guard is not stuck red)" \
    || bad "a passing tester still reported failure — the guard fires when it must not"

rm -rf "$fix/test"
[ "$(_engine)" != 0 ] \
    && ok "declared suite, directory absent → non-zero" \
    || bad "declared tests.shell.dir missing entirely reported SUCCESS"

rm -rf "$fix"

[ "$fail" -eq 0 ] && echo "tester-trigger: ALL PASS" || echo "tester-trigger: FAILURES ABOVE"
exit "$fail"
