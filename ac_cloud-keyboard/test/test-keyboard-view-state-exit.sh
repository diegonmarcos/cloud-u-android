#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #843 One view-state model owns what the input view shows, and every      ║
# ║ non-typing state keeps a visible, working exit                           ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# THE BUG. The clipboard panel's strip keys (CLOSE_HISTORY among them) were built
# ONCE, in ClipboardHistoryView's constructor, and only when
# SettingsValues.mSecondaryStripVisible - which is false while the keyguard is
# locked. A config change while locked re-inflated the input view (#776's path),
# so that view's clipboard panel had no close key for the rest of its life. #776
# then healed the TOOLBAR row, which made the clipboard key reachable again and
# put the user into a panel without its way out. Visibility was decided by
# whichever of five methods ran last, "which panel is open" was View.isShown(),
# and the self-heal ran without knowing a panel was open.
#
#   G1  KeyboardSwitcher: the input view's pieces change visibility ONLY in
#       applyViewState(), which renders KeyboardViewState.plan.
#   G2  no other file toggles those pieces (strip container, emoji tab strip,
#       clipboard strip, the strip / panel views themselves).
#   G3  the #776 self-heal goes through the model: LatinIME calls
#       requestToolbarCheck, which consults deferToolbarCheck BEFORE ensureToolbar;
#       ensureToolbar has no other caller.
#   G4  the model: a clipboard panel always shows the strip container + clipboard
#       strip; every panel shows the frame + wrapper (where its ABC row lives);
#       clipboardStripKeys always carries the close key; both bottom rows hold ABC.
#   G5  the clipboard strip is rebuilt at every open from current settings, via
#       clipboardStripKeys(... CLOSE_HISTORY), never in the constructor.
#   G6  the other exits: system back, the panel's own toolbar key, window hide;
#       a re-inflation resets the model; reloadMainKeyboard asks the model.
#   G7  the JVM invariant test exists and CI runs it.
#
# SELF-PROVING: after the real tree passes, each mutation must turn its named
# check red. python3 only; a missing source file is fatal, never a pass.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
K=ab_cloud-libs-shared/libs/keyboard
J=$K/src/main/java/helium314/keyboard
FILES=(
    "$J/keyboard/KeyboardSwitcher.java"
    "$J/keyboard/KeyboardViewState.kt"
    "$J/keyboard/clipboard/ClipboardHistoryView.kt"
    "$J/keyboard/emoji/EmojiPalettesView.java"
    "$J/latin/LatinIME.java"
    "$J/latin/suggestions/SuggestionStripView.kt"
    "$K/src/main/assets/layouts/clipboard_bottom/clip_bottom_row.json"
    "$K/src/main/assets/layouts/emoji_bottom/emoji_bottom_row.json"
    "$K/src/test/java/helium314/keyboard/keyboard/KeyboardViewStateTest.kt"
    "$K/build.gradle"
    ac_cloud-keyboard/build.sh
    .github/workflows/ship-cloud-keyboard.yml
)
for f in "${FILES[@]}"; do [ -f "$ROOT/$f" ] || { echo "FATAL  missing $f"; exit 1; }; done

ROOT="$ROOT" J="$J" K="$K" python3 - <<'PY'
import os, re, sys, glob
R, J, K = os.environ["ROOT"], os.environ["J"], os.environ["K"]
def rd(p): return open(os.path.join(R, p), encoding="utf-8").read()
def code(s): return re.sub(r"//[^\n]*", "", s)
def body(src, sig):
    m = re.search(sig, src)
    if not m: return ""
    i = src.find("{", m.end() - 1); d = 0
    for k in range(i, len(src)):
        d += {"{": 1, "}": -1}.get(src[k], 0)
        if d == 0: return src[i:k + 1]
    return ""
fails = 0
def check(ok, name):
    global fails
    print(("ok     " if ok else "FAIL   ") + name)
    if not ok: fails += 1

KS = code(rd(f"{J}/keyboard/KeyboardSwitcher.java"))
VS = code(rd(f"{J}/keyboard/KeyboardViewState.kt"))
CH = code(rd(f"{J}/keyboard/clipboard/ClipboardHistoryView.kt"))
IME = code(rd(f"{J}/latin/LatinIME.java"))

