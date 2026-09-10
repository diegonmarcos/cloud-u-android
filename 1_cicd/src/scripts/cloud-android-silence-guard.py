#!/usr/bin/env python3
"""No user-facing action in cloud-keyboard may end without saying why.

WHY THIS FILE EXISTS. TextEnhancer.run() reached a blank-target check and, in the
default "auto" scope, returned without saying anything: no message, no toast, no
state change. The owner pressed the Enhance key and nothing whatsoever happened,
which is indistinguishable from a crash, a dead key, a missing key, an
unconfigured key and a network failure all at once. That ambiguity is what hid
the fact that every application whose editing surface an input method cannot read
-- canvas editors, web views, custom drawing surfaces -- was unsupported.

WHY IT IS NO LONGER ONLY ABOUT ENHANCE. The same defect was sitting in the
Translate bar in three more places: Insert/Replace walked away when it could not
reach the field, Copy walked away when no translation was ready, and the language
swap arrow walked away with the source set to Auto and nothing detected yet. One
bug in four costumes. The entry points are therefore DATA --
1_cicd/src/data/silence-guard.json -- and the next one costs a JSON entry rather
than a patch to a Kotlin parser. Growing that list is the intended way to use
this; editing this file is not.

The assertion with value is NOT "today's wording is present". It is the property:
every `return` inside a listed entry point hands off to one of that entry point's
reporters, and the one exit allowed to stay quiet carries a reason from an
enumerated list. A new silent `return` therefore fails this guard the day it is
written, whatever it says or does not say.

THE SENTENCE HAS TO BE ON THE EXIT ITSELF. A statement here is source lines joined
until their round brackets balance, so `?: run { toast(...); return }` is one
statement and reads as attributed, while a toast on one line and a bare `return` on
the next reads as two, the second of them silent. That is the rule, not a limitation
of the reader: an exit whose explanation sits on a neighbouring line is one edit away
from losing it, and this repository has already shipped an exit whose only
attribution was a comment.

Deliberately plain: python3 and the standard library only. It was established
that testers here have passed on ripgrep's absence rather than on their
assertions, so nothing here shells out at all.
"""

import json
import os
import re
import sys
import xml.etree.ElementTree as ElementTree

ROOT = os.environ.get("CLOUD_ANDROID_ROOT") or os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

MANIFEST = "1_cicd/src/data/silence-guard.json"

problems = []
notes = []


def fail(message):
    problems.append(message)


def scan(text):
    """Blank out comments, and report for every character whether it is inside a
    string literal. Kotlin string templates that themselves contain quotes are
    read the way a plain scanner reads them; that miscounts which spans are
    string and which are code, but it miscounts them in pairs, so brace and paren
    depth still balance and no `return` outside a comment is ever hidden."""
    out, in_string = [], []
    string, line_comment, block_comment, escaped = False, False, False, False
    i = 0
    while i < len(text):
        c, nxt = text[i], text[i + 1] if i + 1 < len(text) else ""
        if line_comment:
            out.append("\n" if c == "\n" else " ")
            in_string.append(False)
            if c == "\n":
                line_comment = False
            i += 1
            continue
        if block_comment:
            out.append("\n" if c == "\n" else " ")
            in_string.append(False)
            if c == "*" and nxt == "/":
                out.append(" ")
                in_string.append(False)
                block_comment = False
                i += 2
                continue
            i += 1
            continue
        if string:
            out.append(c)
            in_string.append(True)
            if escaped:
                escaped = False
            elif c == "\\":
                escaped = True
            elif c == '"':
                string = False
                in_string[-1] = False   # the closing quote itself is punctuation
            i += 1
            continue
        if c == "/" and nxt == "/":
            line_comment = True
            out.append("  ")
            in_string.extend([False, False])
            i += 2
            continue
        if c == "/" and nxt == "*":
            block_comment = True
            out.append("  ")
            in_string.extend([False, False])
            i += 2
            continue
        if c == '"':
            string = True
        out.append(c)
        in_string.append(False)
        i += 1
    return "".join(out), in_string


def entry_body(code, in_string, signature, source):
    """The braces of one entry point, from its signature to its matching close."""
    at = code.find(signature)
    if at < 0:
        fail("cannot find the entry point `%s` in %s — the guard is "
             "checking nothing" % (signature, source))
        return None
    start = code.find("{", at)
    depth, i = 0, start
    while i < len(code):
        if not in_string[i]:
            if code[i] == "{":
                depth += 1
            elif code[i] == "}":
                depth -= 1
                if depth == 0:
                    return start, i + 1
        i += 1
    fail("the entry point `%s` never closes its braces in %s" % (signature, source))
    return None


