#!/usr/bin/env python3
"""#795 -- run pty-selftest.json's checks against a terminal login, each in a real pty.

Usage: pty-selftest.py <pty-selftest.json> <terminal> -- <login argv...>

<login argv> opens an interactive login shell (fish); a check's `run`/`tui` is appended as
`-c <cmd>`. The child is a session leader whose controlling tty is the pts, exactly as
termux.c's create_subprocess makes it, and it carries a seccomp filter that answers the ioctls
Android's SELinux denies with EACCES (see the json's android_denied_tty_ioctls). PROOT_NO_SECCOMP=1
is added to the environment so proot's rewrite lands before that filter is evaluated.

One line per check, `ok   <name> (<s>)` or `FAIL <name>: <why>`; exit 1 if any failed. Callers
mutate (an older proot, no pty) and grep the FAIL lines to prove a check can fail.
"""
import ctypes
import errno
import fcntl
import json
import os
import platform
import re
import select
import signal
import struct
import sys
import termios
import time

# seccomp_data: nr at 0, arch at 4, args[1] (the ioctl request, low word) at 24.
AUDIT = {"x86_64": (0xC000003E, 16), "aarch64": (0xC00000B7, 29)}  # (AUDIT_ARCH_*, __NR_ioctl)
ROWS, COLS = 24, 80
# What a terminal answers to the two queries shells wait on (the app's emulator answers both):
# primary device attributes (fish 4 blocks its first prompt on it) and the cursor position.
REPLIES = ((re.compile(rb"\x1b\[0?c"), b"\x1b[?62;22c"), (re.compile(rb"\x1b\[6n"), b"\x1b[1;1R"))


def android_tty_policy(denied):
    arch, nr_ioctl = AUDIT[platform.machine()]
    n = len(denied)
    allow, deny = 5 + n, 6 + n
    prog = [(0x20, 0, 0, 4), (0x15, 0, allow - 2, arch),
            (0x20, 0, 0, 0), (0x15, 0, allow - 4, nr_ioctl),
            (0x20, 0, 0, 24)]
    prog += [(0x15, deny - (5 + i) - 1, 0, req) for i, req in enumerate(denied)]
    prog += [(0x06, 0, 0, 0x7FFF0000), (0x06, 0, 0, 0x00050000 | errno.EACCES)]
    raw = b"".join(struct.pack("<HBBI", *ins) for ins in prog)
    buf = ctypes.create_string_buffer(raw, len(raw))

    class Fprog(ctypes.Structure):
        _fields_ = [("len", ctypes.c_ushort), ("filter", ctypes.c_void_p)]

    libc = ctypes.CDLL(None, use_errno=True)
    fprog = Fprog(len(prog), ctypes.cast(buf, ctypes.c_void_p))
    if libc.prctl(38, 1, 0, 0, 0) != 0 or libc.prctl(22, 2, ctypes.byref(fprog), 0, 0) != 0:
        raise OSError(ctypes.get_errno(), "seccomp")  # PR_SET_NO_NEW_PRIVS / PR_SET_SECCOMP


def spawn(argv, denied):
    master, slave = os.openpty()
    fcntl.ioctl(master, termios.TIOCSWINSZ, struct.pack("HHHH", ROWS, COLS, 0, 0))
    pid = os.fork()
    if pid == 0:
        try:
            os.close(master)
            os.setsid()
            fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
            for fd in (0, 1, 2):
                os.dup2(slave, fd)
            if slave > 2:
                os.close(slave)
            android_tty_policy(denied)
            os.execvpe(argv[0], argv, {**os.environ, "PROOT_NO_SECCOMP": "1"})
        except BaseException as e:  # noqa: BLE001 -- the child must never return into the harness
            os.write(2, f"pty-selftest: cannot start {argv[0]}: {e}\n".encode())
        os._exit(127)
    os.close(slave)
    return pid, master


def drive(argv, denied, deadline, done, typed=None):
    """Run argv in a pty until done(output) or the deadline; type `typed` once the shell has
    printed something and stayed quiet 0.3 s. Returns (output, seconds, still_running)."""
    pid, fd = spawn(argv, denied)
    out, start, last, alive = b"", time.monotonic(), None, True
    try:
        while True:
            now = time.monotonic()
            if done(out) or now - start > deadline:
                break
            ready, _, _ = select.select([fd], [], [], 0.05)
            if ready:
                try:
                    chunk = os.read(fd, 65536)
                except OSError:
                    chunk = b""
                if not chunk:
                    alive = False
                    break
                out += chunk
                last = time.monotonic()
                for query, reply in REPLIES:
                    for _ in query.findall(chunk):
                        os.write(fd, reply)
            elif typed is not None and last is not None and now - last > 0.3:
                os.write(fd, typed.encode() + b"\r")
                typed = None
        took = time.monotonic() - start
        if alive and os.waitpid(pid, os.WNOHANG)[0] != 0:
            alive = False
        return out.decode("utf-8", "replace"), took, alive
    finally:
        for target in (lambda s: os.killpg(pid, s), lambda s: os.kill(pid, s)):
            try:
                target(signal.SIGKILL)
            except OSError:
                pass
        try:
            os.waitpid(pid, 0)
        except ChildProcessError:
            pass
        os.close(fd)


def tail(text):
    return " | ".join(text.replace("\r", "").strip().splitlines()[-3:])[:300]


def check(c, login, denied):
    limit = c["deadline_s"]
    if "run" in c:
        want = re.compile(c["expect"], re.M)
        out, took, _ = drive(login + ["-c", c["run"]], denied, limit, lambda o: False)
        text = out.replace("\r", "")
        return (want.search(text) is not None, took, f"no line matching {c['expect']!r}: {tail(out)}")
    if "prompt" in c:
        mark = c["expect"].encode()
        out, took, _ = drive(login, denied, limit, lambda o: mark in o, typed=c["prompt"])
        if c["expect"] in out:
            return True, took, ""
        return False, took, f"no {c['expect']} within {limit}s: {tail(out) or 'the session printed nothing'}"
    marks = [m.encode() for m in c["expect_any"]]
    out, took, alive = drive(login + ["-c", c["tui"]], denied, limit,
                             lambda o: c["refuse"].encode() in o or any(m in o for m in marks))
    if c["refuse"] in out:
        return False, took, f"printed {c['refuse']!r}: {tail(out)}"
    if any(m in out for m in c["expect_any"]):
        return True, took, ""
    return False, took, f"no TUI within {limit}s ({'running' if alive else 'exited'}): {tail(out)}"


def main():
    if len(sys.argv) < 5 or sys.argv[3] != "--":
        print(__doc__, file=sys.stderr)
        return 2
    decl = json.load(open(sys.argv[1]))
    terminal, login = sys.argv[2], sys.argv[4:]
    denied = [int(v, 16) for k, v in decl["android_denied_tty_ioctls"].items() if not k.startswith("_")]
    failed = 0
    for c in decl["checks"]:
        if terminal not in c.get("terminals", [terminal]):
            continue
        ok, took, why = check(c, login, denied)
        if ok:
            print(f"ok   {c['name']} ({took:.1f}s)", flush=True)
        else:
            print(f"FAIL {c['name']}: {why}", flush=True)
            failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
