#!/usr/bin/env python3
"""
cloud-android-mesh-membership-audit — #743: is every PUBLISHED constellation
APK a full fleet mesh member?

The Apps Mesh page reports membership off the phone; this reports it off the
artifacts the phone installs, so a gap is visible before anyone installs it.
Source-level checks were not enough: eleven apps were rebuilt on the #733
manifest and still saw 39 of 40 members, because the 40th —
Cloud-Lib-Devtools.apk — carried the fleet provider without libs:core and so
neither defined nor held CONSTELLATION_DATA. Only the APK manifest shows that.

ROSTER: aa_cloud-superapp/data/constellation-fleet.json `apps` (nothing listed
here). Each row's release asset is read from the rolling `latest` release.

FULL MEMBER = the binary AndroidManifest.xml carries
  - FleetTokenProvider at <package>.fleet          (membership marker)
  - FleetMemberReceiver answering MESH_MEMBER      (visible to every member)
  - a <queries> intent for MESH_MEMBER              (sees every member)
  - <permission> AND <uses-permission> CONSTELLATION_DATA
Every row of kind `app` must be a full member. A `lib` row must be a full
member IF it carries the provider at all (most libs are not members, and that
is not a gap); a lib carrying it partially is exactly the #743 defect.

USAGE  cloud-android-mesh-membership-audit.py [ROOT] [--only id,id,...]
       needs `gh` (authenticated) to download release assets.
EXIT   0 all audited rows pass · 1 at least one gap · 3 nothing audited
"""
import json, os, struct, subprocess, sys, tempfile, zipfile

PERM = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"
ACTION = "com.diegonmarcos.cloud.action.MESH_MEMBER"


def manifest_tags(axml: bytes):
    """Start tags of a binary AndroidManifest.xml as (name, {attr: value})."""
    pos, strings, tags = 8, [], []
    while pos < len(axml):
        ctype, hsize, csize = struct.unpack_from("<HHI", axml, pos)
        if ctype == 0x0001:  # string pool
            n, _, flags, sstart, _ = struct.unpack_from("<IIIII", axml, pos + 8)
            offs = struct.unpack_from("<%dI" % n, axml, pos + hsize)
            for o in offs:
                p = pos + sstart + o
                if flags & 0x100:  # UTF-8: utf16 len, utf8 len, bytes
                    p += 2 if axml[p] & 0x80 else 1
                    ln = axml[p]; p += 1
                    if ln & 0x80:
                        ln = ((ln & 0x7F) << 8) | axml[p]; p += 1
                    strings.append(axml[p:p + ln].decode("utf-8", "replace"))
                else:
                    ln = struct.unpack_from("<H", axml, p)[0]; p += 2
                    if ln & 0x8000:
                        ln = ((ln & 0x7FFF) << 16) | struct.unpack_from("<H", axml, p)[0]; p += 2
                    strings.append(axml[p:p + 2 * ln].decode("utf-16le", "replace"))
        elif ctype == 0x0102:  # start element
            _, name, astart, asize, acount = struct.unpack_from("<IIHHH", axml, pos + 16)
            attrs = {}
            for i in range(acount):
                a = pos + 16 + astart + i * asize
                _, an, raw, _, _, dtype, data = struct.unpack_from("<IIIHBBI", axml, a)
                attrs[strings[an]] = strings[raw] if raw != 0xFFFFFFFF else (strings[data] if dtype == 3 else data)
            tags.append((strings[name], attrs))
        pos += csize
    return tags


def membership(tags):
    """Which of the five membership properties this manifest has."""
    pkg = next((a.get("package") for t, a in tags if t == "manifest"), "")
    has = {
        "provider": any(t == "provider" and str(a.get("authorities", "")).split(";").count(f"{pkg}.fleet")
                        and a.get("name", "").endswith(".FleetTokenProvider") for t, a in tags),
        "receiver": any(t == "receiver" and a.get("name", "").endswith(".FleetMemberReceiver") for t, a in tags),
        "defines": any(t == "permission" and a.get("name") == PERM for t, a in tags),
        "holds": any(t == "uses-permission" and a.get("name") == PERM for t, a in tags),
    }
    # MESH_MEMBER appears once under the receiver's intent-filter and once under
    # <queries>; the parser is flat, so tell them apart by the enclosing tag.
    seen, ctx = 0, None
    for t, a in tags:
        if t in ("queries", "receiver", "service", "activity", "provider", "application"):
            ctx = t
        if t == "action" and a.get("name") == ACTION and ctx == "queries":
            seen += 1
    has["queries"] = seen > 0
    return pkg, has


def main(argv):
    root = next((a for a in argv if not a.startswith("--")), None) or \
        subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True).stdout.strip()
    only = next((a.split("=", 1)[1] for a in argv if a.startswith("--only=")), "")
    only = set(filter(None, only.split(",")))
    fleet = json.load(open(os.path.join(root, "aa_cloud-superapp/data/constellation-fleet.json")))["apps"]
    rows = [r for r in fleet if r.get("asset") and (not only or r["id"] in only)]
    gaps = audited = 0
    with tempfile.TemporaryDirectory() as tmp:
        for r in rows:
            got = subprocess.run(["gh", "release", "download", "latest", "-R", "diegonmarcos/cloud-u-android",
                                  "-p", r["asset"], "-D", tmp, "--clobber"], capture_output=True, text=True)
            apk = os.path.join(tmp, r["asset"])
            if got.returncode != 0 or not os.path.isfile(apk):
                print(f"GAP     {r['id']:20} {r['asset']}: not on release `latest` ({got.stderr.strip()[:80]})")
                gaps += 1
                continue
            pkg, has = membership(manifest_tags(zipfile.ZipFile(apk).read("AndroidManifest.xml")))
            audited += 1
            full = all(has.values())
            must = r.get("kind") == "app" or has["provider"]
            verdict = "MEMBER " if full else ("GAP    " if must else "n/a    ")
            if must and not full:
                gaps += 1
            missing = ",".join(k for k, v in has.items() if not v) or "-"
            print(f"{verdict} {r['id']:20} {pkg:40} missing={missing}")
    if audited == 0:
        print("nothing audited — the roster or the release moved; this audit has no subjects")
        return 3
    print(f"── {audited} audited, {gaps} gap(s) ──")
    return 1 if gaps else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