OWNED = ["mMainKeyboardFrame", "mKeyboardViewWrapper", "mKeyboardView", "mStripContainer", "mSuggestionStripView",
         "mEmojiTabStripView", "mClipboardStripScrollView", "mEmojiPalettesView", "mClipboardHistoryView"]
apply_body = body(KS, r"private void applyViewState\(")
rest = KS.replace(apply_body, "") if apply_body else KS
print("== G1 KeyboardSwitcher renders through applyViewState only ==")
check(bool(apply_body) and "mViewState.plan(" in apply_body, "G1 applyViewState renders KeyboardViewState.plan")
direct = [o for o in OWNED if re.search(re.escape(o) + r"\.setVisibility\(", rest)]
check(not direct, f"G1 no owned view's setVisibility outside applyViewState {direct or ''}")
show_calls = [m.start() for m in re.finditer(r"(?<![\w.])show\(", re.sub(r"static void show\(", "", rest))]
check(not show_calls, "G1 show() is called only from applyViewState")
check(all(f"show({o}," in apply_body for o in OWNED), "G1 applyViewState renders every owned view")

print("== G2 nothing else toggles the owned views ==")
pat = re.compile(r"(getStripContainer\(\)|getEmojiTabStrip\(\)|getClipboardStrip\(\)|\bstripContainer\b|\bemojiTabStrip\b|\bclipboardStrip\b|\bmTabStrip\b)"
                 r"\s*\.\s*(setVisibility\(|isVisible\s*=|visibility\s*=|isGone\s*=)")
selfvis = re.compile(r"^\s*(this\.)?(isVisible|visibility|isGone|isInvisible)\s*=|^\s*(this\.)?setVisibility\(", re.M)
hits = []
for p in glob.glob(os.path.join(R, J, "**", "*.*"), recursive=True):
    if not p.endswith((".kt", ".java")) or p.endswith("KeyboardSwitcher.java"): continue
    s = code(open(p, encoding="utf-8").read())
    if pat.search(s): hits.append(os.path.relpath(p, R))
    if os.path.basename(p) in ("SuggestionStripView.kt", "ClipboardHistoryView.kt", "EmojiPalettesView.java") and selfvis.search(s):
        hits.append(os.path.relpath(p, R) + " (own visibility)")
check(not hits, f"G2 no view outside KeyboardSwitcher toggles a strip/panel piece {hits or ''}")

print("== G3 the self-heal goes through the model ==")
rq = body(KS, r"public void requestToolbarCheck\(")
d, e = rq.find("deferToolbarCheck("), rq.find("ensureToolbar(")
check(d >= 0 and e > d, "G3 requestToolbarCheck consults deferToolbarCheck before ensureToolbar")
callers = []
for p in glob.glob(os.path.join(R, J, "**", "*.*"), recursive=True):
    if not p.endswith((".kt", ".java")): continue
    s = code(open(p, encoding="utf-8").read())
    if p.endswith("KeyboardSwitcher.java"): s = s.replace(rq, "")
    if p.endswith("SuggestionStripView.kt"): s = re.sub(r"fun ensureToolbar\(", "", s)
    if "ensureToolbar(" in s: callers.append(os.path.relpath(p, R))
check(not callers, f"G3 ensureToolbar has no caller but requestToolbarCheck {callers or ''}")
start = body(IME, r"void onStartInputViewInternal\("); attach = body(IME, r"public void updateSuggestionStripView\(")
check("mKeyboardSwitcher.requestToolbarCheck(" in start and "mKeyboardSwitcher.requestToolbarCheck(" in attach,
      "G3 LatinIME re-checks on start and attach through requestToolbarCheck")
check("toTyping(" in VS and re.search(r"deferredToolbarCheck\.also", VS) is not None, "G3 a deferred check is handed back on the way to typing")

print("== G4 the model guarantees the exits ==")
clip = re.search(r"PanelKind\.CLIPBOARD\s*->\s*\{(.*?)\n\s{24}\}", VS, re.S)
cb = clip.group(1) if clip else ""
check(re.search(r"(?m)^\s*v\.add\(Piece\.STRIP_CONTAINER\)", cb) is not None and "v.add(Piece.CLIPBOARD_STRIP)" in cb,
      "G4 a clipboard panel always shows the strip container and its strip")
panel = re.search(r"is Mode\.Panel\s*->\s*\{(.*)", VS, re.S)
check(panel is not None and re.search(r"v\.add\(Piece\.MAIN_FRAME\);\s*v\.add\(Piece\.KEYBOARD_WRAPPER\)", panel.group(1)) is not None,
      "G4 every panel shows the frame and the wrapper that hold its ABC row")
