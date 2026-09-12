#!/usr/bin/env bash
# Tester: the one-hand EDGE MENU default order, and Polaris, the Search star.
#
# WHY THIS EXISTS. The owner gave a new default order for both edge menus on
# 2026-09-10 as two blocks of six names. Four ways that can be got wrong, none
# of which a compiler or a screenshot would catch:
#
#   1. ORDER IS THE WHOLE TASK. A test that checks all twelve targets are
#      PRESENT passes while the sequence — the only thing he asked to change —
#      is wrong. Every assertion here is by position.
#   2. THE CORRECTION HE SENT SECOND. He wrote "Drive (cloud media center)" and
#      then corrected it to "Drive (is cloud-drive, not cloud-media-center)".
#      The message a future agent finds first in the history is the WRONG one,
#      so the correction has to live here as a permanent guard: Drive is the
#      SuperApp's own Drive section, and the media centre appears exactly once,
#      on the other handle.
#   3. A SLOT THAT LOOKS FINE AND DOES NOTHING. An edge sector holding a target
#      no dispatcher understands is a dead slot: the owner swipes and the phone
#      does nothing at all. Every one of the twelve is resolved here against
#      the data that declares it.
#   4. A STAR ON TOP OF ANOTHER STAR. Polaris is new and it is placed by a
#      number. Placed wrong it lands on the toolbar island, and the wrong thing
#      takes the touch.
#
# Static tester: no device, no build. build.json is read as data; the Kotlin and
# the layout are checked for the contracts that data relies on.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
LIBS="$APP/../ab_cloud-libs-shared/libs"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

BJ="$APP/build.json"
SHELL_KT="$APP/app/src/main/java/com/diegonmarcos/superapp/ShellActivity.kt"
LAYOUT="$APP/app/src/main/res/layout/activity_main.xml"
STRINGS="$APP/app/src/main/res/values/strings.xml"
STRINGS_ES="$APP/app/src/main/res/values-es/strings.xml"
ONEHAND_FRAG="$APP/app/src/main/java/com/diegonmarcos/superapp/configs/OneHandFragment.kt"
POLARIS="$LIBS/launcher-onehand/src/main/java/com/diegonmarcos/superapp/onehand/PolarisStar.kt"
CIRCULAR="$LIBS/launcher-onehand/src/main/java/com/diegonmarcos/superapp/onehand/CircularMenu.kt"

# FAIL CLOSED. Four testers in this repository once passed only because the tool
# they called was absent and the call failed open. Every assertion below runs
# through python3; if it is not here, nothing has been proven.
if ! command -v python3 >/dev/null 2>&1; then
  echo "  FAIL: python3 is not on PATH — this tester proves nothing without it"
  echo "== RESULT: 0 passed, 1 failed =="
  exit 1
fi
for f in "$BJ" "$SHELL_KT" "$LAYOUT" "$STRINGS" "$STRINGS_ES" "$POLARIS" "$CIRCULAR" "$ONEHAND_FRAG"; do
  if [ ! -f "$f" ]; then
    echo "  FAIL: missing file $f — the tree is not what this tester was written against"
    echo "== RESULT: 0 passed, 1 failed =="
    exit 1
  fi
done

# One python call per question. Prints a single line; a lookup that finds
# nothing prints the empty string, which every comparison below treats as wrong.
q() { python3 -c "
import json
d = json.load(open('$BJ'))
h = {x['id']: x for x in d['onehand']['handles']}
oh = d['onehand']
# A tile carrying a nested 'tiles' array is a FOLDER: it has no target of its
# own, and what can be opened from it is the entries one level down. Reading
# only the top level is how this tester lost the AI Claude tile the day it moved
# inside B-LLM — Sections.AggTile.destinations makes the same substitution on
# the Kotlin side.
def leaves(lst):
    out = []
    for t in lst or []:
        if not isinstance(t, dict):
            continue
        if isinstance(t.get('tiles'), list) and t['tiles']:
            out.extend(leaves(t['tiles']))
        else:
            out.append(t)
    return out
$1" 2>/dev/null; }

echo "== T1: the LEFT handle is the owner's FIRST block, in his order =="
# top→down over the six sectors that are not \`center\`. \`center\` is Back on both
# handles and is not one of the twelve, which is why six names fill seven slots.
LEFT_ORDER=$(q "print('|'.join(h['left']['gestures'][k] for k in
  ('top_outer','top','top_middle','down_middle','down','down_outer')))")
