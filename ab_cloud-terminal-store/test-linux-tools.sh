#!/bin/sh
# Tester for what both terminals do with store.json::linux_tools.
#
#   F  fetch-linux-tools.py: a file that differs from its pinned sha256 is refused BY NAME, a
#      missing one is an error, a branch name is not a pin, and nothing is written for a failure.
#   R  the real pin: the three files at the declared commit pass their sha256 and are what they say
#      they are (sh syntax for the CLIs, a fish function for the greeting). Needs the network;
#      LINUX_TOOLS_NO_NETWORK=1 skips only this part.
#   L  login-init.sh's first-start step, run in a real pty (its notices only print on a terminal):
#        - /usr/local/bin joins PATH and /usr/share joins XDG_DATA_DIRS (only when it was left out)
#        - nothing present   -> ONE line naming every missing prerequisite and the exact command
#        - one missing       -> the line names only that one, and `linux-store switch` is NOT run
#        - all present       -> `linux-store switch` runs once, in the background, and not again
#        - already switched  -> nothing runs, nothing is printed
#        - not a terminal    -> no output at all (scripted sessions parse stdout/stderr)
#        - an age key is only TESTED for existence: its content never reaches an output or a log
#        - the step survives `set -eu` in whatever sources it
#   M  mutations of fetch-linux-tools.py and login-init.sh: each rule broken once must go red.
#
# Offline except R. POSIX sh + python3.
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
command -v python3 >/dev/null 2>&1 || { echo "FAIL python3 is required"; exit 1; }

exec python3 -I - "$ROOT" <<'PY'
import hashlib, json, os, pty, select, shutil, subprocess, sys, tempfile, time

ROOT = sys.argv[1]
STORE = os.path.join(ROOT, "ab_cloud-terminal-store")
FETCH = os.path.join(STORE, "fetch-linux-tools.py")
INIT = os.path.join(STORE, "login-init.sh")
fails = []
NO_NET = os.environ.get("LINUX_TOOLS_NO_NETWORK") == "1"


def ok(label):
    print("ok   " + label)


def bad(label, detail=""):
    print("FAIL " + label + (("\n     " + detail.replace("\n", "\n     ")) if detail else ""))
    fails.append(label)


def sh(argv, env=None, cwd=None, timeout=120):
    r = subprocess.run(argv, capture_output=True, text=True, env=env, cwd=cwd, timeout=timeout)
    return r.returncode, r.stdout + r.stderr


# ───────────────────────── F: the fetcher refuses what is not pinned ─────────────────────────
def fixture(tmp, ref=None, tamper=None, drop=None):
    """A synthetic store.json (its own files, its own hashes) and the local mirror it points at."""
    decl = json.load(open(os.path.join(STORE, "store.json")))
    src = os.path.join(tmp, "src")
    for name, f in decl["linux_tools"]["files"].items():
        data = ("#!/bin/sh\n# fixture " + name + "\n").encode()
        f["sha256"] = hashlib.sha256(data).hexdigest()
        dest = os.path.join(src, f["path"])
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        if name != drop:
            open(dest, "wb").write(data + (b"# tampered\n" if name == tamper else b""))
    if ref:
        decl["linux_tools"]["ref"] = ref
    path = os.path.join(tmp, "store.json")
    json.dump(decl, open(path, "w"))
    return path, src


def fetch_env(store, src):
    env = dict(os.environ, STORE_JSON=store, LINUX_TOOLS_SRC=src)
    return env


tmp = tempfile.mkdtemp()
try:
    store, src = fixture(tmp)
    out = os.path.join(tmp, "out")
    rc, text = sh([sys.executable, "-I", FETCH, "fetch", out], env=fetch_env(store, src))
    files = sorted(os.path.join(dp, f) for dp, _, fs in os.walk(out) for f in fs)
    if rc == 0 and len(files) == 3 and oct(os.stat(os.path.join(out, "usr/local/bin/linux-store")).st_mode & 0o777) == "0o755":
        ok("F fetch writes every pinned file at its install path with its declared mode")
    else:
        bad("F fetch of a matching fixture", text)

    for label, kw, want in (("a byte-changed file", {"tamper": "linux-account"}, "linux-account"),
                            ("a missing file", {"drop": "fish_greeting"}, "fish_greeting"),
                            ("a branch name as the pin", {"ref": "main"}, "not a pin")):
        d = tempfile.mkdtemp(dir=tmp)
        store, src = fixture(d, **kw)
        out = os.path.join(d, "out")
        rc, text = sh([sys.executable, "-I", FETCH, "fetch", out], env=fetch_env(store, src))
        wrote = os.path.exists(out) and any(fs for _, _, fs in os.walk(out))
        if rc != 0 and want in text and not wrote:
            ok("F " + label + " is refused by name and nothing is written")
        else:
            bad("F " + label + " must be refused, naming " + want + " and writing nothing", "rc=%s wrote=%s\n%s" % (rc, wrote, text))
