#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #698 — the rootfs must be able to LOG IN, proven on the zip that ships,  ║
# ║        and a terminal/rootfs mismatch is SAID on screen                  ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# Two holes this closes:
#
#  1. THE HOLLOW GATE. Every exit-127 this app shipped (#605 proot-static,
#     #638 /usr/bin/env, #641 a profile whose store paths never shipped) was a
#     path the login chain needs that the zip did not carry. The bake's gates
#     read the CI runner's /nix/store; rootfs-lib's verifyRootfsPayload checks
#     only that the payload FILE exists. Nothing opened the zip.
#     app/src/main/cpp/verify_login_closure.py now does, from bakeBootstrap.
#     Here it is driven over synthetic rootfs zips GENERATED FROM THE REAL
#     DECLARATIONS (build.json's app id, profile link and binaries, and
#     login-closure.json), and every assertion is mutation-proved: a planted
#     defect must turn it red, and the planted zip must differ from the clean
#     one, so a mutation that did not mutate cannot pass for a working guard.
#
#  2. THE SILENT PAIRING. The Store updates the terminal and its rootfs lib
#     independently, each by its own sha256, so a new terminal over an older
#     rootfs is an ordinary state -- and the terminal ran that rootfs's login
#     without a word. The APK now carries the content address it was built
#     against and the session it opens prints the mismatch as its first line.
#
# Offline, no Android SDK: python3 and source only.
set -u

DIR="$(cd "$(dirname "$0")/.." && pwd)"
GATE="$DIR/app/src/main/cpp/verify_login_closure.py"
CLOSURE="$DIR/login-closure.json"
BUILD_JSON="$DIR/build.json"
GRADLE="$DIR/app/build.gradle"
INSTALLER="$DIR/app/src/main/java/com/termux/app/TermuxInstaller.java"
ACTIVITY="$DIR/app/src/main/java/com/termux/app/TermuxActivity.java"
CLIENT="$DIR/app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java"
SESSION="$DIR/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java"

fails=0
ok()   { echo "  ok   — $1"; }
bad()  { echo "::error::FAIL — $1"; fails=$((fails + 1)); }

SB="$(mktemp -d)"
trap 'rm -rf "$SB"' EXIT

echo "── #698 the login closure gate, on the zip, mutation-proved ──"

# ── G0 — the gate runs on the bytes that ship, and a red gate fails the build ──
if grep -q 'verify_login_closure.py' "$GRADLE" \
   && grep -q 'closureResult.exitValue != 0' "$GRADLE" \
   && grep -q '"login-closure.json"' "$GRADLE"; then
    ok "bakeBootstrap runs verify_login_closure.py over the baked zip with login-closure.json, and throws when it fails"
else
    bad "app/build.gradle's bakeBootstrap does not run the login closure gate (or ignores its exit status)"
fi
# The declaration must stay OUT of the rootfs identity: a verifier edit that
# moved the content address would republish ~400 MB of unchanged bytes.
# #796 the companion's gate inputs are DERIVED by the engine (identity_files +
# module_dir), so they are read from `build.sh companion-paths`, never restated.
COMPANION_PATHS="$(cd "$(dirname "$BUILD_JSON")" && bash ./build.sh companion-paths rootfs-nixdroid 2>/dev/null || true)"
if python3 - "$BUILD_JSON" "$COMPANION_PATHS" <<'EOF'
import json, sys
d = json.load(open(sys.argv[1]))
ids = d["forks"]["nixdroid"]["bootstrap"]["artifact"]["identity_files"]
paths = sys.argv[2].split()
if not paths:
    sys.exit("build.sh companion-paths rootfs-nixdroid printed nothing -- the gate's inputs could not be derived")
bad = [p for p in ids + paths if "login-closure" in p or "verify_login_closure" in p]
sys.exit(1 if bad else 0)
EOF
then
    ok "the gate and its declaration are not rootfs identity inputs (a gate edit republishes nothing)"
else
    bad "login-closure.json / verify_login_closure.py is listed as a rootfs identity input or companion path"
fi

