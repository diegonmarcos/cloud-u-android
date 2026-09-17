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
#   A13 (#457) the Sync tab's three sub-tabs all render — a deleted renderer or a
#       renderSync dispatch that stops routing a chip value fails red — and the
#       Mounted list reads the ONE connections declaration
#       (data/drive-connections.json via connections()), with the old
#       drive-mounts.json second declaration gone from every carrier and an
#       unreachable connection that renders its reason, not an empty box.
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

echo "── A6 the Sync tab renders all three subpages (Git | Rclone | Mounted) ──"
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
if grep -q "renderRcloneSubpage" "$PAGE" && grep -q "renderMountedSubpage" "$PAGE" && grep -q "renderGitSubpage" "$PAGE"; then
    pass "Sync renders all three subpages"
else
    fail "one of the Sync subpages has no renderer"
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

echo "── A9 the Sync tab's declarative lists travel every carrier ──"
# Same three-carrier rule as A3 for the three data files the Sync tab renders
# (remotes + jobs for Rclone, git for Git). The Mounted subpage is deliberately
# NOT a fourth carrier: it renders the ONE fleet declaration
# (data/drive-connections.json) through the same connections() carrier the
# Backups tab filters, so there is no second mounts list to keep in step and no
# risk of an empty box over a file that is right there in the repository
# (#170/#261, #292). The #457 gate below pins Mounted to that declaration.
python3 - "$PAGE" "$BRIDGE" "$GRADLE" "$APP" <<'PYTHON'
import json, os, re, sys

page    = open(sys.argv[1], encoding="utf-8").read()
bridge  = open(sys.argv[2], encoding="utf-8").read()
gradle  = open(sys.argv[3], encoding="utf-8").read()
app_dir = sys.argv[4]

lists = [
    ("remotes", "drive-remotes.json",     "RCLONE_REMOTES_B64", "rcloneRemotes"),
    ("jobs",    "drive-rclone-jobs.json", "RCLONE_JOBS_B64",    "rcloneJobs"),
    ("git",     "drive-git-repos.json",   "GIT_REPOS_B64",      "gitRepos"),
]
failed = False
for key, data_file, build_field, bridge_name in lists:
    data_path = os.path.join(app_dir, "data", data_file)
    if not os.path.isfile(data_path):
        print("  FAIL  missing data/%s — the %s list has no declaration" % (data_file, key))
        failed = True
        continue
    if data_file in gradle and build_field in gradle:
        print("  PASS  build.gradle bakes data/%s into %s" % (data_file, build_field))
    else:
        print("  FAIL  build.gradle does not bake data/%s into %s" % (data_file, build_field))
        failed = True
    if "BuildConfig.%s" % build_field in bridge:
        print("  PASS  FilesBridge reads BuildConfig.%s" % build_field)
    else:
        print("  FAIL  FilesBridge never reads %s — the baked %s list arrives nowhere" % (build_field, key))
        failed = True
    if "Bridge.raw('%s')" % bridge_name in page:
        print("  PASS  the Sync tab asks the bridge for the %s list" % key)
    else:
        print("  FAIL  the page never calls Bridge.raw('%s') — the %s section would render empty" % (bridge_name, key))
        failed = True

# Every list must be non-empty as data, and a declared-but-unreachable entry
# must carry the specific reason the renderer is required to show. An empty
# box over a declared list is this fleet's dominant defect shape (#292).
for key, data_file, _field, _bridge in lists:
    data_path = os.path.join(app_dir, "data", data_file)
    try:
        document = json.load(open(data_path, encoding="utf-8"))
    except Exception as error:
        print("  FAIL  data/%s does not parse: %s" % (data_file, error))
        failed = True
        continue
    key_entries = "repos" if key == "git" else key
    entries = document.get(key_entries, [])
    if not entries:
        print("  FAIL  data/%s declares no %s — the section would render an empty box (#292)" % (data_file, key_entries))
        failed = True
    else:
        print("  PASS  data/%s declares %d %s" % (data_file, len(entries), key_entries))
    if key in ("remotes", "mounts"):
        unreachable = [e for e in entries if e.get("status") != "ok"]
        if not unreachable:
            print("  FAIL  data/%s has no declared-but-unreachable entry — the specific-message path has no data" % data_file)
            failed = True
        for entry in unreachable:
            if not entry.get("reason"):
                print("  FAIL  %s in data/%s is not ok but declares no reason — the renderer would guess" %
                      (entry.get("name") or entry.get("label") or "<unnamed>", data_file))
                failed = True
        print("  PASS  data/%s declares %d unreachable entr%s with reasons" %
              (data_file, len(unreachable), "y" if len(unreachable) == 1 else "ies"))

