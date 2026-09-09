#!/usr/bin/env bash
# cloud-mail reader actions: static proof of the wiring, WITHOUT a gradle build (this runner cannot
# build) and without an APK on a device. Same shape as test-mail-text-tools.sh.
#
#   M1  a message's mailbox membership is EDITED, never replaced -- the data-loss check, asserted
#       on the JSON patch that goes out, not on a screen
#   M2  ADD and REMOVE exist as their own operations, because "move" is not one on a set
#   T1  parts three and five agree on what a tag IS, in one function, and system keywords are not it
#   U1  the unsubscribe icon is absent when the message offers no unsubscribe path
#   U2  the body-scan fallback never fires a POST and never overrides a real header
#   P1  every AI feature's prompt comes from the registry; no prompt string is hardcoded in Kotlin
#   B1  the AI Resume box never writes back to the stored body
#   R1  the default reply action resolves to reply-all, and single reply is still reachable
#   A1  the action row holds what it says it holds, and the count is stated
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$APP/.."
LIBS="$ROOT/ab_cloud-libs-shared"
KB="$LIBS/libs/keyboard/src/main/java"
UI="$APP/app/src/main/kotlin/app/sterna/ui"
JMAP="$APP/core/jmap/src/main/kotlin/app/sterna/core/jmap"
DATA="$APP/core/data/src/main/kotlin/app/sterna/core/data"
SCREEN="$UI/message/MessageScreen.kt"
VM="$UI/message/MessageViewModel.kt"
META="$UI/message/MessageMetadata.kt"
STR="$APP/app/src/main/res/values/strings.xml"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }
lacks() { grep -q -- "$2" "$1" && bad "$3 ($1)" || ok "$3"; }

echo "== cloud-mail reader: membership, tags, unsubscribe, prompts, resume, reply default =="

# ── M1 the data-loss check: an Email/set UPDATE may never write mailboxIds whole ──
# The prior defect (00127a847, reintroduced by the Kotlin rewrite) was a patch of the WHOLE
# "mailboxIds" property, which deletes every mailbox the message was in but the target. Asserted on
# what is emitted: exactly one function writes membership, and it writes RFC 8620 5.3 patch paths.
has "$JMAP/JmapClient.kt" 'internal fun JsonObjectBuilder.putMembershipPatch' \
  "M1 membership is written in exactly one place"
n=$(grep -c 'putMembershipPatch(add' "$JMAP/JmapClient.kt")
[ "$n" -ge 3 ] && ok "M1 move / add / remove all go through it ($n call sites)" \
  || bad "M1 only $n call sites use putMembershipPatch -- one of them writes its own patch"
has "$JMAP/JmapClient.kt" 'put("mailboxIds/\$add", JsonPrimitive(true))' "M1 an add is a patch path"
has "$JMAP/JmapClient.kt" 'put("mailboxIds/\$remove", JsonNull)' "M1 a remove is a patch path"
# The whole-property write may survive ONLY under create/import: a message that does not exist yet
# has no membership to preserve. Anything under "update" is the bug.
python3 - "$JMAP/JmapClient.kt" <<'PY'
import sys
lines = open(sys.argv[1], encoding='utf-8').read().split('\n')
bad = []
for i, l in enumerate(lines):
    if 'putJsonObject("mailboxIds")' not in l:
        continue
    verb = None
    for j in range(i - 1, max(0, i - 80), -1):
        for v in ('"create"', '"update"', '"destroy"', 'Email/import'):
            if v in lines[j]:
                verb = v
                break
        if verb:
            break
    if verb not in ('"create"', 'Email/import'):
        bad.append((i + 1, verb, l.strip()))
if bad:
    for line, verb, text in bad:
        print(f"  FAIL: M1 line {line} writes the whole mailboxIds property under {verb}: {text}")
    sys.exit(1)
