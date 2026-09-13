#!/usr/bin/env bash
# The toolbar row pages by a whole screen of icons: eleven on page one, ten on page two,
# Cloud Vault alone on page three. It has now shown four instead of eleven twice, both
# times because something OUTSIDE PagedToolbarScrollView's arithmetic changed the width a
# key occupies. This runner cannot lay out a view, so the proof is the arithmetic plus the
# two things that feed it:
#
#   T1  every toolbar key gets its OWN layout params. One shared LinearLayout.LayoutParams
#       object was the four-icon bug: PagedToolbarScrollView writes a left margin into the
#       params of the one key that starts its own page, and while all 22 keys held the same
#       object each of them got that margin, so a page sized for eleven icons fit four.
#   T2  exactly one place writes leftMargin on a toolbar key, and it is that alignment.
#   T3  the geometry: page = as many whole keys as the viewport shows, and with the shipped
#       key width on a 411dp portrait screen that is eleven, splitting the 22 default keys
#       11 / 10 / Vault. Fails if the key width or the default row grows past the design.
#   T4  Cloud Vault is last in the default row and is the key that starts its own page.
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
K="$APP/../ab_cloud-libs-shared/libs/keyboard/src/main"
J="$K/java/helium314/keyboard"
SV="$J/latin/suggestions/SuggestionStripView.kt"
PV="$J/latin/suggestions/PagedToolbarScrollView.kt"
TU="$J/latin/utils/ToolbarUtils.kt"
DIM="$K/res/values/config.xml"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }
absent() { grep -q -- "$2" "$1" && bad "$3 ($1)" || ok "$3"; }

echo "== keyboard toolbar paging: eleven icons on a page, Vault on its own =="

# ── T1 one layout-params object per key ──────────────────────────────────────
# The getter is what makes each `= toolbarKeyLayoutParams` a fresh instance. A plain
# initialiser builds it once and hands the same object to all 22 keys, which is the bug.
has "$SV" 'private val toolbarKeyLayoutParams$' "T1 toolbarKeyLayoutParams is a per-use getter"
absent "$SV" 'private val toolbarKeyLayoutParams = LinearLayout.LayoutParams' \
  "T1 no single shared LayoutParams instance for the whole row"
absent "$SV" 'layoutParams = original.layoutParams' \
  "T1 the second-row copy does not share the original key's params object"
n=$(grep -c 'layoutParams = toolbarKeyLayoutParams' "$SV" || true)
[ "$n" -ge 3 ] && ok "T1 $n keys take their params from the getter" \
                || bad "T1 only $n key sites use the getter, expected the 3 that build keys"

# ── T2 one writer of the page-start margin ───────────────────────────────────
n=$(grep -c 'leftMargin = ' "$PV" "$SV" | awk -F: '{s+=$2} END {print s+0}')
[ "$n" = 1 ] && ok "T2 exactly one leftMargin writer" \
             || bad "T2 $n leftMargin writers on toolbar keys — margins from two places cannot converge"
has "$PV" 'params.leftMargin = gap' "T2 and it is the own-page alignment"

# ── T3 the arithmetic, against the shipped key width and default row ─────────
if python3 - "$DIM" "$TU" <<'EOF'
import re, sys
dim, tu = (open(p).read() for p in sys.argv[1:3])

key_dp = int(re.search(r'"config_suggestions_strip_edge_key_width">(\d+)dp', dim).group(1))
row = re.search(r'val default = listOf\((.*?)\n    \)', tu, re.S).group(1)
keys = [k for k in re.findall(r'\b([A-Z][A-Z_]+)\b', re.sub(r'//[^\n]*', '', row))]
own_page = set(re.findall(r'ToolbarKey\.([A-Z_]+)', re.search(r'toolbarKeysOnOwnPage = setOf\(([^)]*)\)', tu).group(1)))

# A 411dp portrait viewport: 1080px at the phone's 2.625 density, and the whole width,
# because the expand key is hidden in the two-row strip (SuggestionStripView.init).
VIEWPORT = 411
per_page = VIEWPORT // key_dp                      # PagedToolbarScrollView.pageWidth
page = per_page * key_dp
assert per_page == 11, f'{key_dp}dp keys give {per_page} per page, the design is 11'

# Lay the row out the way onLayout does, with each key holding its own margin.
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
