#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ The toolbar row pages by a WHOLE SCREEN of icons, and the page size is   ║
# ║ measured rather than declared (#301, #319, #150)                        ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS LIVES HERE. The same contract is asserted by
# aa_cloud-superapp/test/test-keyboard-toolbar-paging.sh — and that tester gates
# the SUPERAPP's release, not this one. cloud-keyboard ships libs:keyboard as its
# own APK, so until this file existed the toolbar could regress and every gate on
# THIS app stayed green. build.json::tests._doc already records that trap for the
# app as a whole; this is the toolbar's share of it.
#
# WHAT IT PINS, and every one of these has actually shipped broken:
#
#   A1  the page size is DERIVED from live measurements — the viewport divided by
#       a real key's width. A literal "11 per page" would be right on exactly one
#       screen and silently wrong on every other width, rotation and icon size.
#   A2  every toolbar key carries its OWN layout params. ONE SHARED
#       LinearLayout.LayoutParams object is the four-icons-instead-of-eleven bug
#       (#319, fixed in 59527a722): PagedToolbarScrollView writes a left margin
#       into the params of the one key that starts its own page, and while all 22
#       keys held the same object every key got that margin.
#   A3  the geometry, computed from the SHIPPED key width and the REAL default
#       row, laid out the way onLayout does — and laid out through whichever
#       params model A2 found in the source, so the pre-fix state produces the
#       decaying row it really produced instead of the design's split.
#   A4  exactly one place writes leftMargin on a toolbar key. Two writers cannot
#       converge on a page boundary.
#
# NO RIPGREP, DELIBERATELY: testers in this repository have passed on its absence
# rather than on their assertions. grep, awk and python3 only — all three are
# build.json::tests.shell.requires, so a missing one is fatal at the preflight
# instead of quietly agreeing with everything here. A missing SOURCE file is
# fatal here too, for the same reason: every assertion below would read an empty
# file and pass.
set -uo pipefail

# CLOUD_ANDROID_ROOT is the test engine's own variable for "the checkout to read",
# honoured so this tester can be pointed at a scratch tree — which is how its
# mutation proof is taken (flip A2's getter back to a shared instance in a copy,
# watch A2 and A3 both go red).
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
K="$ROOT/ab_cloud-libs-shared/libs/keyboard/src/main"
SV="$K/java/helium314/keyboard/latin/suggestions/SuggestionStripView.kt"
PV="$K/java/helium314/keyboard/latin/suggestions/PagedToolbarScrollView.kt"
TU="$K/java/helium314/keyboard/latin/utils/ToolbarUtils.kt"
CFG="$K/res/values/config.xml"

