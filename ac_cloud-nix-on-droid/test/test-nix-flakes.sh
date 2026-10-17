#!/bin/sh
# ~/flakes/{system,hm}, the Android host tool wrappers and the /etc/zshenv guard.
#
#  1. zshenv: every zsh printed
#       /etc/zshenv:.:1: no such file or directory: /nix/store/…-nix-on-droid-session-init.sh/etc/profile.d/…
#     upstream's shell.nix renders etc/static/zshenv as `. "${sessionInit}/etc/profile.d/…"`.
#     The path shape is right (sessionInit is a writeTextFile with a destination, i.e. a
#     DIRECTORY whose name ends in .sh), but the zip never ships that store path. It is
#     guarded like etc/profile (#641). Executed under zsh (bash if zsh is absent), both
#     branches, and mutation-proved against the unpatched line.
#  2. the flakes render from the REAL templates and build.json, with no placeholder
#     left, pins equal to the bake's, and parse as nix (nix-instantiate --parse when
#     nix is on PATH, a static check otherwise; CLOUD_FLAKES_EVAL=1 adds `nix flake check`).
#  3. seed.sh creates the layout and is idempotent (a user edit survives a re-seed).
#  4. the wrappers exist for every declared tool and exec the HOST binary, with the
#     glibc loader variables removed.
# Offline: no network, no Android SDK.
set -u
DIR="$(cd "$(dirname "$0")/.." && pwd)"
fails=0
ok()  { echo "  ok   — $1"; }
bad() { echo "  FAIL — $1"; fails=$((fails + 1)); }
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "── test-nix-flakes ──"
python3 - "$DIR" "$WORK" <<'PYEOF' > "$WORK/py.out" 2>&1
import json, os, re, shutil, subprocess, sys
root, work = sys.argv[1:3]
sys.path.insert(0, f"{root}/app/src/main/cpp")
import bake_default_packages as bake

res = []
def check(cond, msg):
    res.append(("ok" if cond else "FAIL") + " " + msg)

bj = json.load(open(f"{root}/build.json"))
dp = bj["forks"]["nixdroid"]["bootstrap"]["default_packages"]
fallback = dp["fallback_init_script"]
app_id = bj["forks"]["nixdroid"]["bootstrap"]["package_name_rewrite"]["to"]
nod_rev = json.load(open(f"{root}/bootstrap-src/bootstrap-src.json"))["upstream_rev"]

# ── 1. zshenv ─────────────────────────────────────────────────────────────
H = "34f8pm3n1cgd456vyr3pr0m2bx27fdwm"  # the hash the phone printed
ORIGINAL = f'. "/nix/store/{H}-nix-on-droid-session-init.sh/etc/profile.d/nix-on-droid-session-init.sh"\n'
patched = bake.patch_etc_profile(ORIGINAL, fallback)
src_path = ORIGINAL.split('"')[1]
check(re.fullmatch(r"/nix/store/[0-9a-z]{32}-nix-on-droid-session-init\.sh/etc/profile\.d/nix-on-droid-session-init\.sh", src_path)
      and f'[ -e "{src_path}" ]' in patched,
      "zshenv: the guarded path is upstream's <sessionInit dir>/etc/profile.d/nix-on-droid-session-init.sh")
shell = shutil.which("zsh") or "bash"
os.makedirs(os.path.dirname(f"{work}/{fallback}"), exist_ok=True)
open(f"{work}/{fallback}", "w").write("echo FALLBACK_SOURCED\n")

