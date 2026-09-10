# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-enhance-silence-guard.py ───
#!/usr/bin/env python3
"""No exit from the ENHANCE entry point may be silent.

WHY THIS FILE EXISTS. TextEnhancer.run() reached a blank-target check and, in the
default "auto" scope, returned without saying anything: no message, no toast, no
state change. The owner pressed the Enhance key and nothing whatsoever happened,
which is indistinguishable from a crash, a dead key, a missing key, an
unconfigured key and a network failure all at once. That ambiguity is what hid
the fact that every application whose editing surface an input method cannot read
-- canvas editors, web views, custom drawing surfaces -- was unsupported.

The assertion with value is NOT "today's wording is present". It is the property:
every `return` inside the entry point hands off to the attribution helper, and the
one exit allowed to stay quiet carries the single reason it is allowed to have. A
new silent `return` therefore fails this guard the day it is written, whatever it
says or does not say.

Deliberately plain: python3 and the standard library only. It was established
that ripgrep is absent from the CI runner, so nothing here may shell out to it.
"""

import os
import re
import sys
import xml.etree.ElementTree as ElementTree

ROOT = os.environ.get("CLOUD_ANDROID_ROOT") or os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

KEYBOARD = "ab_cloud-libs-shared/libs/keyboard/src/main"
SOURCE = KEYBOARD + "/java/helium314/keyboard/latin/TextEnhancer.kt"
LOCALES = {"values": KEYBOARD + "/res/values/strings.xml",
           "values-es": KEYBOARD + "/res/values-es/strings.xml"}

ENTRY = "fun run(context: Context, connection: RichInputConnection, style: AiRouter.Style)"
LOUD, QUIET = "ended(", "endedQuietly("

# The complete list of reasons an exit may take without putting a sentence on
# screen. It has exactly one member and the point of pinning it here is that
# growing it is an edit somebody has to make on purpose, in this file, next to
# the paragraph saying why the list is short.
QUIET_REASONS_ALLOWED = {"superseded by a newer run"}

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


def entry_body(code, in_string):
    """The braces of the entry point, from its signature to its matching close."""
    at = code.find(ENTRY)
    if at < 0:
        fail("cannot find the enhance entry point `%s` in %s — the guard is "
             "checking nothing" % (ENTRY, SOURCE))
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
    fail("the entry point's braces never close in %s" % SOURCE)
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


# ── read the source ──────────────────────────────────────────────────────────
path = os.path.join(ROOT, SOURCE)
if not os.path.exists(path):
    print("FAIL  %s does not exist — nothing to guard" % SOURCE)
    sys.exit(1)
raw = open(path, encoding="utf-8").read()
code, in_string = scan(raw)
span = entry_body(code, in_string)

used = {}          # resource name -> line it was used on
if span:
    start, end = span
    body = code[start:end]
    first_line = raw[:start].count("\n") + 1

    # 1. every return inside the entry point is attributable
    for number, statement in statements(body, first_line):
        if not re.search(r"\breturn\b", statement):
            continue
        if LOUD not in statement and QUIET not in statement:
            fail("%s:%d: silent exit — `return` with no %s or %s to attribute it:\n"
                 "        %s" % (SOURCE, number, LOUD, QUIET, statement[:160]))

    # 2. the quiet exits carry only the reason the list allows
    for number, statement in statements(body, first_line):
        for call in re.findall(r"endedQuietly\(.*", statement):
            args = arguments(call)
            if len(args) < 2:
                fail("%s:%d: %s needs a label and a reason" % (SOURCE, number, QUIET))
                continue
            reason = args[1]
            if reason.startswith('"'):
                reason = reason.strip('"')
            else:
                match = re.search(r'val\s+%s\s*=\s*"([^"]*)"' % re.escape(reason), code)
                if not match:
                    fail("%s:%d: quiet exit's reason `%s` is not a literal and no "
                         "constant of that name is declared here" % (SOURCE, number, reason))
                    continue
                reason = match.group(1)
            if reason not in QUIET_REASONS_ALLOWED:
                fail("%s:%d: quiet exit claims a reason that is not on the allowed "
                     "list: %r. Allowed: %s" % (SOURCE, number, reason,
                                                sorted(QUIET_REASONS_ALLOWED)))
            notes.append("quiet exit allowed at line %d: %s" % (number, reason))

    # 3. the loud exits: one distinct string each, with the arguments it needs
    for number, statement in statements(body, first_line):
        for call in re.findall(r"(?<!Quietly)\bended\(.*", statement):
            args = arguments(call)
            names = re.findall(r"R\.string\.(\w+)", " ".join(args[3:4]) if len(args) > 3 else "")
            if not names:
                fail("%s:%d: %s does not name a string resource — every message "
                     "the keyboard shows must be translatable" % (SOURCE, number, LOUD))
                continue
            for name in names:
                if name in used:
                    fail("%s:%d: `%s` already reports exit at line %d. Two exits "
                         "sharing a sentence collapse two failures into one and the "
                         "owner cannot tell which happened."
                         % (SOURCE, number, name, used[name]))
                used[name] = number
            notes.append("line %d reports %s with %d argument(s)"
                         % (number, "/".join(names), len(args) - 4))

# ── the strings themselves ───────────────────────────────────────────────────
catalogue = {}
for locale, relative in LOCALES.items():
    full = os.path.join(ROOT, relative)
    if not os.path.exists(full):
        fail("%s is missing — the owner reads this keyboard in Spanish" % relative)
        catalogue[locale] = {}
        continue
    catalogue[locale] = {
        node.get("name"): "".join(node.itertext())
        for node in ElementTree.parse(full).getroot().findall("string")}

english = catalogue.get("values", {})
seen = {}
for name, number in sorted(used.items(), key=lambda kv: kv[1]):
    for locale in LOCALES:
        if name not in catalogue.get(locale, {}):
            fail("%s:%d: `%s` has no entry in %s" % (SOURCE, number, name, LOCALES[locale]))
    value = english.get(name)
    if not value:
        continue
    if not value.strip():
        fail("%s:%d: `%s` is blank in values/strings.xml — a blank message is silence"
             % (SOURCE, number, name))
    if value in seen:
        fail("%s:%d: `%s` says exactly what `%s` says. Two exits reading the same "
             "leave the owner unable to tell them apart." % (SOURCE, number, name, seen[value]))
    seen[value] = name

# 4. every message gets the arguments it formats with
if span:
    for number, statement in statements(body, first_line):
        for call in re.findall(r"(?<!Quietly)\bended\(.*", statement):
            args = arguments(call)
            if len(args) < 4:
                continue
            for name in re.findall(r"R\.string\.(\w+)", args[3]):
                value = english.get(name)
                if value is None:
                    continue
                want, got = placeholders(value), len(args) - 4
                if want != got:
                    fail("%s:%d: `%s` formats %d argument(s) but the call passes %d — "
                         "getString throws at the moment the owner needs the message"
                         % (SOURCE, number, name, want, got))

print("enhance-silence guard — %s" % SOURCE)
for note in notes:
    print("  · %s" % note)
print("  · %d exits, %d distinct messages, locales checked: %s"
      % (len(used) + len([n for n in notes if "quiet exit" in n]), len(used),
         ", ".join(sorted(LOCALES))))
if problems:
    print()
    for problem in problems:
        print("FAIL  %s" % problem)
    sys.exit(1)
print("PASS — no exit from the enhance entry point is silent.")
