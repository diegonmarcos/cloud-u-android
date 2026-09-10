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
#   T11 every panel in build.json that DECLARES an app names a real one — the feature shipped
#       inert because the resolver was right and nothing was declared for it
#   T12 an inbox that declares no app is quiet on the phone and loud in logcat
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
has "$AGG" '?.let { Sections.externalApp(it) }' \
  && ok "T8: an extapp:<id> target resolves through ui.external_apps" \
  || bad "T8: the extapp route is gone"
# The label route is GONE and must stay gone: it bound a card's notifications
# to a human-readable title, so relabelling a card silently moved or dropped
# its boxes, and two cards naming one app both claimed it.
if grep -q 'Sections.externalApps()' "$CODE"; then
  bad "T8: a title/label matching route is back — relabelling a card would move its boxes"
else
  ok "T8: extapp:<id> is the only route — no title matching"
fi
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

echo "== the declaration the resolver needs actually exists =="
# T11 is the assertion that was missing when this feature shipped inert: the
# resolver was correct and NO panel declared anything for it to resolve, so
# every inbox card fell through. Code-shape tests could not see that; only
# reading the data can.
python3 - "$APP/build.json" <<'PY2'
import io, json, sys
b = json.load(io.open(sys.argv[1], encoding="utf-8"))
ids = {a["id"] for a in b["ui"]["external_apps"]}
# KEYED ON THE DECLARATION, NOT ON A LIST OF KINDS. It used to be a hardcoded
# {mail_accounts, chat_matrix, chat_mattermost}, and on 2026-09-10 Projects >
# Inboxes stopped being one card per app and became one card per CHANNEL CLASS
# (kind=class_inbox) — at which point not one panel carried any of those three
# kinds, `seen` fell to zero and this assertion reported that it had gone
# blind. It was right to. The fix is not a fourth kind in the set: what T11
# actually checks is that a panel which DECLARES an app names one that exists,
# so the declaration is the right thing to key on and a fifth panel kind
# invented tomorrow is covered without touching this file.
bad = []
seen = 0
for sec in b["ui"]["sections"]:
    for key, val in sec.items():
        if not (key.startswith("stack_") and isinstance(val, list)):
            continue
        for panel in val:
            if not isinstance(panel, dict):
                continue
            url = panel.get("url", "")
            if not isinstance(url, str) or not url.startswith("extapp:"):
                continue
            seen += 1
            if url[len("extapp:"):].split("/")[0] not in ids:
                bad.append("%s/%s points at unknown app %r" % (key, panel.get("title"), url))
if seen == 0:
    bad.append("no panel declares an extapp: target at all — the page moved and this test went blind")
print("  ok: T11: all %d panels declaring an app resolve to a declared one" % seen if not bad
      else "  FAIL: T11: " + "; ".join(bad))
sys.exit(1 if bad else 0)
PY2
[ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))

echo "== the unresolved case is quiet for the user =="
# T12: an undeclared inbox is a gap only whoever edits build.json can close, so
# it must not be spelled out on the phone. The branch renders nothing and logs.
if awk '/val app = inboxApp\(panel\)/,/^        }$/' "$CODE" | grep -q 'body.addView'; then
  bad "T12: the unresolved branch draws into the card again — build.json prose on the phone"
else
  ok "T12: the unresolved branch draws nothing"
fi
has "$AGG" 'if (panel.kind in INBOX_KINDS) android.util.Log.w(TAG,' \
  && ok "T12: the gap is reported to logcat, where whoever can fix it looks" \
  || bad "T12: nothing reports the gap at all — it would be invisible to everyone"

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
