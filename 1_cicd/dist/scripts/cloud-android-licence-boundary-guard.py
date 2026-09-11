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


def prune_dirs(cfg):
    return set(cfg.get("vendoring", {}).get("prune_dirs", []))


def scan(tree, cfg):
    """Every (path, line_no, marker, line) where the artefact's own source
    names something living under a restricted directory."""
    markers = cfg["markers"]
    exts = tuple(cfg["scan_extensions"])
    skip = prune_dirs(cfg)
    hits = []
    for root_rel in cfg["scan_roots"]:
        root = os.path.join(tree, root_rel)
        if not os.path.isdir(root):
            # A scan root that is not there is never "clean" — it means the
            # tree is not the tree we think it is, and a guard that shrugs at
            # that is a guard that passes on an empty checkout.
            raise FileNotFoundError(root_rel)
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in skip]
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


def restricted_present(tree, cfg):
    """Restricted-licence directories physically present in a tree.

    This is a violation on its own terms and needs no marker to find. The EE
    licence forbids copying, and vendoring upstream source into this repository
    is a copy — so an EE directory sitting in our tree is already the thing the
    licence prohibits, whether or not anything compiles against it.
    """
    return [d for d in cfg["restricted_dirs"]
            if os.path.isdir(os.path.join(tree, d))]


def find_vendored_trees(repo, cfg):
    """Directories under `repo` that are a checkout of this upstream.

    Found by SHAPE, not by a configured path. Which directory the tree lands in
    is the owner's decision and has not been taken yet, so a guard pointed at a
    fixed location would be a guard that a different directory name walks
    straight past — and the failure it exists to catch is precisely somebody
    creating that directory and shipping it.
    """
    anchors = list(cfg["scan_roots"]) + list(cfg["restricted_dirs"])
    skip = prune_dirs(cfg)
    found = []
    for dirpath, dirnames, _ in os.walk(repo):
        dirnames[:] = [d for d in dirnames if d not in skip]
        if any(os.path.isdir(os.path.join(dirpath, a)) for a in anchors):
            found.append(dirpath)
            # One verdict per tree: descending further would re-report the same
            # checkout once per nested anchor.
            dirnames[:] = []
    return found


def report(tree, cfg, key, label):
    """Verdict for one tree. 0 clean, 1 boundary crossed, 2 cannot tell."""
    try:
        hits = scan(tree, cfg)
    except FileNotFoundError as e:
        print(f"ERROR: scan root '{e}' missing under {label} — wrong tree, or an "
              f"incomplete checkout. Refusing to report a clean result.",
              file=sys.stderr)
        return 2

    vendored = restricted_present(tree, cfg)
    known = {k: v for k, v in cfg["known_reaches"].items() if not k.startswith("_")}

    if not hits and not vendored:
        print(f"ok     {key}: {label}: no reach into "
              f"{cfg['restricted_licence']} directories "
              f"({', '.join(cfg['restricted_dirs'])})")
        return 0

    print(f"FAIL   {key}: {label}: the tree reaches into "
          f"{cfg['restricted_licence']} code, which forbids redistribution.",
          file=sys.stderr)
    print(f"       restricted directories: {', '.join(cfg['restricted_dirs'])}",
          file=sys.stderr)
    for d in vendored:
        print(f"  [vendored] {d}/ is present in this tree — {cfg['restricted_licence']}"
              f" source copied into a repository we publish", file=sys.stderr)
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


def main(argv):
    args = [a for a in argv[1:] if a != "--repo"]
    repo_mode = "--repo" in argv[1:]
    if not args:
        print("usage: cloud-android-licence-boundary-guard.py <tree> "
              "[upstream-key] [policy.json]\n"
              "       cloud-android-licence-boundary-guard.py --repo <repo-root> "
              "[upstream-key] [policy.json]\n"
              "  upstream-key omitted = check EVERY upstream in the policy",
              file=sys.stderr)
        return 2
    target = args[0]
    key = args[1] if len(args) > 1 else None
    policy_path = args[2] if len(args) > 2 else DEFAULT_POLICY

    with open(policy_path, encoding="utf-8") as fh:
        policy = json.load(fh)

    # NO DEFAULT UPSTREAM. This read `key = args[1] if len(args) > 1 else "affine"`,
    # and licence-guard.yml calls it with no key at all — so the gate checked AFFiNE
    # and nothing else, and the policy file's own _doc promised "add an upstream = one
    # entry here, no script change". It was not true: a second entry would have sat in
    # the data being checked by nobody, reporting green, which is the failure mode this
    # whole guard exists to prevent. With no key, check EVERY upstream and return the
    # worst verdict.
    keys = [key] if key else sorted(policy["upstreams"])
    if not keys:
        print(f"ERROR: {policy_path} declares no upstreams", file=sys.stderr)
        return 2
    for k in keys:
        if k not in policy["upstreams"]:
            print(f"ERROR: no upstream '{k}' in {policy_path}", file=sys.stderr)
            return 2

    if not repo_mode:
        worst = 0
        for k in keys:
            worst = max(worst, report(target, policy["upstreams"][k], k, target))
        return worst

    # ── repository mode ────────────────────────────────────────────────
    # The guard runs here on every push, before the app it protects exists.
    # That is deliberate: the failure it must catch is somebody CREATING the
    # vendored tree and shipping it, and a gate that is only wired when that
    # directory appears is a gate that depends on the person creating it
    # remembering to wire it. That is what left this guard inert for a week.
    if not os.path.isdir(target):
        print(f"ERROR: '{target}' is not a directory", file=sys.stderr)
        return 2
    worst = 0
    for k in keys:
        cfg = policy["upstreams"][k]
        trees = find_vendored_trees(target, cfg)
        if not trees:
            # Truthfully clean, and said in words that cannot be mistaken for
            # "scanned a tree and found it clean" — the two have to look different
            # in a log or the distinction stops being made.
            print(f"ok     {k}: no {cfg['restricted_licence']} exposure: upstream "
                  f"{cfg['repo']} is not vendored anywhere in this repository, so "
                  f"nothing it contains is compiled into any artefact we sign.")
            continue
        for tree in trees:
            worst = max(worst, report(tree, cfg, k, os.path.relpath(tree, target)))
    return worst


if __name__ == "__main__":
    sys.exit(main(sys.argv))
