#!/usr/bin/env bash
# Tester: Configs ▸ Panel — one page, two tabs (Notify | Control) — and the one
# rule the Control tab exists to keep.
#
# WHY THIS EXISTS: this page has exactly two ways to be wrong, and neither one
# shows up as a crash or a failed build.
#
#   1. A SWITCH THAT LIES. Android forbids a normal app from flipping Wi-Fi,
#      mobile data, Bluetooth or the airplane radios. Drawing a Switch for those
#      gives the owner a control that moves under the thumb, persists, and
#      changes nothing on the device — the single worst outcome this page can
#      have, and one no compiler will ever mention. So: those four must not have
#      a `set`, every write must decide its verdict by RE-READING the device,
#      and the UI must never show the state that was asked for.
#   2. A COPY OF THE NTFY PAGE. The Notify tab was told to reuse the existing
#      page, not clone it. It is a `mirror_page` facet, so it renders the very
#      fragment `page:communication/my-rss` opens; a second stack_ declaration
#      here would be the copy that drifts.
#
# Static tester (no device, no build): build.json is read as data, the Kotlin is
# checked for the contracts that data relies on.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 — $1"; fi; }

BJ="$APP/build.json"
GRADLE="$APP/app/build.gradle"
SECTIONS="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/Sections.kt"
PAGES="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt"
NAV="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/LauncherNavController.kt"
TABS="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/SectionTabsFragment.kt"
CONTROLS="$APP/app/src/main/java/com/diegonmarcos/superapp/configs/DeviceControls.kt"
FRAGMENT="$APP/app/src/main/java/com/diegonmarcos/superapp/configs/ControlFragment.kt"
STATUS="$APP/app/src/main/java/com/diegonmarcos/superapp/ui/StatusLight.kt"

echo "== T1: Configs ▸ Panel is a visible page declaring its two tabs in order =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
order = [p['id'] for p in pages]
panel = next((p for p in pages if p['id'] == 'panel'), None)
if panel is None:                    print('no `panel` page in config')
elif panel.get('tabs') != ['control', 'notify']:
                                     print('tabs = %r' % (panel.get('tabs'),))
elif panel.get('hidden'):            print('the strip itself must stay listed')
elif order[0] != 'panel':            print('Panel is not first: %s' % order[0])
# Panel is the page opened many times a day and About is the page opened once
# ever, so Panel ahead of About is the ordering rule that outlives "Panel is
# first" — asserted separately, or moving one entry above Panel would quietly
# take this with it.
elif 'about' not in order:           print('no `about` page to order against')
elif order.index('panel') > order.index('about'):
                                     print('Panel sits after About: %r' % order)
else:                                print('OK')
PY
)" "panel: tabs = [control, notify], visible, first in the Configs grid and ahead of About"

echo "== T2: both tabs are REAL hidden pages of the SAME section, never the owner =="
# The strip contract established in 07964787e: a tab is a declared page, so
# page:config/<tab> stays a live target. Panel must not be the page that breaks
# it by inventing an inline tab.
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
by_id = {p['id']: p for p in pages}
panel = by_id['panel']
problems = []
for tab in panel.get('tabs', []):
    if tab == 'panel':            problems.append('panel lists itself (infinite render)')
    elif tab not in by_id:        problems.append('tab %r has no page behind it' % tab)
    elif not by_id[tab].get('hidden'):
                                  problems.append('tab %r must be hidden' % tab)
print('; '.join(problems) or 'OK')
PY
)" "notify + control are declared, hidden pages of the config section"

echo "== T3: Notify MIRRORS the ntfy page — it does not carry a copy of it =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
ui = json.load(open(sys.argv[1]))['ui']
sections = {s['id']: s for s in ui['sections']}
notify = next(p for p in sections['config']['pages'] if p['id'] == 'notify')
problems = []
if not notify.get('facet'):
    problems.append('mirror_page only fires on a facet page')
target = notify.get('mirror_page', '')
if '/' not in target:
    problems.append('mirror_page = %r is not <section>/<page>' % target)
