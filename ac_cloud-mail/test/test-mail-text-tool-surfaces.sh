#!/usr/bin/env bash
# cloud-mail Text tools, PER SURFACE: which action each surface offers, and that the ones it does
# not offer are UNREACHABLE rather than merely undrawn. Static proof, WITHOUT a gradle build (this
# runner cannot build) and without an APK on a device. Same shape as test-mail-text-tools.sh.
#
# The rule being guarded, in the owner's words:
#
#     READING a message   ->  AI Resume   + Translate
#     COMPOSING a mail    ->  Text Enhance + Translate
#
# Enhance REWRITES a text into a better version of itself, which only means anything for text the
# user is writing and can still change. A received message is a record of what somebody else sent:
# nothing to improve, nowhere to save an improvement. Resume (this product's name for SUMMARISE)
# is the read-side counterpart -- a separate, shorter text ABOUT the message. Translate is on both
# because it is meaningful in both directions.
#
#   D1  the rule is DECLARED, in exactly one place, and no surface hand-builds its own set
#   S1  each surface's set is exactly what the owner asked for
#   S2  every renderer -- menu AND toolbar icon -- draws from that declaration
#   D2  THE DEAD PATH: an action a surface does not offer cannot be RUN from it, not merely
#       cannot be tapped. Asserted on the guard in front of the engines, not on the menu
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
UI="$APP/app/src/main/kotlin/app/sterna/ui"
RUN="$UI/text/TextToolRun.kt"
PANEL="$UI/text/TextToolPanel.kt"
READER="$UI/message/MessageScreen.kt"
COMPOSER="$UI/compose/ComposeScreen.kt"
MAIN="$APP/app/src/main"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }
lacks() { grep -q -- "$2" "$1" && bad "$3 ($1)" || ok "$3"; }

echo "== cloud-mail Text tools per surface: declaration, renderers, and the dead path =="

# ── D1/S1 the rule is declared once, and says what the owner asked for ──
# Parsed out of the Kotlin rather than grepped for a phrase: the assertion has to fail when the
# SET changes, which a substring match on "TextToolSurface" would not.
python3 - "$RUN" "$MAIN" <<'PY'
import re, sys, pathlib
run = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')
fails = 0

block = re.search(r'enum class TextToolSurface\(.*?\)\s*\{(.*?)\n\}', run, re.S)
if not block:
    print("  FAIL: D1 there is no TextToolSurface declaration -- the rule lives nowhere")
    sys.exit(1)
declared = {
    name: re.findall(r'TextTool\.(\w+)', body)
    for name, body in re.findall(r'(\w+)\(listOf\(([^)]*)\)\)', block.group(1))
}
print(f"  ok: D1 TextToolSurface declares {len(declared)} surfaces: {' '.join(sorted(declared))}")

# The owner's rule, stated here independently of the code so the two have to agree.
want = {"READ": {"RESUME", "TRANSLATE"}, "COMPOSE": {"ENHANCE", "TRANSLATE"}}
if set(declared) != set(want):
    print(f"  FAIL: S1 surfaces are {sorted(declared)}, expected {sorted(want)}")
    fails += 1
for surface, tools in sorted(want.items()):
    got = set(declared.get(surface, []))
    if got == tools:
        print(f"  ok: S1 {surface} offers exactly {' + '.join(sorted(tools))}")
    else:
        print(f"  FAIL: S1 {surface} offers {sorted(got)}, expected {sorted(tools)}")
        fails += 1
# ...and the three named consequences, each spelled out so a failure says WHICH rule broke.
for surface, tool, why in (
    ("COMPOSE", "ENHANCE", "a draft is the user's own text and a rewrite has somewhere to land"),
    ("READ", "RESUME", "a summary is a separate text ABOUT a message somebody else sent"),
    ("READ", "TRANSLATE", "Translate is meaningful in both directions"),
    ("COMPOSE", "TRANSLATE", "Translate is meaningful in both directions"),
):
    if tool in declared.get(surface, []):
        print(f"  ok: S1 {tool} is on {surface} -- {why}")
    else:
        print(f"  FAIL: S1 {tool} is missing from {surface} -- {why}")
        fails += 1
for surface, tool, why in (
    ("READ", "ENHANCE", "there is nothing to improve in a record of what somebody else sent"),
    ("COMPOSE", "RESUME", "summarising a draft you are still writing answers nothing"),
):
    if tool not in declared.get(surface, []):
        print(f"  ok: S1 {tool} is NOT on {surface} -- {why}")
    else:
        print(f"  FAIL: S1 {tool} is offered on {surface} -- {why}")
        fails += 1

