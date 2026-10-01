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
# mutate <label> <python expression editing d> [expected text]: the resolver must
# exit non-zero. `toolset` is store.json::toolset.binaries (#737). An edit that
# raises, or leaves the declaration byte-identical, is a mutation that did not
# mutate: it is a FAIL, never a pass on a stale copy from the previous round.
STORE_JSON="$ROOT/ab_cloud-terminal-store/store.json"
mutate() {
    rm -f "$T/mut.json"
    if ! python3 -c '
import json,sys
d=json.load(open(sys.argv[1])); shared=json.load(open(sys.argv[2]))
toolset=json.load(open(sys.argv[5]))["toolset"]["binaries"]
exec(sys.argv[3])
json.dump(d, open(sys.argv[4], "w"))' "$R/rootfs.json" "$T/shared.json" "$2" "$T/mut.json" "$STORE_JSON" 2>"$T/mut.err" \
       || python3 -c 'import json,sys; sys.exit(json.load(open(sys.argv[1])) != json.load(open(sys.argv[2])))' "$R/rootfs.json" "$T/mut.json"; then
        bad "$1: the mutation DID NOT MUTATE ($(head -c 160 "$T/mut.err"))"; return
    fi
    if ROOTFS_JSON="$T/mut.json" python3 "$R/resolve.py" check >"$T/out" 2>&1; then
        bad "$1: accepted — $(cat "$T/out")"
    elif [ -n "${3:-}" ] && ! grep -q "$3" "$T/out"; then
        bad "$1: refused for the wrong reason (wanted '$3') — $(tail -1 "$T/out")"
    else
        ok "$1: refused ($(tail -1 "$T/out" | cut -c1-110))"
    fi
}
mutate "a declared binary loses its install source" \
    'k=[k for k in d["tarballs"] if not k.startswith("_")][0]; del d["tarballs"][k]'
mutate "the default shell is not a declared binary" \
    'd["default_shell"]="/usr/bin/no-such-shell"' "default_shell"
mutate "a smoke command names an undeclared binary" \
    'd["smoke"]["planted"]=["no-such-tool","--version"]' "smoke.planted"
mutate "#737: a toolset tool loses its termux install source" \
    'del d["tarballs"]["fd"]' "'fd'"
mutate "#737: a toolset tool is restated in rootfs.json" \
    'd["binaries"].append(toolset[-1])' "store.json::toolset"
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
mutate "#737: store.json (the toolset) is not in the artifact identity" \
    'd["artifact"]["identity_files"]=[p for p in d["artifact"]["identity_files"] if not p.endswith("store.json")]' "store.json"
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
# `needs: rootfs`, or a list naming it (#636 added the run's build stamp beside
# it). rootfs_plan is not rootfs: the rootfs job's own `needs: rootfs_plan` must
# never be what satisfies this.
grep -qE 'needs: (rootfs|\[(.*[[:space:],])?rootfs(,.*)?\])$' "$WF" \
                                        && ok "publishing waits for the verified rootfs" || bad "build_and_publish does not need the rootfs job"
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

echo "── 5: #612/#730 the storage grant this target needs is asked for, and its absence is legible ──"
# No storage grant exists at install. Below target 30 (gradle.properties) the
# platform ignores All-Files-Access and only the legacy READ/WRITE runtime grant
# opens shared storage, so the app must ask for THAT; and enter.sh must not mount
# an empty tree, nor leave an empty mount point, in silence. The cross-app half
# (mount target == cloud-drive's store, mutation-proven) is
# ac_cloud-drive/test/test-drive-fresh-phone.sh.
ACT="$J/app/TermuxActivity.java"
grep -q 'getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.R' "$ACT" \
    && ok "TermuxActivity picks the storage grant by its target sdk" \
    || bad "TermuxActivity does not branch on its target sdk — a target-28 app asking only for All-Files-Access never mounts (#730)"
grep -q 'requestPermissions(legacy, PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION)' "$ACT" \
    && ok "TermuxActivity requests the legacy READ/WRITE grant" \
    || bad "TermuxActivity never requests the legacy storage grant"
