#!/usr/bin/env python3
"""A debug-server route may not acknowledge work it did not start.

The failure this exists to catch is not a crash and not a wrong answer. It is a
200 OK carrying a sentence about something that never happened, which is worse
than either: a crash gets investigated and a wrong answer gets argued with,
while "update queued" gets believed and closes the ticket.

Reads 1_cicd/src/data/update-ack-guard.json, finds every Kotlin implementation
of each listed route by its `when` branch key, isolates that branch's body, and
checks it against the rules the manifest states. Exits non-zero with one line
per violation naming the file, the line and the rule.

Run:  python3 1_cicd/src/scripts/cloud-android-update-ack-guard.py [--root DIR]
"""

import argparse
import json
import os
import sys

MANIFEST = "1_cicd/src/data/update-ack-guard.json"


def kotlin_files(root, exclude_prefixes):
    """Every .kt file under root, minus the excluded path prefixes.

    Walked rather than listed. The defect this guard covers existed in two apps
    at once, and the brief that found it warned that a third copy in a second
    repository is a recurring shape on this fleet; a guard that reads a list of
    files can only ever be as current as the last person to remember it.
    """
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d != ".git"]
        for name in filenames:
            if not name.endswith(".kt"):
                continue
            rel = os.path.relpath(os.path.join(dirpath, name), root)
            if any(rel.startswith(p) for p in exclude_prefixes):
                continue
            yield rel


def strip_comments(lines):
    """(lineno, code) per line: comments removed, string literals preserved.

    THE FIRST DRAFT OF THIS GUARD FAILED ON THE FIX IT WAS WRITTEN TO PROTECT.
    It matched raw file text, and the corrected handler carries a comment that
    quotes both the call and the reply literal it replaced — deliberately,
    because the next person to read that branch needs to know why it does not
    do the obvious thing. A rule that cannot be explained in place without
    tripping over the explanation is a rule whose explanation gets deleted.

    String contents are KEPT, because forbidden_reply_literals is a rule about
    what the handler says on the wire and deleting the strings would delete the
    evidence. Escapes and raw triple-quoted strings are tracked so a `//` or a
    `/*` inside a literal cannot silently truncate the rest of a line — that
    direction of error is the one that hides violations.
    """
    out = []
    in_block = False
    for lineno, line in lines:
        res = []
        in_str = None  # None | '"' | '\"\"\"'
        i, n = 0, len(line)
        while i < n:
            if in_block:
                if line.startswith("*/", i):
                    in_block = False
                    i += 2
                else:
                    i += 1
                continue
            if in_str == '"""':
                if line.startswith('"""', i):
                    res.append('"""')
                    i += 3
                    in_str = None
                else:
                    res.append(line[i])
                    i += 1
                continue
            if in_str == '"':
                if line[i] == "\\" and i + 1 < n:
                    res.append(line[i:i + 2])
                    i += 2
                elif line[i] == '"':
                    res.append('"')
                    i += 1
                    in_str = None
                else:
                    res.append(line[i])
                    i += 1
                continue
            if line.startswith("//", i):
                break
            if line.startswith("/*", i):
                in_block = True
                i += 2
                continue
            if line.startswith('"""', i):
                in_str = '"""'
                res.append('"""')
                i += 3
                continue
            if line[i] == '"':
                in_str = '"'
                res.append('"')
                i += 1
                continue
            res.append(line[i])
            i += 1
        # A plain "..." cannot span lines in Kotlin; only """...""" can, and
        # that state is carried by the caller's loop through `in_str` being
        # re-initialised per line. Block comments genuinely do span, so only
        # `in_block` survives the line boundary.
        out.append((lineno, "".join(res)))
    return out


def branch_body(code_lines, start):
    """The `when` branch that opens at index `start`, as (lineno, code) pairs.

    Brace counting on comment-stripped code, deliberately, rather than an
    indentation heuristic: the two handlers in scope are indented differently
    from each other already, and a rule a reformat can switch off is not a rule.
    """
    depth = 0
    opened = False
    out = []
    for i in range(start, len(code_lines)):
        lineno, code = code_lines[i]
        out.append((lineno, code))
        depth += code.count("{") - code.count("}")
        if code.count("{"):
            opened = True
        if opened and depth <= 0:
            break
    return out


def check_route(root, spec, exclude_prefixes):
    key = '"%s" ->' % spec["route"]
    label = spec.get("label", spec["route"])
    problems = []
    found = 0

    for rel in sorted(kotlin_files(root, exclude_prefixes)):
        with open(os.path.join(root, rel), encoding="utf-8", errors="replace") as fh:
            raw = list(enumerate(fh.read().splitlines(), start=1))
        code_lines = strip_comments(raw)
        for idx, (lineno, code) in enumerate(code_lines):
            if key not in code:
                continue
            found += 1
            body = branch_body(code_lines, idx)
            text = "\n".join(t for _, t in body)
            here = "%s:%d" % (rel, lineno)

            for bad in spec.get("forbidden_calls", []):
                for bl, btext in body:
                    if bad in btext:
                        problems.append(
                            "%s:%d  %s routes through %s — that is a WeakReference to a "
                            "foreground Activity, so this request is dropped whenever the "
                            "screen is off, which is when fleet updates happen"
                            % (rel, bl, label, bad)
                        )

            for bad in spec.get("forbidden_reply_literals", []):
                for bl, btext in body:
                    if bad in btext:
                        problems.append(
                            '%s:%d  %s replies the literal "%s" — an outcome it has not '
                            "observed. Report what happened, not what was hoped for"
                            % (rel, bl, label, bad)
                        )

            for need in spec.get("required_calls", []):
                if need not in text:
                    problems.append(
                        "%s  %s never calls %s — the handler must actually start the "
                        "work, and must surface it when it cannot"
                        % (here, label, need)
                    )

    minimum = spec.get("min_implementations", 1)
    if found < minimum:
        problems.append(
            "found %d implementation(s) of %s, expected at least %d — this guard is "
            "checking nothing. Either the branch key was renamed (update the manifest) "
            "or the route was deleted (say so there too). A guard that matches nothing "
            "prints the same green as a guard over correct code."
            % (found, label, minimum)
        )
    return found, problems


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=None, help="repo root (default: infer from this file)")
    args = ap.parse_args()

    root = args.root or os.path.abspath(
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..")
    )
    with open(os.path.join(root, MANIFEST), encoding="utf-8") as fh:
        manifest = json.load(fh)
    exclude = tuple(manifest.get("exclude_path_prefixes", []))

    failures = 0
    for spec in manifest["routes"]:
        found, problems = check_route(root, spec, exclude)
        label = spec.get("label", spec["route"])
        if problems:
            for p in problems:
                print("FAIL   %s" % p)
            failures += len(problems)
        else:
            print("ok     %s — %d honest implementation(s)" % (label, found))

    if failures:
        print("\n%d violation(s). A route that reports success must report the success "
              "of something it observed." % failures)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
