#!/usr/bin/env bash
# Tester: every build.json ui icon names a drawable that actually exists,
# and C3-Bot carries the BOT glyph rather than the AI one.
#
# WHY THIS EXISTS. The owner asked (#291) for one value to change: the C3-Bot
# tile in Cloud ▸ Inboxes was drawn with ic_ai_chat, the same glyph as the three
# real AI tiles beside it (Chat GPT / Claude / Gemini), so the one tile in that
# group that opens a Telegram automation read as a fourth chatbot.
#
# Asserting only that one string would be the weaker half of the job. The way
# an icon reaches the screen has NO failure signal:
#
#   Sections.iconResFor (launcher/Sections.kt:1154) is a pure reflective
#   Resources.getIdentifier(name, "drawable", pkg). There is no icon map, no
#   allow-list, and no build-time validation — app/build.gradle's stripDocs
#   only removes _doc* keys and passes `icon` through verbatim into
#   BuildConfig.UI_SECTIONS_JSON_B64.
#
#   A name that resolves to NOTHING therefore does not fail the build, does not
#   crash, and does not log: iconResFor falls through to the ic_link_tile
#   fallback (Sections.kt:1161) and the tile renders as a generic link. The
#   owner sees a wrong icon, which is the exact symptom he reported — so a typo
#   in the fix would reproduce the bug it was meant to close.
#
# T1 is therefore the general guard, derived from the data rather than from a
# list written here: every `icon` anywhere under ui is resolved against the real
# res/drawable* contents using iconResFor's own fallback chain. T2 pins the one
# value the owner actually asked for.
#
# Static tester: no device, no build. build.json is read as data; the resource
# directories are read from disk.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

BJ="$APP/build.json"
RES="$APP/app/src/main/res"
SECTIONS_KT="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/Sections.kt"

# FAIL CLOSED. A tester whose tool is absent answers from the tool's absence,
# not from the tree. Every assertion below runs through python3.
if ! command -v python3 >/dev/null 2>&1; then
  echo "  FAIL: python3 is not on PATH — this tester proves nothing without it"
  echo "== RESULT: 0 passed, 1 failed =="
  exit 1
fi
for f in "$BJ" "$SECTIONS_KT"; do
  if [ ! -f "$f" ]; then
    echo "  FAIL: missing file $f — the tree is not what this tester was written against"
    echo "== RESULT: 0 passed, 1 failed =="
    exit 1
  fi
done
if [ ! -d "$RES" ]; then
  echo "  FAIL: missing resource dir $RES"
  echo "== RESULT: 0 passed, 1 failed =="
  exit 1
fi

echo "== T1: every ui icon resolves to a real drawable (no silent ic_link_tile fallback) =="
# The resolution order mirrors Sections.iconResFor exactly: literal name, then
# ic_<slug> with .svg stripped and '-'->'_', then progressively trimming a
# trailing _<n>. Anything that still misses would render as the fallback.
UNRESOLVED="$(python3 -c "
import json, os, re, sys
d = json.load(open('$BJ'))
icons = {}
def walk(o, path='ui'):
    if isinstance(o, dict):
        v = o.get('icon')
        if isinstance(v, str) and v.strip():
            icons.setdefault(v.strip(), path + ' (' + str(o.get('id') or o.get('label') or '?') + ')')
        for k, x in o.items():
            walk(x, path + '.' + k)
    elif isinstance(o, list):
        for i, x in enumerate(o):
            walk(x, path + '[' + str(i) + ']')
walk(d.get('ui') or {})
draw = set()
for dirn in os.listdir('$RES'):
    if dirn.startswith('drawable') or dirn.startswith('mipmap'):
        for f in os.listdir(os.path.join('$RES', dirn)):
            draw.add(re.sub(r'\.(xml|png|webp|jpg|jpeg|gif)\$', '', f))
def resolves(n):
    if n in draw: return True
    slug = re.sub(r'\.svg\$', '', n).replace('-', '_')
    if 'ic_' + slug in draw: return True
    t = slug
    while re.search(r'_\d+\$', t):
        t = re.sub(r'_\d+\$', '', t)
        if 'ic_' + t in draw: return True
    return False
