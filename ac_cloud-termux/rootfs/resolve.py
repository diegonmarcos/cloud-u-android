#!/usr/bin/env python3
"""Resolve rootfs.json + the pinned fleet tool belt into what gets built and verified.

rootfs.json declares only what a phone terminal needs ON TOP of the fleet
tool belt (cloud-u-containers/_shared/agent-toolbelt.json, #509). This is
the one place the two are merged, so the builder, the verifier and the
tester can never disagree about which tools the rootfs carries.

Usage:
    resolve.py check                   validate the merged declaration, print a summary
    resolve.py env <docker_arch>       shell assignments for the in-rootfs installer
    resolve.py get <key>               one resolved value (binaries, smoke, default_shell, proot_url, ...)

The shared file is fetched from GitHub at the pinned commit. SHARED_TOOLBELT_FILE
overrides that with a local path (offline runs, and the tester's mutations).

Every rule below exits non-zero with the reason. A declared binary that no
install source provides would otherwise surface much later as a missing command
inside the rootfs build, or on the phone.
"""
import json
import os
import shlex
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
APP_DIR = os.path.dirname(HERE)
REPO_ROOT = os.path.dirname(APP_DIR)


def die(msg):
    print(f"rootfs declaration: {msg}", file=sys.stderr)
    sys.exit(1)


def load():
    decl = json.load(open(os.environ.get("ROOTFS_JSON", os.path.join(HERE, "rootfs.json"))))
    src = decl["shared_toolbelt"]
    if len(src["ref"]) != 40:
        die(f"shared_toolbelt.ref must be a full 40-char commit sha, got {src['ref']!r} — a branch name is not a pin")
    local = os.environ.get("SHARED_TOOLBELT_FILE")
    if local:
        shared = json.load(open(local))
    else:
        url = f"https://raw.githubusercontent.com/{src['repo']}/{src['ref']}/{src['path']}"
        with urllib.request.urlopen(url, timeout=60) as r:
            shared = json.load(r)
    return decl, shared


def resolve(decl, shared):
    local_bins = decl["binaries"]
    shared_bins = shared["binaries"]

    # The terminal's list must not restate the fleet list: a repeated name is the
    # start of a second copy that drifts (#509, #527).
    dup = sorted(set(local_bins) & set(shared_bins))
    if dup:
        die(f"binaries {dup} are already declared in the shared tool belt — delete them from rootfs.json")
    dup = sorted(set(decl["apt_packages"]) & set(shared["apt_packages"]))
    if dup:
        die(f"apt_packages {dup} are already declared in the shared tool belt — delete them from rootfs.json")

    binaries = list(shared_bins) + list(local_bins)
    apt = list(shared["apt_packages"]) + list(decl["apt_packages"])
    tarballs = {k: v for k, v in shared.get("tarballs", {}).items() if not k.startswith("_")}
    tarballs.update({k: v for k, v in decl.get("tarballs", {}).items() if not k.startswith("_")})
    npm = {k: v for k, v in decl.get("npm_globals", {}).items() if not k.startswith("_")}
    pip = {k: v for k, v in decl.get("pip_venvs", {}).items() if not k.startswith("_")}

    # Every declared binary must come from somewhere this build installs.
    provided = set(apt)
    for pkg, names in shared.get("apt_provides", {}).items():
        if pkg in apt:
            provided.update(names)
    for name, t in tarballs.items():
        provided.update(t.get("provides", [name]))
    for spec in list(npm.values()) + list(pip.values()):
        provided.update(spec["provides"])
    orphans = [b for b in binaries if b not in provided]
    if orphans:
        die(f"binaries {orphans} are declared but no apt package, tarball, npm package or pip venv provides them")

    shell = decl["default_shell"]
    if os.path.basename(shell) not in binaries:
        die(f"default_shell {shell} is not a declared binary — the terminal would log into a shell nobody installs")
    smoke = {k: v for k, v in decl["smoke"].items() if not k.startswith("_")}
    for name, cmd in smoke.items():
        if cmd[0] not in binaries:
            die(f"smoke.{name} runs {cmd[0]!r}, which is not a declared binary")
    if not smoke:
        die("smoke is empty — nothing would prove the rootfs runs")

    src = decl["proot"]
    boot = json.load(open(os.path.join(REPO_ROOT, src["source_build_json"])))
    boot = next(iter(boot["forks"].values()))["bootstrap"]

    return {
        "binaries": binaries,
        "apt_packages": apt,
        "tarballs": tarballs,
        "npm_globals": npm,
        "pip_venvs": pip,
        "default_shell": shell,
        "smoke": smoke,
        "nameservers": decl["nameservers"],
        "base_image": decl["base_image"]["ref"],
        "asset_dir": decl["asset_dir"],
        "abis": decl["abis"],
        "proot_entry": src["entry"],
        "proot_bootstrap": {abi: {"url": f"{boot['url_base']}/{boot['release']}/bootstrap-{a['arch']}.zip",
                                  "sha256": a["sha256"]}
                            for abi, a in boot["archs"].items()},
    }


def tarball_lines(tarballs, docker_arch):
    """name|url|dest|strip|bin — one install per line, arch already substituted."""
    out = []
    for name, t in sorted(tarballs.items()):
        arch = t.get("arch_names", {}).get(docker_arch, docker_arch)
        fill = lambda s: s.replace("{version}", t["version"]).replace("{arch}", arch)
        url = fill(t["url"])
        if "extract_to" in t:          # a whole tree (node: bin/, lib/node_modules/npm)
            out.append(f"{name}|{url}|{t['extract_to']}|{t.get('strip_components', 0)}|")
        elif "extracted_bin" in t:     # one binary inside an archive (gh)
            inner = t["extracted_bin"] if "extracted_dir" not in t else f"{fill(t['extracted_dir'])}/{t['extracted_bin']}"
            out.append(f"{name}|{url}|/usr/local/bin/{name}||{inner}")
        else:                          # the download IS the binary (yq)
            out.append(f"{name}|{url}|/usr/local/bin/{name}||")
    return "\n".join(out)


def main():
    if len(sys.argv) < 2:
        die(__doc__)
    r = resolve(*load())
    cmd = sys.argv[1]
    if cmd == "check":
        print(f"{len(r['binaries'])} binaries, {len(r['apt_packages'])} apt packages, "
              f"{len(r['tarballs'])} tarballs, {len(r['npm_globals'])} npm globals, {len(r['pip_venvs'])} pip venvs; "
              f"default shell {r['default_shell']}")
    elif cmd == "env":
        arch = sys.argv[2]
        npm = " ".join(f"{k}@{v['version']}" for k, v in sorted(r["npm_globals"].items()))
        pip = "\n".join(f"{k}|{v['version']}|{v['venv']}|{' '.join(v['provides'])}" for k, v in sorted(r["pip_venvs"].items()))
        for k, v in [("APT_PACKAGES", " ".join(r["apt_packages"])),
                     ("TARBALLS", tarball_lines(r["tarballs"], arch)),
                     ("NPM_GLOBALS", npm),
                     ("PIP_VENVS", pip),
                     ("BINARIES", " ".join(r["binaries"])),
                     ("DEFAULT_SHELL", r["default_shell"])]:
            print(f"{k}={shlex.quote(v)}")
    elif cmd == "get":
        v = r[sys.argv[2]]
        print(v if isinstance(v, str) else json.dumps(v))
    else:
        die(f"unknown command {cmd!r}")


if __name__ == "__main__":
    main()