csk = re.search(r"fun <K> clipboardStripKeys\([^)]*\)[^=]*=(.*?)\n\s*\n", VS + "\n\n", re.S)
check(csk is not None and "listOf(close)" in csk.group(1) and "+ close" in csk.group(1), "G4 clipboardStripKeys always carries the close key")
for row in ("clipboard_bottom/clip_bottom_row.json", "emoji_bottom/emoji_bottom_row.json"):
    check('"label": "alpha"' in rd(f"{K}/src/main/assets/layouts/{row}"), f"G4 {row} holds the ABC key")

print("== G5 the clipboard strip is rebuilt at every open ==")
init = body(CH, r"\n    init\s*")
check("createToolbarKey(" not in init, "G5 the constructor builds no strip key")
start_ch = body(CH, r"fun startClipboardHistory\(")
check("rebuildStripKeys()" in start_ch, "G5 startClipboardHistory rebuilds the strip keys")
rb = body(CH, r"private fun rebuildStripKeys\(")
check("KeyboardViewState.clipboardStripKeys(" in rb and "ToolbarKey.CLOSE_HISTORY" in rb and "removeView(" in rb,
      "G5 rebuildStripKeys replaces the keys via clipboardStripKeys(..., CLOSE_HISTORY)")

print("== G6 back, toolbar key, hide, re-inflation ==")
kd = body(IME, r"public boolean onKeyDown\(")
check(re.search(r"KEYCODE_BACK.*?closePanelIfOpen\(", kd, re.S) is not None, "G6 system back closes an open panel")
tg = body(KS, r"public void onToggleKeyboard\(")
check("toggleTarget(" in tg and "closePanelIfOpen(" in tg, "G6 a panel's toolbar key closes it")
check("closePanelIfOpen(" in body(KS, r"public void onHideWindow\("), "G6 hiding the window leaves the panel")
check("mViewState.onInputViewRecreated()" in body(KS, r"public View onCreateInputView\("), "G6 a re-inflation resets the model")
rl = body(KS, r"public void reloadMainKeyboard\(")
check("mViewState.isPanel(" in rl and "isShowingClipboardHistory()" not in rl, "G6 reloadMainKeyboard asks the model, not isShown()")

print("== G7 the JVM invariant test runs in CI ==")
t = rd(f"{K}/src/test/java/helium314/keyboard/keyboard/KeyboardViewStateTest.kt")
check("lockedConfigChangeWhileClipboardOpenKeepsTheExit" in t and "everyPanelRendersAVisibleExitUnderEverySetting" in t,
      "G7 KeyboardViewStateTest covers the invariant and the locked config change")
check("testImplementation 'junit:junit" in rd(f"{K}/build.gradle"), "G7 libs:keyboard declares junit")
check(":libs:keyboard:testDebugUnitTest" in rd("ac_cloud-keyboard/build.sh") and "./build.sh unit" in rd(".github/workflows/ship-cloud-keyboard.yml"),
      "G7 Ship -> cloud-keyboard runs it")

print(f"== {fails} failed ==")
sys.exit(1 if fails else 0)
PY
rc=$?
[ "$rc" -eq 0 ] || { echo "test-keyboard-view-state-exit: the real tree is RED"; exit 1; }
[ "${VIEW_STATE_NO_MUTATIONS:-0}" = "1" ] && exit 0

MUT_FAIL=0
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
mutate() {
    rm -rf "$WORK/t"; mkdir -p "$WORK/t"
    (cd "$ROOT" && cp --parents -r -t "$WORK/t" "${FILES[@]}" "$J")
    local before; before="$(cat "$WORK/t/$2")"
    python3 -c "import re,sys; p=sys.argv[1]; s=open(p).read(); $3; open(p,'w').write(s)" "$WORK/t/$2"
    if [ "$before" = "$(cat "$WORK/t/$2")" ]; then echo "FAIL   mutation '$1' did not land — the fixture moved"; MUT_FAIL=$((MUT_FAIL + 1)); return; fi
    local out; out="$(CLOUD_ANDROID_ROOT="$WORK/t" VIEW_STATE_NO_MUTATIONS=1 bash "$SELF" 2>&1)"
    if grep -q "^FAIL   $4" <<<"$out"; then echo "ok     mutation '$1' turns $4 red"
    else echo "FAIL   mutation '$1' left $4 green"; MUT_FAIL=$((MUT_FAIL + 1)); fi
}
echo "== mutations =="
mutate "setClipboardKeyboard hides the strip itself (the old style)" "$J/keyboard/KeyboardSwitcher.java" \
    "s=s.replace('        applyViewState();\n        mClipboardStripScrollView.post','        applyViewState();\n        mStripContainer.setVisibility(View.GONE);\n        mClipboardStripScrollView.post',1)" "G1 no owned view"
