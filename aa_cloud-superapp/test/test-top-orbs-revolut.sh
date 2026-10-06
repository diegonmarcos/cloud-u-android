#!/usr/bin/env bash
# test-top-orbs-revolut.sh — the Home-root top chrome is two detached
# liquid-glass corner orbs (Revolut style), declaratively targeted.
#
# WHY (2026-09-19): the owner asked for Revolut's top row — main menu top
# left, wallet top right, both round glass like the bottom-nav family — and
# for the wallet's default app to move from cloud-wallet to cloud-me (the
# Wallet deck lives inside Cloud-Me now). The target must be a build.json
# declaration: the old toolbar handler hardcoded launchExternalApp("cloud-
# wallet") in Kotlin, which is exactly how a moved app keeps getting launched
# at its old address.
#
# #879 the target became a TILE TARGET in the one grammar (`ui.top_orbs.right`:
# section:<id> | extapp:<id>), dispatched by ShellActivity.onTileClicked like any
# tile; it is section:config now, and the orb wears the section's declared icon
# and label. T2/T3 hold that, and T6 plants mutations to prove they notice.
set -u
cd "$(dirname "$0")/.."
LAYOUT="app/src/main/res/layout/activity_main.xml"
SHELL_KT="app/src/main/java/com/diegonmarcos/superapp/ShellActivity.kt"
GRADLE="app/build.gradle"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { [ "$1" = "OK" ] && ok "$2" || bad "$2 — $1"; }

echo "== T1: both corner orbs exist in the layout, round liquid glass =="
check "$(python3 - "$LAYOUT" <<'PY'
import re, sys
t = open(sys.argv[1]).read()
p = []
for orb in ("main_menu_orb", "top_wallet_orb"):
    i = t.find('android:id="@+id/%s"' % orb)
    if i < 0:
        p.append("%s is not declared" % orb); continue
    tag = t[t.rfind('<', 0, i):t.find('>', i) + 1]
    if 'bg_liquid_glass_pill' not in tag:
        p.append("%s is not backed by bg_liquid_glass_pill — not the glass family the islands use" % orb)
print("; ".join(p) or "OK")
PY
)" "main_menu_orb + top_wallet_orb declared with bg_liquid_glass_pill"

echo "== T2: the right orb's target is DECLARED as a tile target, and it is the Configs section =="
check "$(python3 - build.json <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
ui = d.get('ui', {})
orbs = ui.get('top_orbs') or {}
p = []
right = orbs.get('right')
if right != 'section:config':
    p.append("ui.top_orbs.right is %r, not 'section:config'" % right)
if 'right_extapp' in orbs:
    p.append("the old right_extapp key is still declared beside `right` — two targets, one orb")
sec = [s for s in ui.get('sections', []) if s.get('id') == 'config']
if not sec or not sec[0].get('icon') or not sec[0].get('label'):
    p.append("section config declares no icon/label for the orb to wear")
if '_doc_top_orbs' not in ui:
    p.append("no _doc_top_orbs — the declaration has no story")
print("; ".join(p) or "OK")
PY
)" "build.json::ui.top_orbs.right == section:config, documented, the section declares its icon + label"

echo "== T3: ShellActivity reads the declaration and dispatches it as a tile — no hardcoded orb target =="
shell_check() { python3 - "$1" <<'PY'
import re, sys
t = open(sys.argv[1]).read()
p = []
if 'UI_TOP_ORBS_B64' not in t:
    p.append("ShellActivity never reads UI_TOP_ORBS_B64 — the declared target is inert")
if '.optString("right")' not in t:
    p.append("the orb does not read the `right` key")
for lit in ('launchExternalApp("cloud-wallet")', 'launchExternalApp("cloud-me")'):
    if lit in t:
        p.append("hardcoded %s — the exact defect the declaration replaces" % lit)
m = re.search(r'top_wallet_orb\)\?\.setOnClickListener \{([\s\S]*?)\n            \}', t)
if not m or 'onTileClicked(' not in m.group(1):
    p.append("the orb's click does not go through onTileClicked, the tile dispatcher")
if m and 'launchExternalApp(' in m.group(1):
    p.append("the orb's click launches an app directly instead of the declared target")
if 'Sections.byId(' not in t or 'top_wallet_orb_icon' not in t or 'section.iconName' not in t:
    p.append("the orb does not wear its section's declared icon")
if 'contentDescription = section.label' not in t:
    p.append("the orb's content description is not the section label")
if 'main_menu_orb' not in t or 'top_wallet_orb' not in t:
    p.append("orbs are not wired in ShellActivity")
print("; ".join(p) or "OK")
PY
}
check "$(shell_check "$SHELL_KT")" "target read from UI_TOP_ORBS_B64, dispatched by onTileClicked, icon + label from the section, no literal"
check "$(grep -c 'android:id="@+id/top_wallet_orb_icon"' "$LAYOUT" | sed 's/^1$/OK/')" "the orb's glyph has an id the shell can re-skin"

