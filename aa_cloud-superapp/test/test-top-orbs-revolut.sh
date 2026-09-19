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
for orb in ("top_menu_orb", "top_wallet_orb"):
    i = t.find('android:id="@+id/%s"' % orb)
    if i < 0:
        p.append("%s is not declared" % orb); continue
    tag = t[t.rfind('<', 0, i):t.find('>', i) + 1]
    if 'bg_liquid_glass_pill' not in tag:
        p.append("%s is not backed by bg_liquid_glass_pill — not the glass family the islands use" % orb)
print("; ".join(p) or "OK")
PY
)" "top_menu_orb + top_wallet_orb declared with bg_liquid_glass_pill"

echo "== T2: the right orb's target is DECLARED, and it is cloud-me =="
check "$(python3 - build.json <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
ui = d.get('ui', {})
orbs = ui.get('top_orbs') or {}
p = []
if orbs.get('right_extapp') != 'cloud-me':
    p.append("ui.top_orbs.right_extapp is %r, not 'cloud-me' (the Wallet deck lives inside Cloud-Me)" % orbs.get('right_extapp'))
if '_doc_top_orbs' not in ui:
    p.append("no _doc_top_orbs — the declaration has no story")
print("; ".join(p) or "OK")
PY
)" "build.json::ui.top_orbs.right_extapp == cloud-me, documented"

echo "== T3: ShellActivity reads the declaration — no hardcoded orb target =="
check "$(python3 - "$SHELL_KT" <<'PY'
import sys
t = open(sys.argv[1]).read()
p = []
if 'UI_TOP_ORBS_B64' not in t:
    p.append("ShellActivity never reads UI_TOP_ORBS_B64 — the declared target is inert")
for lit in ('launchExternalApp("cloud-wallet")', 'launchExternalApp("cloud-me")'):
    if lit in t:
        p.append("hardcoded %s — the exact defect the declaration replaces" % lit)
if 'top_menu_orb' not in t or 'top_wallet_orb' not in t:
    p.append("orbs are not wired in ShellActivity")
print("; ".join(p) or "OK")
PY
)" "target read from UI_TOP_ORBS_B64, no literal app id in Kotlin"

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

echo "== T5: at the Home root the full-width bar dissolves =="
# Revolut's look is floating circles, not a restyled slab: ShellActivity must
# null the toolbar_island background (and the toolbar hamburger) at the Home
# root, restoring both off it.
check "$(python3 - "$SHELL_KT" <<'PY'
import re, sys
t = open(sys.argv[1]).read()
p = []
if not re.search(r'toolbar_island[\s\S]{0,200}?background\s*=[\s\S]{0,80}?atHomeRoot|atHomeRoot[\s\S]{0,400}?toolbar_island[\s\S]{0,120}?background', t):
    p.append("toolbar_island's background is not toggled with atHomeRoot — the glass slab stays behind the orbs")
print("; ".join(p) or "OK")
PY
)" "toolbar_island background nulls at Home root (bar dissolves)"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
