#!/usr/bin/env python3
"""
cloud-android-upstream-name-guard — #1091: Cloud Notes is our own app, so the
name of the project it was cloned from must not appear anywhere in this
repository except where a licence or an external identifier requires it.

  cloud-android-upstream-name-guard.py [ROOT] [--allowlist FILE] [--list]

Scans every tracked file's PATH and every tracked text file's CONTENT for the
name, case-insensitively, and fails on every hit that no allowlist entry
covers. Binary files are not searched (git grep -I). The allowlist is
1_cicd/src/data/upstream-name-allowlist.json: each entry names the files it
covers (globs; ** crosses directories), the category, the reason, and
optionally regexes. With regexes the entry covers only the text they match, so
a line that carries an allowed token AND a new mention still fails. An entry
that covers nothing fails too (stale entries rot into blanket exemptions).

The word is assembled below instead of written out, so this script is not its
own first violation; the allowlist file is the one place that has to spell it
(its patterns are the exemptions) and is excluded from the scan by name.

Exit: 0 clean, 1 violations or stale entries, 2 usage or configuration error.
"""
import fnmatch
import json
import os
import re
import subprocess
import sys

WORD = "aff" + "ine"
DEFAULT_ALLOWLIST = "1_cicd/src/data/upstream-name-allowlist.json"
SELF_EXCLUDED = {DEFAULT_ALLOWLIST}
SHOW = 40


def glob_to_re(g):
    out = ""
    i = 0
    while i < len(g):
        c = g[i]
        if g.startswith("**/", i):
            out += "(?:.*/)?"
            i += 3
            continue
        if g.startswith("**", i):
            out += ".*"
            i += 2
            continue
        out += "[^/]*" if c == "*" else ("[^/]" if c == "?" else re.escape(c))
        i += 1
    return re.compile("^" + out + "$")


def load(root, path):
    try:
        d = json.load(open(os.path.join(root, path), encoding="utf-8"))
    except (OSError, ValueError) as e:
        print("error: cannot read allowlist %s: %s" % (path, e), file=sys.stderr)
        sys.exit(2)
    entries = []
    for e in d.get("entries", []):
        for k in ("id", "category", "reason", "paths"):
            if not e.get(k):
                print("error: allowlist entry %r lacks %r" % (e.get("id"), k), file=sys.stderr)
                sys.exit(2)
        e["_globs"] = [glob_to_re(p) for p in e["paths"]]
        try:
            e["_res"] = [re.compile(p) for p in e.get("patterns", [])]
        except re.error as err:
            print("error: bad pattern in %s: %s" % (e["id"], err), file=sys.stderr)
            sys.exit(2)
        e["_used"] = 0
        entries.append(e)
    return entries


def git(root, *args):
    return subprocess.run(["git", "-C", root] + list(args), capture_output=True).stdout


def main(argv):
    root = "."
    allow = DEFAULT_ALLOWLIST
    listing = False
    args = argv[1:]
    while args:
        a = args.pop(0)
        if a == "--allowlist":
            allow = args.pop(0)
        elif a == "--list":
            listing = True
        elif a.startswith("-"):
            print(__doc__)
            return 2
        else:
            root = a
    entries = load(root, allow)
    word = re.compile(WORD, re.I)

    def covering(path):
        return [e for e in entries if any(g.match(path) for g in e["_globs"])]

    violations = []

    # 1. path names
    paths = [p for p in git(root, "ls-files", "-z").decode("utf-8", "surrogateescape").split("\0") if p]
    for p in paths:
        if p in SELF_EXCLUDED or not word.search(p):
            continue
        ok = False
        for e in covering(p):
            if e.get("scope") == "content":
                continue
            if not e["_res"]:
                e["_used"] += 1
                ok = True
                break
            rest = p
            for r in e["_res"]:
                rest = r.sub("", rest)
            if not word.search(rest):
                e["_used"] += 1
                ok = True
                break
        if not ok:
            violations.append((p, 0, "file name"))

    # 2. content (git grep -I skips binaries)
    hits = []
    out2 = git(root, "grep", "-I", "-i", "-n", "--no-color", "-e", WORD, "--", ".")
    for line in out2.decode("utf-8", "replace").split("\n"):
        if not line:
            continue
        m = re.match(r"^(.*?):(\d+):(.*)$", line)
        if m:
            hits.append((m.group(1), int(m.group(2)), m.group(3)))
    for path, lineno, text in hits:
        if path in SELF_EXCLUDED:
            continue
        cov = [e for e in covering(path) if e.get("scope") != "path"]
        ok = False
        for e in cov:
            if not e["_res"]:
                e["_used"] += 1
                ok = True
                break
            rest = text
            for r in e["_res"]:
                rest = r.sub("", rest)
            if not word.search(rest):
                e["_used"] += 1
                ok = True
                break
        if not ok:
            violations.append((path, lineno, text.strip()[:160]))

    stale = [e for e in entries if e["_used"] == 0]

    if listing:
        for e in entries:
            print("%-28s %-14s %6d  %s" % (e["id"], e["category"], e["_used"], e["reason"][:90]))
    if violations:
        print("FAIL: the upstream project's name appears %d time(s) outside the allowlist" % len(violations))
        print("      Cloud Notes is our own app: use the Cloud Notes naming (cloud-notes, @cloud-notes/*,")
        print("      CloudNotes, cloud_notes_*). Only a licence/attribution notice or an external identifier")
        print("      may keep the name, and then it needs an entry in %s with its reason." % allow)
        seen = {}
        for p, ln, t in violations:
            seen.setdefault(p, []).append((ln, t))
        shown = 0
        for p, items in sorted(seen.items()):
            for ln, t in items[:3]:
                if shown >= SHOW:
                    break
                print("  %s:%s: %s" % (p, ln or "-", t))
                shown += 1
        print("  (%d file(s) in total)" % len(seen))
    if stale:
        print("FAIL: allowlist entries that cover nothing (delete them): " + ", ".join(e["id"] for e in stale))
    if violations or stale:
        return 1
    print("ok   no mention of the upstream project's name outside %d allowlisted licence/attribution/external entries" % len(entries))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
