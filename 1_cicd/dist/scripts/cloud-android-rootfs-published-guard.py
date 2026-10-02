# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-rootfs-published-guard.py ───
#!/usr/bin/env python3
"""#786 -- the rootfs library ON THE RELEASE is the one the fleet row names, and the verified one.

Usage: cloud-android-rootfs-published-guard.py <app_dir> <variant> [--verified-sha256 FILE]

A phone reported #771's rootfs "not in the published lib" while CI said the tree had been
verified 16/16 and the fleet row had moved. Every link of that chain was green on its own and
nothing compared the ends: the bytes a phone downloads against the row the Store reads and the
tree verify-rootfs.sh ran. (The release was in fact right; the phone held an older install.
The chain is still unguarded, so this closes it.) Red unless:

  1. the LIVE release asset's assets/rootfs-lib.json "version" == the constellation-fleet.json
     row's version_name. Runs on EVERY run, gate-skipped or not, so a manifest that moved while
     the asset did not -- or the reverse -- is red on the run that would leave it so.
  2. when this run built the lib (<app_dir>/dist/<asset> exists): the live asset's manifest is
     byte-for-byte the built one, the release's .sha256 sidecar is the built APK's sha256, and
     its payload sha256 is the --verified-sha256 the rootfs job's tester ran against.

Only rootfs-lib.json is read from the live asset, through HTTP range requests: the APK is
~450-620 MB and its zip central directory says where that one entry is.

Every name comes from data: <app_dir>/fleet-lib.json (module, companion), build.json
release.companions[] (asset per variant), aa_cloud-superapp/data/constellation-fleet.json (row).
"""
import hashlib
import io
import json
import os
import sys
import urllib.request
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
FLEET = "aa_cloud-superapp/data/constellation-fleet.json"
MANIFEST_ENTRY = "assets/rootfs-lib.json"


class RemoteFile(io.RawIOBase):
    """A seekable read-only view of a URL, one Range request per read -- enough for ZipFile."""

    def __init__(self, url):
        req = urllib.request.Request(url, method="HEAD")
        with urllib.request.urlopen(req, timeout=60) as r:
            self.url = r.geturl()  # the signed object URL, so later reads skip the redirect
            self.size = int(r.headers["Content-Length"])
        self.pos = 0

    def readable(self):
        return True

    def seekable(self):
        return True

    def tell(self):
        return self.pos

    def seek(self, offset, whence=0):
        self.pos = {0: offset, 1: self.pos + offset, 2: self.size + offset}[whence]
        return self.pos

    def readinto(self, buf):
        if self.pos >= self.size or len(buf) == 0:
            return 0
        end = min(self.pos + len(buf), self.size) - 1
        req = urllib.request.Request(self.url, headers={"Range": "bytes=%d-%d" % (self.pos, end)})
        with urllib.request.urlopen(req, timeout=60) as r:
            if r.status != 206:
                raise IOError("%s answered %d to a Range request" % (self.url, r.status))
            data = r.read()
        buf[:len(data)] = data
        self.pos += len(data)
        return len(data)


def manifest(fileobj):
    with zipfile.ZipFile(fileobj) as z:
        return json.loads(z.read(MANIFEST_ENTRY))


def fetch_text(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read().decode().strip()


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def load(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as f:
        return json.load(f)


def main(argv):
    if len(argv) not in (2, 4) or (len(argv) == 4 and argv[2] != "--verified-sha256"):
        print(__doc__.split("\n\n")[1], file=sys.stderr)
        return 2
    app, variant = argv[0].rstrip("/"), argv[1]
    verified_file = argv[3] if len(argv) == 4 else None

    lib = load(os.path.join(app, "fleet-lib.json"))
    companion = next(c for c in load(os.path.join(app, "build.json"))["release"]["companions"]
                     if c["id"] == lib["companion"])
    asset = (companion.get("assets") or {}).get(variant) or companion["asset"]
    row_id = "lib-%s" % lib["module"]
    row = next((a for a in load(FLEET)["apps"] if a["id"] == row_id), None)
    if row is None:
        print("FAIL   %s has no row %s" % (FLEET, row_id))
        return 1
    url = row["release_url"].rsplit("/", 1)[0] + "/" + asset

    failures = []
    live = manifest(RemoteFile(url))
    print("live   %s -> %s" % (url, json.dumps(live)))
    print("row    %s version_name=%s" % (row_id, row["version_name"]))
    if live.get("version") != row["version_name"]:
        failures.append("the published %s is version %s, the fleet row %s says %s: phones are told "
                        "one rootfs and handed another" % (asset, live.get("version"), row_id, row["version_name"]))

    built = os.path.join(ROOT, app, "dist", asset)
    if os.path.isfile(built):
        local = manifest(built)
        digest = sha256(built)
        sidecar = fetch_text(url + ".sha256").split()[0]
        print("built  %s sha256=%s -> %s" % (built, digest, json.dumps(local)))
        if local != live:
            failures.append("this run built %s but the release serves %s" % (json.dumps(local), json.dumps(live)))
        if sidecar != digest:
            failures.append("the release's %s.sha256 is %s, the APK this run built is %s" % (asset, sidecar, digest))
        if not verified_file or not os.path.isfile(verified_file):
            failures.append("this run built %s but no verified rootfs digest was handed in (--verified-sha256)" % asset)
        else:
            with open(verified_file, encoding="utf-8") as f:
                verified = f.read().split()[0]
            print("tested %s (the tree verify-rootfs.sh ran)" % verified)
            if local.get("sha256") != verified:
                failures.append("the lib carries payload %s, the rootfs job verified %s" % (local.get("sha256"), verified))
    else:
        print("built  nothing this run (companion gate skipped): only the live asset vs the row is judged")

    for f in failures:
        print("::error::FAIL — %s" % f)
    if not failures:
        print("ok     %s on the release is %s, the row's version%s" % (
            asset, live.get("version"), ", and the bytes this run built and verified" if os.path.isfile(built) else ""))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
