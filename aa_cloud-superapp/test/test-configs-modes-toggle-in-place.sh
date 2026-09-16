#!/usr/bin/env bash
# #349 — Configs ▸ Launcher ▸ Modes: tapping a toggle reloaded the page AND
# landed on a different tab.
#
# That is two defects wearing one coat, and this file asserts BOTH, because
# either one alone leaves the report half true:
#
#   (a) THE REBUILD. onToggleChanged called ShellActivity.notifyLauncherThemeChanged,
#       which is the MODE-change hook and whose body is
#       LauncherStyle.restartForModeChange() -> Activity.recreate(). Twenty-four
#       switches each paid for a whole-Activity rebuild. On top of that every
#       flip called rerenderPage() — detach+attach of the fragment — to change
#       one badge and one derived label, taking the scroll position with it.
#
#   (b) THE LOST TAB. LauncherNavController.activeTabBySection is a plain field
#       of a controller that is itself a field of the Activity, so recreate()
#       destroys it; SectionTabsFragment.startIndex then read "" and fell
#       through to the first content page — Profiles. Fixing (a) only HIDES (b)
#       until the next honest reload, and a mode change is still an honest
#       reload, so (b) has to be fixed on its own.
#
# What this asserts:
#   T1  the mode-change hook really is an Activity recreate — read out of the
#       Kotlin, so T2/T3 are guarding something real rather than a name
#   T2  onToggleChanged does NOT reach that hook, and DOES call the two things
#       it always said it called: the idempotent public chrome re-apply and the
#       live push for the shell-owned views
#   T3  only the MODE tile may reach the recreate hook — it has to, a Material3
#       style is resolved once per Activity at inflate time
#   T4  no control on the Modes page answers a flip with rerenderPage
#   T5  a toggle tile paints ITSELF from the store: there is a repaint registry,
#       the tile registers in it, and it takes the prefs rather than a boolean
#       snapshot it would have to be rebuilt to refresh
#   T6  the master switch detaches its listener before a programmatic sync —
#       without that, following its children writes the master's position back
#       over every one of them
#   T7  the controller's tab memory is STILL Activity-scoped, so T8 guards
#       something real
#   T8  SectionTabsFragment saves its selected page into its own instance state
#       and startIndex prefers it — over `initial_page` as well, since arguments
#       survive a recreate too and would otherwise re-answer a stale deep link
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp"
CFG="$KT/settings/LauncherConfigFragment.kt"
UI="$KT/settings/LauncherSettingsUi.kt"
STYLE="$KT/ui/LauncherStyle.kt"
SHELL_KT="$KT/ShellActivity.kt"
TABS="$KT/launcher/SectionTabsFragment.kt"
NAV="$KT/launcher/LauncherNavController.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

for f in "$CFG" "$UI" "$STYLE" "$SHELL_KT" "$TABS" "$NAV"; do
  [ -f "$f" ] || { bad "missing source: $f"; echo "PASS=$PASS FAIL=$FAIL"; exit 1; }
done

# The body of a named Kotlin function, from its signature to the matching
# closing brace at the same indent. Used instead of grepping the whole file so
# "does onToggleChanged call X" cannot be answered by some other function
# calling X forty lines away.
body() { # body <file> <indent-spaces> <signature-fragment>
  awk -v sig="$3" -v ind="$2" '
    index($0, sig) { f=1 }
    f { print }
    f && $0 == ind "}" { exit }
  ' "$1"
}

echo "== (a) the rebuild: a toggle is not a mode =="

# ── T1 ────────────────────────────────────────────────────────────────────────
# Read the claim "notifyLauncherThemeChanged recreates the Activity" out of the
# tree rather than asserting it from memory. If someone makes that hook cheap,
# this test says so instead of going on policing a call that no longer costs
# anything.
HOOK="$(body "$SHELL_KT" "    " "fun notifyLauncherThemeChanged()")"
RESTART="$(body "$STYLE" "    " "fun restartForModeChange(activity: Activity)")"
if printf '%s' "$HOOK" | grep -q 'restartForModeChange' &&
   printf '%s' "$RESTART" | grep -q 'activity.recreate()'; then
  ok "T1: notifyLauncherThemeChanged -> restartForModeChange -> Activity.recreate()"