# store.json::linux_tools pins the real sha256 of the files fetched from cloud-u-linux, which this
# offline tester cannot (and should not) download. So the gate runs over a sandbox copy of
# build.json + store.json whose pins mkroot.py rewrites to the hashes of its synthetic stand-ins;
# the gate reads store.json beside build.json's app dir, so nothing else about it changes.
SBX="$SB/sbx"
mkdir -p "$SBX/ac_cloud-nix-on-droid" "$SBX/ab_cloud-terminal-store"
cp "$DIR/build.json" "$SBX/ac_cloud-nix-on-droid/build.json"
cp "$DIR/../ab_cloud-terminal-store/store.json" "$SBX/ab_cloud-terminal-store/store.json"
BUILD_JSON="$SBX/ac_cloud-nix-on-droid/build.json"

# ── the synthetic rootfs, generated from the real declarations ──────────────
cat > "$SB/mkroot.py" <<'EOF'
"""Builds a rootfs zip shaped like the baked one: bin/login execs proot-static
with binds and runs bin/sh over login-inner, which sources the baked PATH
script and the store init and names the login shell under the profile link.
Everything declared is read from build.json and login-closure.json; argv[4:]
are mutations to plant."""
import importlib.util, json, os, struct, sys, zipfile

out, build_json, closure_json, *mutations = sys.argv[1:]
boot = json.load(open(build_json))["forks"]["nixdroid"]["bootstrap"]
tools = boot["default_packages"]
closure = json.load(open(closure_json))
app = boot["package_name_rewrite"]["to"]
P = f"/data/data/{app}/files/usr"
link = tools["profile_link"]
fallback = tools["fallback_init_script"]
shell = tools["login_shell_attr"]

def elf(interp=None, needed=(), runpath=None):
    """A minimal ELF64: PT_INTERP when given, and (#846) a PT_DYNAMIC with
    DT_NEEDED / DT_RUNPATH entries over a PT_LOAD that maps the whole file."""
    strtab = b"\0"
    def s_at(text):
        nonlocal strtab
        at = len(strtab); strtab += text.encode() + b"\0"; return at
    dyn = [(1, s_at(n)) for n in needed] + ([(29, s_at(runpath))] if runpath else [])
    phnum = 1 + bool(interp) + bool(dyn)
    blob_at = 64 + 56 * phnum
    istr = interp.encode() + b"\0" if interp else b""
    str_at = blob_at + len(istr)
    dyn_at = str_at + len(strtab)
    dyn_bytes = b"".join(struct.pack("<qQ", t, v) for t, v in dyn + [(5, str_at), (0, 0)]) if dyn else b""
    total = dyn_at + len(dyn_bytes)
    phs = []
    if interp:
        phs.append(struct.pack("<IIQQQQQQ", 3, 4, blob_at, blob_at, blob_at, len(istr), len(istr), 1))
    phs.append(struct.pack("<IIQQQQQQ", 1, 5, 0, 0, 0, total, total, 0x1000))
    if dyn:
        phs.append(struct.pack("<IIQQQQQQ", 2, 6, dyn_at, dyn_at, dyn_at, len(dyn_bytes), len(dyn_bytes), 8))
    hdr = b"\x7fELF" + bytes([2, 1, 1, 0]) + bytes(8) + struct.pack(
        "<HHIQQQIHHHHHH", 2, 183, 1, 0, 64, 0, 0, 64, 56, phnum, 64, 0, 0)
    return hdr + b"".join(phs) + istr + (strtab if dyn else b"") + dyn_bytes

LD = "nix/store/gggg-glibc/lib/ld.so"
BASE_SH = "nix/store/aaaa-bash/bin/bash"
GEN = "nix/store/pppp-profile"
TOOLS = "nix/store/tttt-tools/bin"
absent_literals = "\n".join(
    f'[ -e {P}/{p.replace("*", "0000")} ] && echo absent-by-design'
    for p in closure["absent_by_design"])
