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
#      declared `extapp:cloud-drive` (icon ic_p_sol_cloud at the time; #404 replaced
#      it with ic_drive, see T6); the sector is spelled
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
FRAG="$APP/app/src/main/java/com/diegonmarcos/superapp/configs/OneHandFragment.kt"
OH_GRADLE="$LIBS/launcher-onehand/build.gradle"
OH_SVC="$LIBS/launcher-onehand/src/main/java/com/diegonmarcos/superapp/onehand/OneHandAccessibilityService.kt"
OH_ACT="$LIBS/launcher-onehand/src/main/java/com/diegonmarcos/superapp/onehand/GestureAction.kt"
DRAWABLE="$APP/app/src/main/res/drawable"

# FAIL CLOSED. Four testers in this repository once passed only because the tool
# they called was absent and the call failed open.
if ! command -v python3 >/dev/null 2>&1; then
  echo "  FAIL: python3 is not on PATH — this tester proves nothing without it"
  echo "== RESULT: 0 passed, 1 failed =="
  exit 1
fi
for f in "$BJ" "$OH_GRADLE" "$OH_SVC" "$OH_ACT" "$FRAG"; do
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

# Comments are stripped with a real parser, NOT a sed line-range.
#
# `sed -e '/\/\*/,/\*\//d'` looks right and silently eats code: in a sed range
# addr1,addr2 the end pattern is only tested on LATER lines, so a single-line
# `/** … */` kdoc opens a range that cannot close on its own line and keeps
# deleting until the NEXT `*/` — in OneHandFragment.kt that is line 459 through
# the next kdoc ~90 lines down, taking the very call this tester asserts with it.
# The grep then fails and blames the source. Both of this tester's Kotlin targets
# are full of one-line kdocs, so this is not hypothetical; it cost a false FAIL
# while writing it. python3 is already a hard dependency checked above.
strip_kt() {
  python3 - "$1" <<'PYEOF'
import re, sys
src = open(sys.argv[1]).read()
src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)   # block + kdoc, non-greedy
src = re.sub(r'//[^\n]*', '', src)                # line comments
sys.stdout.write(src)
PYEOF
}

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

SVC=$(strip_kt "$OH_SVC" | tr "\n" " " | tr -s " ")

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

echo "== T4: the CONFIGS PICKER falls back too — it is the page he was looking at =="

# Configs > Launcher > One-Hand builds its own option rows and had its own copy
# of the same one-source lookup: `pm.getApplicationIcon(it.pkg)` for the ★
# favourites, which is where cloud-drive lives. Fixing only the overlay would
# leave the surface the report actually names still drawing Drive blank.
# Whitespace-collapsed, because the fallback sits on its own continuation line:
# a per-line grep for "does this end at getOrNull()" calls the FIXED code broken.
FRAGC=$(strip_kt "$FRAG" | tr '\n' ' ' | tr -s ' ')

# 2026-09-19: the PM lookup goes through AppIconCache (Knox prices each raw
# getApplicationIcon in SECONDS; the cache is the one file allowed to pay it).
# Same order, same fallback: cache(=PackageManager, once) first, tile second.
echo "$FRAGC" | grep -qF 'AppIconCache.load(ctx, it.pkg) ?: declaredAppIcon(ctx, cfg, "app:${it.pkg}")' \
  && ok "the picker's favourite rows fall back from PackageManager to the declared drawable" \
  || bad "the picker's favourite rows still take PackageManager as their only icon source"

# The order is the assertion, not an accident: PackageManager first means an
# INSTALLED app keeps showing its own launcher icon rather than a tile stand-in.
echo "$FRAGC" | grep -qF 'declaredAppIcon(ctx, cfg, "app:${it.pkg}") ?: AppIconCache' \
  && bad "the picker consults the tile BEFORE PackageManager — an installed app loses its own icon" \
  || ok "and PackageManager is consulted first, so an installed app keeps its own icon"

echo "$FRAGC" | grep -q 'fun declaredAppIcon' \
  && ok "declaredAppIcon() exists on the Configs page" \
  || bad "declaredAppIcon() is missing from the Configs page"

echo "$FRAGC" | grep -A3 'fun declaredAppIcon' | grep -q '"action:" + target.removePrefix("action:")' \
  && ok "and it matches on the normalised key, like every other lookup" \
  || bad "declaredAppIcon() compares raw strings"

echo "== T5: the ENGINE does this, not just this tester =="

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

echo "== T6: the sector wears CLOUD DRIVE OWN icon, and still points at the app =="

