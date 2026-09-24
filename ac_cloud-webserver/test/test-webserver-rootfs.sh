#!/usr/bin/env bash
# #288 Route D — what makes cloud-webserver's APK able to run a glibc binary.
#
#   A  every ABI the release ships has BOTH a rootfs pin (read through
#      bake.rootfs.from/.pointer, i.e. from ac_cloud-nix-on-droid's own
#      declaration) and a binary pin, each 64-hex
#   B  the rootfs is REFERENCED, not copied: none of its pins or its host
#      appears anywhere in this app's tree
#   C  resolve_runtime.py's ELF reader agrees with readelf on a real ELF
#   D  the resolver maps that ELF's loader + libraries into a rootfs that has
#      them, and REFUSES one that is missing a library
#   E  wiring: the bake runs before assets merge, the app is told only the
#      asset name build.json declares, and no loader or store path is typed
#      into the Kotlin
#   F  targetSdk <= 28, read from the declaration gradle reads
#
# Offline, like #348's test-bootstrap-baked.sh: the real rootfs and binary are
# checked on every build by bakeWebserver itself, which refuses a sha256
# mismatch and a rootfs that cannot satisfy the binary.
set -uo pipefail

APP="$(cd "$(dirname "$0")/.." && pwd)"
FAIL=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAIL=1; }
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "── A  every shipped ABI has a rootfs pin and a binary pin"
out="$(python3 - "$APP/build.json" <<'EOF'
import json, os, re, sys
d = json.load(open(sys.argv[1]))
bake = d["bake"]
src = os.path.join(os.path.dirname(sys.argv[1]), bake["rootfs"]["from"])
rootfs = json.load(open(src))
for k in bake["rootfs"]["pointer"].split("."):
    rootfs = rootfs.get(k) if isinstance(rootfs, dict) else None
if not rootfs or "archs" not in rootfs:
    print(f"FAIL {src} has no archs at {bake['rootfs']['pointer']}")
    sys.exit()
hexpin = re.compile(r"^[0-9a-f]{64}$")
for v in d["release"]["variants"]:
    for abi in v["abis"]:
        r = rootfs["archs"].get(abi, {}).get("sha256", "")
        b = bake["binary"]["archs"].get(abi, {}).get("sha256", "")
        print(("PASS" if hexpin.match(r) else "FAIL") + f" {abi}: rootfs pin from {bake['rootfs']['from']} = {r!r}")
        print(("PASS" if hexpin.match(b) else "FAIL") + f" {abi}: binary pin = {b!r}")
EOF
)"
[ -n "$out" ] || fail "A produced no verdict"
while IFS= read -r l; do case "$l" in PASS*) pass "${l#PASS }" ;; *) fail "${l#FAIL }" ;; esac; done <<<"$out"

echo "── B  the rootfs declaration is referenced, never copied"
out="$(python3 - "$APP/build.json" "$APP" <<'EOF'
import json, os, sys
d = json.load(open(sys.argv[1]))
src = os.path.join(sys.argv[2], d["bake"]["rootfs"]["from"])
rootfs = json.load(open(src))
for k in d["bake"]["rootfs"]["pointer"].split("."):
    rootfs = rootfs[k]
needles = [a["sha256"] for a in rootfs["archs"].values()] + [rootfs["url_base"]]
hits = []
for dp, dn, fn in os.walk(sys.argv[2]):
    dn[:] = [x for x in dn if x not in ("build", ".gradle", "dist")]
    for f in fn:
        text = open(os.path.join(dp, f), "rb").read().decode("utf-8", "replace")
        hits += [f"{os.path.relpath(os.path.join(dp, f), sys.argv[2])}: {n}" for n in needles if n in text]
print("\n".join(hits) if hits else f"CLEAN {len(needles)}")
EOF
)"
case "$out" in
  "CLEAN "[1-9]*) pass "none of the ${out#CLEAN } rootfs pins/host values is restated in this app" ;;
  *) fail "rootfs declaration copied into this app: $out" ;;
esac

echo "── C  the ELF reader agrees with readelf"
ELF="$(readlink -f "$(command -v python3)")"
want_interp="$(readelf -lW "$ELF" | sed -n 's/.*Requesting program interpreter: \(.*\)\]/\1/p')"
want_needed="$(readelf -dW "$ELF" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | tr '\n' ' ')"
[ -n "$want_interp" ] || fail "C: $ELF has no PT_INTERP per readelf — pick a dynamic ELF"
got="$(python3 - "$APP/app" "$ELF" <<'EOF'
import sys
sys.path.insert(0, sys.argv[1])
from resolve_runtime import elf_needs
interp, needed = elf_needs(sys.argv[2])
print(interp)
print(" ".join(needed) + " ")
EOF
)"
if [ "$got" = "$want_interp"$'\n'"$want_needed" ]; then
  pass "PT_INTERP $want_interp and DT_NEEDED [$want_needed] of $ELF"
else
  fail "elf_needs($ELF) = [$got], readelf says [$want_interp / $want_needed]"
fi

