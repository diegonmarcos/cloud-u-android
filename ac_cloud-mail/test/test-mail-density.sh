#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ cloud-mail DENSITY - a correct resize of each element, from ONE          ║
# ║ declaration (MailMetrics), and never a global scale hack                 ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. The mail UI had been scaled down for a dense data view and that was lost when the
# screens were next rewritten: nothing held the density in ONE place, so every rewrite re-typed its
# own dp literals at the launcher-sized values and the type fell back to Material's default scale.
# The owner's rule: the resize must be per element - type scale, icon size, padding, row height -
# through tokens, NOT a global transform, fontScale/Density override or setScaleX/Y, which shrinks
# the picture and leaves every element the wrong size.
#
#   D1  MailMetrics exists, is DENSE (steps shrink; icon 18, icon button and tap floor 36) and its
#       steps are monotone
#   D2  ONE source: no `N.dp` / `N.sp` literal in main code outside MailMetrics (0.dp is not a size)
#   D3  every screen READS it (the surfaces listed below reference MailMetrics)
#   D4  the type scale: SternaTypography = denseTypography(), TEXT_SCALE < 1, all 15 styles scaled
#   D5  icons and icon buttons come from the dense wrappers, not Material's 24dp / 40dp
#   D6  the touch reservation is lowered in the theme (and not below the 36dp floor)
#   D7  NO GLOBAL SCALE HACK: no Density/fontScale override, no scale of the root, no configuration
#       rewrite; the few animation scales that exist are counted and cannot grow
#   M   mutation-proof: each defence undone in a COPY turns its check red; the real tree is green
#
# OWN-SOURCE ONLY. python3 and grep only; no gradle, no device.
set -uo pipefail
APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$APP/app/src/main/kotlin"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for need in theme/MailMetrics.kt theme/Type.kt theme/Theme.kt components/DenseMaterial.kt; do
  [ -f "$SRC/app/sterna/ui/$need" ] || { echo "ERROR missing $need - this tester is unrun, not passing"; exit 1; }
done

d1() { # <MailMetrics.kt>
  python3 - "$1" <<'PY'
import re, sys
t = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"internal object MailMetrics \{(.*?)\n\}\n", t, re.S)
if not m: print("    no `internal object MailMetrics`"); sys.exit(1)
b = m.group(1); bad = []
vals = {n: float(v) for n, v in re.findall(r"^\s*val (\w+): Dp = ([0-9.]+)\.dp$", b, re.M)}
steps = [(int(n[1:]), v) for n, v in vals.items() if re.fullmatch(r"s\d+", n)]
steps.sort()
if len(steps) < 25: bad.append("only %d steps declared - the ladder the screens use is incomplete" % len(steps))
last = 0
for k, v in steps:
    if v <= last: bad.append("steps are not monotone at s%d = %s" % (k, v))
    last = v
    cap = 0.85 * k if k >= 8 else k
    if v > cap: bad.append("s%d = %sdp is over its density ceiling %.1fdp - it is a launcher-sized value again" % (k, v, cap))
for name, lo, hi in (("icon", 14, 20), ("iconButton", 36, 40), ("tap", 36, 40)):
    if name not in vals: bad.append("MailMetrics.%s is not declared" % name)
    elif not lo <= vals[name] <= hi: bad.append("MailMetrics.%s = %sdp, outside the dense range %s..%sdp" % (name, vals[name], lo, hi))
