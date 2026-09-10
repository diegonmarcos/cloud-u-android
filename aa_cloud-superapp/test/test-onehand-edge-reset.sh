#!/usr/bin/env bash
# Tester: Configs > Launcher > One-Hand > Edge Menu — "Reset to default".
#
# WHY THIS EXISTS. Task #258 gave both edge menus a new twelve-entry default in
# build.json, and nobody could see it: OneHandPrefs.actionFor returns a stored
# slot override AHEAD of the baked default, so any phone that had ever opened
# the Configs page had every sector shadowed. The owner asked for a button he
# presses himself rather than a silent migration. Four ways that button can be
# built wrong, none of which a compiler or a screenshot would catch:
#
#   1. IT WRITES INSTEAD OF DELETING. A reset that stores today's defaults looks
#      identical on the day it ships and diverges forever after: every LATER
#      change to onehand.handles is shadowed by what the button wrote, so the
#      owner is pinned to the 2026-09-10 layout and has to press reset after
#      every update without ever being told why. This is the single most
#      valuable assertion here, and it is T3.
#   2. IT TAKES SETTINGS THAT ARE NOT THE MENU WITH IT. The stars, the floating
#      button and its dragged position, the four Home swipes, the activation
#      mode and the master on/off are separate concerns. A reset that wipes any
#      of them is a data-loss bug wearing a helpful label. T5 and T6.
#   3. IT FIRES WITHOUT ASKING. It destroys work only the owner can reproduce.
#      T2 requires the dialog to stand between the tap and the delete.
#   4. IT CLEARS THE STORE AND LEAVES THE OLD ENTRIES ON SCREEN. Which reads as
#      "the button does nothing" and gets the task reported as not done. T8.
#
# Static tester: no device, no build. build.json is read as data; the Kotlin is
# checked for the contracts that data relies on.
#
# TWO FALSE-GREEN SHAPES WERE WATCHED HAPPENING IN THIS FILE BEFORE IT LANDED,
# and both are why it is written the way it is:
#   * `set -o pipefail` plus `code "$f" | grep -q PATTERN` reports FAILURE on a
#     match: grep -q exits the moment it finds one, the upstream grep dies of
#     SIGPIPE, and pipefail returns that 141. Every text assertion here reads a
#     CAPTURED variable through a herestring, never a live pipeline.
#   * a body() that ended the function at the next line matching `val ` ended it
#     at the function's own first local, handing an EMPTY body to the assertions
#     built on it — and "this function contains no put()" is trivially true of
#     nothing. Extraction is by indentation, and T3 refuses to judge an empty
#     body.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # -> aa_cloud-superapp
LIBS="$APP/../ab_cloud-libs-shared/libs"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

BJ="$APP/build.json"
FRAG="$APP/app/src/main/java/com/diegonmarcos/superapp/configs/OneHandFragment.kt"
PREFS="$LIBS/launcher-onehand/src/main/java/com/diegonmarcos/superapp/onehand/OneHandPrefs.kt"
CONFIG="$LIBS/launcher-onehand/src/main/java/com/diegonmarcos/superapp/onehand/OneHandConfig.kt"
GESTURE="$LIBS/launcher-onehand/src/main/java/com/diegonmarcos/superapp/onehand/GestureAction.kt"
CONTROLLER="$LIBS/launcher-onehand/src/main/java/com/diegonmarcos/superapp/onehand/OneHandController.kt"
SERVICE="$LIBS/launcher-onehand/src/main/java/com/diegonmarcos/superapp/onehand/OneHandAccessibilityService.kt"
HOMESWIPE="$APP/app/src/main/java/com/diegonmarcos/superapp/settings/HomeSwipePrefs.kt"
FLOATPREFS="$APP/app/src/main/java/com/diegonmarcos/superapp/floatingnav/FloatingNavPrefs.kt"
STRINGS="$APP/app/src/main/res/values/strings.xml"
STRINGS_ES="$APP/app/src/main/res/values-es/strings.xml"

# FAIL CLOSED. Four testers in this repository once passed only because the tool
# they called was absent and the call failed open. Every derived answer below
# runs through python3; if it is not here, nothing has been proven.
if ! command -v python3 >/dev/null 2>&1; then
  echo "  FAIL: python3 is not on PATH — this tester proves nothing without it"
  echo "== RESULT: 0 passed, 1 failed =="
  exit 1
