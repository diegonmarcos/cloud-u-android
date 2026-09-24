#!/usr/bin/env bash
# #521 + #524 + #526 + #499 — the top-left menu is the ONE site map.
#
# What this pins:
#   • #499  The Cloud ▸ Apps ▸ AGI "nix" tile captions itself with its
#           identity string — it must say "Terminal". (T1)
#   • #526  In the main-menu the two search entries' FINAL labels are
#           "Search ML" and "Search CGC" (were "Search" / "Search plus"). (T2)
#   • #524  Those two entries sit below the Profile badge, in the declared
#           order Search ML then Search CGC. (T3)
#   • #521  The main-menu (the home drawer's Home pane) is built from ONE
#           build.json declaration (ui.main_menu) plus the page registries,
#           so no navigation surface restates a destination in Kotlin. (T4/T5)
#   • Resolution: a declared main-menu action that resolves to nothing is a
#     FAILURE, never a skip. (T4)
#
# Everything is asserted against build.json and the Kotlin that renders it —
# never against a class name or a literal label a refactor can move, so this
# cannot go green while asserting nothing (#511).
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

# A rule satisfied only by a comment is not satisfied. Strip comments before
# grepping Kotlin bodies.
code() { grep -vE '^[[:space:]]*(//|\*|/\*)' "$1" 2>/dev/null; }

echo "== T1 (#499): the AGI tile captions itself with 'Terminal', not its identity =="
AGI_LABEL=$(python3 -c "
import json
d = json.load(open('$BJ'))
cloud = [s for s in d['ui']['sections'] if s['id'] == 'cloud'][0]
agi = [g for g in cloud['tile_groups'] if g['title'] == 'AGI'][0]
for t in agi['tiles']:
    if t.get('id') == 'ai-tmx':
        print(t.get('label',''))
        break
")
[ "$AGI_LABEL" = "Terminal" ] \
  && ok "the ai-tmx AGI tile reads 'Terminal'" \
  || bad "the ai-tmx AGI tile reads '$AGI_LABEL' — it must be 'Terminal', never the identity string"
case "$AGI_LABEL" in
  nix|Nix|NIX|cloud-terminal-nix) bad "the ai-tmx tile still captions itself with its identity string '$AGI_LABEL'" ; ok "identity-string guard armed" ;;
esac

