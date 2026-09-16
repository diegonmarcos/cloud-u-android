#!/usr/bin/env bash
# #384 — Configs ▸ Launcher ▸ Modes, the Scale control.
#
# Two things the owner asked for, and one thing that must NOT come back:
#
#   (a) a "Restore default" action that puts Scale back to the SHIPPED value
#   (b) a slider you can read: a mark per step, 0 to 10, the handle on a step
#       and never between two — "then it don't have middle range numbers and we
#       can see in which level is"
#   (c) #349 must stay fixed. The Modes page already had a defect where a tap
#       reloaded the page and came back on another tab; a new button on the
#       same page is exactly how that comes back.
#
# THE TRAP THIS FILE EXISTS FOR is (b) meeting the range. #337 declared Scale
# 1..10; the owner asked for 0..10. SystemDisplay.factor read
# `coerceIn(1, 10)` — the declared range written a SECOND time, in Kotlin. Widen
# the declaration alone and step 0 silently folds onto step 1: two tick marks,
# one pixel-identical result, and a slider whose marks lie. So the assertions
# below check that the clamp is DERIVED from the declaration, not that it holds
# some particular pair of numbers.
#
# #408 PUT THE SAME TRAP ON THE OTHER AXIS. The owner asked for two one-tap
# sizes — "Default" = 3 and "Normal" = 6 — and for a fresh install to land on 3.
# Written as a `default: 3` beside a button list that also says 3, that is one
# number in two places: retune either and the size the app installs with stops
# being the size the button called "Default" produces, silently. So the shipped
# value is DERIVED from the presets (the one flagged `shipped`) and there is no
# `default` key on this slider at all. T7 below is what keeps it that way.
#
#   T1  the declaration says 0..10, ticked, and its shipped value is in range
#   T2  nothing restates the range: the clamp reads the declared bounds
#   T3  the bottom step is a real, distinct step
#   T4  the Scale row reads ticks AND the presets off that one declaration
#   T5  the row draws the platform's own per-step marks and a live readout
#   T6  the restore path does not reload the page or recreate the Activity
#   T7  #408: the shipped size is 3, the buttons are "Default" 3 / "Normal" 6,
#       and neither number is written a second time in Kotlin
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp"
BUILD="$APP/build.json"
CFG="$KT/settings/LauncherConfigFragment.kt"
PREFS="$KT/settings/LauncherSettingsPrefs.kt"
DISPLAY="$KT/system/SystemDisplay.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

# Every input must exist up front, so no assertion below can answer from a
# missing file instead of from the code.
for f in "$BUILD" "$CFG" "$PREFS" "$DISPLAY"; do
  [ -f "$f" ] || { echo "ERROR: missing $f" >&2; exit 2; }
done

# The body of a named Kotlin function, signature to the matching close at the
# same indent — so "does the restore path call X" cannot be answered by some
# unrelated function calling X elsewhere in a 1200-line file.
body() { # body <file> <indent-spaces> <signature-fragment>
  awk -v sig="$3" -v ind="$2" '
    index($0, sig) { f=1 }
    f { print }
    f && $0 == ind "}" { exit }
  ' "$1"
}

echo "== T1: the declaration is the range the owner asked for =="
SCALE='.ui.launcher_settings.scale'
jq -e "$SCALE"'.min == 0 and '"$SCALE"'.max == 10' "$BUILD" >/dev/null \
  && ok "T1: Scale is declared 0..10 (#384, widening #337's 1..10)" \
  || bad "T1: Scale is not declared 0..10 — the owner asked for 0 to 10"
jq -e "$SCALE"'.ticks == true' "$BUILD" >/dev/null \
  && ok "T1: the row is declared ticked" \
  || bad "T1: scale.ticks is not true — the tick bar is opt-in per slider and this is the slider that wants it"
