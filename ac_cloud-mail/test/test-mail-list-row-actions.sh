#!/usr/bin/env bash
# cloud-mail message-list row actions (#514): static proof that the row's third line of icons
# REACHES THE SCREEN and acts on the MESSAGE -- WITHOUT a gradle build (this runner cannot build)
# and without an APK on a device. Same shape as test-mail-reader-actions.sh.
#
# WHY THIS FILE EXISTS. #500 shipped the row and a test named after it,
# `EmailListItemPreviewAndActionsRowTest`, which reads the body of `private fun ListRowActions` as
# TEXT and asserts the order of its children. That test passes whether or not anything ever calls
# ListRowActions, and whether or not anything ever calls EmailListItem: deleting the call site in
# the real row leaves all three of its assertions green. A test of a composable's insides is not a
# test that the composable is on screen. So the assertions below are about the CHAIN and about what
# the buttons are handed -- never about what ListRowActions contains, which is already covered.
#
#   L1  the real row composes the actions row, UNCONDITIONALLY, at the row column's own level
#   L2  the real list screen composes the real row: items{} -> emailRow -> SwipeableEmailRow ->
#       EmailListItem, every hop present, so no link can be cut while the suite stays green
#   L3  an unfolded conversation child goes through the SAME row, not a second one
#   L4  the two actions act on the MESSAGE, never on the row -- the #514 defect itself
#   L5  every list that CAN fetch a message passes the loader; the one that cannot passes nothing
#   L6  the loader is the reader's own door, and opening it does not mark the mail read
#   L7  the premise: the list mapper writes no body, which is why L4 and L5 have to exist
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
UI="$APP/app/src/main/kotlin/app/sterna/ui"
ROW="$UI/components/EmailListItem.kt"
LIST="$UI/inbox/InboxScreen.kt"
VM="$UI/inbox/InboxViewModel.kt"
SEARCH="$UI/search/SearchScreen.kt"
MAPPER="$APP/core/data/src/main/kotlin/app/sterna/core/data/mail/EmailMapper.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }
lacks() { grep -q -- "$2" "$1" && bad "$3 ($1)" || ok "$3"; }

echo "== cloud-mail list row actions: the chain to the screen, and what the buttons are handed =="

# ── L1 the real row composes the actions row, and not under a condition ──
# The body of `fun EmailListItem(` only -- a call that lives in a @Preview, or in a sibling
# composable nothing renders, is exactly the shape #514 was reported as.
out=$(python3 - "$ROW" <<'PY'
import sys
src = open(sys.argv[1], encoding='utf-8').read().split('\n')
fails = 0
def ok(m): print(f"  ok: {m}")
def bad(m):
    global fails
    fails += 1
    print(f"  FAIL: {m}")

def top_level_body(signature):
    """A top-level declaration's lines: its signature line to its own column-0 closing brace."""
    start = next((i for i, l in enumerate(src) if l.startswith(signature)), None)
    if start is None:
        return None
    end = next((i for i, l in enumerate(src) if i > start and l == '}'), None)
    return src[start:end + 1] if end is not None else None

row = top_level_body('fun EmailListItem(')
if row is None:
    bad('L1 there is no top-level `fun EmailListItem(` in the row file at all')
    sys.exit(1)

calls = [l for l in row if l.strip().startswith('ListRowActions(')]
if len(calls) == 1:
    ok('L1 the real row composes the actions row exactly once')
else:
    bad(f'L1 `fun EmailListItem` composes the actions row {len(calls)} times, expected exactly 1 '
        '-- a row whose icons are declared but never called is what #514 reported')

# UNCONDITIONAL, and at the weighted Column's own level (12 spaces). Nested one level deeper is an
# `if` around it, which is how "the icons are there on my machine" and "nothing on the phone" are
# both true at once.
if any(l == '            ListRowActions(' for l in row):
    ok('L1 the call sits at the row column\'s own level, under no condition')
else:
    depths = sorted({len(l) - len(l.lstrip()) for l in calls})
    bad(f'L1 the actions row is nested under something (indents {depths}, expected 12) '
        '-- gate it and the row stops drawing while every assertion about its contents stays green')

# The defect axis of #514 itself: what the two actions are HANDED. `email` is the ROW, which carries
# no body; `rowMessage(...)` is the message. A call taking the row is the bug, spelled out.
actions = top_level_body('private fun ListRowActions(')
if actions is None:
    bad('L4 there is no top-level `private fun ListRowActions(`')
else:
    body = '\n'.join(actions)
    for needle, why in (
        ('receivedTextToolSource(message)', 'L4 Resume sends the MESSAGE, not the row'),
        ('copyVerificationCodeOrSayNone(clipboard, context, rowMessage(email, onLoadMessage))',
         'L4 Copy Code reads the MESSAGE, not the row'),
    ):
        ok(why) if needle in body else bad(f'{why} -- missing {needle!r}')
    for needle, why in (
        ('receivedTextToolSource(email)',
         'L4 Resume is not handed the row (its body is null, so this summarises Email.preview)'),
        ('copyVerificationCodeOrSayNone(clipboard, context, email)',
         'L4 Copy Code is not handed the row (it would hunt a code in Email.preview)'),
    ):
        bad(f'{why} -- found {needle!r}') if needle in body else ok(why)
sys.exit(1 if fails else 0)
PY
)
rc=$?
echo "$out"
PASS=$((PASS + $(printf '%s\n' "$out" | grep -c '^  ok:')))
nfail=$(printf '%s\n' "$out" | grep -c '^  FAIL:')
FAIL=$((FAIL + nfail))
[ "$rc" -ne 0 ] && [ "$nfail" -eq 0 ] && bad "L1/L4 the row checker crashed (exit $rc)"

