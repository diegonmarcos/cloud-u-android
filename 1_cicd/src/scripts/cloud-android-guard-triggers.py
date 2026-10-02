#!/usr/bin/env python3
"""
cloud-android-guard-triggers — #763: a guard runs on the pushes that can change
its verdict, and on no others.

DECLARATION  1_cicd/src/data/guard-triggers.json (filtered / unfiltered)

  inject ROOT     Writes each `filtered` guard's paths (plus its own workflow
                  source) into 1_cicd/src/cicd/<name> under `on: push:`, between
                  MANAGED-GUARD-TRIGGERS fences, replacing whatever the fences
                  held. REFUSES (exit 1) when a push-triggered workflow source
                  with no hand-written `paths:` is named in neither list, when
                  one is named in both, or when a named workflow does not exist:
                  a new guard must say whether it is narrowed, and why.
                  Called by cloud-android-ship-repo-workflow-engine.sh.

  coverage ROOT   Runs every `run:` step of every `filtered` guard under strace
                  and fails naming each repo file a guard OPENED that its
                  declared paths do not match -- the one way a filter goes
                  wrong silently (the guard stops running on a change that
                  would have turned it red). A step that exits non-zero fails
                  too: a guard that did not finish cannot vouch for what it
                  reads. Steps that only install packages (apt-get) are skipped,
                  and so is a job that depends on an action other than checkout
                  or nix (its inputs are that action's source, declared by
                  hand) -- each such job is named in a NOTE line.
                  Blind spot: content read through git objects is not a file
                  open; see the declaration's _doc.

EXIT  0 ok · 1 a refusal or an uncovered read · 2 usage
"""
import glob, json, os, re, subprocess, sys, tempfile

DATA = "1_cicd/src/data/guard-triggers.json"
CICD = "1_cicd/src/cicd"
BEG = "    # ── MANAGED-GUARD-TRIGGERS: injected by cloud-android-guard-triggers.py from 1_cicd/src/data/guard-triggers.json ──"
END = "    # ── end MANAGED-GUARD-TRIGGERS ──"


def load(root):
    d = json.load(open(os.path.join(root, DATA)))
    strip = lambda m: {k: v for k, v in m.items() if not k.startswith("_")}
    return strip(d.get("filtered", {})), strip(d.get("unfiltered", {}))


def declared(name, spec):
    return sorted(set(spec["paths"]) | {f"{CICD}/{name}"})


def strip_managed(lines):
    out, skip = [], False
    for l in lines:
        if l == BEG:
            skip = True
        elif l == END:
            skip = False
        elif not skip:
            out.append(l)
    return out


def push_block(lines):
    """(index of `  push:` inside `on:`, has hand-written paths) or (None, False)."""
    on = next((i for i, l in enumerate(lines) if l.rstrip() == "on:"), None)
    if on is None:
        return None, False
    for i in range(on + 1, len(lines)):
        l = lines[i]
        if l and not l.startswith((" ", "#")):
            break
        if l.rstrip() == "  push:":
            j = i + 1
            while j < len(lines) and (not lines[j].strip() or lines[j].startswith("    ")):
                if lines[j].strip() in ("paths:", "paths-ignore:"):
                    return i, True
                j += 1
            return i, False
    return None, False


def inject(root):
    filtered, unfiltered = load(root)
    bad = [f"{n}: named under both `filtered` and `unfiltered`" for n in sorted(set(filtered) & set(unfiltered))]
    for n in sorted(set(filtered) | set(unfiltered)):
        if not os.path.isfile(os.path.join(root, CICD, n)):
            bad.append(f"{n}: named in {DATA} but there is no {CICD}/{n}")
    for wf in sorted(glob.glob(os.path.join(root, CICD, "*.yml"))):
        name = os.path.basename(wf)
        text = open(wf).read()
        lines = strip_managed(text.split("\n"))
        at, hand = push_block(lines)
        if at is None:
            if name in filtered:
                bad.append(f"{name}: declared `filtered` but has no `on: push:` to filter")
            continue
        if name in filtered:
            if hand:
                bad.append(f"{name}: declared `filtered` and also carries a hand-written paths: — one declaration, not two")
                continue
            block = [BEG, "    paths:"] + [f'      - "{p}"' for p in declared(name, filtered[name])] + [END]
            lines = lines[:at + 1] + block + lines[at + 1:]
        elif not hand and name not in unfiltered:
            bad.append(f"{name}: runs on every push and is not in {DATA} — add it under `filtered` "
                       f"(with the paths it reads) or `unfiltered` (with the reason)")
        new = "\n".join(lines)
        if new != text:
            open(wf, "w").write(new)
            print(f"  guard triggers: {name}")
    for b in bad:
        print("  " + b, file=sys.stderr)
    return 1 if bad else 0