else:
    sec, pid = target.split('/', 1)
    if sec not in sections:
        problems.append('mirror_page names an unknown section %r' % sec)
    elif not any(p['id'] == pid for p in sections[sec]['pages']):
        problems.append('mirror_page names an unknown page %r in %r' % (pid, sec))
    if (sec, pid) == ('config', 'notify'):
        problems.append('mirror_page points at itself')
# THE COPY CHECK: the mirrored page's data must stay owned by ITS section.
for key in sections['config']:
    if key.startswith(('stack_notify', 'tiles_notify')):
        problems.append('config declares %s — that is a copy, not a mirror' % key)
print('; '.join(problems) or 'OK')
PY
)" "notify is a facet mirroring communication/my-rss, with no data of its own"

echo "== T4: mirror_page is parsed AND routed, self-reference guarded =="
mp_fail=""
grep -q 'mirror_page' "$SECTIONS"                  || mp_fail="$mp_fail Sections:not-parsed"
grep -q 'val mirrorPage' "$SECTIONS"               || mp_fail="$mp_fail Sections:no-field"
grep -q 'page.mirrorPage' "$NAV"                   || mp_fail="$mp_fail Nav:not-routed"
grep -q 'mirroredPage != null -> pageFragment' "$NAV" || mp_fail="$mp_fail Nav:no-branch"
# A page mirroring itself would re-enter aggregatorPage forever.
grep -q 'sec != section.id || pid != page.id' "$NAV"  || mp_fail="$mp_fail Nav:no-self-guard"
# The mirror branch must be decided BEFORE the section-wide fallbacks, or a
# facet with both would render the section's stack instead of the named page.
python3 - "$NAV" <<'PY' || mp_fail="$mp_fail Nav:branch-order"
import sys
s = open(sys.argv[1]).read()
sys.exit(0 if s.index('mirroredPage != null ->') < s.index('mirrored != null &&') else 1)
PY
[ -z "$mp_fail" ] \
  && ok "mirror_page: declared field, routed branch, self-reference refused, checked first" \
  || bad "mirror_page plumbing incomplete:$mp_fail"

echo "== T5: every DECLARED control has a real capability behind it =="
# An id declared with nothing implementing it is silently DROPPED by the
# fragment, so a typo here costs a control with no error anywhere.
check "$(python3 - "$BJ" "$CONTROLS" <<'PY'
import json, re, sys
cp = json.load(open(sys.argv[1]))['ui']['control_panel']
declared = [c['id'] for g in cp['groups'] for c in g['controls']]
src = open(sys.argv[2]).read()
implemented = set(re.findall(r'"([a-z_]+)" to (?:Control\(|launcherToggle\()', src))
missing = [i for i in declared if i not in implemented]
orphan = [i for i in sorted(implemented) if i not in declared]
out = []
if missing: out.append('declared with no capability: %s' % missing)
if orphan:  out.append('implemented but never declared: %s' % orphan)
if not declared: out.append('no controls declared at all')
print('; '.join(out) or 'OK')
PY
)" "declaration and capability registry name exactly the same controls"

echo "== T6: THE RULE — the four Android will not let an app flip are NOT switches =="
check "$(python3 - "$CONTROLS" <<'PY'
import re, sys
src = open(sys.argv[1]).read()
# Slice the catalog into one block per control id.
blocks, ids = {}, [(m.start(), m.group(1)) for m in
                   re.finditer(r'"([a-z_]+)" to (?:Control\(|launcherToggle\()', src)]
for i, (pos, cid) in enumerate(ids):
    end = ids[i + 1][0] if i + 1 < len(ids) else len(src)
    blocks[cid] = src[pos:end]