ts = re.search(r"const val TEXT_SCALE = ([0-9.]+)f", b)
if not ts: bad.append("TEXT_SCALE is not a declared factor")
elif not 0.6 <= float(ts.group(1)) < 1.0: bad.append("TEXT_SCALE = %s is not a scale-DOWN of the type" % ts.group(1))
for b_ in bad: print("    " + b_)
sys.exit(1 if bad else 0)
PY
}
d2() { # <src dir>
  python3 - "$1" <<'PY'
import os, re, sys
bad = []
EXEMPT = {"theme/MailMetrics.kt": "the declaration",
          "compose/SuggestionMenuFit.kt": "pure arithmetic over a measured window, asserted in dp by SuggestionMenuFitTest"}
root = os.path.join(sys.argv[1], "app/sterna/ui")
for base in (sys.argv[1],):
    for d, _, fs in os.walk(base):
        for f in sorted(fs):
            if not f.endswith(".kt"): continue
            p = os.path.join(d, f); rel = os.path.relpath(p, root)
            if rel in EXEMPT: continue
            for i, l in enumerate(open(p, encoding="utf-8"), 1):
                if l.strip().startswith(("//", "*", "/*")): continue
                for hit in re.findall(r"(?<![\w.])\d+(?:\.\d+)?\.(?:dp|sp)\b", l):
                    if hit in ("0.dp",): continue
                    bad.append("%s:%d holds its own size `%s` - density belongs to MailMetrics" % (os.path.relpath(p, sys.argv[1]), i, hit))
for b in bad[:12]: print("    " + b)
if len(bad) > 12: print("    ... and %d more" % (len(bad) - 12))
sys.exit(1 if bad else 0)
PY
}
d3() { # <src dir>
  python3 - "$1" <<'PY'
import os, sys
ui = os.path.join(sys.argv[1], "app/sterna/ui")
surfaces = ["inbox/InboxScreen.kt", "message/MessageScreen.kt", "compose/ComposeScreen.kt", "settings/SettingsScreen.kt",
            "settings/SettingsComponents.kt", "settings/FiltersScreen.kt", "connect/ConnectScreen.kt", "home/HomeScreen.kt",
            "search/SearchScreen.kt", "components/EmailListItem.kt", "components/EmptyState.kt", "theme/MailList.kt",
            "rss/RssScreen.kt", "sender/MailBySenderScreen.kt", "message/LabelSheet.kt", "message/ResumeBox.kt"]
bad = []
for rel in surfaces:
    p = os.path.join(ui, rel)
    if not os.path.isfile(p): bad.append("%s is gone - the surface list is stale" % rel); continue
    if "MailMetrics." not in open(p, encoding="utf-8").read(): bad.append("%s reads no MailMetrics token - it does not follow the density" % rel)
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}
d4() { # <MailMetrics.kt> <Type.kt> <Theme.kt>
  python3 - "$1" "$2" "$3" <<'PY'
import sys
m, ty, th = (open(p, encoding="utf-8").read() for p in sys.argv[1:4])
bad = []
if "val SternaTypography = denseTypography()" not in ty: bad.append("SternaTypography is not denseTypography()")
if "typography = SternaTypography" not in th: bad.append("the theme does not hand SternaTypography to MaterialTheme")
if m.count(".dense(scale)") < 15: bad.append("denseTypography scales %d styles, not the 15-style Material scale" % m.count(".dense(scale)"))
for need in ("fontSize", "lineHeight", "letterSpacing"):
    if "%s = " % need not in m.split("private fun TextStyle.dense")[1].split("\n}\n")[0]: bad.append("a dense style does not resize its %s" % need)
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}
d5() { # <src dir>
  python3 - "$1" <<'PY'
import os, re, sys
bad = []
for d, _, fs in os.walk(sys.argv[1]):
    for f in fs:
        if not f.endswith(".kt") or f == "DenseMaterial.kt": continue
        p = os.path.join(d, f); t = open(p, encoding="utf-8").read()
        if re.search(r"^import androidx\.compose\.material3\.(Icon|IconButton)$", t, re.M):
            bad.append("%s imports Material's Icon/IconButton (24dp / 40dp) instead of app.sterna.ui.components" % os.path.relpath(p, sys.argv[1]))
dm = open(os.path.join(sys.argv[1], "app/sterna/ui/components/DenseMaterial.kt"), encoding="utf-8").read()
if "modifier.size(MailMetrics.icon)" not in dm: bad.append("the Icon wrapper no longer applies MailMetrics.icon")
if "modifier.size(MailMetrics.iconButton)" not in dm: bad.append("the IconButton wrapper no longer applies MailMetrics.iconButton")
for b in bad[:10]: print("    " + b)
sys.exit(1 if bad else 0)
PY
}
d6() { # <MailMetrics.kt> <Theme.kt>
  python3 - "$1" "$2" <<'PY'
import sys
m, th = (open(p, encoding="utf-8").read() for p in sys.argv[1:3])
bad = []
if "LocalMinimumInteractiveComponentSize provides MailMetrics.tap" not in m: bad.append("the 48dp touch reservation is no longer lowered to MailMetrics.tap")
if "ProvideDenseTouchTargets(content)" not in th: bad.append("the theme does not provide the dense touch targets")
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}
d7() { # <src dir> : the global scale hacks
  python3 - "$1" <<'PY'
import os, re, sys
# animation scales that existed when this guard was written. A COUNT per file: it can shrink, never grow,
# and a file not listed here may not scale at all.
ANIM = {"app/sterna/ui/inbox/InboxScreen.kt": 4, "app/sterna/ui/components/TernRefreshIndicator.kt": 2,
        "app/sterna/ui/components/PendingImportAccounts.kt": 2, "app/sterna/ui/components/EmailListItem.kt": 1}
HARD = [(r"LocalDensity\s+provides", "overrides LocalDensity"),
        (r"\bDensity\(\s*[A-Za-z0-9_.]*\s*[,)]", "builds a Density (a global rescale of every dp and sp)"),
        (r"\bfontScale\s*=\s*[0-9]", "writes a fontScale (reading the system one is fine)"),
        (r"\.copy\(\s*fontScale", "copies a Density/Configuration with another fontScale"),
        (r"createConfigurationContext|updateConfiguration|\bdensityDpi\b", "rewrites the Configuration"),
        (r"setScaleX|setScaleY|\.setScale\(", "setScaleX/Y on a view"),
        (r"setInitialScale\(\s*[1-9]", "pins a WebView scale (a zoom hack)"),
        (r"\bsetTextSize\(|\btextScaleX\b", "resizes text outside the type scale")]
SCALE = re.compile(r"\bscaleX\b|\bscaleY\b|\.scale\(|\bModifier\.scale\b")
bad = []
for d, _, fs in os.walk(sys.argv[1]):
    for f in sorted(fs):
        if not f.endswith(".kt"): continue
        p = os.path.join(d, f); rel = os.path.relpath(p, sys.argv[1]); n = 0
        for i, l in enumerate(open(p, encoding="utf-8"), 1):
            if l.strip().startswith(("//", "*", "/*", "import ")): continue
            for pat, why in HARD:
                if re.search(pat, l): bad.append("%s:%d %s: %s" % (rel, i, why, l.strip()[:70]))
            if SCALE.search(l): n += 1
        allowed = ANIM.get(rel, 0)
        if n > allowed: bad.append("%s scales %d line(s), baseline %d - a scale on a screen is not a density; resize the elements" % (rel, n, allowed))
for b in bad[:12]: print("    " + b)
sys.exit(1 if bad else 0)
PY
}
# the resource side: a theme/style that scales everything
d7res() { # <res dir>
  ! grep -rEl 'android:fontScale|fontScale=|android:scaleX="[0-9.]+".*screen' "$1" >/dev/null 2>&1
}

