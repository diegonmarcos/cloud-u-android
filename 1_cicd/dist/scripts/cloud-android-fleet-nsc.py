# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-fleet-nsc.py ───
#!/usr/bin/env python3
"""cloud-android-fleet-nsc -- the cleartext `.app` allowlist of every fleet app's network_security_config.xml is GENERATED
from the one fleet endpoint declaration (ac_cloud-account/build.json::fleet_endpoints), never hand-listed (#1093).

  write ROOT   rewrite the generated block of every app listed in 1_cicd/src/data/fleet-nsc.json
  check ROOT   exit 1 when a file's block differs from what the declaration derives (FleetHostGuardTest runs the same rule)

The block sits inside a `<domain-config cleartextTrafficPermitted="true">` element, between the BEGIN and END marker comments.
A mesh host is `<service>.app` on WireGuard (WG encrypts), so only services whose mesh_scheme is http are listed, and a public
host is never cleartext. An app whose file has no such element gets one (before </network-security-config>).
"""
import json, os, re, sys

DATA = "1_cicd/src/data/fleet-nsc.json"
BEGIN = "<!-- BEGIN fleet-mesh-cleartext (generated from ac_cloud-account/build.json::fleet_endpoints by 1_cicd/src/scripts/cloud-android-fleet-nsc.py; do not edit) -->"
END = "<!-- END fleet-mesh-cleartext -->"
BLOCK = re.compile(r"[ \t]*" + re.escape(BEGIN) + r".*?" + re.escape(END) + r"\n", re.S)


def hosts(root, cfg, wanted):
    decl = json.load(open(os.path.join(root, cfg["declaration"])))["fleet_endpoints"]["services"]
    out = sorted({s["mesh_dns"] for s in decl if s.get("mesh_scheme", "https") == "http" and (wanted == "*" or s["id"] in wanted)})
    return out


def block(hs):
    return "".join(["        " + BEGIN + "\n"] + ["        <domain>%s</domain>\n" % h for h in hs] + ["        " + END + "\n"])


def render(text, hs):
    new = block(hs)
    if BLOCK.search(text):
        return BLOCK.sub(lambda m: new, text, count=1)
    m = re.search(r'(<domain-config cleartextTrafficPermitted="true">\n)', text)
    if m:
        return text[:m.end()] + new + text[m.end():]
    cfgel = '    <domain-config cleartextTrafficPermitted="true">\n' + new + "    </domain-config>\n"
    return text.replace("</network-security-config>", "\n" + cfgel + "</network-security-config>", 1)


def main(argv):
    if len(argv) < 2 or argv[0] not in ("write", "check"):
        print(__doc__); return 2
    root = os.path.abspath(argv[1]); cfg = json.load(open(os.path.join(root, DATA))); bad = 0
    for app in cfg["apps"]:
        p = os.path.join(root, app["nsc"]); text = open(p, encoding="utf-8").read()
        want = render(text, hosts(root, cfg, app["services"]))
        if want != text:
            if argv[0] == "write": open(p, "w", encoding="utf-8").write(want); print("wrote  " + app["nsc"])
            else: print("FAIL   " + app["nsc"] + " is not what the declaration derives"); bad = 1
    return bad


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
