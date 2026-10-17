#!/usr/bin/env bash
# Tester: the edge-menu overlay (libs:launcher-onehand) must NOT steal taps.
#
# WHY THIS EXISTS. Owner report 2026-10-16: the SuperApp launcher could not tap apps. Root cause:
# 8edc3d7a5 moved the handles into OneHandOverlayService and removed the Accessibility tap pass-through;
# the handle (build.json: 32dp x 220dp, both edges) returned true on every touch and opened the menu on
# a bare swipe, so a 32x220dp band down each edge was dead to the app below and any edge sweep fired.
# A separate window cannot hand a consumed stream back without Accessibility, so the fix is: idle
# window thin + short + NOT_TOUCH_MODAL (EdgeHandleWindow), the preview veil never touchable, and the
# menu opens ONLY after a hold then an inward push (EdgeGestureMachine); the veil is removed at the end.
#
# Static tester (no device, no build), comments stripped.
#   T1  idle strip: FLAGS has NOT_TOUCH_MODAL + NOT_FOCUSABLE and NOT NOT_TOUCHABLE; caps <=24dp thick, <=200dp long
#   T2  preview veil (the only MATCH_PARENT window): PREVIEW_FLAGS has NOT_TOUCHABLE; service uses it
#   T3  service: handle LayoutParams use EdgeHandleWindow (size + FLAGS); no MATCH_PARENT/WRAP_CONTENT in addHandle
#   T4  service: touch handler goes through EdgeGestureMachine with the system long-press time; no bare-swipe activation
#   T5  veil is REMOVED (removeView) on gesture end and in onDestroy
#   T6  MUTATION: every regression above must turn the checker red.
#   T7  (2026-10-17, owner's Samsung: menus DEAD since sha-e5013863) hit area >= 26dp everywhere
#       (MIN_HIT_DP floor in size(), pref default/min); a plain inward swipe opens the menu (PENDING->MENU,
#       no mandatory hold); the Compose preview overlay installs ViewTree Lifecycle + SavedStateRegistry
#       owners on its WindowManager root BEFORE super.onAttachedToWindow().
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
LIB="$APP/../ab_cloud-libs-shared/libs/launcher-onehand"
REL=src/main/java/com/diegonmarcos/superapp/onehand
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
[ -d "$LIB/$REL" ] || { echo "  FAIL: $LIB/$REL missing"; exit 1; }
export KIT_HOST="$LIB/../ui-kit/src/main/kotlin/com/diegonmarcos/superapp/uikit/KitImeHost.kt"