if not icons:
    print('NO-ICONS-FOUND'); sys.exit(0)
for n in sorted(icons):
    if not resolves(n):
        print(n + ' @ ' + icons[n])
" 2>/dev/null)"
if [ "$UNRESOLVED" = "NO-ICONS-FOUND" ]; then
  bad "found no ui icons at all in build.json — the walk is broken, not the data"
elif [ -z "$UNRESOLVED" ]; then
  ok "every build.json ui icon names an existing drawable"
else
  bad "icon names with no drawable — these render as the generic ic_link_tile fallback:"
  echo "$UNRESOLVED" | while IFS= read -r l; do echo "        $l"; done
fi

echo "== T2: C3-Bot uses the BOT glyph, not the AI one (#291) =="
# By id, not by position: the tile is ui.sections[cloud].tile_groups[Inboxes].
# Its three neighbours are genuinely AI and MUST keep ic_ai_chat — the owner
# asked about C3-Bot only, so a blanket ic_ai_chat purge would be wrong.
C3_ICON="$(python3 -c "
import json
d = json.load(open('$BJ'))
def walk(o):
    if isinstance(o, dict):
        if o.get('id') == 'c3-bot': print(o.get('icon', '')); return True
        for x in o.values():
            if walk(x): return True
    elif isinstance(o, list):
        for x in o:
            if walk(x): return True
    return False
walk(d)
" 2>/dev/null)"
if [ -z "$C3_ICON" ]; then
  bad "no tile with id 'c3-bot' in build.json — the tile the owner named is gone"
elif [ "$C3_ICON" = "ic_robot" ]; then
  ok "c3-bot icon is ic_robot"
else
  bad "c3-bot icon is '$C3_ICON', expected ic_robot"
fi

# The glyph has to EXIST for T2 to mean anything — see the ic_link_tile note above.
if [ -f "$RES/drawable/ic_robot.xml" ]; then
  ok "res/drawable/ic_robot.xml exists, so the name above resolves"
else
  bad "res/drawable/ic_robot.xml is missing — c3-bot would fall back to ic_link_tile"
fi

echo "== T3: the three genuine AI tiles were NOT swept up in the change =="
# Guard against the over-broad fix. The owner named one tile; a sed over the
# file would have retargeted Chat GPT / Claude / Gemini too, and nobody asked.
AI_COUNT="$(python3 -c "
import json
d = json.load(open('$BJ'))
n = 0
def walk(o):
    global n
    if isinstance(o, dict):
        if o.get('icon') == 'ic_ai_chat': n += 1
        for x in o.values(): walk(x)
    elif isinstance(o, list):
        for x in o: walk(x)
walk(d.get('ui') or {})
print(n)
" 2>/dev/null)"
if [ "$AI_COUNT" = "3" ]; then
  ok "exactly 3 tiles still carry ic_ai_chat (the real AI ones)"
else
  bad "expected 3 remaining ic_ai_chat tiles, found '$AI_COUNT' — c3-bot's sibling AI tiles must keep their glyph"
fi

echo "== T4: the lookup this tester models is still the lookup the app uses =="
# If iconResFor stops being a getIdentifier reflection — e.g. someone adds a
# generated icon map — T1's model silently stops matching reality and starts
# certifying the wrong thing. Pin the mechanism, not just the data.
if grep -q 'getIdentifier(name, "drawable", pkg)' "$SECTIONS_KT"; then
  ok "Sections.iconResFor still resolves by literal getIdentifier"
else
  bad "Sections.kt no longer contains the literal getIdentifier(name, \"drawable\", pkg) lookup — T1's resolution model is stale and may be certifying icons that no longer resolve this way"
fi
if grep -q 'ic_link_tile' "$SECTIONS_KT"; then
  ok "the silent ic_link_tile fallback is still what an unresolved name hits"
else
  bad "ic_link_tile fallback is gone from Sections.kt — an unresolved icon may now behave differently than this tester assumes"
fi

echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
