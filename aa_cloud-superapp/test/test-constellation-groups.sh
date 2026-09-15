#!/usr/bin/env bash
# #343 — Constellation tabs are DATA: libs / libs-t-ml / libs-l-ml beside apps.
#
#   T1  every fleet entry sits in a declared group, in the declared order
#   T2  the real on-device ML modules moved OUT of plain libs
#   T3  reference rows live outside `apps`, installable:false, with no package
#   T4  the updater cannot reach a reference row
#   T5  ConstellationFragment names no group: no id or label literal, no kind
#       partition, no hardcoded tab list
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
FLEET="$APP/data/constellation-fleet.json"
BUILD="$APP/build.json"
LIBS="$ROOT/ab_cloud-libs-shared/libs"
PAGE="$LIBS/appstore/src/main/java/com/diegonmarcos/superapp/appstore/ConstellationFragment.kt"
ENGINE="$LIBS/updater/src/main/java/com/diegonmarcos/superapp/updater/Fleet.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
# Every input must exist up front, so no assertion below can answer from a
# missing file.
for f in "$FLEET" "$BUILD" "$PAGE" "$ENGINE"; do
  [ -f "$f" ] || { echo "ERROR: missing $f" >&2; exit 2; }
done

echo "== T1: every entry is in a declared group, in declared order =="
jq -e '[.groups[].id] as $declared | all((.apps[], .catalogue[]); .group as $g | $declared | index($g) != null)' "$FLEET" >/dev/null \
  && ok "every apps + catalogue entry names a declared group" \
  || bad "an entry names a group constellation.groups does not declare"
jq -n -e --slurpfile fleet "$FLEET" --slurpfile build "$BUILD" \
  '($fleet[0].groups | map(.id)) == ($build[0].constellation.groups | map(.id))' >/dev/null \
  && ok "fleet groups keep build.json's declared order" \
  || bad "fleet groups differ from build.json::constellation.groups — rerun data/regen.sh"
# members is emitted beside the per-entry stamp; the two must be one fact.
jq -e '. as $root | all($root.groups[]; .id as $id
         | (.members | sort) == ([($root.apps[], $root.catalogue[]) | select(.group == $id) | .id] | sort))' "$FLEET" >/dev/null \
  && ok "each group's members match the per-entry group stamps" \
  || bad "a group's members disagree with its entries' group field"
for g in libs libs-t-ml libs-l-ml; do
  jq -e --arg g "$g" '(.groups | map(.id) | index($g)) != null and any(.groups[]; .id == $g and (.members | length) > 0)' "$FLEET" >/dev/null \
    && ok "group $g is declared and has rows" \
    || bad "group $g is missing or empty"
done

echo "== T2: real ML modules moved out of plain libs =="
# Derived from the modules themselves: a library whose build.gradle pulls in an
# on-device ML runtime is an ML module, whatever group data says. The floor of 2
# (translate-mlkit, voice-vosk today) stops this passing on an empty match.
ML_MODULES="$(grep -lE "^[[:space:]]*implementation[[:space:]]+['\"](com\.google\.mlkit|com\.alphacephei|org\.tensorflow|com\.microsoft\.onnxruntime|com\.google\.mediapipe|com\.google\.ai\.edge)" "$LIBS"/*/build.gradle | xargs -n1 dirname | xargs -n1 basename)"
ML_COUNT="$(printf '%s\n' "$ML_MODULES" | grep -c .)"
[ "$ML_COUNT" -ge 2 ] \
  && ok "found $ML_COUNT library module(s) with an ML runtime dependency" \
  || bad "expected at least 2 ML runtime modules under libs/, found $ML_COUNT"
for mod in $ML_MODULES; do
  jq -e --arg id "lib-$mod" 'any(.apps[]; .id == $id and (.group == "libs-t-ml" or .group == "libs-l-ml"))' "$FLEET" >/dev/null \
    && ok "lib-$mod is in an ML group" \
    || bad "lib-$mod carries an ML runtime but is not in libs-t-ml or libs-l-ml"
done
jq -e 'all(.apps[] | select(.id == "lib-translate-mlkit" or .id == "lib-voice-vosk"); .package != "" and .asset != "") and ([.apps[] | select(.id == "lib-translate-mlkit" or .id == "lib-voice-vosk")] | length == 2)' "$FLEET" >/dev/null \
  && ok "moved modules keep their real installable entries" \
  || bad "a moved ML module lost its package or asset"

echo "== T3: reference rows are outside apps and not installable =="
jq -n -e --slurpfile fleet "$FLEET" --slurpfile build "$BUILD" \
  '($fleet[0].catalogue | length) > 0 and ($fleet[0].catalogue | length) == ($build[0].constellation.catalogue | length)' >/dev/null \
  && ok "every build.json catalogue row is emitted" \
  || bad "catalogue rows missing from the fleet — rerun data/regen.sh"
jq -e 'all(.catalogue[]; .installable == false and (has("package") | not))' "$FLEET" >/dev/null \
  && ok "every catalogue row is installable:false with no package" \
  || bad "a catalogue row claims to be installable or carries a package"
jq -e '[.catalogue[].id] as $references | all(.apps[]; .id as $id | $references | index($id) | not)' "$FLEET" >/dev/null \
  && ok "no reference row is inside apps" \
  || bad "a reference row leaked into apps, where Fleet.parse hands it to the updater"
jq -e 'all(.apps[]; (.package // "") != "")' "$FLEET" >/dev/null \
  && ok "every apps entry has a real package" \
  || bad "an apps entry has no package"

echo "== T4: the updater cannot reach a reference row =="
grep -qF 'optJSONArray("apps")' "$ENGINE" \
  && ok "Fleet.parse reads the apps array" \
  || bad "Fleet.parse no longer reads apps — re-check where reference rows can reach"
grep -qF '"catalogue"' "$ENGINE" \
  && bad "Fleet.kt reads the catalogue, so reference rows can reach the updater" \
  || ok "Fleet.kt never reads the catalogue"
grep -qF 'if (app.blocked) return@filter false' "$ENGINE" \
  && ok "installAllLocked drops blocked entries before checking status" \
  || bad "installAllLocked no longer skips blocked entries"
grep -qF 'blocked = !entry.optBoolean("installable", false)' "$PAGE" \
  && ok "the page builds reference rows blocked unless the data says installable" \
  || bad "reference rows are not built blocked"
grep -qF 'updateAll(ctx, "the whole fleet", fleet)' "$PAGE" \
  && ok "Update all runs over Fleet.parse's list, which holds no reference row" \
  || bad "Update all is no longer built from the parsed fleet"

echo "== T5: ConstellationFragment names no group =="
for literal in $(jq -r '.groups[] | .id, .label' "$FLEET"); do
  grep -nF "\"$literal\"" "$PAGE" \
    && bad "ConstellationFragment hardcodes group literal \"$literal\"" \
    || ok "no \"$literal\" literal"
done
grep -nF 'listOf("Apps"' "$PAGE" && bad "hardcoded tab list is back" || ok "no hardcoded tab list"
grep -nF 'filter { it.kind' "$PAGE" && bad "a kind partition is back" || ok "no kind partition"
grep -qF 'fleetObjects("groups")' "$PAGE" \
  && ok "tabs are built from the fleet's declared groups" \
  || bad "tabs are not built from the declared groups"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
