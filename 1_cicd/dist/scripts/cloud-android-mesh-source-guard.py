# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-mesh-source-guard.py ───
#!/usr/bin/env python3
"""
cloud-android-mesh-source-guard — #753: no constellation app may drop out of
the fleet mesh, decided from SOURCE, on every push.

cloud-android-mesh-membership-audit.py reads the published APKs, so it can only
report a dropout after the APK that lost membership has shipped. #743/#744 found
seventeen apps that had never been members; nothing stopped the next edit that
removes a dependency line from quietly making it eighteen. This holds the same
property against the commit, before anything is built.

ROSTER: aa_cloud-superapp/data/constellation-fleet.json `apps`, every row of
kind `app` (nothing listed here). Its source dir is the row's repo_url path.

  G1  the app's dependency closure reaches libs:core AND libs:devtools. Seeds
      are the non-comment `<config> project(':libs:X')` lines in any gradle
      script or patch under the app's dir (patches: added lines only), expanded
      over the shared libs' own edges (ab_cloud-libs-shared/libs/*/build.gradle*)
      into the modules the app includes — computed by cloud_android_lib_closure,
      the same module the generator derives the ship triggers from.
      core brings CONSTELLATION_DATA; devtools brings provider, receiver and
      <queries> — one without the other is the #743 39-of-40 defect.
  G2  #763, two-sided: the app's ship workflow (the .github/workflows/ship-*.yml
      that watches "<dir>/**") watches EXACTLY the shared lib directories that
      are inputs of the app (cloud_android_lib_closure.inputs: the dependency
      closure, the module map, by-reference sources). Missing one means a lib
      change does not rebuild an app it lands in — with G1 this is the old rule
      that core/devtools are watched, so a mesh change reaches every member.
      Watching one that is not an input means every change there rebuilds and
      republishes the app with nothing in it, which is how one libs/devtools
      edit came to start 30 ship runs plus the stale ones (vault watched
      analytics, browser and updater and compiled none of them).
  G3  the shared manifests still carry the membership: core defines AND
      requests CONSTELLATION_DATA; devtools carries <queries> MESH_MEMBER, the
      FleetTokenProvider at ${applicationId}.fleet and the FleetMemberReceiver
      answering MESH_MEMBER. Losing one drops every member at once.
  G4  the debug server STARTS, not just the provider: devtools declares the
      DebugInitProvider whose onCreate calls AppDebugServer.start, and
      requests INTERNET — Android gates every AF_INET socket on it, loopback
      included, so without it the 127.0.0.1 bind fails EACCES (#762: watchdog,
      writer and camera declare none of their own; they answered the wake,
      kept the provider and served nothing). No app manifest may remove
      either with tools:node="remove"/"removeAll".

USAGE  cloud-android-mesh-source-guard.py [ROOT]
EXIT   0 every app is a member · 1 at least one gap · 3 nothing audited
"""
import glob, json, os, re, sys
import xml.etree.ElementTree as ET

SHARED_LIBS = "ab_cloud-libs-shared/libs"
FLEET = "aa_cloud-superapp/data/constellation-fleet.json"
PERM = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"
ACTION = "com.diegonmarcos.cloud.action.MESH_MEMBER"
A = "{http://schemas.android.com/apk/res/android}"
T = "{http://schemas.android.com/tools}"
INTERNET = "android.permission.INTERNET"
INIT = ".DebugInitProvider"
INIT_KT = "devtools/src/main/java/com/diegonmarcos/superapp/devtools/DebugInitProvider.kt"
MESH = ("core", "devtools")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.dont_write_bytecode = True
from cloud_android_lib_closure import analyse, lib_edges, shared_libs, read  # noqa: E402
SKIP = {".git", "build", ".gradle", "node_modules", ".cxx"}


