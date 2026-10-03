# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-debug-ports-guard.py ───
#!/usr/bin/env python3
"""Debug-ports guard — every constellation package owns one debug-API port.

#792.  AppDebugServer used to take the first free port in 38090-38139.  Fifty
ports for sixty mesh members: the members that woke last found nothing free and
served no debug API at all — sixteen NO_DEBUG_API rows on the S21+ (2026-10-03),
every one of them a healthy app.  Now each package binds the port
ab_cloud-libs-shared/libs/devtools/debug-ports.json gives it (baked into
BuildConfig.DEBUG_PORTS), and only scans — unowned ports only — when a foreign
process holds its own.

HOLDS
  P1  every constellation-fleet.json `apps` row with a package has a port
  P2  ports are unique and inside `range`
  P3  the unowned headroom in `range` can absorb every owned member falling back
  P4  the source still binds the table: AppDebugServer binds portOf(pkg) first
      and falls back over fallbackPorts(...); devtools' build.gradle bakes
      debug-ports.json; the Apps Mesh probe pings AppDebugServer.portOf
A port whose package left the fleet is NOT an error: ports are never reused,
so a retired number stays reserved.

USAGE  cloud-android-debug-ports-guard.py [ROOT]
EXIT   0 holds · 1 at least one violation
"""
import json
import os
import re
import sys

FLEET = "aa_cloud-superapp/data/constellation-fleet.json"
PORTS = "ab_cloud-libs-shared/libs/devtools/debug-ports.json"
GRADLE = "ab_cloud-libs-shared/libs/devtools/build.gradle"
SERVER = "ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/AppDebugServer.kt"
PROBE = "ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreMesh.kt"


def violations(root):
    out = []
    fleet = json.load(open(os.path.join(root, FLEET)))["apps"]
    decl = json.load(open(os.path.join(root, PORTS)))
    first, last = decl["range"]
    ports = {k: v for k, v in decl["ports"].items() if not k.startswith("_")}

    for row in fleet:
        pkg = row.get("package")
        if pkg and pkg not in ports:
            out.append(f"P1 {row['id']} ({pkg}) has no port in {PORTS}")

    owner = {}
    for pkg, p in ports.items():
        if not isinstance(p, int) or not first <= p <= last:
            out.append(f"P2 {pkg} :{p} is outside the range {first}..{last}")
        if p in owner:
            out.append(f"P2 port {p} is given to both {owner[p]} and {pkg}")
        owner.setdefault(p, pkg)

    free = (last - first + 1) - len({p for p in ports.values() if isinstance(p, int) and first <= p <= last})
    if free < len(ports):
        out.append(f"P3 only {free} unowned ports in {first}..{last} for {len(ports)} owned: "
                   f"a fleet-wide fallback would exhaust the range again")

    server = open(os.path.join(root, SERVER)).read()
    bind = re.search(r"private fun bindOwn\(.*?\n    }\n", server, re.S)
    if not bind or "portOf(pkg)" not in bind.group(0) or "fallbackPorts(" not in bind.group(0):
        out.append("P4 AppDebugServer.bindOwn no longer binds portOf(pkg) first with a fallbackPorts(...) scan")
    if not re.search(r"^\s*val sock = bindOwn\(", server, re.M):
        out.append("P4 AppDebugServer.start does not bind through bindOwn")
    if "BuildConfig.DEBUG_PORTS" not in server:
        out.append("P4 AppDebugServer does not read the baked BuildConfig.DEBUG_PORTS table")
    gradle = open(os.path.join(root, GRADLE)).read()
    if "'debug-ports.json'" not in gradle or '"DEBUG_PORTS"' not in gradle:
        out.append("P4 libs/devtools/build.gradle no longer bakes debug-ports.json into DEBUG_PORTS")
    if "AppDebugServer.portOf(" not in open(os.path.join(root, PROBE)).read():
        out.append("P4 the Apps Mesh probe no longer reads the port table (AppDebugServer.portOf)")
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