FAILURES=0
pass() { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

for f in "$SV" "$PV" "$TU" "$CFG"; do
    [ -f "$f" ] || { printf 'FAIL   %s is missing — every assertion below would read an empty file and pass\n' "$f"; exit 1; }
done

echo "== cloud-keyboard toolbar: a full page of icons, measured not declared =="

# ── A1 the page size is measured ────────────────────────────────────────────
# The division IS the derivation: as many whole icons as the viewport can show.
if grep -q 'viewportWidth / icon' "$PV"; then
    pass "A1 page size divides the measured viewport by a measured key width"
else
    fail "A1 PagedToolbarScrollView no longer derives its page from measurements ($PV)"
fi
# A page size sitting in the paging code as a number is the failure A1 exists to
# catch: it cannot be right on two screen widths at once.
if grep -nE '(perPage|keysPerPage|PAGE_SIZE|pageSize)[[:space:]]*=[[:space:]]*[0-9]+' "$PV"; then
    fail "A1 a hardcoded keys-per-page constant in the paging view ($PV)"
else
    pass "A1 no hardcoded keys-per-page constant in the paging view"
fi

# ── A2 one layout-params object per key ─────────────────────────────────────
# The getter is what makes each `= toolbarKeyLayoutParams` a FRESH instance; a
# plain initialiser builds one object and hands it to all 22 keys.
if grep -qE '^[[:space:]]*private val toolbarKeyLayoutParams$' "$SV"; then
    pass "A2 toolbarKeyLayoutParams is a per-use getter"
else
    fail "A2 toolbarKeyLayoutParams is not a getter — the row shares one params object ($SV)"
fi
if grep -q 'private val toolbarKeyLayoutParams = LinearLayout.LayoutParams' "$SV"; then
    fail "A2 a single shared LayoutParams instance for the whole row ($SV)"
else
    pass "A2 no single shared LayoutParams instance for the whole row"
fi
if grep -q 'layoutParams = original.layoutParams' "$SV"; then
    fail "A2 the second-row copy shares the original key's params object ($SV)"
else
    pass "A2 the second-row copy takes its own params"
fi

# ── A4 one writer of the page-start margin ──────────────────────────────────
writers=$(grep -c 'leftMargin = ' "$SV" "$PV" | awk -F: '{ sum += $2 } END { print sum + 0 }')
if [ "$writers" = 1 ]; then
    pass "A4 exactly one leftMargin writer"
else
    fail "A4 $writers leftMargin writers on toolbar keys — margins from two places cannot converge"
fi

# ── A3 the arithmetic, from the shipped width and the real default row ──────
if python3 - "$CFG" "$TU" "$SV" <<'PYEOF'
import re, sys

cfg, tu, sv = (open(p, encoding='utf-8').read() for p in sys.argv[1:4])

# The SHIPPED key width, from the resource the keyboard actually builds keys to.
m = re.search(r'"config_suggestions_strip_edge_key_width">(\d+)dp', cfg)
assert m, 'config_suggestions_strip_edge_key_width is gone — the key width is not 36dp any more'
key_dp = int(m.group(1))

# The REAL default first row: the listOf block that holds the row's own keys,
# chosen by content rather than by position, so the clipboard row below it can
# never be read by accident.
#
# The block ends at a ')' ALONE on its own line, not at the first ')' in the
# text. The row's comment says "(toolbarKeysOnOwnPage)", and a non-greedy match
# to the first ')' stops there — truncating the list one key early, dropping
# VAULT, and reporting a missing third page as a layout defect. That false red
# is what this anchor exists to prevent.
blocks = re.findall(r'val default = listOf\((.*?)\n\s*\)', tu, re.S)
row = next((b for b in blocks if 'FULL_LEFT' in b), None)
assert row is not None, 'the default first toolbar row is gone from ToolbarUtils'
keys = re.findall(r'\b([A-Z][A-Z_]+)\b', re.sub(r'//[^\n]*', '', row))
own_page = set(re.findall(r'ToolbarKey\.([A-Z_]+)',
               re.search(r'toolbarKeysOnOwnPage = setOf\(([^)]*)\)', tu).group(1)))
assert own_page, 'no key asks for its own page — Cloud Vault shared a page again'

# WHICH PARAMS MODEL THE SOURCE ACTUALLY HAS. This is what makes the geometry
# below fail on the pre-fix state instead of quietly assuming the fix: a shared
# object spreads the own-page margin to every key, so the row is laid out with
# that margin on all of them.
shared_params = re.search(r'private val toolbarKeyLayoutParams = LinearLayout\.LayoutParams', sv) is not None

# A 411dp-class portrait viewport — the whole width, because the expand key is
# hidden in the two-row strip. 411/36 = 11.4, so eleven whole keys fit and
# eleven is what #301 asks for; the spec and the hardware agree here.
VIEWPORT = 411
per_page = VIEWPORT // key_dp                    # PagedToolbarScrollView.pageWidth
page = per_page * key_dp
assert per_page == 11, f'{key_dp}dp keys give {per_page} per page on {VIEWPORT}dp, the design is 11'

def layout(margins):
    """Key lefts, the way onLayout lays the row out: each key's own margin then its width."""
    left, lefts = 0, []
    for k in keys:
        left += margins.get(k, 0)
        lefts.append(left)
        left += key_dp
    return lefts

# alignKeysThatWantTheirOwnPage(), iterated the way onLayout re-runs it.
margins, changed, lefts = {}, False, []
for _ in range(4):
    lefts = layout(margins)
    changed = False
    for k, left in zip(keys, lefts):
        if k not in own_page:
            continue
        gap = (page - (left - margins.get(k, 0)) % page) % page
        if margins.get(k, 0) != gap:
            margins[k] = gap
            changed = True
    if shared_params and margins:
        # One object: the margin written for the own-page key is the margin every
        # key now carries.
        margins = {k: max(margins.values()) for k in keys}
    if not changed:
        break
assert not changed, 'the own-page margin never settled — a second pass still moved it'

pages = {}
for k, left in zip(keys, lefts):
    pages.setdefault(left // page, []).append(k)
counts = [len(pages[p]) for p in sorted(pages)]
last = pages[max(pages)]
assert counts == [11, 10, 1], f'pages hold {counts}, the design is 11 / 10 / the own-page key alone'
assert set(last) == own_page, f'the last page holds {last}, not the own-page key alone'
print(f'    {key_dp}dp keys, {len(keys)} in the default row, {per_page} per page, split {counts}, '
      f'page {max(pages) + 1} = {last[0]}')
PYEOF
then
    pass "A3 the page geometry gives 11 / 10 / the own-page key alone"
else
    fail "A3 the page geometry no longer matches the design"
fi

echo
if [ "$FAILURES" = 0 ]; then
    echo "== toolbar layout contract: all assertions hold =="
else
    echo "== toolbar layout contract: $FAILURES FAILED =="
fi
[ "$FAILURES" = 0 ]