def manifest_gaps(root):
    gaps = []
    core = ET.parse(os.path.join(root, SHARED_LIBS, "core/src/main/AndroidManifest.xml")).getroot()
    if not any(e.get(A + "name") == PERM for e in core.iter("permission")):
        gaps.append("libs:core no longer DEFINES CONSTELLATION_DATA")
    if not any(e.get(A + "name") == PERM for e in core.iter("uses-permission")):
        gaps.append("libs:core no longer REQUESTS CONSTELLATION_DATA")
    dev = ET.parse(os.path.join(root, SHARED_LIBS, "devtools/src/main/AndroidManifest.xml")).getroot()
    if not any(a.get(A + "name") == ACTION for q in dev.iter("queries") for a in q.iter("action")):
        gaps.append("libs:devtools lost <queries> MESH_MEMBER — no member can see its peers")
    if not any(p.get(A + "name", "").endswith(".FleetTokenProvider")
               and "${applicationId}.fleet" in p.get(A + "authorities", "").split(";")
               for p in dev.iter("provider")):
        gaps.append("libs:devtools lost the FleetTokenProvider at ${applicationId}.fleet")
    if not any(r.get(A + "name", "").endswith(".FleetMemberReceiver")
               and any(a.get(A + "name") == ACTION for a in r.iter("action"))
               for r in dev.iter("receiver")):
        gaps.append("libs:devtools lost the FleetMemberReceiver answering MESH_MEMBER")
    if not any(p.get(A + "name", "").endswith(INIT) for p in dev.iter("provider")):
        gaps.append("libs:devtools lost the DebugInitProvider — no member ever starts its debug server")
    kt = os.path.join(root, SHARED_LIBS, INIT_KT)
    body = read(kt) if os.path.isfile(kt) else ""
    on_create = re.search(r"fun onCreate\(\)[^{]*\{(.*?)\n    \}", body, re.S)
    if not on_create or not re.search(r"^[^/\n]*AppDebugServer\.start\(", on_create.group(1), re.M):
        gaps.append("DebugInitProvider.onCreate no longer calls AppDebugServer.start — the provider is there, the server never starts")
    if not any(e.get(A + "name") == INTERNET for e in dev.iter("uses-permission")):
        gaps.append("libs:devtools no longer REQUESTS INTERNET — the 127.0.0.1 bind fails EACCES in every member without its own")
    return gaps


def removals(top):
    """App manifests under [top] that strip what G4 needs back out of the merge."""
    out = []
    for d, dirs, files in os.walk(top):
        dirs[:] = [x for x in dirs if x not in SKIP]
        if "AndroidManifest.xml" not in files:
            continue
        p = os.path.join(d, "AndroidManifest.xml")
        try:
            tree = ET.parse(p).getroot()
        except ET.ParseError:
            continue
        for e in tree.iter():
            name = e.get(A + "name", "")
            if e.get(T + "node") in ("remove", "removeAll") and (name == INTERNET or name.endswith(INIT)):
                out.append(f"{os.path.relpath(p, top)} removes {name}")
    return out


def main(argv):
    root = os.path.abspath(argv[0] if argv else ".")
    edges, libs = lib_edges(root), shared_libs(root)
    workflows = {w: read(w) for w in glob.glob(os.path.join(root, ".github/workflows/ship-*.yml"))}

    gaps = [f"GAP     G3 {g}" for g in manifest_gaps(root)]
    apps = [r for r in json.load(open(os.path.join(root, FLEET)))["apps"] if r.get("kind") == "app"]
    for r in apps:
        src = r.get("repo_url", "").split("/tree/main/", 1)[-1].strip("/")
        top = os.path.join(root, src)
        if not src or not os.path.isdir(top):
            gaps.append(f"GAP     {r['id']:16} source dir '{src}' does not exist — membership is unreadable")
            continue
        seed, cl, inputs = analyse(root, src, edges, libs)
        miss = [m for m in MESH if m not in cl]
        if miss:
            gaps.append(f"GAP     {r['id']:16} G1 dependency closure lacks libs:{', libs:'.join(miss)} "
                        f"(direct: {', '.join(sorted(seed)) or 'none'})")
        own = [w for w, t in workflows.items() if f'"{src}/**"' in t]
        if len(own) != 1:
            gaps.append(f"GAP     {r['id']:16} G2 {len(own)} ship workflows watch \"{src}/**\", expected 1")
        else:
            watched = set(re.findall(r'"' + re.escape(SHARED_LIBS) + r'/([\w-]+)/\*\*"', workflows[own[0]]))
            unwatched = sorted(set(inputs) - watched)
            if unwatched:
                gaps.append(f"GAP     {r['id']:16} G2 {os.path.basename(own[0])} does not watch "
                            + ", ".join(f"{SHARED_LIBS}/{m}/**" for m in unwatched))
            for m in sorted(watched - set(inputs)):
                gaps.append(f"GAP     {r['id']:16} G2 {os.path.basename(own[0])} watches {SHARED_LIBS}/{m}/** "
                            f"which {src} does not compile — every change there rebuilds and republishes it for nothing")
        for why in removals(top):
            gaps.append(f"GAP     {r['id']:16} G4 {why} — the provider merges, the debug server cannot start")
        if not any(r["id"] in g for g in gaps):
            print(f"MEMBER  {r['id']:16} {src}")
    for g in gaps:
        print(g)
    if not apps:
        print("nothing audited — the fleet roster has no `app` rows")
        return 3
    print(f"── {len(apps)} app(s), {len(gaps)} gap(s) ──")
    return 1 if gaps else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
