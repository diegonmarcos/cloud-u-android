#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #1121 — an update unpacks the bundled files by itself, with no UI          ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# Rules: (U1) MY_PACKAGE_REPLACED reaches UnpackReceiver, which starts UnpackService; (U2) the
# service is exported ONLY behind the fleet's signature TERMINAL_SESSION permission, with the one
# UNPACK action Cloud Store names; (U3) it unpacks through TerminalDebugApi.prepare (what the first
# start does), writes files/unpack-result.json and hands the outcome to the starter's
# ResultReceiver; (U4) it is a foreground service started from the receiver with no activity
# involved; (U5) the three Java files are the same in both terminals. Each rule is then broken once
# in a scratch copy (mutants) and must go red. The emulator half is
# 1_cicd/src/cicd/test-terminal-upgrade-unpack.yml.
#
# Static, offline, no Android SDK. Byte-identical in ac_cloud-termux and ac_cloud-nix-on-droid.
set -u

DIR="$(cd "$(dirname "$0")/.." && pwd)"
SIB=""
for d in "$DIR/../ac_cloud-termux" "$DIR/../ac_cloud-nix-on-droid"; do
    [ "$(cd "$d" 2>/dev/null && pwd)" != "$DIR" ] && [ -d "$d" ] && SIB="$d"
done

check() {  # $1 = manifest, $2 = receiver, $3 = service, $4 = report; prints violations
    python3 - "$@" <<'PY'
import re, sys
man, rcv, svc, rep = sys.argv[1:5]
man, rcv, svc, rep = (open(p).read() for p in (man, rcv, svc, rep))
bad = []
m = re.search(r'<receiver[^>]*UnpackReceiver"[^>]*>(.*?)</receiver>', man, re.S)
if not m or "android.intent.action.MY_PACKAGE_REPLACED" not in m.group(1):
    bad.append("U1 UnpackReceiver is not declared for MY_PACKAGE_REPLACED")
if m and 'exported="false"' not in re.search(r'<receiver[^>]*UnpackReceiver"[^>]*>', man).group(0):
    bad.append("U1 UnpackReceiver must not be exported")
if "UnpackService.start(" not in rcv:
    bad.append("U1 UnpackReceiver does not start UnpackService")
s = re.search(r'<service[^>]*UnpackService"[^>]*>(.*?)</service>', man, re.S)
if not s:
    bad.append("U2 UnpackService is not declared")
else:
    tag = re.search(r'<service[^>]*UnpackService"[^>]*>', man).group(0)
    if 'android:permission="com.diegonmarcos.cloud.permission.TERMINAL_SESSION"' not in tag:
        bad.append("U2 UnpackService is not guarded by the signature TERMINAL_SESSION permission")
    if "com.diegonmarcos.cloud.action.UNPACK" not in s.group(1):
        bad.append("U2 UnpackService does not answer com.diegonmarcos.cloud.action.UNPACK")
if 'ACTION = "com.diegonmarcos.cloud.action.UNPACK"' not in rep:
    bad.append("U2 UnpackReport.ACTION drifted from the action the Store and the IPC registry name")
if "TerminalDebugApi.prepare(" not in svc:
    bad.append("U3 UnpackService does not unpack through TerminalDebugApi.prepare")
if "UnpackReport.FILE" not in svc or "receiver.send(" not in svc:
    bad.append("U3 UnpackService does not write its result file and answer the ResultReceiver")
if "startForeground(" not in svc or "startForegroundService(" not in svc:
    bad.append("U4 UnpackService is not a foreground service started with startForegroundService")
if re.search(r"startActivity|Activity\.class", rcv + svc):
    bad.append("U4 the unpack opens an activity: it must need no UI")
print("\n".join(bad))
PY
}

J="app/src/main/java/com/termux"
real() { check "$DIR/app/src/main/AndroidManifest.xml" "$DIR/$J/app/UnpackReceiver.java" "$DIR/$J/app/UnpackService.java" "$DIR/$J/cloud/UnpackReport.java"; }

fail=0
out="$(real)"
if [ -n "$out" ]; then echo "$out" | sed 's/^/FAIL /'; fail=1; fi
for f in app/UnpackReceiver.java app/UnpackService.java cloud/UnpackReport.java; do
    if [ -n "$SIB" ] && ! cmp -s "$DIR/$J/$f" "$SIB/$J/$f"; then echo "FAIL U5 $f differs between the two terminals"; fail=1; fi
done

# Mutants: break one rule at a time in a scratch copy; each must be reported.
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
mutant() {  # $1 = label, $2 = file kind (man|rcv|svc|rep), $3 = sed expression
    cp "$DIR/app/src/main/AndroidManifest.xml" "$T/man"; cp "$DIR/$J/app/UnpackReceiver.java" "$T/rcv"
    cp "$DIR/$J/app/UnpackService.java" "$T/svc"; cp "$DIR/$J/cloud/UnpackReport.java" "$T/rep"
    sed -i "$3" "$T/$2"
    if [ -z "$(check "$T/man" "$T/rcv" "$T/svc" "$T/rep")" ]; then echo "FAIL mutant not caught: $1"; fail=1; fi
}
mutant "receiver loses MY_PACKAGE_REPLACED" man '/UnpackReceiver"/,/<\/receiver>/s/MY_PACKAGE_REPLACED/BOOT_COMPLETED/'
mutant "service loses its permission" man 's#android:permission="com.diegonmarcos.cloud.permission.TERMINAL_SESSION">#>#;t;'
mutant "receiver stops starting the service" rcv 's/UnpackService.start(/noop(/'
mutant "service stops unpacking" svc 's/TerminalDebugApi.prepare(/noop(/'
mutant "service stops answering the starter" svc 's/receiver.send(/noop(/'
mutant "service opens an activity" svc 's/startForeground(NOTIFICATION_ID/startActivity(NOTIFICATION_ID/'
mutant "action drifts" rep 's/cloud.action.UNPACK"/cloud.action.UNPACKED"/'

[ "$fail" = 0 ] && echo "ok: an update unpacks by itself (U1-U5 hold, 7 mutants caught)"
exit "$fail"