print("  ok: M1 no Email/set UPDATE writes the whole mailboxIds property")
PY
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))
# The source folder is a REQUIRED argument: a default of null turns every move into a copy.
lacks "$JMAP/JmapClient.kt" 'sourceMailboxId: String? = null' "M1 the source folder has no default"
# and the unit test that executes the builder exists and asserts on the emitted keys
PATCHTEST="$APP/core/jmap/src/test/kotlin/app/sterna/core/jmap/MailboxMembershipPatchTest.kt"
has "$PATCHTEST" 'aMoveEditsTwoBitsAndLeavesEveryOtherMailboxUnnamed' "M1 a test executes the patch builder"
has "$PATCHTEST" 'noArgumentEverProducesAWholePropertyWrite' "M1 the whole-property write is forbidden by test"

# ── M2 add and remove are their own operations, at every layer ──
for f in "$JMAP/JmapClient.kt" "$DATA/mail/MailRepository.kt"; do
  has "$f" 'fun addToMailbox' "M2 ${f##*/} offers ADD"
  has "$f" 'fun removeFromMailbox' "M2 ${f##*/} offers REMOVE"
done
has "$VM" 'fun addMailbox' "M2 the reader can add a mailbox"
has "$VM" 'fun removeMailbox' "M2 the reader can remove a mailbox"
# The membership shown is the SERVER's, never the cached row's single mailboxId.
has "$DATA/mail/MailRepository.kt" 'client.mailboxIdsOf' "M2 membership is read from the server"

# ── T1 parts three and five agree on what a tag is ──
has "$META" 'fun messageTags' "T1 one function decides what a tag is"
n=$(grep -c 'messageTags(' "$SCREEN")
[ "$n" = 2 ] && ok "T1 the chip row and the label sheet both call it" \
  || bad "T1 messageTags called $n times in the screen, expected 2 (chip row + label sheet)"
has "$META" 'enum class TagKind' "T1 the two kinds are distinguished"
has "$META" 'MAILBOX' "T1 mailboxIds is one kind of tag"
has "$META" 'KEYWORD' "T1 keywords is the other"
# System keywords are the star's and the unread state's, and must not be offered as tags.
has "$META" 'filterNot { it.startsWith(SYSTEM_KEYWORD_PREFIX) }' "T1 \$-prefixed keywords are not tags"
# Removing a mailbox asks; removing a keyword does not.
has "$META" 'fun removalNeedsConfirming' "T1 the two removals are not equally destructive"
has "$UI/message/LabelSheet.kt" 'if (removalNeedsConfirming(tag)) confirming = tag' \
  "T1 the sheet confirms a mailbox removal and only that"

# ── U1 the unsubscribe icon is absent when there is nothing to unsubscribe from ──
# The icon, the banner and the overflow entry share ONE decision, so they cannot disagree about
# whether the message offers a way out.
n=$(grep -c 'offeredUnsubscribeAction(unsubscribe, unsubscribeState)' "$SCREEN")
[ "$n" -ge 2 ] && ok "U1 icon and overflow entry share the offer decision ($n sites)" \
  || bad "U1 only $n site gates on offeredUnsubscribeAction -- the icon can outlive the offer"
has "$VM" 'options?.preferredAction()' "U1 no options means no action means no icon"
has "$VM" 'UnsubscribeState.Sending, UnsubscribeState.Sent, UnsubscribeState.Queued -> null' \
  "U1 an already-taken way out offers nothing"

# ── U2 the fallback guesses, and is treated as a guess ──
SCAN="$DATA/mail/UnsubscribeBodyScan.kt"
has "$SCAN" 'UnsubscribeOptions(pageUrl = it)' "U2 a guessed link is only ever a page to look at"
lacks "$SCAN" 'oneClickUrl' "U2 a guess NEVER becomes a one-click POST"
has "$VM" '?: scanBodyForUnsubscribe(anchor)' "U2 the scan runs only when the headers gave nothing"
# elvis, so a real header is never overridden
has "$VM" 'UnsubscribeHeader.parse(anchor.listUnsubscribe, anchor.listUnsubscribePost)' \
  "U2 the headers are still parsed first"
# the word list is DATA, not a list in Kotlin
# CODE lines only. The KDoc above explains that a scan reading the word "unsubscribe" is reading
# the attacker's own text, and grepping the whole file made the explanation trip the assertion --
# which would have taught the next reader to delete the explanation.
if grep -vE '^\s*(//|\*|/\*)' "$SCAN" | grep -qiE '"[^"]*unsubscribe[^"]*"|"[^"]*opt.?out[^"]*"'; then
  bad "U2 the word list is hardcoded in the scanner"
