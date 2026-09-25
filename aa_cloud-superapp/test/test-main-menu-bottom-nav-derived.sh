#!/usr/bin/env bash
# #521 — the main-menu's section list is DERIVED from ui.bottom_nav, not a copy.
#
# WHY: the drawer's Home group hand-listed section tiles ("Inboxes", Projects,
# Cloud …) while the bar became Notify/Projects/Home/Cloud/Phone across
# #462/#473/#477/#498/#511 — the menu named a section differently and had no
# Phone. Asserted against the RESOLVED menu (bottom_nav ids -> ui.sections),
# never against a class name or label literal (#511).
set -u
cd "$(dirname "$0")/.."
SECTIONS_KT="app/src/main/java/com/diegonmarcos/superapp/launcher/Sections.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

OUT=$(python3 - build.json "$SECTIONS_KT" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])); ui = d['ui']
kt = ''.join(l for l in open(sys.argv[2]) if not l.lstrip().startswith(('//', '*', '/*')))
secs = {s['id']: s for s in ui['sections']}
p = []
grp = ui['home_groups'][0]
# Resolve exactly as Sections.homeGroups() does: derived (bottom-nav) tiles, then explicit.
resolved = []
if grp.get('tiles_from_bottom_nav'):
    for sid in ui['bottom_nav']:
        s = secs.get(sid)
        if s is None:
            p.append("bottom_nav names unknown section %r" % sid); continue
        if not s.get('is_master_index'):
            resolved.append(('section:' + sid, s['label']))
resolved += [(t['id'], t['label']) for t in grp.get('tiles', [])]
want = [(('section:' + sid), secs[sid]['label']) for sid in ui['bottom_nav']
        if sid in secs and not secs[sid].get('is_master_index')]
if not want:
    p.append("bottom_nav resolves to zero non-master sections - nothing to assert")
for w in want:
    if w not in resolved:
        p.append("resolved main-menu lacks bottom-nav entry %s" % (w,))
ids = [i for i, _ in resolved]
if len(ids) != len(set(ids)):
    p.append("resolved main-menu repeats a destination: %s" % sorted(i for i in set(ids) if ids.count(i) > 1))
# No explicit tile may restate a bottom-nav section: that is the second copy.
for t in grp.get('tiles', []):
    if t['id'].startswith('section:') and t['id'][8:] in ui['bottom_nav']:
        p.append("home_groups[0] restates bottom-nav section %s by hand" % t['id'])
if 'tiles_from_bottom_nav' not in kt or 'bottomNav()' not in kt:
    p.append("Sections.homeGroups() does not implement tiles_from_bottom_nav")
print('; '.join(p) or 'OK')
PY
)
[ "$OUT" = "OK" ] && ok "main-menu resolves every bottom-nav section (label from ui.sections), no hand copy" \
                   || bad "$OUT"

echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
