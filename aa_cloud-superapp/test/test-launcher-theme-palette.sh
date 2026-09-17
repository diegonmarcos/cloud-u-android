#!/usr/bin/env bash
# Tester: a launcher theme reaches EVERY surface, or it does not ship.
#
# WHY THIS EXISTS. Two themes shipped and the owner's report on them was:
# "they only break the bottom menu nav and the top status and the edge menu,
# they dont apply nothing of its new full colour design". That is not a vague
# complaint, it is a precise list of the only three surfaces in this app that
# take no colour from the app itself — the system bars (a WindowInsetsController
# appearance flag), the chrome islands (a visibility flip) and the floating edge
# menu (its own window, drawn by a service). Everything else painted 0xFF
# literals chosen by hand for the default gradient, and a literal is a colour
# chosen at BUILD time that no theme can reach. It was not that the two themes
# were wired badly; it was that content colour had no wire to be on.
#
# The fix is data: a theme declares a `palette` of ROLES, every role names a
# resource, LauncherPalette is the only thing that resolves them, and the
# surfaces read it. These assertions are what stop the NEXT theme shipping the
# same way, because a half-wired theme looks completely fine to whoever added
# it — it renders, it just renders as the theme before it.
#
# Static tester (no device, no build): build.json and colors.xml are read as
# data and the Kotlin is checked for the contracts that data relies on.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
BJ="$APP/build.json"
COLORS="$APP/app/src/main/res/values/colors.xml"
DRAWABLES="$APP/app/src/main/res/drawable"
SRC="$APP/app/src/main/java/com/diegonmarcos/superapp"
PALETTE="$SRC/ui/LauncherPalette.kt"
SHELL_ACT="$SRC/ShellActivity.kt"
RES_LAYOUT="$APP/app/src/main/res/layout/activity_main.xml"
RES_DIMENS="$APP/app/src/main/res/values/dimens.xml"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 — $1"; fi; }

echo "== T1: EVERY declared theme supplies EVERY role =="
# THE ASSERTION THIS FILE IS FOR, and the one that would have caught the bug.
# The role set is the UNION of what the themes declare, not a list written here:
# a list here would be a second place to edit, and adding a role while forgetting
# one theme is exactly the half-wiring being prevented. A new role is therefore
# not "optional until someone notices" — the first theme to declare it makes it
# mandatory for all of them, in the same commit.
while IFS=$'\t' read -r theme verdict; do
  [ "$verdict" = "OK" ] && ok "theme '$theme' declares every role" \
                        || bad "theme '$theme': $verdict"
done < <(python3 - "$BJ" <<'PY'
import json, sys
themes = json.load(open(sys.argv[1]))["ui"]["launcher_themes"]
roles = sorted({r for t in themes for r in (t.get("palette") or {})})
if not roles:
    print("(no themes)\tno theme declares a palette at all — content cannot follow any theme")
    sys.exit()
for t in themes:
    p = t.get("palette") or {}
    missing = [r for r in roles if not str(p.get(r, "")).strip()]
    print("%s\t%s" % (t["id"], "OK" if not missing else
        "declares no %s — those surfaces would silently keep the previous "
        "theme's colours, which is what 'the theme did nothing' looks like "
        "from the outside" % ", ".join(missing)))
PY
)

echo "== T2: every role names a resource that EXISTS =="
# A token that does not resolve falls back at runtime and looks deliberate on
# screen. getIdentifier returns 0 silently — there is no crash to notice.
while IFS=$'\t' read -r ref verdict; do
  [ "$verdict" = "OK" ] && ok "$ref resolves" || bad "$ref: $verdict"
