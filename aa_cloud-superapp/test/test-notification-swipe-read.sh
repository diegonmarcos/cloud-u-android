#!/usr/bin/env bash
# Tester: a notification is read because the user SWIPED it, and swiping did
# not cost the page its vertical scroll.
#
# BEFORE: "unread" was derived — `ts > previous visit`. Nothing recorded that
# a specific row had been read, so the whole list flipped to read the moment
# you left the page, and Show=Unread answered "arrived recently" rather than
# "not dealt with".
#
# NOW: read state is an explicit per-entry set in StackFilters, keyed by each
# stream's own stable id and namespaced per stream so it can be pruned. The
# watermark stays, but only to answer a DIFFERENT question — the "N new" chip.
#
# These are the invariants that would regress silently, so they are pinned:
#   T1  the unread predicate reads the stored set, not the watermark
#   T2  the "N new" chip still counts arrival (the watermark keeps ONE job)
#   T3  Show=Unread and the swipe agree (one predicate, not two)
#   T4  the swipe only claims the gesture when it is HORIZONTAL
#   T5  read keys are namespaced and pruned against a COMPLETE enumeration
#   T6  an unavailable ntfy poll prunes nothing
#   T7  prefs are committed, and the page key is set even without a filter row
#   T8  still zero RecyclerView / ItemTouchHelper / child fragments on this path
#   T9  the gesture is visibly answered and reversible
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -qF "$2" "$1" 2>/dev/null; }

AGG="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/AggregatorStackFragment.kt"
FIL="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/StackFilters.kt"

# CODE-ONLY view of the fragment. The counting assertions below must not see
# the KDoc, which necessarily NAMES the things it explains it does not use —
# "no RecyclerView on this path" is a sentence containing "RecyclerView".
CODE="$(mktemp)"; trap 'rm -f "$CODE"' EXIT
python3 - "$AGG" "$CODE" <<'PY'
import io, re, sys
s = io.open(sys.argv[1], encoding="utf-8").read()
s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)     # block + KDoc
s = re.sub(r"^[ \t]*//.*$", "", s, flags=re.M)  # whole-line comments
s = re.sub(r"[ \t]//.*$", "", s, flags=re.M)    # trailing comments
io.open(sys.argv[2], "w", encoding="utf-8").write(s)
PY

echo "== read state is per entry =="
has "$AGG" 'if (r.id.isBlank()) r.ts > visitSeenAt else r.id !in readIds' \
  && ok "T1: isUnread consults the stored read set (watermark only as fallback)" \
  || bad "T1: the unread predicate no longer reads readIds"

has "$AGG" 'val fresh = g.rows.count { it.ts > visitSeenAt }' \
  && ok "T2: the 'N new' chip still counts arrival, not attention" \
  || bad "T2: the N-new chip lost its watermark basis"

has "$AGG" 'if (showMode == "unread") rows.filter { isUnread(it) }' \
  && ok "T3: Show=Unread uses the same predicate the swipe writes" \
  || bad "T3: Show=Unread drifted away from isUnread"

echo "== the swipe does not eat the scroll =="
# The disallow-intercept call must be REACHABLE ONLY under the horizontal
# branch. A bare call outside it would hand every vertical drag to the row and
# freeze the page.
if has "$AGG" 'if (horizontal) v.parent?.requestDisallowInterceptTouchEvent(true)'; then
  n=$(grep -c 'requestDisallowInterceptTouchEvent' "$CODE")
  [ "$n" -eq 1 ] \
    && ok "T4: the only disallow-intercept is guarded by the horizontal test" \
    || bad "T4: $n disallow-intercept calls — one of them is not gated on horizontal"
else
  bad "T4: the horizontal-only guard on requestDisallowInterceptTouchEvent is gone"
fi
has "$AGG" 'horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)' \
  && ok "T4: intent is decided by comparing |dx| to |dy| past the touch slop" \
  || bad "T4: the dx-vs-dy intent test is gone"

echo "== read keys stay bounded =="
for ns in 'PHONE_NS = "phone:"' 'APP_NS   = "app:"' 'fun ntfyNs(topic: String) = "ntfy:$topic:"'; do
  has "$AGG" "$ns" && ok "T5: namespace declared — $ns" || bad "T5: missing namespace $ns"
done
p=$(grep -cF 'StackFilters.pruneRead(' "$CODE")
[ "$p" -eq 3 ] \
  && ok "T5: three prunes, one per namespace (phone, in-app, ntfy-per-topic)" \
  || bad "T5: $p pruneRead calls — expected one per namespace"

# T6 — the ntfy prune must sit AFTER the !result.ok early return, so a failed
# poll cannot prune a topic to empty and resurrect every swiped row.
notok=$(grep -n 'if (!result.ok)' "$CODE" | head -1 | cut -d: -f1)
prune=$(grep -n 'if (topic.isNotBlank()) StackFilters.pruneRead' "$CODE" | head -1 | cut -d: -f1)
if [ -n "$notok" ] && [ -n "$prune" ] && [ "$notok" -lt "$prune" ]; then
  ok "T6: the ntfy prune (line $prune) is behind the unavailable return (line $notok)"
else
  bad "T6: an unavailable ntfy poll can reach the prune — swiped rows would come back"
fi

echo "== persistence =="
has "$FIL" 'fun readKeys' && has "$FIL" 'fun markRead' && has "$FIL" 'fun pruneRead' \
  && ok "T7: StackFilters owns the read set (readKeys/markRead/pruneRead)" \
  || bad "T7: the read-set API is missing from StackFilters"
if grep -q '\.apply()' "$FIL"; then
  bad "T7: apply() in StackFilters — a launcher restart silently drops the write"
else
  ok "T7: every write commits"
fi
has "$FIL" 'HashSet(prefs(ctx).getStringSet' \
  && ok "T7: getStringSet is copied, never mutated in place" \
  || bad "T7: the stored set may be mutated in place (undefined behaviour)"
has "$AGG" 'filterPage = mode' \
  && ok "T7: the page key is set even on a page with no filter row" \
  || bad "T7: filterPage is conditional again — swipes land in a shared blank bucket"

echo "== nothing heavier was pulled in =="
if grep -qE 'RecyclerView|ItemTouchHelper' "$CODE"; then
  bad "T8: a RecyclerView/ItemTouchHelper appeared on the notification path"
else
  ok "T8: still hand-built Views — no RecyclerView, no ItemTouchHelper"
fi
# embedChild is the fixed-host-id pool that renders blank on rebuild; the
# notification path must never reach it.
if awk '/private fun renderNotificationCenter/,/^    }/' "$CODE" | grep -q 'embedChild'; then
  bad "T8: the notification centre now embeds a child fragment"
else
  ok "T8: no child fragment in the notification centre"
fi

echo "== the gesture is answered, and reversible =="
has "$AGG" 'val makeRead = isUnread(r)' \
  && ok "T9: the swipe TOGGLES — swiping a read row marks it unread again" \
  || bad "T9: the swipe no longer toggles, so there is no way back"
has "$AGG" 'paint(!makeRead)' \
  && ok "T9: the row is repainted into its new state" \
  || bad "T9: nothing repaints the row — the swipe would look like it did nothing"
grep -q 'v.animate().translationX(out).alpha(0f)' "$AGG" \
  && ok "T9: the row visibly leaves in the direction it was thrown" \
  || bad "T9: the commit animation is gone"

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