fi
for f in "$BJ" "$FRAG" "$PREFS" "$CONFIG" "$GESTURE" "$CONTROLLER" "$SERVICE" \
         "$HOMESWIPE" "$FLOATPREFS" "$STRINGS" "$STRINGS_ES"; do
  if [ ! -f "$f" ]; then
    echo "  FAIL: missing file $f — the tree is not what this tester was written against"
    echo "== RESULT: 0 passed, 1 failed =="
    exit 1
  fi
done

# ── two helpers everything below is built on ──────────────────────────────

# code <file> — the file with comment lines removed. A KDoc that SPELLS an
# example is not a call: GestureAction.kt documents the serialized form with the
# literal "app:com.brave.browser", and a plain grep for a hardcoded default
# therefore finds prose and reports code. Every text assertion here reads this,
# never the raw file.
code() { grep -vE '^[[:space:]]*(//|\*|/\*)' "$1"; }

# body <file> <function-name> — one Kotlin function, comments stripped.
#
# BY INDENTATION. Everything belonging to a function is indented deeper than its
# own `fun` line, so the body runs to the first non-blank line at or left of that
# column (its closing brace, or the next member for an expression body).
#
# Brace counting is deliberately NOT used: a string template such as
# "showHandles: ${c.handles.size} handles" carries braces inside a literal and
# throws the count off. Matching on `val`/`fun` is not used either — that ends
# the body at the function's own first local and returns nothing at all.
body() {
  awk -v want="$2" '
    function indent(s,   i) { i = match(s, /[^ ]/); return i ? i - 1 : -1 }
    !inside && $0 ~ ("(^|[^A-Za-z])fun " want "\\(") { inside = 1; base = indent($0); print; next }
    inside {
      ind = indent($0)
      if (ind == -1 || ind > base) { print; next }
      if ($0 ~ /^ *\}/) { print }
      exit
    }
  ' "$1" | grep -vE '^[[:space:]]*(//|\*|/\*)'
}

echo "== T1: the control is on the EDGE-MENU surface, and only there =="
# The page hosts stars, edge menu, floating button and Home swipes in one
# scroll. The owner said edge menu. Anchored between this file's own two
# section comments, so the button cannot drift into a neighbouring block
# without this failing.
SECTION="$(awk '/3\. Floating menu/ { exit } /2\. Edge menu/ { inside = 1 } inside' "$FRAG")"
if grep -qF 'R.string.onehand_edge_reset_button' <<<"$SECTION"; then
  ok "the reset button is added inside the Edge Menu section of the page"
else
  bad "the reset button is not in the Edge Menu section — it landed on another surface"
fi
# One button for BOTH handles: the twelve entries were one design in two
# blocks. A per-handle reset would leave the owner with half of it.
RESET_BUTTONS=$(code "$FRAG" | grep -cF 'R.string.onehand_edge_reset_button')
[ "$RESET_BUTTONS" = "1" ] \
  && ok "exactly one reset control exists — one press covers both handles" \
  || bad "$RESET_BUTTONS reset controls in the fragment — the owner asked for one design, not two halves"
# The other reset on this page belongs to the floating button and must stay
# a different action with a different name.
code "$FRAG" | grep -qF 'FloatingNavService.resetPosition' \
  && ok "the floating button keeps its own separate 'reset position' action" \
  || bad "FloatingNavService.resetPosition has gone — an unrelated feature lost its control"

echo "== T2: the reset cannot fire without the confirmation =="
CONFIRM="$(body "$FRAG" confirmEdgeMenuReset)"
CLEAR_CALLS=$(code "$FRAG" | grep -cF 'OneHandPrefs.clearEdgeMenuOverrides(')
[ "$CLEAR_CALLS" = "1" ] \
  && ok "the fragment calls clearEdgeMenuOverrides exactly once" \
  || bad "$CLEAR_CALLS live calls to clearEdgeMenuOverrides — a second one is a path around the dialog"