# check <libdir>: print one line per violation, exit 1 if any.
check() {
python3 - "$1/$REL" <<'PYEOF'
import re, sys
d = sys.argv[1]
def src(n):
    t = open(d + "/" + n, encoding="utf-8").read()
    t = re.sub(r'/\*.*?\*/', lambda m: '\n' * m.group(0).count('\n'), t, flags=re.S)
    return re.sub(r'//[^\n]*', '', t)
v = []
w = src("EdgeHandleWindow.kt"); s = src("OneHandOverlayService.kt")
def const(name, text):
    m = re.search(r'const val %s\s*=\s*(.*?)(?=\n\s*(?:const|data|/\*\*|fun|\Z))' % name, text, flags=re.S)
    return m.group(1) if m else ""
flags, pflags = const("FLAGS", w), const("PREVIEW_FLAGS", w)
if "FLAG_NOT_TOUCH_MODAL" not in flags: v.append("T1 idle FLAGS lacks FLAG_NOT_TOUCH_MODAL")
if "FLAG_NOT_FOCUSABLE" not in flags: v.append("T1 idle FLAGS lacks FLAG_NOT_FOCUSABLE")
if "FLAG_NOT_TOUCHABLE" in flags: v.append("T1 idle FLAGS contains FLAG_NOT_TOUCHABLE (strip could never detect a hold)")
m = re.search(r'MAX_THICKNESS_DP\s*=\s*(\d+)', w)
if not m or int(m.group(1)) > 32: v.append("T1 MAX_THICKNESS_DP missing or > 32")
if m and int(m.group(1)) < 26: v.append("T1 MAX_THICKNESS_DP < 26 (strip inside the back-gesture band / Samsung edge reject: unreachable)")
m = re.search(r'MAX_LENGTH_DP\s*=\s*(\d+)', w)
if not m or int(m.group(1)) > 200: v.append("T1 MAX_LENGTH_DP missing or > 200")
if "FLAG_NOT_TOUCHABLE" not in pflags: v.append("T2 PREVIEW_FLAGS lacks FLAG_NOT_TOUCHABLE")
pv = re.search(r'private fun addPreviewLayer.*?\n    }\n', s, flags=re.S)
if not pv or "EdgeHandleWindow.PREVIEW_FLAGS" not in pv.group(0): v.append("T2 preview veil does not use PREVIEW_FLAGS")
if s.count("MATCH_PARENT") != 2 or (pv and pv.group(0).count("MATCH_PARENT") != 2): v.append("T2 a MATCH_PARENT window exists outside the preview veil")
ah = re.search(r'private fun addHandle.*?\n    }\n', s, flags=re.S)
a = ah.group(0) if ah else ""
if "EdgeHandleWindow.FLAGS" not in a or "EdgeHandleWindow.size(" not in a: v.append("T3 addHandle does not use EdgeHandleWindow size+FLAGS")
if "MATCH_PARENT" in a or "WRAP_CONTENT" in a: v.append("T3 addHandle uses MATCH_PARENT/WRAP_CONTENT")
th = re.search(r'private fun onHandleTouch.*?\n    }\n', s, flags=re.S)
t = th.group(0) if th else ""
if not re.search(r'holdMs\s*=\s*EdgeGestureMachine\.holdMs\(\s*ViewConfiguration\.getLongPressTimeout\(\)', t) or not re.search(r'EdgeGestureMachine\(h\.edge,\s*holdMs', t): v.append("T4 touch handler lacks EdgeGestureMachine + long-press timeout")
if re.search(r'SwipeClassifier\.sector\([^)]*\)\s*!=\s*null', t): v.append("T4 menu activates outside the gesture machine")
if "activate(h)" in t and "State.MENU" not in t: v.append("T4 activate() not gated on State.MENU")
eg = re.search(r'private fun endGesture.*?\n    }\n', s, flags=re.S)
if not eg or "endPreview()" not in eg.group(0): v.append("T5 endGesture does not tear down the veil")
ep = re.search(r'private fun endPreview.*?\n    }\n', s, flags=re.S)
if not ep or "removeView" not in ep.group(0): v.append("T5 endPreview does not removeView")
if "endGesture()" not in re.search(r'override fun onDestroy.*?\n    }\n', s, flags=re.S).group(0): v.append("T5 onDestroy does not endGesture")
# ---- T7 ----
m = re.search(r'MIN_HIT_DP\s*=\s*(\d+)', w)
if not m or int(m.group(1)) < 26: v.append("T7 MIN_HIT_DP missing or < 26")
sz = re.search(r'fun size\(.*?\n    }\n', w, flags=re.S)
if not sz or not re.search(r'thicknessPx\.coerceIn\(\s*\(MIN_HIT_DP', sz.group(0)): v.append("T7 size() does not floor thickness at MIN_HIT_DP (a 0/8dp strip is possible)")
p = src("OneHandPrefs.kt")
m = re.search(r'STRIP_DEFAULT_DP\s*=\s*(\d+)', p)
if not m or int(m.group(1)) < 26: v.append("T7 STRIP_DEFAULT_DP < 26")
if not re.search(r'STRIP_MIN_DP\s*=\s*EdgeHandleWindow\.MIN_HIT_DP', p): v.append("T7 STRIP_MIN_DP is not EdgeHandleWindow.MIN_HIT_DP")
g = src("EdgeGestureMachine.kt")
pend = re.search(r'State\.PENDING\s*->\s*\{(.*?)\n            \}', g, flags=re.S)
if not pend or "State.MENU" not in pend.group(1): v.append("T7 PENDING cannot go to MENU: a plain swipe never opens the menu (the Samsung regression)")
if pend and re.search(r'if\s*\(\s*d\s*>\s*slopPx\s*\)\s*state\s*=\s*State\.PASS', pend.group(1)): v.append("T7 any move past slop kills the gesture")
o = src("OverlayCanvasView.kt"); pv2 = src("GesturePreviewView.kt")
if not re.search(r'class OverlayCanvasView\([^)]*\)\s*:\s*KitImeComposeView', o): v.append("T7 OverlayCanvasView no longer extends KitImeComposeView (no owners on the overlay root)")
if not re.search(r'class GesturePreviewView\([^)]*\)\s*:\s*OverlayCanvasView', pv2): v.append("T7 GesturePreviewView not an OverlayCanvasView")
import os
kit = os.environ.get("KIT_HOST", "")
k = open(kit, encoding="utf-8").read() if os.path.exists(kit) else ""
k = re.sub(r'/\*.*?\*/', '', k, flags=re.S); k = re.sub(r'//[^\n]*', '', k)
at = re.search(r'override fun onAttachedToWindow\(\)\s*\{(.*?)\n    }', k, flags=re.S)
if not k: v.append("T7 KitImeHost.kt not found next to the lib")
elif not at or not re.search(r'ensureOwners\(rootView\).*super\.onAttachedToWindow\(\)', at.group(1), flags=re.S): v.append("T7 KitImeComposeView does not install owners before super.onAttachedToWindow()")
for need in ("setViewTreeLifecycleOwner", "setViewTreeSavedStateRegistryOwner"):
    if k and need not in k: v.append("T7 KitImeComposeView lacks " + need)
print("\n".join(v)); sys.exit(1 if v else 0)
PYEOF
}