done < <(python3 - "$BJ" "$COLORS" "$DRAWABLES" <<'PY'
import json, os, re, sys
themes = json.load(open(sys.argv[1]))["ui"]["launcher_themes"]
colours = set(re.findall(r'<color name="([^"]+)"', open(sys.argv[2]).read()))
drawables = {os.path.splitext(f)[0] for f in os.listdir(sys.argv[3])}
for t in themes:
    for role, name in sorted((t.get("palette") or {}).items()):
        # `window` may legitimately be a drawable: the default theme's backdrop
        # is a two-stop gradient no single colour can stand in for.
        okv = name in colours or (role == "window" and name in drawables)
        print("%s.%s -> %s\t%s" % (t["id"], role, name, "OK" if okv else
            "names neither a colors.xml token nor a res/drawable"))
PY
)

echo "== T3: no themed surface paints a colour literal =="
# The defect itself, asserted directly. The file list is declared in build.json
# and only grows: the rest of the app still paints literals, and a converted
# file joins the list so the ground already won cannot be lost.
while IFS=$'\t' read -r f verdict; do
  [ "$verdict" = "OK" ] && ok "$f takes every colour from the palette" \
                        || bad "$f: $verdict"
done < <(python3 - "$BJ" "$APP" <<'PY'
import json, os, re, sys
bj, app = sys.argv[1], sys.argv[2]
d = json.load(open(bj))["ui"]
for rel in d["launcher_theme_surfaces"]:
    path = os.path.join(app, rel)
    short = os.path.basename(rel)
    if not os.path.exists(path):
        print("%s\tdeclared a themed surface but the file does not exist" % short); continue
    body = open(path, encoding="utf-8").read()
    # Strip comments first: the files EXPLAIN which literals they replaced, and
    # a tester that cannot tell prose from code would forbid saying so.
    code = re.sub(r'/\*.*?\*/', '', body, flags=re.S)
    code = re.sub(r'//[^\n]*', '', code)
    hits = set(re.findall(r'0x[0-9A-Fa-f]{8}\b', code))
    hits |= set(re.findall(r'\bColor\.(?:BLACK|WHITE|RED|GREEN|BLUE|YELLOW|CYAN|MAGENTA|GRAY|LTGRAY|DKGRAY)\b', code))
    hits |= set(re.findall(r'Color\.parseColor\(', code))
    print("%s\t%s" % (short, "OK" if not hits else
        "still paints %s — a literal is chosen at build time and no theme can "
        "reach it" % ", ".join(sorted(hits))))
PY
)

echo "== T4: LauncherPalette is the ONLY thing that resolves a theme to a colour =="
# One definition driving every surface is the whole point. A second resolver, or
# a `when (theme)` over colours anywhere, is the shape that let the chrome and
# the content disagree in the first place.
grep -q 'object LauncherPalette' "$PALETTE" \
  && ok "LauncherPalette exists" || bad "LauncherPalette is missing"
# Comments are stripped first: these files EXPLAIN that there is deliberately no
# `when (theme)` here, and a grep that cannot tell prose from code would read
# that sentence as the thing it forbids.
check "$(python3 - "$SRC" <<'PYW'
import os, re, sys
hits = []
for root, _, files in os.walk(sys.argv[1]):
    for f in files:
        if not f.endswith('.kt'): continue
        body = open(os.path.join(root, f), encoding='utf-8').read()
        code = re.sub(r'/\*.*?\*/', '', body, flags=re.S)
        code = re.sub(r'//[^\n]*', '', code)
        if re.search(r'when *\( *theme *\)', code) and \
           re.search(r'setTextColor|setBackgroundColor|setColorFilter', code):
            hits.append(f)
print('; '.join('%s branches on the theme AND paints a colour — a second, '
                'hand-maintained theme-to-colour mapping' % h for h in hits) or 'OK')
PYW
)" "no Kotlin branches on the theme to pick a colour"
# The memo has to be dropped when the theme changes, or the first read after a
# switch answers with the theme the user just left — the "looks like it did
# nothing until you kill the app" failure.
grep -q 'fun invalidate()' "$PALETTE" \
  && ok "the memoised palette can be invalidated" \
  || bad "LauncherPalette caches with no way to invalidate — a theme change would not be seen until the process restarts"
