#!/usr/bin/env bash
# Tester: the Inboxes page answers BOTH questions per inbox, and an app box the
# user put away stays put away.
#
# BEFORE: Projects ▸ Inboxes was five cards of summary counts and nothing else.
# The counts say how much is in an inbox; they never say what arrived, which is
# the thing that makes someone open the app — so the page was read once and then
# ignored. And a chatty app could only be collapsed, one tap at a time, every
# single visit, because nothing remembered the decision.
#
# NOW: an inbox card keeps its counts and draws the posting app's own
# notification boxes underneath them, and every app box carries an Archive
# control that moves the box itself into one collapsed Archive section at the
# bottom of the page. Archived state is a StackFilters selection under the page
# id — the mechanism the page's other choices already use — not a new store.
#
# These are the invariants that would regress silently, so they are pinned:
#   T1  archived state is persisted BY StackFilters (no second store appears)
#   T2  the key is page-scoped and `__`-prefixed, like every page setting
#   T3  every app box gets the control, and the control MOVES the box
#   T4  the Archive renders last and is hidden while it holds nothing
#   T5  the Archive line is resynced from every path that changes its count
#   T6  a body rebuild cannot leave two copies of one box in the Archive
#   T7  an inbox card KEEPS its summary — the notifications are added, not swapped
#   T8  which app an inbox is about is DATA, never a package literal in Kotlin
#   T9  the partial per-app view still prunes no read keys
#   T10 the "N new" watermark advances on a page that declares no filter row
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -qF "$2" "$1" 2>/dev/null; }

AGG="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/AggregatorStackFragment.kt"

# CODE-ONLY view. The counting assertions must not see the KDoc, which
# necessarily NAMES the things it explains this file does not do.
CODE="$(mktemp)"; trap 'rm -f "$CODE"' EXIT
python3 - "$AGG" "$CODE" <<'PY'
import io, re, sys
s = io.open(sys.argv[1], encoding="utf-8").read()
s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
s = re.sub(r"^[ \t]*//.*$", "", s, flags=re.M)
s = re.sub(r"[ \t]//.*$", "", s, flags=re.M)
io.open(sys.argv[2], "w", encoding="utf-8").write(s)
PY

echo "== archived state rides the existing mechanism =="
has "$AGG" 'StackFilters.select(ctx, filterPage, ARCHIVED_PREFIX + key' \
  && ok "T1: archiving writes a StackFilters selection" \
  || bad "T1: archiving no longer goes through StackFilters"
has "$AGG" 'StackFilters.selected(ctx, filterPage, yesNo(ARCHIVED_PREFIX + key))' \
  && ok "T1: archived state is read back through StackFilters" \
  || bad "T1: archived state is read from somewhere else now"
if grep -q 'getSharedPreferences' "$CODE"; then
  bad "T1: the fragment opens its own SharedPreferences — that is a second store"
else
  ok "T1: no second store — prefs stay behind StackFilters"
fi

echo "== the key shape is the page-setting one =="
has "$AGG" 'private const val ARCHIVED_PREFIX     = "__archived/"' \
  && ok "T2: archived keys carry the __ page-setting prefix" \
  || bad "T2: the __archived/ prefix is gone — filterRow would try to draw it"
has "$AGG" 'private const val FILTER_ARCHIVE_OPEN = "__archive_open"' \
  && ok "T2: the Archive's own open/closed flag is a page setting too" \
  || bad "T2: the archive-open flag is no longer a __ page setting"
# filterPage is the page id, so one page's archive cannot hide another's boxes.
n=$(grep -c 'ARCHIVED_PREFIX' "$CODE")
[ "$n" -ge 2 ] && ok "T2: the prefix is used for both read and write ($n sites)" \
  || bad "T2: only $n use of ARCHIVED_PREFIX — one side reads a different key"

echo "== every app box gets the control, and it moves the box =="
has "$AGG" 'header.addView(archivePill(ctx, g.key, block, homeBody))' \
  && ok "T3: the control sits on the group header, beside the clear-all tick" \
  || bad "T3: app boxes no longer get an Archive control"
# groupBlock is the ONE place an app box is built (phone, cloud, in-app, ntfy),
# so one call site here is every box on every page.
b=$(grep -c 'private fun groupBlock' "$CODE")
[ "$b" -eq 1 ] && ok "T3: one groupBlock builder, so one Archive control for every stream" \
  || bad "T3: $b groupBlock builders — a stream could get a box with no control"