echo "── D  the resolver fills the loader + every library, and refuses a gap"
python3 - "$APP/app" "$ELF" "$WORK" <<'EOF' > "$WORK/d.out"
import json, os, subprocess, sys, zipfile
sys.path.insert(0, sys.argv[1])
from resolve_runtime import elf_needs
app, elf, work = sys.argv[1:4]
interp, needed = elf_needs(elf)
libs = [n for n in needed if n != os.path.basename(interp)]
head = open(elf, "rb").read(4096)

def rootfs(path, drop=None):
    # glibc dir holds the loader and all but the last library; the last one
    # lives in a second dir and is reached through SYMLINKS.txt, the way
    # libstdc++.so.6 is in the real bootstrap.
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("bin/proot-static", head)
        z.writestr("nix/store/a-glibc/lib/" + os.path.basename(interp), head)
        for n in libs[:-1]:
            if n != drop:
                z.writestr("nix/store/a-glibc/lib/" + n, b"")
        if libs and libs[-1] != drop:
            z.writestr("nix/store/b-gcc/lib/real-" + libs[-1], b"")
            z.writestr("SYMLINKS.txt", f"real-{libs[-1]}←nix/store/b-gcc/lib/{libs[-1]}\n")

def run(zp):
    return subprocess.run([sys.executable, os.path.join(app, "resolve_runtime.py"), zp, elf,
                           "bin/proot-static", os.path.join(work, "out.json")], capture_output=True, text=True)

full = os.path.join(work, "full.zip"); rootfs(full)
r = run(full)
if r.returncode != 0:
    print("FAIL complete rootfs refused: " + " ".join(r.stderr.split())[:200]); sys.exit()
out = json.load(open(os.path.join(work, "out.json")))
guest = {b["guest"]: b["host"] for b in out["binds"]}
print(("PASS" if guest.get(os.path.dirname(interp)) == "nix/store/a-glibc/lib" else "FAIL")
      + f" loader dir {os.path.dirname(interp)} is bound to the dir holding {os.path.basename(interp)}: {guest}")
print(("PASS" if guest.get("/nix") == "nix" else "FAIL") + f" /nix is bound so absolute store symlinks resolve: {guest}")
missing = [n for n in libs if "/" + out["needed"].get(n, "?") not in out["library_path"]]
print(("FAIL" if missing or not libs else "PASS")
      + f" all {len(libs)} DT_NEEDED on library_path {out['library_path']}" + (f" (missing {missing})" if missing else ""))
if libs:
    gap = os.path.join(work, "gap.zip"); rootfs(gap, drop=libs[0])
    r = run(gap)
    print(("PASS" if r.returncode != 0 and libs[0] in r.stderr else "FAIL")
          + f" a rootfs without {libs[0]} is refused (exit {r.returncode}: {' '.join(r.stderr.split())[:160]})")
EOF
[ -s "$WORK/d.out" ] || fail "D produced no verdict"
while IFS= read -r l; do case "$l" in PASS*) pass "${l#PASS }" ;; *) fail "${l#FAIL }" ;; esac; done <"$WORK/d.out"

echo "── E  wiring"
G="$APP/app/build.gradle"
if grep -Eq 'tasks\.matching \{ it\.name ==~ /merge\.\*Assets/ \}\.configureEach \{ dependsOn bakeWebserver \}' "$G"; then
  pass "bakeWebserver runs before every merge*Assets"
else
  fail "nothing makes merge*Assets depend on bakeWebserver — the APK would ship with no runtime"
fi
if grep -Fq 'buildConfigField "String", "RUNTIME_ASSET", "\"${buildJson.bake.assets.runtime}\""' "$G"; then
  pass "BuildConfig.RUNTIME_ASSET is derived from build.json::bake.assets.runtime"
else
  fail "RUNTIME_ASSET is not derived from build.json::bake.assets.runtime"
fi
KT="$APP/app/src/main/java/com/diegonmarcos/cloudwebserver"
if grep -q 'ctx.assets.open(BuildConfig.RUNTIME_ASSET)' "$KT/WebServerRuntime.kt"; then
  pass "the app reads its launch recipe from BuildConfig.RUNTIME_ASSET"
else
  fail "WebServerRuntime.kt does not open BuildConfig.RUNTIME_ASSET"
fi
typed="$(grep -rnE '/nix|ld-linux|proot-static|lib64|/lib/|"--link2symlink"|[0-9a-f]{64}' "$KT")"
if [ -z "$typed" ]; then
  pass "no loader, store, launcher or pin literal in the Kotlin"
else
  fail "the Kotlin types what the bake derives: $typed"
fi

echo "── F  targetSdk <= 28 (exec from the app data dir)"
sdk="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["android"]["target_sdk"])' "$APP/build.json")"
if grep -Eq '^\s*targetSdk\s+a\.target_sdk\s*$' "$G" && [ "$sdk" -le 28 ]; then
  pass "gradle takes targetSdk from build.json, which says $sdk"
else
  fail "targetSdk is $sdk (or not read from build.json) — from 29 SELinux denies execve() of proot and the binary"
fi

[ "$FAIL" -eq 0 ] && echo "test-webserver-rootfs: all assertions pass" || echo "test-webserver-rootfs: FAILED"
exit "$FAIL"