# The shipped value is now the preset flagged `shipped`, so THAT is what has to
# sit on the ladder. A preset off the ladder is a button that moves the handle
# somewhere the handle cannot be.
jq -e "$SCALE"' | [.presets[] | select(.shipped)] | length == 1' "$BUILD" >/dev/null \
  && ok "T1: exactly one preset is flagged shipped" \
  || bad "T1: the scale presets do not name exactly one shipped value — zero leaves the first-run size undeclared, two make it depend on array order"
# `as $lo` first: inside `.presets[]` a bare `.min` would be read off the PRESET
# object, where it is null, and every value would compare as out of range.
jq -e "$SCALE"' | .min as $lo | .max as $hi | [.presets[] | select(.value < $lo or .value > $hi)] | length == 0' "$BUILD" >/dev/null \
  && ok "T1: every declared preset sits inside the declared range" \
  || bad "T1: a preset is outside the range — its button would move the handle somewhere the handle cannot go"
jq -e "$SCALE"' | has("default") | not' "$BUILD" >/dev/null \
  && ok "T1: the row carries presets INSTEAD of a default, not as well as one" \
  || bad "T1: the scale record has both a default and presets — that is the shipped size written twice, which is the #384 defect on a new axis"

echo "== T2: the range is declared once =="
# The literal form, named so the failure message can say what to do about it.
# COMMENT LINES ARE STRIPPED FIRST, and that is not a loophole: the KDoc above
# factor() quotes the old `coerceIn(1, 10)` to explain what went wrong, which is
# exactly the kind of sentence that must stay writable. An assertion that a
# CODE shape is absent has to read code, or the honest fix for the next person
# is to delete the explanation.
if sed -E 's,//.*,,' "$DISPLAY" | grep -vE '^\s*(\*|/\*)' \
     | grep -qE 'coerceIn\([0-9]+, *[0-9]+\)'; then
  bad "T2: SystemDisplay clamps to literal numbers — that is the declared range written twice (#170), and it silently ate step 0"
else
  ok "T2: SystemDisplay holds no literal scale range"
fi
FACTOR="$(body "$DISPLAY" "    " "fun factor(scale: Int)")"
if printf '%s' "$FACTOR" | grep -q 'Config.scale' &&
   printf '%s' "$FACTOR" | grep -q 'coerceIn('; then
  ok "T2: factor clamps to the DECLARED bounds, so widening build.json widens the band"
else
  bad "T2: factor does not clamp to the declared bounds — a wider declaration would not reach the device"
fi

echo "== T3: the bottom step is a step, not a duplicate =="
# The END-TO-END version of T2, in numbers: read the formula's constants AND
# any surviving literal clamp out of the Kotlin, apply the clamp the way the
# code would, and check that the two lowest DECLARED steps still land on
# different factors. T2 says "the clamp is derived"; this says "and therefore
# the bottom tick mark does something", which is the fact the owner would see.
read -r BASE STEP <<<"$(grep -oE '[0-9]+\.[0-9]+ \+ [0-9]+\.[0-9]+ \* scale' "$DISPLAY" \
  | head -1 | awk '{print $1, $3}')"
MIN="$(jq -r "$SCALE"'.min' "$BUILD")"
# Empty when the clamp reads the declaration, which is the shipped shape.
CLAMP_MIN="$(sed -E 's,//.*,,' "$DISPLAY" | grep -vE '^\s*(\*|/\*)' \
  | grep -oE 'coerceIn\([0-9]+,' | head -1 | tr -dc '0-9')"
FLOOR="${CLAMP_MIN:-$MIN}"
if [ -n "${BASE:-}" ] && [ -n "${STEP:-}" ]; then
  LOW="$(python3 -c "print(round($BASE + $STEP * max($MIN, $FLOOR), 4))")"
  NEXT="$(python3 -c "print(round($BASE + $STEP * max($MIN + 1, $FLOOR), 4))")"
  if [ "$LOW" != "$NEXT" ]; then
    ok "T3: step $MIN reaches the device as factor $LOW and step $((MIN + 1)) as $NEXT — distinct"
  else
    bad "T3: step $MIN and step $((MIN + 1)) both reach the device as factor $LOW — two tick marks, one result, because the clamp floor is $FLOOR"
  fi
