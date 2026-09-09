#!/usr/bin/env bash
# cloud-mail folder sidebar: static proof that folder names stop wrapping, WITHOUT a
# gradle build (this runner cannot build) and without rendering a screen.
#
#   S1  the two levers are declared where the screen and this test both read them
#   S2  every drawer row draws its label through DrawerLabel at the declared size
#   S3  nothing in the sidebar truncates - no maxLines/ellipsis on a folder label
#   S4  every REAL folder label fits the label budget at the declared point size,
#       except the ones drawer-folder-labels.json::known_wrapping names - and that
#       list is asserted BOTH ways, so a stale entry fails as loudly as a regression
#   S5  the chosen width stays within Material 3's modal-drawer maximum
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
UI="$APP/app/src/main/kotlin/app/sterna/ui"
PANE="$UI/PaneLayout.kt"
INBOX="$UI/inbox/InboxScreen.kt"
DATA="$APP/test/drawer-folder-labels.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }

echo "== cloud-mail folder sidebar: width, point size, no truncation, real names fit =="

# S1 the levers are declared, and declared ONCE
for c in DRAWER_SHEET_WIDTH_DP FOLDER_LABEL_TEXT_SIZE_SP DRAWER_FOLDER_ROW_CHROME_DP; do
  n=$(grep -c "^const val $c = " "$PANE")
  [ "$n" = 1 ] && ok "S1 $c declared once in PaneLayout.kt" || bad "S1 $c declared $n times"
done
has "$PANE" 'fun folderLabelBudgetDp(' "S1 the label budget is computed, not restated"

# S2 every drawer row goes through DrawerLabel, and DrawerLabel uses the declared size
has "$INBOX" 'private fun DrawerLabel(text: String)' "S2 DrawerLabel exists"
has "$INBOX" 'fontSize = FOLDER_LABEL_TEXT_SIZE_SP.sp' "S2 DrawerLabel uses the declared point size"
# Scoped to NavigationDrawerItem blocks: `label = { Text(...) }` also names an
# OutlinedTextField in the new/rename-folder dialogs, which is not a sidebar row.
nav=$(grep -c 'NavigationDrawerItem(' "$INBOX")
rows=$(awk '/NavigationDrawerItem\(/{n=25} n&&/label = \{ DrawerLabel\(/{c++} n{n--} END{print c+0}' "$INBOX")
[ "$nav" -ge 5 ] && [ "$rows" = "$nav" ] \
  && ok "S2 all $nav NavigationDrawerItem rows draw through DrawerLabel" \
  || bad "S2 $rows of $nav NavigationDrawerItem rows use DrawerLabel"
strayed=$(awk '/NavigationDrawerItem\(/{n=25} n&&/label = \{ Text\(/{c++} n{n--} END{print c+0}' "$INBOX")
[ "$strayed" = 0 ] && ok "S2 no drawer row bypasses DrawerLabel" || bad "S2 $strayed drawer rows still call Text() directly"

# S3 no truncation anywhere in DrawerLabel
body=$(awk '/private fun DrawerLabel\(text: String\)/,/^\)$/' "$INBOX")
case "$body" in
  *maxLines*|*TextOverflow*|*Ellipsis*) bad "S3 DrawerLabel truncates - an unreadable name is worse than a wrapped one" ;;
  *) ok "S3 DrawerLabel neither clips nor ellipsises" ;;
esac

# S4/S5 geometry against the real folder names
python3 - "$PANE" "$DATA" <<'PY'
import json, re, sys
pane, data = open(sys.argv[1], encoding='utf-8').read(), json.load(open(sys.argv[2], encoding='utf-8'))
def const(name):
    m = re.search(rf'^const val {name} = (\d+)$', pane, re.M)
    assert m, f'{name} not found in PaneLayout.kt'
    return int(m.group(1))
width, size, chrome = const('DRAWER_SHEET_WIDTH_DP'), const('FOLDER_LABEL_TEXT_SIZE_SP'), const('DRAWER_FOLDER_ROW_CHROME_DP')
budget = width - chrome
a = data['advance_em']
def adv(ch):
    o = ord(ch)
    if o == 0xFE0F: return 0.0
    if o > 0x2500:  return a['emoji']['em']
    if ch in a['narrow']['chars']: return a['narrow']['em']
    if ch in a['wide']['chars']:   return a['wide']['em']
    if ch.isupper() or ch.isdigit(): return a['upper_or_digit']['em']
    return a['default']['em']
def dp(s): return sum(adv(c) for c in s) * size
labels = [f"{f['name']}  ({f['unseen']})" if f['unseen'] else f['name'] for f in data['synced']] + data['builtin']
known = set(data['known_wrapping'])
fails = 0
def ok_(m):  print(f"  ok: {m}")
def bad_(m):
    global fails; fails += 1; print(f"  FAIL: {m}")

print(f"  .. drawer {width}dp - chrome {chrome}dp = {budget}dp for the label, at {size}sp")
wrapping = {l for l in labels if dp(l) > budget}
for l in sorted(wrapping - known, key=dp, reverse=True):
    bad_(f"S4 wraps and is not declared: {dp(l):.0f}dp > {budget}dp  {l!r}")
for l in sorted(known - wrapping):
    bad_(f"S4 declared as wrapping but fits at {dp(l):.0f}dp - known_wrapping is stale: {l!r}")
for l in sorted(known - set(labels)):
    bad_(f"S4 known_wrapping names a label that is not in the data at all: {l!r}")
if not (wrapping - known) and not (known - wrapping):
    ok_(f"S4 {len(labels) - len(wrapping)}/{len(labels)} real labels fit; the {len(wrapping)} that do not are exactly the declared ones")
widest = max(labels, key=dp)
ok_(f"S4 widest real label is {dp(widest):.0f}dp: {widest!r}")
# S5 Material 3 DrawerDefaults.MaximumDrawerWidth
if width <= 360: ok_(f"S5 {width}dp is within Material 3's 360dp modal-drawer maximum")
else: bad_(f"S5 {width}dp exceeds Material 3's 360dp modal-drawer maximum - a phone loses its scrim")
sys.exit(1 if fails else 0)
PY
if [ $? -eq 0 ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi

echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" -eq 0 ]
