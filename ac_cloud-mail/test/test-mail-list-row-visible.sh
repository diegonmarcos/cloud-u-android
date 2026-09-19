#!/usr/bin/env bash
# cloud-mail message-list row (#518): static proof that what the row composes can be SEEN.
#
# WHY THIS FILE EXISTS. #514 proved the row's icons and chips are CALLED, all the way from the
# LazyColumn (test-mail-list-row-actions.sh, test-mail-attachment-chips.sh) -- and the owner was
# still looking at an empty row, because being composed is not being visible:
#
#   - the list's Scaffold sits on a PALETTE colour (#472). That is no ColorScheme role, so material3
#     1.3.1's `contentColorFor` answers Unspecified and falls back to LocalContentColor, whose
#     default is Color.Black (ContentColor.kt) -- nothing above the list is a Surface. The two
#     IconButtons of #500 were the only untinted things on the row: black, on a black card.
#   - #500 moved the chips from their own line (#196: "taller rows to fit them") to the tail of a
#     Row, behind 2 x 48 dp of buttons and the bar: about a third of a phone's width, clipped.
#
# Every check below is about the REAL list screen's chain, not about a composable called alone.
#   V1  ICONS: the row states its content colour around the actions, from the row's own ink, and
#       every Scaffold/pane painted with a palette colour states what is drawn on it
#   V2  CHIPS: on a row that has attachments the chips sit in a layout that can WRAP to a full line,
#       and the real list hands the row both the parts' tap handler and nothing that gates them
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
UI="$APP/app/src/main/kotlin/app/sterna/ui"
python3 - "$UI/components/EmailListItem.kt" "$UI/inbox/InboxScreen.kt" <<'PY'
import re, sys
row_src = open(sys.argv[1], encoding='utf-8').read().split('\n')
list_src = open(sys.argv[2], encoding='utf-8').read().split('\n')
fails = 0
def check(cond, msg):
    global fails
    if cond: print(f"  ok: {msg}")
    else:
        fails += 1
        print(f"  FAIL: {msg}")

def body(src, signature):
    start = next((i for i, l in enumerate(src) if l.startswith(signature)), None)
    if start is None: return ''
    end = next(i for i, l in enumerate(src) if i > start and l == '}')
    return '\n'.join(src[start:end + 1])

row = body(row_src, 'fun EmailListItem(')
actions = body(row_src, 'private fun ListRowActions(')
check(row and actions, "V0 EmailListItem and ListRowActions are top-level declarations here")

print("== V1 the icon row can be seen ==")
provider = actions.find('CompositionLocalProvider(LocalContentColor provides ink)')
first_button = actions.find('IconButton(')
check(0 <= provider < first_button,
      "V1 ListRowActions provides LocalContentColor BEFORE its first IconButton -- without it the "
      "icons inherit material3's default Color.Black and vanish on the dark card")
check(re.search(r'ListRowActions\((?:[^()]|\([^()]*\))*\bink = listTextInk\.color,', row) is not None,
      "V1 the real row hands the actions its OWN ink (listTextInk.color), not a literal")
check('tint = Color.' not in actions and 'Color(0x' not in actions,
      "V1 no literal colour in the actions row -- the palette decides (#472)")
for i, line in enumerate(list_src):
    if 'containerColor = LocalMailListPalette' in line:
        window = '\n'.join(list_src[i:i + 8])
        check(re.search(r'^\s*contentColor = LocalMailListPalette\.current\.', window, re.M) is not None,
              f"V1 InboxScreen.kt:{i + 1} a Scaffold on a palette colour states its contentColor "
              "(contentColorFor cannot infer one for a non-scheme colour)")
check(any('containerColor = LocalMailListPalette' in l for l in list_src),
      "V1 the list Scaffold is still where this check looks for it")

print("== V2 the attachment chips have room ==")
chips = actions.find('AttachmentChips(')
flow = actions.find('FlowRow {')
check(0 <= flow < first_button < chips,
      "V2 buttons and chips share ONE FlowRow, so chips that do not fit beside the buttons wrap to "
      "a full-width line instead of being clipped to what two 48 dp buttons leave")
check(re.search(r'^\s*Row\(', actions, re.M) is None,
      "V2 no plain Row in ListRowActions -- a Row measures the chips last, into the leftovers")
check('if (onOpenAttachment != null && attachmentParts.isNotEmpty())' in actions,
      "V2 files are the ONLY gate on the chips")
call = next((i for i, l in enumerate(list_src) if l.strip() == 'EmailListItem('), None)
call_body = '\n'.join(list_src[call:call + 30]) if call is not None else ''
check('onOpenAttachment = onOpenAttachment,' in call_body,
      "V2 the real list screen's EmailListItem call passes the chip tap handler (null draws none)")
check(sum(1 for l in list_src if re.search(r'onOpenAttachment = \{ (part|child, part) -> ', l)) >= 3,
      "V2 top-level rows, conversation parents and unfolded children all supply a real handler")

print(f"\n{'PASS' if fails == 0 else 'FAIL'}: {fails} failed")
sys.exit(1 if fails else 0)
PY
