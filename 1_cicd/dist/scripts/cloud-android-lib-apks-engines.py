# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-lib-apks-engines.py ───
#!/usr/bin/env python3
"""cloud-android-lib-apks-engines -- #870: only RUNTIME ENGINES ship a Cloud-Lib-*.apk.

An engine is a library module whose manifest declares the engine CONTRACT
meta-data (the discovery lib-apks/test/test-engine-services.sh uses). Every other
shared lib is compiled in (`implementation project(':libs:x')`) and must not be
built, published or shelved as a lib APK.

  engines ROOT   print the engine modules, one per line
  sync ROOT      rewrite the managed entries of ab_cloud-libs-shared/lib-apks/
                 build.json::lib_apks.exclude so that exclude = hand-kept entries
                 + every discovered NON-engine module (value starts with MANAGED)
  check ROOT     exit 1 when sync would change build.json
Called by cloud-android-ship-repo-workflow-engine.sh (sync) and the isolation guard.
"""
import json, os, re, sys

LIBS = "ab_cloud-libs-shared/libs"
BJ = "ab_cloud-libs-shared/lib-apks/build.json"
KEY = "com.diegonmarcos.cloud.engine.CONTRACT"
MANAGED = "#870 static lib: compiled in via project(':libs:x'), not a runtime engine (no ENGINE service CONTRACT in its manifests) -- ships no lib APK."


def modules(root):
    d = os.path.join(root, LIBS)
    return sorted(m for m in os.listdir(d) if os.path.isfile(os.path.join(d, m, "build.gradle")))


def engines(root):
    out = []
    for m in modules(root):
        src = os.path.join(root, LIBS, m, "src")
        for dp, _dn, fn in os.walk(src):
            if "AndroidManifest.xml" in fn and KEY in open(os.path.join(dp, "AndroidManifest.xml"), errors="replace").read():
                out.append(m)
                break
    return out


def wanted(root):
    cfg = json.load(open(os.path.join(root, BJ)))
    ex = cfg["lib_apks"].get("exclude", {})
    keep = {k: v for k, v in ex.items() if not v.startswith(MANAGED[:12])}
    eng = set(engines(root))
    new = dict(keep)
    for m in modules(root):
        if m not in eng and m not in new:
            new[m] = MANAGED
    for m in eng:
        if m in keep:
            raise SystemExit(f"{m} is an engine but is hand-excluded in {BJ}")
    return cfg, new


def main(argv):
    if len(argv) < 2:
        return 2
    cmd, root = argv[0], argv[1]
    if cmd == "engines":
        print("\n".join(engines(root)))
        return 0
    cfg, new = wanted(root)
    if new == cfg["lib_apks"].get("exclude", {}):
        return 0
    if cmd == "check":
        print("lib_apks.exclude is out of date with the engine discovery -- run build.sh workflow", file=sys.stderr)
        return 1
    raw = open(os.path.join(root, BJ)).read()
    m = re.search(r'(\n(\s*)"exclude": \{\n)(.*?)(\n\s*\},?\n)', raw, re.S)
    ind = m.group(2) + "  "
    kept = [l for l in m.group(3).split("\n") if MANAGED[:12] not in l]
    kept = [l.rstrip(",") for l in kept if l.strip()]
    ex = cfg["lib_apks"].get("exclude", {})
    lines = kept + [ind + json.dumps(k) + ": " + json.dumps(v) for k, v in sorted(new.items()) if v.startswith(MANAGED[:12])]
    body = ",\n".join(lines)
    raw = raw[:m.start(3)] + body + raw[m.end(3):]
    open(os.path.join(root, BJ), "w").write(raw)
    json.loads(raw)
    print("  synced lib_apks.exclude: %d engines ship, %d static libs excluded" % (len(engines(root)), len([1 for v in new.values() if v.startswith(MANAGED[:12])])))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
