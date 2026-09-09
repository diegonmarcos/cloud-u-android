#!/usr/bin/env bash
# cloud-mail composed body order: static proof that a reply, a reply-all and a forward all put the
# user's message first, the signature under it, and the quoted original last -- WITHOUT a gradle
# build (this runner cannot build) and without an APK on a device. Same shape as
# test-mail-reader-actions.sh.
#
# The order the owner named, and the one every assertion below is about:
#
#     the user's new message
#     the signature
#     the quoted email being replied to
#
#   S1  there is exactly ONE plain-text assembly of a signature and a quote
#   S2  that assembly puts the signature ABOVE the quote unless asked otherwise
#   S3  the setting that inverts it is off by default, so the shipped order is the owner's
#   S4  reply, reply-all AND forward all reach the assembly through one helper -- the three-paths
#       check, because this is the bug that survives being fixed in one place
#   S5  no compose path builds a body of its own beside that helper
#   S6  a reply to a reply carries ONE signature: the quote is cut at the sender's delimiter
#   S7  the caret opens ABOVE the signature, where the answer is written
#   S8  a forward's original is joined AFTER the body at send time, so the signature still precedes
#       it -- the second assembly, and the one that can drift from the first
#   S9  the unit tests that EXECUTE the ordering exist and are named
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
UI="$APP/app/src/main/kotlin/app/sterna/ui"
DATA="$APP/core/data/src/main/kotlin/app/sterna/core/data"
TEXT="$UI/compose/ComposeText.kt"
VM="$UI/compose/ComposeViewModel.kt"
SCREEN="$UI/compose/ComposeScreen.kt"
SETTINGS="$DATA/settings/SettingsRepository.kt"
SIGTEST="$APP/app/src/test/kotlin/app/sterna/ui/compose/ComposeSignatureTest.kt"
DEPTHTEST="$APP/app/src/test/kotlin/app/sterna/ui/compose/QuoteDepthTest.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }

echo "== cloud-mail compose: answer, signature, quote -- in that order, on all three paths =="

# ── S1 one assembly ──
# Two functions that each concatenate a signature block with a quote is how the three openings
# start disagreeing. There is one, and the From-change insert below reproduces what it wrote.
has "$TEXT" 'internal fun bodyWithSignature(' "S1 one function assembles signature + quote"
n=$(grep -c 'signatureBlock(signature, delimiter)' "$TEXT")
[ "$n" -ge 1 ] && ok "S1 the block itself is built in one place ($n call sites)" \
  || bad "S1 signatureBlock is not the single source of the block"

# ── S2 the order, on the line that decides it ──
# The whole defect in one expression: `block + quoted` is the owner's order, `quoted + block` is
# the one that put the signature under the quoted mail.
has "$TEXT" 'return if (signatureBelowQuote) quoted + block else block + quoted' \
  "S2 the default branch is block + quoted -- signature ABOVE the quote"
# …and the From-change insert must land in the SAME spot, or changing identity mid-reply moves the
# signature below the quote the prefill had put it above.
has "$TEXT" 'return body.dropLast(quoted.length) + block + quoted' \
  "S2 a From change re-inserts the block above the quote, not at the end"

# ── S3 the shipped default is the owner's order ──
has "$SETTINGS" 'it\[KEY_SIGNATURE_BELOW_QUOTE\] ?: false' \
  "S3 'signature below the quoted text' is OFF by default"

# ── S4 all three openings reach the one assembly ──
# THE three-paths check. Each of the three DraftFields branches in buildPrefill must take its body
# from replyBody; a branch that builds its own string is the bug this file exists for.
python3 - "$VM" <<'PY'
import sys
src = open(sys.argv[1], encoding='utf-8').read()
start = src.index('private suspend fun buildPrefill')
when = src[start:src.index('\n    /**', start + 10)]
# One arm per "-> DraftFields(". Several openings may share an arm (the two forwards do), so the
# arm is keyed by every ComposeOpening named in its labels, not by the first one found.
marks = [i for i in range(len(when)) if when.startswith('-> DraftFields(', i)]
if not marks:
    print('  FAIL: S4 buildPrefill no longer returns DraftFields per opening')
    sys.exit(1)
covered, missing, prev_end = set(), [], 0
for n, at in enumerate(marks):
    # everything since the previous arm's last line is this arm's labels
    labels = when[prev_end:at]
    nxt = marks[n + 1] if n + 1 < len(marks) else len(when)
    # the arm body stops at the newline that starts the NEXT arm's labels
    arm_end = when.rindex('\n', at, nxt) if n + 1 < len(marks) else nxt
    arm = when[at:arm_end]
    named = {w for w in ('FORWARD_ATTACHMENT', 'FORWARD', 'REPLY_ALL', 'REPLY')
             if f'ComposeOpening.{w}' in labels}
    covered |= named
    if 'body = replyBody(' not in arm:
        missing.append(f'the arm for {"/".join(sorted(named)) or "?"} builds its body without replyBody()')
    prev_end = arm_end
