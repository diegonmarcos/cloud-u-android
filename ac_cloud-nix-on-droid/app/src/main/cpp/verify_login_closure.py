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

#846 -- and the LINKAGE of every ELF the zip ships under nix/store: its
PT_INTERP and each DT_NEEDED must resolve the way glibc's loader resolves them
(sonames already loaded, then DT_RPATH / DT_RUNPATH with $ORIGIN, then the
loader's own lib dir), to a file in the zip. nix's closure is complete by
construction, but the bake now CUTS outputs (-doc/-man/-dev) the closure drags
in by reference; this is what proves the cut took no library a binary loads.
Every shebang script in EXECUTABLES.txt under nix/store whose interpreter is
a /nix/store path must also find it in the zip. Not checked: dlopen() by computed name, and
anything under $HOME, which the login creates at run time.

Pure functions over a Rootfs, so test/test-login-closure.sh drives every
verdict with synthetic zips and no network.

#665 also holds the SIZE here: the shipped zip must not exceed the declared
size_ceiling_bytes in login-closure.json, so an attr addition that blows the
budget goes red in the bake instead of silently shipping a bigger rootfs to
every phone. A missing or non-positive ceiling is itself a failure.

Usage: verify_login_closure.py <rootfs.zip> <build.json> <login-closure.json>
"""
import fnmatch
import json
import os
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


PT_LOAD, PT_DYNAMIC = 1, 2
DT_NEEDED, DT_STRTAB, DT_SONAME, DT_RPATH, DT_RUNPATH = 1, 5, 14, 15, 29


def elf_linkage(data: bytes):
    """(interp, needed, rpath, runpath) of a whole ELF image; None if not a parseable ELF."""
    if data[:4] != b"\x7fELF" or len(data) < 64:
        return None
    end = "<" if data[5] == 1 else ">"
    wide = data[4] == 2
    try:
        if wide:
            phoff = struct.unpack_from(end + "Q", data, 0x20)[0]
            phentsize, phnum = struct.unpack_from(end + "HH", data, 0x36)
            ph_fmt = end + "IIQQQQQQ"  # type flags offset vaddr paddr filesz memsz align
            dyn_fmt, dyn_size = end + "qQ", 16
        else:
            phoff = struct.unpack_from(end + "I", data, 0x1C)[0]
            phentsize, phnum = struct.unpack_from(end + "HH", data, 0x2A)
            ph_fmt = end + "IIIIIIII"  # type offset vaddr paddr filesz memsz flags align
            dyn_fmt, dyn_size = end + "iI", 8
        loads, interp, dynamic = [], None, None
        for i in range(phnum):
            f = struct.unpack_from(ph_fmt, data, phoff + i * phentsize)
            if wide:
                p_type, p_offset, p_vaddr, p_filesz = f[0], f[2], f[3], f[5]
            else:
                p_type, p_offset, p_vaddr, p_filesz = f[0], f[1], f[2], f[4]
            if p_type == PT_LOAD:
                loads.append((p_vaddr, p_offset, p_filesz))
            elif p_type == PT_INTERP:
                interp = data[p_offset:p_offset + p_filesz].rstrip(b"\0").decode()
            elif p_type == PT_DYNAMIC:
                dynamic = (p_offset, p_filesz)
        if dynamic is None:
            return interp, [], [], []
        entries, strtab = [], None
        for off in range(dynamic[0], dynamic[0] + dynamic[1], dyn_size):
            tag, val = struct.unpack_from(dyn_fmt, data, off)
            if tag == 0:
                break
            if tag == DT_STRTAB:
                strtab = val
            entries.append((tag, val))
        base = next((o + strtab - v for v, o, n in loads if v <= strtab < v + n), None) \
            if strtab is not None else None
        if base is None:
            return interp, [], [], []

        def string(at):
            return data[base + at:data.index(b"\0", base + at)].decode()

        pick = lambda t: [string(v) for tag, v in entries if tag == t]
        split = lambda xs: [d for x in xs for d in x.split(":") if d]
        return interp, pick(DT_NEEDED), split(pick(DT_RPATH)), split(pick(DT_RUNPATH))
    except (struct.error, ValueError, UnicodeDecodeError):
        return None


def linkage_problems(ns: Namespace) -> list:
    """#846 -- every nix/store ELF loads: interpreter and DT_NEEDED resolve in the zip."""
    fs, problems, info = ns.fs, [], {}

    def guest_of(rel):
        best = max(((dst, src) for dst, src in ns.binds if rel == src or rel.startswith(src + "/")),
                   key=lambda b: len(b[1]), default=None)
        if best is None:
            return ns.prefix + "/" + rel
        return posixpath.join(best[0], rel[len(best[1]):].lstrip("/"))

    def link_info(rel):
        if rel not in info:
            info[rel] = elf_linkage(fs.zf.read(rel)) if fs.head(rel, 4) == b"\x7fELF" else None
        return info[rel]

    def find(name, dirs, origin):
        for d in dirs:
            d = d.replace("$ORIGIN", origin).replace("${ORIGIN}", origin)
            try:
                target = ns.resolve(posixpath.join(d, name))
            except LookupError:
                continue
            if target in fs.files:
                return target
        return None

    elves = [r for r in sorted(fs.files) if r.startswith("nix/store/") and fs.head(r, 4) == b"\x7fELF"]
    loader_dirs = set()
    for rel in elves:
        li = link_info(rel)
        if li and li[0]:
            try:
                target = ns.resolve(li[0])
            except LookupError as e:
                problems.append(f"linkage: {rel} needs interpreter {li[0]}, which does not resolve ({e})")
                continue
            if target is not None:
                loader_dirs.add(posixpath.dirname(li[0]))

    def load(root):
        """glibc's breadth-first load of root: [] when every DT_NEEDED is found."""
        li = link_info(root)
        defaults = [posixpath.dirname(li[0])] if li[0] else sorted(loader_dirs)
        loaded, queue, missing = set(), [(root, [])], []
        while queue:
            rel, parent_rpath = queue.pop(0)
            li = link_info(rel)
            if not li:
                continue
            _, needed, rpath, runpath = li
            origin = posixpath.dirname(guest_of(rel))
            own = [d.replace("$ORIGIN", origin).replace("${ORIGIN}", origin) for d in rpath]
            chain = [] if runpath else own + parent_rpath
            for name in needed:
                if name in loaded:
                    continue
                target = (ns.resolve(name) if name.startswith("/") and ns.rel_of(name) else None) \
                    if "/" in name else find(name, chain + runpath + defaults, origin)
                if target is None or target not in fs.files:
                    missing.append(f"{name} (needed by {rel})")
                    continue
                loaded.add(name)
                queue.append((target, chain))
        return missing

    for rel in elves:
        li = link_info(rel)
        if not li or not li[1]:
            continue
        for m in load(rel):
            problems.append(f"linkage: {rel}: DT_NEEDED {m} resolves to nothing in the zip")

    for rel in sorted(fs.executables):
        if not rel.startswith("nix/store/") or rel not in fs.files:
            continue
        head = fs.head(rel, 256)
        if head[:2] != b"#!":
            continue
        words = head[2:].split(b"\n", 1)[0].split()
        if not words:
            continue
        interp = words[0].decode("utf-8", "replace")
        if not interp.startswith("/nix/store/"):
            continue  # a FHS path (/usr/bin/perl in a sample hook): not what a store cut can break
        try:
            target = ns.resolve(interp)
        except LookupError as e:
            problems.append(f"linkage: {rel} names interpreter {interp}, which does not resolve ({e})")
            continue
        if target not in fs.files:
            problems.append(f"linkage: {rel} names interpreter {interp}, which is not a file")
    return problems


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
    return problems + linkage_problems(ns)


def declared_commands(build_json: str, closure: dict) -> list:
    """Everything the login runs or the user is promised BY NAME off the profile PATH.

    #737: store.json::toolset (the set BOTH terminals ship) comes first, then
    this terminal's own default_packages.binaries, then the login's own
    path_commands. store.json is found beside the app dir build.json sits in,
    so a synthetic build.json in a sandbox never reads a different repo's list.
    """
    store_json = posixpath.join(posixpath.dirname(posixpath.abspath(build_json)),
                                "..", "ab_cloud-terminal-store", "store.json")
    toolset = json.load(open(store_json))["toolset"]["binaries"]
    tooling = json.load(open(build_json))["forks"]["nixdroid"]["bootstrap"]["default_packages"]
    return list(dict.fromkeys(toolset + tooling["binaries"] + closure.get("path_commands", [])))


def size_problems(zip_path: str, closure: dict) -> list:
    """#665: the zip on disk against the declared ceiling. The ceiling is required."""
    ceiling = closure.get("size_ceiling_bytes")
    if not isinstance(ceiling, int) or isinstance(ceiling, bool) or ceiling <= 0:
        return ["login-closure.json declares no positive integer size_ceiling_bytes -- "
                "the rootfs size budget is unguarded (#665)"]
    size = os.path.getsize(zip_path)
    if size > ceiling:
        return [f"rootfs size {size} B exceeds size_ceiling_bytes {ceiling} B by {size - ceiling} B -- "
                "an attr or closure grew past the declared budget; shrink it or raise the ceiling "
                "deliberately in login-closure.json (#665)"]
    return []


def main(argv) -> int:
    if len(argv) != 4:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    zip_path, build_json, closure_json = argv[1:]
    bootstrap = json.load(open(build_json))["forks"]["nixdroid"]["bootstrap"]
    tooling = bootstrap["default_packages"]
    closure = json.load(open(closure_json))
    commands = declared_commands(build_json, closure)
    with zipfile.ZipFile(zip_path) as zf:
        problems = verify(Rootfs(zf), bootstrap["package_name_rewrite"]["to"],
                          tooling["profile_link"], commands, closure)
    problems += size_problems(zip_path, closure)
    for p in problems:
        print(f"FAIL: {p}", file=sys.stderr)
    if problems:
        print(f"FAIL: {zip_path}: {len(problems)} login-closure problem(s) -- this rootfs would not "
              "log in on the phone", file=sys.stderr)
        return 1
    print(f"OK: {zip_path}: login closure resolves ({len(closure['scan'])} scripts scanned, "
          f"{len(commands)} PATH commands, every executable chmod-ed with its interpreter, "
          f"every nix/store ELF's DT_NEEDED found; "
          f"{os.path.getsize(zip_path)} B <= ceiling {closure['size_ceiling_bytes']} B)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
