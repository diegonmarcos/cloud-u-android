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
#   A9  the numbered prompt() menus are gone and every menu routes through ONE
#       bottom sheet (entry / new-item / sort / tools).
#   A10 the file-manager additions exist at BOTH ends: search, createFile,
#       archive/extract, properties, bulk rename (preview + apply), duplicates,
#       the cancellable job trio, and the SAF tree grant methods.
#   A11 the Zip Slip guard is a real function extract() consults, it rejects the
#       three classic escape shapes, and a hostile archive is refused wholesale
#       before one byte lands. Mutation-run proven locally.
#   A12 a paste that does not fit is refused with the actual numbers, and
#       places() lists removable volumes (getExternalFilesDirs) plus a persisted
#       SAF tree grant and a connect affordance for what path access cannot reach.
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

echo "── A5 the Apps grid is one declaration, resolved against the fleet manifest ──"
APPS="$APP/data/drive-apps.json"
FLEET="$ROOT/aa_cloud-superapp/data/constellation-fleet.json"
[ -f "$APPS" ] || { fail "missing data/drive-apps.json — the Apps tab has no declaration"; APPS=""; }
[ -f "$FLEET" ] || { fail "missing aa_cloud-superapp/data/constellation-fleet.json — the fleet manifest is not in this checkout"; FLEET=""; }
if [ -n "$APPS" ] && [ -n "$FLEET" ]; then
python3 - "$APPS" "$FLEET" <<'PYTHON'
import json, sys

apps_file, fleet_file = sys.argv[1], sys.argv[2]
declared = json.load(open(apps_file, encoding="utf-8"))
fleet = json.load(open(fleet_file, encoding="utf-8"))

entries = declared.get("apps", [])
if not entries:
    print("  FAIL  data/drive-apps.json declares no apps — the Apps tab has nothing to render")
    sys.exit(1)

by_id = {a.get("id"): a for a in fleet.get("apps", [])}
failed = False
for entry in entries:
    label = entry.get("label", "<unnamed>")
    if not label or not entry.get("icon"):
        print("  FAIL  %s declares no label or no icon" % label); failed = True; continue
    if entry.get("fleet"):
        fleet_entry = by_id.get(entry["fleet"])
        if not fleet_entry:
            print("  FAIL  %s names fleet id '%s' which the manifest does not contain" %
                  (label, entry["fleet"])); failed = True; continue
        package = fleet_entry.get("package", "")
        if not package:
            print("  FAIL  fleet id '%s' carries no package in the manifest" % entry["fleet"]); failed = True; continue
        if "com.diegonmarcos" not in package:
            print("  FAIL  fleet id '%s' resolves outside the cloud namespace: %s" %
                  (entry["fleet"], package)); failed = True; continue
        print("  PASS  %s -> %s" % (label, package))
    else:
        if not entry.get("package"):
            print("  FAIL  non-fleet app %s declares no package" % label); failed = True; continue
        if "com.diegonmarcos" in entry.get("package", ""):
            print("  FAIL  %s hardcodes a cloud package '%s' — that identity must come from the fleet manifest" %
                  (label, entry["package"])); failed = True; continue
        print("  PASS  %s -> %s (not a fleet app: package declared here)" % (label, entry["package"]))

# The one-declaration rule: a cloud package string appears only in the manifest.
import re
raw = open(apps_file, encoding="utf-8").read()
if re.search(r'"package"\s*:\s*"com\.diegonmarcos', raw):
    print("  FAIL  drive-apps.json restates a com.diegonmarcos package — identity must come from the fleet manifest")
    failed = True
sys.exit(1 if failed else 0)
PYTHON
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))
fi
if grep -q "UI_APPS_B64" "$GRADLE" && grep -q "drive-apps.json" "$GRADLE"; then
    pass "build.gradle bakes data/drive-apps.json into UI_APPS_B64"
else
    fail "build.gradle does not bake drive-apps.json into UI_APPS_B64"
fi
if grep -q "constellation-fleet.json" "$GRADLE"; then
    pass "build.gradle resolves package identity from the constellation fleet manifest"
else
    fail "build.gradle never reads the fleet manifest — package identity has no source"
fi
if grep -q "Bridge.raw('apps')" "$PAGE" && grep -q "Bridge.call('openApp'" "$PAGE"; then
    pass "the Apps tab reads the grid through the bridge and launches through openApp"
else
    fail "the Apps tab does not reach the bridge both ways (apps list + openApp launch)"
fi

echo "── A6 the Sync tab has Git and Rclone subpages ──"
if grep -q 'data-tab="sync"' "$PAGE"; then
    pass "the nav declares the Sync tab"
else
    fail "the nav has no data-tab=\"sync\" button"
fi
if grep -q "renderSync" "$PAGE"; then
    pass "showTab dispatches to renderSync"
else
    fail "showTab never calls renderSync — the tab would render nothing"
fi
if grep -q "renderRcloneSubpage" "$PAGE" && grep -q "renderGitSubpage" "$PAGE"; then
    pass "Sync renders both subpages"
