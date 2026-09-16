#!/usr/bin/env bash
# #407 — the launcher clock must clear the camera punch-hole AT EVERY SCALE STEP.
#
# WHAT THE OWNER SAW: "when i changed the scale to a reduced one the home screen
# watch time date below the samsung camera got hidden behind the camera, overflow
# into it, how to avoid this when we change scales?"
#
# WHY IT HAPPENED, AND WHY A MARGIN IS THE WRONG ANSWER. This app OWNS the camera
# row on purpose: ShellActivity sets LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES so
# the launcher status strip draws INTO the cutout instead of being letterboxed
# below it, and activity_main.xml's DrawerLayout carries no fitsSystemWindows so
# nothing pads that away. Both are deliberate and both must survive this fix.
# The strip therefore starts at window y=0 and everything in it is positioned by
# dp. SystemDisplay.applyScale writes `wm density`, so every dp becomes FEWER
# pixels at a reduced Scale step while the camera hole stays the same physical
# pixels — the clock rides up under the lens. A clearance tuned to look right at
# one step is wrong at the other ten and on every other cutout geometry.
#
# THE ONLY NUMBER THAT IS TRUE AT ALL ELEVEN STEPS is the cutout's own inset:
# WindowInsetsCompat.Type.displayCutout() is reported in raw pixels off the real
# cutout, and unlike systemBars() it is still dispatched while the status bar is
# hidden — which in launcher mode it always is.
#
#   T1  the SHORT_EDGES opt-in is still there (the fix must not revert it)
#   T2  the clock's reserve is driven by the cutout inset, from a real listener
#   T3  the reserve is unconditional — pets were never clearance, they were luck
#   T4  THE MODEL: the reserve is read OUT OF THE KOTLIN and evaluated over every
#       declared scale step against a sweep of cutout heights and densities. The
#       dp-only reserve this replaced is evaluated beside it, so the check is
#       demonstrably capable of failing.
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp"
BUILD="$APP/build.json"
STRIP="$KT/launcher/LauncherStatusStripView.kt"
SHELL_ACT="$KT/ShellActivity.kt"
DISPLAY="$KT/system/SystemDisplay.kt"
LAYOUT="$APP/app/src/main/res/layout/activity_main.xml"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

for f in "$BUILD" "$STRIP" "$SHELL_ACT" "$DISPLAY" "$LAYOUT"; do
  [ -f "$f" ] || { echo "ERROR: missing $f" >&2; exit 2; }
done

# Kotlin with its comments removed. Every assertion below about what the CODE
# does reads this, never the raw file: the KDoc in these files quotes the very
# shapes being asserted absent, and an assertion a comment can satisfy is an
# assertion that makes deleting the explanation the cheapest way to stay green.
code() { sed -E 's,//.*,,' "$1" | grep -vE '^\s*(\*|/\*)'; }
# NEVER `grep -q` on the end of a code() pipeline: -q exits at the first match,
# the sed upstream takes SIGPIPE, and `set -o pipefail` turns the pipeline's
# status into 141 — a FOUND shape reported as a missing one, silently, and only
# for matches that land before EOF. These greps read to the end and throw the
# output away instead.
has()  { code "$1" | grep -E "$2" >/dev/null; }

echo "== T1: the app still draws INTO the cutout on purpose =="
if has "$SHELL_ACT" 'LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES'; then
  ok "T1: SHORT_EDGES is still set — the strip's top band still owns the camera row"
else
  bad "T1: SHORT_EDGES is gone. That is the letterbox this app deliberately left behind (the strip's band is meant to flank the punch-hole); #407 asked for the clock to clear the camera, NOT for the launcher to stop reaching it"
fi
if grep -q 'fitsSystemWindows' "$LAYOUT"; then
  bad "T1: activity_main.xml declares fitsSystemWindows again — that pads the cutout away for the whole shell and undoes the same opt-in from the other side"
else
  ok "T1: the shell layout still carries no fitsSystemWindows"
fi

echo "== T2: the clock's clearance comes from the cutout, through a live listener =="
if has "$STRIP" 'WindowInsetsCompat\.Type\.displayCutout\(\)'; then
  ok "T2: the strip reads displayCutout() insets"
else
  bad "T2: nothing in the strip reads displayCutout() — the clock's offset can only be dp, which is the bug"
fi
if has "$STRIP" 'setOnApplyWindowInsetsListener'; then
  ok "T2: it reads them from an insets listener, so it re-answers when the window changes"
else
  bad "T2: no insets listener — a one-shot read at construction is stale the moment the cutout is re-dispatched"
fi
# The listener must hand the insets on. Consuming them here would starve the
# toolbar island dispatched after it and ShellActivity's own shell listener.
if has "$STRIP" 'consumeSystemWindowInsets|WindowInsetsCompat\.CONSUMED'; then
  bad "T2: the strip CONSUMES the insets — every sibling dispatched after it loses them"
else
  ok "T2: the strip does not consume the insets it reads"
fi

echo "== T3: the clearance is unconditional =="
# The pet spacer was `if (petsEnabled)`. It was never clearance — it was 22dp of
# luck that a phone with Animal animations off did not have at all.
if has "$STRIP" 'if \(petsEnabled\) *cent(er|re)Col\.addView'; then
  bad "T3: the centre column's reserve is behind the pets flag again — turn Animal animations off and the clock has no clearance at all"
else
  ok "T3: the centre column's reserve is not gated on the pets flag"
fi

