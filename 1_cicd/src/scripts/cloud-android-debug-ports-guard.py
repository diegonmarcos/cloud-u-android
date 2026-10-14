#!/usr/bin/env python3
"""Debug-ports guard — every constellation package owns one debug-API port.

#792.  AppDebugServer used to take the first free port in 38090-38139.  Fifty
ports for sixty mesh members: the members that woke last found nothing free and
served no debug API at all — sixteen NO_DEBUG_API rows on the S21+ (2026-10-03),
every one of them a healthy app.  Now each package binds the port
1_cicd/src/data/debug-ports.json gives it, and only scans — the `fallback`
sub-range no package owns — when a foreign process holds its own.

#796: the table is no longer compiled into every app. Each gradle root bakes
its own GENERATED slice (<root>/debug-api.json, cloud-android-mesh-slices-gen
.py) into BuildConfig.DEBUG_PORTS, so a new package's port rebuilds the roots
whose slice changed and nothing else.

HOLDS
  P1  every constellation-fleet.json `apps` row with a package has a port
  P2  ports are unique, inside `range`, and outside `fallback` (which is inside `range`)
  P3  `fallback` can absorb every LIVE member (a package with a fleet row) falling back at once;
      retired packages keep their number reserved but run nothing, so they are not counted
  P4  #ipc ONE DOOR PER PHONE (a0_docs/eng-specs/fleet-ipc.md): no app binds a
      port of its own (AppDebugServer opens no ServerSocket); the table is the
      VERSION-SKEW map where a pre-gateway build still listens, so AppDebugServer
      still reads BuildConfig.DEBUG_PORTS, devtools' build.gradle still bakes the
      root's debug-api.json and the Apps Mesh probe still asks
      AppDebugServer.portOf; the gateway is declared (`gateway`: the SuperApp,
      outside `range`, the same pkg/port FleetIpc names) and the SuperApp's
      DevControlServer routes /app/ to MeshGateway, which forwards over FleetIpc
  P5  every slice on disk is what the generator produces from the table, and
      no copy of the table is left inside libs/devtools
A port whose package left the fleet is NOT an error: ports are never reused,
so a retired number stays reserved.

USAGE  cloud-android-debug-ports-guard.py [ROOT]
EXIT   0 holds · 1 at least one violation
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.dont_write_bytecode = True
import importlib  # noqa: E402
slices_gen = importlib.import_module("cloud-android-mesh-slices-gen")

FLEET = "aa_cloud-superapp/data/constellation-fleet.json"
PORTS = "1_cicd/src/data/debug-ports.json"
OLD_PORTS = "ab_cloud-libs-shared/libs/devtools/debug-ports.json"
GRADLE = "ab_cloud-libs-shared/libs/devtools/build.gradle"
SERVER = "ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/AppDebugServer.kt"
PROBE = "ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreMesh.kt"
FLEETIPC = "ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/FleetIpc.kt"
DEVCONTROL = "aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/devcontrol/DevControlServer.kt"
GATEWAY = "aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/devcontrol/MeshGateway.kt"


def code(path):
    s = open(path, encoding="utf-8").read()
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    return re.sub(r'(?m)(^|[^:"])//[^\n]*', r"\1", s)


def violations(root):
    out = []
    fleet = json.load(open(os.path.join(root, FLEET)))["apps"]
    decl = json.load(open(os.path.join(root, PORTS)))
    first, last = decl["range"]
    fb_first, fb_last = decl.get("fallback") or (None, None)
    ports = {k: v for k, v in decl["ports"].items() if not k.startswith("_")}
    if fb_first is None or not first <= fb_first <= fb_last <= last:
        out.append(f"P2 `fallback` {fb_first}..{fb_last} is not a sub-range of {first}..{last}")
        fb_first, fb_last = last + 1, last

    for row in fleet:
        pkg = row.get("package")
        if pkg and pkg not in ports:
            out.append(f"P1 {row['id']} ({pkg}) has no port in {PORTS}")

    owner = {}
    for pkg, p in ports.items():
        if not isinstance(p, int) or not first <= p <= last:
            out.append(f"P2 {pkg} :{p} is outside the range {first}..{last}")
        if isinstance(p, int) and fb_first <= p <= fb_last:
            out.append(f"P2 {pkg} :{p} is inside the fallback sub-range {fb_first}..{fb_last}, which no package may own")
        if p in owner:
            out.append(f"P2 port {p} is given to both {owner[p]} and {pkg}")
        owner.setdefault(p, pkg)

    # The members that can fall back are the packages a fleet row installs. A retired package keeps its
    # number reserved (never reused) but runs nothing, so it does not compete for a fallback port: counting
    # the 40+ retired lib packages made every NEW engine APK (#871 ops-engine, #881 decisions-engine) the
    # one that tipped the table over the fallback and demanded a fleet-wide slice change.
    live = sum(1 for pkg in ports if pkg in {r.get("package") for r in fleet})
    free = fb_last - fb_first + 1
    if free < live:
        out.append(f"P3 only {free} fallback ports in {fb_first}..{fb_last} for {live} live members "
                   f"({len(ports)} numbers reserved): a fleet-wide fallback would exhaust the range again")

    server = open(os.path.join(root, SERVER)).read()
    if re.search(r"\bServerSocket\s*\(|\.accept\(\)", code(os.path.join(root, SERVER))):
        out.append("P4 AppDebugServer binds a port again: no app listens, the SuperApp gateway is the one door")
    gw = decl.get("gateway") or {}
    ipc = open(os.path.join(root, FLEETIPC)).read()
    if not gw.get("pkg") or not isinstance(gw.get("port"), int):
        out.append(f"P4 {PORTS} declares no `gateway` (pkg, port): the one mesh door is undeclared")
    else:
        if first <= gw["port"] <= last:
            out.append(f"P4 the gateway port {gw['port']} is inside the per-app range {first}..{last}")
        if f'GATEWAY_PKG = "{gw["pkg"]}"' not in ipc or f"GATEWAY_PORT = {gw['port']}" not in ipc:
            out.append(f"P4 FleetIpc.GATEWAY_PKG/GATEWAY_PORT differ from {PORTS}::gateway ({gw['pkg']}:{gw['port']})")
    dc = code(os.path.join(root, DEVCONTROL)) if os.path.exists(os.path.join(root, DEVCONTROL)) else ""
    mg = code(os.path.join(root, GATEWAY)) if os.path.exists(os.path.join(root, GATEWAY)) else ""
    if "path.startsWith(MeshGateway.PREFIX)" not in dc or "MeshGateway.handle(" not in dc or "FleetIpc.call(" not in mg:
        out.append("P4 the SuperApp's DevControlServer no longer forwards /app/<pkg>/api/<op> through MeshGateway over FleetIpc")
    if "BuildConfig.DEBUG_PORTS" not in server:
        out.append("P4 AppDebugServer does not read the baked BuildConfig.DEBUG_PORTS table")
    gradle = open(os.path.join(root, GRADLE)).read()
    if "'debug-api.json'" not in gradle or '"DEBUG_PORTS"' not in gradle or '"DEBUG_FALLBACK_FIRST"' not in gradle:
        out.append("P4 libs/devtools/build.gradle no longer bakes the root's debug-api.json into DEBUG_PORTS / DEBUG_FALLBACK_*")
    if "AppDebugServer.portOf(" not in open(os.path.join(root, PROBE)).read():
        out.append("P4 the Apps Mesh probe no longer reads the port table (AppDebugServer.portOf)")
    if os.path.exists(os.path.join(root, OLD_PORTS)):
        out.append(f"P5 {OLD_PORTS} exists: a copy of the table inside libs/devtools is a compile input of every app again")
    try:
        for r, want in slices_gen.slices(root).items():
            path = os.path.join(root, r, slices_gen.SLICE)
            have = open(path, encoding="utf-8").read() if os.path.isfile(path) else None
            if have != slices_gen.render(want):
                out.append(f"P5 {r}/{slices_gen.SLICE} is not what the generator produces — run ./build.sh workflow")
    except SystemExit as e:
        out.append(f"P5 {e}")
    return out, len(ports), first, last


def main(argv):
    root = argv[0] if argv else os.getcwd()
    out, n, first, last = violations(root)
    for v in out:
        print("FAIL " + v)
    print(f"── {n} ports in {first}..{last}, {len(out)} violation(s) ──")
    return 1 if out else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