else
  bad "T3: could not read the scale formula out of SystemDisplay — T2's clamp check is guarding a shape that moved"
fi

echo "== T4: the Scale row reads one declaration =="
if grep -q 'ticks = sc.ticks' "$CFG"; then
  ok "T4: ticks come from the declared record"
else
  bad "T4: the Scale row does not pass sc.ticks"
fi
if grep -q 'presets = sc.presets' "$CFG"; then
  ok "T4: the one-tap size buttons are built from the DECLARED presets"
else
  bad "T4: the Scale row does not pass sc.presets — a button list typed into Kotlin outlives the declaration it copied"
fi
grep -q 'val presets: List<Preset>' "$PREFS" \
  && ok "T4: Slider carries presets, so they are parsed data and not a call-site opinion" \
  || bad "T4: Slider has no presets field"
grep -q 'optJSONArray("presets")' "$PREFS" \
  && ok "T4: the presets are parsed out of build.json" \
  || bad "T4: nothing parses the declared presets"
grep -q 'presets.firstOrNull { it.shipped }' "$PREFS" \
  && ok "T4: the first-run value is FOUND among the presets, never named a second time" \
  || bad "T4: Slider.default is not derived from the shipped preset — the install default and the button named after it can now disagree"
grep -q 'val ticks: Boolean' "$PREFS" \
  && ok "T4: Slider carries ticks, so it is parsed data and not a call-site opinion" \
  || bad "T4: Slider has no ticks field"
grep -q 'optBoolean("ticks"' "$PREFS" \
  && ok "T4: ticks is parsed out of build.json" \
  || bad "T4: nothing parses the declared ticks flag"

