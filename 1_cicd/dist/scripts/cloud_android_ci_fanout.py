# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud_android_ci_fanout.py ───
#!/usr/bin/env python3
"""
cloud_android_ci_fanout — #763: how many workflow runs does a change start?

Evaluates every .github/workflows/*.yml `on: push` trigger against a change set
exactly as GitHub does for a push to main, and prints the workflows that would
run. The measure #763 is judged by: before it, one commit that touched
libs/devtools started 53 runs, 23 of them guards that run on every push
whatever it touches.

GitHub's semantics, implemented here and nowhere else:
  - no `paths` and no `paths-ignore`      → runs on every push
  - `paths`: patterns in order, the LAST one that matches a file decides
    (a `!` pattern excludes); the workflow runs when any file is included
  - `paths-ignore`: runs when any file matches none of the patterns
  - `*` never crosses `/`, `**` does, `?` is one non-`/` character
  - a `branches` list that does not name main means no run

USAGE
  cloud_android_ci_fanout.py [--wf DIR] --files F [F ...]
  cloud_android_ci_fanout.py [--wf DIR] --commit SHA      (SHA^..SHA)
  cloud_android_ci_fanout.py [--wf DIR] --range A..B
  add --names to list the workflows, --json for machine output
EXIT 0 always (it measures; the guards assert)
"""
import glob, json, os, re, subprocess, sys

import yaml


def glob_re(pattern):
    out, i = "", 0
    while i < len(pattern):
        c = pattern[i]
        if pattern.startswith("**", i):
            out += ".*"
            i += 2
            continue
        out += {"*": "[^/]*", "?": "[^/]"}.get(c, re.escape(c))
        i += 1
    return re.compile(out + r"\Z")


def triggers(path):
    """(kind, patterns) for a push to main: kind in all/paths/ignore/none."""
    y = yaml.safe_load(open(path)) or {}
    on = y.get(True, y.get("on"))
    if isinstance(on, str):
        on = {on: None}
    elif isinstance(on, list):
        on = {k: None for k in on}
    if not isinstance(on, dict) or "push" not in on:
        return "none", []
    push = on["push"] or {}
    branches = push.get("branches")
    if branches is not None and not any(glob_re(b).match("main") for b in branches):
        return "none", []
    if branches is None and push.get("tags") is not None:
        return "none", []  # a tags-only push trigger never fires for a branch push
    if "paths" in push:
        return "paths", list(push["paths"])
    if "paths-ignore" in push:
        return "ignore", list(push["paths-ignore"])
    return "all", []


def fires(kind, patterns, files):
    if kind == "none" or not files:
        return False
    if kind == "all":
        return True
    if kind == "ignore":
        rx = [glob_re(p) for p in patterns]
        return any(not any(r.match(f) for r in rx) for f in files)
    rx = [(p.startswith("!"), glob_re(p.lstrip("!"))) for p in patterns]
    for f in files:
        verdict = False
        for neg, r in rx:
            if r.match(f):
                verdict = not neg
        if verdict:
            return True
    return False


def changed(spec, root):
    out = subprocess.run(["git", "-C", root, "diff", "--name-only", spec],
                         capture_output=True, text=True, check=True).stdout
    return [l for l in out.splitlines() if l]


def main(argv):
    wf_dir, files, names, as_json, root = ".github/workflows", [], False, False, "."
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--wf":
            wf_dir = argv[i + 1]; i += 2; continue
        if a == "--names":
            names = True
        elif a == "--json":
            as_json = True
        elif a == "--commit":
            files = changed(f"{argv[i + 1]}^..{argv[i + 1]}", root); i += 2; continue
        elif a == "--range":
            files = changed(argv[i + 1], root); i += 2; continue
        elif a == "--files":
            files = argv[i + 1:]; break
        i += 1
    hit = sorted(os.path.basename(w) for w in glob.glob(os.path.join(wf_dir, "*.yml"))
                 if fires(*triggers(w), files))
    ship = [h for h in hit if h.startswith("ship")]
    if as_json:
        print(json.dumps({"files": len(files), "runs": len(hit), "ship": len(ship), "workflows": hit}))
    else:
        if names:
            print("\n".join(hit))
        print(f"{len(hit)} runs ({len(ship)} ship, {len(hit) - len(ship)} other) for {len(files)} changed file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