grep -qF 'OneHandPrefs.clearEdgeMenuOverrides(' <<<"$CONFIRM" \
  && ok "and that call is inside confirmEdgeMenuReset" \
  || bad "the clear is not inside confirmEdgeMenuReset — it fires without asking"
# Order matters: the delete has to sit INSIDE the positive-button lambda, not
# before the builder. Compare line numbers within the extracted function.
POS_LINE=$(grep -nF 'setPositiveButton' <<<"$CONFIRM" | head -1 | cut -d: -f1)
DEL_LINE=$(grep -nF 'OneHandPrefs.clearEdgeMenuOverrides(' <<<"$CONFIRM" | head -1 | cut -d: -f1)
if [ -n "$POS_LINE" ] && [ -n "$DEL_LINE" ] && [ "$DEL_LINE" -gt "$POS_LINE" ]; then
  ok "the delete is inside the positive button's handler (line $DEL_LINE after setPositiveButton at $POS_LINE)"
else
  bad "the delete does not follow setPositiveButton (positive='$POS_LINE' delete='$DEL_LINE') — it may run on open"
fi
grep -qF 'setNegativeButton' <<<"$CONFIRM" \
  && ok "the dialog offers a way out that changes nothing" \
  || bad "the dialog has no negative button — the only exit is to reset"
# The button must open the dialog, never the delete.
BTN_BLOCK="$(awk '/onehand_edge_reset_button/ { inside = 1 } inside; inside && /\}\)/ { exit }' "$FRAG")"
grep -qF 'confirmEdgeMenuReset(' <<<"$BTN_BLOCK" \
  && ok "the button's click handler opens the confirmation" \
  || bad "the button does not call confirmEdgeMenuReset"
grep -qF 'clearEdgeMenuOverrides' <<<"$BTN_BLOCK" \
  && bad "the button deletes directly — the confirmation is decoration" \
  || ok "the button does not delete directly"
# The dialog must SAY WHAT IS LOST, not ask "are you sure". The English message
# is checked for the three things it has to name.
MSG=$(python3 -c "
import xml.etree.ElementTree as ET
root = ET.parse('$STRINGS').getroot()
print(next((''.join(e.itertext()) for e in root if e.get('name') == 'onehand_edge_reset_message'), ''))")
case "$MSG" in
  *"deleted from this phone"*) ok "the message says the sectors are deleted from the phone" ;;
  *) bad "the confirmation does not say what is deleted: '$MSG'" ;;
esac
case "$MSG" in
  *"cannot be undone"*) ok "and that it cannot be undone" ;;
  *) bad "the confirmation does not say the loss is permanent" ;;
esac
case "$MSG" in
  *"NOT touched"*) ok "and lists what survives, on a page holding four unrelated groups of settings" ;;
  *) bad "the confirmation never says what it leaves alone" ;;
esac

echo "== T3: it DELETES the keys — it does not write today's defaults back =="
# THE ASSERTION THIS WHOLE TESTER EXISTS FOR. A reset that persists the current
# defaults shadows every default shipped after it, forever.
CLEARBODY="$(body "$PREFS" clearEdgeMenuOverrides)"
if [ -z "$CLEARBODY" ]; then
  bad "clearEdgeMenuOverrides does not exist in OneHandPrefs — there is no reset to test"
else
  grep -qE '\.remove\(' <<<"$CLEARBODY" \
    && ok "clearEdgeMenuOverrides removes keys" \
    || bad "clearEdgeMenuOverrides removes nothing"
  if grep -qE '\bput[A-Za-z]*\(' <<<"$CLEARBODY"; then
    echo "$CLEARBODY" | grep -nE '\bput[A-Za-z]*\(' | awk '{ print "        " $0 }'
    bad "clearEdgeMenuOverrides WRITES to the store — the phone would be pinned to today's default forever"
  else
    ok "it writes nothing back — the slots are left absent, so the lookup falls through to build.json"
  fi
fi
# The prune marker must survive: re-arming a finished one-time repair against an
# already-empty store is work with no question left to answer.
grep -qF 'PRUNED_KEY' <<<"$CLEARBODY" \
  && bad "the reset touches the prune marker — a finished one-time repair would re-arm" \
  || ok "the prune marker is left alone"