echo "== T5: the row is legible =="
ROW="$(body "$CFG" "    " "private fun sliderRow(")"
if [ -z "$ROW" ]; then
  bad "T5: sliderRow not found"
else
  printf '%s' "$ROW" | grep -q 'tickMark =' \
    && ok "T5: the marks are the platform's own tickMark — drawn once per step, from the SeekBar's own range" \
    || bad "T5: no tickMark — a hand-drawn tick bar would be a second copy of the range and would drift off the stops"
  printf '%s' "$ROW" | grep -q 'override fun onProgressChanged' \
    && printf '%s' "$ROW" | grep -q 'readout.text = p.toString()' \
    && ok "T5: the current step is shown and follows the finger, not the release" \
    || bad "T5: nothing shows the current step live — 'we can see in which level is' is the whole request"
  printf '%s' "$ROW" | grep -q 'preset.value.coerceIn(min, max)' \
    && ok "T5: a preset button moves THIS slider's handle, inside the declared bounds" \
    || bad "T5: the preset buttons do not move the handle"
  printf '%s' "$ROW" | grep -q 'for (preset in presets)' \
    && ok "T5: one button per declared preset, in declared order — a third size is a data edit" \
    || bad "T5: the buttons are not a loop over the declared presets, so adding one is a Kotlin change"
fi

echo "== T6: #349 does not come back through the new button =="
if [ -n "$ROW" ]; then
  for banned in rerenderPage notifyLauncherThemeChanged recreate; do
    if printf '%s' "$ROW" | grep -q "$banned"; then
      bad "T6: sliderRow reaches $banned — a tap on the Modes page reloads it and loses the tab (#349)"
    else
      ok "T6: sliderRow never reaches $banned"
    fi
  done
  # The positive half: the fix has to be that the row updates ITSELF. A button
  # that neither reloads nor repaints just leaves a stale handle on screen.
  printf '%s' "$ROW" | grep -q 'bar.progress = ' \
    && ok "T6: it repaints in place by setting progress on the bar it built" \
    || bad "T6: nothing updates the view in place, so the only way to show the restore is a reload"
fi
# A trailing lambda binds to the LAST parameter. sliderRow grew two parameters
# in #384 and its three call sites all pass their handler as a trailing lambda,
# which is the exact shape test-kotlin-lambda-arg-position.sh exists for.
if grep -qE '^ *onChange: \(Int\) -> Unit,$' "$CFG"; then
  ok "T6: onChange is still sliderRow's last parameter"
else
  bad "T6: sliderRow's last parameter is not onChange — every trailing-lambda call site is now aimed at the wrong one"
fi

echo "== T7: #408 — the shipped size is 3, and neither 3 nor 6 is written twice =="
while IFS=$'\t' read -r verdict detail; do
  [ "$verdict" = "OK" ] && ok "T7: $detail" || bad "T7: $detail"
done < <(python3 - "$BUILD" "$DISPLAY" <<'T7PY'
import json, re, sys

scale = json.load(open(sys.argv[1]))["ui"]["launcher_settings"]["scale"]
presets = scale.get("presets") or []
# The owner's own words, in quotes, in the ticket: "Default" vakues=3 and
# "Normal"= 6. Pinned as LABEL -> VALUE pairs because the PAIRING is the
# request: the confusing half of it — a size called "Default" that is not the
# phone's own size — is HIS decision, so undoing it has to be an edit to this
# line rather than a label quietly drifting away from its number.
WANT = [["Default", 3], ["Normal", 6]]
got = [[p.get("label"), p.get("value")] for p in presets]
if got == WANT:
    print("OK\tthe buttons are exactly the owner's \"Default\" (3) and \"Normal\" (6), in that order")
else:
    print("BAD\tthe buttons are %s — the ticket asks for %s, verbatim" % (got, WANT))

shipped = [p for p in presets if p.get("shipped")]
if len(shipped) == 1 and shipped[0].get("value") == 3:
    print("OK\ta fresh install starts at scale 3, and it starts there because the "
          "preset labelled %r carries shipped:true — not because a second key says 3"
          % shipped[0].get("label"))
else:
    print("BAD\tthe shipped preset is %s, not the 3 #408 asks every new install to land on"
          % ([p.get("value") for p in shipped] or "unset"))

# The factors, named rather than assumed — the owner is choosing by eye, and two
# buttons that reach the device as the same number are one button.
src = open(sys.argv[2]).read()
m = re.search(r"([0-9]+\.[0-9]+) \+ ([0-9]+\.[0-9]+) \* scale", src)
if not m:
    print("BAD\tcould not read the factor ladder out of SystemDisplay — the factors "
          "these steps map to cannot be stated, so this assertion measures nothing")
else:
    base, stepsz = float(m.group(1)), float(m.group(2))
    pairs = [(p["label"], p["value"], round(base + stepsz * p["value"], 4)) for p in presets]
    if len({f for _, _, f in pairs}) == len(pairs):
        print("OK\tthe steps reach the device as distinct factors: %s"
              % ", ".join("%s=%d -> %.2f" % t for t in pairs))
    else:
        print("BAD\tthe declared presets collapse onto one factor (%s) — two buttons, one result"
              % [t[2] for t in pairs])
T7PY
)
# The half no jq can reach: the numbers must not ALSO be sitting in the Kotlin
# that reads them. slider("scale") took a fallback default of 6 before #408 —
# the same 6 the "Normal" button now declares. Comments are stripped first, for
# the reason T2 gives.
for lit in 3 6; do
  HITS="$(sed -E 's,//.*,,' "$PREFS" | grep -vE '^\s*(\*|/\*)' \
          | grep -nE 'slider\("scale"[^)]*'"$lit" || true)"
  if [ -n "$HITS" ]; then
    bad "T7: LauncherSettingsPrefs hands slider(\"scale\") the literal $lit — build.json already declares it, and the copy nothing reads is the one that goes stale ($HITS)"
  else
    ok "T7: no literal $lit is wired into the scale slider in Kotlin"
  fi
done

echo
echo "PASS=$PASS FAIL=$FAIL"
echo "scale-control tester finished — #384 range + #408 presets."
[ "$FAIL" -eq 0 ]