finally:
    shutil.rmtree(tmp, ignore_errors=True)

# ───────────────────────── R: the real pin ─────────────────────────
if NO_NET:
    print("skip R the real pin (LINUX_TOOLS_NO_NETWORK=1)")
else:
    d = tempfile.mkdtemp()
    try:
        rc, text = sh([sys.executable, "-I", FETCH, "fetch", d], timeout=300)
        if rc != 0:
            bad("R the pinned files fetch and pass their sha256", text)
        else:
            ok("R the pinned files fetch and pass their sha256")
            for cli in ("linux-store", "linux-account"):
                p = os.path.join(d, "usr/local/bin", cli)
                head = open(p, "rb").read(12)
                rc2, t2 = sh(["sh", "-n", p])
                if head.startswith(b"#!/bin/sh") and rc2 == 0:
                    ok("R %s is a #!/bin/sh script that parses" % cli)
                else:
                    bad("R %s must be a parsing #!/bin/sh script" % cli, t2)
            g = open(os.path.join(d, "usr/share/fish/vendor_functions.d/fish_greeting.fish")).read()
            if g.lstrip().startswith("function fish_greeting"):
                ok("R the greeting defines function fish_greeting")
            else:
                bad("R the greeting must define function fish_greeting")
    finally:
        shutil.rmtree(d, ignore_errors=True)

# ───────────────────────── L: login-init.sh first start, in a pty ─────────────────────────
SENTINEL = "AGE-SECRET-KEY-SENTINEL-must-never-be-printed"


def world(init_path, key=True, base=True, decl=True, switched=False):
    w = tempfile.mkdtemp()
    home = os.path.join(w, "home")
    os.makedirs(os.path.join(w, "bin"))
    os.makedirs(home)
    fake = os.path.join(w, "bin", "linux-store")
    open(fake, "w").write('#!/bin/sh\necho "switch $* key=${SOPS_AGE_KEY_FILE:-}" >> "$HOME/ran.log"\n')
    os.chmod(fake, 0o755)
    gitbase = os.path.join(home, "cloud-drive-shared-store", "git")
    if base:
        os.makedirs(gitbase)
    if decl and base:
        cfg = os.path.join(gitbase, "cloud-me_configs")
        os.makedirs(os.path.join(cfg, "A", "deb-user-configs"))
        json.dump({"configs_users": [{"id": "diego-admin", "path": "A"}]}, open(os.path.join(cfg, "configs.json"), "w"))
        json.dump({}, open(os.path.join(cfg, "A", "deb-user-configs", "linux-store.json"), "w"))
    if key:
        os.makedirs(os.path.join(home, ".config", "sops", "age"))
        open(os.path.join(home, ".config", "sops", "age", "keys.txt"), "w").write(SENTINEL + "\n")
    if switched:
        os.makedirs(os.path.join(home, ".linux-store"))
        os.symlink("generations/termux-1", os.path.join(home, ".linux-store", "current"))
    return w, home


