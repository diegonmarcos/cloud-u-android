#!/usr/bin/env python3
"""Release orphan guard (#637) — every asset on the rolling release is named by the fleet.

WHAT IT HOLDS.  Each asset on the `latest` release is either referenced by a
fleet-manifest row (`apps[].asset` or a value of `apps[].assets{}`), is a
sidecar (`.sha256`, `.source`) of such an asset, or is listed in
1_cicd/src/data/release-orphan-guard.json::retained with a reason.  A retained
entry that is no longer on the release, or that a row now references, is
stale and also fails, so the excuse list can only shrink.

WHY.  #628 moved both terminals' rootfs into companion lib APKs.  The bare
`cloud-rootfs-*.tar.zst` / `cloud-nixdroid-bootstrap-*.zip` assets it replaced
then sat on the release, ~1.6 GB, reachable by no shipped code, and nothing
noticed: a publish step only ever adds names, and no check compared the
release against what the fleet still installs.

Exit 0 = no unexplained asset.  Exit 1 = orphan, stale retained entry, or an
empty listing (an empty read is a failed read, never "the release is clean").

--assets FILE reads newline-separated asset names instead of asking GitHub;
the mutation test drives the guard through it.
"""

import argparse
import json
import os
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
DECL = os.path.join(ROOT, "1_cicd/src/data/release-orphan-guard.json")


def release_assets(repo, tag):
    rel = json.loads(subprocess.check_output(["gh", "api", f"repos/{repo}/releases/tags/{tag}"]))
    out = subprocess.check_output(
        ["gh", "api", "--paginate", f"repos/{repo}/releases/{rel['id']}/assets?per_page=100", "--jq", ".[].name"])
    return out.decode().split()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets")
    ap.add_argument("--decl", default=DECL)
    ap.add_argument("--manifest")
    a = ap.parse_args()

    decl = json.load(open(a.decl))
    manifest = json.load(open(a.manifest or os.path.join(ROOT, decl["manifest"])))
    referenced = set()
    for row in manifest["apps"]:
        if row.get("asset"):
            referenced.add(row["asset"])
        referenced.update(v for v in (row.get("assets") or {}).values() if v)

    names = open(a.assets).read().split() if a.assets else release_assets(decl["repo"], decl["tag"])
    if not names:
        print("FAIL  the release listing is empty — a failed read, not a clean release")
        return 1

    def base(n):
        for s in decl["sidecars"]:
            if n.endswith(s):
                return n[: -len(s)]
        return n

    retained = decl["retained"]
    on_release = {base(n) for n in names}
    fail = 0
    for n in sorted(set(names)):
        b = base(n)
        if b not in referenced and b not in retained:
            print(f"FAIL  orphan asset {n!r}: no fleet-manifest row names {b!r} and it is not in retained")
            fail = 1
    for r in sorted(retained):
        if r in referenced:
            print(f"FAIL  retained {r!r} is now referenced by a fleet row — drop it from retained")
            fail = 1
        elif r not in on_release:
            print(f"FAIL  retained {r!r} is no longer on the release — drop it from retained")
            fail = 1
    print(f"{'FAIL' if fail else 'OK  '}  {len(names)} assets, {len(referenced)} referenced names, {len(retained)} retained")
    return fail


if __name__ == "__main__":
    sys.exit(main())