else
  ok "U2 the word list is not hardcoded in the scanner"
fi
has "$VM" 'R.array.unsubscribe_body_words' "U2 the words come from a localised resource"
has "$STR" 'name="unsubscribe_body_words"' "U2 that resource exists"
# and nothing is contacted before the user has seen what will be contacted
has "$SCREEN" 'onClick = { viewModel.askUnsubscribe() }' "U2 the icon ASKS, it does not send"
has "$VM" '_unsubscribeConfirm.value = PendingUnsubscribe(action, options)' "U2 asking puts up the confirmation"

# ── P1 every prompt is registry data; none is a Kotlin literal ──
REG="$LIBS/build.json"
python3 - "$REG" <<'PY'
import json, sys
ai = json.load(open(sys.argv[1], encoding='utf-8'))['keyboard_ai']
missing = [k for k in ('styles', 'summaries', 'rewrite_preamble', 'summary_preamble',
                       'default_style', 'default_summary') if k not in ai]
if missing:
    print("  FAIL: P1 build.json::keyboard_ai is missing " + ", ".join(missing))
    sys.exit(1)
for name in ('styles', 'summaries'):
    for k, v in ai[name].items():
        if 'prompt' not in v or 'label' not in v:
            print(f"  FAIL: P1 {name}.{k} has no prompt/label")
            sys.exit(1)
print(f"  ok: P1 the registry holds {len(ai['styles'])} enhance and {len(ai['summaries'])} summary prompts")
PY
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))
# The engine reads them from the registry rather than holding its own.
has "$KB/helium314/keyboard/latin/AiRouter.kt" 'promptSet("summaries")' "P1 summaries come from the registry"
has "$KB/helium314/keyboard/latin/AiRouter.kt" 'registry.optString("summary_preamble")' "P1 so does the preamble"
# THE point: no file that sends a prompt may contain one. A prompt is a sentence of instructions;
# the giveaway is an imperative addressed to a model in a string literal.
for f in "$KB/com/diegonmarcos/superapp/texttools/TextToolsService.kt" \
         "$UI/text/TextToolRun.kt" "$UI/message/ResumeBox.kt"; do
  if grep -nE '"[^"]*(Summarise|Rewrite|You are a) [^"]{20,}"' "$f" >/dev/null 2>&1; then
    bad "P1 ${f##*/} holds a prompt string literal"
  else
    ok "P1 ${f##*/} holds no prompt of its own"
  fi
done
# and the settings screens SHOW the prompt, which is why it has to be data in the first place
SCREENS="$KB/helium314/keyboard/settings/screens"
has "$SCREENS/TextEnhanceScreen.kt" 'PromptPreview(setting, AiRouter.enhanceStyle(' "P1 Text Enhancement shows its prompt"
has "$SCREENS/TextResumeScreen.kt" 'PromptPreview(setting, AiRouter.summaryStyle(' "P1 Text Resume shows its prompt"
has "$SCREENS/AiRoutingScreen.kt" 'AiRouter.rewritePreamble' "P1 AI Routing shows the shared preambles"

# ── the fourth Configs entry, and that it resolves to a real page ──
SEC="$UI/settings/TextToolsSection.kt"
NAV="$KB/helium314/keyboard/settings/SettingsNavHost.kt"
n=$(grep -c 'screen = "' "$SEC")
[ "$n" = 4 ] && ok "P1 Configs > Text declares four entries" || bad "P1 declares $n entries, expected 4"
has "$SEC" 'screen = "text_resume"' "P1 Text Resume is one of them"
grep -qE '^\s*"text_resume" to ' "$NAV" && ok "P1 text_resume resolves to a real keyboard destination" \
  || bad "P1 text_resume is not allowlisted -- the row would open the keyboard's front page"
# ...and after Text Enhancement, as asked
python3 - "$SEC" <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
order = re.findall(r'screen = "([a-z_]+)"', src)
want = ["ai_routing", "text_enhance", "text_resume", "translation"]
if order != want:
    print(f"  FAIL: P1 entry order is {order}, expected {want}")
    sys.exit(1)