def start(init_path, w, home, tty=True, env_extra=None, wrapper="sh -c"):
    """Run one 'login' (source login-init.sh, print PATH/XDG) and return (output, ran.log, PATH, XDG)."""
    env = {"PATH": os.path.join(w, "bin") + ":/usr/bin:/bin", "HOME": home}
    env.update(env_extra or {})
    script = '. "%s"; echo "PATH=$PATH"; echo "XDG=${XDG_DATA_DIRS-unset}"' % init_path
    if wrapper == "sh -euc":
        argv = ["sh", "-euc", script]
    else:
        argv = ["sh", "-c", script]
    if tty:
        master, slave = pty.openpty()
        p = subprocess.Popen(argv, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=slave, close_fds=True)
        os.close(slave)
        notice = b""
        deadline = time.time() + 20
        while time.time() < deadline:
            r, _, _ = select.select([master], [], [], 0.2)
            if r:
                try:
                    chunk = os.read(master, 4096)
                except OSError:
                    break
                if not chunk:
                    break
                notice += chunk
            elif p.poll() is not None:
                break
        stdout = p.communicate(timeout=20)[0].decode()
        os.close(master)
        notice = notice.decode().replace("\r\n", "\n")
    else:
        p = subprocess.run(argv, env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=30)
        stdout, notice = p.stdout, p.stderr
    time.sleep(0.6)  # the switch runs in the background
    ran = os.path.join(home, "ran.log")
    log = open(ran).read() if os.path.exists(ran) else ""
    lines = dict(l.split("=", 1) for l in stdout.splitlines() if "=" in l)
    return notice, log, lines.get("PATH", ""), lines.get("XDG", "")


def lcase(init_path, tag):
    """The behaviours; returns a list of violations (empty = holds)."""
    v = []
    # nothing present
    w, home = world(init_path, key=False, base=False, decl=False)
    try:
        notice, log, path, xdg = start(init_path, w, home)
        lines = [l for l in notice.splitlines() if l.strip()]
        if len(lines) != 1:
            v.append("L1 nothing present must print exactly ONE line, got %d: %r" % (len(lines), notice))
        else:
            for need in ("git base", "cloud-me_configs", "age key", "linux-store switch"):
                if need not in lines[0]:
                    v.append("L1 the one line must name %r: %r" % (need, lines[0]))
        if log:
            v.append("L2 linux-store switch ran with nothing present: %r" % log)
        if "/usr/local/bin" not in path.split(":"):
            v.append("L3 /usr/local/bin was not put on PATH: %s" % path)
        if xdg != "unset":
            v.append("L3 an unset XDG_DATA_DIRS (default already has /usr/share) must stay unset, got %s" % xdg)
        notice, log, path, xdg = start(init_path, w, home, env_extra={"XDG_DATA_DIRS": "/opt/share"})
        if xdg != "/opt/share:/usr/share":
            v.append("L3 /usr/share must be appended to a XDG_DATA_DIRS that lacks it, got %s" % xdg)
        notice, log, path, xdg = start(init_path, w, home, env_extra={"XDG_DATA_DIRS": "/opt/share:/usr/share"})
        if xdg != "/opt/share:/usr/share":
            v.append("L3 XDG_DATA_DIRS that has /usr/share must be left alone, got %s" % xdg)
    finally:
        shutil.rmtree(w, ignore_errors=True)
    # only the key missing
    w, home = world(init_path, key=False)
    try:
        notice, log, _, _ = start(init_path, w, home)
        if "age key" not in notice or "git base" in notice or "declaration" in notice:
            v.append("L4 only the key is missing, the line must name only it: %r" % notice)
        if log:
            v.append("L4 switch ran without an age key: %r" % log)
    finally:
        shutil.rmtree(w, ignore_errors=True)
    # all present
    w, home = world(init_path)
    try:
        notice, log, _, _ = start(init_path, w, home)
        if len(log.splitlines()) != 1:
            v.append("L5 with every prerequisite the switch must run once, ran: %r" % log)
        if "missing" in notice:
            v.append("L5 nothing is missing, yet: %r" % notice)
        notice2, log2, _, _ = start(init_path, w, home)
        if len(log2.splitlines()) != 1:
            v.append("L6 the next start must not run the switch again: %r" % log2)
        for text in (notice, notice2, log, log2):
            if SENTINEL in text:
                v.append("L7 the age key's content reached an output or a log")
    finally:
        shutil.rmtree(w, ignore_errors=True)
    # already switched
    w, home = world(init_path, switched=True)
    try:
        notice, log, _, _ = start(init_path, w, home)
        if log or notice.strip():
            v.append("L8 an already switched home must run and print nothing: %r %r" % (log, notice))
    finally:
        shutil.rmtree(w, ignore_errors=True)
    # not a terminal
    w, home = world(init_path, key=False, base=False, decl=False)
    try:
        notice, log, _, _ = start(init_path, w, home, tty=False)
        if notice.strip():
            v.append("L9 a session that is not a terminal must get no notice: %r" % notice)
    finally:
        shutil.rmtree(w, ignore_errors=True)
    # set -eu in the sourcing shell
    w, home = world(init_path, key=False, base=False, decl=False)
    try:
        notice, log, path, _ = start(init_path, w, home, wrapper="sh -euc")
        if not path:
            v.append("L10 the step killed a shell running under set -eu")
    finally:
        shutil.rmtree(w, ignore_errors=True)
    return v