# D1 the declaration is the ONLY place a set of tools is written down. A screen that names two or
# more different tools is hand-building a list again, which is the arrangement this replaced: two
# lists, no way for them to disagree loudly, and a fix that lands in one of them.
for path in sorted(pathlib.Path(sys.argv[2]).rglob("*.kt")):
    if path.samefile(sys.argv[1]):
        continue
    named = set(re.findall(r'TextTool\.([A-Z]+)\b', path.read_text(encoding='utf-8')))
    if len(named) > 1:
        print(f"  FAIL: D1 {path.name} names {sorted(named)} -- it is building its own tool set")
        fails += 1
print("  ok: D1 no file outside TextToolRun.kt enumerates a set of tools")
sys.exit(1 if fails else 0)
PY
[ $? -eq 0 ] && PASS=$((PASS+11)) || FAIL=$((FAIL+1))

# ── S2 the renderers ask the declaration ──
# Each screen says which surface it IS, exactly once, and nothing else decides.
has "$READER" 'rememberTextToolRunner(TextToolSurface.READ)' "S2 the reader declares itself the READ surface"
has "$COMPOSER" 'rememberTextToolRunner(TextToolSurface.COMPOSE)' "S2 the composer declares itself the COMPOSE surface"
has "$READER" 'TextToolMenuItems(textTools.surface)' "S2 the reader's overflow is drawn from its surface"
has "$COMPOSER" 'TextToolMenuItems(textTools.surface, enabled = !sending)' "S2 the composer's overflow is drawn from its surface"
has "$PANEL" 'surface.tools.filter { it.inOverflow }.forEach' "S2 the menu iterates the surface's own list"
# No screen may hand-write a tool entry beside the generated ones: that is how one surface keeps an
# action the declaration dropped. A tool's LABEL belongs to the enum now, so a screen naming one is
# the tell. Scoped to the three label strings: the composer also names text_tool_stale, which is
# its own toast about a range that moved, not a tool entry, and matching it taught nothing.
for f in "$READER" "$COMPOSER"; do
  grep -qE 'R\.string\.text_tool_(enhance|translate|resume)' "$f" \
    && bad "S2 ${f##*/} hand-writes a tool entry of its own" \
    || ok "S2 ${f##*/} hand-writes no tool entry of its own"
done
# The toolbar icon obeys the same list -- "no menu entry" is not the same as "no icon", and the
# icon is the one a reader forgets. Membership, not just the has-a-body check beside it.
has "$READER" 'TextTool.RESUME in textTools.surface.tools' "S2 the AI Resume ICON is gated on membership too"
has "$READER" 'TextTool.RESUME.icon' "S2 the icon comes from the declaration, not a second glyph choice"
# ...and the panel decides where an outcome is drawn from the same enum rather than naming a tool.
# Code only: the KDoc above EXPLAINS that RESUME draws itself, and matching the explanation would
# have failed on the very comment that makes the rule readable.
has "$PANEL" 'if (!tool.inOverflow) return' "S2 the panel routes an outcome by inOverflow"
grep -vE '^\s*(\*|//|/\*)' "$PANEL" | grep -q 'TextTool\.RESUME' \
  && bad "S2 the panel still names RESUME in code instead of asking inOverflow" \
  || ok "S2 the panel names no single tool in code"

# ── D2 THE DEAD PATH ──
# The point of this section: "Enhance is gone from the reader" must be a property of the APP, not
# of one overflow menu. Everything below is about whether a call can still ARRIVE.
lacks "$READER" 'ENHANCE' "D2 the reader names ENHANCE nowhere -- no entry, no icon, no handler"
lacks "$COMPOSER" 'RESUME' "D2 the composer names RESUME nowhere"
# The guard itself, and that it sits BEFORE the routing rather than after it.
python3 - "$RUN" <<'PY'
import sys, pathlib
src = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')
fails = 0
guard = "if (tool !in surface.tools)"
if guard not in src:
    print("  FAIL: D2 TextToolRunner.run does not check the tool against its surface")
    sys.exit(1)
print("  ok: D2 TextToolRunner.run refuses a tool its surface does not offer")
if src.index(guard) < src.index("when (tool) {"):
    print("  ok: D2 the refusal happens BEFORE the engine is chosen")
else:
    print("  FAIL: D2 the guard sits after the routing -- the call has already gone out")
    fails += 1
