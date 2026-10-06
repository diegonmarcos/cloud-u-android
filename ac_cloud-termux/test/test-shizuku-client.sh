#!/bin/sh
# <shizuku-client> — both Cloud Terminals are Shizuku clients with `rish` in
# their rootfs, driven by ONE declarative block (build.json::shizuku_client) that
# the shared lib (libs:shizuku-adb-debug-tools) reads at build time and the login
# renders at enter time. Asserts, for ac_cloud-termux AND ac_cloud-nix-on-droid:
#   - the shizuku_client block exists, with the provider order as DATA
#     (moe.shizuku.privileged.api then com.diegonmarcos.superapp), the rish paths
#     and RISH_APPLICATION_ID, and the /usr/local/bin/rish + rish.env binds;
#   - the app declares rikka.shizuku.ShizukuProvider and queries the Shizuku pkg;
#   - the app requests Shizuku permission and exports rish on launch (RishBridge);
#   - the app links the lib (settings.gradle + app/build.gradle);
#   - rish + its env are bound into the rootfs on enter (termux: enter.sh +
#     install-in-rootfs.sh; nix: bake patch_bin_login_rish from the same binds[]);
# and once for the lib:
#   - build.gradle bakes the consumer's shizuku_client into BuildConfig;
#   - RishBridge decodes it, sets RISH_APPLICATION_ID and requests Shizuku;
#   - SuperappBridgeChannel is the superapp provider and the ladder orders by the
#     DATA (RishBridge.providers), not a hardcoded package list.
# grep-based; python3 only validates the JSON block. Any miss exits 1.
set -u

DIR="$(cd "$(dirname "$0")/.." && pwd)"                    # ac_cloud-termux
ANDROID="$(cd "$DIR/.." && pwd)"                           # cloud-u-android
TERMUX="$DIR"
NIX="$ANDROID/ac_cloud-nix-on-droid"
LIB="$ANDROID/ab_cloud-libs-shared/libs/shizuku-adb-debug-tools"

fails=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1"; fails=$((fails+1)); }
has()  { # file substring label
    if grep -qF -- "$2" "$1" 2>/dev/null; then ok "$3"; else bad "$3 [$2 not in ${1##*/}]"; fi
}

