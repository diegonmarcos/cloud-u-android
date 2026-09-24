#!/usr/bin/env bash
# Tester: one tap on a tile is one dispatch.
#
# WHAT WAS OBSERVED (one session's trace on the owner's phone, 0.1.1-dev,
# f030922f): 149 duplicate onTileClicked events, each pair 7–49ms apart, EVERY
# one of them `action:constellation`.
#
#   11:08:06.915  onTileClicked tileId=action:constellation
#   11:08:06.940  onTileClicked tileId=action:constellation
#
# The control is what makes it a bug and not a device quirk: `page:`, `extapp:`
# and `app:` tiles were tapped many times in the same trace and not one of them
# duplicated.
#
# THE CAUSE — not a listener bound twice, but ONE TAP TAKING TWO ROUTES INTO
# THE SAME ROUTER, and the second route re-entering through the CLICK entry
# point:
#
#   onTileClicked("action:constellation")            ← log line 1, haptic 1
#     → dispatchHomeAction("constellation")
#       → the page that declares this target opens a SCREEN, so the section
#         grid must become the back-stack base first (or Back leaves Configs
#         for wherever the tap came from)
#       → LauncherNavController.openSectionPage("config", "constellation")
#         → goSection(...) establishes the base   ← the 7–49ms
#         → the page declares `action:constellation`, so dispatch it
#         → host.dispatchTarget(...) == onTileClicked(...)  ← log line 2, haptic 2
#           → dispatchHomeAction again; currentSection is now "config", so the
#             re-home is a no-op and the fragment finally commits
#
# The round trip is deliberate and the navigation needs it. What was wrong is
# that the RETURN LEG went in through onTileClicked, so one tap paid the click
# bookkeeping twice: two trace lines, two Haptics.tap pulses, two
# RecentCloudTiles.recordOpen calls (skewing the Recently-Used smart folder)
# and two App-Tabs recordings. The fragment itself commits once — the first
# pass returns before reaching the commit — so this was never a double screen.
#
# WHY `action:` AND ONLY `action:`: it is the one verb routed through
# dispatchHomeAction, and dispatchHomeAction is the only place that re-homes a
# target into its declaring section. `page:` enters openSectionPage directly,
# `extapp:` and URI targets never enter the nav controller at all. Within
# `action:`, only a target whose page OPENS A SCREEN takes the trip —
# Sections.screenPageForTarget excludes `is_action` pages, which do something
# and return. T5 reads that set out of build.json rather than naming a tile.
#
# NOT FIXED WITH A DEBOUNCE, and T6 keeps it that way. Swallowing the second
# click inside 50ms would clean the log while the double dispatch carried on,
# and would break a genuine fast double tap elsewhere.
#
# Invariants:
#   T1  the click bookkeeping (log, haptic, drawer, recents, tabs) is in
#       onTileClicked and NOWHERE in the router
#   T2  the grammar `when` is in routeTarget, and onTileClicked delegates to it
#   T3  the nav controller's page-action hop uses routeTarget — the return leg
#       completes the tap instead of starting a second one
#   T4  the round trip itself is intact: the section base is still established
#       before the action dispatch (removing it is the OTHER regression, the
#       one where Back leaves Configs for Home)
#   T5  DATA-DRIVEN: build.json still declares at least one target that takes
#       the trip, so T3 is guarding something real
#   T6  no time-based click suppression anywhere in the dispatch path
#   T7  the tile RENDERERS leave the per-tap bookkeeping to onTileClicked. T1-T6
#       all read ShellActivity.kt, which is where the duplicate was — and that
#       is exactly how the Cloud > Apps > Data Apps row went on firing its own
#       Haptics.tap before calling in, two Vibrator pulses per tap on every tile
#       in that row, with this file green over it (#167)
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

SHELL_KT="$APP/app/src/main/java/com/diegonmarcos/superapp/ShellActivity.kt"
NAV="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/LauncherNavController.kt"
for f in "$SHELL_KT" "$NAV"; do
  [ -f "$f" ] || { echo "  FAIL: $f not found"; exit 1; }
done

# The KDoc on both fixed sites quotes `dispatchTarget`, `onTileClicked` and the
# duplicate log lines to explain the bug, so every assertion reads code only.
strip() {
  python3 - "$1" <<'STRIP'
import io, re, sys
s = io.open(sys.argv[1], encoding="utf-8").read()
s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
s = re.sub(r"^[ \t]*//.*$", "", s, flags=re.M)
s = re.sub(r"[ \t]//.*$", "", s, flags=re.M)
sys.stdout.write(s)
STRIP
}
SHELL_CODE="$(mktemp)"; NAV_CODE="$(mktemp)"
trap 'rm -f "$SHELL_CODE" "$NAV_CODE"' EXIT
strip "$SHELL_KT" > "$SHELL_CODE"
strip "$NAV"      > "$NAV_CODE"

