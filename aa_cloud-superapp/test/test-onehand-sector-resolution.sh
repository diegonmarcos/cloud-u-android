#!/usr/bin/env bash
# Tester: an edge-menu sector RESOLVES to a label and an icon.
#
# WHY THIS EXISTS, AND WHY test-onehand-edge-order.sh DID NOT CATCH IT.
#
# The owner reported "AI Claude missing from the left edge menu" three times
# (#111, #127, and again on 2026-09-11). Twice it was closed by editing data
# while the resolver stayed broken. The declaration was never the problem:
# build.json has carried the correct target since 2026-09-10 and the menu was
# still wrong, so EVERY assertion that looks at the declaration passes on the
# broken code. That is precisely how this shipped green twice.
#
# The neighbouring tester's T4 claims "all twelve targets resolve". It does not
# resolve them. It contains
#
#     if target.startswith('intent://') or target.startswith('http'): continue
#
# and the AI Claude sector is `action:intent://…`, so the ONE broken sector was
# the one sector T4 skipped. Its T5 then compares the edge target to the Cloud
# tile target — two declarations that were always identical. Between them they
# certified a menu whose first entry drew as a title-cased URL with no icon.
#
# So this tester never asks "is the string present". It asks, for every sector
# on both handles: WHAT LABEL AND WHAT ICON DOES IT RESOLVE TO — through the
# same tile catalogue the engine resolves through — and it skips nothing.
#
# The two symptoms, stated as the invariants they came from:
#   1. An `action:` sector resolves to the label+icon its TILE owns. Nothing
#      else can supply one: the target names no package, so PackageManager —
#      the only icon source this menu had — has nothing to give it.
#   2. `action:` is decoration, not identity. GestureAction.serialize() re-adds
#      it, so `action:section:drive` and `section:drive` are ONE destination and
#      must compare equal. Two spellings of one target is half of #111/#127.
#   3. A target that resolves to nothing must FAIL, never silently become
#      index 0 — which is "None", i.e. a working slot displayed as unset, one
#      tap away from being persisted that way.
#
# Static tester: no device, no build. build.json is read as DATA through python
# (never grepped — this file's _doc strings contain the words "AI Claude",
# "Drive", "icon", "top_outer" and "action:section:drive" in prose, so a grep
# over it matches documentation instead of configuration). The Kotlin and the
# Gradle are checked with COMMENTS STRIPPED, because the fix's own comments
# quote the broken code they replaced.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
LIBS="$APP/../ab_cloud-libs-shared/libs"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

BJ="$APP/build.json"
FRAG="$APP/app/src/main/java/com/diegonmarcos/superapp/configs/OneHandFragment.kt"
STRINGS="$APP/app/src/main/res/values/strings.xml"
STRINGS_ES="$APP/app/src/main/res/values-es/strings.xml"
OH_GRADLE="$LIBS/launcher-onehand/build.gradle"
OH_CONFIG="$LIBS/launcher-onehand/src/main/java/com/diegonmarcos/superapp/onehand/OneHandConfig.kt"
OH_SVC="$LIBS/launcher-onehand/src/main/java/com/diegonmarcos/superapp/onehand/OneHandAccessibilityService.kt"
DRAWABLE="$APP/app/src/main/res/drawable"

# FAIL CLOSED. Four testers in this repository once passed only because the tool
# they called was absent and the call failed open.
if ! command -v python3 >/dev/null 2>&1; then
  echo "  FAIL: python3 is not on PATH — this tester proves nothing without it"
  echo "== RESULT: 0 passed, 1 failed =="
  exit 1
fi
for f in "$BJ" "$FRAG" "$STRINGS" "$STRINGS_ES" "$OH_GRADLE" "$OH_CONFIG" "$OH_SVC"; do
  if [ ! -f "$f" ]; then
    echo "  FAIL: missing file $f — the tree is not what this tester was written against"
    echo "== RESULT: 0 passed, 1 failed =="
    exit 1
  fi
