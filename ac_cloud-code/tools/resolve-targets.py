#!/usr/bin/env python3
"""cloud-code: resolve the fleet ids nav.json names into launchable targets (#562).

nav.json::myterminal.target is a fleet application id (build.json::name of a
sibling app in cloud-u-android), never a package. This derives, from that app's
own declarations and nothing else:
  package  = <app>/build.json::android.application_id
  activity = the LAUNCHER activity of <app>/<gradle_module>/src/main/AndroidManifest.xml,
             a leading '.' resolved against the module's gradle `namespace`.
It writes src/cloud/targets.gen.json (gitignored), which the web bundle imports.
A target that resolves to nothing FAILS the build: a MyTerminal button that
launches a package that does not exist is the bug this replaces.

#575: the same file carries `shared_root` — the ONE on-phone repo store, read
from the store app (nav.json::shared_store.app, cloud-drive) build.json's
storage.shared_root — so the Backlog and Repos tabs never restate a path.

Chat (replaced Home): `chat` carries what nav.json::chat cannot say by itself, read from the
fleet's own data in this repository: every agent (nav.json's declared order first, then every
other fleet service named chat.agent_prefix*), each with `deployed` (the fleet declares it) and
the mesh address it is reached at; the gateway's base URL (chat.gateway.service's private_dns);
the claude backend's base URL (for its OAuth login); the fleet MCP server NAMES (chat.fleet_mcp,
never a url or header); and the Account app, resolved like MyTerminal.

usage: resolve-targets.py <app-root> [--out FILE] [--print]
"""
import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"


def resolve(app_root, fleet_id):
    repo = os.path.dirname(os.path.abspath(app_root))
    hits = []
    for bj in sorted(glob.glob(os.path.join(repo, "*", "build.json"))):
        try:
            data = json.load(open(bj))
        except (OSError, ValueError):
            continue
        if data.get("name") == fleet_id:
            hits.append((os.path.dirname(bj), data))
    if len(hits) != 1:
        raise SystemExit("resolve-targets: fleet id %r matches %d apps (need exactly 1)" % (fleet_id, len(hits)))
    app_dir, data = hits[0]
    package = (data.get("android") or {}).get("application_id")
    if not package:
        raise SystemExit("resolve-targets: %s/build.json has no android.application_id" % os.path.basename(app_dir))
    module = (data.get("android") or {}).get("gradle_module") or "app"
    manifest = os.path.join(app_dir, module, "src", "main", "AndroidManifest.xml")
    gradle = os.path.join(app_dir, module, "build.gradle")
    namespace = None
    if os.path.isfile(gradle):
        m = re.search(r"""^\s*namespace\s*[=(]?\s*['"]([\w.]+)['"]""", open(gradle).read(), re.M)
        namespace = m and m.group(1)
    namespace = namespace or package
    launcher = None
    for act in ET.parse(manifest).getroot().iter("activity"):
        for f in act.iter("intent-filter"):
            actions = {a.get(ANDROID + "name") for a in f.iter("action")}
            cats = {c.get(ANDROID + "name") for c in f.iter("category")}
            if "android.intent.action.MAIN" in actions and "android.intent.category.LAUNCHER" in cats:
                launcher = act.get(ANDROID + "name")
    if not launcher:
        raise SystemExit("resolve-targets: no LAUNCHER activity in %s" % os.path.relpath(manifest, repo))
    activity = namespace + launcher if launcher.startswith(".") else launcher
    return {"id": fleet_id, "app_dir": os.path.basename(app_dir), "package": package, "activity": activity}


def app_by_fleet_id(app_root, fleet_id):
    """The ONE sibling app whose build.json::name is fleet_id, with its parsed build.json."""
    repo = os.path.dirname(os.path.abspath(app_root))
    hits = []
    for bj in sorted(glob.glob(os.path.join(repo, "*", "build.json"))):
        try:
            data = json.load(open(bj))
        except (OSError, ValueError):
            continue
        if data.get("name") == fleet_id:
            hits.append((os.path.dirname(bj), data))
    if len(hits) != 1:
        raise SystemExit("resolve-targets: fleet id %r matches %d apps (need exactly 1)" % (fleet_id, len(hits)))
    return hits[0]


