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

echo "── own shell server: R8 keep + self-bootstrap through the fallback ──"
# The PRIMARY channel is OUR app_process server (AdbShellServer). Two things
# kept it from ever coming up: R8 stripped the class (nothing in the app
# references it — app_process loads it by name), and only a PC running
# /api/adb/server-command could launch it. Now: the keep rule is GENERATED from
# the same build.json value the server-command bakes (one declaration), and the
# ladder launches the server through whichever other channel is up, once per
# boot (then only after it has stayed down >= 60 s), reporting the result.
SUPERAPP="$ANDROID/aa_cloud-superapp"
ADB="$LIB/src/main/java/com/diegonmarcos/superapp/adbdebug"
cls=$(python3 -c "import json;print(json.load(open('$SUPERAPP/build.json'))['shizuku_diagnostics']['local_server']['class'])" 2>/dev/null)
if [ -n "$cls" ]; then ok "build.json::local_server.class declares the server class"; else bad "build.json::local_server.class missing"; fi
if [ -f "$ADB/${cls##*.}.kt" ] && grep -q "^object ${cls##*.}" "$ADB/${cls##*.}.kt" && grep -q "fun main(args: Array<String>)" "$ADB/${cls##*.}.kt"; then
    ok "the declared class exists in the lib with a main(String[]) entry point"; else bad "declared class $cls has no object/main in the lib"; fi
has "$LIB/build.gradle" "adbServer['class']" "lib build.gradle reads local_server.class ONCE"
has "$LIB/build.gradle" 'ADB_SHELL_SERVER_CLASS", "\"${adbServerClass}' "the server-command bakes that value into BuildConfig"
has "$LIB/build.gradle" '-keep class ${adbServerClass} { public static void main(java.lang.String[]); }' "the R8 keep rule is generated from the SAME value"
has "$LIB/build.gradle" '-keep class ${adbServerClass}\$* { *; }' "nested classes of the server are kept too"
has "$LIB/build.gradle" "consumerProguardFiles 'consumer-rules.pro', adbServerKeep" "the generated rule ships as a consumer rule"
if grep -q "^-keep.*AdbShellServer" "$LIB/consumer-rules.pro"; then bad "consumer-rules.pro hardcodes the class (second declaration)"; else ok "consumer-rules.pro carries no second copy of the class name"; fi
has "$ADB/AdbShellBootstrap.kt" 'BuildConfig.ADB_SHELL_SERVER_CLASS' "the launch line names the class from BuildConfig, not a literal"
has "$ADB/AdbShellBootstrap.kt" 'export CLASSPATH=\$(pm path $pkg | cut -d: -f2); ' "launch line is split for a plain sh -c (export …; nohup …)"
has "$ADB/AdbShellBootstrap.kt" 'nohup app_process /system/bin --nice-name=' "launch line detaches app_process with nohup"
has "$ADB/AdbShellBootstrap.kt" 'fun ensureServer(ctx: Context, ladder: List<ShellChannel>)' "AdbShellBootstrap.ensureServer takes the ladder"
has "$ADB/AdbShellBootstrap.kt" 'if (listening && LocalShellChannel.probe(ctx)) return true' "bootstrap is a no-op only while the server ANSWERS (a listening socket is not a server)"
has "$ADB/AdbShellBootstrap.kt" 'it !== LocalShellChannel && it.isReady(ctx)' "bootstrap runs through the active NON-local channel"
has "$ADB/AdbShellBootstrap.kt" '"") + shellCommand(ctx)' "bootstrap runs the SAME launch line the server-command shows"
has "$ADB/AdbShellBootstrap.kt" "pkill -f 'superapp-ad[b]'; sleep 1; " "a listening-but-mute server (the previous APK's process, shell uid) is killed by nice-name through the fallback first"
has "$ADB/ShellChannel.kt" 'it !== LocalShellChannel || it.probe(ctx)' "active() asks the own server to execute before trusting its socket"
has "$ADB/LocalShellChannel.kt" 'override fun probe(ctx: Context)' "the own server has its own probe"
has "$ADB/LocalShellChannel.kt" 'soTimeout = 2_000' "that probe gives up in 2 s, not the exec's 15"
has "$ADB/AdbShellBootstrap.kt" 'SystemClock.elapsedRealtime()' "once-per-boot guard keys on elapsedRealtime (restarts at boot)"
has "$ADB/AdbShellBootstrap.kt" 'now - last < RETRY_MS) return false' "no retry storm: one attempt, again only after RETRY_MS"
has "$ADB/AdbShellBootstrap.kt" 'RETRY_MS = 60_000L' "retry window is 60 s"
has "$ADB/AdbShellBootstrap.kt" 'Log.i(TAG, "self-bootstrap $result")' "one log line per attempt"
has "$ADB/AdbShellBootstrap.kt" 'fun bootstrapState(ctx: Context): String' "bootstrap result is exposed as one short string"
has "$ADB/ShellChannel.kt" 'if (RishBridge.providers.isEmpty()) AdbShellBootstrap.ensureServer(ctx, ladder)' "the ladder's active() triggers the self-bootstrap — only in the app that owns the server (no shizuku_client block)"
has "$ADB/LocalShellChannel.kt" 'self-bootstrap ${AdbShellBootstrap.bootstrapState(ctx)}' "/api/adb/status local-server row says attempted + result"
has "$SUPERAPP/app/src/main/java/com/diegonmarcos/superapp/system/PrivilegedPlaneWorker.kt" 'ShellChannels.active(ctx)?.name()' "PrivilegedPlaneWorker consults the ladder at start"
has "$SUPERAPP/app/src/main/java/com/diegonmarcos/superapp/configs/PermissionsFragment.kt" 'AdbShellBootstrap.bootstrapState(ctxAny())' "Permissions page row shows the same bootstrap string"

echo
if [ "$fails" -eq 0 ]; then
    echo "ALL GREEN — both terminals are Shizuku clients with rish, one declarative block, lib reads it"
    exit 0
fi
echo "RED — $fails failing check(s)"
exit 1