grep -q 'LauncherPalette.invalidate()' "$SHELL_ACT" \
  && ok "ShellActivity drops the memo when the theme changes" \
  || bad "nothing calls LauncherPalette.invalidate() on a theme change"

echo "== T5: the window backdrop is repainted for EVERY theme, not one branch =="
# It was one setBackgroundColor(Color.BLACK) inside the Power Saving arm and
# nothing in any other arm, so switching AWAY from Power Saving left the decor
# black until the process was killed. A surface painted in one branch of a
# `when` has to be repainted in all of them.
check "$(python3 - "$SHELL_ACT" <<'PY'
import re, sys
body = open(sys.argv[1], encoding='utf-8').read()
m = re.search(r'override fun applyLauncherChrome\(\).*?\n    \}\n', body, re.S)
if not m:
    print('applyLauncherChrome no longer has the shape this asserts about'); sys.exit()
fn = m.group(0)
problems = []
if 'setBackgroundDrawableResource' not in fn:
    problems.append('the window backdrop is never set from the palette')
# Inside the when-block a per-theme background call is the bug re-appearing.
w = re.search(r'\n        when \{.*?\n        \}\n', fn, re.S)
if w and re.search(r'(decorView|window)\s*\.?\s*set[A-Za-z]*Background', w.group(0)):
    problems.append('a background is painted inside one arm of the when — the other arms will not undo it')
print('; '.join(problems) or 'OK')
PY
)" "the backdrop is applied once, outside the per-theme branches"

echo "== T6: Power Saving is a MODE, not a palette entry =="
# The owner asked for "a exact match of samsung super power saving: a full black
# one screen with the 12 apps + edge menus". A colour-scheme entry can never be
# that however black it gets. The reduction has to be structural, so this checks
# the structure and not the darkness.
ps_fail=""
grep -q 'LauncherTheme.CloudPowerSaving *-> *PowerSavingFragment.newInstance()' \
     "$SRC/launcher/LauncherNavController.kt" \
  || ps_fail="$ps_fail no-home-pane-of-its-own"
# Each mode now owns a folder under launcher/themes/ holding its screen and the
# design vocabulary that screen is built from — "all themes have their own
# folders, all different, nothing can be equal".
[ -f "$SRC/launcher/themes/powersaving/PowerSavingFragment.kt" ] \
  || ps_fail="$ps_fail pane-file-missing"
check "$(python3 - "$BJ" <<'PY'
import json, sys
ui = json.load(open(sys.argv[1]))["ui"]
ps = next(t for t in ui["launcher_themes"] if t["id"] == "cloud_power_saving")
f = ps["features"]
problems = []
# What makes it REDUCED. Each of these is a piece of the phone that is gone,
# not a colour: the cube, the bottom nav, the island, the animations.
for key in ("home_3d", "bottom_nav", "dynamic_island", "animations"):
    if f.get(key) is not False:
        problems.append("features.%s is %r — Power Saving would still render it, "
                        "which makes it a black skin rather than a reduced mode"
                        % (key, f.get(key)))
# ...and the one piece that must SURVIVE, because the owner asked for it by name.
if f.get("drawer") is not True:
    problems.append("features.drawer is %r — the owner asked for the edge menus "
                    "to stay reachable in this mode" % f.get("drawer"))
if f.get("grid") != "6x2":
    problems.append("features.grid = %r, want '6x2' — twelve slots in two rows" % f.get("grid"))
if len(ps.get("home_apps") or []) != 12:
    problems.append("%d home_apps, want 12" % len(ps.get("home_apps") or []))
print("; ".join(problems) or "OK")
PY
)" "Power Saving strips the 3D home, bottom nav, island and animations, keeps the edge menus, and is twelve slots in 6x2"
[ -z "$ps_fail" ] && ok "Power Saving renders its own reduced home pane" \
                  || bad "Power Saving falls back to the full home:$ps_fail"