def statements(body, first_line):
    """One entry per logical statement: source lines joined until their round
    brackets balance, so a call wrapped over three lines is read as one call."""
    out, buffered, depth, began = [], [], 0, first_line
    for offset, line in enumerate(body.split("\n")):
        if not buffered:
            began = first_line + offset
        buffered.append(line.strip())
        depth += line.count("(") - line.count(")")
        if depth <= 0:
            out.append((began, " ".join(x for x in buffered if x)))
            buffered, depth = [], 0
    if buffered:
        out.append((began, " ".join(buffered)))
    return [(n, s) for n, s in out if s]


def arguments(call):
    """Top-level arguments of `name(...)`, given the text from `name(` onwards.
    String literals are stepped over: a message that contains a comma is one
    argument, not two, and reading it as two silently shifts every argument after
    it — which is exactly how a guard ends up checking the wrong thing."""
    def walk(text, stop_at_close):
        depth, quoted, escaped, out = 0, False, False, []
        for c in text:
            if quoted:
                out.append(c)
                if escaped:
                    escaped = False
                elif c == "\\":
                    escaped = True
                elif c == '"':
                    quoted = False
                continue
            if c == '"':
                quoted = True
                out.append(c)
                continue
            if c in "([{":
                depth += 1
            elif c in ")]}":
                if depth == 0 and stop_at_close:
                    break
                depth -= 1
            out.append((c, depth))
        return out

    inner = "".join(c if isinstance(c, str) else c[0]
                    for c in walk(call[call.index("(") + 1:], True))
    args, current = [], []
    for item in walk(inner, False):
        if isinstance(item, tuple) and item[0] == "," and item[1] == 0:
            args.append("".join(current).strip())
            current = []
            continue
        current.append(item if isinstance(item, str) else item[0])
    if "".join(current).strip():
        args.append("".join(current).strip())
    return args


def placeholders(value):
    """How many arguments getString must be handed for this string."""
    positional = [int(n) for n in re.findall(r"%(\d+)\$", value)]
    if positional:
        return max(positional)
    return len(re.findall(r"%[^%]", value))


def load_locales(spec, label):
    """resource name -> value, per locale directory named in the manifest."""
    catalogue = {}
    for locale, relative in spec.items():
        full = os.path.join(ROOT, relative)
        if not os.path.exists(full):
            fail("%s: %s is missing — the owner reads this keyboard in Spanish"
                 % (label, relative))
            catalogue[locale] = {}
            continue
        catalogue[locale] = {
            node.get("name"): "".join(node.itertext())
            for node in ElementTree.parse(full).getroot().findall("string")}
    return catalogue


