#!/usr/bin/env python3
"""#698 -- the login closure gate: every path the terminal's login chain
touches must exist IN THE ZIP THAT SHIPS, resolved the way the phone resolves it.

The bake's own gates (bake_default_packages.py) check the realized profile on
the CI runner's /nix/store, and rootfs-lib's verifyRootfsPayload checks that
the payload FILE exists. Neither opens the zip. A rootfs whose bin/sh target,
login shell, `timeout`, sourced init script or ELF interpreter did not make it
into the zip would pass all of them and die on the phone at exit 127 -- the
#605/#638/#640/#641 shape. This reads the output zip itself:

  * the bind map is PARSED from bin/login's own proot exec line, so the
    resolution follows the artifact, not a copy of it;
  * every absolute path literal in the scanned login scripts must resolve
    through SYMLINKS.txt (absolute targets are guest paths, as proot sees
    them) to a zip entry -- except the declared absent_by_design ones, which
    must NOT resolve (etc/UNINTIALISED present means the first-run wizard runs);
  * everything the chain executes -- each resolved literal that is an ELF or
    carries a shebang, the declared `run` entries, and every command it runs
    BY NAME off the profile PATH -- must be listed in EXECUTABLES.txt (the
    only thing TermuxInstaller chmods for store paths), and its ELF
    interpreter / shebang interpreter must pass the same check.

Not checked: DT_NEEDED libraries (nix's closure is complete by construction;
the ceiling is a hand-deleted .so), and anything under $HOME, which the
login creates at run time.

Pure functions over a Rootfs, so test/test-login-closure.sh drives every
verdict with synthetic zips and no network.

Usage: verify_login_closure.py <rootfs.zip> <build.json> <login-closure.json>
"""
import fnmatch
import json
import posixpath
import re
import struct
import sys
import zipfile

# An absolute path starts a word: line start, whitespace, a quote, `=`, `(`,
# `:` (bind specs, PATH lists) or `>` (redirections). `~/`, `$HOME/`, `*/` and
# `)/` never qualify, so expansions are skipped rather than half-read.
PATH_LITERAL = re.compile(r"""(?:^|(?<=[\s"'=(:>]))(/[A-Za-z0-9._+@%,/-]*)""", re.M)
BIND = re.compile(r"-b\s+(/[^\s:]*):(/[^\s\\\"']*)")
PT_INTERP = 3


class Rootfs:
    """The installed tree as TermuxInstaller will lay it down: zip entries,
    SYMLINKS.txt links and EXECUTABLES.txt chmods, nothing else."""

    def __init__(self, zf: zipfile.ZipFile):
        self.zf = zf
        self.files, self.dirs = set(), {""}
        for name in zf.namelist():
            if name.endswith("/"):
                self._add_dirs(name.rstrip("/"))
            else:
                self.files.add(name)
                self._add_dirs(posixpath.dirname(name))
        self.links = {}
        for line in self.text("SYMLINKS.txt").splitlines():
            target, link = line.split("←")
            self.links[link] = target
            self._add_dirs(posixpath.dirname(link))
        self.executables = set(self.text("EXECUTABLES.txt").splitlines())

    def _add_dirs(self, d):
        while d and d not in self.dirs:
            self.dirs.add(d)
            d = posixpath.dirname(d)

    def text(self, rel):
        return self.zf.read(rel).decode("utf-8", "replace")

    def head(self, rel, n=65536):
        with self.zf.open(rel) as f:
            return f.read(n)