def run(text, name, real):
    r = f"{work}/{name}"; os.makedirs(r, exist_ok=True)
    body = text.replace('"/nix/', f'"{r}/nix/').replace(f". /{fallback}", f". {work}/{fallback}")
    open(f"{r}/zshenv", "w").write(body)
    if real:
        d = f"{r}/nix/store/{H}-nix-on-droid-session-init.sh/etc/profile.d"
        os.makedirs(d, exist_ok=True)
        open(f"{d}/nix-on-droid-session-init.sh", "w").write("echo REAL_SESSION_INIT\n")
    p = subprocess.run([shell, f"{r}/zshenv"], capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr

rc, out = run(patched, "fresh", False)
check(rc == 0 and "FALLBACK_SOURCED" in out and "no such file" not in out.lower(),
      f"zshenv ({os.path.basename(shell)}): a fresh install sources the fallback, silently ({out.strip()!r})")
rc, out = run(patched, "real", True)
check(rc == 0 and "REAL_SESSION_INIT" in out, f"zshenv: a real generation is still sourced ({out.strip()!r})")
rc, out = run(ORIGINAL, "mutant", False)
check(rc != 0 or "no such file" in out.lower(), "mutation proved: the unpatched zshenv line fails as on the phone")
bake_src = open(bake.__file__).read()
check('ETC_ZSHENV_ENTRY = "etc/static/zshenv"' in bake_src and "name == ETC_ZSHENV_ENTRY" in bake_src,
      "the bake writes the guarded etc/static/zshenv into the zip")

# ── 2. the flakes render ──────────────────────────────────────────────────
decl = bake.load_flakes_decl()
ex = dp["extras"]
files = bake.render_flakes(decl, app_id, dp["nixpkgs_pin"], nod_rev, dp["attrs"],
                           dp["login_shell_attr"], "aarch64-linux", ex["attrs"], ex)
inst = decl["install_dir"]
for want in ["system/rootfs-extras.nix", "system/extras.json", "system/rootfs.json", "system/noexec-shebang.c", "seed.sh", "system/flake.nix", "system/nix-on-droid.nix", "system/android-host-tools.nix",
             "system/android-host-exec", "system/README.md", "hm/flake.nix", "hm/home.nix", "hm/README.md"]:
    check(f"{inst}/{want}" in files, f"rendered {inst}/{want}")
text = {k: v[0].decode() for k, v in files.items()}
check(not any(re.search(r"@[A-Z_]+@", t) for t in text.values()), "no @PLACEHOLDER@ survives rendering")
sysf, hmf = text[f"{inst}/system/flake.nix"], text[f"{inst}/hm/flake.nix"]
check(f"github:NixOS/nixpkgs/{dp['nixpkgs_pin']}" in sysf and f"github:NixOS/nixpkgs/{dp['nixpkgs_pin']}" in hmf,
      "both flakes pin the bake's nixpkgs")
check(f"nix-community/nix-on-droid/{nod_rev}" in sysf and f"nix-community/nix-on-droid/{nod_rev}" in hmf,
      "both flakes pin the bootstrap's nix-on-droid revision")
check("nixOnDroidConfigurations.default" in sysf and "hm.homeModules.default" in sysf
      and f'path:{bake.flake_home(app_id)}/{decl["home_dir"]}/hm' in sysf,
      "the system flake is the nixOnDroidConfiguration and wires ~/flakes/hm in as its home-manager module")
check("homeModules.default" in hmf and 'home-manager.follows = "nix-on-droid/home-manager"' in hmf,
      "the hm flake exports the module and follows nix-on-droid's home-manager")
mod = text[f"{inst}/system/nix-on-droid.nix"]
check(all(f'pkgs."{a}"' in mod for a in dp["attrs"]) and f'pkgs.{dp["login_shell_attr"]}' in mod
      and "android-host-tools.nix" in mod, "the system module installs every baked attr, the login shell and android-host-tools")
# The switch must reproduce the bake: the flake's package set is a superset of
# default_packages.attrs + extras.attrs (parsed from the rendered module, not trusted).
pk = mod.split("environment.packages = [", 1)[1].split("];", 1)[0]
flake_set = set(re.findall(r'pkgs\."([^"]+)"', pk)) | {"extras." + a for a in re.findall(r'extras\."([^"]+)"', pk)}
baked_set = set(dp["attrs"]) | {"extras." + a for a in ex["attrs"]}
check(baked_set <= flake_set, f"the flake's packages are a superset of the baked set (missing: {sorted(baked_set - flake_set)})")
mut_pk = pk.replace('extras."goose"', "")
mut_set = set(re.findall(r'pkgs\."([^"]+)"', mut_pk)) | {"extras." + a for a in re.findall(r'extras\."([^"]+)"', mut_pk)}
check(not baked_set <= mut_set, "mutation proved: a module without goose fails the superset check")
xn = text[f"{inst}/system/rootfs-extras.nix"]
check(all(f"{inst}/system/{n}" in files for n in ("rootfs-extras.nix", "extras.json", "rootfs.json", "noexec-shebang.c"))
      and "import ./rootfs-extras.nix { inherit pkgs; }" in mod,
      "rootfs-extras.nix ships beside its inputs and the module imports it")
check(not any("../" in l for l in xn.splitlines() if not l.lstrip().startswith("#"))
      and "./rootfs.json" in xn and "./extras.json" in xn and "./noexec-shebang.c" in xn,
      "the copied rootfs-extras.nix reads only files beside it")
xd = json.loads(text[f"{inst}/system/extras.json"])
check(xd == ex and all(a in re.findall(r"^  ([a-z][a-z0-9-]*) =", xn, re.M) for a in ex["attrs"]),
      "extras.json carries the bake's pins and rootfs-extras.nix defines every extras attr")
aht = text[f"{inst}/system/android-host-tools.nix"]
check(all(t in aht.split("for t in", 1)[1].split(";", 1)[0].split() for t in decl["android_host"]["tools"]),
      "android-host-tools links every declared tool")

out_dir = f"{work}/render"
for rel, (data, ex) in files.items():
    p = f"{out_dir}/{rel}"; os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "wb").write(data)
    if ex: os.chmod(p, 0o755)
