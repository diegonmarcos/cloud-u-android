#!/usr/bin/env bash
# Tester: #649 — Configs is TWO declared sections, Launcher and Watchdog, and
# Watchdog has TWO subsections, Setup and Observability. Actions is untouched.
#
# WHAT THIS FILE IS FOR. A regroup this size has one failure mode that no other
# tester in this directory can see: an ORPHANED PAGE. Every page still exists,
# every page still parses, every page still has a fragment behind it — and one of
# them is reachable from nowhere, because it slipped out of the run of entries
# its heading covers and out of every `tabs` list at the same time. The grid
# draws the other twenty, the suite stays green, and the page is gone. T5 below
# is that check, and it is the reason this file exists; the rest is the structure
# the owner asked for, pinned so a later edit has to mean it.
#
# THE SECOND FAILURE MODE is a heading that looks declared and is not. Moving the
# words "Launcher" or "Setup" into Kotlin would render identically today and make
# every assertion here unfalsifiable tomorrow, because build.json would no longer
# be what the screen is built from. T4 greps for that directly.
#
# Static tester (no device, no build): build.json is read as data; the Kotlin is
# checked for the contract that data relies on, and for the literals it must not
# contain.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 — $1"; fi; }

BJ="$APP/build.json"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp"
SECTIONS="$KT/launcher/Sections.kt"
NAV="$KT/launcher/LauncherNavController.kt"
GRID="$KT/launcher/TileGridFragment.kt"
PAGES="$KT/launcher/SectionPages.kt"

command -v python3 >/dev/null 2>&1 || {
  echo "  ABORT: python3 missing — every assertion below would report a verdict"
  echo "         from its absence rather than from the declaration."; exit 2; }
for f in "$BJ" "$SECTIONS" "$NAV" "$GRID" "$PAGES"; do
  [ -f "$f" ] || {
    echo "  ABORT: no such file: $f — a grep over nothing matches nothing, which"
    echo "         is indistinguishable here from a contract being kept."; exit 2; }
done

echo "== T1: the two sections and the two subsections, in declaration order =="
# Both the NAMES and the ORDER, read off the file the grid reads. Groups are runs
# of consecutive entries, so this doubles as the consecutiveness check: a member
# parked elsewhere in the array shows up here as a repeated heading.
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
shape, last = [], None
for p in pages:
    if p.get('hidden') or p.get('is_action'): continue
    key = (p.get('group', ''), p.get('subgroup', ''))
    if key != last: shape.append(key); last = key
want = [('Launcher', ''), ('Watchdog', 'Setup'), ('Watchdog', 'Observability')]
print('OK' if shape == want else 'heading runs = %r' % (shape,))
PY
)" "Launcher, then Watchdog ▸ Setup, then Watchdog ▸ Observability — each appearing ONCE"

echo "== T2: each heading holds exactly the pages the owner named =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
def members(g, s=''):
    return [p['label'] for p in pages
            if not p.get('hidden') and not p.get('is_action')
            and p.get('group', '') == g and p.get('subgroup', '') == s]
want = {
    ('Launcher', ''):                  ['Presets', 'Controls', 'One-Hand', 'Notify'],
    ('Watchdog', 'Setup'):             ['Account', 'WireGuard', 'KDE', 'AI', 'Permissions', 'Store'],
    ('Watchdog', 'Observability'):     ['About'],
}
problems = ['%s%s = %r' % (g, ' ▸ ' + s if s else '', members(g, s))
            for (g, s), exp in want.items() if members(g, s) != exp]
print('; '.join(problems) or 'OK')
PY
)" "Launcher = Presets/Controls/One-Hand/Notify; Setup = Account/WireGuard/KDE/AI/Permissions/Store; Observability = About"

echo "== T3: Store is the former Constellation, and Cloud Constellation is INSIDE it =="
# #563 renamed the Constellation page Store; the word survives as the label of
# the fleet tab inside it. Both halves are asserted because either one alone
# reads as a finished rename: a Store page with no Cloud Constellation tab means
# the fleet store was dropped, and a second page called Constellation means the
# rename was undone beside it.
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
by_id = {p['id']: p for p in pages}
problems = []
store = by_id.get('store')
if store is None:                             problems.append('no `store` page')
else:
    if store.get('group') != 'Watchdog' or store.get('subgroup') != 'Setup':
        problems.append('store is under %r/%r, not Watchdog ▸ Setup'
                        % (store.get('group'), store.get('subgroup')))
    if store.get('tabs') != ['store-cloud', 'store-phone']:
        problems.append('store tabs = %r' % (store.get('tabs'),))
if by_id.get('store-cloud', {}).get('label') != 'Cloud Constellation':
    problems.append('the Cloud Constellation tab lost its label: %r'
                    % (by_id.get('store-cloud', {}).get('label'),))
