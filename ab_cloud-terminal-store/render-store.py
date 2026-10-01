#!/usr/bin/env python3
"""#644 — render store.json (+ the app's own tool list) into declaration.sh.

The engine is POSIX sh, so its declaration is sh: sourceable key=value, no JSON
parser in a shell that may be starting with nothing but coreutils on PATH. The
JSON stays the authored form and this is the build-time projection of it.

    render-store.py <terminal> [<out>]       # default: stdout
    render-store.py --check                  # every terminal renders, literals agree

Importable too: bake_default_packages.py calls render(terminal) so the nix
bootstrap bake needs no new gradle argument and no engine-script change.

Why a projection and not a second declaration: the tool list is NOT restated
here. `terminals.<t>.tools` points into the app's existing declaration
(build.json::...default_packages.binaries, rootfs.json::binaries), so a tool
enters the store by being declared where it was already declared. That is the
zero-code-change property #644 is actually about, and test-bootstrap-baked.sh
asserts it by comparing what this renders against that list.
"""

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
STORE_JSON = os.path.join(HERE, "store.json")
REPO = os.path.dirname(HERE)
LOGIN_INIT = os.path.join(HERE, "login-init.sh")


def _dig(blob, dotted):
    for part in dotted.split("."):
        if not isinstance(blob, dict) or part not in blob:
            raise SystemExit("render-store.py: %s has no %s" % (dotted, part))
        blob = blob[part]
    return blob


def _value(spec, app_dir):
    """A declared value: a literal, or a pointer into the app's own declaration.

    One resolver for every indirection in store.json, so adding another field
    that follows build.json never means another branch.
    """
    if not isinstance(spec, dict) or "from_file" not in spec:
        return spec
    path = os.path.join(REPO, app_dir, spec["from_file"])
    if not os.path.isfile(path):
        raise SystemExit("render-store.py: store.json points at %s, which does not exist" % path)
    with open(path, encoding="utf-8") as handle:
        got = _dig(json.load(handle), spec["at"])
    if isinstance(got, str):
        got = spec.get("prepend", "") + got + spec.get("append", "")
    return got


def _quote(value):
    return "'" + str(value).replace("'", "'\\''") + "'"


def load():
    with open(STORE_JSON, encoding="utf-8") as handle:
        return json.load(handle)


def check_login_init(decl):
    """The one literal that cannot come from the declaration at runtime.

    login-init.sh has to find declaration.sh before it can read declaration.sh,
    so it carries the install directory as a literal. A silent disagreement
    between that literal and store.json is a store that never initialises and a
    terminal that looks fine until a link rots — exactly #644's failure mode one
    level down. So it is checked, loudly, at build time.
    """
    want = "/" + decl["store"]["install_dir"].strip("/")
    marker = ': "${CLOUD_STORE_INSTALL_DIR:=%s}"' % want
    with open(LOGIN_INIT, encoding="utf-8") as handle:
        body = handle.read()
    if marker not in body:
        raise SystemExit(
            "render-store.py: login-init.sh does not default CLOUD_STORE_INSTALL_DIR to %s, "
            "which is store.json::store.install_dir — the login path would look for the "
            "declaration somewhere it is not shipped" % want)
    return want


def toolset(decl=None):
    """#737 -- store.json::toolset.binaries, the set BOTH terminals must ship."""
    got = (decl or load()).get("toolset", {}).get("binaries")
    if not isinstance(got, list) or not got:
        raise SystemExit("render-store.py: store.json::toolset.binaries is %r, not a non-empty list" % (got,))
    return got


def tools_of(terminal, decl=None):
    """The tools a terminal ships: the shared toolset, then its own extras.

    Exposed because bake_default_packages.py and verify_login_closure.py gate
    the realized nix profile and the shipped zip against it: a name here with
    no binary behind it is a link that dangles on every phone, which is the
    defect class #644 exists to close. An app list that restates a toolset
    name is refused: that is the first step back to two lists that drift.
    """
    decl = decl or load()
    term = decl["terminals"][terminal]
    shared = toolset(decl)
    own = _value(term["tools"], term["app_dir"])
    if not isinstance(own, list):
        raise SystemExit("render-store.py: %s's tool list resolved to %r, not a list" % (terminal, own))
    again = [t for t in own if t in shared]
    if again:
        raise SystemExit("render-store.py: %s restates %s, already in store.json::toolset -- "
                         "delete them from %s/%s" % (terminal, ", ".join(again), term["app_dir"],
                                                    "::".join((term["tools"].get("from_file", ""), term["tools"].get("at", "")))
                                                    if isinstance(term["tools"], dict) else "its literal list"))
    return shared + own


