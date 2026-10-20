#!/bin/sh
# #1033 -- the Nix terminal carries every binary the Termux terminal does, except the ones
# login-closure.json::tool_parity_exceptions names with a reason. Offline except that
# rootfs/resolve.py reads the pinned shared tool belt.
set -u
DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$DIR/.." && pwd)"
echo "── tool parity [$(basename "$DIR")] vs ac_cloud-termux ──"
python3 - "$DIR" "$REPO" <<'PY'
import json, os, subprocess, sys
d, repo = sys.argv[1], sys.argv[2]
store = json.load(open(os.path.join(repo, "ab_cloud-terminal-store", "store.json")))
nix_dp = json.load(open(os.path.join(d, "build.json")))["forks"]["nixdroid"]["bootstrap"]["default_packages"]
nix = set(store["toolset"]["binaries"]) | set(nix_dp["binaries"])
termux = set(json.loads(subprocess.check_output(["python3", os.path.join(repo, "ac_cloud-termux", "rootfs", "resolve.py"), "get", "binaries"])))
exc = json.load(open(os.path.join(d, "login-closure.json")))["tool_parity_exceptions"]
missing = sorted(termux - nix)
bad = [m for m in missing if m not in exc]
stale = sorted(e for e in exc if e not in missing)
for m in bad:
    print("::error::FAIL -- the termux terminal carries '%s' and this one does not (add its nixpkgs attr, or declare an exception with a reason in login-closure.json)" % m)
for e in stale:
    print("::error::FAIL -- tool_parity_exceptions names '%s', which this terminal now carries or termux dropped: delete the exception" % e)
# every provided binary must have an attr behind it, so the declaration cannot name a tool nothing installs
prov = {b for bs in nix_dp["provides"].values() for b in bs}
orphan = sorted(set(nix_dp["binaries"]) - prov)
for o in orphan:
    print("::error::FAIL -- binaries names '%s' but no attr provides it" % o)
if bad or stale or orphan:
    sys.exit(1)
print("  ok   -- %d termux binaries: %d carried here, %d declared exceptions (%s)" % (len(termux), len(termux) - len(missing), len(missing), ", ".join(missing)))
PY
