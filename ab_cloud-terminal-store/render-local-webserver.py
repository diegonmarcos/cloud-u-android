#!/usr/bin/env python3
"""Render a terminal's build.json::runtime.webserver + local-webserver.sh into the sh both
Cloud Terminals run at login (option 37.1: each terminal serves its own disk through
my-webserver, on its own loopback port).

    render-local-webserver.py <app build.json> [<out>]   # default: stdout

Importable: bake_default_packages.py calls render(build_json) for the nix bin/login, and
ac_cloud-termux/rootfs/build-rootfs.sh runs this for the termux cloud-store/ copy, so the
port is declared once (build.json) and the logic is written once (local-webserver.sh).

The declaration is checked here, at build time, so a bad one fails the build instead of a
login: the port must be an unprivileged integer, the bind must be loopback (my-webserver
hard-binds 127.0.0.1; declaring anything else would be a lie this refuses), and the root must
be "$HOME" (each caller maps it to its guest's view of the terminal's own home).
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SNIPPET = os.path.join(HERE, "local-webserver.sh")


def declaration(build_json):
    with open(build_json, encoding="utf-8") as handle:
        decl = (json.load(handle).get("runtime") or {}).get("webserver")
    if not isinstance(decl, dict):
        raise ValueError("%s declares no runtime.webserver" % build_json)
    port, bind, root, binary = decl.get("port"), decl.get("bind"), decl.get("root"), decl.get("binary")
    if not isinstance(port, int) or isinstance(port, bool) or not 1024 <= port <= 65535:
        raise ValueError("%s runtime.webserver.port must be an integer in 1024..65535, got %r" % (build_json, port))
    if bind != "127.0.0.1":
        raise ValueError("%s runtime.webserver.bind must be 127.0.0.1 (loopback only), got %r" % (build_json, bind))
    if root != "$HOME":
        raise ValueError("%s runtime.webserver.root must be \"$HOME\", got %r" % (build_json, root))
    if not isinstance(binary, str) or not re.fullmatch(r"[A-Za-z0-9._-]+", binary):
        raise ValueError("%s runtime.webserver.binary must be a bare file name, got %r" % (build_json, binary))
    return decl


def render(build_json):
    decl = declaration(build_json)
    with open(SNIPPET, encoding="utf-8") as handle:
        body = handle.read()
    head = (
        "# rendered by ab_cloud-terminal-store/render-local-webserver.py from %s::runtime.webserver\n"
        "CLOUD_WEBSERVER_PORT=%d\n"
        "CLOUD_WEBSERVER_BIN=%s\n"
        'CLOUD_WEBSERVER_LOG="${CLOUD_WEBSERVER_LOG:-$HOME/.cloud-webserver.log}"\n'
    ) % (os.path.basename(os.path.dirname(os.path.abspath(build_json))) + "/build.json",
         decl["port"], decl["binary"])
    return head + body


def main(argv):
    if len(argv) not in (2, 3):
        print(__doc__, file=sys.stderr)
        return 2
    try:
        text = render(argv[1])
    except (ValueError, OSError) as error:
        print("render-local-webserver.py: %s" % error, file=sys.stderr)
        return 1
    if len(argv) == 3:
        with open(argv[2], "w", encoding="utf-8") as handle:
            handle.write(text)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
