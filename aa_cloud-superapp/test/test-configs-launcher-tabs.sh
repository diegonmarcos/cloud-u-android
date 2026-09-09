#!/usr/bin/env bash
# Tester: Configs ▸ Launcher is ONE page with two tabs (Theme | One-Hand), and
# merging the old One-Hand page into it broke neither its stored settings nor
# the targets that still name it.
#
# WHY THIS EXISTS: the merge touches the two things a page move silently
# destroys.
#   1. SETTINGS — a preference store keyed off a page id would have moved when
#      the page did, wiping every toggle the user had set. This asserts the
#      launcher/one-hand stores are named literally, so the id could change
#      (`launcher` → `theme`) without taking the data with it.
#   2. TARGETS — `page:config/onehand` is spoken by launcher shortcuts, edge
#      gestures, the radial menus and the App-Tabs history already on the
#      device. The page stays DECLARED (hidden) and openSectionPage resolves it
#      to its owner page + tab, so those keep landing. A tab declared inline
#      instead of as a real page would have killed all of them at once.
#
# Static tester (no device, no build): build.json is read as data, the Kotlin
# is checked for the routing contract that data relies on.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 — $1"; fi; }

BJ="$APP/build.json"
SECTIONS="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/Sections.kt"
PAGES="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt"
STRIP="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/SectionTabsFragment.kt"
NAV="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/LauncherNavController.kt"

echo "== T1: Configs ▸ Launcher declares its two tabs as page ids, in order =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
launcher = next((p for p in pages if p['id'] == 'launcher'), None)
if launcher is None:                       print('no `launcher` page in config')
elif launcher.get('tabs') != ['theme', 'onehand']:
                                           print('tabs = %r' % (launcher.get('tabs'),))
elif launcher.get('hidden'):               print('the strip itself must stay listed')
else:                                      print('OK')
PY
)" "launcher: tabs = [theme, onehand], still a visible Configs entry"

echo "== T2: every tab is a REAL declared page, hidden, and not the owner itself =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
by_id = {p['id']: p for p in pages}
problems = []
for owner in pages:
    for tab in owner.get('tabs', []):
        if tab == owner['id']:
            problems.append('%s lists itself as a tab (infinite render)' % tab)
        elif tab not in by_id:
            problems.append('tab %r has no page behind it' % tab)
        elif not by_id[tab].get('hidden'):
            problems.append('tab %r is still a standalone Configs entry' % tab)
print('; '.join(problems) or 'OK')
PY
)" "theme + onehand are declared, hidden pages of the same section"

echo "== T3: the One-Hand id SURVIVES (page:config/onehand must still resolve) =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
oh = next((p for p in pages if p['id'] == 'onehand'), None)
if oh is None:            print('the onehand page was DELETED — every stored target now dead-ends')
elif oh['label'] != 'One-Hand': print('label = %r' % oh['label'])
else:                     print('OK')
PY
)" "onehand still declared, so its target resolves through Section.allPages"

echo "== T4: Sections parses the page tabs list and can answer who owns a tab =="
grep -qF 'tabs     = po.optJSONArray("tabs")' "$SECTIONS" \
  && ok "Page.tabs is parsed from build.json" \
  || bad "Page.tabs is not parsed — the declaration would be ignored"
grep -qF 'fun tabOwnerOf(sectionId: String, pageId: String): Page?' "$SECTIONS" \
  && ok "tabOwnerOf exists (tab id → owning page)" \
  || bad "tabOwnerOf missing — a tab id has no way back to its page"
grep -qF 'owner.tabs.filter { it != owner.id }' "$SECTIONS" \
  && ok "a page listed among its own tabs is dropped (no render loop)" \
  || bad "self-referencing tab guard missing"

echo "== T5: a page that declares tabs renders the SAME strip a section does =="
grep -qF 'tabs.isNotEmpty() -> SectionTabsFragment.forPage(sectionId, pageId)' "$PAGES" \
  && ok "SectionPages routes a tabbed page to SectionTabsFragment (no second tab widget)" \
  || bad "tabbed-page branch missing or routed elsewhere"