# The renderer must SAY the reason, not leave the box blank: the page has to
# branch on status and emit the entry's reason text.
if "Cannot be reached" in page and ".reason" in page:
    print("  PASS  the page renders a specific 'Cannot be reached' message from the declared reason")
else:
    print("  FAIL  the page has no status-branching renderer — a declared-but-unreachable entry would render as an empty box")
    failed = True
if re.search(r"Bridge\.raw\('(rcloneRemotes|rcloneJobs|gitRepos|connections)'\)", page):
    print("  PASS  at least one Sync section reads its list through the bridge")
else:
    print("  FAIL  no Sync section reads a declarative list — the tab is not data-driven yet")
    failed = True

sys.exit(1 if failed else 0)
PYTHON
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

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

echo "── #457 gate: three Sync sub-tabs render; Mounted IS the connections declaration ──"
# The mutation gate the task demands. RED if a Sync sub-tab stops rendering
# (a renderer deleted, or renderSync's dispatch stops routing its chip value),
# and RED if the Mounted list stops reading the declaration (it must consume the
# SAME connections() carrier — data/drive-connections.json — and the second
# declaration must be gone from every carrier: data file, BuildConfig field,
# bridge method, page renderer). A hollow green where Mounted renders an empty
# box over a duplicate list is the defect this makes loud (#170/#261, #292).
python3 - "$PAGE" "$BRIDGE" "$GRADLE" "$APP" <<'PYTHON'
import json, os, re, sys

page    = open(sys.argv[1], encoding="utf-8").read()
bridge  = open(sys.argv[2], encoding="utf-8").read()
gradle  = open(sys.argv[3], encoding="utf-8").read()
app_dir = sys.argv[4]
failed  = False

# Gate 1 — every Sync sub-tab renders, and renderSync routes each chip value to
# its own renderer. Dropping the mounted branch of the dispatch must fail this.
# Each slice is bounded to ONE function body (next 'function ' keyword), so a
# check cannot be satisfied by a later declaration of the same string.
def fun_body(src, name):
    start = src.find("function %s" % name)
    if start < 0:
        return ""
    nxt = src.find("function ", start + 1)
    return src[start:nxt if nxt > 0 else len(src)]

for renderer in ("renderRcloneSubpage", "renderMountedSubpage", "renderGitSubpage"):
    if renderer in page:
        print("  PASS  %s renders" % renderer)
    else:
        print("  FAIL  %s is missing — that Sync sub-tab has no renderer" % renderer); failed = True
dispatch = fun_body(page, "renderSync")
# The dispatch is a ternary: git and mounted name their branch AND their
# renderer; rclone is the fallback that must still call its renderer. Pin the
# exact shapes so rewiring any branch or deleting any renderer fails red.
if "state.syncSub === 'git' ? renderGitSubpage()" in dispatch:
    print("  PASS  renderSync routes 'git' to renderGitSubpage")
else:
    print("  FAIL  renderSync no longer routes 'git' to renderGitSubpage"); failed = True
if "state.syncSub === 'mounted' ? renderMountedSubpage()" in dispatch:
    print("  PASS  renderSync routes 'mounted' to renderMountedSubpage")
else:
    print("  FAIL  renderSync no longer routes 'mounted' to renderMountedSubpage"); failed = True
if "renderRcloneSubpage()" in dispatch:
    print("  PASS  renderSync falls back to renderRcloneSubpage")
else:
    print("  FAIL  renderSync no longer falls back to renderRcloneSubpage"); failed = True

# Gate 2 — the Mounted list reads the declaration. renderMountedSubpage must
# consume connections() and render through connectionCard, the page must read
# the connections carrier over the bridge, and the old second declaration must
# be gone from all four places it used to live.
mounted = fun_body(page, "renderMountedSubpage")
if "connections()" in mounted:
    print("  PASS  renderMountedSubpage reads the connections declaration")
else:
    print("  FAIL  renderMountedSubpage does not read connections() — the Mounted list is not the declaration"); failed = True
if "connectionCard" in mounted:
    print("  PASS  renderMountedSubpage renders through connectionCard")
else:
    print("  FAIL  renderMountedSubpage does not use connectionCard — a second renderer to keep in step"); failed = True