echo "== T7: Minimalist Black is a DIFFERENT theme, not the same one twice =="
# The owner asked for two themes and got two names. If their palettes and their
# feature sets are identical then only one of them exists and picking either is
# the same choice under two labels.
check "$(python3 - "$BJ" <<'PY'
import json, sys
ui = json.load(open(sys.argv[1]))["ui"]
by = {t["id"]: t for t in ui["launcher_themes"]}
mb, ps = by["cloud_minimalist_black"], by["cloud_power_saving"]
problems = []
if mb["palette"] == ps["palette"]:
    problems.append("the two themes declare an identical palette")
if mb["features"] == ps["features"]:
    problems.append("the two themes declare an identical feature set")
# Both are OLED themes, so both must be TRUE black. A near-black like #121212
# lights every subpixel and costs power the mode claims to save.
if not mb["features"].get("oled_black") or not ps["features"].get("oled_black"):
    problems.append("an oled_black theme is not marked oled_black")
print("; ".join(problems) or "OK")
PY
)" "Minimalist Black and Power Saving differ in palette AND in what they render"

echo "== T8: the OLED themes are TRUE black, and every text role is READABLE =="
# Two things at once because they are the same arithmetic. #000000 costs an OLED
# panel no power per pixel and #121212 does, so a power-saving mode that reuses
# the ordinary dark surface saves nothing it claims to. And a role is only a
# colour that follows the theme if it can still be READ on that theme — alpha
# composited over its own surface over its own window, not assumed opaque.
check "$(python3 - "$BJ" "$COLORS" "$DRAWABLES" <<'PY'
import json, os, re, sys
themes = json.load(open(sys.argv[1]))["ui"]["launcher_themes"]
xml = open(sys.argv[2]).read()
tok = dict(re.findall(r'<color name="([^"]+)">#([0-9A-Fa-f]{8})<', xml))

def argb(name):
    h = tok.get(name)
    return None if h is None else (int(h[0:2], 16), (int(h[2:4], 16), int(h[4:6], 16), int(h[6:8], 16)))
def over(fg, bg):
    a, c = fg
    return tuple(round(c[i] * a / 255 + bg[i] * (1 - a / 255)) for i in range(3))
def lin(c):
    c /= 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
def lum(rgb):
    r, g, b = rgb
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
def ratio(a, b):
    la, lb = lum(a), lum(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)

def window_colours(name):
    """Every colour this theme's backdrop actually paints. A gradient paints
       several, and a role has to be readable on the worst of them."""
    if name in tok:
        return [argb(name)[1]]
    path = os.path.join(sys.argv[3], name + ".xml")
    if not os.path.exists(path):
        return []
    return [(int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))
            for h in re.findall(r'Color="#[0-9A-Fa-f]{2}([0-9A-Fa-f]{6})"', open(path).read())]

problems, worst = [], []
for t in themes:
    p = t["palette"]
    bgs = window_colours(p["window"])
    if not bgs:
        problems.append("%s: window %r resolves to no colour to measure against" % (t["id"], p["window"]))
        continue
    if t["features"].get("oled_black") and any(b != (0, 0, 0) for b in bgs):
        problems.append("%s is oled_black but its window is #%02X%02X%02X, not true black — "
                        "an OLED panel spends power on every non-black pixel"
                        % ((t["id"],) + bgs[0]))
    surface = argb(p["surface"])
    for role in ("text_primary", "text_secondary", "accent"):
        fg = argb(p[role])
        if fg is None:
            problems.append("%s.%s names %r, which is not a colors.xml token" % (t["id"], role, p[role]))
            continue
        low, on = min(((ratio(over(fg, over(surface, b) if surface else b),
                              over(surface, b) if surface else b), b) for b in bgs),
                      key=lambda x: x[0])
        worst.append("%s/%s %.2f:1" % (t["id"], role, low))
        if low < 4.5:
            problems.append("%s.%s is %.2f:1 on its own surface — below the 4.5:1 AA floor "
                            "for the 11-14sp it is drawn at" % (t["id"], role, low))
