#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ Every name drive.html calls on the bridge exists, and the editor and the ║
# ║ mirror jobs reach the APK through all of their carriers (#330)           ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. cloud-drive had zero testers, so its ship run asked one
# question — did Gradle exit 0 — and Gradle cannot see any of what follows. The
# whole application is an HTML page calling a Kotlin class by STRING NAME across
# a WebView JavascriptInterface. Nothing in the toolchain checks those strings:
#
#   - rename a bridge method and the Kotlin still compiles, the APK still
#     builds, the tab still renders, and the call returns undefined.
#   - drive.html's own Bridge.call() wraps that in try/catch and hands back
#     { ok: false }, and Bridge.raw() hands back null, which every caller reads
#     as "the list is empty". A BROKEN BUILD IS INDISTINGUISHABLE FROM AN EMPTY
#     PHONE. That is the failure this file makes loud.
#
# WHAT IT PINS:
#
#   A1  every name the page calls — Bridge.call('x') and Bridge.raw('x') — is
#       declared @JavascriptInterface in FilesBridge.kt.
#   A2  the editor is wired at BOTH ends: the page reads through readText and
#       SAVES through writeText, and both are on the bridge. An editor whose
#       save never reaches disk is #330's central requirement, silently unmet.
#   A3  the mirror (rsync) jobs travel all three carriers — the data file, the
#       build.gradle BuildConfig field that bakes it in, and the bridge method
#       that decodes it. Drop any one and the Backups tab renders "Nothing
#       here" over a file that is right there in the repository.
#   A4  no declared job would destroy data by construction: no destination sits
#       inside its own source (each pass would copy the last one, for ever), and
#       no path is absolute (the /storage/emulated/0 form names one device's
#       user id and is wrong under a second profile).
#
# NO RIPGREP, DELIBERATELY — testers in this repository have passed on its
# absence rather than on their assertions. python3 and grep only, both in
# build.json::tests.shell.requires, so a missing one is fatal at the engine's
# preflight instead of quietly agreeing with everything here. A missing SOURCE
# file is fatal here too, for the same reason.
set -uo pipefail

# CLOUD_ANDROID_ROOT is the test engine's own variable for "the checkout to
# read", honoured so this tester can be pointed at a scratch tree — which is how
# its mutation proof is taken (rename writeText in a copy, watch A1 and A2 go
# red; declare a job whose destination is under its source, watch A4 go red).
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
PAGE="$APP/app/src/main/assets/drive.html"
BRIDGE="$APP/app/src/main/java/com/diegonmarcos/clouddrive/FilesBridge.kt"
GRADLE="$APP/app/build.gradle"
JOBS="$APP/data/drive-mirror-jobs.json"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

for required in "$PAGE" "$BRIDGE" "$GRADLE" "$JOBS"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required"; exit 1; }
done

echo "── A1 every bridge name the page calls is declared on the bridge ──"
python3 - "$PAGE" "$BRIDGE" <<'PYTHON'
import re, sys

page = open(sys.argv[1], encoding="utf-8").read()
bridge = open(sys.argv[2], encoding="utf-8").read()

called = sorted(set(re.findall(r"Bridge\.(?:call|raw)\(\s*'([A-Za-z]\w*)'", page)))
# The only shape the WebView will expose. A plain `fun` is unreachable from the
# page no matter how right its name looks, which is itself a bug worth catching.
declared = set(re.findall(r"@JavascriptInterface\s+fun\s+(\w+)", bridge))

if not called:
    print("  FAIL  the page calls nothing on the bridge — this tester read the wrong file")
    sys.exit(1)

missing = [name for name in called if name not in declared]
for name in called:
    print(("  PASS  Bridge -> %s" if name in declared else
           "  FAIL  the page calls %s, which FilesBridge does not declare @JavascriptInterface") % name)
print("  ....  %d names checked, %d declared on the bridge" % (len(called), len(declared)))
sys.exit(1 if missing else 0)
PYTHON
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

echo "── A2 the editor reads AND saves ──"
if grep -q "fun readText(" "$BRIDGE"; then
    pass "FilesBridge.readText exists"
else
    fail "FilesBridge.readText is missing — the editor cannot open a file"
fi
if grep -q "fun writeText(" "$BRIDGE"; then
    pass "FilesBridge.writeText exists"
else
    fail "FilesBridge.writeText is missing — the editor cannot save a file"
fi
if grep -q "Bridge.call('writeText'" "$PAGE"; then
    pass "the page saves through writeText"
else
    fail "the page never calls writeText — the editor is a viewer, which #330 explicitly refused"
fi
if grep -q "id=\"editor-save\"" "$PAGE" && grep -q "saveEditor" "$PAGE"; then
    pass "the Save control is bound"
else
    fail "no Save control reaches saveEditor — the save path is unreachable from the screen"
fi

echo "── A3 the mirror jobs reach the APK through every carrier ──"
if grep -q "drive-mirror-jobs.json" "$GRADLE" && grep -q "MIRROR_JOBS_B64" "$GRADLE"; then
    pass "build.gradle bakes data/drive-mirror-jobs.json into MIRROR_JOBS_B64"
else
    fail "build.gradle does not bake drive-mirror-jobs.json into MIRROR_JOBS_B64"
fi
if grep -q "BuildConfig.MIRROR_JOBS_B64" "$BRIDGE"; then
    pass "FilesBridge reads BuildConfig.MIRROR_JOBS_B64"
else
    fail "FilesBridge never reads MIRROR_JOBS_B64 — the baked list arrives nowhere"
fi
if grep -q "Bridge.raw('mirrorJobs')" "$PAGE"; then
    pass "the Backups tab asks the bridge for the jobs"
else
    fail "the page never calls mirrorJobs — the Backups tab would render 'Nothing here'"
fi

echo "── A4 no declared job can destroy data by construction ──"
python3 - "$JOBS" <<'PYTHON'
import json, posixpath, sys

document = json.load(open(sys.argv[1], encoding="utf-8"))
jobs = document.get("jobs", [])
if not jobs:
    print("  FAIL  no jobs are declared — the Backups tab has nothing to run")
    sys.exit(1)

failed = False
for job in jobs:
    name = job.get("name", "<unnamed>")
    source, destination = job.get("source", ""), job.get("destination", "")
    if not source or not destination:
        print("  FAIL  %s declares no source or no destination" % name); failed = True; continue
    if source.startswith("/") or destination.startswith("/"):
        print("  FAIL  %s uses an absolute path, which names one device's user id" % name); failed = True; continue
    source_parts = posixpath.normpath(source).split("/")
    destination_parts = posixpath.normpath(destination).split("/")
    if destination_parts[:len(source_parts)] == source_parts:
        print("  FAIL  %s writes into its own source — every pass would copy the last one" % name)
        failed = True; continue
    if not isinstance(job.get("delete", False), bool):
        print("  FAIL  %s declares a non-boolean delete" % name); failed = True; continue
    print("  PASS  %s (delete=%s)" % (name, job.get("delete", False)))
sys.exit(1 if failed else 0)
PYTHON
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "test-drive-bridge-contract: OK"
    exit 0
fi
echo "test-drive-bridge-contract: $FAILURES assertion group(s) FAILED"
exit 1
