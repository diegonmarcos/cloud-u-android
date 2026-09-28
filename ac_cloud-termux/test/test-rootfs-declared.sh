#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #470 — the agent-coding rootfs is ONE declaration, and it is wired in    ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# The runtime half of this tester is rootfs/verify-rootfs.sh: the ship
# workflow's rootfs job runs it against the tree it just built, on a runner of
# the APK's own CPU, through the exact proot + enter.sh the phone uses. It
# needs that built tree, so it cannot run here. This half runs in the shell
# phase, before anything is built, and fails on what would make that runtime
# half wrong or absent:
#
#   1. the declaration does not hold together — a declared binary no install
#      source provides, a default shell or smoke command naming an undeclared
#      binary, or a tool copied from the fleet tool belt instead of consumed;
#   2. the fleet tool belt is not actually consumed — every one of its
#      binaries must be in the resolved roster;
#   3. the guards in (1) are real — each is fed a declaration broken the way
#      it exists to catch and must refuse it;
#   4. the runtime tester, the build, the gradle gate and the Java that stages
#      and activates the rootfs are all wired to the same declaration.
#
# Everything is read from rootfs/rootfs.json, build.json and the pinned fleet
# file; nothing below names a tool. POSIX sh on purpose: every path it reads is
# this application's own (its tree and its ship workflow).
set -eu

APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
R="$APP/rootfs"
WF="$ROOT/1_cicd/src/cicd/ship-cloud-termux.yml"
fail=0
ok()  { echo "  ok   $*"; }
bad() { echo "  FAIL $*"; fail=1; }
json() { python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2]))" "$@"; }

T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT

echo "── 1: the merged declaration holds together ──"
# Fetched once at the pinned commit; every run below reuses the same bytes.
repo="$(json "$R/rootfs.json" 'd["shared_toolbelt"]["repo"]')"
ref="$(json "$R/rootfs.json" 'd["shared_toolbelt"]["ref"]')"
path="$(json "$R/rootfs.json" 'd["shared_toolbelt"]["path"]')"
curl -fsSL --retry 3 "https://raw.githubusercontent.com/$repo/$ref/$path" -o "$T/shared.json" \
    || { bad "cannot fetch the fleet tool belt $repo@$ref:$path"; exit 1; }
export SHARED_TOOLBELT_FILE="$T/shared.json"
if summary="$(python3 "$R/resolve.py" check 2>&1)"; then ok "$summary"; else bad "$summary"; fi

echo "── 2: the fleet tool belt is consumed, not copied ──"
python3 "$R/resolve.py" get binaries > "$T/resolved.json"
missing="$(python3 -c '
import json,sys
shared=json.load(open(sys.argv[1]))["binaries"]; got=json.load(open(sys.argv[2]))
print(" ".join(b for b in shared if b not in got))' "$T/shared.json" "$T/resolved.json")"
n_shared="$(json "$T/shared.json" 'len(d["binaries"])')"
[ -z "$missing" ] && ok "all $n_shared fleet binaries are in the resolved roster" \
                  || bad "fleet binaries missing from the resolved roster: $missing"

echo "── 3: each guard refuses the declaration it exists to catch ──"
# mutate <label> <python expression editing d>: the resolver must exit non-zero.
mutate() {
    python3 -c '
import json,sys
d=json.load(open(sys.argv[1])); shared=json.load(open(sys.argv[2]))
exec(sys.argv[3])
json.dump(d, open(sys.argv[4], "w"))' "$R/rootfs.json" "$T/shared.json" "$2" "$T/mut.json"
    if ROOTFS_JSON="$T/mut.json" python3 "$R/resolve.py" check >"$T/out" 2>&1; then
        bad "$1: accepted — $(cat "$T/out")"
    else
        ok "$1: refused ($(tail -1 "$T/out" | cut -c1-110))"
    fi
}
mutate "a declared binary loses its install source" \
    'k=[k for k in d["tarballs"] if not k.startswith("_")][0]; del d["tarballs"][k]'
mutate "the default shell is not a declared binary" \
    'import os; d["binaries"].remove(os.path.basename(d["default_shell"]))'
mutate "a smoke command names an undeclared binary" \
    'c=[v for k,v in d["smoke"].items() if not k.startswith("_")][0]; d["binaries"].remove(c[0]); d["tarballs"]={k:v for k,v in d["tarballs"].items() if c[0] not in v.get("provides",[k])}'
mutate "a fleet binary is repeated here" \
    'd["binaries"].append(shared["binaries"][0])'
mutate "the fleet pin is a branch, not a commit" \
    'd["shared_toolbelt"]["ref"]="main"'
