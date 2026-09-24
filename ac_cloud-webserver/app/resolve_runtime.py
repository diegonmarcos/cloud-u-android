#!/usr/bin/env python3
"""Work out how to launch a glibc binary under the baked proot rootfs.

    resolve_runtime.py <bootstrap.zip> <binary> <launcher-entry> <out.json>

Route D (#288): my-webserver is a glibc Node SEA. Android is bionic, so the
kernel's execve of it fails ENOENT on its PT_INTERP. The nix-on-droid bootstrap
(#348) already carries a glibc; proot-static (bionic, runs natively) makes it
reachable. Nothing below is a path anybody typed: the loader and every library
come from the binary's OWN program headers, and each is looked up in the
bootstrap's OWN listing (zip entries plus SYMLINKS.txt). If the rootfs cannot
satisfy the binary, this exits non-zero and the APK is not built -- which is the
whole claim of Route D, checked on every build rather than asserted once.

Also importable: the tester calls elf_needs() against a real system ELF and
compares it with readelf.
"""
import json
import os
import struct
import sys
import zipfile

PT_LOAD, PT_DYNAMIC, PT_INTERP = 1, 2, 3
DT_NULL, DT_NEEDED, DT_STRTAB = 0, 1, 5


def elf_needs(path):
    """(PT_INTERP, [DT_NEEDED...]) of a little-endian ELF64."""
    with open(path, "rb") as f:
        ident = f.read(16)
        if ident[:4] != b"\x7fELF" or ident[4] != 2 or ident[5] != 1:
            raise SystemExit(f"{path}: not a little-endian ELF64")
        f.seek(0x20)
        (phoff,) = struct.unpack("<Q", f.read(8))
        f.seek(0x36)
        phentsize, phnum = struct.unpack("<HH", f.read(4))
        loads, interp, dyn = [], None, None
        for i in range(phnum):
            f.seek(phoff + i * phentsize)
            p_type, _flags, off, vaddr, _paddr, filesz = struct.unpack("<IIQQQQ", f.read(40))
            if p_type == PT_LOAD:
                loads.append((vaddr, off, filesz))
            elif p_type == PT_INTERP:
                f.seek(off)
                interp = f.read(filesz).rstrip(b"\0").decode()
            elif p_type == PT_DYNAMIC:
                dyn = (off, filesz)
        if dyn is None:
            return interp, []
        f.seek(dyn[0])
        raw = f.read(dyn[1])
        entries = [struct.unpack_from("<qQ", raw, o) for o in range(0, len(raw) - 15, 16)]
        strtab = next((v for t, v in entries if t == DT_STRTAB), None)
        if strtab is None:
            raise SystemExit(f"{path}: PT_DYNAMIC has no DT_STRTAB")
        base = next((off + strtab - va for va, off, sz in loads if va <= strtab < va + sz), None)
        if base is None:
            raise SystemExit(f"{path}: DT_STRTAB 0x{strtab:x} is in no PT_LOAD")
        needed = []
        for t, v in entries:
            if t == DT_NULL:
                break
            if t == DT_NEEDED:
                f.seek(base + v)
                needed.append(f.read(256).split(b"\0", 1)[0].decode())
        return interp, needed


def rootfs_index(zf):
    """basename -> sorted dirs holding it (as a file or a symlink), plus the set of plain files."""
    files = {n for n in zf.namelist() if not n.endswith("/")}
    names = list(files)
    if "SYMLINKS.txt" in files:
        for line in zf.read("SYMLINKS.txt").decode().splitlines():
            if "←" in line:
                names.append(line.split("←", 1)[1])
    index = {}
    for n in names:
        index.setdefault(os.path.basename(n), set()).add(os.path.dirname(n))
    return {k: sorted(v) for k, v in index.items()}, files


def resolve(zip_path, binary, launcher):
    interp, needed = elf_needs(binary)
    if not interp:
        raise SystemExit(f"{binary} has no PT_INTERP -- it does not need this rootfs at all")
    with zipfile.ZipFile(zip_path) as zf:
        index, files = rootfs_index(zf)
        if launcher not in files:
            raise SystemExit(f"launcher {launcher} is not a regular file in {zip_path}")
        with zf.open(launcher) as fh:
            if fh.read(4) != b"\x7fELF":
                raise SystemExit(f"launcher {launcher} in {zip_path} is not an ELF")

    missing = [n for n in [os.path.basename(interp)] + needed if n not in index]
    if missing:
        raise SystemExit(f"{zip_path} cannot run {binary}: no {', '.join(missing)} anywhere in the rootfs")

    # The loader's directory first, then prefer a directory already chosen, so
    # the library path stays as short as the binary allows.
    interp_dir = index[os.path.basename(interp)][0]
    chosen, where = [interp_dir], {}
    for lib in needed:
        where[lib] = next((d for d in chosen if d in index[lib]), index[lib][0])
        if where[lib] not in chosen:
            chosen.append(where[lib])

    # Symlinks inside the store are absolute (/nix/store/...), so every
    # top-level directory we draw from is bound at the same name under /.
    tops = sorted({d.split("/", 1)[0] for d in chosen})
    binds = [{"host": t, "guest": "/" + t} for t in tops]
    binds.append({"host": interp_dir, "guest": os.path.dirname(interp)})
    return {
        "launcher": launcher,
        "interp": interp,
        "binds": binds,
        "library_path": ["/" + d for d in chosen],
        "needed": where,
    }


if __name__ == "__main__":
    if len(sys.argv) != 5:
        raise SystemExit(__doc__)
    out = resolve(sys.argv[1], sys.argv[2], sys.argv[3])
    with open(sys.argv[4], "w") as fh:
        json.dump(out, fh, indent=1, sort_keys=True)
    print(json.dumps(out, indent=1, sort_keys=True))