nixes = [f"{out_dir}/{k}" for k in files if k.endswith(".nix")]

def static_ok(t):
    s = re.sub(r"''.*?''", "''", t, flags=re.S)
    s = re.sub(r'"(\\.|[^"\\])*"', '""', s)
    s = re.sub(r"#[^\n]*", "", s)
    depth = {"{": 0, "[": 0, "(": 0}; pair = {"}": "{", "]": "[", ")": "("}
    for c in s:
        if c in depth: depth[c] += 1
        elif c in pair:
            depth[pair[c]] -= 1
            if depth[pair[c]] < 0: return False
    return all(v == 0 for v in depth.values()) and s.count('"') % 2 == 0

if shutil.which("nix-instantiate"):
    for n in nixes:
        p = subprocess.run(["nix-instantiate", "--parse", n], capture_output=True, text=True)
        check(p.returncode == 0, f"nix-instantiate --parse {os.path.relpath(n, out_dir)} {p.stderr.strip()[:200]}")
else:
    for n in nixes:
        check(static_ok(open(n).read()), f"static parse {os.path.relpath(n, out_dir)} (nix not on PATH)")
check(not static_ok("{ a = { b = 1; };"), "mutation proved: the static parse rejects an unbalanced flake")
if os.environ.get("CLOUD_FLAKES_EVAL") == "1" and shutil.which("nix"):
    for sub in ("hm", "system"):
        p = subprocess.run(["nix", "--extra-experimental-features", "nix-command flakes", "flake", "check",
                            "--no-build", "--no-write-lock-file", f"path:{out_dir}/{inst}/{sub}"],
                           capture_output=True, text=True)
        check(p.returncode == 0, f"nix flake check {sub}: {p.stderr.strip()[-300:]}")

# ── 3. seed.sh: layout and idempotence ────────────────────────────────────
home = f"{work}/home"; os.makedirs(home)
env = {**os.environ, "HOME": home}
seed = f"{out_dir}/{inst}/seed.sh"
p = subprocess.run(["sh", seed, decl["home_dir"]], env=env, capture_output=True, text=True)
fl = f"{home}/{decl['home_dir']}"
check(p.returncode == 0 and os.path.isfile(f"{fl}/system/flake.nix") and os.path.isfile(f"{fl}/hm/flake.nix")
      and os.path.isfile(f"{fl}/system/README.md") and os.path.isfile(f"{fl}/hm/README.md")
      and os.access(f"{fl}/system/android-host-exec", os.X_OK),
      f"seed.sh creates ~/{decl['home_dir']}/system and ~/{decl['home_dir']}/hm ({p.stderr.strip()!r})")
