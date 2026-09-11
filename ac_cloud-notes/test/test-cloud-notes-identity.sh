#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ Cloud Notes is a COMPLETE rename of a vendored tree, and still   ║
# ║ carries its upstream attribution                                 ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. ac_cloud-notes is a one-shot vendoring of gsantner/markor
# with the package identity moved off net.gsantner. A rename like that fails in
# exactly two directions and neither one announces itself:
#
#   TOO LITTLE — a file the sweep missed keeps the upstream package. On a
#   compiled reference that is a build error, which CI catches; but in the
#   MANIFEST or in a resource it is not. The ContentProvider authority is the
#   expensive case: authorities are device-global, so an authority left on the
#   upstream string means INSTALL_FAILED_CONFLICTING_PROVIDER the day the owner
#   also has a real Markor installed — a failure that happens on HIS phone, at
#   install time, and never in CI.
#
#   TOO MUCH — the sweep also rewrote the attribution. Apache-2.0 §4(a)/(b)
#   requires the licence and the notices to travel with the code; owning the
#   fork does not change that. LICENSE.txt, UPSTREAM-README.md and
#   CONTRIBUTORS.md are therefore EXCLUDED from the rewrite by design, and this
#   tester is what makes that exclusion a fact rather than an intention.
#
# The assertions:
#
#   N1  applicationId + namespace + the manifest_package_id resValue all say
#       com.diegonmarcos.cloudnotes, and build.json agrees with all three. A
#       build.json that disagrees with gradle is how an app publishes under one
#       name and registers in the Constellation store under another.
#   N2  the upstream package appears NOWHERE the toolchain reads — app/src,
#       app/thirdparty and the three gradle files. Scoped to compiled inputs on
#       purpose: build.json, NOTICE.md and README.md legitimately NAME the old
#       package because they DOCUMENT the rename, and a repo-wide grep would
#       match that prose and report a defect that is actually the record of the
#       fix. The needle is assembled at runtime from two fragments so this
#       file's own source cannot satisfy the search it performs.
#   N3  the FileProvider authority is still the ${applicationId} PLACEHOLDER.
#       This is the assertion that keeps N2 honest: the authority is correct
#       today because it is derived, not because it was edited, and the way it
#       silently breaks is somebody "helpfully" expanding it to a literal.
#   N4  attribution survives: the three files exist, LICENSE.txt still carries
#       the verbatim Apache-2.0 body and still names the upstream author, and
#       NOTICE.md states the changes §4(b) asks for.
#   N5  the four files build.gradle's copyRepoFiles task copies into
#       res/raw exist at the tree root. Upstream's ROOT_TO_RAW_COPYFILES makes
#       every resource-generating task depend on that Copy, so a missing one
#       fails the build deep inside mergeResources with a message that names
#       none of this.
#   N6  build.json's gradle_task names a product flavor this tree actually
#       declares. Markor has THREE flavors, so the task must name one; a task
#       naming a flavor that does not exist is the "green run that published
#       nothing" shape.
#   N7  no committed blob approaches GitHub's 100MB per-blob cap.
#   N8  the launcher label is the rebrand, and is still translatable="false".
#   N9  the DEFAULT notebook folder is the declared constant and has no space
#       in it. Upstream derived that folder from app_name.toLowerCase(), which
#       is fine for a one-word upstream name and silently became
#       "Documents/cloud notes" the moment this app was rebranded — a wart the
#       rebrand CREATED, on the owner's phone, in a path. This pins the fix so a
#       later edit cannot quietly reintroduce the derivation.
#
# No ripgrep, deliberately: testers in this repository have passed only because
# ripgrep was absent and their call failed open. grep, awk and python3 only, and
# a missing file is FATAL rather than an empty read every assertion agrees with.

set -uo pipefail

APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILED=0
ok()  { printf '  PASS  %s\n' "$*"; }
bad() { printf '  FAIL  %s\n' "$*"; FAILED=1; }
# A file this tester needs and cannot read is a FAILURE, never a skip.
need() { [ -f "$1" ] || { bad "missing file: ${1#"$APP"/}"; return 1; }; return 0; }

PKG="com.diegonmarcos.cloudnotes"
# Assembled so this file's own bytes never contain the literal it hunts for.
OLD_PKG="$(printf 'net.%s' 'gsantner')"
OLD_PATH="$(printf 'net/%s' 'gsantner')"

GRADLE="$APP/app/build.gradle"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"
BUILDJSON="$APP/build.json"

echo "== N1: identity is $PKG in gradle AND build.json =="
if need "$GRADLE" && need "$BUILDJSON"; then
    for pat in "applicationId \"$PKG\"" "namespace '$PKG'" "\"manifest_package_id\", \"$PKG\""; do
        if grep -qF -- "$pat" "$GRADLE"; then ok "app/build.gradle declares: $pat"
        else bad "app/build.gradle does NOT declare: $pat"; fi
    done
    python3 - "$BUILDJSON" "$GRADLE" "$PKG" <<'PY' || FAILED=1