print("  ok: P1 Text Resume sits after Text Enhancement")
PY
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))

# ── B1 the summary never writes back to the stored body ──
BOX="$UI/message/ResumeBox.kt"
has "$BOX" 'var edited by remember' "B1 the summary lives in the box's own state"
# Nothing in the box may reach a writer. The runner is passed for its progress/error and dismiss()
# only -- any other call on it, or any viewModel at all, is a route to the message.
lacks "$BOX" 'viewModel' "B1 the box holds no ViewModel, so it cannot ask for a write"
for writer in 'saveBody' 'updateBody' 'setBody' 'copy(bodyValues' 'onApply'; do
  lacks "$BOX" "$writer" "B1 the box calls no body writer ($writer)"
done
# The reader still passes NO apply callback to the shared panel, as the previous agent left it.
has "$SCREEN" 'TextToolPanel(textTools, onApply = null)' "B1 the shared panel still has nowhere to apply"
# What is SENT is the quote-free flattened copy, not the stored body.
has "$SCREEN" 'TextToolScope.receivedScope' "B1 the summary is made from a quote-free COPY"
# ...and Resume reuses that same source rather than building a second one.
has "$SCREEN" 'textTools.run(textToolScope, TextTool.RESUME, textToolSource())' "B1 Resume sends the same scoped text"

# ── the AI plumbing is REUSED, not copied ──
RUN="$UI/text/TextToolRun.kt"
n=$(grep -c "when (tool)" "$RUN")
[ "$n" = 1 ] && ok "A0 tool -> engine is still mapped in exactly one place" \
  || bad "A0 tool->engine mapped in $n places -- a third copy of the routing"
has "$RUN" 'TextTool.RESUME -> client.summarise(text)' "A0 Resume routes through the shared client"
# no second client, no second binding, no credential anywhere in mail
n=$(grep -rc 'TextToolsClient(' "$APP/app/src/main" | grep -v ':0$' | wc -l)
[ "$n" = 1 ] && ok "A0 exactly one file constructs the binder client" \
  || bad "A0 $n files construct a TextToolsClient"
hits=$(grep -rlE 'openrouter|AiRouter|PREF_AI_TOKEN' "$APP/app/src" 2>/dev/null)
[ -z "$hits" ] && ok "A0 mail still reaches no AI provider credential" || bad "A0 mail reaches the credential: $hits"
# the binder method was APPENDED, never inserted: transaction codes are the wire format
python3 - "$LIBS/libs/text-tools/src/main/aidl/com/diegonmarcos/superapp/texttools/ITextTools.aidl" <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
methods = re.findall(r'^\s+(?:String\[\]|String|List<String>)\s+(\w+)\(', src, re.M)
want = ['enhance', 'translate', 'enhanceProviderLabel', 'translateLanguages', 'summarise']
if methods != want:
    print(f"  FAIL: A0 AIDL method order is {methods}, expected {want} -- summarise must be APPENDED")
    sys.exit(1)
print("  ok: A0 summarise was appended, so no existing transaction code moved")
PY
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))

# ── R1 the reply default is reply-all, and single reply is still reachable ──
python3 - "$SCREEN" <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
# The toolbar IconButton (not a DropdownMenuItem) that starts a reply.
row = re.search(r'IconButton\(onClick = \{ onReply\("(\w+)", replyTargetId, accountId\) \}\)', src)
if not row:
    print("  FAIL: R1 no reply action on the toolbar at all")
    sys.exit(1)
if row.group(1) != "replyAll":
    print(f'  FAIL: R1 the toolbar reply action is "{row.group(1)}", not "replyAll"')
    sys.exit(1)
print("  ok: R1 the toolbar's reply action resolves to reply-all")
# ...with the icon that conventionally means it
seg = src[row.start():row.start() + 400]
if "Icons.AutoMirrored.Filled.ReplyAll" not in seg:
    print("  FAIL: R1 the reply-all action does not carry the double-arrow icon")
    sys.exit(1)
print("  ok: R1 it carries the double-arrow icon")
# ...and plain reply is still one tap away, in the menu
if 'onClick = { menuOpen = false; onReply("reply", replyTargetId, accountId) }' not in src:
    print("  FAIL: R1 plain single reply is not reachable from the overflow menu")
    sys.exit(1)