open(f"{fl}/hm/home.nix", "w").write("# mine\n{ ... }: { }\n")
p = subprocess.run(["sh", seed, decl["home_dir"]], env=env, capture_output=True, text=True)
check(p.returncode == 0 and open(f"{fl}/hm/home.nix").read().startswith("# mine"),
      "seed.sh is idempotent: a second login keeps the user's edit")
line = bake.flakes_seed_line(inst, decl["home_dir"])
check(f'sh "/{inst}/seed.sh"' in line and "|| true" in line, "the login-inner seed line is guarded and never fatal")
check("flakes_seed_line(flakes_decl" in bake_src, "the bake passes the seed line to patch_login_inner")

# ── 4. wrappers point at the host binaries ────────────────────────────────
hf = bake.android_host_files(decl)
bdir = decl["android_host"]["bin_dir"]
check(all(f"{bdir}/{t}" in hf and hf[f"{bdir}/{t}"][1] for t in decl["android_host"]["tools"]),
      f"a wrapper ships, executable, for each of {decl['android_host']['tools']}")
fake = f"{work}/fakeandroid"; os.makedirs(f"{fake}/system/bin")
stub = '#!/bin/sh\necho "HOST:${0##*/} ARGS:$* LD:${LD_LIBRARY_PATH-unset}"\n'
for n in ("getprop", "toybox"):
    open(f"{fake}/system/bin/{n}", "w").write(stub); os.chmod(f"{fake}/system/bin/{n}", 0o755)
wsrc = hf[f"{bdir}/getprop"][0].decode()
check("for root in /system /android/system" in wsrc, "the wrapper resolves /system/bin, then /android/system/bin")
wtext = wsrc.replace("for root in /system /android/system", f"for root in {fake}/system")
wb = f"{work}/wbin"; os.makedirs(wb)
for n in ("getprop", "android-ls", "pm"):
    open(f"{wb}/{n}", "w").write(wtext); os.chmod(f"{wb}/{n}", 0o755)
renv = {**os.environ, "LD_LIBRARY_PATH": "/nix/store/x-glibc/lib"}
p = subprocess.run([f"{wb}/getprop", "ro.build.version.sdk"], env=renv, capture_output=True, text=True)
check(p.stdout.strip() == "HOST:getprop ARGS:ro.build.version.sdk LD:unset",
      f"getprop execs the host getprop with LD_LIBRARY_PATH removed ({p.stdout.strip()!r})")
p = subprocess.run([f"{wb}/android-ls", "/sdcard"], env=renv, capture_output=True, text=True)
check(p.stdout.strip().startswith("HOST:toybox ARGS:ls /sdcard"), f"android-ls is toybox ls ({p.stdout.strip()!r})")
p = subprocess.run([f"{wb}/pm", "list"], env=renv, capture_output=True, text=True)
check(p.returncode == 127 and "no pm" in p.stderr, "a tool the host lacks fails loudly (127), never silently")

for r in res:
    print("RESULT:" + r)
PYEOF
rc=$?
if ! grep -q '^RESULT:' "$WORK/py.out"; then
    bad "the python checks did not run (rc=$rc): $(tail -5 "$WORK/py.out")"
fi
while IFS= read -r line; do
    case "$line" in
        RESULT:ok\ *) ok "${line#RESULT:ok }" ;;
        RESULT:FAIL\ *) bad "${line#RESULT:FAIL }" ;;
    esac
done < "$WORK/py.out"
[ "$rc" -eq 0 ] || bad "python exited $rc: $(grep -v '^RESULT:' "$WORK/py.out" | tail -5)"

echo "── $fails failed ──"
[ "$fails" -eq 0 ]