class Namespace:
    """Guest paths -> rootfs entries, through bin/login's own binds."""

    def __init__(self, fs: Rootfs, app_id: str):
        self.fs = fs
        self.prefix = f"/data/data/{app_id}/files/usr"
        self.home = f"/data/data/{app_id}/files/home"
        self.binds = []
        login = fs.text("bin/login")
        exec_at = login.rfind(f"exec {self.prefix}/bin/proot-static")
        # Only the exec statement's own binds: the conditional BIND_PROC_* ones
        # above it are variables by the time proot sees them.
        for src, dst in BIND.findall(login[exec_at:] if exec_at >= 0 else ""):
            rel = self.rel_of_prefix(src)
            if rel is not None:
                self.binds.append((dst.rstrip("/") or "/", rel))

    def rel_of_prefix(self, path):
        if path == self.prefix:
            return ""
        if path.startswith(self.prefix + "/"):
            return path[len(self.prefix) + 1:]
        return None

    def rel_of(self, guest):
        """rootfs-relative path, or None when the path lives outside the rootfs
        (the Android host, or $HOME, which the login creates at run time)."""
        guest = posixpath.normpath(guest)
        if guest == self.home or guest.startswith(self.home + "/"):
            return None
        rel = self.rel_of_prefix(guest)
        if rel is not None:
            return rel
        for dst, src in self.binds:
            if guest == dst or guest.startswith(dst + "/"):
                return posixpath.join(src, guest[len(dst):].lstrip("/")).rstrip("/")
        return None

    def resolve(self, guest, depth=0):
        """-> rootfs rel of the final entry; None if outside the rootfs.
        Raises LookupError naming the first missing component."""
        if depth > 40:
            raise LookupError(f"symlink loop at {guest}")
        guest = posixpath.normpath(guest)
        if self.rel_of(guest) is None:
            return None
        parts = [p for p in guest.split("/") if p]
        walked = "/"
        for i, part in enumerate(parts):
            walked = posixpath.join(walked, part)
            rel = self.rel_of(walked)
            if rel is None:
                continue  # a host ancestor of the prefix (/data, /data/data, ...)
            if rel in self.fs.links:
                target = self.fs.links[rel]
                if not target.startswith("/"):
                    target = posixpath.join(posixpath.dirname(walked), target)
                rest = "/".join(parts[i + 1:])
                return self.resolve(posixpath.join(target, rest) if rest else target, depth + 1)
            if rel in self.fs.files:
                if i != len(parts) - 1:
                    raise LookupError(f"{rel} is a file, not a directory")
                return rel
            if rel not in self.fs.dirs:
                raise LookupError(f"{rel} is not in the zip")
        return self.rel_of(walked)


def path_literals(text):
    """Absolute path literals of a shell script, full-line comments skipped."""
    out = []
    for line in text.splitlines():
        if line.lstrip().startswith("#"):
            continue
        out += [p for p in PATH_LITERAL.findall(line) if not p.startswith("//")]
    return out


def elf_interp(head: bytes):
    """PT_INTERP of an ELF header, or None (not ELF, or static)."""
    if head[:4] != b"\x7fELF":
        return None
    end = "<" if head[5] == 1 else ">"
    if head[4] == 2:
        phoff = struct.unpack_from(end + "Q", head, 0x20)[0]
        phentsize, phnum = struct.unpack_from(end + "HH", head, 0x36)
        off_fmt, off_at, size_at = "Q", 8, 32
    else:
        phoff = struct.unpack_from(end + "I", head, 0x1C)[0]
        phentsize, phnum = struct.unpack_from(end + "HH", head, 0x2A)
        off_fmt, off_at, size_at = "I", 4, 16
    for i in range(phnum):
        ph = phoff + i * phentsize
        if struct.unpack_from(end + "I", head, ph)[0] == PT_INTERP:
            p_offset = struct.unpack_from(end + off_fmt, head, ph + off_at)[0]
            p_filesz = struct.unpack_from(end + off_fmt, head, ph + size_at)[0]
            return head[p_offset:p_offset + p_filesz].rstrip(b"\0").decode()
    return None


def runnable(ns: Namespace, rel, why, problems, seen):
    """rel must be chmod-ed by the installer and its interpreter must be too."""
    if rel in seen:
        return
    seen.add(rel)
    if rel not in ns.fs.executables:
        problems.append(f"{why}: {rel} is not in EXECUTABLES.txt, so the installer never chmods it +x")
    head = ns.fs.head(rel)
    interp = elf_interp(head)
    if interp is None and head[:2] == b"#!":
        interp = head[2:].split(b"\n", 1)[0].split()[0].decode()
    if interp is None:
        return
    try:
        target = ns.resolve(interp)
    except LookupError as e:
        problems.append(f"{why}: {rel} needs interpreter {interp}, which does not resolve ({e})")
        return
    if target is not None:  # None: an Android host interpreter such as /system/bin/sh
        runnable(ns, target, f"{why} -> interpreter {interp}", problems, seen)