files = {
    "bin/login": (f"#!/system/bin/sh\n{absent_literals}\n"
                  f"exec {P}/bin/proot-static \\\n"
                  + "".join(f"  -b {P}/{d}:/{d} \\\n" for d in ("nix", "bin", "etc", "usr"))
                  + f"  -b /:/android \\\n  {P}/bin/sh {P}/usr/lib/login-inner \"$@\"\n"),
    "bin/proot-static": elf(),
    BASE_SH: elf("/" + LD),
    LD: elf(),
    "usr/lib/login-inner": (f'. /{fallback}\n'
                            f'usershell="/{link}/bin/{shell}"\n'
                            'timeout 5 "$usershell" -c exit && exec "$usershell"\nexec -l bash\n'),
    fallback: f'export PATH="/{link}/bin:$PATH"\n',
    f"{TOOLS}/coreutils": elf("/" + LD, needed=["libacl.so.1", "libc.so.6"],
                              runpath="/nix/store/aclx-acl/lib"),
    # #846 the libraries coreutils loads: one through its RUNPATH, libc from the loader's dir
    "nix/store/aclx-acl/lib/libacl.so.1": elf(needed=["libc.so.6"]),
    "nix/store/gggg-glibc/lib/libc.so.6": elf(),
}
for script in closure["scan"]:
    files.setdefault(script, "# synthetic\n")
for rel in closure.get("run", []):
    files[rel] = "#!/bin/sh\nexit 0\n"
# store.json::linux_tools: stand-ins for the pinned files, and the sandbox store.json re-pinned to them
import hashlib
store_path = os.path.join(os.path.dirname(os.path.abspath(build_json)), "..", "ab_cloud-terminal-store", "store.json")
store = json.load(open(store_path))
LT = store["linux_tools"]["files"]
for name, f in LT.items():
    body = ("#!/bin/sh\n# synthetic " + name + "\nexit 0\n") if f["role"] == "cli" else "function fish_greeting\nend\n"
    files[f["install"]] = body
    f["sha256"] = hashlib.sha256(body.encode()).hexdigest()
json.dump(store, open(store_path, "w"), indent=1)
CLI_INSTALLS = sorted(f["install"] for f in LT.values() if f["role"] == "cli")
GREETING = [f["install"] for f in LT.values() if f["role"] == "fish_function"][0]
# The gate's own reader, so the synthetic zip carries exactly what the gate will
# demand: store.json::toolset + default_packages.binaries + path_commands (#737).
spec = importlib.util.spec_from_file_location("gate", os.environ["GATE"])
gate = importlib.util.module_from_spec(spec); spec.loader.exec_module(gate)
commands = gate.declared_commands(build_json, closure)
toolset_last = json.load(open(os.path.join(os.path.dirname(os.path.abspath(build_json)), "..",
                                           "ab_cloud-terminal-store", "store.json")))["toolset"]["binaries"][-1]
links = {"bin/sh": "/" + BASE_SH, link: "/" + GEN}
for c in commands:
    links[f"{GEN}/bin/{c}"] = f"/{TOOLS}/{c}"
    if c == "timeout":
        links[f"{TOOLS}/timeout"] = "coreutils"  # relative, as coreutils ships it
    else:
        files[f"{TOOLS}/{c}"] = elf("/" + LD)
executables = ["bin/proot-static", "bin/login", BASE_SH, LD] + \
    [r for r in files if r.startswith(TOOLS)] + list(closure.get("run", []))

for m in mutations:
    if m == "no-base-shell":      del files[BASE_SH]
    elif m == "no-timeout":       del links[f"{GEN}/bin/timeout"]
    elif m == "shell-not-chmod":  executables.remove(f"{TOOLS}/{shell}")
    elif m == "no-interpreter":   del files[LD]
    elif m == "no-path-script":   del files[fallback]
    elif m == "wizard-shipped":   files["etc/UNINTIALISED"] = "1\n"
    elif m == "dangling-profile": links[link] = "/nix/store/0000-missing-profile"
    elif m == "engine-not-chmod": executables.remove(closure["run"][0])
    elif m == "no-toolset-tool":  del links[f"{GEN}/bin/{toolset_last}"]
    elif m == "linux-tool-tampered":  files[CLI_INSTALLS[0]] += "# changed after the pin\n"
    elif m == "linux-greeting-missing":  del files[GREETING]
    elif m == "linux-cli-not-chmod":  executables.remove(CLI_INSTALLS[0])
    elif m == "no-needed-lib":    del files["nix/store/aclx-acl/lib/libacl.so.1"]
    elif m == "no-loader-lib":    del files["nix/store/gggg-glibc/lib/libc.so.6"]
    elif m == "exec-renamed":     files["bin/login"] = files["bin/login"].replace("/bin/proot-static", "/bin/proot")
    else: sys.exit(f"unknown mutation {m}")