echo "── <shizuku-client>: declarative block + lib read ──"
for pair in "termux:$TERMUX:cld.termux" "nix:$NIX:cld.termux.nix"; do
    name=${pair%%:*}; rest=${pair#*:}; repo=${rest%:*}; appid=${rest##*:}
    BJ="$repo/build.json"
    echo "  [$name] $repo"

    # 1) the block, as DATA, with the provider ORDER and rish/bind fields
    python3 - "$BJ" "$appid" <<'PY'
import json,sys
bj,appid=sys.argv[1],sys.argv[2]
d=json.load(open(bj)).get("shizuku_client")
def chk(c,l):
    print(("  ok   " if c else "  FAIL ")+l)
    return 0 if c else 1
f=0
f+=chk(bool(d),"block present")
if d:
    p=d.get("providers") or []
    f+=chk(p[:2]==["com.diegonmarcos.superapp","moe.shizuku.privileged.api"],
           "providers order is DATA: superapp (primary) then shizuku (fallback)")
    r=d.get("rish") or {}
    f+=chk(r.get("application_id")==appid,"rish.application_id == %s"%appid)
    f+=chk(r.get("rootfs_bin")=="/usr/local/bin" and r.get("rootfs_lib")=="/usr/local/lib",
           "rish rootfs_bin/rootfs_lib declared")
    guests=[b.get("guest") for b in (d.get("binds") or [])]
    f+=chk("/usr/local/bin/rish" in guests and "/usr/local/etc/rish.env" in guests,
           "binds carry rish + rish.env guest paths")
    b=d.get("superapp_bridge") or {}
    f+=chk(b.get("port")==38080 and b.get("exec_path")=="/api/adb/exec",
           "superapp_bridge loopback route declared")
sys.exit(1 if f else 0)
PY
    [ $? -eq 0 ] || fails=$((fails+1))

    # 2) manifest: ShizukuProvider + Shizuku package query
    MF="$repo/app/src/main/AndroidManifest.xml"
    has "$MF" 'rikka.shizuku.ShizukuProvider' "[$name] manifest declares ShizukuProvider"
    has "$MF" 'moe.shizuku.privileged.api' "[$name] manifest queries the Shizuku package"

    # 3) launch hook: permission request + rish export
    AP="$repo/app/src/main/java/com/termux/app/TermuxApplication.java"
    has "$AP" 'RishBridge.INSTANCE.requestShizukuPermissionIfNeeded()' "[$name] requests Shizuku permission on launch"
    has "$AP" 'RishBridge.INSTANCE.export(' "[$name] exports rish on launch"

    # 4) the app links the shared lib
    has "$repo/settings.gradle" ':libs:shizuku-adb-debug-tools' "[$name] settings.gradle includes the lib"
    has "$repo/app/build.gradle" 'project(":libs:shizuku-adb-debug-tools")' "[$name] app depends on the lib"
done

echo "── rish reaches the rootfs on enter ──"
# termux: enter.sh binds + install-in-rootfs.sh mountpoints
has "$TERMUX/rootfs/enter.sh" '-b $HERE/rish/rish:/usr/local/bin/rish' "[termux] enter.sh binds rish"
has "$TERMUX/rootfs/enter.sh" '/usr/local/etc/rish.env' "[termux] enter.sh binds rish.env"
has "$TERMUX/rootfs/install-in-rootfs.sh" '/usr/local/bin/rish' "[termux] install-in-rootfs creates the rish mountpoint"
# nix: the bake renders the SAME binds[] into bin/login
has "$NIX/app/src/main/cpp/bake_default_packages.py" 'def patch_bin_login_rish' "[nix] bake defines patch_bin_login_rish"
has "$NIX/app/src/main/cpp/bake_default_packages.py" 'patch_bin_login_rish(bin_login, app_id)' "[nix] bake calls patch_bin_login_rish"
has "$NIX/app/src/main/cpp/bake_default_packages.py" 'shizuku_client' "[nix] bake reads the shizuku_client binds"

echo "── the shared lib reads the block ──"
has "$LIB/build.gradle" 'shizuku_client' "lib build.gradle reads build.json::shizuku_client"
has "$LIB/build.gradle" 'SHIZUKU_CLIENT_B64' "lib bakes SHIZUKU_CLIENT_B64 into BuildConfig"
RB="$LIB/src/main/java/com/diegonmarcos/superapp/adbdebug/RishBridge.kt"
has "$RB" 'BuildConfig.SHIZUKU_CLIENT_B64' "RishBridge decodes the baked block"
has "$RB" 'RISH_APPLICATION_ID=' "RishBridge writes RISH_APPLICATION_ID into rish.env"
has "$RB" 'ShizukuAdb.requestPermission' "RishBridge requests Shizuku permission"
has "$RB" 'FleetToken.get(ctx)' "RishBridge writes the fleet token (no secret in source)"
SB="$LIB/src/main/java/com/diegonmarcos/superapp/adbdebug/SuperappBridgeChannel.kt"
has "$SB" 'com.diegonmarcos.superapp' "SuperappBridgeChannel is the superapp provider"
has "$SB" 'Authorization' "SuperappBridgeChannel authenticates with the fleet token"
SC="$LIB/src/main/java/com/diegonmarcos/superapp/adbdebug/ShellChannel.kt"
has "$SC" 'RishBridge.providers' "the ladder orders channels by the DATA (provider order)"
has "$SC" 'SuperappBridgeChannel' "the ladder includes the superapp bridge channel"

echo
if [ "$fails" -eq 0 ]; then
    echo "ALL GREEN — both terminals are Shizuku clients with rish, one declarative block, lib reads it"
    exit 0
fi
echo "RED — $fails failing check(s)"
exit 1
