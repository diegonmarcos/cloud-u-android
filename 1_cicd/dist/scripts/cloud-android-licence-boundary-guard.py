# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-licence-boundary-guard.py ───
#!/usr/bin/env python3
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-licence-boundary-guard — refuse to ship an APK     ║
# ║ built from a tree that reaches into a restricted-licence dir     ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. AFFiNE's root LICENSE makes the repository MIT *except* two
# directories, which are under a source-available Enterprise Edition licence
# that forbids copying and distributing. Both of those are exactly what this
# fleet does with an upstream: it vendors the source into cloud-u-android, and
# it publishes a signed APK.
#
# The 2026-09-09 scoping found ONE reach into those directories and concluded
# that de-clouding the app made the tree "unambiguously MIT". There were two.
# The one it missed goes through Cargo: packages/common/native has no
# package.json, so it does not appear in any yarn-workspace dependency walk,
# and the crate is renamed to `affine_common` on the way in, so grepping for
# the directory path finds nothing either. EE-licensed Rust ends up statically
# linked into libaffine_mobile_native.so and shipped.
#
# A licence boundary that lives in a prose spec is checked once, by whoever
# wrote it. This checks it on every build, across all three of the app's build
# systems, from data — 1_cicd/src/data/licence-boundaries.json. No path, no
# upstream and no crate name appears below.
#
# No ripgrep: it is not on the CI runner, and testers here have previously
# passed on its absence rather than on their assertions.

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_POLICY = os.path.join(HERE, "..", "data", "licence-boundaries.json")


def scan(tree, cfg):
    """Every (path, line_no, marker, line) where the artefact's own source
    names something living under a restricted directory."""
    markers = cfg["markers"]
    exts = tuple(cfg["scan_extensions"])
    hits = []
    for root_rel in cfg["scan_roots"]:
        root = os.path.join(tree, root_rel)
        if not os.path.isdir(root):
            # A scan root that is not there is never "clean" — it means the
            # tree is not the tree we think it is, and a guard that shrugs at
            # that is a guard that passes on an empty checkout.
            raise FileNotFoundError(root_rel)
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames
                           if d not in ("node_modules", ".git", "build", "target")]
            for fn in filenames:
                if not fn.endswith(exts):
                    continue
                p = os.path.join(dirpath, fn)
                try:
                    with open(p, encoding="utf-8", errors="replace") as fh:
                        lines = fh.readlines()
                except OSError:
                    continue
                for i, line in enumerate(lines, 1):
                    for m in markers:
                        if m in line:
                            hits.append((os.path.relpath(p, tree), i, m,
                                         line.strip()))
    return hits


def main(argv):
    if len(argv) < 2:
        print("usage: cloud-android-licence-boundary-guard.py <tree> "
              "[upstream-key] [policy.json]", file=sys.stderr)
        return 2
    tree = argv[1]
    key = argv[2] if len(argv) > 2 else "affine"
    policy_path = argv[3] if len(argv) > 3 else DEFAULT_POLICY

    with open(policy_path, encoding="utf-8") as fh:
        policy = json.load(fh)
    if key not in policy["upstreams"]:
        print(f"ERROR: no upstream '{key}' in {policy_path}", file=sys.stderr)
        return 2
    cfg = policy["upstreams"][key]

    try:
        hits = scan(tree, cfg)
    except FileNotFoundError as e:
        print(f"ERROR: scan root '{e}' missing under {tree} — wrong tree, or an "
              f"incomplete checkout. Refusing to report a clean result.",
              file=sys.stderr)
        return 2

    known = {k: v for k, v in cfg["known_reaches"].items() if not k.startswith("_")}
    if not hits:
        print(f"ok     {key}: no reach into "
              f"{cfg['restricted_licence']} directories "
              f"({', '.join(cfg['restricted_dirs'])})")
        return 0

    print(f"FAIL   {key}: the tree reaches into "
          f"{cfg['restricted_licence']} code, which forbids redistribution.",
          file=sys.stderr)
    print(f"       restricted directories: {', '.join(cfg['restricted_dirs'])}",
          file=sys.stderr)
    for path, ln, marker, text in hits:
        note = known.get(path)
        tag = "known" if note else "NEW"
        print(f"  [{tag}] {path}:{ln}: {text}", file=sys.stderr)
        if note:
            print(f"          -> {note}", file=sys.stderr)
    new = [h for h in hits if h[0] not in known]
    if new:
        print(f"       {len(new)} reach(es) NOT in the policy's known_reaches. "
              f"An upstream bump introduced them; re-scope before shipping.",
              file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
