# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-dns-resolver-guard.py ───
#!/usr/bin/env python3
"""No fleet code may resolve names anywhere but Android's resolver (#741).

The SuperApp's Configs > Mesh > DNS page is the one place the phone's DNS is
decided. An app follows it only while it asks Android; a public server written
into code, a resolv.conf naming one, a DoH client or a raw :53 socket answers
from somewhere that page never sees. This reads every tracked file under the
fleet's trees and fails on any such pattern outside the declared allowlist.

Everything it knows comes from 1_cicd/src/data/dns-resolver-guard.json: the
trees to scan, the rules, the allowed trees and the pinned exemptions. An
exemption or an allowed tree that no longer matches anything also fails, so the
allowlist cannot quietly outlive what it excused.

Usage: cloud-android-dns-resolver-guard.py <repo-root> [policy.json]
"""
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_POLICY = os.path.join(HERE, "..", "data", "dns-resolver-guard.json")


def tracked(root):
    # Untracked-but-not-ignored too: a file about to be committed is checked before it lands.
    out = subprocess.run(["git", "-C", root, "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                         capture_output=True, check=True).stdout
    return [p for p in out.decode("utf-8", "surrogateescape").split("\0") if p]


def scan(root, cfg):
    rules = {k: (re.compile(r["pattern"]), tuple(r.get("suffixes", ()))) for k, r in cfg["rules"].items()}
    # Every match of a rule contains one of its needles, so a file holding no needle at all
    # is skipped without running a single pattern: almost every file in the fleet.
    needles = [n for r in cfg["rules"].values() for n in r["needles"]]
    findings = []
    for path in tracked(root):
        if not path.startswith(tuple(cfg["scan"])):
            continue
        if any(d in "/" + path for d in cfg["skip_dirs"]) or path.endswith(tuple(cfg["skip_suffixes"])):
            continue
        full = os.path.join(root, path)
        if os.path.islink(full) or not os.path.isfile(full):
            continue
        try:
            with open(full, encoding="utf-8") as fh:
                text = fh.read()
        except (UnicodeDecodeError, OSError):
            continue
        if not any(n in text for n in needles):
            continue
        for n, line in enumerate(text.splitlines(), 1):
            for name, (rx, suffixes) in rules.items():
                if suffixes and not path.endswith(suffixes):
                    continue
                if rx.search(line) and not path.startswith(tuple(cfg["allow"])):
                    findings.append((path, n, name, line.strip()))
    return findings


def main(argv):
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    root = argv[1]
    with open(argv[2] if len(argv) > 2 else DEFAULT_POLICY, encoding="utf-8") as fh:
        cfg = json.load(fh)
    findings = scan(root, cfg)

    used = set()
    bad = []
    for path, n, rule, text in findings:
        hit = next((i for i, e in enumerate(cfg["exempt"]) if e["path"] == path and e["match"] in text), None)
        if hit is None:
            bad.append("%s:%d [%s] %s\n      %s" % (path, n, rule, cfg["rules"][rule]["why"], text[:200]))
        else:
            used.add(hit)
    stale = ["exempt entry matches nothing any more, delete it: %s :: %s" % (e["path"], e["match"])
             for i, e in enumerate(cfg["exempt"]) if i not in used]
    for prefix in cfg["allow"]:
        if not os.path.isdir(os.path.join(root, prefix)):
            stale.append("allowed tree %s does not exist" % prefix)

    if bad or stale:
        print("DNS resolver guard: %d bypass(es), %d stale allowlist entr(y/ies)" % (len(bad), len(stale)))
        for b in bad:
            print("  FAIL " + b)
        for s in stale:
            print("  FAIL " + s)
        print("Every lookup must go through Android's resolver so the SuperApp's DNS menu applies "
              "(1_cicd/src/data/dns-resolver-guard.json::_doc). A bundled binary uses libs/sysdns "
              "ResolverProxy; a terminal shell uses its SystemDnsBridge.")
        return 1
    print("DNS resolver guard: no bypass outside %s (%d pinned exemption(s))"
          % (", ".join(sorted(cfg["allow"])), len(cfg["exempt"])))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