problems = []
# These CANNOT be toggled by a normal app on any supported Android. A `set` on
# one of them is the lying switch this whole page is written to avoid.
for cid in ('wifi', 'mobile_data', 'bluetooth', 'airplane_mode'):
    b = blocks.get(cid)
    if b is None:                 problems.append('%s: not implemented' % cid)
    elif 'set =' in b:            problems.append('%s: has a set — it would lie' % cid)
    elif 'open =' not in b:       problems.append('%s: no way through to Settings' % cid)
    elif 'read =' not in b:       problems.append('%s: no read-only state' % cid)
# Every control reads its state; a control with no read has nothing honest to show.
for cid, b in blocks.items():
    if 'read =' not in b and 'launcherToggle' not in b:
        problems.append('%s: no read' % cid)
print('; '.join(problems) or 'OK')
PY
)" "wifi / mobile_data / bluetooth / airplane_mode are Settings rows, not switches"

echo "== T7: no write is EVER reported successful without re-reading the device =="
# Verdict(true, …) as a literal means "trust the call" — exactly the bug that
# makes a switch report a change it did not achieve.
if grep -n 'Verdict(true' "$CONTROLS" >/dev/null; then
  bad "DeviceControls hardcodes a successful Verdict: $(grep -n 'Verdict(true' "$CONTROLS" | head -3)"
else
  ok "every Verdict is computed from a fresh read, never asserted"
fi

echo "== T8: the UI never displays the state that was ASKED for =="
ui_fail=""
# isChecked may only be assigned in draw(), from the value just read back.
assigns="$(grep -n 'isChecked *=' "$FRAGMENT" | grep -v '^\s*$' || true)"
echo "$assigns" | grep -q 'sw.isChecked = state == true' \
  || ui_fail="$ui_fail no-read-back-assignment"
echo "$assigns" | grep -qE 'isChecked *= *(want|on)\b' \
  && ui_fail="$ui_fail assigns-the-request"
# Assigning isChecked fires the listener, so a refresh would re-issue the last
# write on every resume — tearing down the mesh or the API server for a look.
grep -q 'setOnCheckedChangeListener(null)' "$FRAGMENT" \
  || ui_fail="$ui_fail listener-not-detached"
# A control with no `set` must never be given a Switch.
grep -q 'if (control.set != null)' "$FRAGMENT" \
  || ui_fail="$ui_fail switch-not-gated-on-set"
# A blocked switch must be disabled AND say why.
grep -q 'sw.isEnabled = blocked.isEmpty()' "$FRAGMENT" \
  || ui_fail="$ui_fail blocked-switch-still-enabled"
grep -q 'row.note.text = blocked' "$FRAGMENT" \
  || ui_fail="$ui_fail blocked-reason-not-shown"
[ -z "$ui_fail" ] \
  && ok "switches are drawn from the device read, with the listener detached" \
  || bad "the switch board can show a state it did not verify:$ui_fail"

echo "== T9: ui.control_panel reaches the APK — emitter, constant, reader =="
cp_fail=""
grep -q 'buildJson.ui.control_panel' "$GRADLE"          || cp_fail="$cp_fail gradle:no-json-read"
grep -q 'uiControlPanelB64' "$GRADLE"                   || cp_fail="$cp_fail gradle:no-b64"
grep -q 'UI_CONTROL_PANEL_B64' "$GRADLE"                || cp_fail="$cp_fail gradle:no-buildconfig"
grep -q 'BuildConfig.UI_CONTROL_PANEL_B64' "$CONTROLS"  || cp_fail="$cp_fail kotlin:not-read"
[ -z "$cp_fail" ] \
  && ok "build.json → uiControlPanelB64 → BuildConfig.UI_CONTROL_PANEL_B64 → DeviceControls" \
  || bad "the declaration cannot reach the UI:$cp_fail"

echo "== T10: three groups, by blast radius, every control in exactly one =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
cp = json.load(open(sys.argv[1]))['ui']['control_panel']
ids = [g['id'] for g in cp['groups']]
problems = []
if ids != ['phone', 'cloud', 'system']:
    problems.append('groups = %r, expected [phone, cloud, system]' % ids)