has "$AGG" 'target.addView(block)' && has "$AGG" '(block.parent as? ViewGroup)?.removeView(block)' \
  && ok "T3: the click moves the box, it does not only write a preference" \
  || bad "T3: the Archive click no longer relocates the box — it would look dead"

echo "== the Archive section =="
# Built before the cards (so a card can file into it) but added AFTER them, so
# it is the last thing on the page.
bld=$(grep -n 'val archive = buildArchiveSection(ctx)' "$CODE" | head -1 | cut -d: -f1)
loop=$(grep -n 'for (panel in panels) {' "$CODE" | head -1 | cut -d: -f1)
add=$(grep -n 'column.addView(archive)' "$CODE" | head -1 | cut -d: -f1)
if [ -n "$bld" ] && [ -n "$loop" ] && [ -n "$add" ] && [ "$bld" -lt "$loop" ] && [ "$loop" -lt "$add" ]; then
  ok "T4: built before the cards (line $bld), attached after them (line $add)"
else
  bad "T4: the Archive is no longer built-before / attached-after the card loop"
fi
has "$AGG" 'archiveWrap?.isVisible = box.childCount > 0' \
  && ok "T4: an empty Archive is not drawn at all" \
  || bad "T4: the Archive renders even when it holds nothing"

echo "== the count stays honest =="
s=$(grep -c 'syncArchiveHeader()' "$CODE")
[ "$s" -ge 5 ] \
  && ok "T5: the Archive line is resynced from $s sites (build, place, click, rebuild)" \
  || bad "T5: only $s syncArchiveHeader() calls — some path changes the count silently"
has "$AGG" 'archiveBox?.removeAllViews()' \
  && ok "T6: the Archive is emptied before the bodies that re-file into it rebuild" \
  || bad "T6: a toggle tap would add a second copy of every archived box"

echo "== an inbox card keeps its summary =="
has "$AGG" 'renderStats(ctx, body, panel); renderInboxNotifications(ctx, body, panel) }' \
  && ok "T7: stats first, notifications after — the counts were kept, not replaced" \
  || bad "T7: the stats card no longer renders its summary rows"
# Each inbox branch is two lines: the kind, then its renderers. -A2 covers the
# branch without reaching the next one.
for k in mail_accounts chat_matrix chat_mattermost; do
  grep -A2 -F "\"$k\"" "$CODE" | grep -q 'renderInboxNotifications' \
    && ok "T7: $k draws its inbox notifications" \
    || bad "T7: $k lost its inbox notifications"
done

echo "== the inbox→app binding is data =="
has "$AGG" 'Sections.externalApp(extappId)' \
  && ok "T8: an extapp:<id> target resolves through ui.external_apps" \
  || bad "T8: the extapp route is gone"
has "$AGG" 'Sections.externalApps().firstOrNull' \
  && ok "T8: the label route reads the same declared catalog" \
  || bad "T8: the label route no longer reads ui.external_apps"
# No package literal anywhere in the code: a roster of app ids in Kotlin starts
# rotting the day it is written. Fully-qualified Kotlin references are not
# string literals, so only literals are inspected.
python3 - "$CODE" <<'PY'
import io, re, sys
code = io.open(sys.argv[1], encoding="utf-8").read()
lits = re.findall(r'"((?:\\.|[^"\\\n])*)"', code)
pkg  = re.compile(r'^(com|io|org|net|me)\.[A-Za-z0-9_]+\.[A-Za-z0-9_.]+$')
bad  = [l for l in lits if pkg.match(l)]
print("  ok: T8: no package literal in the fragment" if not bad
      else "  FAIL: T8: package literal(s) hardcoded: " + ", ".join(bad))
sys.exit(1 if bad else 0)
PY
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))

echo "== read keys and the watermark =="
p=$(grep -cF 'StackFilters.pruneRead(' "$CODE")
[ "$p" -eq 3 ] \
  && ok "T9: still three prunes — the per-app inbox view added none" \
  || bad "T9: $p pruneRead calls — a partial view prunes, and read rows will reappear"
# The watermark must be read+advanced OUTSIDE the `filters.isNotEmpty()` branch,
# or a page with no toggle row reports its whole history as "N new" forever.
if awk '/val filters = Sections.stackFiltersFor/,0' "$CODE" | grep -q 'StackFilters.markSeen'; then
  bad "T10: markSeen sits after/inside the filters branch again"
else
  has "$AGG" 'StackFilters.markSeen(ctx, filterPage, System.currentTimeMillis())' \
    && ok "T10: the watermark advances on every visit, filter row or not" \
    || bad "T10: the watermark is never advanced"
fi

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