echo "== T2 (#526): the two search entries are named Search ML and Search CGC =="
SEARCH_NAMES=$(python3 -c "
import json
d = json.load(open('$BJ'))
mm = d['ui'].get('main_menu') or []
print('\n'.join((e.get('label','') or '') for e in mm if e.get('label','').startswith('Search ')))
")
EXPECT="Search ML
Search CGC"
[ "$SEARCH_NAMES" = "$EXPECT" ] \
  && ok "main-menu declares exactly: Search ML, Search CGC" \
  || { echo "$SEARCH_NAMES" | grep -q '^Search ML$' || bad "missing 'Search ML' in main-menu"; \
       echo "$SEARCH_NAMES" | grep -q '^Search CGC$' || bad "missing 'Search CGC' in main-menu"; }

echo "== T3 (#524): they sit below the Profile badge, Search ML then Search CGC =="
ORDER=$(python3 -c "
import json
d = json.load(open('$BJ'))
mm = d['ui'].get('main_menu') or []
labels = [e.get('label','') for e in mm]
import sys
i_ml = labels.index('Search ML') if 'Search ML' in labels else -1
i_cgc = labels.index('Search CGC') if 'Search CGC' in labels else -1
sys.stdout.write('%d %d' % (i_ml, i_cgc))
")
read -r I_ML I_CGC <<<"$ORDER"
# Below the Profile badge: the drawer header (nav_header, the identity row the
# little Profile avatar badge lives in) is the NavigationView's header and the
# menu below it is exactly what this code renders. So the profile-above-search
# ordering is structural: header first, then the main-menu entries.
grep -q 'app:headerLayout' "$APP/app/src/main/res/layout/fragment_home_drawer.xml" \
  && ok "the drawer declares its header (Profile badge) above the menu" \
  || bad "fragment_home_drawer.xml lost its headerLayout — the Profile badge no longer sits above the menu"
code "$SRC/HomeDrawerFragment.kt" | grep -q 'Sections.mainMenu()' \
  && ok "HomeDrawerFragment renders main-menu after the header via Sections.mainMenu()" \
  || bad "HomeDrawerFragment no longer renders Sections.mainMenu()"
[ "$I_ML" -ge 0 ] && [ "$I_CGC" -gt "$I_ML" ] \
  && ok "declared order in main-menu: Search ML (index $I_ML) then Search CGC (index $I_CGC)" \
  || bad "the two search entries are not ordered Search ML before Search CGC (got $I_ML, $I_CGC)"

echo "== T4 (resolution): every declared main-menu action resolves — empty resolution FAILS =="
UNRESOLVED=$(python3 -c "
import json, sys
d = json.load(open('$BJ'))
mm = d['ui'].get('main_menu') or []
# The resolved vocabulary: every handled action the app declares.
vocab = set()
for h in (d['ui'].get('home_actions') or []):
    vocab.add(h.get('action_type',''))
for s in d['ui'].get('sections', []):
    for p in s.get('pages', []):
        a = p.get('action','')
        if a.startswith('action:'): vocab.add(a[7:])
for pg in (d['ui'].get('pages') or []):
    t = pg.get('target','')
    if t.startswith('action:'): vocab.add(t[7:])
# Built-in dispatcher actions (ShellActivity dispatchHomeAction / tile grammar).
# 'constellation' left this list with its ShellActivity branch (#563): the
# store is page:config/store now, so a main-menu entry naming the old action
# would dispatch to nothing and must fail here.
vocab.update(['open_search', 'open_home_apps', 'check_updates', 'import_configs'])
bad_ones = []
for e in mm:
    at = (e.get('action_type','') or '').strip()
    if at == '':
        bad_ones.append((e.get('label','?'), 'EMPTY action_type'))
    elif at not in vocab:
        bad_ones.append((e.get('label','?'), 'action:'+at))
sys.stdout.write('; '.join('%s -> %s' % x for x in bad_ones))
")
[ -z "$UNRESOLVED" ] \
  && ok "every main-menu action_type resolves to a declared/handled action" \
  || bad "main-menu entries resolve to nothing: $UNRESOLVED"

echo "== T5 (#521 / single source): destinations are DECLARED once, never a Kotlin copy =="
# T5a — the search labels exist in the declaration; a restatement in KOTLIN
# CODE (not a comment) is a second copy. Comments are stripped so an editor's
# prose about the labels cannot count as one (#511 — assert the code, not text).
SECOND=$(find "$APP/app/src/main/java" -name '*.kt' -print0 | while IFS= read -r -d '' f; do
  code "$f" | grep -F -e 'Search ML' -e 'Search CGC' | sed "s|^|$f:|"
done)
[ -z "$SECOND" ] \
  && ok "no Kotlin code restates the search labels — they come from build.json::ui.main_menu only" \
  || { while IFS= read -r l; do bad "search label restated in Kotlin code: $l"; done <<< "$SECOND"; }
# T5b — the renderer is a derivation, not a hand-written list.
code "$SRC/HomeDrawerFragment.kt" | grep -q 'Sections.mainMenu()' \
  && ok "HomeDrawerFragment builds the menu from the declaration (Sections.mainMenu())" \
  || bad "HomeDrawerFragment no longer derives its menu from the declaration"
# T5c — the Home bottom-nav fan menu is a derivation of the registries, not a
# hand-written tuple of destination strings+labels.
code "$SRC/HomeFanMenu.kt" | grep -q 'Pages.byId' \
  && ok "HomeFanMenu reads the page registry (Pages.byId) instead of a Kotlin list" \
  || bad "HomeFanMenu restates a hand-written nav list — it must derive from the declarations"
if code "$SRC/HomeFanMenu.kt" | grep -q 'to (R.drawable'; then
  bad "HomeFanMenu still hand-writes (icon to label) pairs — a second private copy of the nav tree"
else
  ok "HomeFanMenu carries no hardcoded (icon, label) nav pairs"
fi

echo "== T6 (rename): the old declaration key is gone =="
grep -q '"home_drawer_prepend"' "$BJ" \
  && bad "build.json still declares home_drawer_prepend — the rename to main_menu is incomplete" \
  || ok "build.json declares only ui.main_menu (home_drawer_prepend renamed)"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
