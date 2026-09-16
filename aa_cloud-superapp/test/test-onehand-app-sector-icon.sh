#!/usr/bin/env bash
# Tester: an `app:` edge-menu sector pointing at one of OUR apps keeps its icon
# when that app is not installed.
#
# WHY THIS EXISTS, AND WHY test-onehand-sector-resolution.sh DID NOT CATCH IT.
#
# "Drive renders with no icon" is the second half of #111/#127/#284. The first
# half (AI Claude) was fixed on 2026-09-11 by deriving an `action_catalogue` at
# build time, and the published APK proves it worked: left `top_outer` resolves
# to label 'AI Claude' + icon 'ic_ai_chat'. Drive did not come with it.
#
# Drive was EXTRACTED into its own APK (#302/#304). That rewrote its sector from
# an in-app `action:` target to an external `app:com.diegonmarcos.clouddrive`,
# which moved it out of the one branch that had just been given a declared-icon
# path and into the branch that still had none. Two separate holes opened:
#
#   1. BUILD TIME. The catalogue is keyed on the raw target. The Drive TILE is
#      declared `extapp:cloud-drive` (icon ic_p_sol_cloud); the sector is spelled
#      `app:com.diegonmarcos.clouddrive`. `action:`-stripping alone does not join
#      those, so the lookup missed and the published catalogue really does carry
#
#          {"label": "Drive", "icon": "", "target": "app:com.diegonmarcos.clouddrive"}
#
#      An empty icon, derived from a tile that HAS one. Nothing failed: the
#      handle loop skipped every non-`action:` sector, so it was never validated.
#
#   2. RUNTIME. iconForAction's OpenApp branch was `appIcon(pkg)` and nothing
#      else. getApplicationIcon throws for a package that is not installed, the
#      runCatching swallows it, and the sector draws its label with no glyph —
#      while labelForAction still finds the name in onehand.apps. Label present,
#      icon absent, is the exact signature the owner reported.
#
# The sibling tester cannot see either one: T1 iterates `action:` sectors only,
# and every assertion it makes about Drive is about the DECLARATION, which was
# correct throughout. This one asks what an `app:` sector resolves to, and what
# it resolves to WITHOUT PackageManager — the state that is true on a phone
# where the extracted APK is not installed.
#
# Static tester: no device, no build. build.json is read as DATA through python
# (its _doc prose names "Drive", "icon" and "extapp:" repeatedly, so a grep over
# it matches documentation instead of configuration). Kotlin and Gradle are
# checked with COMMENTS STRIPPED, because this fix's own comments quote the
# broken code they replace.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
LIBS="$APP/../ab_cloud-libs-shared/libs"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

BJ="$APP/build.json"
OH_GRADLE="$LIBS/launcher-onehand/build.gradle"
OH_SVC="$LIBS/launcher-onehand/src/main/java/com/diegonmarcos/superapp/onehand/OneHandAccessibilityService.kt"
DRAWABLE="$APP/app/src/main/res/drawable"

# FAIL CLOSED. Four testers in this repository once passed only because the tool
# they called was absent and the call failed open.
if ! command -v python3 >/dev/null 2>&1; then
  echo "  FAIL: python3 is not on PATH — this tester proves nothing without it"
  echo "== RESULT: 0 passed, 1 failed =="
  exit 1
fi
for f in "$BJ" "$OH_GRADLE" "$OH_SVC"; do
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

