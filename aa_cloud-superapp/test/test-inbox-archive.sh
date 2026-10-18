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

DIR="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher"

# CODE-ONLY view. The counting assertions must not see the KDoc, which
# necessarily NAMES the things it explains this file does not do. (#773: the
# page is split - rules in AggregatorModel, memory in AggregatorState, the boxes
# and the Archive in AggregatorNotify, the cards in AggregatorPanels.)
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
python3 - "$DIR" "$TMP" <<'PY'
import glob, io, os, re, sys
for f in glob.glob(os.path.join(sys.argv[1], "Aggregator*.kt")):
    s = io.open(f, encoding="utf-8").read()
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    s = re.sub(r"^[ \t]*//.*$", "", s, flags=re.M)
    s = re.sub(r"[ \t]//.*$", "", s, flags=re.M)
    io.open(os.path.join(sys.argv[2], os.path.basename(f)), "w", encoding="utf-8").write(s)
PY
MODEL="$TMP/AggregatorModel.kt"; STATE="$TMP/AggregatorState.kt"; NOTIFY="$TMP/AggregatorNotify.kt"
PANELS="$TMP/AggregatorPanels.kt"; FRAG="$TMP/AggregatorStackFragment.kt"
cat "$TMP"/Aggregator*.kt > "$TMP/ALL.kt"; CODE="$TMP/ALL.kt"
for f in "$MODEL" "$STATE" "$NOTIFY" "$PANELS" "$FRAG"; do
  [ -f "$f" ] || { echo "  FAIL: $(basename "$f") not found"; exit 1; }
done

echo "== archived state rides the existing mechanism =="
has "$STATE" 'StackFilters.select(ctx, page, AggregatorModel.ARCHIVED_PREFIX + key' \
  && ok "T1: archiving writes a StackFilters selection" \
  || bad "T1: archiving no longer goes through StackFilters"
has "$STATE" 'StackFilters.selected(ctx, page, yesNo(AggregatorModel.ARCHIVED_PREFIX + key))' \
  && ok "T1: archived state is read back through StackFilters" \
  || bad "T1: archived state is read from somewhere else now"
if grep -q 'getSharedPreferences' "$CODE"; then
  bad "T1: the page opens its own SharedPreferences — that is a second store"
else
  ok "T1: no second store — prefs stay behind StackFilters"
fi

echo "== the key shape is the page-setting one =="
has "$MODEL" 'const val ARCHIVED_PREFIX = "__archived/"' \
  && ok "T2: archived keys carry the __ page-setting prefix" \
  || bad "T2: the __archived/ prefix is gone — FilterRow would try to draw it"
has "$MODEL" 'const val FILTER_ARCHIVE_OPEN = "__archive_open"' \
  && ok "T2: the Archive's own open/closed flag is a page setting too" \
  || bad "T2: the archive-open flag is no longer a __ page setting"
# `page` is the page id, so one page's archive cannot hide another's boxes.
n=$(grep -c 'ARCHIVED_PREFIX' "$STATE")
[ "$n" -ge 2 ] && ok "T2: the prefix is used for both read and write ($n sites)" \
  || bad "T2: only $n use of ARCHIVED_PREFIX — one side reads a different key"
has "$PANELS" 'filterNot { it.id.startsWith(AggregatorModel.SETTING_PREFIX) }' \
  && ok "T2: the Filters row skips drawing a control for any __ setting" \
  || bad "T2: the Filters row would draw the __ settings as toggles"

echo "== every app box gets the control, and it moves the box =="
has "$NOTIFY" 'state.setArchived(g.key, true)' \
  && ok "T3: the control rides on every app card (long-press sheet, Restore pill in the Archive)" \
  || bad "T3: app boxes no longer get an Archive control"
# GroupBlock is the ONE place an app box is built (phone, cloud, in-app, ntfy),
# so one call site here is every box on every page.
b=$(grep -c 'internal fun GroupBlock' "$CODE")
[ "$b" -eq 1 ] && ok "T3: one GroupBlock builder, so one Archive control for every stream" \
  || bad "T3: $b GroupBlock builders — a stream could get a box with no control"
# The click MOVES the box: it flips the stored state, the card's list skips an
# archived box, and the Archive section draws exactly the archived ones. A
# preference written with nothing re-drawn would read as a dead button.
has "$NOTIFY" 'state.setArchived(key, !state.isArchived(key))' \
  && has "$NOTIFY" 'if (state.isArchived(v.group.key)) continue' \
  && has "$NOTIFY" 'state.isArchived(v.group.key) -> Triple(i, v, false)' \
  && ok "T3: the click moves the box (stored, skipped in its card, drawn in the Archive)" \
  || bad "T3: the Archive click no longer relocates the box — it would look dead"

