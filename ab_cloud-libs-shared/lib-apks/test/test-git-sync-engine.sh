#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #567 push 2 / #871 move 11 — the git sync ENGINE (JGit), checked where it ║
# ║ now ships: libs:git-sync-engine, Cloud-Lib-Git-Sync-Engine.apk            ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# Until move 11 Cloud Drive compiled JGit and these checks lived in
# ac_cloud-drive/test/test-drive-git-sync-engine.sh, -git-page.sh and -git-auth-chain.sh. No app
# compiles JGit now: it ships in the engine APK, and Cloud Drive's ship does not watch that
# directory, so a change there runs THIS pipeline (Cloud Libs) and no other. The checks moved here
# with the code.
#
#   G1  the brief's verb inventory — status, stage, unstage, commit, push, pull, branches, log, diff,
#       remotes, resolve, markResolved, abortMerge, and the two force verbs — is DECLARED on JGitEngine
#       and answered by the engine's call surface (GitCalls) …
#   G3  … JGitEngine.kt imports nothing from android.* — it is the JVM-tested half.
#   G4  branches are read-only: no create/delete/checkout/rename-branch verb in the engine, no
#       `branchCreate(`/`branchDelete(`/`branchRename(` JGit call anywhere in the module.
#   G5  the JVM suite covers every verb in G1 by name: each verb appears in JGitEngineTest.kt, so a
#       verb added to the engine without a test fails here, not on the phone. The force verbs are
#       proven to do what the plain ones cannot.
#   G6  the engine's dependencies are the ones the module doc says: JGit, the jsch SSH module with the
#       original jsch EXCLUDED and the mwiede fork in its place (the fork is what understands
#       accept-new), and host keys are accept-new, never 'no'.
#   G7  #669 the SESSION rides the HTTP transport as the one declared header — for clone AND for the
#       shared withAuth path every later fetch/pull/push rides — and a bearer never reaches the leg.
#   G8  the engine is not an app: it names none, and the wire's verbs are all answered (GitWire.VERBS is
#       exactly the verbs GitCalls.execute implements).
#
# OWN-SOURCE ONLY: reads ab_cloud-libs-shared/libs and lib-apks, nothing an app owns.
# python3 and grep only, no network, no build.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
LIBS="$ROOT/ab_cloud-libs-shared/libs"
MOD="$LIBS/git-sync-engine"
CONTRACT="$LIBS/git-sync"
PKG="$MOD/src/main/java/com/diegonmarcos/cloudlib/gitsync/engine"
ENGINE="$PKG/JGitEngine.kt"
CALLS="$PKG/GitCalls.kt"
TEST="$MOD/src/test/java/com/diegonmarcos/cloudlib/gitsync/engine/JGitEngineTest.kt"
GRADLE="$MOD/build.gradle"
WIRE="$CONTRACT/src/main/java/com/diegonmarcos/cloudlib/gitsync/GitWire.kt"
LIBAPKS="$ROOT/ab_cloud-libs-shared/lib-apks/build.json"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
_code() { grep -vE '^[[:space:]]*(\*|//|/\*)' "$1"; }
for required in "$ENGINE" "$CALLS" "$TEST" "$GRADLE" "$WIRE" "$LIBAPKS"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

VERBS="status stage unstage commit push pull branches log diff remotes resolve markResolved abortMerge forcePush forcePull"

echo "── G1 every mandated verb is declared on the engine ──"
for verb in $VERBS; do
    if grep -qE "^\s*fun $verb\(" "$ENGINE"; then pass "engine declares $verb()"; else fail "engine lacks fun $verb("; fi
done

echo "── G3 the engine proper is pure JVM ──"
if grep -qE "^import android\." "$ENGINE"; then fail "JGitEngine.kt imports android.* — the JVM suite would run against stubs"; else pass "JGitEngine.kt has no android.* import"; fi

echo "── G4 branches are read-only ──"
if grep -qE "fun (createBranch|deleteBranch|checkoutBranch|renameBranch|branchCreate)\(" "$ENGINE"; then
    fail "the engine grew a branch-writing verb (fleet rule: a manager lists branches, never creates them)"
else
    pass "no branch-writing verb on the engine"
