#!/usr/bin/env bash
# ╔═════════════════════════════════════════════════════════════════
# ║ #567 push 4 / #871 move 10 — the ONE pinned static rclone binary, checked   ║
# ║ where it now ships: libs:rclone-engine, Cloud-Lib-Rclone-Engine.apk        ║
# ╚═════════════════════════════════════════════════════════════════
#
# Until move 10 Cloud Drive compiled libs:rclone, the binary with it, and these checks lived in
# ac_cloud-drive/test/test-drive-rclone-engine.sh. No app compiles the binary now: it ships in
# the engine APK, and Cloud Drive's ship does not watch that directory, so a change there runs
# THIS pipeline (Cloud Libs) and no other. The checks moved here with the code.
#
#   R1  data/rclone-binary.json is the ONE pin: a version, and per ABI a
#       64-hex zip sha256 and binary sha256; interp declared "none".
#   R2  build.gradle reads that file (no literal version/hash/URL in gradle),
#       verifies BOTH hashes, reads the ELF program headers for PT_INTERP, and
#       stages the binary under the jni_name the pin declares, before preBuild.
#   R3  the engine execs from nativeLibraryDir by BuildConfig.RCLONE_JNI_NAME
#       (never a path it wrote itself — W^X on API 29+), and lib_apks.exec_native
#       lists the module so the installer extracts the .so at all.
#   R4  no app compiles the binary any more: libs:rclone (the contract) carries no
#       jniLibs, no fetch task and no ProcessBuilder.
#
# OWN-SOURCE ONLY: reads ab_cloud-libs-shared/libs and lib-apks, nothing an app owns.
# python3 and grep only, no network, no build.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
LIBS="$ROOT/ab_cloud-libs-shared/libs"
MOD="$LIBS/rclone-engine"
CONTRACT="$LIBS/rclone"
PIN="$MOD/data/rclone-binary.json"
GRADLE="$MOD/build.gradle"
SERVICE="$MOD/src/main/java/com/diegonmarcos/cloudlib/rclone/engine/RcloneEngineService.kt"
LIBAPKS="$ROOT/ab_cloud-libs-shared/lib-apks/build.json"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$PIN" "$GRADLE" "$SERVICE" "$LIBAPKS" "$CONTRACT/build.gradle"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

echo "── R1 the pin ──"
python3 - "$PIN" <<'PYTHON' && pass "pin: version, per-ABI 64-hex zip+binary sha256, interp none, jni_name lib*.so" || fail "pin shape (see above)"
import json, re, sys
d = json.load(open(sys.argv[1]))
bad = 0
def chk(cond, msg):
    global bad
    if not cond: print("    " + msg); bad += 1
chk(re.fullmatch(r"v\d+\.\d+\.\d+", d.get("version", "")), "version is not vX.Y.Z: %r" % d.get("version"))
chk(d.get("interp") == "none", "interp must be 'none' (static binary), got %r" % d.get("interp"))
chk(re.fullmatch(r"lib[\w.-]+\.so", d.get("jni_name", "")), "jni_name must be lib*.so: %r" % d.get("jni_name"))
chk("{version}" in d.get("url_template", "") and "{arch}" in d.get("url_template", ""), "url_template lacks {version}/{arch}")
bins = d.get("binaries", {})
chk(len(bins) >= 1, "no binaries")
for abi, b in bins.items():
    for k in ("zip_sha256", "binary_sha256"):
        chk(re.fullmatch(r"[0-9a-f]{64}", b.get(k, "")), "%s.%s is not a 64-hex sha256" % (abi, k))
    chk(b.get("arch") in ("arm64", "amd64", "arm-v7", "386"), "%s.arch unexpected: %r" % (abi, b.get("arch")))
sys.exit(1 if bad else 0)
PYTHON

echo "── R2 build.gradle drives everything off the pin ──"
if grep -qE "JsonSlurper\(\)\.parse\(file\('data/rclone-binary\.json'\)\)" "$GRADLE"; then pass "gradle reads data/rclone-binary.json"; else fail "gradle does not read data/rclone-binary.json"; fi
if grep -qE "https?://" "$GRADLE"; then fail "gradle carries a literal URL — the pin owns it"; else pass "no literal URL in gradle"; fi
if grep -qE "[0-9a-f]{64}" "$GRADLE"; then fail "gradle carries a literal sha256 — the pin owns it"; else pass "no literal sha256 in gradle"; fi
if grep -qE "fetchPinned\(url, zip, pin\.zip_sha256\)" "$GRADLE" && grep -qE "actual != pin\.binary_sha256" "$GRADLE" && grep -qE "if \(actual != sha256\)" "$GRADLE"; then pass "both hashes COMPARED (zip in fetchPinned, binary after extraction)"; else fail "zip and binary sha256 are not both compared against the pin"; fi
if grep -qE "type == 3" "$GRADLE" && grep -qE "hasProgramInterpreter" "$GRADLE"; then pass "PT_INTERP is read off the ELF program headers"; else fail "no PT_INTERP check"; fi
if grep -qE "preBuild\.dependsOn fetchRcloneBinaries" "$GRADLE"; then pass "staged before preBuild"; else fail "fetch task not wired before preBuild"; fi
if grep -qE "jniLibs\.srcDirs \+= rcloneJniDir" "$GRADLE"; then pass "staged dir is a jniLibs source"; else fail "staged dir is not a jniLibs source"; fi

echo "── R3 exec path ──"
if grep -qE "applicationInfo\.nativeLibraryDir, BuildConfig\.RCLONE_JNI_NAME" "$SERVICE"; then pass "the engine execs nativeLibraryDir/<jni_name>"; else fail "the engine does not exec from nativeLibraryDir by the baked name"; fi
if python3 -c 'import json,sys; sys.exit(0 if "rclone-engine" in json.load(open(sys.argv[1]))["lib_apks"]["exec_native"] else 1)' "$LIBAPKS"; then pass "lib_apks.exec_native lists rclone-engine (the installer extracts the .so)"; else fail "lib_apks.exec_native does not list rclone-engine — the .so is never extracted and the exec fails ENOENT"; fi

echo "── R4 the contract module carries no binary ──"
if grep -vE '^[[:space:]]*(\*|//|/\*)' "$CONTRACT/build.gradle" | grep -qE "jniLibs|fetchRcloneBinaries|rclone-binary\.json"; then fail "libs:rclone still stages or fetches the binary"; else pass "libs:rclone stages no binary"; fi
if grep -rqE "ProcessBuilder\(" "$CONTRACT/src/main"; then fail "libs:rclone still execs a process — apps must not run rclone"; else pass "libs:rclone execs nothing"; fi
if [ -e "$CONTRACT/data/rclone-binary.json" ]; then fail "the pin is still in libs:rclone — one pin, in the engine"; else pass "the pin lives only in the engine"; fi

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-rclone-engine: all checks passed"; else echo "test-rclone-engine: $FAILURES check(s) FAILED"; exit 1; fi