if any(p['label'] == 'Constellation' for p in pages):
    problems.append('a page is labelled Constellation again — #563 renamed it Store')
print('; '.join(problems) or 'OK')
PY
)" "Store sits in Watchdog ▸ Setup and owns the Cloud Constellation tab; no page named Constellation"

echo "== T4: no heading, subheading or page name is a Kotlin literal on the grid path =="
# The whole point of the declaration: a literal would make the build.json edit
# inert while the screen kept looking correct.
#
# THE WORD LIST IS DERIVED, never typed here — it is every group, subgroup and
# visible label the config section declares. A hand list is the version of this
# check that goes quiet the moment a heading is renamed, which is precisely when
# it is needed.
#
# THE SCOPE IS THE GRID PATH — the three files that turn the declaration into
# tiles. Deliberately not the whole app, and this is the honest boundary rather
# than a convenient one: OTHER surfaces legitimately own the same words, and two
# do today. configs/OneHandFragment.kt prints "One-Hand" as the title INSIDE the
# One-Hand page, and profile/ProfileFragment.kt names a tab "Setup" inside Account
# (#626) — neither is the Configs grid deciding what a heading says, and widening
# this grep to catch them would make it fail on a coincidence of vocabulary
# instead of on a broken contract. What it must catch is any of these words
# reaching the grid from Kotlin, which is what these three files do and nothing
# else does.
#
# COMMENTS ARE STRIPPED. This file's own rationale quotes the headings it pins,
# and a grep that counts prose is a check that fails the moment it is explained.
check "$(python3 - "$BJ" "$SECTIONS" "$NAV" "$GRID" <<'PY'
import json, re, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
words = set()
for p in pages:
    for k in ('group', 'subgroup'):
        if p.get(k): words.add(p[k])
    if not p.get('hidden') and not p.get('is_action'): words.add(p['label'])
if not words:
    print('the config section declares no group, subgroup or label — this check '
          'derived an empty word list and would pass over anything'); sys.exit()
hits = []
for path in sys.argv[2:]:
    src = open(path).read()
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)          # block comments
    src = re.sub(r'(?m)//.*$', '', src)                       # line comments
    for w in sorted(words):
        if '"%s"' % w in src:
            hits.append('%s hardcodes "%s"' % (path.rsplit('/', 1)[-1], w))
print('; '.join(hits) or 'OK')
PY
)" "none of the declared headings or page names appears as a literal in Sections/LauncherNavController/TileGridFragment"
# How many words it actually checked, asserted separately. "No literals found" is
# also what an empty word list says, and the two must not read alike.
WORDS=$(python3 -c "
import json
pages = next(s for s in json.load(open('$BJ'))['ui']['sections']
             if s['id'] == 'config')['pages']
w = {p[k] for p in pages for k in ('group', 'subgroup') if p.get(k)}
w |= {p['label'] for p in pages if not p.get('hidden') and not p.get('is_action')}
print(len(w))
")
[ "$WORDS" = 15 ] \
  && ok "T4 derived 15 words from the declaration (2 groups + 2 subgroups + 11 page labels)" \
  || bad "T4 derived $WORDS words, expected 15 — it is checking a different set than it claims"

# The ONE heading Kotlin may still name is the fallback for a section that
# declares none, which is every section but this one. Pinned so it stays the only
# one: an unreviewed second constant here is how a declared heading gets quietly
# replaced by a literal.
# #697 moved the pair from LauncherNavController into Sections so the grid and
# the menus share them; the controller may only alias, never spell, a heading.
KTGROUPS=$(grep -cE '^\s*const val GROUP_[A-Z]+ = "' "$SECTIONS" || true)
NAVLIT=$(grep -cE 'val GROUP_[A-Z]+ = "' "$NAV" || true)
[ "$KTGROUPS" = 2 ] && [ "$NAVLIT" = 0 ] \
  && ok "Sections names exactly 2 headings in Kotlin (the Pages fallback + Actions), the nav controller none" \
  || bad "$KTGROUPS hardcoded GROUP_ constants in Sections (expected 2), $NAVLIT in LauncherNavController (expected 0)"

echo "== T5: NO ORPHANED PAGE — every declared page is reachable =="
# THE check this file exists for. A page is reachable when it draws a tile (not
# `hidden`), OR it is a tab of a page that does, OR something names it as a
# target. Anything else exists, parses, has a fragment, and cannot be opened.
check "$(python3 - "$BJ" <<'PY'
import json, re, sys
raw   = open(sys.argv[1]).read()
pages = next(s for s in json.load(raw)['ui']['sections']
             if s['id'] == 'config')['pages'] if False else \
        next(s for s in json.loads(raw)['ui']['sections']
             if s['id'] == 'config')['pages']
by_id   = {p['id']: p for p in pages}
tiled   = {p['id'] for p in pages if not p.get('hidden')}
as_tab  = {t: p['id'] for p in pages if p['id'] in tiled for t in p.get('tabs', [])}
# A `page:config/<id>` anywhere in this file is a live door of its own.
targeted = set(re.findall(r'page:config/([A-Za-z0-9_-]+)', raw))
orphans = []
for p in pages:
    pid = p['id']
    if pid in tiled or pid in as_tab or pid in targeted: continue
    orphans.append(pid)
if orphans:
    print('unreachable: %r — declared, renders, and nothing opens it' % (orphans,))
    sys.exit()
# And the reverse: a tab naming a page that does not exist is a dead strip entry.
dangling = [(p['id'], t) for p in pages for t in p.get('tabs', []) if t not in by_id]
print('tabs with no page behind them: %r' % (dangling,) if dangling else 'OK')
PY
)" "every config page draws a tile, is a tab of one that does, or is named as a page: target"