for want in ('FORWARD', 'FORWARD_ATTACHMENT', 'REPLY_ALL', 'REPLY'):
    if want not in covered:
        missing.append(f'ComposeOpening.{want} has no arm at all')
if missing:
    for m in missing:
        print(f'  FAIL: S4 {m}')
    sys.exit(1)
print(f'  ok: S4 all {len(covered)} openings take their body from replyBody() ({len(marks)} arms)')
PY
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))
# …and replyBody is the one thing that calls the assembly, so the three share code rather than
# merely agreeing today.
n=$(grep -o 'bodyWithSignature(' "$VM" | wc -l)
[ "$n" -eq 1 ] && ok "S4 exactly one call site of bodyWithSignature in the ViewModel" \
  || bad "S4 $n call sites of bodyWithSignature -- the paths no longer share the assembly"
has "$VM" 'private suspend fun replyBody' "S4 the shared helper exists"

# ── S5 no path assembles a body beside the helper ──
# The blank-new-message prefill is allowed its own signatureBlock (it has no quote to order against);
# anything else concatenating SIGNATURE_DELIMITER into a body would be a second assembly.
python3 - "$VM" "$SCREEN" <<'PY'
import sys
bad = []
for path in sys.argv[1:]:
    for i, line in enumerate(open(path, encoding='utf-8'), 1):
        if 'SIGNATURE_DELIMITER' in line and not line.lstrip().startswith(('*', '//')):
            bad.append(f'{path.split("/")[-1]}:{i} builds a delimiter into a body by hand')
if bad:
    for b in bad:
        print(f'  FAIL: S5 {b}')
    sys.exit(1)
print('  ok: S5 no compose path writes the delimiter into a body by hand')
PY
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))

# ── S6 a reply to a reply carries ONE signature ──
# The sender's own signature is cut off the quote before it is quoted, so a fresh block is added to
# a body that does not already end in an old one. Without this the count grows by one per round.
has "$TEXT" 'return cutAtSignatureDelimiter(if (isHtml) htmlToQuotedText(raw) else raw)' \
  "S6 the quote is cut at the sender's signature delimiter before it is quoted"
has "$VM" 'val quoted = quotedOriginalText(o).lineSequence()' \
  "S6 the reply quote is built from that cut copy, not the raw body"

# ── S7 the caret opens above the signature ──
# The order can be right and the experience wrong: a caret below the signature, or below the quote,
# means the user's first keystroke lands in the wrong block.
has "$TEXT" 'internal fun initialBodyCaret' "S7 one rule decides the opening caret"
python3 - "$TEXT" <<'PY'
import sys
src = open(sys.argv[1], encoding='utf-8').read()
start = src.index('internal fun initialBodyCaret')
body = src[start:src.index('\n}', start)]
# A reply is not a draft and carries no mailto: body, so it must fall to the `else -> 0` arm.
if 'else -> 0' not in body:
    print('  FAIL: S7 a reply no longer opens with the caret at offset 0 (above the signature)')
    sys.exit(1)
if 'isDraft -> bodyLength' not in body:
    print('  FAIL: S7 the reopened-draft arm is gone; the reply arm can no longer be told from it')
    sys.exit(1)
print('  ok: S7 a reply opens with the caret at offset 0, above the signature and the quote')
PY
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))
has "$SCREEN" 'body = TextFieldValue(quote, TextRange(0))' \
  "S7 the out-of-band quote drop-in also puts the caret at the top"

# ── S8 the forward's SECOND assembly keeps the signature ahead of the original ──
# A forward's editable body holds the signature but no quote: the original is joined at send time,
# by a different function. That join must APPEND, or the forwarded mail lands above the signature.
has "$VM" 'return "${toPlainText(userBody)}\\n\\n${fwd.text}" to "$html<br><br>${fwd.html}"' \
  "S8 the forwarded original is appended AFTER the body in both alternatives"
has "$VM" 'body = replyBody(""),' "S8 a forward's editable body is the signature alone, no quote"

# ── S9 the assertions that EXECUTE the ordering ──
# The greps above prove the wiring; these named tests run the functions and compare whole strings.
for t in replyPutsTheSignatureAboveTheQuoteByDefault \
         onAReplyTheSignatureGoesAboveTheQuote_underTheAnswerBeingWritten \
         anUntouchedReplyEndsUpExactlyAsThePrefillWouldHaveBuiltIt \
         theQuoteBelowTheSignatureSurvivesTheSubstitution; do
  has "$SIGTEST" "$t" "S9 executed: $t"
done
has "$DEPTHTEST" 'theSignatureIsStillCutFromAQuotedHtmlOriginal' \
  "S9 executed: a quoted original's own signature is cut (the reply-to-a-reply count)"
has "$DEPTHTEST" 'aQuotedSignatureInsideTheHistoryIsLeftInPlace' \
  "S9 executed: an already-quoted signature deeper in the history is left alone"

echo
echo "== $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
