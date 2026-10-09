#!/bin/sh
# Tester for terminal-parity-guard.py: the real tree holds, and each rule goes red when broken.
#
# An assertion no mutation can break verifies nothing, so the proof list is what makes this worth
# running. The guard runs against the real tree first; then, for each mutation, a throwaway copy of
# exactly the files store.json names gets ONE edit, the edit is checked to have changed the input,
# and the guard must turn red with a message naming the broken thing (not merely exit 1).
#
# Static, offline, POSIX sh + python3. Run from anywhere:  sh ab_cloud-terminal-store/test-terminal-parity.sh
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GUARD="$ROOT/ab_cloud-terminal-store/terminal-parity-guard.py"
command -v python3 >/dev/null 2>&1 || { echo "FAIL python3 is required"; exit 1; }

exec python3 -I - "$ROOT" "$GUARD" <<'PY'
import json, os, shutil, subprocess, sys, tempfile

ROOT, GUARD = sys.argv[1], sys.argv[2]
fails = []


def guard(root):
    r = subprocess.run([sys.executable, "-I", GUARD, root], capture_output=True, text=True)
    return r.returncode, r.stderr + r.stdout


# ── the real tree holds ──
rc, out = guard(ROOT)
print(("ok   " if rc == 0 else "FAIL ") + "the real tree: " + out.strip().splitlines()[-1])
if rc != 0:
    print(out)
    fails.append("the real tree is red")

# ── a sandbox holding exactly what the guard reads ──
decl = json.load(open(os.path.join(ROOT, "ab_cloud-terminal-store", "store.json")))
need = {"ab_cloud-terminal-store/store.json", "ab_cloud-terminal-store/fetch-linux-tools.py",
        "ab_cloud-terminal-store/login-init.sh"}
for t in decl["terminals"].values():
    need.update(t["startup_files"])
    need.add(t["linux_tools"]["baked_by"])
    need.update(t["linux_tools"]["verified_by"])


