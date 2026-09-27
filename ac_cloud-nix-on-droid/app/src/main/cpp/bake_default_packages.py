#!/usr/bin/env python3
"""#595 -- bakes agent tooling (git, node, claude) into the nix-on-droid store
this terminal ships, so a fresh install never has to run upstream's
interactive, networked first-login wizard just to get a shell with a package
in it.

Runs AFTER patch_bootstrap_ids.py, on the already id-rewritten zip. Requires
`nix` (flakes + nix-command experimental features) on PATH and network
access to realize the declared attrs -- CI-time only, never on the phone.

This bootstrap format is TermuxInstaller-derived (see that class's extraction
loop): a plain zip entry carries no permission bits at all -- ZipInputStream
ignores them -- so two manifest entries do the real work instead:
  SYMLINKS.txt     "<target>←<link-path-relative-to-root>" per line
  EXECUTABLES.txt  one link-path-relative-to-root per line, chmod 0700 each
Regular files therefore need no special handling; every symlink and every
executable this script adds MUST go through one of those two manifests, or
the installed app will silently ship a plain-file copy of a symlink, or a
binary nothing ever chmods +x.

Usage:
    bake_default_packages.py <input.zip> <output.zip> <nixpkgs_pin> \
        <comma-separated attrs> <profile_link> <fallback_init_script> <app_id> \
        <nix_system, e.g. aarch64-linux> <shared_root_name>

#612 also patches bin/login here (not a separate script): it is the same
"add text to a generated file" job as the login-inner patch above, on a zip
this function already has open.
"""
import os
import stat
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

SESSION_INIT_TEMPLATE = (
    '. "/data/data/{app_id}/files/home/.nix-profile/etc/profile.d/'
    'nix-on-droid-session-init.sh"'
)
DROPPED_ENTRY = "etc/static/UNINTIALISED"


# claude-code carries an unfree license in nixpkgs (Anthropic's own terms,
# not a nixpkgs restriction) -- nix's eval refuses it unless this is set, the
# same override `nixos-rebuild`/`nix-env` users are told to add for it.
NIX_ENV = {**os.environ, "NIXPKGS_ALLOW_UNFREE": "1"}


def run(cmd):
    print("+ " + " ".join(cmd), file=sys.stderr)
    return subprocess.run(cmd, check=True, env=NIX_ENV)


def capture(cmd):
    return subprocess.run(cmd, check=True, capture_output=True, text=True, env=NIX_ENV).stdout