# Fixed timestamps: an unmutated rebuild is byte-identical, so "the planted zip
# differs from the clean one" really means the mutation landed.
def entry(rel):
    return zipfile.ZipInfo(rel, date_time=(1980, 1, 1, 0, 0, 0))
with zipfile.ZipFile(out, "w") as z:
    for rel, data in sorted(files.items()):
        z.writestr(entry(rel), data)
    z.writestr(entry("SYMLINKS.txt"), "".join(f"{t}←{l}\n" for l, t in sorted(links.items())))
    z.writestr(entry("EXECUTABLES.txt"), "".join(e + "\n" for e in executables))
EOF

export GATE
gate() { python3 "$GATE" "$1" "$BUILD_JSON" "$CLOSURE" >"$SB/out" 2>&1; }

if python3 "$SB/mkroot.py" "$SB/clean.zip" "$BUILD_JSON" "$CLOSURE" 2>"$SB/mk.err" && gate "$SB/clean.zip"; then
    ok "a rootfs whose whole login chain resolves passes ($(tail -1 "$SB/out" | cut -c1-90)...)"
else
    bad "the clean synthetic rootfs does not pass the gate: $(cat "$SB/mk.err" "$SB/out" | head -5)"
fi

# ── M1..M9 — each planted defect must be named, on a zip that really changed ──
mutation() {  # <name> <expected text in the gate's output> <what it proves>
    if ! python3 "$SB/mkroot.py" "$SB/$1.zip" "$BUILD_JSON" "$CLOSURE" "$1" 2>"$SB/mk.err"; then
        bad "mutation $1 could not be built: $(cat "$SB/mk.err")"; return
    fi
    if cmp -s "$SB/clean.zip" "$SB/$1.zip"; then
        bad "mutation $1 DID NOT MUTATE: the planted zip is byte-identical to the clean one"; return
    fi
    if gate "$SB/$1.zip"; then
        bad "MUTATION SURVIVED ($1): $3 -- yet the gate passed"
    elif grep -q "$2" "$SB/out"; then
        ok "mutation proved ($1): $3"
    else
        bad "mutation $1 went red for the wrong reason (wanted '$2'): $(head -2 "$SB/out")"
    fi
}
FALLBACK="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["forks"]["nixdroid"]["bootstrap"]["default_packages"]["fallback_init_script"])' "$BUILD_JSON")"
ENGINE="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["run"][0])' "$CLOSURE")"
mutation no-base-shell    "usr/bin/sh does not resolve"        "bin/sh's target (the shell proot runs login-inner with) not shipped"
mutation no-timeout       "PATH command 'timeout'"             "timeout -- the bounded login's only bound -- dropped from the profile"
mutation shell-not-chmod  "not in EXECUTABLES.txt"             "the login shell shipped but never chmod-ed +x"
mutation no-interpreter   "needs interpreter"                  "an ELF's PT_INTERP loader not shipped (ENOENT on a file that exists)"
mutation no-path-script   "$FALLBACK does not resolve"         "the PATH script login-inner sources not shipped"
mutation wizard-shipped   "absent_by_design"                   "the first-run wizard's gate shipped again (a y/N prompt instead of a shell)"
mutation dangling-profile "0000-missing-profile is not in the zip" "the default profile link dangles"
mutation engine-not-chmod "run: $ENGINE"                       "the store engine the login executes never chmod-ed"
TOOLSET_LAST="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["toolset"]["binaries"][-1])' "$DIR/../ab_cloud-terminal-store/store.json")"
mutation no-toolset-tool  "PATH command '$TOOLSET_LAST'"       "#737: a tool store.json::toolset promises on both terminals ('$TOOLSET_LAST') is missing from the shipped profile"
mutation linux-tool-tampered "linux_tools: linux-account at /usr/local/bin/linux-account is sha256" "store.json::linux_tools: a CLI whose bytes differ from the pinned sha256 is refused"
mutation linux-greeting-missing "linux_tools: fish_greeting is not baked" "store.json::linux_tools: the fish greeting missing from the zip is named"
mutation linux-cli-not-chmod "linux_tools: linux-account (/usr/local/bin/linux-account) is not in EXECUTABLES.txt" "store.json::linux_tools: a CLI never chmod-ed +x is named"
mutation no-needed-lib    "DT_NEEDED libacl.so.1"              "#846: a library an ELF loads through its RUNPATH was cut from the zip (a -dev/-doc/-man cut that took a .so)"
mutation no-loader-lib    "DT_NEEDED libc.so.6"                "#846: a library found only in the ELF loader's own dir was cut"
mutation exec-renamed     "binds nothing"                      "bin/login's proot exec line not recognised (the gate must not pass blind)"