# T5 is only as strong as the page set it walks. Pin the COUNT: a page deleted
# outright cannot be reported as an orphan by a check that iterates what is left.
COUNT=$(python3 -c "
import json
print(len(next(s for s in json.load(open('$BJ'))['ui']['sections']
               if s['id'] == 'config')['pages']))
")
[ "$COUNT" = 21 ] \
  && ok "21 config pages declared — the set T5 walks is the whole set (#649: 22 minus the dissolved launcher page)" \
  || bad "$COUNT config pages, expected 21 — a page was added or DELETED, and T5 cannot report a page that is gone"

echo "== T6: ACTIONS IS UNTOUCHED =="
# The owner said to keep it as it is. Three things make that true: the same three
# entries, the same flag, and the same rendering path. Their POSITION in the array
# is deliberately not asserted — the grid appends the Actions group last whatever
# the order, which is what lets this section be regrouped without moving them.
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
acts = [(p['id'], p['label'], p.get('action')) for p in pages if p.get('is_action')]
want = [('update',      'Update All',  'action:update_all'),
        ('kde_connect', 'KDE Connect', 'action:kde_connect_now'),
        ('animations',  'Animations',  'action:toggle_animations')]
if acts != want: print('actions = %r' % (acts,)); sys.exit()
grouped = [p['id'] for p in pages if p.get('is_action') and (p.get('group') or p.get('subgroup'))]
print('an action entry carries a group: %r — Actions is its own heading, off the '
      'is_action flag' % (grouped,) if grouped else 'OK')
PY
)" "Update All, KDE Connect, Animations — same ids, labels and targets, and none of them grouped"

grep -qF 'group = if (p.isAction) GROUP_ACTIONS else p.group.ifBlank { GROUP_PAGES }' "$NAV" \
  && ok "the Actions heading still comes from is_action, not from the new group field" \
  || bad "the is_action → Actions path changed — Actions was to be left alone"
grep -qF 'own.filterNot { it.group == GROUP_ACTIONS }' "$NAV" \
  && ok "the pages half is everything that is NOT an action (not: everything named Pages)" \
  || bad "the grid still selects pages by the fallback heading name, so a declared heading drops them off the grid"

echo "== T7: the declaration actually reaches the screen =="
grep -qF 'group    = po.optString("group", "")' "$SECTIONS" \
  && ok "Page.group is parsed from build.json" \
  || bad "Page.group is not parsed — the declared heading would be ignored"
grep -qF 'subgroup = po.optString("subgroup", "")' "$SECTIONS" \
  && ok "Page.subgroup is parsed from build.json" \
  || bad "Page.subgroup is not parsed — the second level would be ignored"
grep -qF 'subgroup = if (p.isAction) "" else p.subgroup' "$NAV" \
  && ok "the nav controller carries subgroup through to the tile" \
  || bad "subgroup never reaches TileGridFragment — Setup/Observability would not draw"
grep -qF 'grid.addView(subgroupHeader(s))' "$GRID" \
  && ok "TileGridFragment draws a subgroup header" \
  || bad "no subgroup header is drawn — both Watchdog subsections would run together"
grep -qF 'subs.getOrNull(i + span).orEmpty() == s' "$GRID" \
  && ok "a grid row never straddles a subgroup boundary" \
  || bad "the row span ignores subgroup — Observability's tile would share Setup's last row"
grep -qF 'shownSub = null' "$GRID" \
  && ok "a new group resets the remembered subgroup (a repeated subheading under a later group still draws)" \
  || bad "shownSub is never reset — the same subheading under two groups would be drawn once"