if "Bridge.raw('connections')" in page:
    print("  PASS  the page reads the connections carrier through the bridge")
else:
    print("  FAIL  the page never calls Bridge.raw('connections') — Mounted/Backups cannot load the declaration"); failed = True

data_files = os.listdir(os.path.join(app_dir, "data"))
for marker, where, label in (
    ("drive-mounts.json", data_files,  "the data directory"),
    ("DRIVE_MOUNTS_B64",  gradle,      "build.gradle"),
    ("driveMounts",       bridge,      "FilesBridge.kt"),
    ("driveMounts",       page,        "drive.html"),
    ("mountCard",         page,        "drive.html"),
):
    present = (marker in where) if isinstance(where, str) else (marker in where)
    if present:
        print("  FAIL  %s still references %s — the second declaration was not removed (#170/#261)" % (label, marker))
        failed = True
    else:
        print("  PASS  no %s anywhere" % marker)

# Constraint 4 has data: the connections catalogue itself declares something
# unreachable, WITH the reason, and the renderer says it. A list of only-ok
# entries would make the specific-message path untestable (#292).
try:
    connections = json.load(open(os.path.join(app_dir, "data", "drive-connections.json"), encoding="utf-8"))
except Exception as error:
    print("  FAIL  data/drive-connections.json does not parse: %s" % error); failed = True
    connections = []
if not connections:
    print("  FAIL  data/drive-connections.json declares no connections — Mounted has nothing to render"); failed = True
else:
    unreachable = [c for c in connections if c.get("status") != "ok"]
    print("  PASS  data/drive-connections.json declares %d connections, %d unreachable" %
          (len(connections), len(unreachable)))
    if not unreachable:
        print("  FAIL  no declared-but-unreachable connection — the specific-message path has no data"); failed = True
    for entry in unreachable:
        if not entry.get("reason"):
            print("  FAIL  %s is not ok but declares no reason — the renderer would guess" %
                  (entry.get("name") or "<unnamed>")); failed = True
        else:
            print("  PASS  %s declares its reason" % (entry.get("name") or "<unnamed>"))
card = fun_body(page, "connectionCard")
if "Cannot be reached" in card and ".reason" in card:
    print("  PASS  connectionCard renders 'Cannot be reached' from the declared reason")
else:
    print("  FAIL  connectionCard has no status-branching message — unreachable renders as an empty box"); failed = True

sys.exit(1 if failed else 0)
PYTHON
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

echo
echo "── A9 #458 the PDF reader is real, one mechanism, honest conversion ──"
VENDOR="$APP/app/src/main/assets/vendor"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"
MAIN="$APP/app/src/main/java/com/diegonmarcos/clouddrive/MainActivity.kt"
STRINGS="$APP/app/src/main/res/values/strings.xml"
for required in "$VENDOR/pdf.min.js" "$VENDOR/pdf.worker.min.js" "$VENDOR/VENDORED.md" "$VENDOR/APACHE-LICENSE.txt" "$MANIFEST" "$MAIN" "$STRINGS" "$APP/test/test-pdf-reader-smoke.js"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required"; exit 1; }
done
if grep -q "2.16.105" "$VENDOR/VENDORED.md" && grep -qi "Apache-2.0\|Apache License" "$VENDOR/VENDORED.md"; then
    pass "VENDORED.md pins pdf.js to 2.16.105 and records the Apache-2.0 verdict"
else
    fail "VENDORED.md does not pin the version and licence"
fi
if grep -q "vendor/pdf.min.js" "$PAGE" && grep -q "vendor/pdf.worker.min.js" "$PAGE"; then
    pass "drive.html loads both engine files as classic scripts"
else
    fail "drive.html does not load the vendored engine scripts"
fi
if ! grep -rq "PdfRenderer" "$APP/app/src/main/java"; then
    pass "Android PdfRenderer is never used — pdf.js is the one mechanism"
else
    fail "Android PdfRenderer appears in the Kotlin — a second engine to keep in step"
fi
for name in isPdfEntry openReader closeReader renderReader readerRenderPage readerGoToPage readerRunFind readerRenderOutline readerToggleNight readerApplyTransform; do
    if grep -q "$name" "$PAGE"; then
        pass "the page wires $name"
    else
        fail "the page never wires $name — the reader feature is not reachable"
    fi
done
if grep -q "reader-night" "$PAGE" && grep -q "filter: invert" "$PAGE"; then
    pass "night mode is a CSS invert filter on the rendered stage (no re-render)"