mutate "a panel view toggles its own visibility" "$J/keyboard/clipboard/ClipboardHistoryView.kt" \
    "s=s.replace('        initialize()\n','        initialize()\n        visibility = VISIBLE\n',1)" "G2 no view outside"
mutate "the emoji panel hides the category strip itself" "$J/keyboard/emoji/EmojiPalettesView.java" \
    "s=s.replace('KeyboardSwitcher.getInstance().setEmojiCategoryStripShown(emoji);','if (mTabStrip != null) mTabStrip.setVisibility(emoji ? VISIBLE : GONE);')" "G2 no view outside"
mutate "LatinIME calls the strip's self-heal directly (the #776 wiring)" "$J/latin/LatinIME.java" \
    "s=s.replace('mKeyboardSwitcher.requestToolbarCheck(\"strip attached\")','mSuggestionStripView.ensureToolbar(\"strip attached\")')" "G3 ensureToolbar has no caller"
mutate "the self-heal ignores an open panel" "$J/keyboard/KeyboardSwitcher.java" \
    "s=s.replace('if (mViewState.deferToolbarCheck(trigger))','if (false)')" "G3 requestToolbarCheck consults"
mutate "the clipboard strip follows the hidden secondary strip" "$J/keyboard/KeyboardViewState.kt" \
    "s=re.sub(r'(?m)^(\s*)v\.add\(Piece\.STRIP_CONTAINER\)\n(\s*)\}', r'\1if (inputs.secondaryStripVisible) v.add(Piece.STRIP_CONTAINER)\n\2}', s, count=1)" "G4 a clipboard panel always"
mutate "panels respect 'show toolbar only' (hides the ABC row)" "$J/keyboard/KeyboardViewState.kt" \
    "s=s.replace('v.add(Piece.MAIN_FRAME); v.add(Piece.KEYBOARD_WRAPPER)','v.add(Piece.MAIN_FRAME); if (!inputs.showToolbarOnly) v.add(Piece.KEYBOARD_WRAPPER)')" "G4 every panel shows"
mutate "a hidden strip drops the close key too" "$J/keyboard/KeyboardViewState.kt" \
    "s=s.replace('if (!secondaryStripVisible) listOf(close)','if (!secondaryStripVisible) emptyList()')" "G4 clipboardStripKeys"
mutate "strip keys built once in the constructor again" "$J/keyboard/clipboard/ClipboardHistoryView.kt" \
    "s=s.replace('        fitsSystemWindows = true','        getEnabledClipboardToolbarKeys(context.prefs()).forEach { toolbarKeys.add(createToolbarKey(context, it)) }\n        fitsSystemWindows = true',1)" "G5 the constructor"
mutate "back is left to the framework" "$J/latin/LatinIME.java" \
    "s=s.replace('mKeyboardSwitcher.closePanelIfOpen(\"system back\")','false')" "G6 system back"
mutate "the toolbar key hides the window again (upstream)" "$J/keyboard/KeyboardSwitcher.java" \
    "s=s.replace('closePanelIfOpen(\"its toolbar key was pressed again\");','mLatinIME.hideWindow();')" "G6 a panel's toolbar key"
mutate "a re-inflation keeps a stale panel mode" "$J/keyboard/KeyboardSwitcher.java" \
    "s=s.replace('        mViewState.onInputViewRecreated();\n','')" "G6 a re-inflation"
mutate "CI stops running the JVM test" ".github/workflows/ship-cloud-keyboard.yml" \
    "s=s.replace('./build.sh unit','true')" "G7 Ship -> cloud-keyboard"
[ "$MUT_FAIL" -eq 0 ] || { echo "test-keyboard-view-state-exit: $MUT_FAIL mutation(s) not caught"; exit 1; }
echo
echo "test-keyboard-view-state-exit: OK (real tree green, every mutation red)"
