#!/usr/bin/env python3
"""#795 -- fetch the proot-static both terminals ship, pinned in proot.json beside this file.

Usage: fetch-proot.py <android-abi> <out-file>

The pinned bootstrap zip (bootstrap-release-24.05) carries termux/proot 60485d2 (2024-05-04).
glibc 2.42 made tcgetattr/tcsetattr issue TCGETS2/TCSETS*2, which Android's SELinux tty ioctl
allowlist does not carry, so under that proot every glibc-2.42 program saw EACCES on its own
terminal: isatty() false, `stty: 'standard input': Permission denied`, fish never drew a prompt
and claude fell into --print. termux/proot 6fa36f7/228a5f2/2cb63b8 rewrite those ioctls to their
TCGETS/TCSETS* twins; upstream nix-on-droid ships that proot from its binary cache. This script
fetches exactly that store path's NAR, refuses any byte that differs from the pin (the compressed
NAR and the unpacked binary both), and writes the one file the declaration names.

Needs python3 and the zstd CLI (both CI runners carry it; the termux rootfs job installs it).
"""
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

DECL = Path(__file__).resolve().parent / "proot.json"


def nar_file(nar: bytes, want: str) -> bytes:
    """The contents of regular file `want` (a/b/c) inside a NAR. Raises on anything malformed."""
    pos = 0

    def s() -> bytes:
        nonlocal pos
        n = int.from_bytes(nar[pos:pos + 8], "little")
        v = nar[pos + 8:pos + 8 + n]
        if len(v) != n:
            raise ValueError("truncated NAR")
        pos += 8 + n + (-n % 8)
        return v

    def expect(*tokens):
        for t in tokens:
            got = s()
            if got != t:
                raise ValueError(f"NAR: expected {t!r}, got {got[:40]!r}")

    found = None

    def node(path):
        nonlocal found
        expect(b"(", b"type")
        kind = s()
        if kind == b"regular":
            tok = s()
            if tok == b"executable":
                expect(b"")
                tok = s()
            if tok != b"contents":
                raise ValueError(f"NAR: expected contents, got {tok!r}")
            data = s()
            if path == want:
                found = data
            expect(b")")
        elif kind == b"symlink":
            expect(b"target")
            s()
            expect(b")")
        elif kind == b"directory":
            while True:
                tok = s()
                if tok == b")":
                    break
                if tok != b"entry":
                    raise ValueError(f"NAR: expected entry, got {tok!r}")
                expect(b"(", b"name")
                name = s().decode()
                expect(b"node")
                node(f"{path}/{name}" if path else name)
                expect(b")")
        else:
            raise ValueError(f"NAR: unknown node type {kind!r}")

    expect(b"nix-archive-1")
    node("")
    if found is None:
        raise ValueError(f"NAR has no regular file {want}")
    return found


def download(url: str) -> bytes:
    for attempt in range(3):
        try:
            # cachix answers 403 to urllib's default User-Agent (measured); curl's is accepted.
            req = urllib.request.Request(url, headers={"User-Agent": "curl/8 (cloud-u-android fetch_proot.py)"})
            with urllib.request.urlopen(req, timeout=120) as r:
                return r.read()
        except OSError as e:
            if attempt == 2:
                raise
            print(f"fetch-proot: {url}: {e}; retrying", file=sys.stderr)
            time.sleep(5)
    raise AssertionError


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    abi, out = sys.argv[1:]
    decl = json.load(open(DECL))
    pin = decl["archs"][abi]
    url = f"{decl['cache']}/{pin['nar']}"
    blob = download(url)
    got = hashlib.sha256(blob).hexdigest()
    if got != pin["nar_sha256"]:
        print(f"FAIL fetch-proot: {url} hashes to {got}, the pin says {pin['nar_sha256']}", file=sys.stderr)
        return 1
    nar = subprocess.run(["zstd", "-dc"], input=blob, capture_output=True, check=True).stdout
    data = nar_file(nar, decl["entry"])
    got = hashlib.sha256(data).hexdigest()
    if got != pin["sha256"]:
        print(f"FAIL fetch-proot: {decl['entry']} in {pin['store_path']} hashes to {got}, "
              f"the pin says {pin['sha256']}", file=sys.stderr)
        return 1
    tmp = out + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
    os.chmod(tmp, 0o755)
    os.replace(tmp, out)
    print(f"fetch-proot: {abi} {pin['store_path']}/{decl['entry']} sha256 {got} -> {out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
