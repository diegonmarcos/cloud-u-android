#!/usr/bin/env bash
# Tester: pull-down refreshes the Notify pages, the spinner always stops, and
# the gesture never claims a fetch it did not make.
#
# BEFORE: the only way to re-ask the sources was to tap a filter toggle, which
# re-rendered the bodies as a side effect of changing what they showed. There
# was no refresh gesture and no refresh button on this page at all.
#
# NOW: Material's PullToRefreshBox wraps the page's scrolling Column (#773: it was
# the platform SwipeRefreshLayout around a ScrollView; the nested-scroll protocol
# is the same one - a drag below the top scrolls the page, only a pull from the
# very top arms the spinner). The three ways this feature is normally shipped
# broken are each pinned here:
#   T4/T5  a spinner that never stops reads as a hang, so every completion path
#          clears it — including the one where the callback never runs
#   T6/T7  a spinner that always succeeds is a control that misreports state,
#          which this codebase already treats as a defect
#   T3     a naive wrapper either steals the drag or never receives it
#
# Invariants:
#   T1  the Compose Material3 dependency is declared, and the View-era widget is gone
#   T2  the framework component, not a hand-rolled touch listener
#   T3  it wraps the scrolling page, is the page's only host, and is not drawn
#       on the empty-page exits
#   T4  the spinner is cleared on success, on empty AND on failure
#   T5  a watchdog exists, and is scoped to the view — View.post does NOT
#       drop runnables on a view that was attached when it was posted, which
#       is how this page crashed repainting through a detached fragment
#   T6  the gesture is disarmed on a page with nothing to re-query
#   T7  a refresh really re-queries: channels go back to the network instead of
#       being repainted out of the cache
#   T8  the outcome distinguishes "fetched something" from "fetched nothing"
#   T9  unreachable is counted apart and never painted as success
#   T10 a refresh does NOT advance the unread watermark
#   T11 the refresh shares the re-render path with the toggles, and the Archive is
#       DERIVED from it, so it cannot double
#   T12 the pre-existing way to re-render (the filter row) still works — the
#       swipe joined it, it did not replace it
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -qF "$2" "$1" 2>/dev/null; }

DIR="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher"
GRADLE="$APP/app/build.gradle"

# CODE-ONLY view: the KDoc explains the hand-rolled listener it is NOT, and the
# counting assertions must not read prose.
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
FRAG="$TMP/AggregatorStackFragment.kt"
cat "$TMP"/Aggregator*.kt > "$TMP/ALL.kt"; CODE="$TMP/ALL.kt"
for f in "$MODEL" "$STATE" "$NOTIFY" "$FRAG"; do
  [ -f "$f" ] || { echo "  FAIL: $(basename "$f") not found"; exit 1; }
done

echo "== the dependency is declared where the code that needs it lives =="
grep -q "implementation 'androidx.compose.material3:material3'" "$GRADLE" \
  && ok "T1: Compose Material3 (PullToRefreshBox) is an app/ dependency" \
  || bad "T1: the module does not declare material3 — the import will not resolve"
if grep -q "swiperefreshlayout" "$GRADLE"; then
  bad "T1: the View-era swiperefreshlayout dependency is still declared — nothing uses it any more"
else
  ok "T1: no dead swiperefreshlayout dependency"
fi
has "$FRAG" 'import androidx.compose.material3.pulltorefresh.PullToRefreshBox' \
  && ok "T2: the framework component is imported" \
  || bad "T2: the PullToRefreshBox import is gone"
# A hand-rolled pull gesture is the thing the framework component exists to stop
# anyone writing. A touch interceptor or a pointerInput in the fragment would be
# that gesture coming back.
if grep -qE 'onInterceptTouchEvent|onTouchEvent|pointerInput|SwipeRefreshLayout' "$FRAG"; then
  bad "T2: a hand-rolled touch interceptor (or the View widget) is back in the fragment"
else
  ok "T2: no hand-rolled touch interception"
fi

