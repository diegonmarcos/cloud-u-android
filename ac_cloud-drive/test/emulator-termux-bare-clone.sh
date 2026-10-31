#!/usr/bin/env bash
# #1358 "bare clone in cld.termux failed (exit 1)", reproduced on an emulator with the real
# cld.termux (terminal + rootfs lib), SuperApp (the fleet token authority) and Cloud Drive.
#   1. BEFORE: the pre-#1353 job start (the script quoted twice, handed to root's login shell)
#      through cld.termux's own login path (/api/terminal/exec = login -c -> enter.sh -> fish -c):
#      its exit code and fish's stderr are recorded, the failure is EXPECTED.
#   2. AFTER: Drive's Mirror all (bare) item path (/api/git/bare = StoreGit.bareClone over CLOUD_EXEC)
#      for three public repositories; every one must report ok, a failure prints git's real line.
# Usage: emulator-termux-bare-clone.sh <drive.apk> <terminal.apk> <rootfs.apk> <superapp.apk> <out dir>
set -u
DRIVE_APK="$1"; TERM_APK="$2"; ROOTFS_APK="$3"; SUPER_APK="$4"; OUT="$5"
mkdir -p "$OUT"
DRIVE=com.diegonmarcos.clouddrive; TERMUX=cld.termux; SUPER=com.diegonmarcos.superapp
DRIVE_PORT=38154; TERMUX_PORT=38167
REPOS="https://github.com/octocat/Hello-World.git,https://github.com/octocat/Spoon-Knife.git,https://github.com/octocat/git-consortium.git"
fail() { echo "::error title=cld.termux bare clone::$*"; adb exec-out screencap -p > "$OUT/fail.png" 2>/dev/null; adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null; exit 1; }
q() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
for a in "$SUPER_APK" "$TERM_APK" "$ROOTFS_APK" "$DRIVE_APK"; do
  timeout 600 adb install -r -g "$a" >"$OUT/install.log" 2>&1 || { cat "$OUT/install.log"; fail "install $(basename "$a") failed"; }
done
for p in "$SUPER" "$TERMUX" "$DRIVE"; do
  adb shell pm list packages | grep -qx "package:$p" || fail "$p is not installed (package name changed?)"
  adb shell monkey -p "$p" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 8
done
adb forward tcp:$DRIVE_PORT tcp:$DRIVE_PORT >/dev/null; adb forward tcp:$TERMUX_PORT tcp:$TERMUX_PORT >/dev/null

# Any guarded request makes a member adopt the token from SuperApp, which mints it then; root reads it back.
curl -s -m 20 "http://127.0.0.1:$DRIVE_PORT/api/health" >/dev/null || true
TOKEN=""
for _ in $(seq 1 20); do
  TOKEN="$(adb shell cat /data/data/$SUPER/shared_prefs/dev_control.xml 2>/dev/null | sed -n 's/.*name="token">\([^<]*\)<.*/\1/p')"
  [ -n "$TOKEN" ] && break; sleep 3
done
[ -n "$TOKEN" ] || fail "SuperApp minted no fleet token"
echo "::add-mask::$TOKEN"
api() { curl -s -m "$2" -G -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$1/api/$3" "${@:4}"; }

echo "── bootstrap cld.termux (Termux bootstrap + rootfs unpack) ──"
api $TERMUX_PORT 960 terminal/exec --data-urlencode "cmd=echo shell=\$SHELL; command -v git fish" --data-urlencode timeout=600000 | tee "$OUT/bootstrap.json"; echo
grep -q '"ok":true' "$OUT/bootstrap.json" || fail "cld.termux did not bootstrap: $(head -c 600 "$OUT/bootstrap.json")"

echo "── BEFORE: the pre-#1353 bare-clone job start through root's login shell ──"
G=/root/cloud-shared-store/git/_bare/octocat/Hello-World.git; D=/root/cloud-shared-store/.jobs; ID=before
S="set -e; mkdir -p \"\$(dirname $(q "$G"))\"; T=\"\$(cat)\"; if [ -n \"\$T\" ]; then H=\"Authorization: Basic \$(printf 'x-access-token:%s' \"\$T\" | base64 | tr -d '\\n')\"; git -c http.extraHeader=\"\$H\" clone --mirror --depth 1 'https://github.com/octocat/Hello-World.git' $(q "$G"); else git clone --mirror --depth 1 'https://github.com/octocat/Hello-World.git' $(q "$G"); fi"
START="mkdir -p $D && chmod 700 $D && cat > $D/$ID.in && ( nohup sh -c $(q "sh -c $(q "$S") < $D/$ID.in > $D/$ID.log 2>&1; echo \$? > $D/$ID.rc; rm -f $D/$ID.in") >/dev/null 2>&1 & )"
api $TERMUX_PORT 120 terminal/exec --data-urlencode "cmd=sh -c $(q "$START")" --data-urlencode timeout=60000 | tee "$OUT/before.json"; echo
sleep 20
api $TERMUX_PORT 60 terminal/exec --data-urlencode "cmd=sh -c $(q "cat $D/$ID.rc $D/$ID.log 2>&1; ls -d $G 2>&1")" | tee "$OUT/before-job.json"; echo

echo "── AFTER: Drive's bare clone (StoreGit.bareClone over CLOUD_EXEC) for three public repositories ──"
api $DRIVE_PORT 60 health | tee "$OUT/drive-health.json"; echo
api $DRIVE_PORT 900 git/bare --data-urlencode "url=$REPOS" --data-urlencode depth=1 | tee "$OUT/after.json"; echo
adb exec-out screencap -p > "$OUT/after.png" 2>/dev/null
grep -q '"ok":true,"results"' "$OUT/after.json" || fail "a bare clone failed through Drive: $(head -c 1500 "$OUT/after.json")"
api $TERMUX_PORT 60 terminal/exec --data-urlencode "cmd=sh -c $(q "for m in /root/cloud-shared-store/git/_bare/octocat/*.git; do printf '%s ' \$m; git -C \$m rev-parse --is-bare-repository; done")" | tee "$OUT/mirrors.json"; echo
echo "cld.termux bare clone: all three mirrors cloned through Drive"