def render(terminal):
    decl = load()
    install_dir = check_login_init(decl)
    if terminal not in decl["terminals"]:
        raise SystemExit("render-store.py: store.json declares no terminal %r (have: %s)"
                         % (terminal, ", ".join(sorted(decl["terminals"]))))
    term = decl["terminals"][terminal]
    store = decl["store"]
    app_dir = term["app_dir"]

    tools = tools_of(terminal, decl)
    if not tools:
        raise SystemExit("render-store.py: %s's tool list resolved to %r, not a non-empty list"
                         % (terminal, tools))
    dupes = sorted({t for t in tools if tools.count(t) > 1})
    if dupes:
        raise SystemExit("render-store.py: %s declares these tools twice: %s"
                         % (terminal, ", ".join(dupes)))
    if store["engine"] in tools:
        raise SystemExit("render-store.py: %s declares %r as a tool, but the engine links itself"
                         % (terminal, store["engine"]))

    target = term["target"]
    mode = target["mode"]
    if mode not in ("prefix", "search"):
        raise SystemExit("render-store.py: target mode %r is not one cloud-store implements" % mode)
    prefix = _value(target.get("prefix", ""), app_dir) if mode == "prefix" else ""
    search = _value(target.get("search", []), app_dir) if mode == "search" else []

    fixed = []
    for link in term.get("fixed_links", []):
        if "=" in link["path"] or " " in link["path"]:
            raise SystemExit("render-store.py: fixed link path %r cannot hold '=' or a space "
                             "(declaration.sh encodes them as path=tool, space separated)"
                             % link["path"])
        if link["tool"] not in tools:
            raise SystemExit("render-store.py: fixed link %s wants %r, which %s does not declare"
                             % (link["path"], link["tool"], terminal))
        fixed.append("%s=%s" % (link["path"], link["tool"]))

    nix_profile = _value(term.get("nix_profile") or "", app_dir)
    if term["package_manager"] == "nix" and not nix_profile:
        raise SystemExit("render-store.py: %s says package_manager nix but declares no profile" % terminal)

    lines = [
        "# GENERATED by ab_cloud-terminal-store/render-store.py — do not edit.",
        "# Source: store.json::toolset + %s/%s (#644, #737)." % (app_dir, term["tools"]["from_file"]
                                                  if isinstance(term["tools"], dict) else "literal"),
        "# A tool is added by editing toolset (both terminals) or THAT list; cloud-store stays untouched.",
        "CLOUD_STORE_TERMINAL=%s" % _quote(terminal),
        "CLOUD_STORE_ROOT=%s" % _quote(store["root"]),
        "CLOUD_STORE_CURRENT=%s" % _quote(store["current"]),
        "CLOUD_STORE_GENERATIONS=%s" % _quote(store["generations"]),
        "CLOUD_STORE_BIN=%s" % _quote(store["bin"]),
        "CLOUD_STORE_ENGINE=%s" % _quote(store["engine"]),
        "CLOUD_STORE_KEEP=%s" % _quote(store["keep_generations"]),
        "CLOUD_STORE_INSTALL_DIR=%s" % _quote(install_dir),
        "CLOUD_STORE_MODE=%s" % _quote(mode),
        "CLOUD_STORE_PREFIX=%s" % _quote(prefix),
        "CLOUD_STORE_SEARCH=%s" % _quote(" ".join(search)),
        "CLOUD_STORE_PACKAGE_MANAGER=%s" % _quote(term["package_manager"]),
        "CLOUD_STORE_NIX_PROFILE=%s" % _quote(nix_profile),
        "CLOUD_STORE_TOOLS=%s" % _quote(" ".join(tools)),
        "CLOUD_STORE_FIXED_LINKS=%s" % _quote(" ".join(fixed)),
    ]
    return "\n".join(lines) + "\n"


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    if argv[0] == "--check":
        for terminal in sorted(load()["terminals"]):
            body = render(terminal)
            print("render-store.py: ok — %s renders %d bytes" % (terminal, len(body)))
        return 0
    body = render(argv[0])
    if len(argv) > 1:
        with open(argv[1], "w", encoding="utf-8") as handle:
            handle.write(body)
    else:
        sys.stdout.write(body)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
