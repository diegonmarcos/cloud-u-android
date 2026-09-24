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

echo
[ "$fail" -eq 0 ] && echo "PASS test-rootfs-declared" || echo "FAIL test-rootfs-declared"
exit "$fail"