import json, re, sys
bj, gradle, pkg = sys.argv[1], sys.argv[2], sys.argv[3]
d = json.load(open(bj)); g = open(gradle, encoding='utf-8').read()
rc, ok = 0, True
def chk(c, m):
    global rc
    print(("  PASS  " if c else "  FAIL  ") + m)
    if not c: rc = 1
chk(d["android"]["application_id"] == pkg, "build.json android.application_id == %s" % pkg)
fk = list(k for k in d["forks"] if not k.startswith("_"))
chk(len(fk) == 1, "exactly one forks.<key> (single-app): %r" % fk)
chk(d["forks"][fk[0]]["app_id"] == pkg, "build.json forks.%s.app_id == %s" % (fk[0], pkg))
# versionCode / versionName in build.json must match the vendored gradle, or the
# Constellation store offers a version the APK does not claim.
mc = re.search(r'versionCode\s+(\d+)', g); mn = re.search(r'versionName\s+"([^"]+)"', g)
chk(mc is not None and int(mc.group(1)) == d["android"]["version_code"],
    "build.json version_code == gradle versionCode (%s)" % (mc.group(1) if mc else "ABSENT"))
chk(mn is not None and mn.group(1) == d["android"]["version_name"],
    "build.json version_name == gradle versionName (%s)" % (mn.group(1) if mn else "ABSENT"))
sys.exit(rc)
PY
fi

echo "== N2: the upstream package is absent from everything the toolchain reads =="
SCAN_ROOTS=("$APP/app/src" "$APP/app/thirdparty" "$APP/app/build.gradle" "$APP/build.gradle" "$APP/settings.gradle")
for r in "${SCAN_ROOTS[@]}"; do
    [ -e "$r" ] || { bad "scan root missing (cannot prove absence): ${r#"$APP"/}"; continue; }
done
if [ "$FAILED" = "0" ]; then
    HITS="$(grep -rlF -e "$OLD_PKG" -e "$OLD_PATH" "${SCAN_ROOTS[@]}" 2>/dev/null || true)"
    if [ -z "$HITS" ]; then ok "no occurrence of the upstream package under app/src, app/thirdparty or the gradle files"
    else bad "upstream package still present in:"; printf '        %s\n' $HITS; fi
fi

echo "== N3: the FileProvider authority is a placeholder, not a literal =="
if need "$MANIFEST"; then
    AUTH="$(awk 'match($0, /android:authorities="[^"]*"/) {
                     print substr($0, RSTART, RLENGTH); exit }' "$MANIFEST")"
    if [ -z "$AUTH" ]; then bad "no android:authorities in the manifest at all"
    elif [ "$AUTH" = 'android:authorities="${applicationId}.provider"' ]; then
        ok 'authority is ${applicationId}.provider — derived, so it follows applicationId'
    else bad "authority is not the applicationId placeholder: $AUTH"; fi
fi

echo "== N4: Apache-2.0 attribution survived the rename =="
for f in LICENSE.txt UPSTREAM-README.md CONTRIBUTORS.md NOTICE.md; do
    need "$APP/$f" && ok "present: $f"
done
if need "$APP/LICENSE.txt"; then
    grep -qF "Apache License" "$APP/LICENSE.txt" \
      && grep -qF "Version 2.0, January 2004" "$APP/LICENSE.txt" \
      && ok "LICENSE.txt still carries the verbatim Apache-2.0 body" \
      || bad "LICENSE.txt no longer carries the Apache-2.0 body"
    grep -qF "gsantner" "$APP/LICENSE.txt" \
      && ok "LICENSE.txt still names the upstream author" \
      || bad "LICENSE.txt no longer names the upstream author"
fi
if need "$APP/NOTICE.md"; then
    grep -qiF "changes" "$APP/NOTICE.md" \
      && ok "NOTICE.md states the changes Apache-2.0 4(b) requires" \
      || bad "NOTICE.md does not state the changes made"
fi

echo "== N5: copyRepoFiles inputs exist at the tree root =="
if need "$APP/build.gradle"; then
    # Read the list from upstream's own declaration rather than restating it,
    # so adding a file to ROOT_TO_RAW_COPYFILES cannot leave this check behind.
    LIST="$(awk -F'=' '/ROOT_TO_RAW_COPYFILES/ {print $2}' "$APP/build.gradle" \
            | tr -d '[];' | tr ',' '\n' | tr -d '" ' | grep -v '^$')"
    [ -n "$LIST" ] || bad "could not read ROOT_TO_RAW_COPYFILES out of build.gradle"
    for f in $LIST; do
        case "$f" in LICENSE.md|LICENSE) continue ;; esac  # upstream lists 3 spellings; it ships LICENSE.txt
        [ -f "$APP/$f" ] && ok "copyRepoFiles input present: $f" || bad "copyRepoFiles input MISSING: $f"
    done
