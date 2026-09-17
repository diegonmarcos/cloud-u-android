#!/usr/bin/env bash
# cloud-mail message list cards (#472): static proof that a mail row is its OWN rectangle — a black
# card on a dark-grey pane with unread WHITE and read light-grey ink — and that the reading pane
# carries no redundant "Show images" text label any more. Same shape as test-mail-reader-actions.sh:
# no gradle build, no device; every assertion reads the sources.
#
#   C1  unread and read TEXT INKS are different colours in BOTH schemes (mutation: equal → red)
#   C2  the CARD and the PANE behind it are different colours in BOTH schemes (mutation: equal → red)
#   C3  a row's text ink is ONE decision routed through the palette, and the four message lines use it
#   C3b the BOLD weight rides that SAME decision on sender/time/subject; no inline 'if (unread)'
#       weight branch exists beside it (mutation: flip or inline → red, #478)
#   C4  a row is lifted off its neighbours by the declared gutters and corner (mutation: zero → red)
#   C5  the list panes (inbox and search) paint the declared pane colour behind the cards
#   C6  the card colour reaches the row through the one rowBackground decision, as the palette's card
#   C7  the reading pane's only "show images" affordance is the ICON (and the overflow NAMES it) —
#       the redundant text strip is gone (mutation: reintroduce it → red)
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
UI="$APP/app/src/main/kotlin/app/sterna/ui"
THEME="$UI/theme/MailList.kt"
ROW="$UI/components/EmailListItem.kt"
INBOX="$UI/inbox/InboxScreen.kt"
SEARCH="$UI/search/SearchScreen.kt"
SCREEN="$UI/message/MessageScreen.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }
lacks() { grep -q -- "$2" "$1" && bad "$3 ($1)" || ok "$3"; }

echo "== cloud-mail message list: cards, panes, inks, no text show-images =="

# ── C1 + C2 the palette actually separates: ink vs ink, card vs pane ──
python3 - "$THEME" <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
def block(name):
    start = src.index("internal val " + name)
    end = src.find("\ninternal val ", start + 1)
    if end == -1:
        end = len(src)
    return src[start:end]
def color(b, key):
    m = re.search(r"\b" + key + r"\s*=\s*Color\((0x[0-9A-Fa-f]+)\)", b)
    return m.group(1).upper() if m else None
fails = 0
for name, why in (("ArcticMailListPalette", "light scheme"), ("PelagicMailListPalette", "dark scheme")):
    b = block(name)
    u, r, p, c = (color(b, k) for k in ("unreadText", "readText", "pane", "card"))
    if not (u and r and p and c):
        print(f"  FAIL: C1/C2 {name} does not declare all four colours (u={u}, r={r}, p={p}, c={c})"); fails += 1; continue
    if u == r:
        print(f"  FAIL: C1 {why} unread and read inks are the SAME colour {u} — read/unread render identically"); fails += 1
    else:
        print(f"  ok: C1 {why} unread {u} != read {r}")
    if p == c:
        print(f"  FAIL: C2 {why} card and pane are the SAME colour {c} — the cards disappear into the pane"); fails += 1
    else:
        print(f"  ok: C2 {why} card {c} on pane {p}")
sys.exit(1 if fails else 0)
PY
rc=$?
[ $rc -eq 0 ] || FAIL=$((FAIL+rc))

# ── C3 one text-ink decision (colour AND weight #478), used by the four message lines ──
has "$ROW" 'val listTextInk = mailListTextInk(unread, mailPalette)' \
  "C3 the row's ink is ONE decision through the palette"
n=$(grep -c 'color = listTextInk.color,' "$ROW")
[ "$n" -eq 4 ] && ok "C3 the sender, time, subject and preview all wear the decision ($n lines)" \
  || bad "C3 expected exactly 4 'color = listTextInk.color,' lines, found $n"
w=$(grep -c 'fontWeight = listTextInk.weight,' "$ROW")
[ "$w" -eq 3 ] && ok "C3b the sender, time and subject wear the SAME decision's bold weight ($w lines)" \
  || bad "C3b expected exactly 3 'fontWeight = listTextInk.weight,' lines, found $w"
i=$(grep -c 'fontWeight = if (unread)' "$ROW")
[ "$i" -eq 0 ] && ok "C3b no inline 'fontWeight = if (unread)' branch beside the ink decision" \
  || bad "C3b found $i inline read/unread weight branches — a second declaration (#478)"

# ── C4 the card's separation: gutters and corner, from the ONE dimension declaration ──
has "$ROW" '.padding(horizontal = MailListDimens.gutterH, vertical = MailListDimens.gutterV)' \
  "C4 the row is inset by the declared gutters"
has "$ROW" '.clip(MailListDimens.shape)' "C4 the row is clipped to the declared corner"
python3 - "$THEME" <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
dims = src[src.index("internal object MailListDimens"):src.index("\n}", src.index("internal object MailListDimens"))]
fails = 0
for key in ("gutterH", "gutterV", "corner"):
    m = re.search(r"\b" + key + r"\s*=\s*([0-9.]+)\.dp", dims)
    if not m or float(m.group(1)) <= 0:
        print(f"  FAIL: C4 MailListDimens.{key} is {'missing' if not m else m.group(0)} — a zero gutter is a flat list again"); fails += 1
    else:
        print(f"  ok: C4 MailListDimens.{key} = {m.group(1)}dp")
sys.exit(1 if fails else 0)
PY
rc=$?
[ $rc -eq 0 ] || FAIL=$((FAIL+rc))

# ── C5 the panes wear the declared pane colour ──
has "$INBOX" '.background(LocalMailListPalette.current.pane)' \
  "C5 the inbox list pane paints the palette's pane"
has "$SEARCH" '.background(LocalMailListPalette.current.pane)' \
  "C5 the search results pane paints the palette's pane"

# ── C6 the card colour reaches the row through the ONE decision ──
python3 - "$ROW" <<'PY'
import sys
src = open(sys.argv[1], encoding='utf-8').read()
start = src.index("val rowColor = rowBackground(")
end = src.index(")", start)
call = src[start:end + 1]
ok = "card = mailPalette.card," in call
print("  ok: C6 rowBackground is handed the palette's card" if ok
      else "  FAIL: C6 rowBackground is not handed the palette's card — a literal black elsewhere is a second declaration")
sys.exit(0 if ok else 1)
PY
rc=$?
[ $rc -eq 0 ] || FAIL=$((FAIL+rc))

# ── C7 reading pane: no text strip; the icon is the affordance, the overflow names it ──
lacks "$SCREEN" 'ImagesStrip(' "C7 the redundant text 'Show images' strip is gone"
lacks "$SCREEN" 'imagesStrip' "C7 the strip's wiring is gone with it"
has "$SCREEN" 'contentDescription = stringResource(R.string.message_show_images)' \
  "C7 the reading row still draws Show images as an ICON"
n=$(grep -o 'R\.string\.message_show_images' "$SCREEN" | wc -l)
[ "$n" -eq 2 ] && ok "C7 the two remaining uses are the icon's description and the overflow's name ($n)" \
  || bad "C7 expected exactly 2 uses of R.string.message_show_images, found $n"

echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" -eq 0 ]