# ── #665 GUARD 4 — the size ceiling, mutation-proved on the declaration ──
# A ceiling one byte under the clean zip must fail; a closure with the ceiling
# removed must fail too, so the budget cannot be dropped silently.
CLEAN_SIZE="$(wc -c < "$SB/clean.zip" | tr -d ' ')"
for m in under absent; do
    python3 - "$CLOSURE" "$SB/closure-$m.json" "$m" "$CLEAN_SIZE" <<'EOF2'
import json, sys
src, out, m, size = sys.argv[1:]
d = json.load(open(src))
if m == "under": d["size_ceiling_bytes"] = int(size) - 1
else: d.pop("size_ceiling_bytes", None)
json.dump(d, open(out, "w"))
EOF2
    if cmp -s "$CLOSURE" "$SB/closure-$m.json"; then bad "size mutation $m DID NOT MUTATE"; continue; fi
    if python3 "$GATE" "$SB/clean.zip" "$BUILD_JSON" "$SB/closure-$m.json" >"$SB/out" 2>&1; then
        bad "MUTATION SURVIVED (size-$m): the gate passed a rootfs with no enforceable size budget"
    elif grep -q "size_ceiling_bytes" "$SB/out"; then
        ok "mutation proved (size-$m): a rootfs over (or without) its declared size ceiling fails the bake (#665)"
    else
        bad "size mutation $m went red for the wrong reason: $(head -2 "$SB/out")"
    fi
done
if python3 -c 'import json,sys; c=json.load(open(sys.argv[1]))["size_ceiling_bytes"]; sys.exit(0 if isinstance(c,int) and 600000000 < c < 800000000 else 1)' "$CLOSURE"; then
    ok "login-closure.json declares a size ceiling in the measured band"
else
    bad "login-closure.json size_ceiling_bytes missing or outside the measured band"
fi

# A stale exemption would excuse a future real miss, so it is itself a failure.
python3 - "$CLOSURE" "$SB/stale.json" <<'EOF'
import json, sys
c = json.load(open(sys.argv[1]))
c["absent_by_design"]["nix/store/*-never-referenced"] = "planted by the tester"
json.dump(c, open(sys.argv[2], "w"))
EOF
if python3 "$GATE" "$SB/clean.zip" "$BUILD_JSON" "$SB/stale.json" >"$SB/out" 2>&1; then
    bad "MUTATION SURVIVED (stale exemption): an absent_by_design entry nothing references passed"
elif grep -q "matches no path" "$SB/out"; then
    ok "mutation proved (stale exemption): an absent_by_design entry nothing references fails the gate"
else
    bad "the stale exemption went red for the wrong reason: $(head -2 "$SB/out")"
fi

echo "── #698 a terminal/rootfs mismatch is said on the terminal screen ──"

# ── P1 — the APK knows which rootfs it was built against, from the ONE address ──
if grep -q 'buildConfigField "String", "CLOUD_ROOTFS_LIB_VERSION", "\\"" + bootstrapArtifactId(bootstrapSpec().artifact)' "$GRADLE"; then
    ok "BuildConfig.CLOUD_ROOTFS_LIB_VERSION is the content address rootfs-lib stamps (bootstrapArtifactId), not a literal"