fi
WRITES="$(grep -rnE "\.(branchCreate|branchDelete|branchRename)\(" "$MOD/src/main" "$CONTRACT/src/main")"
if [ -z "$WRITES" ]; then pass "no JGit branchCreate/branchDelete/branchRename call in the engine or its contract"; else fail "branch-writing JGit call:"; printf '%s\n' "$WRITES" | sed 's/^/        /'; fi

echo "── G5 the JVM suite names every verb ──"
TESTS="$(grep -cE "^\s*@Test" "$TEST")"
if [ "$TESTS" -gt 0 ]; then pass "JGitEngineTest has $TESTS @Test methods"; else fail "JGitEngineTest has no @Test"; fi
for verb in $VERBS; do
    if grep -qE "\.$verb\(" "$TEST"; then pass "test exercises .$verb("; else fail "no test calls .$verb("; fi
done
if grep -qE "MERGING" "$TEST" && grep -qE "<<<<<<<" "$TEST"; then pass "the conflict test reads the merge state and the markers off disk"; else fail "the conflict test does not read the MERGING state and the conflict markers"; fi
if grep -qE 'fun forcePushOverwritesTheRemoteAndForcePullOverwritesTheLocalSide' "$TEST" \
   && grep -qE 'assertFalse\("a diverged push must be rejected' "$TEST" \
   && grep -qE 'forced\.summary\.contains\("force-pushed"\)' "$TEST" \
   && grep -qE 'forced\.summary\.contains\("force-pulled"\)' "$TEST"; then
    pass "the JVM suite drives both force verbs on real repositories AND shows the plain verbs refuse the same divergence first"
else
    fail "JGitEngineTest does not prove the force verbs do what the plain ones cannot"
fi
if grep -qE 'setForce\(true\)' "$ENGINE" && grep -qE 'ResetCommand\.ResetType\.HARD\)\.setRef\(ref\.name\)' "$ENGINE"; then pass "forcePush takes the fast-forward check off; forcePull resets HARD onto the fetched remote ref"; else fail "the force verbs are not what #608 says (setForce(true) / reset --hard onto the remote ref)"; fi

echo "── G6 dependencies match the module's own account of them ──"
if grep -qE "org\.eclipse\.jgit:org\.eclipse\.jgit:" "$GRADLE"; then pass "JGit declared"; else fail "JGit not declared"; fi
if grep -qE "org\.eclipse\.jgit\.ssh\.jsch" "$GRADLE" && grep -qE "exclude group: 'com\.jcraft', module: 'jsch'" "$GRADLE" && grep -qE "com\.github\.mwiede:jsch" "$GRADLE"; then
    pass "ssh.jsch with the original jsch excluded and the mwiede fork in its place"
else
    fail "the SSH transport is not wired as documented (ssh.jsch + exclude com.jcraft:jsch + com.github.mwiede:jsch)"
fi
if grep -qE '"StrictHostKeyChecking", "accept-new"' "$ENGINE"; then pass "host keys: accept-new, never 'no'"; else fail "StrictHostKeyChecking is not accept-new"; fi
if grep -qE "org\.eclipse\.jgit|com\.jcraft" "$CONTRACT/build.gradle"; then fail "libs:git-sync (compiled into the app) still depends on JGit/jsch"; else pass "the contract module carries no JGit and no jsch"; fi
if python3 -c 'import json,sys; sys.exit(0 if "git-sync-engine" not in json.load(open(sys.argv[1]))["lib_apks"].get("exclude", {}) else 1)' "$LIBAPKS"; then pass "the engine is not excluded from the lib APK shelf"; else fail "git-sync-engine is excluded from the shelf — it would never ship"; fi

echo "── G7 #669 the session rides the transport as the declared header, and a bearer never does ──"
wired="$(_code "$ENGINE" | grep -oE 'setTransportConfigCallback\(sessionCallback\(auth\)\)' | wc -l | tr -d ' ')"
if [ "$wired" = "2" ]; then pass "the engine wires the session twice: clone and withAuth (fetch/pull/push)"; else fail "the engine wires the session $wired time(s); clone and withAuth are the two"; fi
if grep -qE 'if \(transport is TransportHttp\) transport\.setAdditionalHeaders\(mapOf\(auth\.header to auth\.value\)\)' "$ENGINE"; then pass "the session rides TransportHttp.setAdditionalHeaders under the DECLARED header name"; else fail "the session does not ride TransportHttp.setAdditionalHeaders under the DECLARED header name"; fi
bearer="$(for f in "$ENGINE" "$CALLS" "$WIRE"; do _code "$f" | grep -nE '"Authorization"|Bearer ' || true; done)"
if [ -z "$bearer" ]; then pass "no Authorization/Bearer literal on the engine or the wire"; else fail "a bearer literal on the git leg (measured to collide with gitea's own token auth):"; printf '%s\n' "$bearer" | sed 's/^/        /'; fi

echo "── G8 the wire's verbs are exactly the ones the engine runs ──"
python3 - "$WIRE" "$CALLS" <<'PYTHON' && pass "GitWire.VERBS == the verbs GitCalls.execute implements" || fail "the wire and the engine disagree on the verbs (see above)"
import re, sys
wire = open(sys.argv[1], encoding="utf-8").read()
calls = open(sys.argv[2], encoding="utf-8").read()
m = re.search(r"val VERBS: Set<String> = setOf\((.*?)\)\n", wire, re.S)
declared = set(re.findall(r'"([a-z_]+)"', m.group(1))) if m else set()
body = calls[calls.index("private fun execute("):]
implemented = set(re.findall(r'^\s*"([a-z_]+)"\s*->', body, re.M))
bad = 0
if not declared: print("    GitWire.VERBS not found"); bad = 1
if declared - implemented: print("    declared but not implemented: %s" % sorted(declared - implemented)); bad = 1
if implemented - declared: print("    implemented but not declared: %s" % sorted(implemented - declared)); bad = 1
sys.exit(bad)
PYTHON

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-git-sync-engine: all checks passed"; else echo "test-git-sync-engine: $FAILURES check(s) FAILED"; exit 1; fi
