#!/usr/bin/env bash
# The toolbar row pages by a whole screen of icons: eleven on page one, ten on page two,
# Cloud Vault alone on page three. It has now shown four instead of eleven twice, both
# times because something OUTSIDE the paging arithmetic (ToolbarPaging) changed the width a
# key occupies. This runner cannot lay out a view, so the proof is the arithmetic plus the
# two things that feed it:
#
#   T1  every toolbar key gets its OWN page gap. One shared LinearLayout.LayoutParams
#       object was the four-icon bug (the View strip): the margin written for the one key that
#       starts its own page landed on all 22 keys, so a page sized for eleven icons fit four.
#       The Compose strip takes one gap PER KEY from ToolbarPaging.gaps and nothing shared.
#   T2  exactly one place computes the page-start gap of a toolbar key, and it is that alignment.
#   T3  the geometry: page = as many whole keys as the viewport shows, and with the shipped
#       key width on a 411dp portrait screen that is eleven, splitting the 22 default keys
#       11 / 10 / Vault. Fails if the key width or the default row grows past the design.
#   T4  Cloud Vault is last in the default row and is the key that starts its own page.
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
K="$APP/../ab_cloud-libs-shared/libs/keyboard/src/main"
J="$K/java/helium314/keyboard"
SV="$J/latin/suggestions/SuggestionStripView.kt"
PV="$J/latin/suggestions/ToolbarPaging.kt"
TU="$J/latin/utils/ToolbarUtils.kt"
DIM="$K/res/values/config.xml"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }
absent() { grep -q -- "$2" "$1" && bad "$3 ($1)" || ok "$3"; }

echo "== keyboard toolbar paging: eleven icons on a page, Vault on its own =="

# ── T1 one gap per key ───────────────────────────────────────────────────────
# The row indexes ToolbarPaging.gaps by key; a single gap value applied to every key would be
# the shared-params bug again.
has "$SV" 'ToolbarPaging.gaps(keys, toolbarKeysOnOwnPage, iconPx, page)' "T1 the gaps come from ToolbarPaging.gaps"
has "$SV" 'gaps\[i\].toDp()' "T1 each key is given ITS OWN gap"
absent "$SV" 'gaps.max()' "T1 no single shared gap for the whole row"
absent "$SV" 'secondRowKeys.add(original)' "T1 the second-row copy does not share the original key's icon"

# ── T2 one writer of the page-start gap ──────────────────────────────────────
n=$(grep -c '\] = gapForOwnPage(' "$PV" "$SV" | awk -F: '{s+=$2} END {print s+0}')
[ "$n" = 1 ] && ok "T2 exactly one page-start gap writer" \
             || bad "T2 $n page-start gap writers on toolbar keys — gaps from two places cannot converge"
has "$PV" 'gaps\[i\] = gapForOwnPage(x, page)' "T2 and it is the own-page alignment"

# ── T3 the arithmetic, against the shipped key width and default row ─────────
if python3 - "$DIM" "$TU" <<'EOF'
import re, sys
dim, tu = (open(p).read() for p in sys.argv[1:3])

key_dp = int(re.search(r'"config_suggestions_strip_edge_key_width">(\d+)dp', dim).group(1))
row = re.search(r'val default = listOf\((.*?)\n    \)', tu, re.S).group(1)
keys = [k for k in re.findall(r'\b([A-Z][A-Z_]+)\b', re.sub(r'//[^\n]*', '', row))]
own_page = set(re.findall(r'ToolbarKey\.([A-Z_]+)', re.search(r'toolbarKeysOnOwnPage = setOf\(([^)]*)\)', tu).group(1)))

# A 411dp portrait viewport: 1080px at the phone's 2.625 density, and the whole width,
# because the two-row strip has no expand key.
VIEWPORT = 411
per_page = VIEWPORT // key_dp                      # ToolbarPaging.pageWidth
page = per_page * key_dp
assert per_page == 11, f'{key_dp}dp keys give {per_page} per page, the design is 11'

# Lay the row out the way the strip does, with each key holding its own gap.
def layout(margins):
    left, lefts = 0, []
    for k in keys:
        left += margins.get(k, 0)
        lefts.append(left)
        left += key_dp
    return lefts

margins, lefts = {}, None
for _ in range(4):
    lefts = layout(margins)
    changed = False
    for k, left in zip(keys, lefts):
        if k not in own_page: continue
        gap = (page - (left - margins.get(k, 0)) % page) % page
        if margins.get(k, 0) != gap: margins[k], changed = gap, True
    if not changed: break
assert not changed, 'the own-page margin never settled — a second pass still moved it'

pages = {}
for k, left in zip(keys, lefts): pages.setdefault(left // page, []).append(k)
counts = [len(pages[p]) for p in sorted(pages)]
assert counts == [11, 10, 1], f'pages hold {counts}, the design is 11 / 10 / Vault alone'
assert pages[max(pages)] == ['VAULT'], f'page 3 holds {pages[max(pages)]}'

# And the same row with ONE shared params object, which is what shipped: the margin set for
# Vault lands on every key, and each pass adds another key width to it, so the row loses
# icons every time it is laid out and never settles. This is the state T1 forbids.
shared, seen = 0, []
for _ in range(3):
    left = 0
    for k in keys:
        left += shared
        if k in own_page: start = left - shared
        left += key_dp
    shared = (page - start % page) % page
    seen.append(page // (key_dp + shared))
assert seen[0] > seen[-1], 'the shared-params row should decay, so this check proves nothing'
print(f'{key_dp}dp keys, {per_page}/page, split {counts}; shared params would show {seen}')
EOF
then ok "T3 the page arithmetic gives 11 / 10 / Vault"; else bad "T3 page arithmetic"; fi

# ── T4 Vault's place in the row ──────────────────────────────────────────────
has "$TU" 'toolbarKeysOnOwnPage = setOf(ToolbarKey.VAULT)' "T4 Vault is the own-page key"
has "$TU" '^        VAULT,$' "T4 Vault is last in the default row"

echo
echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" = 0 ]