echo "== T4: with the key gone, actionFor genuinely falls through =="
ACTIONFOR="$(body "$PREFS" actionFor)"
grep -qF '?: return default' <<<"$ACTIONFOR" \
  && ok "an ABSENT key returns the baked default (getString(..., null) ?: return default)" \
  || bad "actionFor does not return the default when the key is absent — the reset would blank the menu"
grep -qE 'parse\(raw\).*\?: default' <<<"$ACTIONFOR" \
  && ok "and an unparseable stored value falls back to the same default" \
  || bad "actionFor does not fall back when the stored value cannot be parsed"
# Absent and empty must behave the SAME. A stored empty string that read as
# "configured to nothing" would leave a dead sector after the reset.
PARSEBODY="$(body "$GESTURE" parse)"
grep -qF 'isNullOrBlank' <<<"$PARSEBODY" \
  && ok "GestureAction.parse returns null for blank, so empty is handled exactly like absent" \
  || bad "GestureAction.parse does not treat a blank string as null — an empty key would be a dead sector"

echo "== T5: only the sector keys go — this store's flat settings survive =="
# DERIVED FROM THE SOURCE, not from a list typed here. Every SharedPreferences
# key OneHandPrefs uses is read out of the file and sorted into two kinds by the
# one property the reset's filter depends on: the sector overrides interpolate
# "<handleId>.<slotKey>" and therefore carry a dot, the feature settings are
# plain literals and must not. The day somebody adds a flat key spelled with a
# dot, this fails — which is the moment the reset would start eating it.
KEYREPORT=$(python3 - "$PREFS" <<'PYEOF'
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
accessor = (r'(?:getString|getBoolean|getInt|getLong|getFloat|getStringSet'
            r'|putString|putBoolean|putInt|putLong|putFloat|remove)\(\s*"([^"]*)"')
keys = set(re.findall(accessor, src))
# Named constants reach the same argument positions. FILE is the store's
# filename, not a key inside it.
for name, value in re.findall(r'const\s+val\s+(\w+)\s*=\s*"([^"]*)"', src):
    if name != "FILE":
        keys.add(value)
interpolated = sorted(k for k in keys if "$" in k)
flat = sorted(k for k in keys if "$" not in k)
problems = []
if not interpolated:
    problems.append("no interpolated key found at all — the override shape is not what this tester expects")
for k in interpolated:
    if "." not in k:
        problems.append("override key '%s' has no dot — the reset filter would not match it" % k)
for k in flat:
    if "." in k:
        problems.append("flat setting '%s' contains a dot — the reset would silently delete it" % k)
print("|".join(problems))
print("interpolated=" + ",".join(interpolated))
print("flat=" + ",".join(flat))
PYEOF
)
KEYPROBLEMS=$(sed -n '1p' <<<"$KEYREPORT")
if [ -z "$KEYPROBLEMS" ]; then
  ok "every override key carries a dot and no flat setting does — $(sed -n '3p' <<<"$KEYREPORT")"
else
  bad "key-shape problems: $KEYPROBLEMS"
fi
# The filter has to be that same property, read off the code rather than assumed.
KEYSBODY="$(body "$PREFS" edgeMenuKeys)"
grep -qF "contains('.')" <<<"$KEYSBODY" \
  && ok "edgeMenuKeys selects on the dot, the property just proven to separate the two kinds" \
  || bad "edgeMenuKeys does not filter on the dot — the proof above describes a different function"
# Read off the STORE, not off the declared handles: an override for a handle or
# sector build.json no longer declares is exactly what a reset must clear.
grep -qE 'p\.all\.keys' <<<"$KEYSBODY" \
  && ok "and it reads the live store, so an override for a retired sector is cleared too" \
  || bad "edgeMenuKeys does not enumerate the store — stale overrides would survive the reset"