grep -q 'storage access is not granted' "$R/enter.sh" \
    && ok "enter.sh prints a legible notice when the shared store is not readable" \
    || bad "enter.sh binds an unreadable shared store silently — no storage-access notice (#612)"
# #736: enter.sh decides the binds when a SESSION starts and its notice says to
# allow the prompt and open a new session, so the request belongs at every session
# start (addNewSession, ahead of createTermuxSession). Asked once per onCreate, a
# declined prompt never came back; asked in both places, the two requests collide
# on a fresh install and the second returns empty.
SC="$J/app/terminal/TermuxTerminalSessionClient.java"
session_start_rechecks() {  # $1 = session client, $2 = activity
    awk '/ void addNewSession\(/ {on=1; asked=0} on && /requestManageStorageIfNeeded\(\);/ {asked=1}
         on && /createTermuxSession\(/ {found=asked; exit} END {exit !found}' "$1" || return 1
    [ -z "$(grep -v '^[[:space:]]*//' "$2" | grep -E '^[[:space:]]*requestManageStorageIfNeeded\(\);')" ]
}
session_start_rechecks "$SC" "$ACT" \
    && ok "every session start re-checks storage before enter.sh runs, and the activity does not ask a second time" \
    || bad "storage is not re-checked at session start (or is also asked in onCreate) — a declined grant never comes back (#736)"
MUT_SC="$(mktemp)"; MUT_ACT="$(mktemp)"
grep -v 'mActivity.requestManageStorageIfNeeded();' "$SC" > "$MUT_SC"
awk '/TermuxUtils.sendTermuxOpenedBroadcast\(this\);/ && !done {print "        requestManageStorageIfNeeded();"; done=1} {print}' "$ACT" > "$MUT_ACT"
if cmp -s "$SC" "$MUT_SC" || cmp -s "$ACT" "$MUT_ACT"; then
    bad "MUTATION DID NOT APPLY: the #736 session-start mutations could not be planted"
elif session_start_rechecks "$MUT_SC" "$ACT"; then
    bad "MUTATION SURVIVED: dropping the session-start request left the pin green"
elif session_start_rechecks "$SC" "$MUT_ACT"; then
    bad "MUTATION SURVIVED: a second request in onCreate left the pin green"
else
    ok "mutation proved: no session-start request, or a duplicate onCreate request, each turns the pin red"
fi
rm -f "$MUT_SC" "$MUT_ACT"

echo "── 6: #628 the tree ships as the cloud-lib-rootfs-termux companion APK, and extraction is sha256-gated ──"
G="$APP/app/build.gradle"
CR="$J/cloud/CloudRootfs.java"
LG="$APP/rootfs-lib/build.gradle"
LM="$APP/rootfs-lib/src/main/AndroidManifest.xml"
P="$R/publish-artifact.sh"
digest_asset="$(json "$R/rootfs.json" 'd["artifact"]["digest_asset"]')"
url_asset="$(json "$R/rootfs.json" 'd["artifact"]["url_asset"]')"

[ -f "$P" ] && bad "rootfs/publish-artifact.sh is still here — #628 leaves exactly ONE way the rootfs arrives, and this was the #618 network path" \
            || ok "publish-artifact.sh is gone — no publish-then-fetch path is left to drift out of sync"
[ -f "$LG" ] && ok "the companion lib module exists: rootfs-lib/build.gradle" \
             || bad "rootfs-lib/build.gradle is missing — nothing builds the cloud-lib-rootfs-termux APK"
[ -f "$LM" ] && ok "the companion lib manifest exists: rootfs-lib/src/main/AndroidManifest.xml" \
             || bad "rootfs-lib/src/main/AndroidManifest.xml is missing"
grep -q "':rootfs-lib'" "$APP/settings.gradle" \
    && ok "settings.gradle includes :rootfs-lib" || bad "settings.gradle does not include :rootfs-lib"
grep -q "tasks.register('stageRootfsPayload')" "$G" \
    && ok "gradle stages the tarball into the companion lib before packaging" || bad "gradle has no stageRootfsPayload task"
grep -q "dependsOn 'stageRootfsPayload'" "$G" \
    && ok "verifyCloudRootfs runs after the stage" || bad "verifyCloudRootfs does not depend on stageRootfsPayload"
# THE point of #628: no rootfs bytes, no digest sidecar and no url sidecar left
# in the APP's own APK — all three moved into the companion lib.
grep -q "must carry none of it" "$G" \
    && ok "gradle refuses to build an APK with the #618 sidecars or the tarball still in assets/" \
    || bad "nothing stops the old scheme's leftovers being packaged again"
if grep -q 'FILES = {"proot", "enter.sh"}' "$CR"; then
    ok "the app copies only the small files out of its OWN assets/ — the tarball is not among them"
else
    bad "CloudRootfs still lists something beyond proot/enter.sh among its own assets"
fi
grep -q 'checkSignatures' "$CR" && grep -q 'SIGNATURE_MATCH' "$CR" \
    && ok "CloudRootfs refuses to unpack a companion signed with a different key" \
    || bad "CloudRootfs does not check the companion's signature — untrusted bytes could be unpacked and executed"
grep -q 'class LibMissing' "$CR" \
    && ok "a missing companion is a distinguishable LibMissing, not a generic IOException" \
    || bad "CloudRootfs has no LibMissing — the caller cannot offer the Store deep link"
# MUTATION PROOF. This is the assertion #618 and #628 both rest on, so it is
# run twice: once against the real file and once against a copy with the
# digest comparison deleted. A copy that still passes means the assertion
# checks nothing, and an unverified 400 MB payload could be unpacked without
# this tester noticing.
sha_gated() {
    grep -q 'DigestInputStream' "$1" && grep -q 'want.equals(got)' "$1" \
        && grep -q 'not the " + want' "$1"
}
sha_gated "$CR" \
    && ok "the extraction is gated on the companion's own declared sha256" \
    || bad "the extracted rootfs is not compared to the declared digest — unread bytes would be unpacked and executed"
MUT="$T/CloudRootfs-no-gate.java"
grep -v 'want.equals(got)' "$CR" > "$MUT"
if sha_gated "$MUT"; then
    bad "MUTATION SURVIVED: deleting the digest comparison left the assertion above green — it proves nothing"
else
    ok "mutation proved: deleting the digest comparison makes the assertion above go red"
fi
grep -q 'part.delete()' "$CR" \
    && ok "a digest mismatch leaves nothing behind" || bad "a rejected extraction is kept on disk"
# #628's whole point: nothing here ever opens a socket.
if grep -qE 'HttpURLConnection|java\.net\.URL' "$CR"; then
    bad "CloudRootfs.java still touches the network — #628 removes the #618 runtime fetch entirely"
else
    ok "CloudRootfs.java has no HTTP left in it — the rootfs is read out of the sibling APK, never fetched"
fi
grep -q 'cloudRootfsArtifact.digest_asset' "$G" && grep -q 'cloudRootfsArtifact.url_asset' "$G" \
    && ok "gradle still names the two #618 sidecars ($digest_asset, $url_asset) — only to assert their ABSENCE now" \
    || bad "the #618 sidecar names are not read from rootfs.json::artifact any more"

echo "── 5: #644 the declarative link store is wired to THIS terminal ──"
#
# The engine's own semantics (generations, atomic switch, rollback, verify,
# repair) are mutation-proved once, offline, in the #644 section of
# ac_cloud-nix-on-droid/test/test-bootstrap-baked.sh — one engine, one set of
# proofs. What can only be wrong HERE is the wiring, and every piece of it is a
# place a green tick could hide a store that never runs: staged but not bound,
# bound but never entered, entered but not required by the gradle gate.
S="$ROOT/ab_cloud-terminal-store"
E="$APP/enter.sh"; [ -f "$E" ] || E="$R/enter.sh"
G="$APP/app/build.gradle"

if [ -f "$S/cloud-store" ] && [ -f "$S/render-store.py" ]; then
    ok "the shared store exists at ab_cloud-terminal-store (ONE engine, two terminals that stay separate)"
else
    bad "ab_cloud-terminal-store is missing — build-rootfs.sh stages it into the APK assets"
fi

# The tool list is the one rootfs.json already keeps, so adding a tool is an edit
# to THAT list. Proved by equality, not by reading the renderer.
WANT="$(python3 -c 'import json,sys; print(" ".join(json.load(open(sys.argv[1]))["toolset"]["binaries"] + json.load(open(sys.argv[2]))["binaries"]))' "$STORE_JSON" "$R/rootfs.json")"
GOT="$(python3 "$S/render-store.py" termux 2>/dev/null | sed -n "s/^CLOUD_STORE_TOOLS='\(.*\)'$/\1/p")"
if [ -n "$GOT" ] && [ "$GOT" = "$WANT" ]; then
    ok "the rendered store declaration IS store.json::toolset + rootfs.json::binaries — a new tool is a data-only edit here too"
else
    bad "the store renders '$GOT' but toolset + rootfs.json::binaries is '$WANT' — the store would manage a different set of links than the rootfs installs"
fi

# No nix in this terminal, and the declaration must say so rather than imply one.
PM="$(python3 "$S/render-store.py" termux 2>/dev/null | sed -n "s/^CLOUD_STORE_PACKAGE_MANAGER='\(.*\)'$/\1/p")"
[ "$PM" = none ] && ok "this terminal declares package_manager 'none' — it has no nix, so the generations/rollback are implemented, not delegated" \
    || bad "the termux declaration claims package manager '$PM'; there is no nix in this rootfs for it to drive"

grep -q 'ab_cloud-terminal-store' "$R/build-rootfs.sh" \
    && ok "build-rootfs.sh stages the store beside enter.sh (APK assets, NOT the tarball — an engine fix must not cost a ~400 MB rebuild)" \
    || bad "build-rootfs.sh does not stage the store, so the assets gradle requires would never exist"

grep -q 'render-store.py' "$R/build-rootfs.sh" \
    && ok "build-rootfs.sh renders the declaration at build time, so it cannot drift from rootfs.json" \
    || bad "build-rootfs.sh ships no rendered declaration — the engine would have nothing to read"

grep -q 'mkdir -p /usr/lib/cloud-store' "$R/install-in-rootfs.sh" \
    && ok "install-in-rootfs.sh creates the bind mountpoint (proot binds onto an existing path; an empty dir is all the tarball carries)" \
    || bad "the rootfs has no /usr/lib/cloud-store mountpoint, so enter.sh's bind would have nowhere to land"

if grep -q 'b \$HERE/cloud-store:/usr/lib/cloud-store' "$E" && grep -q 'login-exec' "$E"; then
    ok "enter.sh binds the store and execs through login-exec, so every session initialises and verifies it"
else
    bad "enter.sh does not both bind the store and enter through login-exec — a staged store nothing runs is the #644 defect with extra steps"
fi

# Guarded, because a login is worth more than a store: #638 is what a boot path
# that can fail looks like from the owner's end.
grep -q '\[ -d "\$HERE/cloud-store" \]' "$E" \
    && ok "the store wiring is guarded on presence — an APK built before #644 still reaches a shell" \
    || bad "enter.sh wires the store unconditionally; a missing asset would cost the terminal its shell"

MISSING=""
for f in cloud-store declaration.sh login-init.sh login-exec; do
    grep -q "cloud-store/$f" "$G" || MISSING="$MISSING $f"
done
[ -z "$MISSING" ] && ok "verifyCloudRootfs requires all four store assets, so a staging failure is a red build not a silent loss" \
    || bad "app/build.gradle's asset gate does not require:$MISSING"

grep -q 'cloud-store' "$R/verify-rootfs.sh" \
    && ok "verify-rootfs.sh stages the store too, so the runtime half exercises the path the phone takes" \
    || bad "verify-rootfs.sh ignores the store — enter.sh would correctly decline to wire it and the runtime tester would pass over nothing"

grep -q '"ab_cloud-terminal-store/\*\*"' "$WF" \
    && ok "the ship workflow watches ab_cloud-terminal-store, so an engine fix starts a run and publishes" \
    || bad "$WF does not watch the shared store: an engine fix would sit inert behind a green tick"

echo "── 7: #736 a phone runs THIS APK's enter.sh, and shared storage has one entry ──"
# On a phone that had already staged the rootfs, isStaged() was true after every
# update (it compares only the companion lib's digest), so stage() never ran and
# the phone kept logging in through its FIRST enter.sh: the S21 showed exactly the
# empty mount-point dirs of an enter.sh several fixes old. The current-rootfs path
# must refresh enter.sh/proot before the terminal opens.
IN="$J/app/TermuxInstaller.java"
refreshes_when_staged() {  # $1 = TermuxInstaller.java
    awk '/private static void stageCloudRootfs\(/ {on=1}
         on && /CloudRootfs.isStaged\(activity\)/ {br=1}
         br && /CloudRootfs.refreshFiles\(activity\);/ {ref=1}
         br && /whenDone.run\(\);/ {found=ref; exit} END {exit !found}' "$1"
}
refreshes_when_staged "$IN" && grep -q 'Os.rename(tmp.getAbsolutePath(), target.getAbsolutePath())' "$CR" \
    && ok "a staged phone gets this APK's enter.sh/proot on every start, swapped in by rename" \
    || bad "an update that changes only enter.sh never reaches a phone that already staged the rootfs (#736)"
MUT="$T/TermuxInstaller-no-refresh.java"
grep -v 'CloudRootfs.refreshFiles(activity);' "$IN" > "$MUT"
if cmp -s "$IN" "$MUT"; then bad "MUTATION DID NOT APPLY: no refreshFiles call to remove"
elif refreshes_when_staged "$MUT"; then bad "MUTATION SURVIVED: without the refresh the pin stayed green"
else ok "mutation proved: dropping the refresh turns the pin red"; fi
# ~/emulated is the ONE entry for shared storage: the upstream ~/storage tree is not
# made by the app (setupStorageSymlinks) and enter.sh removes one an older version
# left. The runtime half (links resolve in the guest, ~/storage gone) is verify-rootfs.sh.
no_storage_tree() {  # $1 = TermuxInstaller.java, $2 = enter.sh
    ! awk '/static void setupStorageSymlinks\(/ {on=1} on && /Os.symlink\(/ {f=1} on && /^    }$/ {exit} END {exit !f}' "$1" \
        && grep -q 'for l in "$HOME/storage"/\*; do \[ ! -L "$l" \] || rm -f "$l"; done' "$2"
}
no_storage_tree "$IN" "$R/enter.sh" \
    && ok "no ~/storage tree is made, and enter.sh removes the one an older version left" \
    || bad "a second shared-storage entry (~/storage) is still made or kept beside ~/emulated (#736)"
sed 's|^        Logger.logInfo("termux-storage", "Shared storage is ~/emulated.*|        Os.symlink(Environment.getExternalStorageDirectory().getAbsolutePath(), new File(TermuxConstants.TERMUX_STORAGE_HOME_DIR, "shared").getAbsolutePath());|' "$IN" > "$MUT"
if cmp -s "$IN" "$MUT"; then bad "MUTATION DID NOT APPLY: setupStorageSymlinks could not be re-planted"
elif no_storage_tree "$MUT" "$R/enter.sh"; then bad "MUTATION SURVIVED: a re-planted ~/storage link stayed green"
else ok "mutation proved: a re-planted ~/storage link turns the pin red"; fi

echo
[ "$fail" -eq 0 ] && echo "PASS test-rootfs-declared" || echo "FAIL test-rootfs-declared"
exit "$fail"
