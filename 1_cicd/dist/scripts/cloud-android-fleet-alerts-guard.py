# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-fleet-alerts-guard.py ───
#!/usr/bin/env python3
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-fleet-alerts-guard — a fleet alert goes through    ║
# ║ FleetAlerts, never a notify() of its own                         ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY (#777). The fleet's alerts were spread over a dozen channels in a dozen
# apps — Store "updates installed", install results, backup failures, the
# camera's save error — while the SuperApp's "Alerts" badge showed sample
# data. #777 made ONE alerts channel: libs:core FleetAlerts raises into the
# SuperApp's Alerts group. Nothing stops the next app from opening a channel
# of its own again, so this guard does: every direct notify() in an app or lib
# tree must be CLASSIFIED in 1_cicd/src/data/fleet-alerts-guard.json as
# something that is not an alert (a badge, a foreground service, media, the
# app's own messages, ...). There is no `alert` kind.
#
# It fails on:
#   F1 a file that calls notify() and is not listed;
#   F2 a listed file whose notify() count changed (a new post slipped into an
#      exempt file — re-classify it, or route it through FleetAlerts);
#   F3 a listed file that no longer exists or no longer posts (a stale
#      exemption reads as policy and hides the next real one);
#   F4 a kind outside the vocabulary;
#   F5 nothing scanned at all (an empty checkout is not a clean fleet).
#
# Everything it knows is in the policy JSON; no app, path or kind is named
# here. No ripgrep: it is not on the runner.
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_POLICY = os.path.join(HERE, "..", "data", "fleet-alerts-guard.json")


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def scan(repo, cfg):
    """{relpath: notify-call count} for every source file that posts."""
    call = re.compile(cfg["notify_call"])
    prune = set(cfg["prune_dirs"])
    exts = tuple(cfg["extensions"])
    found, files = {}, 0
    for top in sorted(os.listdir(repo)):
        if not top.startswith(tuple(cfg["app_prefixes"])) or not os.path.isdir(os.path.join(repo, top)):
            continue
        for root, dirs, names in os.walk(os.path.join(repo, top)):
            dirs[:] = sorted(d for d in dirs if d not in prune)
            for n in names:
                if not n.endswith(exts):
                    continue
                files += 1
                p = os.path.join(root, n)
                with open(p, encoding="utf-8", errors="replace") as f:
                    c = len(call.findall(strip_comments(f.read())))
                if c:
                    found[os.path.relpath(p, repo).replace(os.sep, "/")] = c
    return found, files


def check(repo, cfg):
    bad = []
    found, files = scan(repo, cfg)
    if files == 0:
        return ["F5 no .kt/.java source found under any app prefix — nothing was checked, which is not a pass"]
    listed = {}
    for e in cfg.get("posters", []):
        listed[e["path"]] = e
        if e.get("kind") not in cfg["kinds"]:
            bad.append("F4 %s: kind %r is not one of %s" % (e["path"], e.get("kind"), sorted(cfg["kinds"])))
        if not e.get("why"):
            bad.append("F4 %s: an exemption with no `why`" % e["path"])
    for path, n in sorted(found.items()):
        e = listed.get(path)
        if e is None:
            bad.append("F1 %s posts %d notification(s) directly. If it is an alert, raise it with "
                       "FleetAlerts.raise (libs:core) so it lands in the SuperApp's Alerts group; if it is "
                       "not, classify it in fleet-alerts-guard.json with its kind and why." % (path, n))
        elif e.get("notify_calls") != n:
            bad.append("F2 %s: %d notify() call(s), the policy says %s — a post was added or removed in an "
                       "exempt file; route a new alert through FleetAlerts, then set notify_calls to match."
                       % (path, n, e.get("notify_calls")))
    for path in sorted(set(listed) - set(found)):
        why = "no longer exists" if not os.path.isfile(os.path.join(repo, path)) else "no longer posts"
        bad.append("F3 %s %s — drop its exemption from fleet-alerts-guard.json" % (path, why))
    return bad


def main(argv):
    repo = os.path.abspath(argv[1]) if len(argv) > 1 else os.getcwd()
    policy = argv[2] if len(argv) > 2 else DEFAULT_POLICY
    with open(policy, encoding="utf-8") as f:
        cfg = json.load(f)
    bad = check(repo, cfg)
    if bad:
        print("fleet alerts guard: %d violation(s). Rule: %s" % (len(bad), cfg.get("_rule", "")))
        for b in bad:
            print("  " + b)
        return 1
    print("fleet alerts guard: OK — every direct notify() in the fleet is classified (%d file(s))"
          % len(cfg.get("posters", [])))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
