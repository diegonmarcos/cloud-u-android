#!/usr/bin/env bash
# Tester: pull-down refreshes the Notify pages, the spinner always stops, and
# the gesture never claims a fetch it did not make.
#
# BEFORE: the only way to re-ask the sources was to tap a filter toggle, which
# re-rendered the bodies as a side effect of changing what they showed. There
# was no refresh gesture and no refresh button on this page at all.
#
# NOW: the platform SwipeRefreshLayout wraps the page's ScrollView. The three
# ways this feature is normally shipped broken are each pinned here:
#   T4/T5  a spinner that never stops reads as a hang, so every completion path
#          clears it — including the one where the callback never runs
#   T6/T7  a spinner that always succeeds is a control that misreports state,
#          which this codebase already treats as a defect
#   T3     a naive wrapper either steals the drag or never receives it
#
# Invariants:
#   T1  androidx.swiperefreshlayout is a declared dependency of THIS module
#   T2  the platform widget, not a hand-rolled touch listener
#   T3  it wraps the ScrollView, holds exactly one child, and is what the
#       fragment returns from every exit in onCreateView
#   T4  the spinner is cleared on success, on empty AND on failure
#   T5  a watchdog exists, because View.post drops runnables on a detached view
#   T6  the gesture is disarmed on a page with nothing to re-query
#   T7  a refresh really re-queries: channels go back to the network instead of
#       being repainted out of the cache
#   T8  the outcome distinguishes "fetched something" from "fetched nothing"
#   T9  unreachable is counted apart and never painted as success
#   T10 a refresh does NOT advance the unread watermark
#   T11 the refresh shares the rebuild path, so it cannot double the Archive
#   T12 the pre-existing way to re-render (the filter row) still works — the
#       swipe joined it, it did not replace it
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -qF "$2" "$1" 2>/dev/null; }

AGG="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/AggregatorStackFragment.kt"
GRADLE="$APP/app/build.gradle"

# CODE-ONLY view: the KDoc explains the hand-rolled listener it is NOT, and the
# counting assertions must not read prose.
CODE="$(mktemp)"; trap 'rm -f "$CODE"' EXIT
python3 - "$AGG" "$CODE" <<'PY'
import io, re, sys
s = io.open(sys.argv[1], encoding="utf-8").read()
s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
s = re.sub(r"^[ \t]*//.*$", "", s, flags=re.M)
s = re.sub(r"[ \t]//.*$", "", s, flags=re.M)
io.open(sys.argv[2], "w", encoding="utf-8").write(s)
PY

echo "== the dependency is declared where the code that needs it lives =="
grep -q "implementation 'androidx.swiperefreshlayout:swiperefreshlayout:" "$GRADLE" \
  && ok "T1: swiperefreshlayout is an app/ dependency ($(grep -o "swiperefreshlayout:[0-9.]*" "$GRADLE" | tail -1))" \
  || bad "T1: the module does not declare swiperefreshlayout — the import will not resolve"
has "$AGG" 'import androidx.swiperefreshlayout.widget.SwipeRefreshLayout' \
  && ok "T2: the platform widget is imported" \
  || bad "T2: the SwipeRefreshLayout import is gone"
# A hand-rolled pull gesture is the thing the platform widget exists to stop
# anyone writing. onInterceptTouchEvent/onTouchEvent in this fragment would be
# that gesture coming back.
if grep -qE 'override fun (onInterceptTouchEvent|onTouchEvent)' "$CODE"; then
  bad "T2: a hand-rolled touch interceptor is back in the fragment"
else
  ok "T2: no hand-rolled touch interception"
fi

echo "== the hierarchy it wraps actually supports the drag =="
# SwipeRefreshLayout takes its FIRST non-spinner child as the drag target and
# asks it canScrollVertically(-1) before claiming a drag. One child, and that
# child a ScrollView, is what makes the lookup find the right view: a drag
# below the top scrolls, a pull at the top spins.
if awk '/val host = SwipeRefreshLayout\(ctx\)/,/^        }$/' "$CODE" | grep -q 'addView(scroll)'; then
  ok "T3: the ScrollView is the SwipeRefreshLayout's child"
else
  bad "T3: the SwipeRefreshLayout no longer wraps the ScrollView"
fi
n=$(awk '/val host = SwipeRefreshLayout\(ctx\)/,/^        }$/' "$CODE" | grep -c 'addView(')
[ "$n" -eq 1 ] \
  && ok "T3: exactly one child, so the drag target resolves to the ScrollView" \
  || bad "T3: $n children added to the host — the target lookup takes the first"
# EVERY exit from onCreateView must hand back the host. A path that still
# returns the bare ScrollView is a tab with no gesture at all.
if grep -q 'return scroll$' "$CODE"; then
  bad "T3: an onCreateView path still returns the bare ScrollView — no gesture there"
