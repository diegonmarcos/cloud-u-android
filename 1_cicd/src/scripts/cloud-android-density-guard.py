#!/usr/bin/env python3
# cloud-android-density-guard: the fleet's Compose screens stay as dense as the View screens they replaced, and
# window insets are owned once. WHY: after the Compose conversions many apps got huge padding. Root cause: a bare
# MaterialTheme gives every Text a fixed 24.sp line (so 9sp text is 24sp tall) and every Material control a 48dp
# floor; libs:ui-kit's CloudKitTheme / KitDenseMaterial now remove both. This guard fails when
#   - the owner (ui-kit CloudKit.kt) stops holding the dense typography / touch floor,
#   - a Kotlin file builds a bare MaterialTheme(colorScheme = ..) without `typography =`,
#   - a Scaffold( has no contentWindowInsets (default systemBars padding stacks on the host's),
#   - a page applies safeDrawing/systemBars padding again.
# Everything it knows is in 1_cicd/src/data/density-guard.json.
# usage: cloud-android-density-guard.py [repo-root] [--policy FILE]   exit 0 clean, 1 violation, 2 cannot run
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))


def strip_comments(src):
    src = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def main(argv):
    root, policy = ".", os.path.join(HERE, "..", "data", "density-guard.json")
    args = argv[1:]
    while args:
        a = args.pop(0)
        if a == "--policy":
            policy = args.pop(0)
        else:
            root = a
    try:
        cfg = json.load(open(policy))
    except Exception as e:
        print("density-guard: cannot read policy: %s" % e); return 2
    bad = []
    owner = os.path.join(root, cfg["owner"])
    if not os.path.isfile(owner):
        bad.append("owner missing: %s (libs:ui-kit must hold the dense theme)" % cfg["owner"])
    else:
        src = strip_comments(open(owner, encoding="utf-8", errors="replace").read())
        for n in cfg["owner_needles"]:
            if n not in src:
                bad.append("owner %s lost `%s`" % (cfg["owner"], n))
    rules = {k: re.compile(v["pattern"]) for k, v in cfg["rules"].items()}
    scanned = 0
    for d, dirs, files in os.walk(root):
        dirs[:] = [x for x in dirs if x not in (".git", "build", "node_modules")]
        for f in files:
            if not f.endswith(cfg["scan_glob_ext"]):
                continue
            full = os.path.join(d, f)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            if not any(rel.startswith(r + "/") for r in cfg["scan_roots"]):
                continue
            if any(s in "/" + rel for s in cfg["skip_dirs"]):
                continue
            scanned += 1
            src = strip_comments(open(full, encoding="utf-8", errors="replace").read())
            for name, rx in rules.items():
                if rel in cfg["theme_exempt"] and name == "bare_material_theme":
                    continue
                if any(rel.startswith(p) for p in cfg["rule_allow"].get(name, [])):
                    continue
                for m in rx.finditer(src):
                    line = src.count("\n", 0, m.start()) + 1
                    bad.append("%s:%d [%s] %s" % (rel, line, name, cfg["rules"][name]["why"]))
    if scanned == 0:
        print("density-guard: scanned nothing (false green refused)"); return 2
    for b in bad:
        print("FAIL " + b)
    if bad:
        return 1
    print("density-guard: ok (%d Kotlin files)" % scanned)
    return 0


sys.exit(main(sys.argv))
