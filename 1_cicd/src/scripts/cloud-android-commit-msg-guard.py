#!/usr/bin/env python3
"""#670 commit-message guard: a published commit message carries an interpreted
task brief, never the owner's words quoted.

Fails any commit message in the checked range that contains profanity, `!!!`,
an ALL-CAPS shouting run, a `> ` blockquote line, or `verbatim:` followed by
quoted text. Every commit in the range is scanned, not only the tip, so a
stacked or squashed push cannot carry one through.

Usage:
  cloud-android-commit-msg-guard.py --range BASE..HEAD   # git rev-list range
  cloud-android-commit-msg-guard.py --file MSGFILE       # one message (tester / hook)

The range is new pushes only: history that predates the guard is not
re-litigated here (rewriting it is the owner's decision, tracked in #670).
"""
import re
import subprocess
import sys

PROFANITY = re.compile(r"\b(fuck\w*|shit\w*|bullshit\w*|wtf|crap|crappy|damn\w*|asshole\w*|bitch\w*)\b", re.I)
BANG = re.compile(r"!!!|\?\?\?")
BLOCKQUOTE = re.compile(r"^>[ \t]", re.M)  # column 0 only: indented "  > " is quoted tool output, not a person
VERBATIM = re.compile(r"verbatim\s*:\s*[\"'“‘*_]", re.I)
CODE_SPAN = re.compile(r"`[^`\n]*`")
# Shouting: an all-caps run of four or more words (20+ chars) that ends in
# '!' or '?', or that sits inside quotation marks. A bare all-caps run is NOT
# shouting here: this repository writes section headings in capitals
# ("WHY THIS EXISTS", "WHAT THIS DOES NOT DO") and measured over 1000 commits
# that rule alone flagged ~70 house-style headings and no quote. Code spans
# in backticks are stripped first.
CAPS = r"[A-Z][A-Z']+(?:[ \t,]+[A-Z][A-Z']+){3,}"
CAPS_RUN = re.compile(r"(?:" + CAPS + r"[ \t]*[!?]|[\"\u201c]" + CAPS + r")")
CAPS_MIN = 20

TRAILER = re.compile(r"^[A-Za-z-]+: ", re.M)


def findings(msg: str):
    out = []
    prose = CODE_SPAN.sub("", msg)
    m = PROFANITY.search(prose)
    if m:
        out.append(f"profanity ({m.group(0)[0]}***)")
    if BANG.search(prose):
        out.append("'!!!' or '???'")
    if BLOCKQUOTE.search(msg):
        out.append("'> ' blockquote line")
    if VERBATIM.search(prose):
        out.append("'verbatim:' followed by quoted text")
    for m in CAPS_RUN.finditer(prose):
        if len(m.group(0)) >= CAPS_MIN:
            out.append(f"ALL-CAPS run of {len(m.group(0))} chars")
            break
    return out


def main(argv):
    if len(argv) == 3 and argv[1] == "--file":
        with open(argv[2], encoding="utf-8") as f:
            msgs = [("(file)", f.read())]
    elif len(argv) == 3 and argv[1] == "--range":
        rng = argv[2]
        shas = subprocess.run(["git", "rev-list", "--no-merges", rng], check=True,
                              capture_output=True, text=True).stdout.split()
        msgs = [(s[:10], subprocess.run(["git", "log", "-1", "--format=%B", s], check=True,
                                        capture_output=True, text=True).stdout) for s in shas]
    else:
        print(__doc__, file=sys.stderr)
        return 2
    bad = 0
    for sha, msg in msgs:
        f = findings(msg)
        if f:
            bad += 1
            subj = msg.splitlines()[0] if msg else ""
            print(f"FAIL {sha} {subj[:80]!r}: " + "; ".join(f))
    print(f"commit-msg guard: {len(msgs)} message(s) checked, {bad} failing")
    if bad:
        print("A published commit message carries an interpreted task brief in neutral "
              "engineering prose, never the owner's words (#670). Reword with "
              "`git commit --amend` / `git rebase -i` before pushing.")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