mutate "the artifact asset name does not address its content (#618)" \
    'd["artifact"]["asset"]="cloud-rootfs-{abi}.tar.zst"'
mutate "the artifact url is written out instead of derived (#618)" \
    'd["artifact"]["url"]="https://github.com/diegonmarcos/cloud-u-android/releases/download/latest/x.tar.zst"'
mutate "rootfs.json is not in the artifact identity (#618)" \
    'd["artifact"]["identity_files"]=[p for p in d["artifact"]["identity_files"] if not p.endswith("rootfs.json")]'
mutate "an artifact identity file does not exist (#618)" \
    'd["artifact"]["identity_files"]=d["artifact"]["identity_files"]+["rootfs/nothing-here.sh"]'

echo "── 4: build, runtime tester, gradle and Java all read this declaration ──"
asset_dir="$(json "$R/rootfs.json" 'd["asset_dir"]')"
# Every APK variant has a rootfs to ship, and a native runner to prove it on.
for abi in $(json "$APP/build.json" '" ".join(v["abis"][0] for v in d["release"]["variants"])'); do
    runner="$(json "$R/rootfs.json" "d['abis'].get('$abi',{}).get('runner','')")"
    [ -n "$runner" ] && ok "variant $abi builds and verifies its rootfs on $runner" \
                     || bad "release variant $abi has no rootfs.json::abis entry — its APK would ship no rootfs"
done
grep -q 'rootfs/build-rootfs.sh' "$WF"  && ok "ship workflow builds the rootfs"       || bad "ship workflow never runs rootfs/build-rootfs.sh"
grep -q 'rootfs/verify-rootfs.sh' "$WF" && ok "ship workflow runs the runtime tester" || bad "ship workflow never runs rootfs/verify-rootfs.sh — the runtime tester would never execute"
grep -q 'needs: rootfs$' "$WF"          && ok "publishing waits for the verified rootfs" || bad "build_and_publish does not need the rootfs job"
grep -q 'CLOUD_ROOTFS_ASSET_DIR", "\\"${cloudRootfs.asset_dir}' "$APP/app/build.gradle" \
    && ok "gradle derives the asset dir from rootfs.json" || bad "app/build.gradle does not read asset_dir from rootfs.json"
grep -q "preBuild.dependsOn tasks.named('verifyCloudRootfs')" "$APP/app/build.gradle" \
    && ok "gradle refuses to build without the rootfs artefacts" || bad "nothing stops an APK without the rootfs"
if (cd "$ROOT" && git check-ignore -q "ac_cloud-termux/app/src/main/assets/$asset_dir/rootfs.tar.zst"); then
    ok "assets/$asset_dir/ is gitignored (hundreds of MB, CI-built)"
else
    bad "assets/$asset_dir/ is not gitignored — the rootfs would end up in git"
fi
J="$APP/app/src/main/java/com/termux"
[ "$(grep -c 'stageCloudRootfs(activity, whenDone)' "$J/app/TermuxInstaller.java")" -ge 2 ] \
    && ok "staging runs for an existing prefix AND after a fresh bootstrap" \
    || bad "TermuxInstaller stages the rootfs on only one path — installs that predate it would never get it (#436)"
grep -q 'Os.symlink(new File(dir, "enter.sh")' "$J/cloud/CloudRootfs.java" \
    && ok "the app points ~/.termux/shell at enter.sh" || bad "nothing makes the rootfs the login shell"
# enter.sh finds everything relative to itself, so the com.termux -> cld.termux
# rewrite (patch_bootstrap_ids.py) has nothing in it to rewrite or to miss.
ids="$(grep -v '^[[:space:]]*#' "$R/enter.sh" | grep -n '/data/data\|com\.termux\|cld\.termux' || true)"
[ -z "$ids" ] && ok "enter.sh hardcodes no app id or /data/data path" || bad "enter.sh hardcodes an app path: $ids"

echo "── 5: #612 All-Files-Access is asked for, and its absence is legible ──"
# All-Files-Access (MANAGE_EXTERNAL_STORAGE) is declared in the manifest but is a
# special access that is NOT granted at install and cannot be self-granted, so the
# app must send the user to the toggle and enter.sh must not mount an empty tree in
# silence. Both are greps over source: delete either and this section goes red.
ACT="$J/app/TermuxActivity.java"
grep -q 'Environment.isExternalStorageManager()' "$ACT" \
    && ok "TermuxActivity checks isExternalStorageManager() on launch" \
    || bad "TermuxActivity has no isExternalStorageManager() first-run check — the terminal never asks for All-Files-Access (#612)"
grep -q 'ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION' "$ACT" \
    && ok "TermuxActivity opens this app's All-Files-Access settings screen" \
    || bad "TermuxActivity never fires ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION — the check leads nowhere"