done
if [ ! -d "$DRAWABLE" ]; then
  echo "  FAIL: missing $DRAWABLE — cannot prove any icon is real"
  echo "== RESULT: 0 passed, 1 failed =="
  exit 1
fi

# The resolver, reimplemented from the data exactly as build.gradle derives it.
# If this and the Gradle ever disagree the Gradle is the engine and this is
# wrong — T5 below pins the Gradle actually does it.
RESOLVER=$(cat <<'PYEOF'
import json, re, sys
d = json.load(open(sys.argv[1]))
oh = d['onehand']

def key(t):
    return 'action:' + re.sub(r'^action:', '', t or '')

# A tile carrying a nested 'tiles' array is a FOLDER: it has no target of its
# own, and what can be opened from it is the entries one level down. Reading
# only the top level is how the left handle's AI Claude sector stopped
# resolving the day that tile moved inside B-LLM — Sections.AggTile.destinations
# makes the same substitution on the Kotlin side.
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

tiles = {}
for s in d['ui']['sections']:
    lists = [v for k, v in s.items() if k.startswith('tiles_') and isinstance(v, list)]
    for g in (s.get('tile_groups') or []):
        if isinstance(g.get('tiles'), list): lists.append(g['tiles'])
    for L in lists:
        for t in leaves(L):
            if not isinstance(t, dict): continue
            if t.get('separator') or not t.get('target') or not t.get('label'): continue
            tiles.setdefault(key(t['target']), (t['label'], t.get('icon') or ''))

sections = {s['id']: (s.get('label') or s['id'], s.get('icon') or '')
            for s in d['ui']['sections'] if s.get('id')}

def resolve(t):
    """label, icon for an in-app target — or None. NOTHING is skipped: the
    sector this tester exists for is an intent:// one, and skipping it is what
    the previous tester did."""
    hit = tiles.get(key(t))
    if hit: return hit
    bare = re.sub(r'^action:', '', t or '')
    if bare.startswith('section:'):
        return sections.get(bare[len('section:'):])
    return None
PYEOF
)
q() { python3 -c "$RESOLVER
$1" "$BJ" 2>&1; }

echo "== T1: every action: sector on both handles resolves to a label AND an icon =="
# The whole bug in one assertion. Nothing is skipped by scheme. On the code as
# it shipped for #111/#127 the left top_outer sector resolved to nothing and
# drew as its own URL; that is a FAIL here, not a `continue`.
UNRESOLVED=$(q "
out = []
for h in oh['handles']:
    for slot, v in h['gestures'].items():
        v = str(v)
        if not v.startswith('action:'): continue
        r = resolve(v)
        if r is None:
            out.append(h['id'] + '.' + slot + ' resolves to NOTHING (would draw as a raw target)')
        elif not r[0].strip():
            out.append(h['id'] + '.' + slot + ' resolves to an empty label')
        elif not r[1].strip():
            out.append(h['id'] + '.' + slot + ' resolves to label ' + r[0] + ' with NO icon')
print(' ;; '.join(out))")
if [ -z "$UNRESOLVED" ]; then
  ok "every action: sector resolves to a non-empty label and a non-empty icon"
else
  bad "sectors that do not resolve: $UNRESOLVED"
fi

echo "== T2: the two sectors he reported, by what they RESOLVE to =="
# Not "is the string in build.json" — it has been since 2026-09-10 and the menu
# was wrong anyway. This reads the resolved pair.
CLAUDE=$(q "
r = resolve(next(h for h in oh['handles'] if h['id']=='left')['gestures']['top_outer'])
print('%s|%s' % r if r else 'NONE|NONE')")
[ "${CLAUDE%%|*}" = "AI Claude" ] \
  && ok "left top_outer resolves to the label 'AI Claude' (got: ${CLAUDE%%|*})" \
  || bad "left top_outer resolves to '${CLAUDE%%|*}', not 'AI Claude' — this is the reported symptom"
CLAUDE_ICON="${CLAUDE##*|}"
[ -n "$CLAUDE_ICON" ] && [ "$CLAUDE_ICON" != "NONE" ] \
  && ok "left top_outer resolves to an icon ($CLAUDE_ICON)" \
  || bad "left top_outer resolves to no icon"

# Drive's icon must be the DRIVE SECTION's own, not merely "some icon" and not
# the ic_link_tile fallback: there is no cloud-drive application to take one
# from, so the section definition is the only honest source.
DRIVE=$(q "
r = resolve(next(h for h in oh['handles'] if h['id']=='left')['gestures']['down'])
sec = sections['drive']
print('%s|%s|%s' % (r[0] if r else 'NONE', r[1] if r else 'NONE', sec[1]))")
D_LABEL="$(echo "$DRIVE" | cut -d'|' -f1)"
D_ICON="$(echo "$DRIVE" | cut -d'|' -f2)"
D_SEC="$(echo "$DRIVE" | cut -d'|' -f3)"
[ "$D_LABEL" = "Drive" ] \
  && ok "left down resolves to the label 'Drive'" \
  || bad "left down resolves to '$D_LABEL', not 'Drive'"
if [ -n "$D_ICON" ] && [ "$D_ICON" = "$D_SEC" ] && [ "$D_ICON" != "NONE" ]; then
  ok "left down's icon IS the Drive section's own icon ($D_ICON), not a generic fallback"
else
  bad "left down's icon is '$D_ICON' but the drive section declares '$D_SEC' — the reported symptom"
fi
# An icon NAME that names no drawable is an icon that does not draw.
for n in "$CLAUDE_ICON" "$D_ICON"; do
  if ls "$DRAWABLE/$n".* >/dev/null 2>&1; then
    ok "drawable $n exists in res/drawable"
  else
    bad "resolved icon '$n' has no drawable in res/drawable — it would fall back to a generic glyph"
  fi
done

echo "== T3: the prefix cannot make one destination into two =="
# `action:section:drive` and `section:drive` are the same place. This is the
# half of #111/#127 that was a string problem rather than a missing entry.
SAME=$(q "print('yes' if resolve('action:section:drive') == resolve('section:drive')
                 and resolve('section:drive') is not None else 'no')")
[ "$SAME" = "yes" ] \
  && ok "action:section:drive and section:drive resolve identically" \
  || bad "the action: prefix still changes what a target resolves to"

echo "== T4: NEGATIVE — an unknown target must resolve to nothing, not to something =="
# The defect was a resolver that ANSWERED for input it did not know (index 0,
# i.e. "None"). A resolver that returns a value for this input is broken even
# if every positive case above passes.
NEG=$(q "
probes = ['action:section:definitely-not-a-section',
          'action:intent://resolve?domain=not_a_real_bot_xyz#Intent;end',
          'action:open_nothing_at_all']
print(' ;; '.join(p for p in probes if resolve(p) is not None))")
if [ -z "$NEG" ]; then
  ok "unknown targets resolve to nothing — the resolver discriminates, it does not default"
else
  bad "these unknown targets resolved to something: $NEG"
fi

echo "== T5: the ENGINE does this, not just this tester =="
# Comments stripped first. The fix's own comments quote `coerceAtLeast(0)` and
# the words they replaced, so an unstripped grep reads the explanation and
# reports the bug either present or absent at random.
strip() { python3 - "$1" <<'PYEOF'
import re, sys, io
s = io.open(sys.argv[1], encoding='utf-8').read()
s = re.sub(r'/\*.*?\*/', ' ', s, flags=re.S)   # block + KDoc
s = re.sub(r'//[^\n]*', ' ', s)                # line comments
sys.stdout.write(s)
PYEOF
}
G="$(strip "$OH_GRADLE")"
C="$(strip "$OH_CONFIG")"
S="$(strip "$OH_SVC")"
F="$(strip "$FRAG")"

case "$G" in
  *action_catalogue*) ok "build.gradle derives an action_catalogue" ;;
  *) bad "build.gradle no longer derives action_catalogue — the sector list is unresolved again" ;;
esac
case "$G" in
  *GradleException*) ok "build.gradle FAILS THE BUILD on a sector that resolves to nothing" ;;
  *) bad "build.gradle no longer fails on an unresolvable sector — it can ship unnamed again" ;;
esac
case "$C" in
  *action_catalogue*) ok "OneHandConfig reads the derived action_catalogue" ;;
  *) bad "OneHandConfig no longer reads action_catalogue — labels/icons will not reach the menu" ;;