seen, dupes = set(), []
for g in cp['groups']:
    if not g.get('label'):   problems.append('group %s has no label' % g['id'])
    if not g.get('controls'):problems.append('group %s is empty' % g['id'])
    for c in g['controls']:
        if c['id'] in seen:  dupes.append(c['id'])
        seen.add(c['id'])
        if not c.get('label'):    problems.append('%s has no label' % c['id'])
        if not c.get('subtitle'): problems.append('%s has no subtitle' % c['id'])
if dupes: problems.append('control in two groups: %s' % dupes)
print('; '.join(problems) or 'OK')
PY
)" "Phone / Cloud / System, labelled, non-empty, no control in two of them"

echo "== T11: Control is routed by id; Notify is NOT (it goes through the facet) =="
r_fail=""
grep -q 'pageId == "control" ->' "$PAGES"        || r_fail="$r_fail control:not-routed"
grep -q 'ControlFragment.newInstance()' "$PAGES" || r_fail="$r_fail control:no-fragment"
# A SectionPages branch for `notify` would bypass the mirror and hand back a
# generic placeholder, which is how the tab would quietly stop being the ntfy
# page while still opening something.
grep -q 'pageId == "notify"' "$PAGES"            && r_fail="$r_fail notify:shadowed-by-factory"
[ -z "$r_fail" ] \
  && ok "config/control → ControlFragment; config/notify left to the mirror" \
  || bad "page routing wrong:$r_fail"

echo "== T12: the tab a strip OPENS ON is the first declared tab, and nothing else =="
# "Control is first" and "Panel opens on Control" are only the same statement
# while startIndex's fallback stays "the first tab with a fragment". If someone
# adds a default_tab flag, the array and the flag become two answers to one
# question and the reorder above silently stops deciding anything.
land_fail=""
grep -q 'pages.indexOfFirst { it.action.isBlank() }.coerceAtLeast(0)' "$TABS" \
  || land_fail="$land_fail no-first-tab-fallback"
grep -qE '"default_tab"|"initial_tab"|"selected_tab"' "$BJ" \
  && land_fail="$land_fail second-source-of-truth-in-json"
grep -qE 'defaultTab|initialTab' "$SECTIONS" \
  && land_fail="$land_fail second-source-of-truth-in-kotlin"
# WHICH design this app has, pinned so changing it has to be deliberate: the
# strip is restored to the tab the user left, from an IN-MEMORY map on the
# controller — so the first-tab rule decides a cold open, and the rest of the
# process gets the last tab looked at. A move to SharedPreferences would make
# a phone that once opened Notify open Notify forever, which is a different
# product decision and must not arrive as a refactor.
grep -q 'activeTabFor(tabKey)' "$TABS" \
  || land_fail="$land_fail strip-does-not-restore"
grep -q 'private val activeTabBySection = mutableMapOf<String, String>()' "$NAV" \
  || land_fail="$land_fail tab-memory-is-no-longer-in-memory"
[ -z "$land_fail" ] \
  && ok "first declared tab is the landing tab; last-viewed tab is remembered per process only" \
  || bad "the declared tab order does not decide what opens:$land_fail"

echo "== T13: every DECLARED control carries an icon that RESOLVES, and a state source =="
# Two ways a row ships blank, neither of which the compiler or the JSON parser
# will mention:
#   - no `icon` at all, or one naming a drawable that does not exist. Sections
#     .iconResFor falls back to the generic ic_link_tile for any name it cannot
#     resolve, so a typo becomes a row that looks deliberate and means nothing.
#   - no `read`, which is the ONLY thing a light is painted from. A row without
#     one could not have a light that was driven by anything.
# Adding a control without either has to fail HERE, because nothing downstream
# fails at all.
check "$(python3 - "$BJ" "$CONTROLS" "$APP/app/src/main/res/drawable" <<'PY'
import json, os, re, sys
cp = json.load(open(sys.argv[1]))['ui']['control_panel']
src = open(sys.argv[2]).read()
drawables = sys.argv[3]