echo "== the Archive section =="
# Drawn AFTER the cards, so it is the last thing on the page.
loop=$(grep -n 'for ((i, panel) in below)' "$FRAG" | head -1 | cut -d: -f1)
add=$(grep -n 'ArchiveSection(st, actions.open)' "$FRAG" | head -1 | cut -d: -f1)
if [ -n "$loop" ] && [ -n "$add" ] && [ "$loop" -lt "$add" ]; then
  ok "T4: drawn after the cards (cards line $loop, Archive line $add)"
else
  bad "T4: the Archive is no longer drawn after the card loop"
fi
has "$NOTIFY" 'if (archived.isEmpty()) return' \
  && ok "T4: an empty Archive is not drawn at all" \
  || bad "T4: the Archive renders even when it holds nothing"

echo "== the count stays honest =="
# The Archive line is DERIVED from the same groups the cards skip (a
# derivedStateOf over allViews), so every path that changes the count - a click,
# a late ntfy poll, a toggle, a pull - changes the line with it; there is no
# separate sync to forget.
if has "$NOTIFY" '${archived.size}' && grep -q 'derivedStateOf' <<<"$(awk '/internal fun ArchiveSection/,/^}$/' "$NOTIFY")"; then
  ok "T5: the Archive line is derived from the archived groups (no separate count to drift)"
else
  bad "T5: the Archive line is no longer derived from the archived groups — some path changes the count silently"
fi
if grep -qE 'addView|removeAllViews' "$NOTIFY"; then
  bad "T6: a View-style append is back - a toggle tap would add a second copy of every archived box"
else
  ok "T6: nothing is appended to the Archive, so a re-render cannot leave two copies of one box"
fi

echo "== an inbox card keeps its summary =="
has "$PANELS" '"stats" -> { StatsBody(panel); InboxNotifications(state, panel, onOpen) }' \
  && ok "T7: stats first, notifications after — the counts were kept, not replaced" \
  || bad "T7: the stats card no longer renders its summary rows"
# Each inbox branch is ONE line: the kind, then its renderers.
for k in mail_accounts chat_matrix chat_mattermost; do
  grep -q 'InboxNotifications' <<<"$(grep -F "\"$k\" ->" "$PANELS")" \
    && ok "T7: $k draws its inbox notifications" \
    || bad "T7: $k lost its inbox notifications"
done

echo "== the inbox→app binding is data =="
has "$MODEL" '?.let { Sections.externalApp(it) }' \
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
print("  ok: T8: no package literal in the page" if not bad
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
            # extapp:<id>[/<fork>][#<deep target>] — the id is what must resolve.
            if url[len("extapp:"):].split("#")[0].split("/")[0] not in ids:
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
if grep -qE 'Agg(Caption|StateLine|EmptyHint)\(' <<<"$(awk '/if \(app == null\) \{/,/^    }$/' "$NOTIFY")"; then
  bad "T12: the unresolved branch draws into the card again — build.json prose on the phone"
else
  ok "T12: the unresolved branch draws nothing"
fi
has "$NOTIFY" 'if (panel.kind in AggregatorModel.INBOX_KINDS) LaunchedEffect(panel) {' \
  && has "$NOTIFY" 'android.util.Log.w(TAG, "inbox panel' \
  && ok "T12: the gap is reported to logcat, where whoever can fix it looks" \
  || bad "T12: nothing reports the gap at all — it would be invisible to everyone"

echo "== read keys and the watermark =="
p=$(grep -cF 'StackFilters.pruneRead(' "$CODE")
[ "$p" -eq 3 ] \
  && ok "T9: still three prunes — the per-app inbox view added none" \
  || bad "T9: $p pruneRead calls — a partial view prunes, and read rows will reappear"
# The watermark must be read+advanced by the STATE every visit, outside any
# `filters.isNotEmpty()` branch, or a page with no toggle row reports its whole
# history as "N new" forever.
if grep -q 'StackFilters.markSeen' "$PANELS" "$NOTIFY"; then
  bad "T10: markSeen sits in a body again — the state is the one place that advances it"
elif grep -q 'StackFilters.markSeen(ctx, page, System.currentTimeMillis())' <<<"$(awk '/^    init \{/,/^    }$/' "$STATE")"; then
  ok "T10: the watermark advances on every visit (state init), filter row or not"
else
  bad "T10: the watermark is never advanced"
fi

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