# The derivation, reimplemented from the data as build.gradle derives it.
#
# CRITICALLY, it models the engine AS IT CURRENTLY IS, not as it ought to be.
# Whether the Gradle canonicalises extapp:→app: is DETECTED from the Gradle and
# passed in as argv[2]; the resolver then keys the catalogue the way the shipped
# engine really keys it. Without that, this tester would resolve Drive through
# its own corrected copy of the logic and report a green icon while the engine
# emitted `icon: ''` — a tester that passes on the broken code, which is exactly
# how #111 and #127 were both closed on a defect that was still there.
RESOLVER=$(cat <<'PYEOF'
import json, re, sys
d = json.load(open(sys.argv[1]))
oh = d['onehand']
ENGINE_CANON = (len(sys.argv) > 2 and sys.argv[2] == 'canonical')

# ui.external_apps declares which package an `extapp:<id>` installs as. That is
# the ONLY honest way to know `extapp:cloud-drive` and `app:com.diegonmarcos.
# clouddrive` are one destination — anything else is a guess that goes stale the
# next time an app is extracted.
extpkg = {}
for e in d.get('ui', {}).get('external_apps', []) or []:
    if not isinstance(e, dict) or not e.get('id'):
        continue
    pkg = e.get('hub_package') or e.get('install_package')
    if pkg:
        extpkg[str(e['id'])] = str(pkg)

def key(t):
    bare = re.sub(r'^action:', '', t or '')
    if ENGINE_CANON and bare.startswith('extapp:'):
        i = re.split(r'[#/]', bare[len('extapp:'):])[0]
        if i in extpkg:
            bare = 'app:' + extpkg[i]
    return 'action:' + bare

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
    lists += [g.get('tiles', []) for g in (s.get('tile_groups') or [])]
    for tl in lists:
        for t in leaves(tl):
            if t.get('target') and t.get('label') and not t.get('separator'):
                tiles.setdefault(key(t['target']), {'label': t['label'], 'icon': t.get('icon') or ''})
PYEOF
)
# Ask the ENGINE whether it canonicalises, and resolve the way it does.
if grep -v '^\s*//' "$OH_GRADLE" | grep -q "startsWith('extapp:')"; then
  ENGINE_CANON=canonical
else
  ENGINE_CANON=raw
fi
q() { python3 -c "$RESOLVER
$1" "$BJ" "$ENGINE_CANON"; }

# Block comments FIRST. With the line-comment expressions first, sed deletes the
# closing `*/` line before the range ever sees it, the range never terminates,
# and everything to EOF disappears — a stripper that returns an empty file makes
# every grep below fail closed, which is survivable, but it would just as easily
# hide a real regression behind a confusing failure.
strip_kt() { sed -e '/\/\*/,/\*\//d' -e 's://.*::' "$1"; }

echo "== T1: the destination the owner reported, resolved as the engine resolves it =="

# The whole defect in one assertion. Drive's sector is spelled app:<pkg>; Drive's
# tile is spelled extapp:<id>. If the two do not canonicalise to one key, this
# icon is '' and the sector draws blank on a phone without the extracted APK.
DRIVE=$(q "
left = next(h for h in oh['handles'] if h['id']=='left')['gestures']
sect = [v for v in left.values() if (v or '').startswith('app:') and 'clouddrive' in v]
if len(sect) != 1:
    print('NO-SECTOR'); raise SystemExit
hit = tiles.get(key(sect[0]))
print('%s|%s' % ((hit or {}).get('label') or '', (hit or {}).get('icon') or ''))")

case "$DRIVE" in
  NO-SECTOR)
    bad "no left-handle app: sector targets the cloud-drive package — tester is stale" ;;
  *"|"*)
    D_LABEL="${DRIVE%%|*}"; D_ICON="${DRIVE##*|}"
    [ -n "$D_LABEL" ] \
      && ok "the left Drive sector resolves to a tile label (got: $D_LABEL)" \
      || bad "the left Drive sector resolves to NO tile — extapp: and app: never met"
    [ -n "$D_ICON" ] \
      && ok "the left Drive sector resolves to an icon (got: $D_ICON)" \
      || bad "the left Drive sector resolves to an EMPTY icon — this is the reported defect"
    if [ -n "$D_ICON" ]; then
      [ -f "$DRAWABLE/$D_ICON.xml" ] || [ -f "$DRAWABLE/$D_ICON.png" ] \
        && ok "drawable $D_ICON exists in res/drawable" \
        || bad "drawable $D_ICON is declared but absent — the icon would resolve to nothing"
    fi ;;
  *)
    bad "resolver produced unreadable output: $DRIVE" ;;