# Actions whose effect the coverage job reproduces itself (it checks out, and
# installs nix), so the run steps after them can be replayed in place.
REPLAYABLE = ("actions/checkout@", "cachix/install-nix-action@")
READ = re.compile(r'^\d+\s+(?:openat\(AT_FDCWD, |open\()"([^"]+)", ([A-Z_|]+)')


def reads(log, root):
    """Repo files the traced process opened for reading."""
    out = set()
    for line in open(log, errors="replace"):
        m = READ.match(line)
        if not m or " = -1 " in line or "O_DIRECTORY" in m.group(2) or "O_WRONLY" in m.group(2):
            continue
        p = m.group(1)
        a = os.path.normpath(p if p.startswith("/") else os.path.join(root, p))
        if a.startswith(root + os.sep) and os.sep + ".git" + os.sep not in a + os.sep and os.path.isfile(a):
            out.add(os.path.relpath(a, root))
    return out


def coverage(root):
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    sys.dont_write_bytecode = True
    import yaml
    from cloud_android_ci_fanout import fires

    root = os.path.abspath(root)
    filtered, _ = load(root)
    if not filtered:
        print(f"nothing to check — {DATA} filters no guard")
        return 1
    gaps = 0
    for name, spec in sorted(filtered.items()):
        paths = declared(name, spec)
        y = yaml.safe_load(open(os.path.join(root, CICD, name)))
        steps = []
        for jid, job in y["jobs"].items():
            # A job whose run steps depend on another action's effect (a local
            # action that installs a binary) cannot be replayed step by step;
            # its inputs are the action's source, declared by hand. Said, not skipped silently.
            other = [s["uses"] for s in job.get("steps", []) if s.get("uses") and not s["uses"].startswith(REPLAYABLE)]
            if other:
                print(f"NOTE    {name:34} job {jid} not traced: it depends on {', '.join(other)}")
                continue
            steps += [s["run"] for s in job.get("steps", []) if s.get("run")]
        seen, failed = set(), []
        for n, run in enumerate(steps):
            if "apt-get" in run:
                continue
            with tempfile.NamedTemporaryFile(suffix=".strace") as log:
                p = subprocess.run(["strace", "-f", "-qq", "-e", "trace=openat,open", "-o", log.name,
                                    "bash", "-e", "-c", run], cwd=root, capture_output=True, text=True)
                if p.returncode != 0:
                    failed.append(f"step {n} exited {p.returncode}: {(p.stdout + p.stderr).strip()[-400:]}")
                seen |= reads(log.name, root)
        miss = sorted(f for f in seen if not fires("paths", paths, [f]))
        for f in failed:
            print(f"FAIL    {name:34} {f}")
        for f in miss:
            print(f"GAP     {name:34} read {f} — no declared path matches it")
        if not failed and not miss:
            print(f"COVERED {name:34} {len(seen)} file(s) read, all inside its {len(paths)} path(s)")
        gaps += len(failed) + len(miss)
    print(f"── {len(filtered)} filtered guard(s), {gaps} problem(s) ──")
    return 1 if gaps else 0


def main(argv):
    if len(argv) != 2 or argv[0] not in ("inject", "coverage"):
        print(__doc__, file=sys.stderr)
        return 2
    return (inject if argv[0] == "inject" else coverage)(argv[1])


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