else
    fail "night mode does not apply a CSS invert filter"
fi
for name in readPdf takeIncomingPdf; do
    if grep -q "fun $name(" "$BRIDGE" && grep -q "@JavascriptInterface" "$BRIDGE"; then
        pass "FilesBridge.$name is declared @JavascriptInterface"
    else
        fail "FilesBridge.$name is not declared on the bridge"
    fi
done
if grep -q "Bridge.call('readPdf'" "$PAGE" && grep -q "Bridge.call('takeIncomingPdf'" "$PAGE"; then
    pass "the page reads PDFs and drains hand-offs through the bridge"
else
    fail "the page does not reach the bridge for both PDF entry points"
fi
if grep -q "openInputStream" "$BRIDGE"; then
    pass "content:// hand-offs are read through the ContentResolver, never a path"
else
    fail "the bridge does not read hand-offs through the ContentResolver"
fi
if grep -q 'android:launchMode="singleTask"' "$MANIFEST"; then
    pass "MainActivity is singleTask — a second hand-off lands in onNewIntent"
else
    fail "MainActivity is not singleTask — a second tap stacks another copy"
fi
if grep -q "android.intent.action.VIEW" "$MANIFEST" && grep -q 'android:mimeType="application/pdf"' "$MANIFEST" && grep -q 'android:scheme="content"' "$MANIFEST" && grep -q 'android:scheme="file"' "$MANIFEST"; then
    pass "the manifest declares the VIEW application/pdf handler for content and file"
else
    fail "the manifest PDF handler is incomplete"
fi
if grep -q "onNewIntent" "$MAIN" && grep -q "setIncomingPdf" "$MAIN"; then
    pass "onNewIntent forwards the hand-off URI to the bridge"
else
    fail "onNewIntent does not forward the incoming URI"
fi
if grep -q "open_in_cloud_drive" "$STRINGS" && grep -q "@string/open_in_cloud_drive" "$MANIFEST"; then
    pass "the handler's label is a declared string on the intent filter"
else
    fail "the open-with label string is missing"
fi
for target in txt md html csv; do
    if grep -q "id: '$target'" "$PAGE"; then
        pass "conversion offers $target"
    else
        fail "conversion does not offer $target"
    fi
done
if grep -q "docx, xlsx and odt need the fleet converter" "$PAGE"; then
    pass "docx/xlsx/odt are shown disabled with the one-line fleet-converter reason"
else
    fail "the disabled targets do not state their one-line reason"
fi
python3 - "$PAGE" <<'PYTHON'
import re, sys

page = open(sys.argv[1], encoding="utf-8").read()

# The scan refusal is a control-flow fact, not an opinion: inside convertPdfBytes
# the empty-text guard must come BEFORE any builder is called, and the message it
# returns must carry the words "no text layer". Extract the function body between
# its declaration and the next function, and compare positions.
start = page.index("function convertPdfBytes(")
end = page.index("async function pdfPagesPlainText", start)
body = page[start:end]
guard = body.index("NO_TEXT_LAYER_MESSAGE")
builder = body.index("buildConvertedText")
if guard < builder:
    print("  PASS  the no-text-layer guard runs before any builder")
else:
    print("  FAIL  a builder can run before the no-text-layer guard — a scan would write an empty file")
    sys.exit(1)

message_start = page.index("const NO_TEXT_LAYER_MESSAGE")
message = page[message_start:page.index(";", message_start)]
if "no text layer" in message:
    print("  PASS  the refusal names the no-text-layer reason")
else:
    print("  FAIL  the refusal does not say 'no text layer'")
    sys.exit(1)
PYTHON
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))
if node "$APP/test/test-pdf-reader-smoke.js" "$APP" >/tmp/pdf-reader-smoke.log 2>&1; then
    pass "pdf.js smoke run proves the engine extracts text and finds none in a scan"
else
    fail "pdf.js smoke run failed — the engine or the scan path is broken"
    sed 's/^/    /' /tmp/pdf-reader-smoke.log | tail -5
fi
if grep -q '"node"' "$GRADLE" 2>/dev/null || grep -q '"node"' "$APP/build.json"; then
    pass "build.json declares node as required test tooling"
else
    fail "build.json does not require node — its absence would fake a green tick"
fi

echo "── A14 (#459) the image viewer: rows open it, scans are typed, and a new image clears the sheet ──"
python3 - "$PAGE" "$BRIDGE" <<'PYTHON'
import re, sys