echo "== T6: the settings the owner did NOT ask to reset cannot be reached =="
# Structural, not a promise: they live in DIFFERENT SharedPreferences files, so
# an edit confined to onehand_prefs has no way to touch them.
STORES=$(python3 - "$PREFS" "$HOMESWIPE" "$FLOATPREFS" <<'PYEOF'
import re, sys
names = []
for path in sys.argv[1:]:
    src = open(path, encoding="utf-8").read()
    found = set(re.findall(r'getSharedPreferences\(\s*(?:"([^"]+)"|(\w+))', src))
    consts = dict(re.findall(r'const\s+val\s+(\w+)\s*=\s*"([^"]*)"', src))
    resolved = {literal or consts.get(ident, "?" + ident) for literal, ident in found}
    names.append(path.split("/")[-1] + "=" + ",".join(sorted(resolved)))
print(" ".join(names))
PYEOF
)
echo "        stores: $STORES"
case "$STORES" in
  *"OneHandPrefs.kt=onehand_prefs"*) ok "OneHandPrefs owns onehand_prefs and nothing else" ;;
  *) bad "OneHandPrefs no longer reads exactly one store: $STORES" ;;
esac
case "$STORES" in
  *"HomeSwipePrefs.kt=home_swipe_prefs"*) ok "the Home swipes live in home_swipe_prefs — a different file entirely" ;;
  *) bad "HomeSwipePrefs is not in its own store: $STORES" ;;
esac
case "$STORES" in
  *"FloatingNavPrefs.kt=floating_nav_pos,floatingnav_prefs"*)
     ok "the floating button and its dragged position live in two further files of their own" ;;
  *) bad "FloatingNavPrefs is not in its own stores: $STORES" ;;
esac
# And the reset path names none of them.
for foreign in HomeSwipePrefs FloatingNav CircularMenu ArcMenu setTrigger setEnabled setDebugVisible; do
  if grep -qF "$foreign" <<<"$CONFIRM"; then
    bad "the reset path touches $foreign — that is not the edge menu"
  else
    ok "the reset path never mentions $foreign"
  fi
done
# The stars are baked, not stored, so there is nothing about them to reset.
code "$FRAG" | grep -qF 'CircularMenu.config()' \
  && ok "the stars are still read from the baked config — the reset has no star state to reach" \
  || bad "the stars no longer read CircularMenu.config()"

echo "== T7: what surfaces afterwards is build.json, not a Kotlin copy =="
# The fall-through target is the DECODED build.json handle, passed straight in
# as actionFor's default. That is what makes the menu track build.json from the
# reset onward instead of freezing at this version.
EFFECTIVE="$(body "$CONFIG" effective)"
grep -qF 'OneHandPrefs.actionFor(ctx, h.id, slot.key, h.gestures[slot.key])' <<<"$EFFECTIVE" \
  && ok "effective() passes the decoded build.json action as the default for every slot" \
  || bad "effective() no longer feeds the baked gesture in as the fall-through default"
grep -qF 'BuildConfig.ONEHAND_CONFIG_B64' <<<"$EFFECTIVE" \
  && ok "and the base config is the baked build.json blob" \
  || bad "effective() no longer decodes ONEHAND_CONFIG_B64"
# No Kotlin file may carry a copy of a declared target. A copy would pass a test
# written against a literal list forever while drifting from the real default.
HARDCODED=""
while IFS= read -r target; do
  for src in "$FRAG" "$PREFS" "$CONFIG" "$GESTURE" "$CONTROLLER" "$SERVICE"; do
    if code "$src" | grep -qF -- "$target"; then
      HARDCODED="$HARDCODED $(basename "$src"):$target"
    fi
  done