# A refusal that returns quietly is the bug this is here to prevent: it looks like a tool that ran
# and produced nothing. The guard has to report, like every other way out of run().
# Scoped to the guard's OWN block, not a window of characters after it: a fixed window reached the
# next branch's outcome and passed on a guard whose own report had been deleted. Watched failing.
# Scoped to the guard's OWN block and to COMMENT-FREE code, both learned by watching this
# assertion pass on a mutant: a fixed character window reached the NEXT branch's outcome, and a
# bare substring match was satisfied by the very line it was meant to check, commented out.
tail = src[src.index(guard):src.index("\n        }", src.index(guard))]
tail = "\n".join(l for l in tail.splitlines() if not l.strip().startswith("//"))
if "outcome = TextToolOutcome(tool, null," in tail:
    print("  ok: D2 a refused run reports, rather than returning silently")
else:
    print("  FAIL: D2 the guard returns without an outcome -- a refusal would look like a no-op")
    fails += 1
# A runner cannot exist without a surface: no default, and no second way to build one.
if "fun rememberTextToolRunner(surface: TextToolSurface)" in src:
    print("  ok: D2 a runner cannot be built without naming its surface")
else:
    print("  FAIL: D2 rememberTextToolRunner does not require a surface")
    fails += 1
sys.exit(1 if fails else 0)
PY
[ $? -eq 0 ] && PASS=$((PASS+4)) || FAIL=$((FAIL+1))

# The engines are reachable through exactly one function, so the guard above is not one of several
# doors. If a screen could call client.enhance() itself, none of this would mean anything.
python3 - "$MAIN" "$RUN" <<'PY'
import re, sys, pathlib
main, run = pathlib.Path(sys.argv[1]), sys.argv[2]
fails = 0
# Matched as a whole name: "TextToolRunner(" is a substring of "rememberTextToolRunner(", and a
# check that cannot tell the constructor from its own factory reports every caller as a door.
# The tool calls carry THIS APP'S own prompt and model now (enhanceWith/summariseWith) rather than
# an empty argument that asked the keyboard to resolve the setting. Same doors, renamed - the guard
# is about which file may open one, not about what the call is called.
#
# NOT IN THIS LIST, deliberately: client.settingsSnapshot(). It runs no tool and produces no text
# for a surface to draw; it is read once by MailTextToolsPrefs to seed this app's copy of the
# owner's settings. Guarding it here would be guarding the settings screens against offering
# settings, and the surface rule has nothing to say about them.
for call in ("client.enhanceWith(", "client.translate(", "client.summariseWith(", "TextToolRunner("):
    pat = re.compile(r'(?<![A-Za-z])' + re.escape(call))
    where = [p for p in sorted(main.rglob("*.kt")) if pat.search(p.read_text(encoding='utf-8'))]
    if [str(p) for p in where] == [run]:
        print(f"  ok: D2 {call}) is reached from TextToolRun.kt alone")
    else:
        print(f"  FAIL: D2 {call}) is reached from {[p.name for p in where]} -- a second door past the guard")
        fails += 1
sys.exit(1 if fails else 0)
PY
[ $? -eq 0 ] && PASS=$((PASS+4)) || FAIL=$((FAIL+1))

# Finally, the surfaces and their call sites checked against each other: every tool a screen names
# in a run() call has to be one its own declared surface offers. This is what would catch an
# Enhance handler left behind on the reader under any name.
python3 - "$READER" "$COMPOSER" "$RUN" <<'PY'
import re, sys, pathlib
run = pathlib.Path(sys.argv[3]).read_text(encoding='utf-8')
block = re.search(r'enum class TextToolSurface\(.*?\)\s*\{(.*?)\n\}', run, re.S).group(1)
declared = {n: set(re.findall(r'TextTool\.(\w+)', b))
            for n, b in re.findall(r'(\w+)\(listOf\(([^)]*)\)\)', block)}
fails = 0
for path in sys.argv[1:3]:
    p = pathlib.Path(path)
    src = p.read_text(encoding='utf-8')
    m = re.search(r'rememberTextToolRunner\(TextToolSurface\.(\w+)\)', src)
    if not m:
        print(f"  FAIL: D2 {p.name} builds a runner without naming a surface")
        fails += 1
        continue
    surface = m.group(1)
    used = set(re.findall(r'\.run\([^)]*?TextTool\.(\w+)', src))
    stray = used - declared[surface]
    if stray:
        print(f"  FAIL: D2 {p.name} ({surface}) runs {sorted(stray)}, which {surface} does not offer")
        fails += 1
    else:
        print(f"  ok: D2 {p.name} ({surface}) runs only {sorted(used) or 'tools chosen by the menu'}")
sys.exit(1 if fails else 0)
PY
[ $? -eq 0 ] && PASS=$((PASS+2)) || FAIL=$((FAIL+1))

echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" -eq 0 ]