else
  r=$(grep -c 'return host$' "$CODE")
  [ "$r" -eq 3 ] && ok "T3: all $r onCreateView exits return the gesture host" \
    || bad "T3: $r exits return host — expected 3"
fi

echo "== the spinner always stops =="
c=$(grep -c 'isRefreshing = false' "$CODE")
[ "$c" -ge 2 ] \
  && ok "T4: the spinner is cleared from $c sites (nothing-to-do, and completion)" \
  || bad "T4: only $c site clears the spinner"
# finishRefresh is the single completion path, and it must be reachable from
# all three ends: synchronous, poll-landed, and gave-up.
f=$(grep -c 'finishRefresh(timedOut = ' "$CODE")
[ "$f" -eq 3 ] \
  && ok "T4: completion is reached from all 3 ends (sync, poll settled, watchdog)" \
  || bad "T4: $f calls to finishRefresh — expected 3"
# The failure path specifically: a poll that could not even be scheduled must
# settle, or the counter never reaches zero.
if awk '/}.onFailure \{/,/^        }$/' "$CODE" | grep -q 'ntfyPollSettled()'; then
  ok "T4: the poll FAILURE path settles too, not just the success path"
else
  bad "T4: a failed poll never settles — the spinner would run forever"
fi
has "$AGG" 'host.postDelayed({' \
  && ok "T5: a watchdog backstops the case where a callback never runs at all" \
  || bad "T5: no watchdog — a detached view drops View.post and hangs the spinner"

echo "== the gesture does not pretend =="
has "$AGG" 'host.isEnabled = bodyRefreshers.isNotEmpty()' \
  && ok "T6: disarmed on a page with nothing to re-query" \
  || bad "T6: the gesture is armed regardless of whether anything can be fetched"
has "$AGG" 'if (cached == null || ntfyForceRepoll) slots[topic] = state to rowsBox' \
  && ok "T7: a refresh sends the channels back to the network, not to the cache" \
  || bad "T7: a refresh would repaint the cache and call it a fetch"
has "$AGG" 'ntfyForceRepoll = true' \
  && ok "T7: the refresh sets the force-repoll flag" \
  || bad "T7: nothing forces the re-poll"
# THE HONESTY RULE. A refresh that brought nothing must not read like one that
# brought something, so the outcome is a COUNT of what was not there before.
has "$AGG" 'val fresh = pageRowIds.count { it !in refreshBefore }' \
  && ok "T8: the outcome counts what actually arrived" \
  || bad "T8: the outcome no longer distinguishes a fetch from an empty fetch"
grep -q '"nothing new"' "$CODE" && grep -q '"\$fresh new"' "$CODE" \
  && ok "T8: the two results say different things" \
  || bad "T8: the empty and non-empty results are no longer distinct"
# And a channel we could not read is not a channel that was quiet.
has "$AGG" 'val unreachable = ntfyCache.count { !it.value.ok }' \
  && ok "T9: channels that could not be reached are counted apart" \
  || bad "T9: an unreachable channel is folded into the success count"
if awk '/val color = when \{/,/^        }$/' "$CODE" | grep -q 'timedOut || unreachable > 0 -> SIGNAL_UNKNOWN'; then
  ok "T9: any unknown in the answer paints grey, never the green of a clean result"
else
  bad "T9: an incomplete measurement can be painted as a clean result"
fi

echo "== the refresh does not damage what the page already knew =="
# Advancing the watermark here would clear every "N new" chip at the moment the
# refresh had just earned them.
if awk '/private fun startRefresh/,/^    }$/' "$CODE" | grep -q 'markSeen'; then
  bad "T10: a refresh advances the watermark and erases its own result"
else
  ok "T10: the unread watermark is left alone by a refresh"
fi
# Both re-render paths go through the one rebuild, which empties the Archive
# first — a refresh that skipped it would add a second copy of every archived
# box, the same bug a toggle tap already had.
b=$(grep -c 'rebuildBodies()' "$CODE")
[ "$b" -eq 3 ] \
  && ok "T11: one rebuild path, called by the toggle row and by the gesture" \
  || bad "T11: $b rebuildBodies sites — expected 3 (definition + 2 callers)"
if awk '/private fun rebuildBodies/,/^    }$/' "$CODE" | grep -q 'archiveBox?.removeAllViews()'; then
  ok "T11: the shared rebuild empties the Archive before the bodies re-file"
else
  bad "T11: the rebuild no longer empties the Archive — boxes will double"
fi

echo "== the swipe joined the existing way to update, it did not replace it =="
# There was no refresh BUTTON on this page to remove. What did exist is the
# filter row, which re-renders the bodies as a side effect; it still does.
if awk '/private fun onFilterChanged/,/^    }$/' "$CODE" | grep -q 'rebuildBodies()'; then
  ok "T12: the filter row still re-renders the bodies"
else
  bad "T12: the toggle row lost its re-render — the swipe replaced it"
fi

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