# One block per control id, so `read` is attributed to the right entry.
blocks, ids = {}, [(m.start(), m.group(1)) for m in
                   re.finditer(r'"([a-z_]+)" to (?:Control\(|launcherToggle\()', src)]
for i, (pos, cid) in enumerate(ids):
    blocks[cid] = src[pos:(ids[i + 1][0] if i + 1 < len(ids) else len(src))]

problems = []
for g in cp['groups']:
    for c in g['controls']:
        cid = c['id']
        icon = c.get('icon', '')
        if not icon:
            problems.append('%s: no icon declared' % cid)
        elif not any(os.path.exists(os.path.join(drawables, icon + ext))
                     for ext in ('.xml', '.png', '.webp')):
            problems.append('%s: icon %r is not a drawable - it would render '
                            'as the ic_link_tile fallback' % (cid, icon))
        b = blocks.get(cid)
        if b is None:
            problems.append('%s: no capability, so no state source' % cid)
        elif 'read =' not in b and 'launcherToggle' not in b:
            problems.append('%s: no read - its light could not be driven' % cid)
print('; '.join(problems) or 'OK')
PY
)" "every control: an icon that resolves to a real drawable, and a read to light it"

echo "== T14: NO light colour is hardcoded - every one comes from StatusLight =="
# A hex green written at the row is a colour chosen at BUILD time. It survives
# the state source being removed, which is exactly how a light stops meaning
# anything while still looking right.
light_fail=""
grep -qE '0x[fF][fF](16A34A|DC2626|6B7280)' "$FRAGMENT" \
  && light_fail="$light_fail fragment-hardcodes-a-light-colour"
grep -q 'StatusLight.colour(' "$FRAGMENT" || light_fail="$light_fail fragment-does-not-use-the-token"
# setTextColor on the light must take StatusLight's answer and nothing else.
grep -qE 'light\.setTextColor\(StatusLight\.colour\(' "$FRAGMENT" \
  || light_fail="$light_fail light-painted-from-something-else"
# ONE definition of healthy / failed / cannot-say in the whole app. Scoped to
# constants NAMED as a status colour on purpose: the same hex used as a plain
# palette colour elsewhere (CalendarAgendaPopup's green play glyph) is not a
# second status light and banning it would make this assertion a lie about
# what it protects. A second `GREEN =` IS the defect - that is a page about to
# drift into its own idea of what healthy looks like.
dupes="$(grep -rnE '(GREEN|RED|GREY|GRAY|OK|FAIL|HEALTHY|STATUS)[A-Z_]*  *= *0x[fF][fF](16A34A|DC2626|6B7280)' \
         --include=*.kt "$APP/app/src/main/java" 2>/dev/null | grep -v 'StatusLight.kt' || true)"
[ -z "$dupes" ] || light_fail="$light_fail status-colour-redefined-in:$(echo "$dupes" | cut -d: -f1 | xargs -n1 basename | tr '\n' ',')"
grep -q '0xFF16A34A' "$STATUS" || light_fail="$light_fail StatusLight-is-not-the-definer"
[ -z "$light_fail" ] \
  && ok "every light colour resolves from StatusLight, which is its only definition" \
  || bad "a light colour is hardcoded or duplicated:$light_fail"

echo "== T15: a state source that will not answer renders UNKNOWN, never green =="
# The third state. Red and green are two; the check that has not run, threw,
# timed out or aged out is the third, and drawing it as either of the other two
# is a false statement the owner cannot see is false.
unknown_fail=""
# null MUST map to UNKNOWN in the one place that decides.
python3 - "$STATUS" <<'PY' || unknown_fail="$unknown_fail null-is-not-unknown"
import re, sys
s = open(sys.argv[1]).read()
m = re.search(r'fun of\(reading: Boolean\?\).*?\n    \}', s, re.S)
sys.exit(0 if m and re.search(r'null\s*->\s*State\.UNKNOWN', m.group(0)) else 1)
PY
# ...and UNKNOWN must not be painted with the ON colour.
python3 - "$STATUS" <<'PY' || unknown_fail="$unknown_fail unknown-is-green"
import re, sys
s = open(sys.argv[1]).read()
m = re.search(r'fun colour\(state: State\).*?\n    \}', s, re.S)
sys.exit(0 if m and re.search(r'State\.UNKNOWN\s*->\s*GREY', m.group(0)) else 1)
PY
# A read that throws must become null, not a default.
grep -q 'runCatching { row.control.read(ctx) }.getOrNull()' "$FRAGMENT" \
  || unknown_fail="$unknown_fail throwing-read-not-nulled"