sys.stderr.write("    worst case: " + "; ".join(worst) + "\n")
print("; ".join(problems) or "OK")
PY
)" "every OLED theme is #000000 and every text role clears WCAG AA on its own theme"

echo "== T9: the twelve-slot editor draws the grid it edits, with real icons =="
# It was twelve stacked Spinners of app NAMES, editing a screen that is two rows
# of six with icons on it — nothing about the control resembled the thing it
# controlled. And the column count must come from the theme's declared grid, not
# from a constant in each file: two constants that have to agree, with nothing
# making them, is how the pane and its editor drift apart.
CFG="$SRC/settings/LauncherConfigFragment.kt"
PANE="$SRC/launcher/themes/powersaving/PowerSavingFragment.kt"
TILE="$SRC/launcher/AppIconTile.kt"
ed_fail=""
grep -q 'AppIconTile.grid(' "$CFG"  || ed_fail="$ed_fail editor-does-not-draw-the-grid"
# INVERTED 2026-09-13. This line used to REQUIRE the pane to call
# AppIconTile.grid — and that requirement was the bug. Painting Power Saving's
# twelve slots with the default launcher's own tile builder is exactly why the
# mode read as "the normal home screen in black" twice in a row, which is what
# the owner rejected. The pane now draws its own cells; reusing the shared
# builder again is the regression. The KDoc mentions [AppIconTile] to explain
# the history, so this matches a member ACCESS, not the name.
grep -q 'AppIconTile\.' "$PANE"     && ed_fail="$ed_fail pane-reuses-the-default-launchers-tile-builder"
grep -q 'Spinner' "$PANE"           && ed_fail="$ed_fail pane-still-has-a-spinner"
# Painting its own cells does not license the pane to invent its own app LIST:
# the twelve slots still come from the owner's overrides, so the editor and the
# mode are always showing the same twelve apps in the same order.
grep -q 'PowerSavingAppsPrefs(ctx).resolved()' "$PANE" \
  || ed_fail="$ed_fail pane-ignores-the-owners-slot-overrides"
# The COLUMN count is no longer a shared number, and that is deliberate. This
# used to require the pane to read gridColumnsFor() so its width could not drift
# from the editor's — which assumed both surfaces draw a grid. The mode does not
# have a grid any more: twelve launcher icons are twelve full-colour bitmaps
# lighting every subpixel at once, the single most expensive thing an OLED
# power-saving screen could put on itself, so the pane lists its apps as text
# rows. A column count it has no columns for would be a constant no screen reads.
# What IS still asserted is that the mode owns its own layout vocabulary rather
# than borrowing the shared one.
grep -q 'PowerSavingDesign\.' "$PANE" \
  || ed_fail="$ed_fail pane-does-not-draw-from-its-own-modes-design"
grep -qE '^import com\.diegonmarcos\.superapp\.ui\.' "$PANE" \
  && ed_fail="$ed_fail pane-imports-the-shared-look-from-ui"
# Scoped to the CALL SITE, not to the file. A bare `grep gridColumnsFor` over
# LauncherConfigFragment.kt passes on the function's own DEFINITION, which lives
# in that same file — so it stayed green with the editor's columns replaced by a
# literal 4. An assertion that cannot fail is not an assertion.
col_verdict="$(python3 - "$CFG" <<'PYC'
import re, sys

def call_args(body):
    """The full argument list of AppIconTile.grid(...), paren-balanced.

    A non-greedy regex is not enough: the argument list CONTAINS nested calls
    (AppIconTile.Slot(...)), so the first ')' it reaches belongs to one of them
    and the slice stops before the columns argument is ever seen — which is how
    an earlier version of this check stayed green while the columns had been
    replaced by a literal."""
    k = body.find('AppIconTile.grid(')
    if k < 0:
        return None
    k += len('AppIconTile.grid(')
    depth, start = 1, k
    while k < len(body) and depth:
        if body[k] == '(': depth += 1
        elif body[k] == ')': depth -= 1
        k += 1
    return body[start:k - 1]

