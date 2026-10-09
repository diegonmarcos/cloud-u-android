#!/usr/bin/env python3
"""Fetch the linux-store / linux-account CLIs and the fish greeting both terminals ship.

Declared once, in store.json::linux_tools (one repo, ONE commit pin, a sha256 per file), and
read by both bakes: ac_cloud-termux/rootfs/build-rootfs.sh writes the result into the tree,
ac_cloud-nix-on-droid's bake_default_packages.py into the bootstrap zip. This is the only
fetcher, so there is one place where a byte is checked.

    fetch-linux-tools.py check                 the declaration is well formed
    fetch-linux-tools.py plan                  "<mode> <install path>" per file, no network
    fetch-linux-tools.py fetch <out-dir>       download at the pin, verify, write <out-dir>/<install path>

Any byte that differs from the declared sha256 is refused by name (a moved pin, a retargeted tag
or a tampered proxy all end here), and nothing is written for a file that fails. LINUX_TOOLS_SRC
points at a local directory laid out like the repository (its files are verified against the
same hashes), for offline runs and for the testers' mutations.

Importable: load(), check(decl), plan(decl), fetch(decl) -> {install path: (bytes, mode)}.
"""
import hashlib
import json
import os
import re
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
STORE_JSON = os.environ.get("STORE_JSON", os.path.join(HERE, "store.json"))
URL = "https://raw.githubusercontent.com/{repo}/{ref}/{path}"


def load():
    with open(STORE_JSON, encoding="utf-8") as handle:
        return json.load(handle)


def check(decl=None):
    """Every rule the declaration must hold; raises SystemExit naming the first one broken."""
    decl = decl or load()
    block = decl.get("linux_tools")
    if not isinstance(block, dict):
        raise SystemExit("fetch-linux-tools.py: store.json has no linux_tools block")
    if not re.fullmatch(r"[0-9a-f]{40}", str(block.get("ref", ""))):
        raise SystemExit("fetch-linux-tools.py: linux_tools.ref %r is not a full 40-char commit sha -- "
                         "a branch or tag name is not a pin" % (block.get("ref"),))
    if not re.fullmatch(r"[\w.-]+/[\w.-]+", str(block.get("repo", ""))):
        raise SystemExit("fetch-linux-tools.py: linux_tools.repo %r is not owner/name" % (block.get("repo"),))
    lic = block.get("licence", {})
    if not lic.get("spdx") or not lic.get("holder"):
        raise SystemExit("fetch-linux-tools.py: linux_tools.licence needs spdx and holder -- "
                         "the licence of what is fetched is declared beside the pin")
    files = block.get("files")
    if not isinstance(files, dict) or not files:
        raise SystemExit("fetch-linux-tools.py: linux_tools.files is empty")
    installs = set()
    for name, f in files.items():
        if not re.fullmatch(r"[0-9a-f]{64}", str(f.get("sha256", ""))):
            raise SystemExit("fetch-linux-tools.py: %s has no 64-hex sha256" % name)
        path = str(f.get("path", ""))
        if not path or path.startswith("/") or ".." in path.split("/"):
            raise SystemExit("fetch-linux-tools.py: %s path %r must be repo-relative" % (name, path))
        inst = str(f.get("install", ""))
        if not inst or inst.startswith("/") or ".." in inst.split("/"):
            raise SystemExit("fetch-linux-tools.py: %s install %r must be relative to the image root" % (name, inst))
        if inst in installs:
            raise SystemExit("fetch-linux-tools.py: two entries install %s" % inst)
        installs.add(inst)
        if not re.fullmatch(r"0[0-7]{3}", str(f.get("mode", ""))):
            raise SystemExit("fetch-linux-tools.py: %s mode %r is not octal like 0755" % (name, f.get("mode")))
        if f.get("role") not in ("cli", "fish_function"):
            raise SystemExit("fetch-linux-tools.py: %s role %r is not cli or fish_function" % (name, f.get("role")))
        if f["role"] == "cli" and not f["install"].startswith("usr/local/bin/"):
            raise SystemExit("fetch-linux-tools.py: cli %s installs at %s, not under usr/local/bin "
                             "(login-init.sh puts exactly that on PATH)" % (name, f["install"]))
        if f["role"] == "fish_function" and not f["install"].startswith("usr/share/fish/vendor_functions.d/"):
            raise SystemExit("fetch-linux-tools.py: %s installs at %s, not under usr/share/fish/vendor_functions.d "
                             "(login-init.sh makes exactly that a fish data dir)" % (name, f["install"]))
    toolset = decl.get("toolset", {}).get("binaries", [])
    missing = [n for n in block.get("needs", []) if n not in toolset]
    if missing:
        raise SystemExit("fetch-linux-tools.py: linux_tools.needs %s are not in store.json::toolset.binaries, "
                         "so no bake proves they are installed" % ", ".join(missing))
    return block


def plan(decl=None):
    block = check(decl)
    return [(f["install"], f["mode"]) for _, f in sorted(block["files"].items(), key=lambda kv: kv[1]["install"])]


def _get(url):
    last = None
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                return response.read()
        except Exception as error:  # noqa: BLE001 -- any transport failure is retried, then named
            last = error
            time.sleep(2 * (attempt + 1))
    raise SystemExit("fetch-linux-tools.py: could not fetch %s: %s" % (url, last))


def fetch(decl=None):
    """{install path: (bytes, mode)}, every byte verified; nothing is returned for a failing file."""
    decl = decl or load()
    block = check(decl)
    local = os.environ.get("LINUX_TOOLS_SRC")
    out = {}
    for name, f in sorted(block["files"].items()):
        if local:
            with open(os.path.join(local, f["path"]), "rb") as handle:
                data = handle.read()
            origin = os.path.join(local, f["path"])
        else:
            origin = URL.format(repo=block["repo"], ref=block["ref"], path=f["path"])
            data = _get(origin)
        got = hashlib.sha256(data).hexdigest()
        if got != f["sha256"]:
            raise SystemExit("fetch-linux-tools.py: %s from %s is sha256 %s, but store.json::linux_tools pins %s "
                             "-- refusing to install it" % (name, origin, got, f["sha256"]))
        out[f["install"]] = (data, int(f["mode"], 8))
    return out


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    if argv[0] == "check":
        block = check()
        print("fetch-linux-tools.py: ok -- %d files pinned at %s@%s" % (len(block["files"]), block["repo"], block["ref"][:12]))
        return 0
    if argv[0] == "plan":
        for inst, mode in plan():
            print("%s %s" % (mode, inst))
        return 0
    if argv[0] == "fetch" and len(argv) == 2:
        for inst, (data, mode) in sorted(fetch().items()):
            dest = os.path.join(argv[1], inst)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "wb") as handle:
                handle.write(data)
            os.chmod(dest, mode)
            print("fetch-linux-tools.py: %s %s (%d bytes, sha256 verified)" % (oct(mode)[2:].zfill(4), inst, len(data)))
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