echo "== T4: the model — every declared step, swept over cutouts and densities =="
SCALE='.ui.launcher_settings.scale'
MIN="$(jq -r "$SCALE"'.min' "$BUILD")"
MAX="$(jq -r "$SCALE"'.max' "$BUILD")"
PETDP="$(jq -r '.status_pets.px' "$BUILD")"
# The factor ladder, read out of SystemDisplay rather than restated here.
read -r BASE STEP <<<"$(grep -oE '[0-9]+\.[0-9]+ \+ [0-9]+\.[0-9]+ \* scale' "$DISPLAY" \
  | head -1 | awk '{print $1, $3}')"
# The reserve expression, read out of the Kotlin. Not a copy of it: if someone
# drops cutoutTopPx from that line, this is what stops being satisfiable.
RESERVE="$(code "$STRIP" | grep -A1 -E 'private fun cent(er|re)TopReservePx' \
  | tail -n +2 | tr '\n' ' ' | sed -E 's/^\s+//; s/\s+/ /g; s/ $//')"
PADDP="$(code "$STRIP" | grep -oE 'innerRowTopPadPx = \([0-9]+ \* resources' \
  | grep -oE '[0-9]+ \*' | tr -dc '0-9')"
if [ -z "${BASE:-}" ] || [ -z "${STEP:-}" ] || [ -z "$RESERVE" ] || [ -z "$PADDP" ]; then
  bad "T4: could not read the ladder ($BASE/$STEP), the reserve expression ('$RESERVE') or the row pad ($PADDP) out of the source — the shapes T2 guards have moved and this model is measuring nothing"
else
  while IFS=$'\t' read -r verdict detail; do
    [ "$verdict" = "OK" ] && ok "T4: $detail" || bad "T4: $detail"
  done < <(BASE="$BASE" STEP="$STEP" MIN="$MIN" MAX="$MAX" PETDP="$PETDP" \
           PADDP="$PADDP" RESERVE="$RESERVE" python3 - <<'PY'
import os, re, sys

base, step = float(os.environ["BASE"]), float(os.environ["STEP"])
lo, hi = int(os.environ["MIN"]), int(os.environ["MAX"])
pet_dp, pad_dp = int(os.environ["PETDP"]), int(os.environ["PADDP"])
src = os.environ["RESERVE"]

# Kotlin -> python, by NAMED rule and then fail-closed. A translator that
# tolerates what it does not recognise would quietly evaluate some other
# expression than the one that ships.
expr = src.replace("maxOf(", "max(")
expr = re.sub(r"if \((\w+)\) (\w+) else (\w+)", r"(\2 if \1 else \3)", expr)
residue = re.sub(r"\b(max|if|else|petsEnabled|petPx|cutoutTopPx|innerRowTopPadPx)\b", "", expr)
residue = re.sub(r"[0-9+\-*/(), ]", "", residue)
if residue:
    print("BAD\tthe reserve expression uses %r, which this model cannot evaluate — "
          "refusing to answer from a translation it does not understand "
          "(expression: %s)" % (residue, src))
    sys.exit()
if "cutoutTopPx" not in expr:
    print("BAD\tthe clock's reserve does not mention cutoutTopPx at all — it is back "
          "to being a dp that shrinks with `wm density` while the camera does not "
          "(expression: %s)" % src)
    sys.exit()

# A sweep, not one device: the point of #407 is that no single geometry may be
# assumed. Densities cover ldpi..xxxhdpi, cutouts cover a shallow corner notch
# through a tall pill.
DENSITIES = (1.0, 1.5, 2.0, 2.625, 3.0, 3.5, 4.0)
CUTOUTS = tuple(range(0, 201, 10))

def clock_top(scale, density, cutout):
    """Pixels between the top of the screen and the top of the clock."""
    factor = base + step * scale
    px = density * factor                      # dp -> px at this Scale step
    env = {"max": max, "petsEnabled": True,
           "petPx": int(pet_dp * px),
           "innerRowTopPadPx": int(pad_dp * px),
           "cutoutTopPx": cutout}
    return env["innerRowTopPadPx"] + eval(expr, {"__builtins__": {}}, env)

worst = None
for scale in range(lo, hi + 1):
    for density in DENSITIES:
        for cutout in CUTOUTS:
            slack = clock_top(scale, density, cutout) - cutout
            if worst is None or slack < worst[0]:
                worst = (slack, scale, density, cutout)
if worst[0] >= 0:
    print("OK\tthe clock clears the cutout at every step %d..%d, over %d densities "
          "x %d cutout heights — tightest case %+dpx at step %d, density %s, "
          "cutout %dpx" % (lo, hi, len(DENSITIES), len(CUTOUTS), worst[0],
                           worst[1], worst[2], worst[3]))
else:
    print("BAD\tthe clock lands %dpx INSIDE the cutout at step %d, density %s, "
          "cutout %dpx — that is the camera overflowing the watch again"
          % (-worst[0], worst[1], worst[2], worst[3]))

# The half that proves this model can go red: the clearance that WAS there —
# the row pad plus the pet row, all of it dp — measured the same way.
def old_clock_top(scale, density):
    factor = base + step * scale
    px = density * factor
    return int(pad_dp * px) + int(pet_dp * px)

broke = [(s, d, c) for s in range(lo, hi + 1) for d in DENSITIES for c in CUTOUTS
         if old_clock_top(s, d) < c]
if broke:
    print("OK\tthe dp-only clearance this replaced fails %d of the same cases "
          "(first: step %d, density %s, cutout %dpx) — the model above is not "
          "vacuously true" % (len(broke), broke[0][0], broke[0][1], broke[0][2]))
else:
    print("BAD\tthe dp-only clearance passes every swept case too, so this model "
          "cannot tell the fix from the bug and proves nothing")
PY
)
fi

echo
echo "PASS=$PASS FAIL=$FAIL"
echo "#407 cutout-vs-clock tester finished."
[ "$FAIL" -eq 0 ]