else
    fail "one of the Sync subpages has no renderer"
fi
if grep -q "connectionCard" "$PAGE" && grep -q "'rclone'" "$PAGE"; then
    pass "the Rclone subpage reuses the existing connectionCard renderer"
else
    fail "the Rclone subpage does not reuse connectionCard — a second renderer to keep in step"
fi
if grep -q "VENDORED.md" "$PAGE"; then
    pass "the Git subpage names the vendored lib's record"
else
    fail "the Git subpage does not reference VENDORED.md — the honest state is not stated"
fi

echo "── A7 dark is the default, set at the declaration ──"
THEMES="$APP/app/src/main/res/values/themes.xml"
MAIN="$APP/app/src/main/java/com/diegonmarcos/clouddrive/MainActivity.kt"
if grep -q 'Theme.Material3.Dark.NoActionBar' "$THEMES"; then
    pass "themes.xml declares the Material3 Dark parent"
else
    fail "themes.xml still parents Light — the Android chrome is not dark by default"
fi
if grep -q "isAppearanceLightStatusBars = false" "$MAIN" && grep -q "isAppearanceLightNavigationBars = false" "$MAIN"; then
    pass "MainActivity draws LIGHT system-bar glyphs over the dark page"
else
    fail "MainActivity still draws dark glyphs — they would vanish into the dark page"
fi
if grep -q "background: #111827" "$PAGE" && ! grep -q "bg-white" "$PAGE"; then
    pass "the page base is dark and no light surface class remains"
else
    fail "the page still carries a light background or a bg-white surface"
fi

echo "── A9 the numbered menus are dead; every menu is ONE bottom sheet ──"
for needle in "sheetRoot" "openSheet" "sheetMenu" "sheetPrompt" "sheetConfirm"; do
    if grep -q "function $needle\|const $needle" "$PAGE"; then
        pass "the bottom sheet exposes $needle"
    else
        fail "the bottom sheet has no $needle — menus have nowhere to render"
    fi
done
for menu in "newItemMenu" "sortMenu" "toolsMenu"; do
    if grep -q "function $menu" "$PAGE"; then
        pass "$menu exists"
    else
        fail "$menu is missing — its header button would dead-end"
    fi
done
python3 - "$PAGE" <<'PYTHON'
import re, sys
page = open(sys.argv[1], encoding="utf-8").read()
# Every numbered menu used to be a prompt(\n"1 Open\n2 ...") call. The sheet
# replacement keeps the menu STEP but must never resurrect the dialog shape.
if re.search(r"prompt\(\s*[`'\"]", page):
    print("  FAIL  a prompt( callable with text remains — the sheet was supposed to replace it")
    sys.exit(1)
# The entry menu must reach the sheet, not window.prompt.
body = page[page.find("function showEntryMenu"):]
if "sheetMenu(" not in body:
    print("  FAIL  showEntryMenu no longer routes through sheetMenu")
    sys.exit(1)
print("  PASS  showEntryMenu routes through the sheet")
sys.exit(0)
PYTHON
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

echo "── A10 every new file-manager bridge method is declared AND reached ──"
python3 - "$PAGE" "$BRIDGE" <<'PYTHON'
import re, sys
page = open(sys.argv[1], encoding="utf-8").read()
bridge = open(sys.argv[2], encoding="utf-8").read()
declared = set(re.findall(r"@JavascriptInterface\s+fun\s+(\w+)", bridge))

# page-callable names from the new tool set; the status trio is polled behind a
# variable, so its declaration is pinned HERE rather than through the call site.
required = {
    "search": "name AND text search, cancellable",
    "searchStatus": "search progress for the poller",
    "properties": "size + counts + perms + hashes, cancellable",
    "propertiesStatus": "properties progress",
    "duplicates": "size-bucketed duplicate scan",
    "duplicatesStatus": "duplicates progress",
    "cancelJob": "the Cancel button on every long job",
    "createFile": "the create-file sibling createFolder never got",
    "archive": "zip a selection",
    "extract": "unzip with a Zip Slip guard",
    "bulkRenamePreview": "rename pattern preview (no writes)",
    "bulkRename": "rename apply with rollback",
    "requestTreeGrant": "SAF tree grant for SD / USB",
    "treeGrantInfo": "persisted grant state",
    "forgetTreeGrant": "drop the persisted grant"
}
failed = False
for name, purpose in required.items():
    if name in declared:
        print("  PASS  %s is declared — %s" % (name, purpose))
    else:
        print("  FAIL  %s is NOT declared — %s" % (name, purpose)); failed = True
if not re.search(r"Bridge\.call\('search'", page):
    print("  FAIL  the page never calls search"); failed = True
if not re.search(r"Bridge\.call\('archive'", page):
    print("  FAIL  the page never calls archive"); failed = True
if not re.search(r"Bridge\.call\('extract'", page):
    print("  FAIL  the page never calls extract"); failed = True
if not re.search(r"Bridge\.call\('bulkRenamePreview'", page):
    print("  FAIL  the page never calls bulkRenamePreview"); failed = True