def is_executable_content(ns: Namespace, rel):
    head = ns.fs.head(rel, 4)
    return head[:4] == b"\x7fELF" or head[:2] == b"#!"


def verify(fs: Rootfs, app_id, profile_link, commands, closure) -> list:
    """Every problem with the login closure, as one line each; [] means it holds."""
    problems, seen = [], set()
    ns = Namespace(fs, app_id)
    if not ns.binds:
        return ["bin/login binds nothing out of the prefix -- its proot exec line was not recognised"]

    absent = dict(closure.get("absent_by_design", {}))
    matched = set()

    for script in closure["scan"]:
        if script not in fs.files:
            problems.append(f"scan: {script} is not in the zip")
            continue
        for literal in path_literals(fs.text(script)):
            rel = ns.rel_of(literal)
            if rel is None:
                continue  # Android host path or $HOME
            pattern = next((p for p in absent if fnmatch.fnmatch(rel, p)), None)
            if pattern:
                matched.add(pattern)
                try:
                    if ns.resolve(literal) is not None:
                        problems.append(f"{script}: {literal} is declared absent_by_design "
                                        f"({absent[pattern]}) but the zip ships it")
                except LookupError:
                    pass
                continue
            try:
                target = ns.resolve(literal)
            except LookupError as e:
                problems.append(f"{script}: {literal} does not resolve ({e})")
                continue
            if target in fs.files and is_executable_content(ns, target):
                runnable(ns, target, f"{script}: {literal}", problems, seen)

    for pattern in sorted(set(absent) - matched):
        problems.append(f"absent_by_design entry {pattern!r} matches no path the scanned scripts "
                        "reference -- a stale exemption would silently excuse a future real one")

    for rel in closure.get("run", []):
        try:
            target = ns.resolve(ns.prefix + "/" + rel)
        except LookupError as e:
            problems.append(f"run: {rel} does not resolve ({e})")
            continue
        if target not in fs.files:
            problems.append(f"run: {rel} is not a file")
            continue
        runnable(ns, target, f"run: {rel}", problems, seen)

    for cmd in commands:
        literal = f"/{profile_link}/bin/{cmd}"
        try:
            target = ns.resolve(literal)
        except LookupError as e:
            problems.append(f"PATH command {cmd!r}: {literal} does not resolve ({e})")
            continue
        if target not in fs.files:
            problems.append(f"PATH command {cmd!r}: {literal} is not a file")
            continue
        runnable(ns, target, f"PATH command {cmd!r}", problems, seen)
    return problems


def main(argv) -> int:
    if len(argv) != 4:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    zip_path, build_json, closure_json = argv[1:]
    bootstrap = json.load(open(build_json))["forks"]["nixdroid"]["bootstrap"]
    tooling = bootstrap["default_packages"]
    closure = json.load(open(closure_json))
    commands = list(dict.fromkeys(tooling["binaries"] + closure.get("path_commands", [])))
    with zipfile.ZipFile(zip_path) as zf:
        problems = verify(Rootfs(zf), bootstrap["package_name_rewrite"]["to"],
                          tooling["profile_link"], commands, closure)
    for p in problems:
        print(f"FAIL: {p}", file=sys.stderr)
    if problems:
        print(f"FAIL: {zip_path}: {len(problems)} login-closure problem(s) -- this rootfs would not "
              "log in on the phone", file=sys.stderr)
        return 1
    print(f"OK: {zip_path}: login closure resolves ({len(closure['scan'])} scripts scanned, "
          f"{len(commands)} PATH commands, every executable chmod-ed with its interpreter)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