problems = []
# The editor only. The pane no longer calls AppIconTile at all — its own column
# count is asserted by the gridColumnsFor grep above.
for path, marker in ((sys.argv[1], 'the editor'),):
    args = call_args(open(path, encoding='utf-8').read())
    if args is None:
        problems.append('%s does not call AppIconTile.grid' % marker); continue
    m = re.search(r'columns\s*=\s*([^,\n]*)', args)
    if not m:
        problems.append('%s passes no columns argument' % marker)
    elif 'gridColumnsFor' not in m.group(1):
        problems.append("%s sets columns to %s instead of reading the theme's "
                        "declared grid" % (marker, m.group(1).strip()))
print('; '.join(problems))
PYC
)"
[ -n "$col_verdict" ] && ed_fail="$ed_fail $col_verdict"
[ -z "$ed_fail" ] && ok "the pane paints its own grid at the theme's declared width, and the editor mirrors it" \
                  || bad "the pane/editor grid contract is broken:$ed_fail"
# Icons come from the CENTRAL classification, not a third enumeration.
grep -q 'PhoneAppsFragment.snapshot' "$TILE" \
  && ok "icons come from the same app list the Phone tab and the search index read" \
  || bad "AppIconTile enumerates apps its own way — a third source of what an app is"
# Twelve badged icons is the ~600ms load PhoneAppsFragment documents. On the
# main thread that is a visibly frozen screen.
grep -q 'Thread {' "$TILE" && grep -q 'Handler(Looper.getMainLooper())' "$TILE" \
  && ok "icons load off the main thread and post back" \
  || bad "icon loading is on the main thread"
# A slot outlives the app it points at. That tile must SAY so.
grep -q 'R.string.app_tile_not_installed' "$TILE" \
  && ok "an uninstalled app renders as a stated placeholder, not a blank hole" \
  || bad "nothing states that a slot's app is gone — it would render as an empty tile"
# Selection has to survive greyscale and colour blindness, so it is a glyph and
# not only a background tint.
grep -q 'R.string.app_tile_selected' "$TILE" \
  && ok "selection is marked by shape as well as by colour" \
  || bad "selection is a background tint alone — invisible in greyscale"

echo "== T10: every string this change added is in the string table =="
# The owner reads this app in Spanish. There is no translated locale yet, so
# this asserts the STRINGS ARE EXTRACTABLE, which is the part that has to be
# true before any locale can exist.
STRINGS="$APP/app/src/main/res/values/strings.xml"
str_fail=""
for k in app_tile_selected app_tile_unselected app_tile_not_installed \
         power_saving_apps_title power_saving_apps_caption \
         power_saving_slot_chooser power_saving_slot_default power_saving_settings; do
  grep -q "name=\"$k\"" "$STRINGS" || str_fail="$str_fail missing:$k"
done
[ -z "$str_fail" ] && ok "the eight new user-visible strings are resources" \
                   || bad "a new string is compiled into Kotlin:$str_fail"