# ── L2 the real list screen composes the real row, hop by hop ──
# Each of these is a link that #464/#472-class rewrites have cut before while the leaf composable
# and its own test survived intact. Naming every hop is what makes a cut one loud red line.
has "$LIST" 'val emailRow: @Composable' "L2 the list declares ONE row renderer"
n=$(grep -cE '^\s+emailRow\($|^\s+emailRow\(InboxRow\(' "$LIST")
[ "$n" -ge 2 ] && ok "L2 the renderer is CALLED from the list bodies ($n call sites)" \
  || bad "L2 the row renderer is declared and called $n times -- a renderer nothing calls draws nothing"
has "$LIST" 'SwipeableEmailRow(' "L2 the renderer composes the swipeable row"
has "$LIST" 'EmailListItem(' "L2 the swipeable row composes the list item"
# ...and the browse list really does page rows into a LazyColumn that calls it.
python3 - "$LIST" <<'PY'
import sys
src = open(sys.argv[1], encoding='utf-8').read()
# The paged browse list: LazyColumn -> items(count = listRows.itemCount ...) -> emailRow(
i = src.find('items(\n                                count = listRows.itemCount')
if i < 0:
    print('  FAIL: L2 the paged browse list no longer feeds listRows.itemCount into items{}')
    sys.exit(1)
if 'emailRow(' not in src[i:i + 1600]:
    print('  FAIL: L2 the paged items{} block does not call emailRow -- the list draws something else')
    sys.exit(1)
print('  ok: L2 the paged LazyColumn item calls the row renderer')
PY
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))

# ── L3 an unfolded child is the same row, not a second implementation ──
has "$LIST" 'private fun ThreadChildren(' "L3 unfolded children have their own composable"
n=$(grep -c 'SwipeableEmailRow(' "$LIST")
[ "$n" = 3 ] && ok "L3 both row sites (top level + child) and the one declaration, and no fourth" \
  || bad "L3 SwipeableEmailRow appears $n times, expected 3 (1 declaration + 2 call sites)"

# ── L5 who passes the loader, and who honestly does not ──
has "$ROW" 'onLoadMessage: (suspend (Email) -> Email)? = null,' "L5 the row takes a message loader"
n=$(grep -c 'onLoadMessage = viewModel::messageWithBody' "$LIST")
[ "$n" = 2 ] && ok "L5 both list sites (rows + unfolded children) pass the loader" \
  || bad "L5 $n of 2 list sites pass onLoadMessage -- a row without it answers about the preview"
has "$LIST" 'onLoadMessage = onLoadMessage,' "L5 the child row is handed the same loader"
# Search results come from the FTS table and are not a message the list can open; passing nothing is
# the honest answer there, and is asserted so a later "fix" cannot quietly wire a wrong one.
lacks "$SEARCH" 'onLoadMessage' "L5 the search list passes no loader, as it passes no chips"

# ── L6 the loader is the reader's own door, and does not mark the mail read ──
has "$VM" 'suspend fun messageWithBody(email: Email): Email' "L6 the list can fetch a message"
has "$VM" 'repo.openMessage(credentials, email.id, markRead = false).email' \
  "L6 it opens the message the reader's way, WITHOUT marking it read"
has "$VM" 'val credentials = credentialsFor(email) ?: return email' \
  "L6 a row of another account is fetched on ITS account (unified inbox)"

# ── L7 the premise: a list row carries no body, which is the whole reason for L4-L6 ──
# If a future change starts mapping a body into the list row, this fires -- and the fix is then to
# drop the loader, not to keep both. The assertion is on the ONE mapper every list row comes from.
python3 - "$MAPPER" <<'PY'
import sys
src = open(sys.argv[1], encoding='utf-8').read()
start = src.index('internal fun EmailEntity.toEmail(): Email = Email(')
body = src[start:src.index('\n)\n', start)]
code = '\n'.join(l for l in body.split('\n') if not l.strip().startswith(('//', '*', '/*')))
carried = [f for f in ('bodyValues', 'textBody', 'htmlBody') if f + ' =' in code]
if carried:
    print(f'  FAIL: L7 the list mapper now carries {carried} -- a row HAS a body, so the row\'s '
          'actions no longer need a loader and messageWithBody should go, not sit beside it')
    sys.exit(1)
print('  ok: L7 the list mapper carries no body, so a row genuinely cannot answer for the message')
PY
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))

echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" -eq 0 ]