echo "== the hierarchy it wraps actually supports the drag =="
# PullToRefreshBox arms from NESTED SCROLL: it claims a drag only when its child
# scroll container is at the top and is pulled further. So the page must be ONE
# scroll container, directly inside it, and the rows must own none (T4 of the
# swipe tester) - a drag below the top scrolls, a pull at the top spins.
if grep -q 'verticalScroll(scroll)' "$FRAG" && grep -q 'page()' <<<"$(awk '/PullToRefreshBox\(/,/^        }$/' "$FRAG")"; then
  ok "T3: the scrolling page is the PullToRefreshBox's content"
else
  bad "T3: the PullToRefreshBox no longer wraps the scrolling page"
fi
n=$(grep -c 'verticalScroll(' "$FRAG")
[ "$n" -eq 1 ] \
  && ok "T3: exactly one scroll container, so the pull resolves to the page" \
  || bad "T3: $n scroll containers in the fragment — the nested-scroll lookup takes the first"
# EVERY draw of the page goes through the one `page` lambda: the armed host and
# the disarmed branch differ only in the wrapper, so a tab can never lose its
# scroll. The empty-page exits (no section, no panels) draw a hint, not a host.
pg=$(grep -c 'page()' "$FRAG"); pb=$(grep -c 'PullToRefreshBox(' "$FRAG")
if [ "$pg" -eq 2 ] && [ "$pb" -eq 1 ]; then
  ok "T3: one page lambda, drawn inside the host or bare — never twice with different content"
else
  bad "T3: $pg page() draws and $pb hosts — expected 2 and 1"
fi
ex=$(grep -n 'No panels for' "$FRAG" | head -1 | cut -d: -f1); pr=$(grep -n 'PullToRefreshBox(' "$FRAG" | tail -1 | cut -d: -f1)
if [ -n "$ex" ] && [ -n "$pr" ] && [ "$ex" -lt "$pr" ]; then
  ok "T3: the empty-page exits return before any gesture host is composed"
else
  bad "T3: a gesture host can be composed on a page with no panels"
fi

echo "== the spinner always stops =="
c=$(grep -c 'refreshing = false' "$STATE")
[ "$c" -ge 2 ] \
  && ok "T4: the spinner is cleared from $c sites (nothing-to-do, and completion)" \
  || bad "T4: only $c site clears the spinner"
# finishRefresh is the single completion path, and it must be reachable from
# all three ends: synchronous, poll-landed, and gave-up.
f=$(grep -c 'finishRefresh(timedOut = ' "$STATE")
[ "$f" -eq 3 ] \
  && ok "T4: completion is reached from all 3 ends (sync, poll settled, watchdog)" \
  || bad "T4: $f calls to finishRefresh — expected 3"
# The failure path specifically: pollTopics answers for EVERY requested topic on
# every path including total transport failure, so the settle after the paint
# loop is reached whatever came back.
if grep -q 'catch (_: Throwable)' <<<"$(awk '/fun pollTopics/,/^    }$/' "$MODEL")"; then
  ok "T4: a poll that fails outright still returns a verdict per topic"
else
  bad "T4: pollTopics can now escape without answering — the counter would stick"
fi
if grep -q 'if (counted) pollSettled()' <<<"$(awk '/suspend fun pollPanel/,/^    }$/' "$STATE")"; then
  ok "T4: the poll settles on the one path every outcome reaches"
else
  bad "T4: the poll no longer settles — the spinner would run forever"
fi
# T5: the watchdog waits on the COMPOSITION's scope (it is a parameter of
# startRefresh, and the page hands in rememberCoroutineScope()), so it is
# cancelled with the view instead of firing finishRefresh into a dead Context.
if grep -q 'postDelayed' "$CODE"; then
  bad "T5: the watchdog is back on postDelayed — it outlives the view it repaints"
elif grep -q 'scope.launch' <<<"$(awk '/fun startRefresh/,/^    }$/' "$STATE")" \
     && grep -q 'delay(AggregatorModel.REFRESH_TIMEOUT_MS)' <<<"$(awk '/fun startRefresh/,/^    }$/' "$STATE")"; then
  ok "T5: a watchdog backstops a callback that never runs, and dies with the view"