LEFT_WANT='action:intent://resolve?domain=Cloud_agent_claude_bot#Intent;scheme=tg;package=org.telegram.messenger;S.browser_fallback_url=https%3A%2F%2Ft.me%2FCloud_agent_claude_bot;end|app:com.diegonmarcos.comms.mail|app:com.instagram.android|app:com.whatsapp.w4b|action:section:drive|app:com.google.android.apps.bard'
if [ "$LEFT_ORDER" = "$LEFT_WANT" ]; then
  ok "left = AI Claude · Mail · Instagram · Whatsapp Business · Drive · Gemini, by position"
else
  bad "left order is wrong"
  echo "        want: $LEFT_WANT"
  echo "        got : $LEFT_ORDER"
fi
[ "$(q "print(h['left']['gestures']['center'])")" = "back" ] \
  && ok "left center is still Back — a straight-in swipe never became one of the twelve" \
  || bad "left center is no longer Back"

echo "== T2: the RIGHT handle is the owner's SECOND block, in his order =="
RIGHT_ORDER=$(q "print('|'.join(h['right']['gestures'][k] for k in
  ('top_outer','top','top_middle','down_middle','down','down_outer')))")
RIGHT_WANT='app:com.brave.browser|app:com.diegonmarcos.cloudme|app:com.diegonmarcos.mediacenter|app:cld.camera|app:com.google.android.apps.translate|app:com.google.android.apps.maps'
if [ "$RIGHT_ORDER" = "$RIGHT_WANT" ]; then
  ok "right = Brave · Me · Media Center · Camera · Translator Google · Google Maps, by position"
else
  bad "right order is wrong"
  echo "        want: $RIGHT_WANT"
  echo "        got : $RIGHT_ORDER"
fi
[ "$(q "print(h['right']['gestures']['center'])")" = "back" ] \
  && ok "right center is still Back" \
  || bad "right center is no longer Back"

echo "== T3: the owner's own correction, turned into a guard =="
# "Drive (is cloud-drive, not cloud-media-center)" — his second message wins.
DRIVE=$(q "print(h['left']['gestures']['down'])")
case "$DRIVE" in
  *mediacenter*|*media-center*|*media_center*)
     bad "Drive points at the media centre — this is the exact mistake the owner corrected" ;;
  action:section:drive)
     ok "Drive is action:section:drive, the SuperApp's own Drive section" ;;
  *) bad "Drive is '$DRIVE', neither the Drive section nor anything recognised" ;;