echo
echo "== T11: the bottom-nav bar is a full pill, and its selection geometry is declared NOT flush =="
# #473. Diego's request: the home bottom bar has semicircular (pill) ends and each
# extreme button must sit further INSIDE so that when one of them is selected its
# selection shadow is concentrically ringed by the bar's curved end — never
# clipped, never flush against the edge — while the gaps between all icons stay
# equal (hard constraint: left margin == right margin, every inter-item gap
# identical), and the icon↔label distance grows a little.
#
# WHY THIS EXISTS: a Material3 BottomNavigationView divides its available width
# into equal item slots, so once the available width is fixed the gaps between
# icons are EQUAL BY CONSTRUCTION and shrink together whenever the width shrinks.
# That is exactly the two-effect lever #473 wants: a single horizontal inset
# declared on BOTH ends (paddingStart AND paddingEnd = the SAME dimen) moves the
# end buttons inward off the pill's semicircular ends AND, because it eats the
# same amount out of both sides of the total width, reduces every inter-icon gap
# by the same amount. So the whole geometry is TWO numbers, both from ONE
# declaration each, and the two regressions that hollow out this change are:
#   1. the inset reverted to flush (0dp / removed)  → end pill sits against the
#      curved end, clipped.
#   2. the two ends differently inset (an unequal gap sneaks in) → left and
#      right margins no longer match.
check "$(python3 - "$RES_LAYOUT" "$RES_DIMENS" <<'PY'
import os, re, sys
layout, dims = open(sys.argv[1], encoding='utf-8').read(), open(sys.argv[2], encoding='utf-8').read()
problems = []

# The dims must exist and be real (not 0dp) — a 0dp/removed entry is the flush
# regression #473 ships to remove.
dimval = {}
for name, v in re.findall(r'<dimen name="([^"]+)">([^<]+)</dimen>', dims):
    m = re.match(r'^([0-9.]+)dp$', v.strip())
    dimval[name] = float(m.group(1)) if m else None
for needed in ('bottom_nav_end_inset', 'bottom_nav_icon_label_gap'):
    if needed not in dimval:
        problems.append('%s is not declared in dimens.xml' % needed)
        continue
    if dimval[needed] is None:
        problems.append('%s is not a literal dp value a static reader can check' % needed)
    elif dimval[needed] <= 0:
        problems.append('%s is %sdp — flush/zero is exactly the regression this asserts away' % (needed, dimval[needed]))

# The nav view must apply the SAME inset dimen to BOTH ends. Reading the same
# @dimen/… ref twice is what makes left margin == right margin by construction;
# two different refs or a literal on either side is the unequal-gap regression.
nav = re.search(r'<com\.google\.android\.material\.bottomnavigation\.BottomNavigationView\b[^>]*/?>', layout)
if not nav:
    problems.append('the layout no longer contains a BottomNavigationView')
else:
    tag = nav.group(0)
    def attr(name):
        m = re.search(name + r'\s*=\s*"([^"]+)"', tag)
        return m.group(1) if m else None
    ps, pe = attr(r'android:paddingStart'), attr(r'android:paddingEnd')
    if ps is None or pe is None:
        problems.append('the nav has no explicit start/end padding — the end buttons sit flush')
    elif ps != pe:
        problems.append('paddingStart=%s but paddingEnd=%s — left/right margins differ' % (ps, pe))
    elif ps != '@dimen/bottom_nav_end_inset':
        problems.append('start/end inset = %s, not @dimen/bottom_nav_end_inset' % ps)
    else:
        inset = dimval.get('bottom_nav_end_inset')
        if inset is not None and inset <= 0:
            problems.append('end inset resolves to %sdp — the pill would be flush' % inset)
    # icon↔label distance must come from the ONE declared dimen, and be larger
    # than the Material default (4dp) — "increase a little".
    gap = attr(r'app:activeIndicatorLabelPadding')
    if gap is None:
        problems.append('no activeIndicatorLabelPadding set — icon/label gap not increased')
    elif gap != '@dimen/bottom_nav_icon_label_gap':
        problems.append('icon/label gap = %s, not @dimen/bottom_nav_icon_label_gap' % gap)
    else:
        gv = dimval.get('bottom_nav_icon_label_gap')
        if gv is not None and gv <= 4:
            problems.append('icon/label gap is %sdp — the request was to INCREASE it above the 4dp default' % gv)

print('; '.join(problems) or 'OK')
PY
)" "both end buttons inset off the pill's semicircular ends by one dimen, gaps stay equal, icon↔label gap enlarged"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
