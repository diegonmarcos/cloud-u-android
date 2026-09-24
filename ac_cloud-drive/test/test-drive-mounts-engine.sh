#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #567 push 4 — libs:mounts speaks every declared mount type through a     ║
# ║ maintained library, trusts host keys on first use only, and every verb  ║
# ║ of the file-system contract is reached from the browser                 ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# The pure half (URI ↔ spec, WebDAV parser, store, TOFU verifier) is proven by
# MountsEngineTest in every drive ship. What only a tester over the sources
# can see:
#
#   M1  every MountType the model declares is handled by MountFsFactory.open
#       (a type with no engine is a mount that can be added and never opened).
#   M2  every RemoteFs verb (list, download, upload, mkdir, delete, rename,
#       exec) is implemented by every engine class and reached from the screen.
#   M3  host keys: the sshj client is given TofuHostKeyVerifier and NEVER
#       PromiscuousVerifier; the verifier's refuse path is tested.
#   M4  the engines are the documented maintained libraries (sshj, commons-net,
#       OkHttp) — no Runtime.exec / ProcessBuilder shell-out anywhere in the module.
#   M5  the pure half imports nothing from android.*.
#
# OWN-SOURCE ONLY: everything read is under a module dir this app's
# build.json declares.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
MOD="$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:mounts"]["dir"])))' "$APP/build.json" "$APP")"
PKG="$MOD/src/main/java/com/diegonmarcos/cloudlib/mounts"
MODELS="$PKG/MountModels.kt"; FS="$PKG/RemoteFs.kt"; ENGINES="$PKG/MountEngines.kt"; SCREEN="$PKG/MountsScreen.kt"; PARSER="$PKG/WebDavParser.kt"
TEST="$MOD/src/test/java/com/diegonmarcos/cloudlib/mounts/MountsEngineTest.kt"
GRADLE="$MOD/build.gradle"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$MODELS" "$FS" "$ENGINES" "$SCREEN" "$PARSER" "$TEST" "$GRADLE"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required"; exit 1; }
done

echo "── M1 every declared MountType has an engine ──"
TYPES="$(python3 - "$MODELS" <<'PYTHON'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
body = re.search(r"enum class MountType\(.*?\)\s*\{(.*?);", text, re.S)
print(" ".join(re.findall(r"^\s*([A-Z][A-Z0-9_]*)\(", body.group(1), re.M)) if body else "")
PYTHON
)"
[ -n "$TYPES" ] && pass "MountType declares: $TYPES" || fail "cannot read MountType entries from MountModels.kt"
for t in $TYPES; do
    if grep -qE "MountType\.$t\b" "$ENGINES"; then pass "MountFsFactory/engines handle MountType.$t"; else fail "MountType.$t has no engine branch in MountEngines.kt"; fi
done

echo "── M2 the RemoteFs contract: every verb, every engine, reached from the screen ──"
VERBS="$(python3 - "$FS" <<'PYTHON'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
body = re.search(r"interface RemoteFs\b.*?\{(.*?)\n\}", text, re.S)
print(" ".join(re.findall(r"^\s*fun (\w+)\(", body.group(1), re.M)) if body else "")
PYTHON
)"
[ -n "$VERBS" ] && pass "RemoteFs verbs: $VERBS" || fail "RemoteFs declares no verbs"
for cls in SftpFs FtpFs WebDavFs; do
    grep -qE "class $cls\(" "$ENGINES" && pass "engine $cls exists" || fail "engine $cls missing"
done
for verb in $VERBS; do
    n="$(grep -cE "override fun $verb\(" "$ENGINES")"
    if [ "$verb" = "exec" ]; then
        [ "$n" -ge 1 ] && pass "exec overridden by the ssh engine" || fail "exec not overridden by any engine"
    else
        [ "$n" -eq 3 ] && pass "$verb implemented by all 3 engines" || fail "$verb implemented by $n of 3 engines"
    fi
    if grep -qE "\.$verb\(" "$SCREEN"; then pass "screen reaches .$verb("; else fail "screen never calls .$verb("; fi
done

echo "── M3 host keys ──"
if grep -qE "addHostKeyVerifier\(TofuHostKeyVerifier\(" "$ENGINES"; then pass "sshj uses TofuHostKeyVerifier"; else fail "sshj client is not given TofuHostKeyVerifier"; fi
PROMISC="$(grep -rnE "PromiscuousVerifier" "$MOD/src/main" | grep -vE "^[^:]+:[0-9]+:\s*(//|\*)")"
if [ -n "$PROMISC" ]; then fail "PromiscuousVerifier in code — accepts any host key:"; printf '%s\n' "$PROMISC" | sed 's/^/        /'; else pass "no PromiscuousVerifier outside comments"; fi
if grep -qE "a changed key is REFUSED" "$TEST" && grep -qE "assertFalse\(.*verify\(" "$TEST"; then pass "the refuse path is tested"; else fail "no test proves a changed host key is refused"; fi

echo "── M4 maintained libraries, no shell-out ──"
for dep in "com.hierynomus:sshj" "commons-net:commons-net" "com.squareup.okhttp3:okhttp"; do
    grep -qF "$dep" "$GRADLE" && pass "$dep declared" || fail "$dep not declared"
done
if grep -rqE "ProcessBuilder|Runtime\.getRuntime\(\)\.exec" "$MOD/src/main"; then fail "the module shells out (ProcessBuilder/Runtime.exec)"; else pass "no shell-out in the module"; fi

echo "── M5 pure half ──"
for f in "$MODELS" "$FS" "$PARSER"; do
    if grep -qE "^import android\." "$f"; then fail "$(basename "$f") imports android.*"; else pass "$(basename "$f") pure"; fi
done

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-mounts-engine: all checks passed"; else echo "test-drive-mounts-engine: $FAILURES check(s) FAILED"; exit 1; fi
