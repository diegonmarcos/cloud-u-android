#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #567 push 4 — libs:mounts speaks every declared mount type through a     ║
# ║ maintained library, trusts host keys on first use only, and every verb  ║
# ║ of the file-system contract is reached from the browser                 ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# The pure half (URI ↔ spec, store) is proven by MountsEngineTest and the client by EngineFsTest in every
# drive ship. #871 move 12: the protocol clients (sshj, commons-net, OkHttp), the host-key verifier and the
# WebDAV parser moved to libs:mounts-engine (Cloud-Lib-Mounts-Engine.apk); their checks (every MountType has an
# engine, every verb on every engine class, TOFU and no Promiscuous, the maintained libraries) moved to
# ab_cloud-libs-shared/lib-apks/test/test-mounts-engine.sh. What only a tester over THIS app's module can see:
#
#   M2  every RemoteFs verb (list, download, upload, mkdir, delete, rename, exec) is implemented by the
#       client (EngineFs) and reached from the screen.
#   M4  the app carries no protocol library and shells out to nothing: no sshj, commons-net or OkHttp in
#       libs:mounts, no ProcessBuilder / Runtime.exec anywhere in the module.
#   M5  the pure half (models, RemoteFs, the wire) imports nothing from android.*.
#
# OWN-SOURCE ONLY: everything read is under a module dir this app's
# build.json declares.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
MOD="$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:mounts"]["dir"])))' "$APP/build.json" "$APP")"
PKG="$MOD/src/main/java/com/diegonmarcos/cloudlib/mounts"
MODELS="$PKG/MountModels.kt"; FS="$PKG/RemoteFs.kt"; ENGINES="$PKG/EngineFs.kt"; SCREEN="$PKG/MountsScreen.kt"; WIRE="$PKG/MountWire.kt"
TEST="$MOD/src/test/java/com/diegonmarcos/cloudlib/mounts/EngineFsTest.kt"
GRADLE="$MOD/build.gradle"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$MODELS" "$FS" "$ENGINES" "$SCREEN" "$WIRE" "$TEST" "$GRADLE"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required"; exit 1; }
done

echo "── M2 the RemoteFs contract: every verb on the client, reached from the screen ──"
VERBS="$(python3 - "$FS" <<'PYTHON'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
body = re.search(r"interface RemoteFs\b.*?\{(.*?)\n\}", text, re.S)
print(" ".join(re.findall(r"^\s*fun (\w+)\(", body.group(1), re.M)) if body else "")
PYTHON
)"
[ -n "$VERBS" ] && pass "RemoteFs verbs: $VERBS" || fail "RemoteFs declares no verbs"
grep -qE "class EngineFs\b" "$ENGINES" && pass "client EngineFs exists" || fail "client EngineFs missing"
for verb in $VERBS; do
    n="$(grep -cE "override fun $verb\(" "$ENGINES")"
    [ "$n" -eq 1 ] && pass "$verb implemented by the client" || fail "$verb implemented $n times by the client (want 1)"
    if grep -qE "\.$verb\(" "$SCREEN"; then pass "screen reaches .$verb("; else fail "screen never calls .$verb("; fi
done

echo "── M4 no protocol library and no shell-out in the app's module ──"
for dep in "com.hierynomus:sshj" "commons-net:commons-net" "com.squareup.okhttp3:okhttp"; do
    if grep -qF "$dep" "$GRADLE"; then fail "libs:mounts depends on $dep — the engine APK carries it"; else pass "libs:mounts does not depend on $dep"; fi
done
if grep -rqE "net\.schmizz|org\.apache\.commons\.net|okhttp3" "$MOD/src/main"; then fail "libs:mounts imports a protocol library"; else pass "no protocol-library import in libs:mounts"; fi
if grep -rqE "ProcessBuilder|Runtime\.getRuntime\(\)\.exec" "$MOD/src/main"; then fail "the module shells out (ProcessBuilder/Runtime.exec)"; else pass "no shell-out in the module"; fi

echo "── M5 pure half ──"
for f in "$MODELS" "$FS" "$WIRE"; do
    if grep -qE "^import android\." "$f"; then fail "$(basename "$f") imports android.*"; else pass "$(basename "$f") pure"; fi
done

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-mounts-engine: all checks passed"; else echo "test-drive-mounts-engine: $FAILURES check(s) FAILED"; exit 1; fi
