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
CONTROLS="$APP/app/src/main/java/com/diegonmarcos/superapp/configs/DeviceControls.kt"
FRAGMENT="$APP/app/src/main/java/com/diegonmarcos/superapp/configs/ControlFragment.kt"

echo "== T1: Configs ▸ Panel is a visible page declaring its two tabs in order =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
panel = next((p for p in pages if p['id'] == 'panel'), None)
if panel is None:                    print('no `panel` page in config')
elif panel.get('tabs') != ['notify', 'control']:
                                     print('tabs = %r' % (panel.get('tabs'),))
elif panel.get('hidden'):            print('the strip itself must stay listed')
elif pages[0]['id'] != 'panel':      print('Panel is not first: %s' % pages[0]['id'])
else:                                print('OK')
PY
)" "panel: tabs = [notify, control], visible, first in the Configs grid"

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

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