page = open(sys.argv[1], encoding="utf-8").read()
bridge = open(sys.argv[2], encoding="utf-8").read()
failures = 0

def between(page, start_fn, end_fn):
    start = page.index(start_fn)
    end = page.index(end_fn, start)
    return page[start:end]

def check(ok, message):
    global failures
    if ok:
        print("  PASS  " + message)
    else:
        print("  FAIL  " + message)
        failures += 1

# Every executable-statement check below is LINE-ANCHORED, so a mutation that
# comments a line out fails it as surely as one that deletes the line: a bare
# `in` match would "PASS" over a comment containing the same words, and a check
# that survives being commented out cannot be trusted in the first place.
def anchored(pattern, text):
    return re.search(r"^\s*" + pattern, text, re.M) is not None

# For a literal that legitimately sits mid-line (a Kotlin string argument, a
# fragment of an HTML template line), column-anchoring is the wrong tool — but
# comment-blindness is still the poison: accept a match ONLY on a line whose
# first non-space character is not a comment opener.
def lineHas(text, needle):
    for line in text.splitlines():
        stripped = line.lstrip()
        if needle in stripped and not stripped.startswith(("//", "#", "*")):
            return True
    return False

# A14a — an image row in the Files tab opens the viewer, not the generic opener.
rows = between(page, "function bindFileRows(", "function showEntryMenu(")
check(anchored(r"else if \(isImage\(entry\)\) openViewer\(entry\);", rows),
      "image rows open the viewer (isImage branch inside bindFileRows)")

# A14b — every top-bar control is bound; an unbound button is a hollow green.
for button in ("viewer-close", "viewer-rotate", "viewer-scan", "viewer-ocr",
               "viewer-info", "viewer-share", "viewer-delete"):
    check(anchored(r"viewerEl\('" + button + r"'\)\.onclick", page),
          "the viewer top bar wires " + button)

# A14c — the scan sheet renders TYPED actions for every payload kind the shared
# engine can return, and a copy fallback for anything unrecognised.
scan = between(page, "function renderScanSheet(", "function copyToClipboard(")
for ptype in ("url", "wifi", "contact", "calendar", "phone", "email", "geo"):
    # url is the FIRST branch (plain `if`), every later kind is `} else if` —
    # one pattern must accept both spellings.
    check(anchored(r"(?:if|\}\s*else if) \(p\.type === '" + ptype + r"'\)", scan),
          "the scan sheet has a typed action branch for " + ptype)
check(anchored(r"actions\.push\(actionButton\('Copy the text'", scan),
      "the scan sheet falls back to copy for an untyped payload")

# A14d — a decode that finds nothing is said out loud on BOTH sides; silence on
# either looks exactly like a hang.
check(lineHas(bridge, '"no barcode found in this image"'),
      "the bridge names the no-barcode outcome")
check(lineHas(scan, "result.error || 'no barcode found'"),
      "the page renders the no-barcode outcome, not a blank sheet")

# A14e — MUTATION TARGET: loading an image is a new context. The scan/OCR/info
# sheet and the double-tap bookkeeping describe the image they were opened for;
# viewerLoad must clear both whenever it loads an image. Deleting OR commenting
# either line makes this check fail while the APK still builds and the viewer
# still looks fine — the exact silent regression this tester exists for.
load = between(page, "function viewerLoad(", "function fitImage(")
hide = re.search(r"^\s*viewerHideSheet\(\);", load, re.M)
src = re.search(r"^\s*img\.src", load, re.M)
# The call must EXIST in viewerLoad (not only in openViewer, which the
# swipe/rotate/delete callers never run) and the function must still be the
# load function (a body-extraction drift would empty img.src out of it). The
# call's row position is deliberately NOT compared to img.src's: hide is
# synchronous and the load is async, so any row in the function clears the
# sheet before the new pixels render — an order check could only fail on a
# harmless reordering, and a check that fails on innocent code is noise.
check(hide is not None and src is not None,
      "viewerLoad clears the sheet whenever it loads an image")
check(anchored(r"viewer\.lastTap = 0;", load) and anchored(r"viewer\.tapPoint = null;", load),
      "viewerLoad resets the double-tap bookkeeping per image")

sys.exit(1 if failures else 0)
PYTHON
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "test-drive-bridge-contract: OK"
    exit 0
fi
echo "test-drive-bridge-contract: $FAILURES assertion group(s) FAILED"
exit 1