done < <(python3 -c "
import json
d = json.load(open('$BJ'))
for h in d['onehand']['handles']:
    for slot, value in h['gestures'].items():
        if slot != 'center':
            print(value)")
if [ -z "$HARDCODED" ]; then
  ok "none of the twelve declared targets appears in live Kotlin — build.json is the only copy"
else
  bad "a declared target is hardcoded in Kotlin:$HARDCODED"
fi
# And the twelve are still twelve. If a handle loses a sector the reset would
# hand the owner a shorter menu than the one he approved.
DECLARED=$(python3 -c "
import json
d = json.load(open('$BJ'))
print(sum(1 for h in d['onehand']['handles'] for s in h['gestures'] if s != 'center'))")
[ "$DECLARED" = "12" ] \
  && ok "build.json still declares twelve non-centre sectors — what the reset restores" \
  || bad "build.json declares $DECLARED non-centre sectors, not the twelve the owner approved"

echo "== T8: the menu repaints without an app restart =="
# Two surfaces draw these entries and they are two different windows.
grep -qF 'fillHandleEditors(' <<<"$CONFIRM" \
  && ok "the reset redraws the pickers on this page" \
  || bad "the reset does not redraw the pickers — the discarded entries would stay on screen"
grep -qF 'OneHandController.refresh(' <<<"$CONFIRM" \
  && ok "and signals the overlay, which is a separate window owned by the accessibility service" \
  || bad "the reset never refreshes the live overlay — the handles would answer the old actions"
FILLBODY="$(body "$FRAG" fillHandleEditors)"
grep -qF 'OneHandConfig.effective(ctx)' <<<"$FILLBODY" \
  && ok "the redraw RE-READS the config rather than reusing what the page was built with" \
  || bad "fillHandleEditors does not re-read the config — it would repaint the same stale values"
grep -qF 'removeAllViews()' <<<"$FILLBODY" \
  && ok "and clears the old pickers first, so a Spinner cannot keep its old selection" \
  || bad "fillHandleEditors does not clear the old views — the columns would be drawn twice"
REFRESHBODY="$(body "$CONTROLLER" refresh)"
grep -qF 'showHandles()' <<<"$REFRESHBODY" \
  && ok "refresh() reaches the service's showHandles()" \
  || bad "OneHandController.refresh no longer calls showHandles"
SHOWBODY="$(body "$SERVICE" showHandles)"
grep -qF 'OneHandConfig.effective(this)' <<<"$SHOWBODY" \
  && ok "and showHandles() re-reads the effective config, so the overlay picks the reset up live" \
  || bad "showHandles does not re-read the config — the overlay would keep the cleared actions"

echo "== T9: both locales, because a partial locale fails the build =="
for k in onehand_edge_reset_button onehand_edge_reset_caption onehand_edge_reset_title \
         onehand_edge_reset_message onehand_edge_reset_confirm onehand_edge_reset_done; do
  grep -qF "name=\"$k\"" "$STRINGS" \
    && ok "string $k declared in values/" \
    || bad "string $k missing from values/"
  grep -qF "name=\"$k\"" "$STRINGS_ES" \
    && ok "string $k translated in values-es/" \
    || bad "string $k missing from values-es/ — a Spanish phone would draw the English string"
done
# The count the dialog names is a format argument in both, or one locale would
# crash on getString with an argument it has nowhere to put.
SPECS=$(python3 - "$STRINGS" "$STRINGS_ES" <<'PYEOF'
import re, sys
import xml.etree.ElementTree as ET
out = []
for path in sys.argv[1:]:
    root = ET.parse(path).getroot()
    for key in ("onehand_edge_reset_message", "onehand_edge_reset_done"):
        text = next((''.join(e.itertext()) for e in root if e.get("name") == key), None)
        out.append("%s:%s:%s" % (path.split("/")[-2], key,
                                 ",".join(sorted(re.findall(r'%\d+\$[a-z]', text or "")))))
print(" ".join(out))
PYEOF
)
echo "        specifiers: $SPECS"
MISSING_SPEC=$(tr ' ' '\n' <<<"$SPECS" | awk -F: '$3 != "%1$d" { print $1 "/" $2 }')
[ -z "$MISSING_SPEC" ] \
  && ok "both counted strings carry %1\$d in both locales" \
  || bad "wrong or missing format argument in: $MISSING_SPEC"
# The owner reads this phone in Spanish. A "translation" that is the English
# string copied across is not one.
python3 -c "
import sys, xml.etree.ElementTree as ET
def get(p, k):
    r = ET.parse(p).getroot()
    return next((''.join(e.itertext()) for e in r if e.get('name') == k), '')
same = [k for k in ('onehand_edge_reset_button', 'onehand_edge_reset_title',
                    'onehand_edge_reset_message', 'onehand_edge_reset_confirm')
        if get('$STRINGS', k) == get('$STRINGS_ES', k)]
sys.exit(1 if same else 0)" \
  && ok "no Spanish string is a byte-for-byte copy of the English one" \
  || bad "a values-es string is identical to values/ — that is an untranslated key with a locale directory around it"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