echo "== T1-T5: real tree"
OUT="$(check "$LIB")" && ok "idle strip thin/short/NOT_TOUCH_MODAL, veil NOT_TOUCHABLE+removed, >=26dp hit, swipe or hold+push, Compose owners" \
  || bad "touch regression: $OUT"

echo "== T6: mutations (the checker must flag each)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/edge-touch.XXXXXX")" || { bad "mktemp failed"; WORK=""; }
if [ -n "$WORK" ]; then
  mutate() { # name file sed-expr
    rm -rf "$WORK/lib"; mkdir -p "$WORK/lib"; cp -r "$LIB/src" "$WORK/lib/src"
    sed -i "$3" "$WORK/lib/$REL/$2"
    if cmp -s "$LIB/$REL/$2" "$WORK/lib/$REL/$2"; then bad "mutation '$1' changed nothing (sed pattern stale)"; return; fi
    if check "$WORK/lib" >/dev/null; then bad "mutation '$1' NOT detected"; else ok "mutation '$1' detected"; fi
  }
  W=EdgeHandleWindow.kt; S=OneHandOverlayService.kt
  mutate "drop FLAG_NOT_TOUCH_MODAL" $W '0,/LP.FLAG_NOT_TOUCH_MODAL or/s//0 or/'
  mutate "idle strip made NOT_TOUCHABLE" $W 's/const val FLAGS = LP.FLAG_NOT_FOCUSABLE/const val FLAGS = LP.FLAG_NOT_TOUCHABLE or LP.FLAG_NOT_FOCUSABLE/'
  mutate "strip 48dp thick" $W 's/MAX_THICKNESS_DP = 28/MAX_THICKNESS_DP = 48/'
  mutate "strip 24dp thin (inside Samsung reject band)" $W 's/MAX_THICKNESS_DP = 28/MAX_THICKNESS_DP = 24/'
  mutate "min hit 12dp" $W 's/MIN_HIT_DP = 26/MIN_HIT_DP = 12/'
  mutate "size() floor removed (0dp strip)" $W 's/thicknessPx.coerceIn((MIN_HIT_DP \* density).toInt(),/thicknessPx.coerceIn(1,/'
  mutate "pref default 24dp" OneHandPrefs.kt 's/STRIP_DEFAULT_DP = 28/STRIP_DEFAULT_DP = 24/'
  mutate "pref min 12dp" OneHandPrefs.kt 's/STRIP_MIN_DP = EdgeHandleWindow.MIN_HIT_DP/STRIP_MIN_DP = 12/'
  mutate "hold mandatory again (swipe dead)" EdgeGestureMachine.kt 's/if (inward(edge, dx, dy) \&\& d > pushPx) state = State.MENU/if (false) state = State.IDLE/'
  mutate "preview not Compose-owned" OverlayCanvasView.kt 's/) : KitImeComposeView(ctx)/) : android.view.View(ctx)/'
  mutate "strip full height" $W 's/MAX_LENGTH_DP = 160/MAX_LENGTH_DP = 2400/'
  mutate "veil touchable" $W 's/PREVIEW_FLAGS = LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCHABLE or/PREVIEW_FLAGS = LP.FLAG_NOT_FOCUSABLE or/'
  mutate "veil flags unused" $S 's/EdgeHandleWindow.PREVIEW_FLAGS/WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE/'
  mutate "handle window MATCH_PARENT" $S 's/            size.widthPx,/            WindowManager.LayoutParams.MATCH_PARENT,/'
  mutate "handle ignores EdgeHandleWindow.FLAGS" $S 's/            EdgeHandleWindow.FLAGS,/            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,/'
  mutate "menu opened outside the gesture machine" $S 's/if (m.state == EdgeGestureMachine.State.MENU) {/if (SwipeClassifier.sector(h.edge, 1f, 0f) != null) {/'
  mutate "no long-press timeout" $S 's/EdgeGestureMachine.holdMs(ViewConfiguration.getLongPressTimeout().toLong(), c.longPressMs)/0L/'
  mutate "veil never removed" $S '/private fun endPreview/,/^    }/s/; runCatching { windowManager?.removeView(it) }//'
  rm -rf "$WORK/lib"; rmdir "$WORK"
fi
echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