# #404. #284 fixed the PLUMBING — the sector resolves to whatever glyph the tile
# declares — and the tile declared ic_p_sol_cloud, the generic palette cloud the
# in-app Drive PAGE wore and that four other build.json entries also use. Right
# machinery, wrong picture, and "Drive shows the page icon" is the half of the
# owner report that survived #284. Three assertions, none of which a rename or a
# revert can slip past:
#
#   6a  the sector still LAUNCHES THE APP. A revert to the in-app page would
#       spell the target `action:section:drive` or `page:drive/...`; the whole
#       point of 302/304 is that no such destination exists any more.
#   6b  the glyph is Drive's ALONE. A name shared with another entry is how the
#       page icon got here in the first place, and it is invisible in review.
#   6c  the glyph is genuinely the Drive APK's, not something redrawn to look
#       like it: every path in the SuperApp's copy must appear verbatim in
#       ac_cloud-drive's own launcher foreground.
DRIVE_APP="$APP/../ac_cloud-drive"
FG="$DRIVE_APP/app/src/main/res/drawable/ic_launcher_foreground.xml"

SECTOR=$(q "
left = next(h for h in oh['handles'] if h['id']=='left')['gestures']
print(left.get('down') or '')")

case "$SECTOR" in
  app:*clouddrive*)
    ok "6a: the left down sector launches the Drive package (got: $SECTOR)" ;;
  *section:*|*page:*)
    bad "6a: the left down sector reverted to an in-app page target ($SECTOR) — that page was deleted by 302/304" ;;
  *)
    bad "6a: the left down sector is $SECTOR — it must launch the extracted Drive package" ;;
esac

ICON=$(q "
left = next(h for h in oh['handles'] if h['id']=='left')['gestures']
hit = tiles.get(key(left.get('down') or ''))
print((hit or {}).get('icon') or '')")

if [ -z "$ICON" ]; then
  bad "6b: the Drive sector resolves to no icon at all"
else
  SHARED=$(q "
import json
name = '$ICON'
seen = []
def walk(node, path):
    if isinstance(node, dict):
        if node.get('icon') == name:
            seen.append(node.get('id') or node.get('label') or path)
        for k, v in node.items():
            walk(v, path + '/' + str(k))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            walk(v, path + '/%d' % i)
walk(d, '')
print(len(seen))
print(','.join(str(x) for x in seen))")
  COUNT=$(printf '%s\n' "$SHARED" | sed -n 1p)
  WHO=$(printf '%s\n' "$SHARED" | sed -n 2p)
  [ "$COUNT" = "1" ] \
    && ok "6b: $ICON is declared by exactly one entry — it is Drive's own glyph, not a shared palette mark" \
    || bad "6b: $ICON is declared by $COUNT entries ($WHO) — a shared glyph, which is how the in-app page icon reached this sector"

  if [ ! -f "$FG" ]; then
    bad "6c: $FG is missing — cannot prove the glyph came from the Drive APK"
  else
    PROV=$(python3 - "$DRAWABLE/$ICON.xml" "$FG" <<'PYEOF2'
import re, sys
def paths(f):
    return [p for p in re.findall(r'android:pathData="([^"]+)"', open(f).read())]
mine, theirs = paths(sys.argv[1]), set(paths(sys.argv[2]))
if not mine:
    print("NONE"); raise SystemExit
missing = [p for p in mine if p not in theirs]
print("OK" if not missing else "MISSING:%d/%d" % (len(missing), len(mine)))
PYEOF2
)
    case "$PROV" in
      OK)   ok "6c: every path in $ICON is copied verbatim from ac_cloud-drive's launcher foreground" ;;
      NONE) bad "6c: $ICON declares no paths — it draws nothing" ;;
      *)    bad "6c: $ICON does not match the Drive APK's launcher foreground ($PROV paths differ) — it is not that app's icon" ;;
    esac
  fi
fi

echo "== T7: a sector whose app is NOT installed must SAY so, never go quiet =="

# Drive is the one sector on this handle that can legitimately point at an
# absent package — it lives in its own APK now, and there is no in-app page left
# to fall back to. GestureAction.launch used to answer that with a bare `return`:
# menu animates, finger lifts, nothing happens, indistinguishable from a missed
# swipe. Silence is the defect; which visible answer is chosen is not asserted
# here, only that one exists and that it names the destination.
ACT=$(strip_kt "$OH_ACT" | tr '\n' ' ' | tr -s ' ')

echo "$ACT" | grep -q 'getLaunchIntentForPackage(pkg) ?: return' \
  && bad "launch() still returns silently when the package is absent — the swipe does nothing and says nothing" \
  || ok "launch() no longer swallows an absent package with a bare return"

echo "$ACT" | grep -q 'is not installed' \
  && ok "and it tells the user the app is not installed" \
  || bad "nothing in GestureAction says an app is not installed — the failure is still invisible"

echo "$ACT" | grep -q 'fun perform(svc: AccessibilityService, label: String? = null)' \
  && ok "perform() carries the sector's own label, so the message can name Drive rather than a package id" \
  || bad "perform() takes no label — a not-installed message could only print the raw package id"

echo "$SVC" | grep -q 'perform(this, labelForAction(it))' \
  && ok "and the service passes the label it just drew on that sector" \
  || bad "the service calls perform() without the label it already resolved"


echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
