#!/usr/bin/env python3
"""Inject (or check) the gradle-cache-hygiene step after every setup-gradle step.

  inject ROOT   rewrite 1_cicd/src/cicd/*.yml so every gradle/actions/setup-gradle
                step is followed by the managed block below (idempotent)
  check  ROOT   fail, naming the file, when any workflow under 1_cicd/src/cicd,
                1_cicd/dist/cicd or .github/workflows has a setup-gradle step
                without the block right after it

Why: see 1_cicd/src/actions/gradle-cache-hygiene/action.yml. One cached failed
lookup (Maven Central 429) broke ship-cloud-calc and then ship-cloud-store x86_64
on every run. Run by cloud-android-ship-repo-workflow-engine.sh, so a new ship
workflow gets the step on generation, and `check` runs in generated-up-to-date.
"""
import glob, os, re, sys

BEG = "      # ── MANAGED-GRADLE-CACHE-HYGIENE: injected by cloud-android-gradle-cache-hygiene.py ──"
END = "      # ── end MANAGED-GRADLE-CACHE-HYGIENE ──"
USES = re.compile(r"^\s+(- )?uses: gradle/actions/setup-gradle@")
STEP = re.compile(r"^(\s*)- ")


def strip_managed(lines):
    out, i = [], 0
    while i < len(lines):
        if lines[i] == BEG:
            while i < len(lines) and lines[i] != END:
                i += 1
            i += 1
            continue
        out.append(lines[i]); i += 1
    return out


def step_bounds(lines, at):
    """(start, end, dash_indent) of the step holding line `at`; end is exclusive,
    trailing blank/comment lines excluded."""
    s = at
    while s >= 0 and not STEP.match(lines[s]):
        s -= 1
    if s < 0:
        raise ValueError("setup-gradle at line %d is not inside a step" % (at + 1))
    ind = len(STEP.match(lines[s]).group(1))
    e = s + 1
    while e < len(lines):
        l = lines[e]
        if l.strip() and not l.lstrip().startswith("#") and len(l) - len(l.lstrip()) <= ind:
            break
        e += 1
    while e > s + 1 and (not lines[e - 1].strip() or lines[e - 1].lstrip().startswith("#")):
        e -= 1
    return s, e, ind


def block(lines, s, e, ind):
    if ind != 6:
        raise ValueError("setup-gradle step at line %d is not at the usual 6-space step indent" % (s + 1))
    cond = None
    for l in lines[s:e]:
        m = re.match(r"^\s+(- )?if: (.*)$", l)
        if m and len(l) - len(l.lstrip()) in (ind, ind + 2):
            cond = m.group(2)
    out = [BEG,
           "      # Edit 1_cicd/src/actions/gradle-cache-hygiene, never this block.",
           "      - name: Gradle cache hygiene (retry failed resolution, never cache a failed lookup)"]
    if cond:
        out.append("        if: " + cond)
    out += ["        uses: ./.github/actions/gradle-cache-hygiene", END]
    return out


def inject(root):
    bad = []
    for wf in sorted(glob.glob(os.path.join(root, "1_cicd/src/cicd/*.yml"))):
        text = open(wf).read()
        lines = strip_managed(text.split("\n"))
        try:
            hits = [i for i, l in enumerate(lines) if USES.match(l)]
            for at in reversed(hits):
                s, e, ind = step_bounds(lines, at)
                lines[e:e] = block(lines, s, e, ind)
        except ValueError as ex:
            bad.append("%s: %s" % (os.path.basename(wf), ex))
            continue
        new = "\n".join(lines)
        if new != text:
            open(wf, "w").write(new)
            print("  gradle-cache-hygiene %s" % os.path.basename(wf))
    return bad


def check(root):
    bad = []
    for pat in ("1_cicd/src/cicd/*.yml", "1_cicd/dist/cicd/*.yml", ".github/workflows/*.yml"):
        for wf in sorted(glob.glob(os.path.join(root, pat))):
            lines = open(wf).read().split("\n")
            for at, l in enumerate(lines):
                if not USES.match(l):
                    continue
                try:
                    s, e, ind = step_bounds(lines, at)
                except ValueError as ex:
                    bad.append("%s: %s" % (os.path.relpath(wf, root), ex)); continue
                if e >= len(lines) or lines[e] != BEG:
                    bad.append("%s:%d: setup-gradle step is not followed by the gradle-cache-hygiene "
                               "block -- run ./build.sh workflow (never add it by hand)"
                               % (os.path.relpath(wf, root), s + 1))
    if not os.path.isfile(os.path.join(root, ".github/actions/gradle-cache-hygiene/action.yml")):
        bad.append(".github/actions/gradle-cache-hygiene/action.yml is missing -- run ./build.sh workflow")
    return bad


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] not in ("inject", "check"):
        sys.exit("usage: %s inject|check ROOT" % sys.argv[0])
    bad = (inject if sys.argv[1] == "inject" else check)(sys.argv[2])
    for b in bad:
        print("::error::" + b, file=sys.stderr)
    sys.exit(1 if bad else 0)