echo "== T4: the gradle bake exists — build.json reaches BuildConfig =="
check "$(python3 - "$GRADLE" <<'PY'
import sys
t = open(sys.argv[1]).read()
p = []
if 'buildJson.ui.top_orbs' not in t:
    p.append("build.gradle never reads buildJson.ui.top_orbs")
if 'UI_TOP_ORBS_B64' not in t:
    p.append("no UI_TOP_ORBS_B64 buildConfigField")
print("; ".join(p) or "OK")
PY
)" "UI_TOP_ORBS_B64 baked from buildJson.ui.top_orbs"

echo "== T5: the top slab does not exist — on ANY screen =="
# The first orbs commit "restored" bg_liquid_glass on toolbar_island off the
# Home root. The layout declares NO background on that island, so the code
# invented a full-width slab that appeared whenever a section was selected —
# reported by the owner the same day (screenshot 2026-09-19 13:43). Neither
# the XML nor ShellActivity may give toolbar_island a background; icons and
# the centre islands float over the wallpaper everywhere.
check "$(python3 - "$LAYOUT" "$SHELL_KT" <<'PY'
import re, sys
p = []
lay = open(sys.argv[1]).read()
i = lay.find('android:id="@+id/toolbar_island"')
tag = lay[lay.rfind('<', 0, i):lay.find('>', i) + 1]
if 'android:background' in tag:
    p.append("toolbar_island declares a background in the layout — the slab is back")
kt = open(sys.argv[2]).read()
if re.search(r'toolbar_island[\s\S]{0,200}?background\s*=\s*(?!\s*null)[^\n]*getDrawable', kt):
    p.append("ShellActivity assigns a drawable to toolbar_island's background — the invented slab is back")
print("; ".join(p) or "OK")
PY
)" "no background on toolbar_island, in XML or in code"

echo "== T6: mutations - the checks above notice a revert =="
SCRATCH="$(mktemp -d)"; trap 'rm -rf "$SCRATCH"' EXIT
sed 's/topOrbTarget().takeIf { t -> t.isNotBlank() }?.let { t -> onTileClicked(t) }/launchExternalApp("cloud-me")/' "$SHELL_KT" > "$SCRATCH/m1.kt"
r="$(shell_check "$SCRATCH/m1.kt")"; [ "$r" != "OK" ] && ok "M1 orb click reverted to a hardcoded launchExternalApp is caught" || bad "M1 hardcoded launch NOT caught"
sed 's/\.optString("right")/.optString("right_extapp")/' "$SHELL_KT" > "$SCRATCH/m2.kt"
r="$(shell_check "$SCRATCH/m2.kt")"; [ "$r" != "OK" ] && ok "M2 reading the old right_extapp key is caught" || bad "M2 old key NOT caught"
sed 's/contentDescription = section.label/contentDescription = "Wallet"/' "$SHELL_KT" > "$SCRATCH/m3.kt"
r="$(shell_check "$SCRATCH/m3.kt")"; [ "$r" != "OK" ] && ok "M3 a literal content description is caught" || bad "M3 literal description NOT caught"
sed 's/setImageResource(Sections.iconResFor(this, section.iconName))/setImageResource(0)/' "$SHELL_KT" > "$SCRATCH/m4.kt"
r="$(shell_check "$SCRATCH/m4.kt")"; [ "$r" != "OK" ] && ok "M4 an orb that stops wearing the section icon is caught" || bad "M4 icon revert NOT caught"
python3 - <<'PY' > "$SCRATCH/bj.json"
import json
d = json.load(open('build.json')); d['ui']['top_orbs'] = {'right_extapp': 'cloud-me'}; print(json.dumps(d))
PY
python3 - "$SCRATCH/bj.json" <<'PY' && ok "M5 build.json put back to right_extapp is caught" || bad "M5 declaration revert NOT caught"
import json, sys
o = json.load(open(sys.argv[1]))['ui']['top_orbs']
sys.exit(0 if o.get('right') != 'section:config' else 1)
PY

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
