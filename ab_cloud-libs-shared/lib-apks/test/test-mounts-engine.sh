#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #567 push 4 / #871 move 12 — the mounts ENGINE speaks every declared mount ║
# ║ type through a maintained library and trusts host keys on first use only,  ║
# ║ checked where it now ships: libs:mounts-engine, Cloud-Lib-Mounts-Engine.apk ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# Until move 12 Cloud Drive compiled the protocol clients and these checks lived in
# ac_cloud-drive/test/test-drive-mounts-engine.sh. No app compiles them now: they ship in the engine APK,
# and Cloud Drive's ship does not watch that directory, so a change there runs THIS pipeline (Cloud Libs)
# and no other. The checks moved here with the code.
#
#   M1  every MountType the model declares is handled by the engine's MountFsFactory.open (a type with no
#       engine is a mount that can be added and never opened).
#   M2  every RemoteFs verb (list, download, upload, mkdir, delete, rename, exec) is implemented by every
#       engine class, and every verb of the wire is answered by MountCalls (and the other way round).
#   M3  host keys: the sshj client is given TofuHostKeyVerifier and NEVER PromiscuousVerifier; the verifier's
#       refuse path is tested.
#   M4  the engines are the documented maintained libraries (sshj, commons-net, OkHttp) — no Runtime.exec /
#       ProcessBuilder shell-out anywhere in the engine — and the contract module (compiled into the app)
#       carries none of them.
#   M5  the parser is pure (no android.* import).
#
# OWN-SOURCE ONLY: reads ab_cloud-libs-shared/libs, nothing an app owns.
# python3 and grep only, no network, no build.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
LIBS="$ROOT/ab_cloud-libs-shared/libs"
MOD="$LIBS/mounts-engine"
CONTRACT="$LIBS/mounts"
PKG="$MOD/src/main/java/com/diegonmarcos/cloudlib/mounts/engine"
CPKG="$CONTRACT/src/main/java/com/diegonmarcos/cloudlib/mounts"
MODELS="$CPKG/MountModels.kt"; FS="$CPKG/RemoteFs.kt"; WIRE="$CPKG/MountWire.kt"
ENGINES="$PKG/MountEngines.kt"; PARSER="$PKG/WebDavParser.kt"; CALLS="$PKG/MountCalls.kt"
TEST="$MOD/src/test/java/com/diegonmarcos/cloudlib/mounts/engine/MountsEngineProtocolTest.kt"
GRADLE="$MOD/build.gradle"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$MODELS" "$FS" "$WIRE" "$ENGINES" "$PARSER" "$CALLS" "$TEST" "$GRADLE" "$CONTRACT/build.gradle"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
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

echo "── M2 the RemoteFs contract: every verb, every engine; the wire's verbs are the ones answered ──"
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
done
python3 - "$WIRE" "$CALLS" <<'PYTHON' && pass "MountWire.VERBS == the verbs MountCalls.fs implements" || fail "the wire and the engine disagree on the verbs (see above)"
import re, sys
wire = open(sys.argv[1], encoding="utf-8").read()
calls = open(sys.argv[2], encoding="utf-8").read()
m = re.search(r"val VERBS: Set<String> = setOf\((.*?)\)\n", wire, re.S)
declared = set(re.findall(r'"([a-z_]+)"', m.group(1))) if m else set()
body = calls[calls.index("val result: String? = synchronized"):]
body = body[:body.index("else -> throw")]
implemented = set(re.findall(r'^\s*"([a-z_]+)"\s*->', body, re.M))
bad = 0
if not declared: print("    MountWire.VERBS not found"); bad = 1
if declared - implemented: print("    declared but not implemented: %s" % sorted(declared - implemented)); bad = 1
if implemented - declared: print("    implemented but not declared: %s" % sorted(implemented - declared)); bad = 1
sys.exit(bad)
PYTHON

echo "── M3 host keys ──"
if grep -qE "addHostKeyVerifier\(TofuHostKeyVerifier\(" "$ENGINES"; then pass "sshj uses TofuHostKeyVerifier"; else fail "sshj client is not given TofuHostKeyVerifier"; fi
PROMISC="$(grep -rnE "PromiscuousVerifier" "$MOD/src/main" | grep -vE "^[^:]+:[0-9]+:\s*(//|\*)")"
if [ -n "$PROMISC" ]; then fail "PromiscuousVerifier in code — accepts any host key:"; printf '%s\n' "$PROMISC" | sed 's/^/        /'; else pass "no PromiscuousVerifier outside comments"; fi
if grep -qE "a changed key is REFUSED" "$TEST" && grep -qE "assertFalse\(.*verify\(" "$TEST"; then pass "the refuse path is tested"; else fail "no test proves a changed host key is refused"; fi

echo "── M4 maintained libraries, no shell-out ──"
for dep in "com.hierynomus:sshj" "commons-net:commons-net" "com.squareup.okhttp3:okhttp"; do
    grep -qF "$dep" "$GRADLE" && pass "$dep declared by the engine" || fail "$dep not declared by the engine"
    if grep -qF "$dep" "$CONTRACT/build.gradle"; then fail "libs:mounts (compiled into the app) still depends on $dep"; else pass "libs:mounts does not depend on $dep"; fi
done
if grep -rqE "ProcessBuilder|Runtime\.getRuntime\(\)\.exec" "$MOD/src/main"; then fail "the engine shells out (ProcessBuilder/Runtime.exec)"; else pass "no shell-out in the engine"; fi

echo "── M5 the parser is pure ──"
if grep -qE "^import android\." "$PARSER"; then fail "WebDavParser.kt imports android.*"; else pass "WebDavParser.kt pure"; fi

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-mounts-engine: all checks passed"; else echo "test-mounts-engine: $FAILURES check(s) FAILED"; exit 1; fi