else
  bad "T1: could not read the recreate out of the mode-change hook — T2/T3 would assert nothing"
fi

# ── T2 ────────────────────────────────────────────────────────────────────────
TOGGLED="$(body "$CFG" "    " "private fun onToggleChanged()")"
if [ -z "$TOGGLED" ]; then
  bad "T2: onToggleChanged not found"
else
  if printf '%s' "$TOGGLED" | grep -q 'notifyLauncherThemeChanged'; then
    bad "T2: onToggleChanged still asks for a MODE change — every switch rebuilds the Activity (#349a)"
  else
    ok "T2: onToggleChanged does not reach the recreate hook"
  fi
  # It still has to DO the work the recreate was standing in for, or the fix is
  # a regression wearing a green tester: the stars/pets/island views live in the
  # activity shell and cannot re-read themselves.
  printf '%s' "$TOGGLED" | grep -q 'applyLauncherChrome()' \
    && ok "T2: it re-applies the launcher chrome (the public, idempotent hook)" \
    || bad "T2: onToggleChanged no longer re-applies the launcher chrome"
  printf '%s' "$TOGGLED" | grep -q 'applyShellLiveToggles' \
    && ok "T2: it pushes the shell-owned toggles (stars / pets / island) live" \
    || bad "T2: nothing re-applies stars/pets/island — the recreate was doing that"
fi
grep -q 'internal fun applyShellLiveToggles' "$UI" \
  && ok "T2: applyShellLiveToggles is shared chrome, not a private copy in the fragment" \
  || bad "T2: applyShellLiveToggles is not in LauncherSettingsUi.kt (#228: no second copy)"

# ── T3 ────────────────────────────────────────────────────────────────────────
# A mode MUST recreate. Exactly one call site, and it is the mode tile.
HOOK_CALLS="$(grep -c 'notifyLauncherThemeChanged()' "$CFG")"
if [ "$HOOK_CALLS" = "1" ]; then
  ok "T3: exactly one call to the recreate hook on this page — the mode tile"
else
  bad "T3: $HOOK_CALLS calls to notifyLauncherThemeChanged in the Modes page (want exactly 1)"
fi

# ── T4 ────────────────────────────────────────────────────────────────────────
# rerenderPage is detach+attach of the whole fragment. Nothing a SWITCH does may
# reach it. (The Power Saving slot dialog at the very bottom of the page still
# uses it; that is a dialog choice, not a toggle, and belongs to #340's surface.)
for fn in "private fun toggleTile" "private fun toggleGrid" "private fun toggleRow" \
          "private fun onToggleChanged"; do
  if body "$CFG" "    " "$fn" | grep -q 'rerenderPage'; then
    bad "T4: ${fn##* } still rebuilds the page on a flip (#349a)"
  else
    ok "T4: ${fn##* } does not rebuild the page"
  fi
done
# The flip handlers are LAMBDAS passed from onCreateView, so the four functions
# above can be clean while every call site rebuilds — which is exactly how this
# shipped. onCreateView is where the mode tiles, the screensaver picker, the
# master and the grid are all wired, and none of them may reach rerenderPage.
if body "$CFG" "    " "override fun onCreateView(" | grep -q 'rerenderPage'; then
  bad "T4: a control wired in onCreateView still answers a tap with a full page rebuild (#349a)"
else
  ok "T4: nothing wired in onCreateView rebuilds the page"
fi
# One use is allowed to remain in the whole file: the Power Saving slot chooser,
# a DIALOG choice on #340's surface rather than a toggle. Two would mean a flip
# handler crept back.
RERENDERS="$(grep -c 'rerenderPage()' "$CFG")"
if [ "$RERENDERS" -le 1 ]; then
  ok "T4: $RERENDERS rerenderPage call left in the page (the slot dialog; #340's surface)"
else
  bad "T4: $RERENDERS rerenderPage calls in the Modes page — a flip handler is rebuilding again"
fi
if grep -q 'repaintFromStore()' "$CFG"; then
  ok "T4: flips go through repaintFromStore instead"
