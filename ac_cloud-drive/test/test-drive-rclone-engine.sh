#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #567 push 4 — libs:rclone ships ONE pinned static binary and the app     ║
# ║ that packages it can actually exec it                                    ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# The parsers and stores are proven by RcloneEngineTest in every drive ship.
# What only a tester over the build files can see:
#
#   R1  data/rclone-binary.json is the ONE pin: a version, and per ABI a
#       64-hex zip sha256 and binary sha256; interp declared "none".
#   R2  build.gradle reads that file (no literal version/hash/URL in gradle),
#       verifies BOTH hashes, reads the ELF program headers for PT_INTERP, and
#       stages the binary under the jni_name the pin declares, before preBuild.
#   R3  RcloneRunner execs from nativeLibraryDir by BuildConfig.RCLONE_JNI_NAME
#       (never a path it wrote itself — W^X on API 29+), and the drive app
#       sets useLegacyPackaging so the installer extracts the .so at all.
#   R4  the remote-types catalogue is one named asset, opened by the screen and
#       read by the JVM test; the job store's declare() is what the host calls.
#   R5  the pure half imports nothing from android.*.
#
# OWN-SOURCE ONLY: everything read is under this app or a module dir its
# build.json declares.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
MOD="$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:rclone"]["dir"])))' "$APP/build.json" "$APP")"
PIN="$MOD/data/rclone-binary.json"
GRADLE="$MOD/build.gradle"
PKG="$MOD/src/main/java/com/diegonmarcos/cloudlib/rclone"
RUNNER="$PKG/RcloneRunner.kt"
SCREEN="$PKG/RcloneScreen.kt"
TEST="$MOD/src/test/java/com/diegonmarcos/cloudlib/rclone/RcloneEngineTest.kt"
APP_GRADLE="$APP/app/build.gradle"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$PIN" "$GRADLE" "$RUNNER" "$SCREEN" "$TEST" "$APP_GRADLE"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required"; exit 1; }
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
if grep -qE "applicationInfo\.nativeLibraryDir, BuildConfig\.RCLONE_JNI_NAME" "$RUNNER"; then pass "runner execs nativeLibraryDir/<jni_name>"; else fail "runner does not exec from nativeLibraryDir by the baked name"; fi
if grep -qE "useLegacyPackaging = true" "$APP_GRADLE"; then pass "drive app extracts native libs (useLegacyPackaging)"; else fail "ac_cloud-drive/app/build.gradle lacks jniLibs.useLegacyPackaging = true — the .so is never extracted and exec fails ENOENT"; fi

echo "── R4 catalogue + host contract ──"
if grep -qE "assets\.open\(RcloneRemoteTypes\.ASSET\)" "$SCREEN" && grep -qE "src/main/assets/\" \+ RcloneRemoteTypes\.ASSET" "$TEST"; then pass "remote-types asset opened by name in screen and test"; else fail "remote-types asset not opened by its declared name in both screen and test"; fi
ASSET="$MOD/src/main/assets/$(grep -oE 'const val ASSET = "[^"]+"' "$PKG/RcloneRemoteTypes.kt" | sed 's/.*= "//; s/"$//')"
[ -f "$ASSET" ] && pass "asset file exists" || fail "asset file missing: $ASSET"
if grep -qE "fun declare\(jobs: List<RcloneJob>\)" "$PKG/RcloneJobs.kt" && grep -qE "\.declare\(" "$TEST"; then pass "declare() exists and is tested"; else fail "declare() contract missing or untested"; fi
for verb in start cancel lsjson obscure download test version; do
    if grep -qE "\.$verb\(" "$SCREEN"; then pass "screen reaches runner.$verb("; else fail "screen never calls .$verb("; fi
done

echo "── R5 pure half ──"
for f in "$PKG/RcloneConfig.kt" "$PKG/RcloneOutput.kt" "$PKG/RcloneJobs.kt" "$PKG/RcloneRemoteTypes.kt"; do
    if grep -qE "^import android\." "$f"; then fail "$(basename "$f") imports android.*"; else pass "$(basename "$f") pure"; fi
done

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-rclone-engine: all checks passed"; else echo "test-drive-rclone-engine: $FAILURES check(s) FAILED"; exit 1; fi