# The tabs branch must be tested BEFORE the id branches, or a page id listed
# below would win and the strip would never be built.
tabs_ln=$(grep -n 'tabs.isNotEmpty() ->' "$PAGES" | head -1 | cut -d: -f1)
url_ln=$(grep -n 'url.isNotBlank() ->' "$PAGES" | head -1 | cut -d: -f1)
if [ -n "$tabs_ln" ] && [ -n "$url_ln" ] && [ "$tabs_ln" -lt "$url_ln" ]; then
  ok "tabs branch (line $tabs_ln) precedes every id/url branch (line $url_ln)"
else
  bad "tabs branch does not come first in factoryFor"
fi
grep -qF 'forPage(sectionId: String, pageId: String): SectionTabsFragment' "$STRIP" \
  && ok "SectionTabsFragment.forPage is the page-strip entry point" \
  || bad "SectionTabsFragment.forPage missing"

echo "== T6: both tabs still route to the fragments they always used =="
grep -qF 'pageId == "theme" -> LauncherConfigFragment.newInstance()' "$PAGES" \
  && ok "Theme tab → LauncherConfigFragment" || bad "Theme tab lost its fragment"
grep -qF 'pageId == "onehand" ->' "$PAGES" \
  && ok "One-Hand tab → OneHandFragment" || bad "One-Hand tab lost its fragment"

echo "== T7: a target naming a tab is resolved to its page BEFORE anything else =="
own_ln=$(grep -n 'Sections.tabOwnerOf(sectionId, pageId)?.let' "$NAV" | head -1 | cut -d: -f1)
base_ln=$(grep -n 'if (host.currentSection != sectionId) {' "$NAV" | head -1 | cut -d: -f1)
if [ -n "$own_ln" ] && [ -n "$base_ln" ]; then
  [ "$own_ln" -lt "$base_ln" ] \
    && ok "tab redirect (line $own_ln) runs before the section base (line $base_ln)" \
    || bad "tab redirect (line $own_ln) runs AFTER the section base (line $base_ln) — the base is built for a page that is then replaced"
else
  bad "could not locate the tab redirect / section-base markers in openSectionPage"
fi
grep -qF 'recordActiveTab(SectionTabsFragment.pageTabKey(sectionId, owner.id), pageId)' "$NAV" \
  && ok "the wanted tab is recorded before the owner page opens" \
  || bad "the deep link would open the owner page on tab 0, not the tab asked for"

echo "== T8: a page strip cannot overwrite its SECTION's remembered tab =="
grep -qF 'if (ownerPageId.isBlank()) sectionId else pageTabKey(sectionId, ownerPageId)' "$STRIP" \
  && ok "page strips remember their tab under their own key" \
  || bad "page and section strips share one key — Configs would inherit Launcher's tab"

echo "== T9: NO preference store is keyed off a page id (settings must not move) =="
# This is the invariant that let `launcher` become `theme` for free. If a store
# name ever gets built from a page id, moving a control between pages silently
# resets it — which is exactly what this merge must never do.
sp_fail=""
for f in "$APP/app/src/main/java/com/diegonmarcos/superapp/settings/LauncherThemePrefs.kt" \
         "$APP/app/src/main/java/com/diegonmarcos/superapp/settings/LauncherProfilePrefs.kt" \
         "$APP/app/src/main/java/com/diegonmarcos/superapp/settings/LauncherSettingsPrefs.kt" \
         "$APP/app/src/main/java/com/diegonmarcos/superapp/settings/HomeSwipePrefs.kt"; do
  [ -f "$f" ] || { sp_fail="$sp_fail $(basename "$f"):missing"; continue; }
  # Every getSharedPreferences argument must be a literal or a constant, never
  # an interpolated page id.
  grep -n 'getSharedPreferences(' "$f" | grep -q '\$' \
    && sp_fail="$sp_fail $(basename "$f"):interpolated"
done
[ -z "$sp_fail" ] \
  && ok "launcher + one-hand stores are named literally, so no key moved with the page" \
  || bad "preference store name built from a variable:$sp_fail"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