def main() -> int:
    if len(sys.argv) != 10:
        print(
            "usage: bake_default_packages.py <input.zip> <output.zip> "
            "<nixpkgs_pin> <attrs csv> <profile_link> <fallback_init_script> <app_id> <nix_system> "
            "<shared_root_name>",
            file=sys.stderr,
        )
        return 2

    (input_zip, output_zip, pin, attrs_csv, profile_link, fallback_script, app_id, nix_system,
     shared_root_name) = sys.argv[1:10]
    attrs = [a for a in attrs_csv.split(",") if a]
    if not attrs:
        print("no attrs given", file=sys.stderr)
        return 2

    with tempfile.TemporaryDirectory() as work:
        profile = os.path.join(work, "profile")
        # The CI runner is always x86_64-linux; legacyPackages.<system> (rather
        # than the plain #attr shorthand, which resolves against the CALLER's
        # system) is what lets one runner bake either ABI's binaries, pulling
        # pre-built substitutes for the foreign arch from cache.nixos.org for
        # most attrs. That assumption held for git/nodejs_22 but not for
        # claude-code: cache.nixos.org does not carry an aarch64-linux
        # substitute for every claude-code release, so an arm64 job with no
        # foreign builder registered hard-fails with "platform mismatch"
        # instead of falling back to a (slower, but working) build. Registering
        # binfmt_misc for the foreign arch -- one-time, host-wide, via the
        # already-present docker daemon -- and telling nix that arch is locally
        # buildable turns that hard-fail into a QEMU-emulated build instead.
        host_arch = capture(["uname", "-m"]).strip()
        foreign_arch = nix_system.split("-")[0]
        nix_arch_of_host = {"x86_64": "x86_64", "aarch64": "aarch64", "arm64": "aarch64"}.get(host_arch, host_arch)
        extra_platform_args = []
        if foreign_arch != nix_arch_of_host:
            print(f"host is {host_arch}, baking for {nix_system}: registering QEMU emulation "
                  f"so nix can build (not just substitute) {foreign_arch} derivations", file=sys.stderr)
            run(["docker", "run", "--rm", "--privileged", "tonistiigi/binfmt", "--install", "all"])
            extra_platform_args = ["--extra-platforms", nix_system]

        refs = [f"github:NixOS/nixpkgs/{pin}#legacyPackages.{nix_system}.{a}" for a in attrs]
        run(["nix", "profile", "install", "--profile", profile, *refs, "--impure",
             "--extra-experimental-features", "nix-command flakes", *extra_platform_args])

        generation = capture(["readlink", "-f", profile]).strip()
        if not os.path.exists(generation):
            print(f"FAIL: realized profile generation {generation} does not exist", file=sys.stderr)
            return 1

        closure = capture(["nix-store", "-qR", generation]).split()
        if not closure:
            print("FAIL: empty closure for the realized profile", file=sys.stderr)
            return 1
        print(f"closure: {len(closure)} store paths", file=sys.stderr)

        with zipfile.ZipFile(input_zip) as zin:
            existing = set(zin.namelist())
            login_inner = zin.read("usr/lib/login-inner").decode()
            bin_login = zin.read("bin/login").decode()
            symlinks_txt = zin.read("SYMLINKS.txt").decode()
            executables_txt = zin.read("EXECUTABLES.txt").decode()

            # ── #612: auto-mount shared storage + the cloud-drive shared
            # store into $HOME, the same generated-text-injection technique
            # as the session-init patch below. bin/login runs with no -r (it
            # binds individual dirs onto the real Android root, it does not
            # chroot), so $HOME here is already the real on-device path and
            # these two mountpoints just need to exist under it before the
            # exec, guarded like the fakeProcStat/fakeProcUptime binds above
            # them so a missing source (e.g. cloud-drive never opened yet)
            # degrades to no bind instead of a failed one.
            exec_line = f"exec /data/data/{app_id}/files/usr/bin/proot-static \\"
            if bin_login.count(exec_line) != 1:
                print(f"FAIL: expected exactly one proot-static exec line in bin/login:\n  {exec_line}",
                      file=sys.stderr)
                return 1
            mount_setup = (
                'mkdir -p "$HOME/emulated" "$HOME/cloud-drive-shared-store" 2>/dev/null || true\n'
                'if [ -d /storage/emulated/0 ]; then\n'
                '  BIND_HOME_EMULATED="-b /storage/emulated/0:$HOME/emulated"\n'
                'else\n'
                '  BIND_HOME_EMULATED=""\n'
                'fi\n\n'
                f'mkdir -p "/storage/emulated/0/{shared_root_name}" 2>/dev/null || true\n'
                f'if [ -d "/storage/emulated/0/{shared_root_name}" ]; then\n'
                f'  BIND_HOME_SHARED_STORE="-b /storage/emulated/0/{shared_root_name}:$HOME/cloud-drive-shared-store"\n'
                'else\n'
                '  BIND_HOME_SHARED_STORE=""\n'
                'fi\n\n'
            )
            bin_login = bin_login.replace(
                exec_line,
                mount_setup + exec_line + "\n  $BIND_HOME_EMULATED \\\n  $BIND_HOME_SHARED_STORE \\",
                1,
            )

            # ── patch login-inner's one unconditional session-init line ───
            want = SESSION_INIT_TEMPLATE.format(app_id=app_id)
            if login_inner.count(want) < 1:
                print(f"FAIL: expected session-init line not found in usr/lib/login-inner:\n  {want}",
                      file=sys.stderr)
                return 1
            head, _, tail = login_inner.rpartition(want)
            fallback_line = f'if [ -e "/data/data/{app_id}/files/home/.nix-profile/etc/profile.d/nix-on-droid-session-init.sh" ]; then\n  {want}\nelse\n  . /{fallback_script}\nfi'
            login_inner = head + fallback_line + tail

            # ── new manifest lines ─────────────────────────────────────────
            new_symlinks = []
            new_executables = []
            new_files = {}  # zip path -> bytes

            def add_tree(store_path: str):
                base = store_path.lstrip("/")
                if base in existing or base in new_files or any(
                    s.endswith(f"←{base}") for s in new_symlinks
                ):
                    return  # this exact store path already shipped
                for root, dirs, files in os.walk(store_path):
                    rel_root = os.path.relpath(root, store_path)
                    for name in dirs + files:
                        full = os.path.join(root, name)
                        rel = f"{base}/{name}" if rel_root == "." else f"{base}/{rel_root}/{name}"
                        if rel in existing:
                            continue
                        if os.path.islink(full):
                            target = os.readlink(full)
                            new_symlinks.append(f"{target}←{rel}")
                        elif os.path.isdir(full):
                            continue  # created implicitly on extraction
                        else:
                            data = Path(full).read_bytes()
                            new_files[rel] = data
                            if os.access(full, os.X_OK):
                                new_executables.append(rel)

            for store_path in closure:
                add_tree(store_path)

            # the profile generation itself becomes the DEFAULT profile
            new_symlinks.append(f"{generation}←{profile_link}")

            fallback_body = (
                "# #595 -- baked default tooling, sourced by usr/lib/login-inner when\n"
                "# $HOME/.nix-profile does not exist (the wizard that would normally\n"
                "# create it never ran, because the packages are already installed).\n"
                f'export PATH="/{profile_link}/bin:$PATH"\n'
            )
            new_files[fallback_script] = fallback_body.encode()

            if not symlinks_txt.endswith("\n"):
                symlinks_txt += "\n"
            symlinks_txt += "\n".join(new_symlinks) + "\n"
            if not executables_txt.endswith("\n"):
                executables_txt += "\n"
            executables_txt += "\n".join(new_executables) + "\n"

            print(f"adding {len(new_files)} files, {len(new_symlinks)} symlinks, "
                  f"{len(new_executables)} new executables", file=sys.stderr)

            with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zout:
                for info in zin.infolist():
                    name = info.filename
                    if name == DROPPED_ENTRY:
                        continue
                    if name == "usr/lib/login-inner":
                        zout.writestr(info, login_inner)
                    elif name == "bin/login":
                        zout.writestr(info, bin_login)
                    elif name == "SYMLINKS.txt":
                        zout.writestr(info, symlinks_txt)
                    elif name == "EXECUTABLES.txt":
                        zout.writestr(info, executables_txt)
                    else:
                        zout.writestr(info, zin.read(name))
                for rel, data in new_files.items():
                    zout.writestr(rel, data)

    print(f"OK: {output_zip} carries {attrs} baked into {profile_link}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