if not re.search(r"Bridge\.call\('createFile'", page):
    print("  FAIL  the page never calls createFile"); failed = True
if not re.search(r"Bridge\.call\('properties'", page):
    print("  FAIL  the page never calls properties"); failed = True
if not re.search(r"Bridge\.call\('duplicates'", page):
    print("  FAIL  the page never calls duplicates"); failed = True
sys.exit(1 if failed else 0)
PYTHON
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

echo "── A11 the Zip Slip guard exists and extract() goes through it ──"
python3 - "$BRIDGE" <<'PYTHON'
import re, sys
source = open(sys.argv[1], encoding="utf-8").read()

guard = re.search(r"internal fun zipEntryTarget\(destination: File, entryName: String\): File\?", source)
if not guard:
    print("  FAIL  no zipEntryTarget() — extract() has no place to refuse hostile names")
    sys.exit(1)
# The function must actually be the guard: a name that participates nowhere is
# decoration. A mutation that renames it away must fail THIS assertion set.
if "startsWith(destCanonical.path + File.separator)" not in source[guard.start():]:
    print("  FAIL  the guard never compares the canonical target against the destination root")
    sys.exit(1)
extract = source[source.find("fun extract("):]
if "zipEntryTarget(" not in extract:
    print("  FAIL  extract() never consults zipEntryTarget — a hostile entry would be written")
    sys.exit(1)
# Reject the three classic escape shapes in the guard's own source.
body = source[guard.start():source.find("\n}\n", guard.start())]
for shape, marker in [("dotdot", '".."'), ("absolute", 'startsWith("/")'), ("canonical", "canonicalFile")]:
    if marker not in body:
        print("  FAIL  the guard lacks the %s rejection (%s)" % (shape, marker))
        sys.exit(1)
print("  PASS  zipEntryTarget() rejects '..', absolute entries and non-canonical parents")
# extract() refuses the WHOLE archive on the first hostile name, before writing.
if "the archive tries to write outside the destination" not in extract:
    print("  FAIL  extract() does not refuse the archive wholesale on a hostile entry")
    sys.exit(1)
print("  PASS  extract() refuses the whole archive on the first hostile entry name")
sys.exit(0)
PYTHON
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

echo "── A12 the paste guard and the removable-volume places ──"
if grep -q "not enough space" "$BRIDGE" && grep -q "fun measureTotalSize" "$BRIDGE"; then
    pass "transfer() refuses a paste that cannot fit, with the actual numbers"
else
    fail "transfer() has no free-space guard (measureTotalSize or 'not enough space')"
fi
if grep -q "getExternalFilesDirs" "$BRIDGE" && grep -q "fun places" "$BRIDGE"; then
    pass "places() reads the app's dirs on every mounted volume"
else
    fail "places() never lists getExternalFilesDirs volumes — SD/USB are invisible"
fi
if grep -q "registerForActivityResult" "$MAIN" && grep -q "persistTreeGrant" "$MAIN" "$BRIDGE" && grep -q "takePersistableUriPermission" "$BRIDGE"; then
    pass "the SAF tree grant persists (Activity launcher + takePersistableUriPermission)"
else
    fail "the SAF tree grant is neither launched nor persisted"
fi
if grep -q "kind === 'tree'" "$PAGE" && grep -q "requestTreeGrant" "$PAGE"; then
    pass "the page renders granted tree places and the connect affordance"
else
    fail "the page has no place for a granted tree or a connect action"
fi

echo "── A8 the GitSync lib is vendored, licensed and cannot block the release ──"
GSYNC="$ROOT/ab_cloud-libs-shared/libs/gitsync"
VENDORED="$GSYNC/VENDORED.md"
if [ -f "$VENDORED" ]; then
    pass "VENDORED.md exists in the vendored tree"
else
    fail "no VENDORED.md at ab_cloud-libs-shared/libs/gitsync"
fi
if grep -qi "GPL-3.0\|GNU General Public License" "$VENDORED" && grep -q "0f4902fceab3b1572ffea65e332f14605237f32e" "$VENDORED"; then
    pass "VENDORED.md records the licence verdict and the pinned upstream revision"
else
    fail "VENDORED.md does not record the GPL-3.0 verdict and the pinned revision"
fi
if [ -f "$GSYNC/LICENSE.md" ] && grep -qi "GNU GENERAL PUBLIC LICENSE" "$GSYNC/LICENSE.md"; then
    pass "the upstream GPL-3.0 licence text travels with the tree"
else
    fail "the vendored tree lost its upstream GPL-3.0 licence text"
fi
if [ ! -f "$GSYNC/build.gradle" ]; then
    pass "the vendored tree is not a Gradle module — no APK can compile it by accident"
else
    fail "the vendored tree carries a root build.gradle — it IS a Gradle module now"
fi
if ! grep -rq "gitsync" "$GRADLE" "$APP/settings.gradle" 2>/dev/null; then
    pass "cloud-drive's build never references the lib — a break there cannot fail this release (#254)"
else
    fail "cloud-drive's build references the vendored lib — it has veto power over the release (#254)"
fi

echo
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