v = lcase(INIT, "real")
if v:
    for x in v:
        bad(x)
else:
    ok("L the first-start step behaves in every case (L1-L10)")

text = open(INIT).read()
fn = text[text.index("cloud_linux_first_start() {"):text.index("cloud_linux_first_start || true")]
for forbidden in ("age-keygen", "sops ", "cat \"$_lkey", "< \"$_lkey", "$(cat", "base64", "> \"$_lkey"):
    if forbidden in fn:
        bad("L11 the first-start step must never read, generate or write a key (found %r)" % forbidden)
        break
else:
    ok("L the first-start step contains nothing that reads, generates or writes a key")

# ───────────────────────── M: mutations ─────────────────────────
def mutate(label, rel, old, new, expect):
    """Break one rule in a scratch copy; the stated case must turn red."""
    d = tempfile.mkdtemp()
    try:
        path = os.path.join(d, os.path.basename(rel))
        body = open(os.path.join(ROOT, rel)).read()
        if old not in body:
            bad("M mutation does not apply: " + label)
            return
        open(path, "w").write(body.replace(old, new, 1))
        if rel.endswith("login-init.sh"):
            got = lcase(path, "mutant")
            if any(g.startswith(expect) for g in got):
                ok("M mutation proved: %s goes red (%s)" % (label, expect))
            else:
                bad("M mutation survived: " + label, "\n".join(got) or "(no violation)")
        else:
            # the fetcher: run F's tampered case against the mutant
            t = tempfile.mkdtemp(dir=d)
            store, src = fixture(t, tamper="linux-account")
            out = os.path.join(t, "out")
            rc, text2 = sh([sys.executable, "-I", path, "fetch", out], env=fetch_env(store, src))
            wrote = os.path.exists(out) and any(fs for _, _, fs in os.walk(out))
            if rc == 0 or wrote:
                ok("M mutation proved: %s goes red (%s)" % (label, expect))
            else:
                bad("M mutation survived: " + label)
    finally:
        shutil.rmtree(d, ignore_errors=True)


FETCHER = "ab_cloud-terminal-store/fetch-linux-tools.py"
LI = "ab_cloud-terminal-store/login-init.sh"
mutate("the fetcher stops comparing hashes", FETCHER, "if got != f[\"sha256\"]:", "if False:", "F")
mutate("switch runs without an age key", LI, '[ -s "$_lkey" ] || [ -n "${SOPS_AGE_KEY:-}" ] || _lmiss="$_lmiss the age key ($_lkey),"', ":", "L4")
mutate("the switch runs on every start", LI, '    if [ -e "$_lh/.linux-store/.first-switch-started" ]; then', "    if false; then", "L6")
mutate("the notices print on every session, terminal or not", LI, "_ltty=0; [ -t 2 ] && _ltty=1", "_ltty=1", "L9")
mutate("the missing line forgets the command to run", LI, "place it, then run: linux-store switch", "place it", "L1")
mutate("the step is fatal under set -e", LI, "cloud_linux_first_start || true", "cloud_linux_first_start; false", "L10")
mutate("a switched home is switched again", LI, '    [ -L "$_lh/.linux-store/current" ] && return 0\n', "", "L8")
mutate("/usr/local/bin is never put on PATH", LI, '*) PATH="${PATH:+$PATH:}/usr/local/bin"; export PATH ;;', "*) ;;", "L3")

if fails:
    print("FAILED linux-tools tester: %d problem(s)" % len(fails))
    sys.exit(1)
print("PASS linux-tools tester")
PY