# The two function bodies, read off the Kotlin.
fn() { awk "/^    override fun $2\\(tileId: String\\) \\{/{f=1} f{print} f&&/^    \\}\$/{exit}" "$1"; }
CLICK="$(fn "$SHELL_CODE" onTileClicked)"
ROUTE="$(fn "$SHELL_CODE" routeTarget)"

echo "== the click bookkeeping happens once, where a click arrives =="
if [ -z "$CLICK" ] || [ -z "$ROUTE" ]; then
  bad "T1/T2: could not read onTileClicked and routeTarget out of ShellActivity — one of them was renamed and every assertion below is checking nothing"
else
  ok "T1: both onTileClicked and routeTarget are present to compare"
  # Each of these is a per-TAP side effect. Firing any of them on the return
  # leg is what made one tap look like two.
  for effect in 'Trace.i(TAG, "onTileClicked' 'Haptics.tap(' 'RecentCloudTiles.recordOpen(' 'recordTarget('; do
    inc=$(printf '%s\n' "$CLICK" | grep -cF "$effect")
    inr=$(printf '%s\n' "$ROUTE" | grep -cF "$effect")
    if [ "$inc" -ge 1 ] && [ "$inr" -eq 0 ]; then
      ok "T1: '$effect' is billed by the click, not by the router"
    elif [ "$inc" -eq 0 ]; then
      bad "T1: '$effect' is gone from onTileClicked — the click stopped being counted at all"
    else
      bad "T1: '$effect' is in routeTarget, so every re-entry mid-tap bills it again — this is the defect"
    fi
  done
fi

echo "== the router is the router, and the click delegates to it =="
# The grammar verbs must live in routeTarget. If they drift back into
# onTileClicked the split has been undone and the round trip re-enters the
# click path again.
verbs=$(printf '%s\n' "$ROUTE" | grep -o 'tileId\.startsWith("[^"]*")' | sed 's/.*("//;s/")//' | sort -u)
n_verbs=$(printf '%s' "$verbs" | grep -c . || true)
if [ "$n_verbs" -ge 4 ]; then
  ok "T2: routeTarget owns the grammar ($(printf '%s' "$verbs" | tr '\n' ' '))"
else
  bad "T2: routeTarget branches on only $n_verbs verbs — the grammar is not there"
fi
# A routing ARM (`startsWith(...) ->`), not any mention of startsWith: the
# click legitimately tests the prefix once, to decide whether the target is the
# kind the App-Tabs shelf records. That is bookkeeping. A `when` arm is routing.
if printf '%s\n' "$CLICK" | grep -qE 'startsWith\("[^"]*"\)[^-]*->' \
   || printf '%s\n' "$CLICK" | grep -q 'when {'; then
  bad "T2: onTileClicked routes as well as counting — a re-entry mid-tap lands back on the click path, which is the defect"
else
  ok "T2: onTileClicked counts the tap and delegates the routing"
fi
if printf '%s\n' "$CLICK" | grep -q '^        routeTarget(tileId)$'; then
  ok "T2: onTileClicked hands the target to the router"
else
  bad "T2: onTileClicked no longer calls routeTarget — the click goes nowhere"
fi

echo "== the return leg completes the tap instead of starting a second one =="
if grep -qF 'if (pageAction.isNotBlank()) { host.routeTarget(pageAction); return }' "$NAV_CODE"; then
  ok "T3: openSectionPage's page-action hop routes without re-counting a click"
else
  bad "T3: the page-action hop is missing, or back on the click entry point (dispatchTarget) — that round trip is what logged the same tileId twice, 7–49ms apart"
fi
# dispatchTarget stays for its real callers (an App-Tabs shelf pick, a tab whose
# page is an action) — those ARE taps. What must not come back is the nav
# controller using it to finish one.
if grep -q 'host\.dispatchTarget(' "$NAV_CODE"; then
  bad "T3: the nav controller calls dispatchTarget again — it is billing its own re-entry as a user click"
else
  ok "T3: the nav controller never re-enters through the click entry point"
fi

echo "== the round trip that makes the fix necessary is still there =="
# Deleting the re-home would also stop the duplicate — and would put the
# Constellation screen back over Home with Back leaving for Home. The fix is
# to stop double-COUNTING the trip, not to stop taking it.
if awk '/private fun dispatchHomeAction/,/^        val anchor/' "$SHELL_CODE" \
     | grep -q 'screenPageForTarget'; then
  ok "T4: an action page is still re-homed into the section that declares it"
else
  bad "T4: the re-home is gone — Back from such a page leaves for whatever launched it"
fi
base_ln=$(grep -n 'goSection(sectionId, Sections.byId(sectionId)' "$NAV_CODE" | head -1 | cut -d: -f1)
act_ln=$(grep -n 'host.routeTarget(pageAction)' "$NAV_CODE" | head -1 | cut -d: -f1)
if [ -n "$base_ln" ] && [ -n "$act_ln" ] && [ "$base_ln" -lt "$act_ln" ]; then
  ok "T4: the base (line $base_ln) is still established before the dispatch (line $act_ln)"
else
  bad "T4: base/dispatch order lost (base=$base_ln dispatch=$act_ln)"
fi