esac
MC_COUNT=$(q "
n = sum(1 for x in oh['handles'] for v in x['gestures'].values() if 'mediacenter' in v)
print(n)")
[ "$MC_COUNT" = "1" ] \
  && ok "the media centre appears exactly once across both handles" \
  || bad "the media centre appears $MC_COUNT times across the handles — the owner named it once"
MC_SIDE=$(q "
print(next(x['id'] for x in oh['handles'] for v in x['gestures'].values() if 'mediacenter' in v))")
[ "$MC_SIDE" = "right" ] \
  && ok "and it is on the right handle, the block it was named in" \
  || bad "the media centre is on the '$MC_SIDE' handle — the owner put it in the second block"
# There is no cloud-drive Android application, which is why Drive is a section
# and not an app: target. If one is ever added this assertion is what says so.
q "print(any(e['id'] == 'cloud-drive' for e in d['ui']['external_apps']))" | grep -q False \
  && ok "no cloud-drive application exists, so the section target is the only real Drive" \
  || bad "a cloud-drive application now exists in ui.external_apps — Drive should probably launch it"

echo "== T4: no dead slots — every one of the twelve resolves to declared data =="
# app:<pkg> must be in onehand.apps (what the picker offers). A fleet package
# (com.diegonmarcos.* / cld.*) must ALSO match ui.external_apps identity, so an
# invented fleet id cannot pass by being typed into two places.
UNRESOLVED=$(q "
favourites = {a['package'] for a in oh['apps']}
fleet = set()
for e in d['ui']['external_apps']:
    for k in ('hub_package', 'install_package', 'alt_package'):
        if e.get(k): fleet.add(e[k])
declared_actions = {a['target'] for a in oh['circular_menu']['actions']}
sections = {s['id'] for s in d['ui']['sections']}

def _key(t):
    return 'action:' + (t or '')[7:] if (t or '').startswith('action:') else 'action:' + (t or '')
_tiles = set()
for _s in d['ui']['sections']:
    _lists = [v for k, v in _s.items() if k.startswith('tiles_') and isinstance(v, list)]
    for _g in (_s.get('tile_groups') or []):
        if isinstance(_g.get('tiles'), list): _lists.append(_g['tiles'])
    for _L in _lists:
        for _t in leaves(_L):
            if isinstance(_t, dict) and not _t.get('separator') and _t.get('target'):
                _tiles.add(_key(_t['target']))

def resolve_target(value):
    '''The tile/section catalogue, same rule as the engine. None = unresolvable.'''
    if _key(value) in _tiles: return True
    bare = value[7:] if value.startswith('action:') else value
    if bare.startswith('section:') and bare[8:] in sections: return True
    return None
bad_targets = []
for handle in oh['handles']:
    for slot, value in handle['gestures'].items():
        if slot == 'center': continue
        if value.startswith('app:'):
            pkg = value[4:]
            if pkg not in favourites:
                bad_targets.append(handle['id'] + '.' + slot + ' package not offered by the picker: ' + pkg)
            elif (pkg.startswith('com.diegonmarcos.') or pkg.startswith('cld.')) and pkg not in fleet:
                bad_targets.append(handle['id'] + '.' + slot + ' fleet package not in ui.external_apps: ' + pkg)
        elif value.startswith('action:'):
            target = value[7:]
            # NO SCHEME IS SKIPPED. This branch used to `continue` on intent://
            # and http, and the AI Claude sector is an intent:// one — so the
            # single sector that was broken on the phone was the single sector
            # this tester stepped over, while reporting all twelve resolved.
            # That is how #111 and #127 were both closed on a green run.
            #
            # Membership in circular_menu.actions is ALSO the wrong question:
            # that list is a MENU, and requiring every sector to appear in it is
            # what made the last two fixes data edits. A sector is resolvable
            # when the app's own tile catalogue can name it — the same rule
            # libs/launcher-onehand/build.gradle now enforces at build time, and
            # test-onehand-sector-resolution.sh asserts in full.
            if resolve_target(value) is None:
                bad_targets.append(handle['id'] + '.' + slot
                                   + ' resolves to no tile and no section: ' + target)
        else:
            bad_targets.append(handle['id'] + '.' + slot + ' is neither app: nor action: — ' + value)
print(' ;; '.join(bad_targets))")
if [ -z "$UNRESOLVED" ]; then
  ok "all twelve targets resolve — no slot the owner can tap for nothing"
else
  bad "unresolved edge-menu targets: $UNRESOLVED"
fi

echo "== T5: AI Claude's URI was kept, not re-derived =="
# The working target is the Cloud > Apps tile's. Both are read from data here,
# so this compares two live strings rather than a copy of one of them.
SAME=$(q "
tile = [t['target'] for s in d['ui']['sections'] for g in s.get('tile_groups', [])
        for t in leaves(g.get('tiles', [])) if 'Cloud_agent_claude_bot' in t.get('target', '')]
print('yes' if len(tile) == 1 and h['left']['gestures']['top_outer'] == 'action:' + tile[0] else 'no')")
[ "$SAME" = "yes" ] \
  && ok "the edge gesture is 'action:' + the exact Cloud > Apps tile target, byte for byte" \
  || bad "the AI Claude gesture and the Cloud > Apps tile no longer carry the same bot URI"
q "print('yes' if h['left']['gestures']['top_outer'].startswith('action:intent://') else 'no')" \
  | grep -q yes \
  && ok "it travels as an action: payload, the only form GestureAction.parse forwards verbatim" \
  || bad "the AI Claude target lost its action: prefix — GestureAction.parse would not forward it"

echo "== T6: Polaris, the Search star — declared, on top, colliding with nothing =="
q "print(oh['search_star']['enabled'])" | grep -q True \
  && ok "onehand.search_star is declared and enabled" \
  || bad "onehand.search_star is missing or disabled"
STAR_TARGET=$(q "print(oh['search_star']['target'])")
[ "$STAR_TARGET" = "action:open_search" ] \
  && ok "it opens action:open_search — the SearchSheet that already exists, not a new surface" \
  || bad "the search star opens '$STAR_TARGET', which is not the app's own search"
q "print(oh['search_star']['target'] in {a['target'] for a in oh['circular_menu']['actions']})" \
  | grep -q True \
  && ok "its target is one of the declared star actions, so it cannot drift from the Sirius ring" \
  || bad "the search star's target is not in circular_menu.actions — two declarations of one destination"
# The floor is the top island: toolbar_island sits at layout_marginTop 26dp and
# is one ?attr/actionBarSize tall (56dp on a phone). Below 82dp the star's touch
# box overlaps the toolbar and the wrong view takes the tap.
TOP_OFFSET=$(q "print(oh['search_star']['top_offset_dp'])")
ISLAND_TOP=$(awk '/android:id="@\+id\/toolbar_island"/,/^$/ {
    if ($0 ~ /layout_marginTop/) { gsub(/[^0-9]/, "", $0); print; exit } }' "$LAYOUT")
if [ -n "$TOP_OFFSET" ] && [ -n "$ISLAND_TOP" ]; then
  FLOOR=$((ISLAND_TOP + 56))
  if [ "$TOP_OFFSET" -ge "$FLOOR" ]; then
    ok "top_offset_dp ${TOP_OFFSET} clears the toolbar island (${ISLAND_TOP}dp + 56dp actionBar = ${FLOOR}dp)"
  else
    bad "top_offset_dp ${TOP_OFFSET} lands ON the toolbar island, which ends at ${FLOOR}dp"
  fi
else
  bad "could not read top_offset_dp ('$TOP_OFFSET') or the island margin ('$ISLAND_TOP')"
fi
# Centauri and Recent Tabs share the MIDWAY row and Canopus the bottom one; both
# are anchored off the bottom island in Kotlin, not off the top. A top-anchored
# star therefore cannot reach them — but only while it is the ONLY view in this
# layout that uses layout_gravity="top|center_horizontal" for a star.
TOP_STARS=$(grep -c 'android:id="@+id/search_polaris_star"' "$LAYOUT")
[ "$TOP_STARS" = "1" ] \
  && ok "exactly one Polaris view is declared in the layout" \
  || bad "$TOP_STARS Polaris views in the layout — a duplicate would double every touch"
grep -q 'android:layout_gravity="top|center_horizontal"' "$LAYOUT" \
  && ok "it is laid out against the TOP edge, where the owner asked for it" \
  || bad "the Polaris view is not top-anchored"
for other in configs_canopus_star active_apps_centauri_star recent_tabs_star all_apps_sirius_star; do
  if awk -v id="$other" '
      $0 ~ "android:id=\"@\\+id/" id "\"" { inview = 1 }
      inview && /layout_gravity="top/ { found = 1 }
      inview && /\/>/ { inview = 0 }
      END { exit(found ? 1 : 0) }' "$LAYOUT"; then
    ok "$other is not top-anchored — Polaris displaces it from nothing"
  else
    bad "$other is ALSO anchored to the top — the two stars may overlap"
  fi
done

echo "== T7: the star is wired, and it is a star like the others =="
# LIVE CODE, NOT A MENTION. A plain substring search matched the call after it
# had been commented out — the tester stayed green while the star was dead. Each
# pattern below requires the line's first non-blank character not to be a slash.
live() {
  # Lines that mention the call, minus the ones that are commented out. The
  # match and the comment test are SEPARATE greps because folding them into one
  # anchored pattern silently stopped matching a call that begins its own line.
  hits="$(grep -E "$1" "$SHELL_KT" | grep -vE '^[[:space:]]*//')"
  [ -n "$hits" ]
}
live 'PolarisStar\(' \
  && ok "ShellActivity constructs PolarisStar" \
  || bad "PolarisStar is never constructed (or the call is commented out) — the view would do nothing"
live 'polarisStar\.setup\(\)' \
  && ok "and calls setup(), where glyph, size and placement are applied" \
  || bad "polarisStar.setup() is never called (or the call is commented out)"
live 'polarisStar\.update\(section\)' \
  && ok "and update(), so it hides off the home section like its four siblings" \
  || bad "polarisStar.update() is never called (or the call is commented out) — the star would follow the user off Home"
grep -qF 'CircularMenu.config()' "$POLARIS" \
  && ok "glyph and size come from the shared star block, not a second set of numbers" \
  || bad "PolarisStar carries its own size/glyph — the five stars can drift apart"
grep -qF 'search_star' "$CIRCULAR" \
  && ok "CircularMenu reads onehand.search_star from the baked config" \
  || bad "nothing decodes onehand.search_star — top_offset_dp and target would be inert"
grep -qF 'onehand.search_star' "$BJ" >/dev/null 2>&1
grep -qE 'top_offset_dp|topOffsetDp' "$POLARIS" \
  && ok "the offset is read, not typed into the layout as a margin" \
  || bad "PolarisStar ignores top_offset_dp — build.json would not move the star"
grep -qF 'CircularMenu.searchStar()' "$ONEHAND_FRAG" \
  && ok "the Configs One-Hand page describes the new star from the same config" \
  || bad "the One-Hand page does not mention Polaris — the owner has no way to learn it exists"

echo "== T8: both locales, because a partial locale fails CI =="
for k in star_search_desc onehand_star_search_name onehand_star_search_place onehand_star_search_what; do
  grep -qF "name=\"$k\"" "$STRINGS" \
    && ok "string $k declared in values/" \
    || bad "string $k missing from values/"
  grep -qF "name=\"$k\"" "$STRINGS_ES" \
    && ok "string $k translated in values-es/" \
    || bad "string $k missing from values-es/ — a Spanish phone would draw the English string"
done
grep -qF 'android:contentDescription="@string/star_search_desc"' "$LAYOUT" \
  && ok "the star's content description is a resource, not a literal" \
  || bad "the Polaris view carries a hardcoded content description"
# The intro sentence counts the stars out loud. A fifth star with a sentence
# that still says four is the kind of wrong nobody notices for months.
grep -qF 'Four stars' "$STRINGS" \
  && bad "values/ still says 'Four stars' — there are five now" \
  || ok "the English stars intro no longer says four"
grep -qF 'cuatro estrellas' "$STRINGS_ES" \
  && bad "values-es/ still says 'cuatro estrellas' — there are five now" \
  || ok "the Spanish stars intro no longer says four"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