grep -q 'All-Files-Access' "$R/enter.sh" \
    && ok "enter.sh prints a legible notice when the shared store is not readable" \
    || bad "enter.sh binds an unreadable shared store silently — no All-Files-Access notice (#612)"

echo "── 6: #618 the tree is FETCHED, not bundled, and the fetch is sha256-gated ──"
G="$APP/app/build.gradle"
CR="$J/cloud/CloudRootfs.java"
P="$R/publish-artifact.sh"
digest_asset="$(json "$R/rootfs.json" 'd["artifact"]["digest_asset"]')"
url_asset="$(json "$R/rootfs.json" 'd["artifact"]["url_asset"]')"

[ -f "$P" ] && ok "the publish step exists: rootfs/publish-artifact.sh" \
            || bad "nothing publishes the rootfs tarball — it could only ship inside the APK"
grep -q 'already published' "$P" \
    && ok "an already-published asset keeps its hosted bytes (a rebuild cannot move them under a shipped APK)" \
    || bad "publish-artifact.sh re-uploads the same asset name — phones would be told a digest the host no longer serves"
grep -q "tasks.register('publishCloudRootfs')" "$G" \
    && ok "gradle publishes the tarball before packaging" || bad "gradle has no publishCloudRootfs task"
grep -q "dependsOn 'publishCloudRootfs'" "$G" \
    && ok "verifyCloudRootfs runs after the publish" || bad "verifyCloudRootfs does not depend on publishCloudRootfs"
# THE point of #618: no rootfs bytes in the APK. The gradle gate must refuse a
# tarball left in assets/, and the Java must not list it among the assets it
# copies out (it cannot, it is not there).
grep -q "would bundle the rootfs it is supposed to fetch" "$G" \
    && ok "gradle refuses to build an APK with the tarball still in assets/" \
    || bad "nothing stops the tarball being packaged again — the APK would be 400 MB (#618)"
if grep -q "FILES = {\"proot\", \"enter.sh\", URL_ASSET}" "$CR"; then
    ok "the app copies only the small files out of assets/"
else
    bad "CloudRootfs still lists the tarball among its assets — it is not in the APK any more"
fi
# MUTATION PROOF. This is the assertion #618 rests on, so it is run twice: once
# against the real file and once against a copy with the digest comparison
# deleted. A copy that still passes means the assertion checks nothing, and an
# unverified 400 MB download could land without this tester noticing.
sha_gated() {
    grep -q 'DigestInputStream' "$1" && grep -q 'want.equals(got)' "$1" \
        && grep -q 'not the " + want' "$1"
}
sha_gated "$CR" \
    && ok "the fetch is gated on the baked sha256" \
    || bad "the fetched rootfs is not compared to the baked digest — unread bytes would be unpacked and executed (#618)"
MUT="$T/CloudRootfs-no-gate.java"
grep -v 'want.equals(got)' "$CR" > "$MUT"
if sha_gated "$MUT"; then
    bad "MUTATION SURVIVED: deleting the digest comparison left the assertion above green — it proves nothing"
else
    ok "mutation proved: deleting the digest comparison makes the assertion above go red"
fi
grep -q 'part.delete()' "$CR" \
    && ok "a digest mismatch leaves nothing behind" || bad "a rejected download is kept on disk"
# The url and the digest reach the phone as ASSETS written by the publish step,
# so neither this APK's Java nor its gradle names a host.
# grep -n prefixes every line with "<n>:", so a comment filter anchored at ^ must
# allow for it — anchoring at '^\s*\*' matches nothing and passes whatever the
# file says, the same hollow-green shape the sibling tester documents.
if grep -n 'https\?://' "$CR" | grep -vE '^[0-9]+:[[:space:]]*(\*|//|/\*)' | grep -q .; then
    bad "CloudRootfs.java hardcodes a url — it must read $url_asset, written from the declaration"
else
    ok "CloudRootfs.java hardcodes no url (it reads $url_asset)"
fi
grep -q 'cloudRootfsArtifact.repo' "$G" && grep -q 'cloudRootfsArtifact.tag' "$G" \
    && ok "gradle reads the repo and tag from rootfs.json::artifact" \
    || bad "app/build.gradle does not read artifact.repo/tag from the declaration"
grep -q 'cloudRootfsArtifact.digest_asset' "$G" && grep -q 'cloudRootfsArtifact.url_asset' "$G" \
    && ok "gradle names the two baked files ($digest_asset, $url_asset) from the declaration" \
    || bad "the digest/url asset names are not read from rootfs.json::artifact"

echo
[ "$fail" -eq 0 ] && echo "PASS test-rootfs-declared" || echo "FAIL test-rootfs-declared"
exit "$fail"