else
  bad "T5: no watchdog — a poll that never settles hangs the spinner forever"
fi

echo "== the gesture does not pretend =="
has "$FRAG" 'if (st.refreshable)' && has "$STATE" 'val refreshable: Boolean = panels.any { it.kind in AggregatorModel.REFRESHABLE_KINDS }' \
  && ok "T6: disarmed on a page with nothing to re-query" \
  || bad "T6: the gesture is armed regardless of whether anything can be fetched"
has "$STATE" 'val slots = if (forced) topics else topics.filter { it !in ntfyCache }' \
  && ok "T7: a refresh sends the channels back to the network, not to the cache" \
  || bad "T7: a refresh would repaint the cache and call it a fetch"
has "$STATE" 'pollEpoch++' \
  && ok "T7: the refresh bumps the poll epoch every card compares against" \
  || bad "T7: nothing forces the re-poll"
# THE HONESTY RULE. A refresh that brought nothing must not read like one that
# brought something, so the outcome is a COUNT of what was not there before.
has "$STATE" 'val fresh = displayedRowIds().count { it !in refreshBefore }' \
  && ok "T8: the outcome counts what actually arrived" \
  || bad "T8: the outcome no longer distinguishes a fetch from an empty fetch"
grep -q '"nothing new"' "$STATE" && grep -q '"\$fresh new"' "$STATE" \
  && ok "T8: the two results say different things" \
  || bad "T8: the empty and non-empty results are no longer distinct"
# And a channel we could not read is not a channel that was quiet.
has "$STATE" 'val unreachable = ntfyCache.count { !it.value.ok }' \
  && ok "T9: channels that could not be reached are counted apart" \
  || bad "T9: an unreachable channel is folded into the success count"
if grep -q 'timedOut || unreachable > 0 -> Tone.UNKNOWN' <<<"$(awk '/val tone = when \{/,/^        }$/' "$STATE")"; then
  ok "T9: any unknown in the answer paints grey, never the green of a clean result"
else
  bad "T9: an incomplete measurement can be painted as a clean result"
fi

echo "== the refresh does not damage what the page already knew =="
# Advancing the watermark here would clear every "N new" chip at the moment the
# refresh had just earned them. (The state advances it ONCE, in its init.)
if grep -q 'markSeen' <<<"$(awk '/fun startRefresh/,/^    }$/' "$STATE")"; then
  bad "T10: a refresh advances the watermark and erases its own result"
else
  ok "T10: the unread watermark is left alone by a refresh"
fi
# Both re-render paths bump the one epoch every notification body reads, and the
# Archive is DERIVED from the bodies' own groups (a derivedStateOf over allViews,
# nothing appended), so a refresh can no more add a second copy of every archived
# box than a toggle tap can.
b=$(grep -c 'epoch++' <<<"$(awk '/fun select\(/,/^    }$/;/fun startRefresh/,/^    }$/' "$STATE")")
[ "$b" -eq 2 ] \
  && ok "T11: one re-render path (epoch), bumped by the toggle row and by the gesture" \
  || bad "T11: $b epoch bumps among select()/startRefresh() — expected 2"
if grep -q 'derivedStateOf' <<<"$(awk '/internal fun ArchiveSection/,/^}$/' "$NOTIFY")" \
   && ! grep -q 'addView' "$NOTIFY"; then
  ok "T11: the Archive is derived from the groups each render, so a refresh cannot double it"
else
  bad "T11: the Archive is no longer derived — boxes will double"
fi

echo "== the swipe joined the existing way to update, it did not replace it =="
# There was no refresh BUTTON on this page to remove. What did exist is the
# filter row, which re-renders the bodies as a side effect; it still does.
if grep -q 'epoch++' <<<"$(awk '/fun select\(/,/^    }$/' "$STATE")"; then
  ok "T12: the filter row still re-renders the bodies"
else
  bad "T12: the toggle row lost its re-render — the swipe replaced it"
fi

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