def shared_root(app_root, fleet_id):
    """#575: the ONE shared repo store, read from the store app's own declaration.

    nav.json::shared_store.app names cloud-drive by fleet id; that app's
    build.json::storage.shared_root is the folder under shared storage. It is
    copied here RELATIVE, exactly as declared: index.js prefixes the device's
    externalRootDirectory at runtime. An absolute value, or none, fails the build."""
    app_dir, data = app_by_fleet_id(app_root, fleet_id)
    root = ((data.get("storage") or {}).get("shared_root") or "").strip()
    if not root or root.startswith("/") or ".." in root:
        raise SystemExit("resolve-targets: %s/build.json::storage.shared_root must be a relative folder (got %r)"
                         % (os.path.basename(app_dir), root))
    return root


def git_subdir(app_root, fleet_id):
    """#676: the store's ONE git folder, read from the same declaration.

    #606/#608 moved every clone under <shared_root>/<git_subdir>; a consumer
    composing paths at the store's ROOT is a second tree (the bug #676 closes).
    Copied here exactly as cloud-drive declares it — one relative segment —
    so this app's Backlog and Repos land where the store actually clones."""
    app_dir, data = app_by_fleet_id(app_root, fleet_id)
    sub = ((data.get("storage") or {}).get("git_subdir") or "").strip()
    if not sub or sub.startswith("/") or "/" in sub or ".." in sub:
        raise SystemExit("resolve-targets: %s/build.json::storage.git_subdir must be ONE relative folder name (got %r)"
                         % (os.path.basename(app_dir), sub))
    return sub


def fleet_services(app_root, rel):
    repo = os.path.dirname(os.path.abspath(app_root))
    path = os.path.join(repo, rel)
    try:
        services = json.load(open(path))
    except (OSError, ValueError) as e:
        raise SystemExit("resolve-targets: cannot read the fleet services %s (%s)" % (rel, e))
    if not isinstance(services, list) or not services:
        raise SystemExit("resolve-targets: %s is not a list of services" % rel)
    return {s["name"]: s for s in services if isinstance(s, dict) and s.get("name")}


def base_url(service):
    """http://<private_dns> for a mesh service (WireGuard-only: plain http on the mesh)."""
    dns = (service or {}).get("private_dns") or ""
    if not dns or ":" not in dns:
        return None
    return "http://" + dns


def chat(app_root, nav):
    c = nav["chat"]
    services = fleet_services(app_root, c["fleet_services"])
    prefix = c["agent_prefix"]
    gateway = services.get(c["gateway"]["service"])
    if not gateway or not base_url(gateway):
        raise SystemExit("resolve-targets: the gateway %r is not a fleet service with an address" % c["gateway"]["service"])
    agents, seen = [], set()
    for a in c["agents"]:
        svc = services.get(a["fleet"])
        seen.add(a["fleet"])
        agents.append({"id": a["id"], "fleet": a["fleet"], "deployed": svc is not None,
                       "address": (svc or {}).get("private_dns"), "declared": True})
    for name in sorted(services):
        if name.startswith(prefix) and name not in seen:
            svc = services[name]
            agents.append({"id": name[len(prefix):], "fleet": name, "deployed": True,
                           "address": svc.get("private_dns"), "declared": False})
    repo = os.path.dirname(os.path.abspath(app_root))
    try:
        mcp = sorted((json.load(open(os.path.join(repo, c["fleet_mcp"]))).get("mcpServers") or {}).keys())
    except (OSError, ValueError) as e:
        raise SystemExit("resolve-targets: cannot read the fleet MCP list %s (%s)" % (c["fleet_mcp"], e))
    claude = next((a for a in c["agents"] if a["mode"] == "claude-cli"), None)
    return {"agents": agents,
            "gateway": base_url(gateway),
            "claude_api": base_url(services.get(claude["fleet"])) if claude else None,
            "mcp_fleet": mcp,
            "account": resolve(app_root, c["account"]["app"])}


def main(argv):
    if len(argv) < 2:
        raise SystemExit(__doc__)
    app_root = argv[1]
    nav = json.load(open(os.path.join(app_root, "src", "cloud", "nav.json")))
    out = {"_doc": "GENERATED by tools/resolve-targets.py from nav.json + the fleet. Do not edit.",
           "myterminal": resolve(app_root, nav["myterminal"]["target"]),
           "shared_root": shared_root(app_root, nav["shared_store"]["app"]),
           "git_subdir": git_subdir(app_root, nav["shared_store"]["app"]),
           "chat": chat(app_root, nav)}
    text = json.dumps(out, indent=2) + "\n"
    if "--print" in argv:
        sys.stdout.write(text)
    if "--out" in argv:
        open(argv[argv.index("--out") + 1], "w").write(text)


if __name__ == "__main__":
    main(sys.argv)