fi

echo "== N6: build.json's gradle_task names a flavor this tree declares =="
if need "$BUILDJSON" && need "$GRADLE"; then
    python3 - "$BUILDJSON" "$GRADLE" <<'PY' || FAILED=1
import json, re, sys
d = json.load(open(sys.argv[1])); g = open(sys.argv[2], encoding='utf-8').read()
fk = [k for k in d["forks"] if not k.startswith("_")][0]
task = d["forks"][fk]["build"]["gradle_task"]
block = re.search(r'productFlavors\s*\{(.*?)\n    \}', g, re.S)
flavors = re.findall(r'^\s{8}(\w+)\s*\{', block.group(1), re.M) if block else []
print("  ..    declared flavors: %r ; gradle_task=%s" % (flavors, task))
if not flavors:
    print("  FAIL  could not parse productFlavors out of app/build.gradle"); sys.exit(1)
hit = [f for f in flavors if f.lower() in task.lower()]
if hit:
    print("  PASS  gradle_task names declared flavor %s" % hit[0]); sys.exit(0)
print("  FAIL  gradle_task %r names none of the declared flavors %r" % (task, flavors)); sys.exit(1)
PY
fi

echo "== N7: no committed blob approaches GitHub's 100MB per-blob cap =="
BIG="$(find "$APP" -type f -size +90M 2>/dev/null || true)"
if [ -z "$BIG" ]; then
    # awk keeps the running maximum instead of sort|head, which also died of
    # SIGPIPE here. -printf is GNU-only and BusyBox find prints NOTHING rather
    # than erroring, so an empty answer is reported as unknown, never as zero.
    LARGEST="$(find "$APP" -type f -printf '%s %p\n' 2>/dev/null \
               | awk '$1>m{m=$1;p=$2} END{if(m) print m, p}')"
    ok "largest blob: ${LARGEST:-unknown (find -printf unsupported)}"
else bad "blob(s) over 90MB:"; printf '        %s\n' $BIG; fi

echo "== N8: the launcher label is the rebrand and stays untranslated =="
NT="$APP/app/src/main/res/values/string-not_translatable.xml"
if need "$NT"; then
    grep -qF '<string name="app_name_real" translatable="false">Cloud Notes</string>' "$NT" \
      && ok 'app_name_real is "Cloud Notes" and translatable="false"' \
      || bad 'app_name_real is not the rebranded, translatable="false" value'
fi

echo "== N9: the default notebook folder is declared, not derived from the app name =="
APPSET="$APP/app/src/main/java/com/diegonmarcos/cloudnotes/model/AppSettings.java"
if need "$NT" && need "$APPSET"; then
    DIRNAME="$(awk -F'>' '/name="default_notebook_folder_name"/ {split($2,a,"<"); print a[1]}' "$NT")"
    if [ -z "$DIRNAME" ]; then
        bad "no default_notebook_folder_name string declared"
    else
        ok "default notebook folder declared as: $DIRNAME"
        case "$DIRNAME" in
            *\ *) bad "default notebook folder contains a space: '$DIRNAME'" ;;
            *)    ok  "default notebook folder has no space in it" ;;
        esac
    fi
    # Strip comments before matching: this file's own explanation of the
    # app_name derivation would otherwise satisfy a search meant to forbid it.
    BODY="$(sed -e 's://.*::' "$APPSET" | awk '!/^[[:space:]]*\*/ && !/^[[:space:]]*\/\*/')"
    # MATCHED WITH `case`, NOT `printf | grep -q`. This tester runs under
    # `set -o pipefail`, and `grep -q` exits the instant it matches, which sends
    # SIGPIPE to the still-writing printf; pipefail then reports the PIPELINE as
    # 141 and the successful match reads as a failure. It is timing-dependent,
    # so it passed on this container and failed on the GHA runner (run
    # 34599213929) with "printf: write error: Broken pipe" one line above a FAIL
    # for a string that was present the whole time. `case` needs no subprocess
    # and no pipe, so the whole class is gone rather than worked around.
    case "$BODY" in
        *"R.string.default_notebook_folder_name"*)
            ok "getDefaultNotebookFile() uses the declared constant" ;;
        *)  bad "getDefaultNotebookFile() does NOT use default_notebook_folder_name" ;;
    esac
    case "$BODY" in
        *"R.string.app_name).toLowerCase"*)
            bad "the default folder is STILL derived from app_name.toLowerCase()" ;;
        *)  ok "the default folder is no longer derived from app_name" ;;
    esac
fi

echo
[ "$FAILED" = "0" ] && { echo "test-cloud-notes-identity: ALL PASS"; exit 0; }
echo "test-cloud-notes-identity: FAILURES PRESENT"; exit 1