def check(entry_point):
    """One manifest entry, start to finish."""
    label = entry_point["label"]
    source = entry_point["source"]
    signature = entry_point["entry"]
    reporters = entry_point["reporters"]
    quiet = entry_point.get("quiet")
    messages = entry_point.get("messages")

    path = os.path.join(ROOT, source)
    if not os.path.exists(path):
        fail("%s: %s does not exist — nothing to guard" % (label, source))
        return
    raw = open(path, encoding="utf-8").read()
    code, in_string = scan(raw)
    span = entry_body(code, in_string, signature, source)
    if not span:
        return

    start, end = span
    body = code[start:end]
    first_line = raw[:start].count("\n") + 1
    lines = statements(body, first_line)
    attributes = list(reporters) + ([quiet["reporter"]] if quiet else [])

    # 1. every return inside the entry point is attributable
    for number, statement in lines:
        if not re.search(r"\breturn\b", statement):
            continue
        if not any(a in statement for a in attributes):
            fail("%s:%d [%s]: silent exit — `return` with no %s to attribute it:\n"
                 "        %s" % (source, number, label,
                                 " or ".join(attributes), statement[:160]))

    # 2. the quiet exits carry only the reason the manifest allows
    if quiet:
        allowed = quiet["reasons_allowed"]
        index = quiet["reason_argument"]
        pattern = re.escape(quiet["reporter"].rstrip("(")) + r"\(.*"
        for number, statement in lines:
            for call in re.findall(pattern, statement):
                args = arguments(call)
                if len(args) <= index:
                    fail("%s:%d [%s]: %s needs at least %d argument(s), the last of "
                         "them the reason" % (source, number, label,
                                              quiet["reporter"], index + 1))
                    continue
                reason = args[index]
                if reason.startswith('"'):
                    reason = reason.strip('"')
                else:
                    match = re.search(r'val\s+%s\s*=\s*"([^"]*)"' % re.escape(reason), code)
                    if not match:
                        fail("%s:%d [%s]: quiet exit's reason `%s` is not a literal and "
                             "no constant of that name is declared here"
                             % (source, number, label, reason))
                        continue
                    reason = match.group(1)
                if reason not in allowed:
                    fail("%s:%d [%s]: quiet exit claims a reason that is not on the "
                         "allowed list: %r. Allowed: %s"
                         % (source, number, label, reason, sorted(allowed)))
                notes.append("%s: quiet exit allowed at line %d: %s"
                             % (label, number, reason))

    # 3/4. reporters that name a string resource: one distinct string each, present
    # in every locale the manifest lists, formatted with the arguments it needs.
    if not messages:
        notes.append("%s: %d exit(s) attributed via %s (plain literals, no resource "
                     "catalogue to check)"
                     % (label, sum(1 for _, s in lines if re.search(r"\breturn\b", s)),
                        "/".join(reporters)))
        return

    reporter = messages["reporter"]
    index = messages["resource_argument"]
    # The open bracket is part of the match, which is what keeps `ended(` from also
    # finding `endedQuietly(` — one reporter's name being a prefix of the other's is
    # the normal case here, and a search that confused them would check the wrong
    # call and report a clean sweep of it.
    pattern = r"\b%s\(.*" % re.escape(reporter.rstrip("("))

    used = {}          # resource name -> line it was used on
    for number, statement in lines:
        for call in re.findall(pattern, statement):
            args = arguments(call)
            names = re.findall(r"R\.string\.(\w+)",
                               " ".join(args[index:index + 1]) if len(args) > index else "")
            if not names:
                fail("%s:%d [%s]: %s does not name a string resource — every message "
                     "the keyboard shows must be translatable"
                     % (source, number, label, reporter))
                continue
            for name in names:
                if name in used:
                    fail("%s:%d [%s]: `%s` already reports exit at line %d. Two exits "
                         "sharing a sentence collapse two failures into one and the "
                         "owner cannot tell which happened."
                         % (source, number, label, name, used[name]))
                used[name] = number
            notes.append("%s: line %d reports %s with %d argument(s)"
                         % (label, number, "/".join(names), len(args) - index - 1))

    catalogue = load_locales(messages["locales"], label)
    english = catalogue.get("values", {})
    seen = {}
    for name, number in sorted(used.items(), key=lambda kv: kv[1]):
        for locale, relative in messages["locales"].items():
            if name not in catalogue.get(locale, {}):
                fail("%s:%d [%s]: `%s` has no entry in %s"
                     % (source, number, label, name, relative))
        value = english.get(name)
        if not value:
            continue
        if not value.strip():
            fail("%s:%d [%s]: `%s` is blank in values/strings.xml — a blank message "
                 "is silence" % (source, number, label, name))
        if value in seen:
            fail("%s:%d [%s]: `%s` says exactly what `%s` says. Two exits reading the "
                 "same leave the owner unable to tell them apart."
                 % (source, number, label, name, seen[value]))
        seen[value] = name

    for number, statement in lines:
        for call in re.findall(pattern, statement):
            args = arguments(call)
            if len(args) <= index:
                continue
            for name in re.findall(r"R\.string\.(\w+)", args[index]):
                value = english.get(name)
                if value is None:
                    continue
                want, got = placeholders(value), len(args) - index - 1
                if want != got:
                    fail("%s:%d [%s]: `%s` formats %d argument(s) but the call passes "
                         "%d — getString throws at the moment the owner needs the message"
                         % (source, number, label, name, want, got))


# ── read the manifest ────────────────────────────────────────────────────────
manifest_path = os.path.join(ROOT, MANIFEST)
if not os.path.exists(manifest_path):
    print("FAIL  %s does not exist — the guard has no entry points and would "
          "otherwise pass by checking nothing" % MANIFEST)
    sys.exit(1)
manifest = json.load(open(manifest_path, encoding="utf-8"))
entry_points = manifest.get("entry_points") or []
if not entry_points:
    print("FAIL  %s lists no entry points — a guard over an empty list is a green "
          "tick for a check that is not there" % MANIFEST)
    sys.exit(1)

print("silence guard — %d user-facing action(s) from %s"
      % (len(entry_points), MANIFEST))
for entry_point in entry_points:
    check(entry_point)
for note in notes:
    print("  · %s" % note)
if problems:
    print()
    for problem in problems:
        print("FAIL  %s" % problem)
    sys.exit(1)
print("PASS — no listed action can end without telling the owner why.")
