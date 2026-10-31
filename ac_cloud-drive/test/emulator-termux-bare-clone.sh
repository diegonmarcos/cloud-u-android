#!/usr/bin/env bash
# #1358 "bare clone in cld.termux failed (exit 1)", reproduced on an emulator with the real
# cld.termux (terminal + rootfs lib) and Cloud Drive, both release-signed (CLOUD_EXEC is signature-level).
#   1. BEFORE: the pre-#1353 job start (the script quoted twice, handed to root's login shell)
#      through cld.termux's own login path (terminal/exec = login -c -> enter.sh -> fish -c):
#      its exit code and fish's stderr are recorded; the failure is EXPECTED.
#   2. AFTER: Drive's Mirror all (bare) item path (git/bare = StoreGit.bareClone over CLOUD_EXEC)
#      for three public repositories; every one must report ok, a failure prints git's real line.
# Release builds serve their debug routes over the fleet IPC provider only (no TCP port), so the
# routes are called with `content call` as root (uid 0 passes the provider's signature check).
# Usage: emulator-termux-bare-clone.sh <drive.apk> <terminal.apk> <rootfs.apk> <out dir>
set -u
DRIVE_APK="$1"; TERM_APK="$2"; ROOTFS_APK="$3"; OUT="$4"
mkdir -p "$OUT"
DRIVE=com.diegonmarcos.clouddrive; TERMUX=cld.termux
REPOS="https://github.com/octocat/Hello-World.git,https://github.com/octocat/Spoon-Knife.git,https://github.com/octocat/git-consortium.git"
fail() { echo "::error title=cld.termux bare clone::$*"; adb exec-out screencap -p > "$OUT/fail.png" 2>/dev/null; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; exit 1; }
q() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }
# ipc <pkg> <op> [key=value ...]: one route over <pkg>.fleet; each value is staged as a device file first.
ipc() {
  local pkg="$1" op="$2" extras="" k v; shift 2
  for kv in "$@"; do
    k="${kv%%=*}"; v="${kv#*=}"
    # content's KEY:TYPE:VALUE parser splits on every unescaped colon: \ and : are escaped (a URL has colons).
    printf '%s' "$v" | sed 's/\\/\\\\/g; s/:/\\:/g' | adb shell "cat > /data/local/tmp/ipc_$k"
    extras="$extras --extra \"$k:s:\$(cat /data/local/tmp/ipc_$k)\""
  done
  adb shell "content call --uri content://$pkg.fleet --method fleet.ipc.v1 --arg $op$extras" 2>&1
}

adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
for a in "$TERM_APK" "$ROOTFS_APK" "$DRIVE_APK"; do
  timeout 600 adb install -r -g "$a" >"$OUT/install.log" 2>&1 || { cat "$OUT/install.log"; fail "install $(basename "$a") failed"; }
done
for p in "$TERMUX" "$DRIVE"; do
  adb shell pm list packages | grep -qx "package:$p" || fail "$p is not installed (package name changed?)"
  adb shell monkey -p "$p" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 8
done
adb shell dumpsys package $DRIVE | grep -E "CLOUD_EXEC|cld.termux.permission" | tee "$OUT/drive-perms.txt"

echo "── bootstrap cld.termux (Termux bootstrap + rootfs unpack) ──"
ipc $TERMUX terminal/exec "cmd=echo shell=\$SHELL; command -v git fish" timeout=600000 | tee "$OUT/bootstrap.json"; echo
grep -q 'status=200' "$OUT/bootstrap.json" && grep -q '"ok":true' "$OUT/bootstrap.json" || fail "cld.termux did not bootstrap: $(head -c 600 "$OUT/bootstrap.json")"

echo "── BEFORE: the pre-#1353 bare-clone job start through root's login shell ──"
G=/root/cloud-shared-store/git/_bare/octocat/Hello-World.git; D=/root/cloud-shared-store/.jobs; ID=before
S="set -e; mkdir -p \"\$(dirname $(q "$G"))\"; T=\"\$(cat)\"; if [ -n \"\$T\" ]; then H=\"Authorization: Basic \$(printf 'x-access-token:%s' \"\$T\" | base64 | tr -d '\\n')\"; git -c http.extraHeader=\"\$H\" clone --mirror --depth 1 'https://github.com/octocat/Hello-World.git' $(q "$G"); else git clone --mirror --depth 1 'https://github.com/octocat/Hello-World.git' $(q "$G"); fi"
START="mkdir -p $D && chmod 700 $D && cat > $D/$ID.in && ( nohup sh -c $(q "sh -c $(q "$S") < $D/$ID.in > $D/$ID.log 2>&1; echo \$? > $D/$ID.rc; rm -f $D/$ID.in") >/dev/null 2>&1 & )"
ipc $TERMUX terminal/exec "cmd=sh -c $(q "$START")" timeout=60000 | tee "$OUT/before.json"; echo
sleep 20
ipc $TERMUX terminal/exec "cmd=sh -c $(q "cat $D/$ID.rc $D/$ID.log 2>&1; ls -d $G 2>&1")" | tee "$OUT/before-job.json"; echo

echo "── AFTER: Drive's bare clone (StoreGit.bareClone over CLOUD_EXEC) for three public repositories ──"
ipc $DRIVE health | tee "$OUT/drive-health.json"; echo
ipc $DRIVE git/bare "url=$REPOS" depth=1 | tee "$OUT/after.json"; echo
adb exec-out screencap -p > "$OUT/after.png" 2>/dev/null
grep -q '"ok":true,"results"' "$OUT/after.json" || fail "a bare clone failed through Drive: $(head -c 1500 "$OUT/after.json")"
ipc $TERMUX terminal/exec "cmd=sh -c $(q "for m in /root/cloud-shared-store/git/_bare/octocat/*.git; do printf '%s ' \$m; git -C \$m rev-parse --is-bare-repository; done")" | tee "$OUT/mirrors.json"; echo
echo "cld.termux bare clone: all three mirrors cloned through Drive"
