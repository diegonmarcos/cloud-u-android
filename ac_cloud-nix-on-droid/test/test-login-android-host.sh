#!/bin/sh
# bin/login runs in mksh on the Android host BEFORE proot, with a PATH that holds no tools. Guards
# for the phone's login errors (Samsung A37, Android 16, app uid 10597):
#  1. no `printf` in the generated pre-proot text ("login[186]: printf: inaccessible or not found");
#  2. the rish binds only when RishBridge staged their sources ("can't sanitize binding rish.env");
#  3. /etc/passwd + /etc/group get a nix-on-droid entry for the app's REAL uid/gid ("whoami:
#     cannot find name for user ID 10597"), replacing a stale one, idempotently.
set -u
DIR="$(cd "$(dirname "$0")/.." && pwd)"
BAKE_PY="$DIR/app/src/main/cpp/bake_default_packages.py"
fails=0
ok()  { echo "  ok   — $1"; }
bad() { echo "::error::FAIL — $1"; fails=$((fails + 1)); }
T="$(mktemp -d)" && [ -n "$T" ] && [ -d "$T" ] || { echo "::error::FAIL — mktemp -d failed"; exit 1; }
trap 'rm -rf "$T"' EXIT

python3 - "$BAKE_PY" "$T" <<'PY' || bad "the generated bin/login text (see above)"
import importlib.util, os, re, subprocess, sys
spec = importlib.util.spec_from_file_location("bake", sys.argv[1])
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
t = sys.argv[2]
app, profile = "cld.termux.nix", "nix/var/nix/profiles/per-user/nix-on-droid/profile"
pre = f"/data/data/{app}/files"
fails = []

# 1. printf
text = (m.BIN_LOGIN_TRACE + m.storage_setup("CloudDrive") + m.passwd_setup(app, f"/{profile}/bin/fish")
        + m.render_local_webserver(m.webserver_build_json()))
code = "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("#"))
if re.search(r"(^|[\s;&|({`$])printf\b", code, re.M):
    fails.append("bin/login's pre-proot text calls printf (not a mksh builtin, not on its PATH)")

# 2. rish: conditional binds
login = (f"#!/system/bin/sh\nset -e\nexec {pre}/usr/bin/proot-static \\\n  -b {pre}/usr/etc:/etc \\\n"
         f'  {pre}/usr/bin/sh {pre}/usr/usr/lib/login-inner "$@"\n')
out = m.patch_bin_login_rish(login, app)
if m.shizuku_client_binds():
    if re.search(r"^\s*-b \S*rish", out, re.M):
        fails.append("rish is bound unconditionally in the proot exec")
    if "$CLOUD_RISH_BINDS" not in out:
        fails.append("the proot exec does not carry $CLOUD_RISH_BINDS")
    setup = "\n".join(l for l in out.splitlines() if "CLOUD_RISH_BINDS=" in l).replace(pre, t + "/files")
    os.makedirs(t + "/files/usr/rish", exist_ok=True)
    def binds():
        return subprocess.run(["sh", "-c", setup + '\necho "$CLOUD_RISH_BINDS"'], capture_output=True, text=True).stdout.strip()
    if binds():
        fails.append(f"rish binds without staged sources: {binds()!r}")
    open(t + "/files/usr/rish/rish", "w").close()
    b = binds()
    if "rish/rish:/usr/local/bin/rish" not in b or "rish.env" in b:
        fails.append(f"only the staged rish source should bind: {b!r}")

# 3. passwd
sysbin = t + "/system/bin"
os.makedirs(sysbin, exist_ok=True)
for c in ("grep", "mv"):
    os.symlink(subprocess.run(["sh", "-c", f"command -v {c}"], capture_output=True, text=True).stdout.strip(), f"{sysbin}/{c}")
with open(f"{sysbin}/id", "w") as f:
    f.write('#!/bin/sh\ncase "$1" in -u) echo 10597 ;; -g) echo 10597 ;; esac\n')
os.chmod(f"{sysbin}/id", 0o755)
etc = t + "/files/usr/etc"
os.makedirs(etc, exist_ok=True)
open(etc + "/passwd", "w").write("root:x:0:0:root:/root:/bin/sh\nnix-on-droid:x:1000:1000:nix-on-droid:/old:/bin/sh\n")
open(etc + "/group", "w").write("root:x:0:\nnix-on-droid:x:1000:\n")
script = ('cloud_trace() { :; }\n' + m.passwd_setup(app, f"/{profile}/bin/fish")).replace(pre, t + "/files").replace("/system/bin/", sysbin + "/")
home = pre + "/home"
for _ in range(2):
    r = subprocess.run(["/bin/sh", "-ec", script], env={"HOME": home, "PATH": "/nonexistent"}, capture_output=True, text=True)
    if r.returncode or r.stderr:
        fails.append(f"passwd setup failed: rc={r.returncode} {r.stderr!r}")
want = f"nix-on-droid:x:10597:10597:nix-on-droid:{home}:/{profile}/bin/fish"
pw = open(etc + "/passwd").read().splitlines()
gr = open(etc + "/group").read().splitlines()
if pw != ["root:x:0:0:root:/root:/bin/sh", want]:
    fails.append(f"/etc/passwd is {pw!r}, want root + {want!r}")
if gr != ["root:x:0:", "nix-on-droid:x:10597:"]:
    fails.append(f"/etc/group is {gr!r}")

for f in fails:
    print("::error::FAIL — " + f)
sys.exit(1 if fails else 0)
PY
[ "$fails" = 0 ] && ok "no printf, conditional rish binds, passwd/group entry for the real uid" || exit 1