M="$SRC/app/sterna/ui/theme/MailMetrics.kt"; TY="$SRC/app/sterna/ui/theme/Type.kt"; TH="$SRC/app/sterna/ui/theme/Theme.kt"
echo "── D1 the declaration exists and is dense ──"
d1 "$M" && pass "MailMetrics: monotone steps, every step under its ceiling, icon 20 / icon button 36 / tap 36, TEXT_SCALE < 1" || fail "MailMetrics is missing, incomplete or launcher-sized"
echo "── D2 ONE source: no screen sizes itself ──"
d2 "$SRC" && pass "no dp or sp literal outside MailMetrics" || fail "a screen holds its own size"
echo "── D3 every screen reads the declaration ──"
d3 "$SRC" && pass "the inbox, reader, composer, settings, home, search, list row and sheets read MailMetrics" || fail "a surface does not follow the density"
echo "── D4 the type scale is resized once, in the theme ──"
d4 "$M" "$TY" "$TH" && pass "all 15 Material styles: size, line height and tracking, via denseTypography()" || fail "the type scale is not resized from the declaration"
echo "── D5 icons and icon buttons are dense ──"
d5 "$SRC" && pass "no screen imports Material's Icon/IconButton; the wrappers apply MailMetrics.icon / iconButton" || fail "an icon keeps the Material box"
echo "── D6 the touch reservation is lowered, not removed ──"
d6 "$M" "$TH" && pass "LocalMinimumInteractiveComponentSize = MailMetrics.tap (36dp), provided by the theme" || fail "the 48dp touch reservation is back"
echo "── D7 no global scale hack ──"
d7 "$SRC" && d7res "$APP/app/src/main/res" && pass "no Density/fontScale override, no configuration rewrite, no root scale; animation scales cannot grow" || fail "a global scale hack is in the mail app"