else
  bad "T4: there is no in-place repaint path — a flip has nothing to do but rebuild"
fi

echo "== a row repaints itself, from the store, in place =="

# ── T5 ────────────────────────────────────────────────────────────────────────
grep -q 'private val repaints = mutableListOf<() -> Unit>()' "$CFG" \
  && ok "T5: the page has a repaint registry" \
  || bad "T5: no repaint registry on the Modes page"
grep -q 'repaints.clear()' "$CFG" \
  && ok "T5: the registry is emptied by onCreateView, so it cannot hold dead views" \
  || bad "T5: the registry is never cleared — a rebuilt page would repaint detached views"
TILE="$(body "$CFG" "    " "private fun toggleTile(")"
if printf '%s' "$TILE" | grep -q 'prefs: LauncherSettingsPrefs'; then
  ok "T5: toggleTile takes the store, not an on/off snapshot"
else
  bad "T5: toggleTile still takes a captured boolean — it can only refresh by being rebuilt"
fi
printf '%s' "$TILE" | grep -q 'repaints +=' \
  && ok "T5: a tile registers its own repaint" \
  || bad "T5: a tile registers no repaint — it cannot follow a master flip"
printf '%s' "$TILE" | grep -q 'onFlip(item, !prefs.toggle(item))' \
  && ok "T5: a tap reads the CURRENT stored value, not the one captured at build time" \
  || bad "T5: the tap still flips a captured value — right once, wrong every time after"

# ── T6 ────────────────────────────────────────────────────────────────────────
ROW="$(body "$CFG" "    " "private fun toggleRow(")"
if printf '%s' "$ROW" | grep -q 'checked: () -> Boolean'; then
  ok "T6: the master asks its children rather than being told once"
else
  bad "T6: the master takes a snapshot boolean — a child flip cannot move it"
fi
if printf '%s' "$ROW" | grep -q 'setOnCheckedChangeListener(null)'; then
  ok "T6: the master detaches its listener before a programmatic sync"
else
  bad "T6: syncing the master re-enters onChange and writes its position over every child"
fi

echo "== (b) the lost tab: the strip has to survive the recreate =="

# ── T7 ────────────────────────────────────────────────────────────────────────
# The reason saved state is needed at all. If the controller's map ever becomes
# process- or disk-scoped, this says so rather than leaving T8 defending a
# problem that moved.
if grep -q 'private val activeTabBySection = mutableMapOf<String, String>()' "$NAV" &&
   grep -q 'internal val nav = LauncherNavController(this)' "$SHELL_KT"; then
  ok "T7: the tab memory is a field of a controller the Activity owns — recreate() takes it"
else
  bad "T7: the controller's tab memory moved — re-read why SectionTabsFragment saves its own"
fi

# ── T8 ────────────────────────────────────────────────────────────────────────
grep -q 'override fun onSaveInstanceState' "$TABS" \
  && ok "T8: the strip saves its own state" \
  || bad "T8: the strip saves nothing — a recreate drops it back on tab 0 (#349b)"
grep -q 'outState.putString(STATE_SELECTED_PAGE, selectedPageId)' "$TABS" \
  && ok "T8: what it saves is the selected page id" \
  || bad "T8: the selected page is not what gets saved"
START="$(body "$TABS" "    " "private fun startIndex(")"
if printf '%s' "$START" | grep -q 'saved?.getString(STATE_SELECTED_PAGE)'; then
  ok "T8: startIndex reads the restored page"
else
  bad "T8: startIndex ignores the restored page — saving it changes nothing"
fi
# Order matters and is the subtle half: `arguments` are restored across a
# recreate too, so if initial_page were consulted first, a strip opened by a
# deep link would re-answer that deep link forever and undo every tab the user
# picked after it.
if printf '%s' "$START" | awk '
    /STATE_SELECTED_PAGE/ { saved = NR }
    /ARG_INITIAL_PAGE/    { arg = NR }
    END { exit !(saved && arg && saved < arg) }'; then
  ok "T8: restored state is preferred over initial_page, which also survives a recreate"
else
  bad "T8: initial_page is consulted before the restored tab — a deep link would re-fire on every recreate"
fi

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