# A row never read, or read too long ago, has no answer to show.
grep -q 'if (row.readAt != 0L' "$FRAGMENT" || unknown_fail="$unknown_fail never-read-not-unknown"
grep -q 'SystemClock.elapsedRealtime() - row.readAt <= STALE_MS' "$FRAGMENT" \
  || unknown_fail="$unknown_fail stale-reading-still-shown"
# The light is painted from reading(), which is the function that ages out -
# painting from row.reading directly would skip the staleness rule entirely.
grep -q 'StatusLight.of(reading(row))' "$FRAGMENT" \
  || unknown_fail="$unknown_fail light-bypasses-the-stale-check"
# Nothing may coerce a null reading into a boolean on the way to a light.
grep -qE 'reading\(row\)\s*(\?:|== true)' "$FRAGMENT" \
  && unknown_fail="$unknown_fail null-coerced-before-the-light"
[ -z "$unknown_fail" ] \
  && ok "unread, throwing and aged-out readings all render Unknown, not a colour" \
  || bad "the third state is drawn as one of the other two:$unknown_fail"

echo "== T16: the lights poll only while the page is VISIBLE, and age out slower than they poll =="
# This is a phone. A control panel that keeps binding the WireGuard engine and
# probing shell channels while the owner is in another app is a battery cost
# with no reader.
life_fail=""
grep -q 'main.post(ticker)' "$FRAGMENT"            || life_fail="$life_fail no-start-on-resume"
grep -q 'main.removeCallbacks(ticker)' "$FRAGMENT" || life_fail="$life_fail never-stopped"
grep -q 'override fun onPause()' "$FRAGMENT"       || life_fail="$life_fail no-onPause"
# The stop must be in onPause, not only in onDestroy - a fragment that is merely
# covered still runs its handler.
python3 - "$FRAGMENT" <<'PY' || life_fail="$life_fail stop-not-in-onPause"
import re, sys
s = open(sys.argv[1]).read()
m = re.search(r'override fun onPause\(\).*?\n    \}', s, re.S)
sys.exit(0 if m and 'removeCallbacks(ticker)' in m.group(0) else 1)
PY
grep -q 'main.postDelayed(this, REFRESH_MS)' "$FRAGMENT" || life_fail="$life_fail ticker-does-not-repeat"
# A reading must outlive the interval that refreshes it, or every row blinks to
# Unknown between ticks and the light becomes noise the owner learns to ignore.
python3 - "$FRAGMENT" <<'PY' || life_fail="$life_fail stale-window-shorter-than-poll"
import re, sys
s = open(sys.argv[1]).read()
def const(n):
    m = re.search(r'%s = ([0-9_]+)L' % n, s)
    return int(m.group(1).replace('_', '')) if m else None
r, st = const('REFRESH_MS'), const('STALE_MS')
sys.exit(0 if r and st and st > r else 1)
PY
# ONE state path. A second poller alongside the existing read is how two
# surfaces start disagreeing about the same device.
for banned in 'java.util.Timer' 'ScheduledExecutorService' 'lifecycleScope.launch'; do
  grep -q "$banned" "$FRAGMENT" && life_fail="$life_fail second-poller:$banned"
done
[ -z "$life_fail" ] \
  && ok "one ticker, started on resume, cancelled on pause, ageing slower than it polls" \
  || bad "the panel polls when nobody is looking, or blinks when they are:$life_fail"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