echo "== T8: each of Launcher's four pages opens on its OWN, not via a tab owner =="
# They were tabs until #649. A tile that still resolves to an owner page would
# land the owner on a tab strip that no longer exists.
for pid in presets controls onehand notify; do
  case "$pid" in
    notify) grep -qF '"mirror_page": "communication/my-rss"' "$BJ" \
              && ok "notify renders through its declared mirror_page, no factory branch needed" \
              || bad "notify has neither a mirror_page nor a fragment — the tile would open nothing" ;;
    *)      grep -qE "pageId == \"$pid\"" "$PAGES" \
              && ok "page:config/$pid resolves to its own fragment in SectionPages" \
              || bad "no SectionPages branch for $pid — its new tile opens the fallback placeholder" ;;
  esac
done

echo "== T9: NO PAGE WAS LOST — the id set before the regroup == the id set now, minus declared retirements (#697) =="
# T5 walks what is left and the COUNT pin above only sees a net change: delete one
# page and add another and both stay green. This is a SET check against the ids
# as they stood at 171d9e048~1, the last declaration before #649 regrouped the
# section — the pages the owner had working. A page may leave the set only by
# being named in build.json `retired_pages` with the pages its content lives on
# now, and those must be real, visible pages. A NEW page fails here too, on
# purpose: adding one to Configs is a decision, and it is recorded by adding its
# id to BEFORE below.
check "$(python3 - "$BJ" <<'PY'
import json, sys
BEFORE = {'presets', 'controls', 'onehand', 'notify', 'launcher',
          'profile', 'wg', 'kde', 'ai', 'perms', 'store', 'about',
          'websearch', 'localsearch', 'textenhance', 'library', 'tokens',
          'store-cloud', 'store-phone',
          'update', 'kde_connect', 'animations'}
sec     = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
               if s['id'] == 'config')
pages   = sec['pages']
now     = {p['id'] for p in pages}
retired = sec.get('retired_pages', {})
tiled   = {p['id'] for p in pages if not p.get('hidden') and not p.get('is_action')}
problems = []
lost = sorted(BEFORE - now - set(retired))
if lost:  problems.append('LOST %r — gone from pages and not declared in retired_pages' % lost)
new = sorted(now - BEFORE)
if new:   problems.append('NEW %r — add the id to BEFORE in this tester if it is meant' % new)
for rid, into in sorted(retired.items()):
    if rid not in BEFORE: problems.append('retired %r never existed' % rid)
    if rid in now:        problems.append('retired %r is still declared' % rid)
    if not into:          problems.append('retired %r names no successor' % rid)
    for s in into:
        if s not in tiled: problems.append('retired %r -> %r, which is not a visible page' % (rid, s))
print('; '.join(problems) or 'OK')
PY
)" "every pre-regroup config page id is still declared, or retired into visible successors (launcher → its four tabs)"

echo "== T10: the rail and the drawer list Configs under the SAME declared headings as the grid (#697) =="
# #649 reached the phone grid only. On a two-pane screen Configs renders through
# SectionMenuFragment (the rail), and the drawer expands `section:config` in
# HomeDrawerFragment; both walked section.pages flat, so an unfolded screen
# showed fourteen rows with no Launcher, Watchdog, Setup or Observability.
MENU="$KT/launcher/SectionMenuFragment.kt"
DRAWER="$KT/launcher/HomeDrawerFragment.kt"
for f in "$MENU" "$DRAWER"; do
  n=${f##*/}
  grep -qF 'val (g, s) = Sections.headingOf(section, page)' "$f" \
    && ok "$n asks Sections.headingOf for each page's heading" \
    || bad "$n does not read the declared heading — it lists Configs flat"
  grep -qE 'g\.uppercase\(\)\)\.setEnabled\(false\)' "$f" \
    && ok "$n prints the group heading as a disabled row" \
    || bad "$n never prints the group heading (Launcher / Watchdog)"
  grep -qE '\$s"\)\.setEnabled\(false\)' "$f" \
    && ok "$n prints the subgroup heading as a disabled row" \
    || bad "$n never prints the subgroup heading (Setup / Observability)"
done
grep -qF 'section.pages.none { it.group.isNotBlank() || it.subgroup.isNotBlank() } -> "" to ""' "$SECTIONS" \
  && ok "a section that declares no heading gets none in its menus (every section but Configs is unchanged)" \
  || bad "headingOf no longer returns blank for an undeclared section — every menu would grow a PAGES banner"
grep -qF 'page.isAction -> GROUP_ACTIONS to ""' "$SECTIONS" \
  && ok "menus put is_action pages under Actions, as the grid does" \
  || bad "headingOf lost the is_action → Actions rule — the three actions would list under Observability"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" = 0 ]