echo "== the targets that take the trip, read out of build.json =="
# Naming a page here would let this file go on passing after the declaration
# that produces the hop had moved. The set is derived: EVERY page that declares
# an `action` is dispatched by LauncherNavController.openSectionPage through
# host.routeTarget — the return leg T3 guards — whether or not it opens a
# screen (Configs ▸ Update All, KDE Connect, Animations, WireGuard's
# section:wg all walk it from the Configs grid).
#
# #563 retired the only SCREEN-opening action page: Configs ▸ Constellation
# (action:constellation) became Configs ▸ Store, an ordinary tabbed page. This
# check used to count only that screen-opening subset, so it went red with
# "T3 is guarding a path nothing walks" — which was no longer true, because
# the is_action pages still walk it. The subset is still printed, as
# information, so a future screen-opening action page is visible here.
TRIPPERS="$(python3 - "$APP/build.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
for sec in d["ui"]["sections"]:
    for key in ("pages", "pages_hidden"):
        for pg in sec.get(key) or []:
            a = pg.get("action", "")
            if a:
                kind = "fires" if pg.get("is_action", False) or not a.startswith("action:") else "opens a screen"
                print("%s/%s -> %s (%s)" % (sec.get("id"), pg.get("id"), a, kind))
PY
)"
if [ -n "$TRIPPERS" ]; then
  ok "T5: $(printf '%s' "$TRIPPERS" | grep -c .) declared page action(s) take the openSectionPage -> routeTarget hop, so T3 guards something real:"
  printf '%s\n' "$TRIPPERS" | sed 's/^/        /'
else
  bad "T5: no page declares an action any more — T3 is guarding a path nothing walks. Either the declaration moved, or this check needs retiring on purpose."
fi

echo "== the second delivery was removed, not hidden =="
# A time window would make the log read clean while both dispatches carried on,
# and would eat a genuine fast double tap somewhere else.
if printf '%s\n%s\n' "$CLICK" "$ROUTE" \
     | grep -qE 'elapsedRealtime|uptimeMillis|currentTimeMillis|lastClick|DEBOUNCE|debounce'; then
  bad "T6: a time-based guard is in the dispatch path — that hides a double dispatch instead of removing it, and breaks a real fast double tap"
else
  ok "T6: no debounce — the duplicate is gone because the second delivery is gone"
fi

echo "== and the surfaces that CALL it do not bill the tap themselves =="
# T1–T6 all read ShellActivity.kt. That is where the duplicate WAS, and it is
# why the tile RENDERERS could go on doing their own half of the bookkeeping
# with this file green over them — #167 found the Cloud ▸ Apps ▸ Data Apps row
# firing Haptics.tap in the cell's own listener AND again inside onTileClicked,
# two direct Vibrator pulses for one finger on every tile in that row, Drive
# included.
#
# onTileClicked IS the bookkeeping. A surface that has one to hand calls it and
# adds nothing. The one exception is a cell that opens a POPUP instead of
# dispatching — a folder reaches no dispatcher, so it has to buzz for itself,
# and it is required to say so on the line above.
RENDERERS="$(grep -rl 'onTileClicked(' "$APP/app/src/main/java/com/diegonmarcos/superapp/launcher" \
             2>/dev/null | sort)"
if [ -z "$RENDERERS" ]; then
  bad "T7: found no tile renderer calling onTileClicked — this check is asserting nothing"
else
  # The rule, stated exactly: inside one click listener, a haptic that fires
  # UNCONDITIONALLY (before the listener's first branch) alongside a dispatch is
  # the double — that tap is going to be billed again inside onTileClicked. A
  # haptic that sits inside a branch is the popup case and is fine, which is
  # also why the test cannot just ask "does this file mention Haptics".
  DOUBLE="$(python3 - $RENDERERS <<'PY'
import re, sys

def blocks(src, opener="setOnClickListener {"):
    out, i = [], src.find(opener)
    while i != -1:
        depth, j = 0, i + len(opener) - 1
        while j < len(src):
            if src[j] == "{": depth += 1
            elif src[j] == "}":
                depth -= 1
                if depth == 0: break
            j += 1
        out.append(src[i:j + 1])
        i = src.find(opener, j)
    return out

for path in sys.argv[1:]:
    src = open(path).read()
    for b in blocks(src):
        if "onTileClicked(" not in b:
            continue
        branch = re.search(r"\b(if|when)\s*\(", b)
        prelude = b[:branch.start()] if branch else b
        if re.search(r"Haptics\.\w+\(", prelude):
            print(path.rsplit("/", 1)[-1])
            break
PY
)"
  if [ -n "$DOUBLE" ]; then
    bad "T7: fires its own haptic unconditionally and then calls onTileClicked — one tap, two Vibrator pulses: $(printf '%s' "$DOUBLE" | tr '\n' ' ')"
  else
    ok "T7: $(printf '%s\n' "$RENDERERS" | wc -l | tr -d ' ') tile renderer(s) leave the per-tap bookkeeping to onTileClicked"
  fi
fi

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