echo "── M mutation-proof ──"
T="$(mktemp -d)"; trap 'rm -rf "${T:?}"' EXIT
fresh() { rm -rf "$T/s"; cp -r "$SRC" "$T/s"; }
sub() { # <file under s> <old> <new>
  python3 - "$T/s/$1" "$2" "$3" <<'PY'
import sys
p, old, new = sys.argv[1:4]
s = open(p, encoding="utf-8").read()
if old not in s: print("MUTATION-NOT-APPLIED %s" % old); sys.exit(3)
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PY
}
red() { # <label> <check> <fn args...>
  local label="$1"; shift
  "$@" >/dev/null && fail "M: $label passed" || pass "M: $label -> RED"
}
fresh; sub app/sterna/ui/inbox/InboxScreen.kt "MailMetrics.s16" "16.dp" || fail "M: stale tester (D2 mutation)"
red "a magic 16.dp put back into a screen (D2)" d2 "$T/s"
fresh; sub app/sterna/ui/theme/MailMetrics.kt "val s24: Dp = 19.dp" "val s24: Dp = 24.dp" || fail "M: stale tester (D1 mutation)"
red "the s24 step turned back up to 24dp (D1)" d1 "$T/s/app/sterna/ui/theme/MailMetrics.kt"
fresh; sub app/sterna/ui/theme/MailMetrics.kt "const val TEXT_SCALE = 0.85f" "const val TEXT_SCALE = 1.0f" || fail "M: stale tester (D1 text)"
red "TEXT_SCALE back to 1.0 (D1)" d1 "$T/s/app/sterna/ui/theme/MailMetrics.kt"
fresh; sub app/sterna/ui/theme/Type.kt "denseTypography()" "androidx.compose.material3.Typography()" || fail "M: stale tester (D4)"
red "the default Material type scale back (D4)" d4 "$T/s/app/sterna/ui/theme/MailMetrics.kt" "$T/s/app/sterna/ui/theme/Type.kt" "$T/s/app/sterna/ui/theme/Theme.kt"
fresh; sub app/sterna/ui/theme/MailMetrics.kt "    bodySmall = base.bodySmall.dense(scale)," "    bodySmall = base.bodySmall," || fail "M: stale tester (D4 style)"
red "one of the 15 styles left unscaled (D4)" d4 "$T/s/app/sterna/ui/theme/MailMetrics.kt" "$T/s/app/sterna/ui/theme/Type.kt" "$T/s/app/sterna/ui/theme/Theme.kt"
fresh; sub app/sterna/ui/home/HomeScreen.kt "import app.sterna.ui.components.Icon" "import androidx.compose.material3.Icon" || fail "M: stale tester (D5)"
red "a screen back on Material's 24dp Icon (D5)" d5 "$T/s"
fresh; sub app/sterna/ui/components/DenseMaterial.kt "modifier.size(MailMetrics.icon)" "modifier" || fail "M: stale tester (D5 wrapper)"
red "the Icon wrapper stops sizing (D5)" d5 "$T/s"
fresh; sub app/sterna/ui/theme/Theme.kt "content = { ProvideDenseTouchTargets(content) }," "content = content," || fail "M: stale tester (D6)"
red "the dense touch targets not provided (D6)" d6 "$T/s/app/sterna/ui/theme/MailMetrics.kt" "$T/s/app/sterna/ui/theme/Theme.kt"
fresh; sub app/sterna/ui/theme/Theme.kt "CompositionLocalProvider(LocalMailListPalette provides mailListPalette, LocalSternaDarkTheme provides darkTheme) {" "CompositionLocalProvider(LocalMailListPalette provides mailListPalette, LocalSternaDarkTheme provides darkTheme, LocalDensity provides Density(LocalDensity.current.density * 0.8f)) {" || fail "M: stale tester (D7 density)"
red "a LocalDensity override at the theme (D7)" d7 "$T/s"
fresh; sub app/sterna/ui/theme/Theme.kt "typography = SternaTypography," "typography = SternaTypography, modifier = androidx.compose.ui.Modifier.scale(0.8f)," || fail "M: stale tester (D7 scale)"
red "a root Modifier.scale in the theme (D7)" d7 "$T/s"
fresh; sub app/sterna/ui/inbox/InboxScreen.kt "fun InboxScreen(" "val fontScale = 0.8f\nfun InboxScreen(" || fail "M: stale tester (D7 fontScale)"
red "a fontScale written in a screen (D7)" d7 "$T/s"
d1 "$M" >/dev/null && d2 "$SRC" >/dev/null && d3 "$SRC" >/dev/null && d4 "$M" "$TY" "$TH" >/dev/null && d5 "$SRC" >/dev/null \
  && d6 "$M" "$TH" >/dev/null && d7 "$SRC" >/dev/null && pass "M: the unmutated tree is still green" || fail "M: the unmutated tree is red"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-mail-density: all checks passed"; else echo "test-mail-density: $FAILURES check(s) FAILED"; fi
exit "$FAILURES"
