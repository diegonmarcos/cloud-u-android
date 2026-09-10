#!/usr/bin/env python3
"""Phone > Apps page guard — the two defects this page has already shipped.

WHAT IT HOLDS.  Every region named in 1_cicd/src/data/phone-apps-page-guard.json
must contain the calls listed in `must_call` and none of the calls listed in
`must_not_call`.  That is enough to express both properties the owner asked for
and neither of which is visible in a diff review:

  #260  every app row in the Quickmarks area is ONE sideways-scrollable line
  #261  Smart Folders is collapsed at birth and fetched off the main thread

WHY IT IS A SCRIPT AND NOT A grep.  The file it checks is heavily commented, and
those comments quote the exact constructs the rules forbid — the paragraph
explaining why `chunked` was wrong contains the word `chunked`.  A grep reads
that explanation as the defect and goes red on the fix.  Worse, it also reads a
COMMENTED-OUT call as a live one, so commenting out the fix would keep it green.
So comments and string literals are blanked before anything is matched, with
their length preserved so reported line numbers still point at real lines.  Both
directions of that are proven in phone-apps-page-guard.test.sh.

A REGION THE GUARD CANNOT FIND IS A FAILURE, not a clean sweep.  A renamed or
moved function must make this go red and say so; a guard that quietly stops
covering something is the exact failure it exists to catch.
"""

import json
import os
import sys

ROOT = os.environ.get("CLOUD_ANDROID_ROOT") or os.getcwd()
MANIFEST = os.path.join(ROOT, "1_cicd/src/data/phone-apps-page-guard.json")

failures = []


def blank_comments_and_strings(text):
    """Return `text` with comment and string-literal CONTENT replaced by spaces.

    Offsets and newlines are preserved, so an index into the result is an index
    into the original and line numbers survive.  One left-to-right pass, so a
    `//` inside a string is part of the string and a quote inside a comment is
    part of the comment — the two cannot capture each other.  Kotlin's nested
    block comments are counted rather than terminated at the first `*/`.
    """
    out = list(text)
    i, n = 0, len(text)

    def blank(start, end):
        for k in range(start, end):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        two = text[i:i + 2]
        if two == "//":
            end = text.find("\n", i)
            end = n if end == -1 else end
            blank(i, end)
            i = end
        elif two == "/*":
            depth, j = 1, i + 2
            while j < n and depth:
                if text[j:j + 2] == "/*":
                    depth, j = depth + 1, j + 2
                elif text[j:j + 2] == "*/":
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            blank(i, j)
            i = j
        elif text[i:i + 3] == '"""':
            end = text.find('"""', i + 3)
            end = n if end == -1 else end + 3
            blank(i, end)
            i = end
        elif text[i] in '"\'':
            quote, j = text[i], i + 1
            while j < n and text[j] != quote:
                j += 2 if text[j] == "\\" else 1
            j = min(j + 1, n)
            blank(i, j)
            i = j
        else:
            i += 1
    return "".join(out)


def body_of(code, entry):
    """Extent of the region declared by `entry`, as (start, end) into `code`.

    Walks from the end of the declaration to the brace that opens the body —
    the first `{` seen at parenthesis depth zero — so a signature reflowed
    across several lines matches the same single-line `entry` string.  Returns
    None when the declaration is not there at all.
    """
    at = code.find(entry)
    if at == -1:
        return None
    i, depth, n = at + len(entry), 0, len(code)
    # `entry` may stop mid-parameter-list (it ends at the opening parenthesis
    # for the reflowed signatures), so seed the depth from what it already ate.
    depth += entry.count("(") - entry.count(")")
    while i < n:
        char = code[i]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        elif char == "{" and depth <= 0:
            break
        i += 1
    else:
        return None
    start, braces = i, 0
    while i < n:
        if code[i] == "{":
            braces += 1
        elif code[i] == "}":
            braces -= 1
            if braces == 0:
                return (start, i + 1)
        i += 1
    return None


def line_of(code, index):
    return code.count("\n", 0, index) + 1


def main():
    if not os.path.isfile(MANIFEST):
        print("FAIL   manifest %s does not exist — nothing to guard." % MANIFEST)
        return 1

    with open(MANIFEST, encoding="utf-8") as handle:
        manifest = json.load(handle)

    regions = manifest.get("regions") or []
    if not regions:
        # A manifest with no regions would let this sweep nothing and print a
        # green tick for it — the "ran zero testers and passed" shape.
        print("FAIL   %s lists no regions — this guard would prove nothing."
              % os.path.relpath(MANIFEST, ROOT))
        return 1

    checked = 0
    for region in regions:
        label = region["label"]
        relative = region["source"]
        path = os.path.join(ROOT, relative)
        if not os.path.isfile(path):
            failures.append("[%s]: %s does not exist — nothing to guard." % (label, relative))
            continue

        with open(path, encoding="utf-8") as handle:
            raw = handle.read()
        code = blank_comments_and_strings(raw)

        extent = body_of(code, region["entry"])
        if extent is None:
            failures.append(
                "[%s]: cannot find the entry point %r in %s — this region is no longer "
                "being checked at all." % (label, region["entry"], relative))
            continue

        start, end = extent
        body = code[start:end]
        checked += 1

        for fragment in region.get("must_call", []):
            if fragment not in body:
                failures.append(
                    "[%s]: %s (line %d) no longer contains %r. This region is required to "
                    "call it — see the manifest for what breaks when it does not."
                    % (label, relative, line_of(code, start), fragment))

        for fragment in region.get("must_not_call", []):
            at = body.find(fragment)
            if at != -1:
                failures.append(
                    "[%s]: %s line %d calls %r inside this region, which is the defect this "
                    "rule exists to stop." % (label, relative, line_of(code, start + at), fragment))

    if failures:
        print("Phone > Apps page guard — %d problem(s):\n" % len(failures))
        for line in failures:
            print("  " + line)
        print("\nSee 1_cicd/src/data/phone-apps-page-guard.json for why each rule is there.")
        return 1

    print("Phone > Apps page guard: %d region(s) checked, all hold." % checked)
    return 0


if __name__ == "__main__":
    sys.exit(main())