else
    bad "app/build.gradle does not derive CLOUD_ROOTFS_LIB_VERSION from bootstrapArtifactId(bootstrapSpec().artifact)"
fi

# ── P2 — the notice compares THAT against the rootfs about to run, and names the fix ──
if grep -q 'BuildConfig.CLOUD_ROOTFS_LIB_VERSION.equals(installed)' "$INSTALLER" \
   && grep -q "static String rootfsVersionNotice()" "$INSTALLER" \
   && grep -q "from the Store's Cloud tab" "$INSTALLER"; then
    ok "TermuxInstaller.rootfsVersionNotice compares the extracted rootfs with the build's and names the Store fix"
else
    bad "TermuxInstaller has no rootfsVersionNotice comparing the extracted rootfs with BuildConfig.CLOUD_ROOTFS_LIB_VERSION"
fi

# ── P3 — the session the app opens after its bootstrap check carries it ──
if grep -A1 'mTermuxTerminalSessionActivityClient.addNewSession(launchFailsafe, null,' "$ACTIVITY" | grep -q 'TermuxInstaller.rootfsVersionNotice()'; then
    ok "TermuxActivity hands the notice to the session it opens after setupBootstrapIfNeeded"
else
    bad "TermuxActivity's post-bootstrap session does not carry TermuxInstaller.rootfsVersionNotice()"
fi

# ── P4/P5 — ordering is the whole mechanism: set before the view sizes the
# session, printed before the shell is spawned. Checked by line order, and
# mutation-proved by swapping the lines on copies.
order_ok() {  # <file> <first pattern> <second pattern>
    python3 - "$@" <<'EOF'
import sys
text = open(sys.argv[1]).read()
a, b = text.find(sys.argv[2]), text.find(sys.argv[3])
sys.exit(0 if 0 <= a < b else 1)
EOF
}
if order_ok "$CLIENT" "newTerminalSession.setPreamble(preamble);" "setCurrentSession(newTerminalSession);"; then
    ok "the preamble is set before setCurrentSession (which sizes the session and starts the shell)"
else
    bad "TermuxTerminalSessionActivityClient does not set the preamble before setCurrentSession"
fi
if order_ok "$SESSION" "mEmulator.append(line, line.length);" "JNI.createSubprocess("; then
    ok "TerminalSession prints the preamble before it spawns the shell, so it is the screen's first line"
else
    bad "TerminalSession does not print the preamble before JNI.createSubprocess"
fi
python3 - "$CLIENT" "$SB/client.java" "$SESSION" "$SB/session.java" <<'EOF'
import sys
c = open(sys.argv[1]).read()
open(sys.argv[2], "w").write(c.replace("            newTerminalSession.setPreamble(preamble);\n", "", 1)
    .replace("setCurrentSession(newTerminalSession);", "setCurrentSession(newTerminalSession);\n            newTerminalSession.setPreamble(preamble);", 1))
s = open(sys.argv[3]).read()
start = s.index("        if (mPreamble != null) {")
end = s.index("        }\n", start) + len("        }\n")
block = s[start:end]
s = s[:start] + s[end:]
at = s.index("        mShellPid = processId[0];")
open(sys.argv[4], "w").write(s[:at] + block + s[at:])
EOF
if cmp -s "$CLIENT" "$SB/client.java" || cmp -s "$SESSION" "$SB/session.java"; then
    bad "the ordering mutations DID NOT MUTATE the copies"
elif order_ok "$SB/client.java" "newTerminalSession.setPreamble(preamble);" "setCurrentSession(newTerminalSession);" \
   || order_ok "$SB/session.java" "mEmulator.append(line, line.length);" "JNI.createSubprocess("; then
    bad "MUTATION SURVIVED: moving the preamble after the view attach / after the spawn still passes the order pins"
else
    ok "mutation proved: a preamble set after attach, or printed after the spawn, fails its order pin"
fi

echo "── $fails failed ──"
[ "$fails" -eq 0 ]