def sandbox():
    box = tempfile.mkdtemp()
    for rel in need:
        dst = os.path.join(box, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy(os.path.join(ROOT, rel), dst)
    return box


def edit(box, rel, fn):
    path = os.path.join(box, rel)
    before = open(path, encoding="utf-8").read()
    after = fn(before)
    if after == before:
        return False
    open(path, "w", encoding="utf-8").write(after)
    return True


def edit_store(box, fn):
    path = os.path.join(box, "ab_cloud-terminal-store/store.json")
    data = json.load(open(path))
    fn(data)
    json.dump(data, open(path, "w"), indent=1)
    return True


def drop_lines(*needles):
    return lambda text: "".join(l for l in text.splitlines(True) if not any(n in l for n in needles))


NIX_BAKE = decl["terminals"]["nix"]["linux_tools"]["baked_by"]
TERMUX_BAKE = decl["terminals"]["termux"]["linux_tools"]["baked_by"]
NIX_VERIFY = decl["terminals"]["nix"]["linux_tools"]["verified_by"][0]
TERMUX_VERIFY = decl["terminals"]["termux"]["linux_tools"]["verified_by"][0]
NIX_STARTUP = [f for f in decl["terminals"]["nix"]["startup_files"] if f.endswith(".java")][0]
TERMUX_STARTUP = [f for f in decl["terminals"]["termux"]["startup_files"] if f.endswith("enter.sh")][0]
LOGIN_INIT = "ab_cloud-terminal-store/login-init.sh"


def add_cli(d):
    d["linux_tools"]["files"]["linux-extra"] = {
        "path": "da_linux-store/linux-extra", "sha256": "0" * 64, "install": "usr/local/bin/linux-extra",
        "mode": "0755", "role": "cli"}


def pin_branch(d):
    d["linux_tools"]["ref"] = "main"


def no_sha(d):
    d["linux_tools"]["files"]["linux-store"]["sha256"] = "abc"


def need_not_in_toolset(d):
    d["linux_tools"]["needs"].append("not-a-baked-tool")


def declare_step(d):
    d["startup"]["steps"]["a_new_step"] = "declared, implemented nowhere"


def unlist_login_init(d):
    d["terminals"]["termux"]["startup_files"].remove(LOGIN_INIT)


# (name, what to change, a fragment the red output must contain)
mutations = [
    ("nix verifier stops naming linux-account",
     lambda b: edit(b, NIX_VERIFY, drop_lines("linux-account")),
     "nix: no verifier"),
    ("termux verifier stops naming the greeting",
     lambda b: edit(b, TERMUX_VERIFY, lambda t: t.replace("fish_greeting.fish", "greeting-gone")),
     "termux: no verifier"),
    ("termux verifier stops naming linux-store",
     lambda b: edit(b, TERMUX_VERIFY, lambda t: t.replace("linux-store", "store-gone")),
     "termux: no verifier"),
    ("nix bake stops using the shared fetcher",
     lambda b: edit(b, NIX_BAKE, lambda t: t.replace("fetch-linux-tools.py", "hand-wired.py")),
     "nix: " + NIX_BAKE),
    ("termux bake stops using the shared fetcher",
     lambda b: edit(b, TERMUX_BAKE, lambda t: t.replace("fetch-linux-tools.py", "hand-wired.py")),
     "termux: " + TERMUX_BAKE),
    ("nix bake stops carrying login-init.sh",
     lambda b: edit(b, NIX_BAKE, lambda t: t.replace("login-init.sh", "login-gone.sh")),
     "nix: " + NIX_BAKE),
    ("nix loses the wipe_stale_root step the termux terminal has",
     lambda b: edit(b, NIX_STARTUP, lambda t: t.replace("startup-step: wipe_stale_root", "no step here")),
     "wipe_stale_root"),
    ("termux loses the storage_links step the nix terminal has",
     lambda b: edit(b, TERMUX_STARTUP, lambda t: t.replace("startup-step: storage_links", "no step here")),
     "storage_links"),
    ("termux gains a step only it has",
     lambda b: edit(b, TERMUX_STARTUP, lambda t: t + "\n# startup-step: termux_only_thing\n"),
     "termux_only_thing"),
    ("nix gains a step only it has",
     lambda b: edit(b, NIX_STARTUP, lambda t: t + "\n// startup-step: nix_only_thing\n"),
     "nix_only_thing"),
    ("the shared first-start step is dropped from login-init.sh (both lose it)",
     lambda b: edit(b, LOGIN_INIT, lambda t: t.replace("startup-step: linux_first_start", "no step here")),
     "linux_first_start"),
    ("the shared PATH step is dropped from login-init.sh",
     lambda b: edit(b, LOGIN_INIT, lambda t: t.replace("startup-step: linux_path", "no step here")),
     "linux_path"),
    ("a step is declared that neither terminal implements", lambda b: edit_store(b, declare_step), "a_new_step"),
    ("login-init.sh is unlisted from the termux startup files", lambda b: edit_store(b, unlist_login_init), "startup_files does not list"),
    ("a new CLI is declared but no verifier names it", lambda b: edit_store(b, add_cli), "linux-extra"),
    ("the pin becomes a branch name", lambda b: edit_store(b, pin_branch), "not a pin"),
    ("a file loses its sha256", lambda b: edit_store(b, no_sha), "sha256"),
    ("a runtime dependency is not in the toolset", lambda b: edit_store(b, need_not_in_toolset), "not-a-baked-tool"),
]

for name, change, expect in mutations:
    box = sandbox()
    try:
        if not change(box):
            print("FAIL MUTATION DID NOT APPLY: " + name)
            fails.append("mutation did not apply: " + name)
            continue
        rc, out = guard(box)
        if rc == 0:
            print("FAIL MUTATION SURVIVED: " + name)
            fails.append("survived: " + name)
        elif expect not in out:
            print("FAIL RED FOR THE WRONG REASON: %s (wanted %r)\n%s" % (name, expect, out))
            fails.append("wrong reason: " + name)
        else:
            print("ok   mutation proved: " + name + " goes red")
    finally:
        shutil.rmtree(box, ignore_errors=True)

# the baseline sandbox (unedited copy) is green: the mutations' red is theirs, not the sandbox's
box = sandbox()
try:
    rc, out = guard(box)
    print(("ok   " if rc == 0 else "FAIL ") + "an unedited sandbox copy is green")
    if rc != 0:
        print(out)
        fails.append("the unedited sandbox is red")
finally:
    shutil.rmtree(box, ignore_errors=True)

if fails:
    print("FAILED terminal parity tester: " + "; ".join(fails))
    sys.exit(1)
print("PASS terminal parity tester")
PY