esac
# The overlay must give an AppTarget an icon. Before the fix its icon expression
# was `(action as? GestureAction.OpenApp)` — an app-only cast — so an action:
# sector could not have one at any data.
case "$S" in
  *iconForAction*) ok "the overlay resolves an icon for every action, not only for app: ones" ;;
  *) bad "the overlay has no iconForAction — action: sectors draw with no icon again" ;;
esac
case "$S" in
  *catalogueFor*) ok "the overlay matches the catalogue on the normalised key" ;;
  *) bad "the overlay no longer uses the normalised lookup — raw-string matching is back" ;;
esac
# The silent index-0 fallback: the single line that turned a miss into "None"
# over a working slot. Scoped to the SECTOR lookup by the expression it matches
# on — a bare search for coerceAtLeast also hits addHomeSwipePicker, which
# picks over a closed in-code vocabulary and is not this bug.
SECTOR_COERCE=$(printf '%s' "$F" | python3 -c "
import re, sys
s = re.sub(r'\\s+', ' ', sys.stdin.read())
m = re.search(r'indexOfFirst \\{ it\\.action\\?\\.serialize\\(\\) == current\\?\\.serialize\\(\\) \\}(.{0,40})', s)
if m is None: print('MISSING')
elif 'coerceAtLeast' in m.group(1): print('COERCED')
else: print('OK')")
case "$SECTOR_COERCE" in
  OK)      ok "the sector selection no longer coerces an unmatched target to index 0" ;;
  COERCED) bad "the sector selection still coerces to index 0 — a miss silently reads as None" ;;
  *)       bad "could not find the sector selection expression at all — this tester is stale, treat as unproven" ;;