esac

echo "== T2: NEGATIVE — a third-party app: sector must NOT gain a phantom icon =="

# Brave/Instagram have no tile and PackageManager is their correct icon source.
# A fix that hands every app: sector some fallback glyph would be a new bug, so
# the absence of a catalogue entry for them is an assertion, not an oversight.
THIRD=$(q "
left = next(h for h in oh['handles'] if h['id']=='left')['gestures']
right = next(h for h in oh['handles'] if h['id']=='right')['gestures']
bad = []
for g in (left, right):
    for v in g.values():
        v = v or ''
        if not v.startswith('app:') or 'diegonmarcos' in v or v.startswith('app:cld.'):
            continue
        if tiles.get(key(v)):
            bad.append(v)
print(','.join(bad) if bad else 'none')")
[ "$THIRD" = "none" ] \
  && ok "no third-party app: sector resolves to a tile — PackageManager stays their icon source" \
  || bad "third-party sectors gained a tile icon they should not have: $THIRD"

echo "== T3: the RUNTIME falls back, so an uninstalled app still draws =="

SVC=$(strip_kt "$OH_SVC")

# The defect precisely: OpenApp had exactly one icon source and it was
# install-dependent. The fallback must exist AND must come second — an installed
# app has to keep showing its own launcher icon.
echo "$SVC" | grep -q 'is GestureAction.OpenApp -> appIcon(action.pkg) ?:' \
  && ok "the OpenApp branch falls back when PackageManager has nothing" \
  || bad "the OpenApp branch is still appIcon() alone — an uninstalled app draws no glyph"

echo "$SVC" | grep -qE 'OpenApp -> appIcon\(action\.pkg\) \?: declaredIcon' \
  && ok "and it falls back to the DECLARED drawable, not to another package lookup" \
  || bad "the OpenApp fallback does not reach the declared tile drawable"

echo "$SVC" | grep -q 'fun declaredIcon' \
  && ok "declaredIcon() exists to resolve a raw target against the catalogue" \
  || bad "declaredIcon() is missing"

echo "$SVC" | grep -A4 'fun declaredIcon' | grep -q '"action:" + target.removePrefix("action:")' \
  && ok "and it matches on the normalised key, so one destination stays one destination" \
  || bad "declaredIcon() compares raw strings — the same mistake that hid this three times"

echo "== T4: the ENGINE does this, not just this tester =="

GRADLE=$(sed -e 's://.*::' "$OH_GRADLE")

echo "$GRADLE" | grep -q 'external_apps' \
  && ok "build.gradle reads ui.external_apps for the extapp: → package mapping" \
  || bad "build.gradle does not read ui.external_apps — any mapping it uses is hardcoded"

echo "$GRADLE" | grep -q "startsWith('extapp:')" \
  && ok "build.gradle canonicalises extapp: targets" \
  || bad "build.gradle still keys the catalogue on the raw target"

# The mapping must be DERIVED from the manifest, never typed in beside it. A
# literal package name in the canonicaliser is the hardcoded list this repo's
# whole engine contract forbids, and it is how the data and the code drift.
echo "$GRADLE" | grep -q "ohExtPkg\[id\]" \
  && ok "the canonical package comes from that declared map, not from a literal" \
  || bad "the canonicalisation does not consult the declared map"

echo "$GRADLE" | grep -q "com.diegonmarcos.clouddrive" \
  && bad "build.gradle hardcodes the clouddrive package — derive it from external_apps" \
  || ok "no package name is hardcoded in the derivation"

echo "$GRADLE" | grep -q "t.startsWith('app:')" \
  && ok "the handle loop no longer skips every app: sector" \
  || bad "the handle loop still returns early on app: sectors — they are never given an icon"

echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