print("  ok: R1 plain single reply is still reachable in the overflow")
PY
[ $? -eq 0 ] && PASS=$((PASS+3)) || FAIL=$((FAIL+1))
# The safety the default demands: a reply-all shows every recipient before a word is typed.
has "$UI/compose/ComposeViewModel.kt" 'showAllRecipients = true,' "R1 a reply-all asks for every recipient to be shown"
has "$UI/compose/ComposeScreen.kt" 'neverCollapse = showAllRecipients,' "R1 the To field honours it"
has "$UI/compose/ComposeScreen.kt" 'chips.size > 2 && !neverCollapse' "R1 ...so the chips are not folded into a +N"

# ── A1 the action row is what it claims to be, and the count is stated ──
python3 - "$SCREEN" <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
start = src.index("val resumable = messages.firstOrNull()?.body != null")
end = src.index("var menuOpen by remember", start)
row = src[start:end]
# Two spellings, because the row draws from two places now. A Text tool takes its glyph from the
# TextTool enum, which is where "which surface offers this" is also declared -- so the row names
# the ACTION (Resume) and the enum owns the glyph, instead of this test pinning a second copy of a
# choice it does not make. Everything else is still a literal Icons.Filled.X.
icons = re.findall(r'Icons\.(?:AutoMirrored\.)?Filled\.(\w+)|TextTool\.(\w+)\.icon', row)
# star/unstar is one action drawn two ways; likewise delete-forever, which is no longer on the row.
seen, order = set(), []
for literal, tool in icons:
    i = literal or tool.capitalize()
    key = {"StarBorder": "Star"}.get(i, i)
    if key not in seen:
        seen.add(key)
        order.append(key)
want = ["Resume", "Star", "Label", "Unsubscribe", "ReplyAll"]
if order != want:
    print(f"  FAIL: A1 the action row is {order}, expected {want}")
    sys.exit(1)
print(f"  ok: A1 the row is {' '.join(order)} + overflow = {len(order) + 1} at most")
if len(order) + 1 > 6:
    print(f"  FAIL: A1 {len(order) + 1} actions do not fit a 360dp bar (6 slots)")
    sys.exit(1)
print("  ok: A1 it fits the six slots a 360dp bar has")
# Archive and Delete must still EXIST, in the overflow -- moved, never dropped.
for entry in ("R.string.message_archive", "R.string.message_delete"):
    if f"Text(stringResource({entry}))" not in src and entry not in src:
        print(f"  FAIL: A1 {entry} was dropped rather than moved to the overflow")
        sys.exit(1)
print("  ok: A1 Archive and Delete moved to the overflow rather than being dropped")
PY
[ $? -eq 0 ] && PASS=$((PASS+3)) || FAIL=$((FAIL+1))

# ── the sender surface shows what exists and omits what does not ──
has "$META" 'fun messageMetadata' "S1 the metadata rows are one decision"
has "$META" 'value?.trim()?.takeIf { it.isNotEmpty() }?.let' "S1 a blank field produces NO row"
has "$META" 'lastOrNull { it.name.trim().lowercase() == name }' \
  "S1 the LAST Authentication-Results is read -- the first is an outer relay's own claim"
has "$META" 'fun formatAddress' "S1 an address is copied in name-addr form"
has "$META" 'needsQuoting' "S1 a display name holding a comma is quoted, not split into two recipients"
has "$SCREEN" 'clipboard.setText(AnnotatedString(formatAddress(it)))' "S1 long-press copies that form"
has "$SCREEN" 'onLongClick = copyTarget?.let' "S1 the long-press is on the sender row"

# every R.string the new files name must exist
miss=""
for s in $(grep -ho 'R\.string\.[a-z_0-9]*' "$UI/message/LabelSheet.kt" "$UI/message/ResumeBox.kt" \
             "$UI/settings/TextToolsSection.kt" | sed 's/R.string.//' | sort -u); do
  grep -q "name=\"$s\"" "$STR" || miss="$miss $s"
done
[ -z "$miss" ] && ok "S1 every referenced R.string exists" || bad "S1 missing strings:$miss"

echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" -eq 0 ]