esac
case "$F" in
  *onehand_sector_unresolved*) ok "an unmatched sector gets a visible 'unresolved' row instead" ;;
  *) bad "no visible fallback row — an unmatched sector has nowhere honest to render" ;;
esac

echo "== T6: the new row is localisable (his phone is Spanish) =="
for f in "$STRINGS" "$STRINGS_ES"; do
  if grep -qF 'name="onehand_sector_unresolved"' "$f"; then
    ok "onehand_sector_unresolved present in $(basename "$(dirname "$f")")"
  else
    bad "onehand_sector_unresolved missing from $(basename "$(dirname "$f")") — English on a Spanish phone (#221/#226)"
  fi
done

echo "== T7: the edge target and the Cloud > Apps tile target stay byte-identical =="
# Kept from the neighbouring tester deliberately. It does NOT prove the menu
# works — that is T1/T2's job — but the two must not drift apart either.
SAME2=$(q "
tile = [t['target'] for s in d['ui']['sections'] for g in s.get('tile_groups', [])
        for t in leaves(g.get('tiles', [])) if 'Cloud_agent_claude_bot' in (t.get('target') or '')]
left = next(h for h in oh['handles'] if h['id']=='left')['gestures']['top_outer']
print('yes' if len(tile) == 1 and left == 'action:' + tile[0] else 'no')")
[ "$SAME2" = "yes" ] \
  && ok "the edge gesture is 'action:' + the Cloud > Apps tile target, byte for byte" \
  || bad "the edge gesture and the Cloud > Apps tile target have drifted apart"

echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
