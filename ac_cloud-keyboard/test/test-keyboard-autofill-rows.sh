#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ Tier 3 autofill: two rows, Vault alone on row 1, Suppression Mode ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# a0_docs/eng-specs/autofill-3-tier.md. The pure rules (EditorInfo -> mode, the row matrix,
# candidates) are JVM-tested in libs:keyboard ImeAutofillTest (./build.sh unit). This holds the
# WIRING those rules need, which no JVM test can see:
#
#   K1  row 1 exists in the strip layout, ABOVE the keyboard's own rows, starts with a key glyph,
#       and is GONE by default (zero height when there is nothing from Vault);
#   K2  onInlineSuggestionsResponse puts Vault's chips on row 1 through the controller and NEVER
#       through setExternalSuggestionView (that is row 2: the old path mixed them); an empty
#       response clears row 1;
#   K3  Suppression Mode hides row 2, refuses the clipboard chip, and forces incognito
#       (InputAttributes -> SettingsValues), so nothing typed in a suppressed field is learnt;
#   K4  row 2 candidates are committed through the input connection (commitText(text, 1)), never
#       through pickSuggestionManually (which would teach the dictionary a personal value);
#   K5  the keyboard requests only AUTOFILL_PROFILE_READ (it never writes the SOT) and reads the SOT
#       off the main thread.
#
# Each assertion is then re-run against a deliberately broken copy (mutation): a check that cannot
# fail is reported as such.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")"
export ROOT
python3 - <<'PYEOF'
import os, re, sys
ROOT = os.environ["ROOT"]
KB = "ab_cloud-libs-shared/libs/keyboard/src/main"
F = {
    "layout": KB + "/res/layout/suggestions_strip.xml",
    "ime": KB + "/java/helium314/keyboard/latin/LatinIME.java",
    "ctl": KB + "/java/helium314/keyboard/latin/ImeAutofillController.kt",
    "strip": KB + "/java/helium314/keyboard/latin/suggestions/SuggestionStripView.kt",
    "attrs": KB + "/java/helium314/keyboard/latin/InputAttributes.java",
    "settings": KB + "/java/helium314/keyboard/latin/settings/SettingsValues.java",
    "manifest": "ac_cloud-keyboard/app/src/main/AndroidManifest.xml",
}

def strip_comments(s):
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    s = re.sub(r"<!--.*?-->", "", s, flags=re.S)
    return re.sub(r"(?m)//.*$", "", s)

def body(src, sig):
    i = src.find(sig)
    if i < 0: return ""
    j = src.find("{", i); d = 0
    for k in range(j, len(src)):
        d += {"{": 1, "}": -1}.get(src[k], 0)
        if d == 0: return src[i:k + 1]
    return src[i:]

def check(files, ok):
    lay, ime, ctl, strip, attrs, st, man = (strip_comments(files[k]) for k in ("layout", "ime", "ctl", "strip", "attrs", "settings", "manifest"))
    # K1
    i_row, i_tool, i_sugg = lay.find('@+id/inline_autofill_row'), lay.find('@+id/toolbar_row'), lay.find('@+id/suggestions_row')
    ok(0 <= i_row < i_tool < i_sugg, "K1 row 1 (inline_autofill_row) sits above the keyboard's own rows")
    row = lay[i_row:i_tool]
    ok('android:visibility="gone"' in row, "K1 row 1 is GONE by default (zero height with nothing from Vault)")
    ok("@drawable/ic_autofill_key" in row and "inline_autofill_container" in row, "K1 row 1 starts with the key glyph and has its own container")
    ok("@dimen/config_suggestions_strip_height" in row, "K1 row 1 keeps the strip's chip height")
    # K2
    resp = body(ime, "public boolean onInlineSuggestionsResponse(")
    ok("mImeAutofill.onInline(inlineSuggestionView" in resp, "K2 Vault's chips go to row 1 through the controller")
    ok("setExternalSuggestionView" not in resp, "K2 inline suggestions never take row 2 (no setExternalSuggestionView)")
    ok("mImeAutofill.onInline(null, 0" in resp, "K2 an empty response clears row 1")
    ok("inlineContainer.addView(view" in strip and "inlineRow.isVisible = view != null" in strip, "K2 row 1 collapses when it holds nothing")
    # K3
    ok("suggestionsRow.isVisible = !suppressed" in strip, "K3 Suppression Mode hides row 2")
    ok("strip?.setOwnCandidatesSuppressed(!rows.row2)" in ctl and "SuggestionRows.decide(" in ctl, "K3 the rows follow SuggestionRows")
    ok("if (mImeAutofill.isSuppressed()) return false;" in body(ime, "public boolean tryShowClipboardSuggestion("), "K3 no clipboard chip in Suppression Mode")
    ok("mImeAutofill.onStartInputView(editorInfo" in body(ime, "void onStartInputViewInternal("), "K3 every field start is classified")
    ok("FieldPolicy.INSTANCE.decide(editorInfo).getSuppressed()" in attrs, "K3 InputAttributes carries the suppression")
    ok("|| mInputAttributes.mAutofillSuppressed" in st[st.find("mIncognitoModeEnabled ="):st.find("mIncognitoModeEnabled =") + 400], "K3 a suppressed field is incognito: nothing learnt or recorded")
    # K4
    commit = body(ctl, "private fun commit(")
    ok("c.commitText(text, 1)" in commit, "K4 a candidate is committed with commitText(text, 1)")
    ok("pickSuggestion" not in ctl and "addToUserHistory" not in ctl and "mDictionaryFacilitator" not in ctl, "K4 a candidate never reaches the dictionary")
    ok("if (decision.suppressed) return" in commit, "K4 nothing is committed from the keyboard's row in Suppression Mode")
    neutral = body(ime, "public void setNeutralSuggestionStrip(")
    ok("mImeAutofill.showCandidates(mSuggestionStripView)" in neutral, "K4 an empty field shows SOT candidates on row 2")
    # K5
    ok("AUTOFILL_PROFILE_READ" in man and "AUTOFILL_PROFILE_WRITE" not in man, "K5 the keyboard reads the SOT and never writes it")
    ok("io.execute {" in ctl and "AutofillSotClient.profiles(ctx)" in body(ctl, "fun onStartInputView(")[body(ctl, "fun onStartInputView(").find("io.execute"):], "K5 the SOT is read off the main thread")
    mine = ctl + body(strip, "fun setAutofillCandidates(") + body(strip, "fun setInlineSuggestions(") + body(strip, "fun setOwnCandidatesSuppressed(")
    ok(not re.search(r"\bLog\.[dviwe]\(", mine), "K5 the autofill code logs nothing (no candidate, no field text)")

def run(files):
    fails = []
    def ok(c, label):
        if not c: fails.append(label)
    check(files, ok)
    return fails

files = {}
for k, rel in F.items():
    p = os.path.join(ROOT, rel)
    if not os.path.isfile(p):
        print("FAIL   %s is missing — every assertion would read an empty file" % rel); sys.exit(1)
    files[k] = open(p, encoding="utf-8").read()

fails = run(files)
for f in fails: print("FAIL   " + f)
if fails:
    print("keyboard autofill rows: %d assertion(s) failed" % len(fails)); sys.exit(1)
print("ok     keyboard autofill rows: every assertion holds on the real tree")

MUT = [
    ("inline back on row 2", "ime", "mImeAutofill.onInline(inlineSuggestionView, inlineSuggestions.size(), mSuggestionStripView);",
     "mSuggestionStripView.setExternalSuggestionView(inlineSuggestionView, true); mImeAutofill.onInline(inlineSuggestionView, inlineSuggestions.size(), mSuggestionStripView);"),
    ("row 1 always visible", "layout", '''            android:id="@+id/inline_autofill_row"
            android:orientation="horizontal"
            android:visibility="gone"''', '''            android:id="@+id/inline_autofill_row"
            android:orientation="horizontal"'''),
    ("suppression keeps row 2", "strip", "suggestionsRow.isVisible = !suppressed", "suggestionsRow.isVisible = true"),
    ("suppressed field learns", "settings", "|| mInputAttributes.mAutofillSuppressed", ""),
    ("clipboard chip on a password field", "ime", "if (mImeAutofill.isSuppressed()) return false;", ""),
    ("candidate taught to the dictionary", "ctl", "c.commitText(text, 1)", "ime.pickSuggestionManually(null)"),
    ("a candidate is logged", "ctl", "    private fun commit(text: String) {", '    private fun commit(text: String) {\n        android.util.Log.d("x", text)'),
    ("keyboard writes the SOT", "manifest", 'android:name="com.diegonmarcos.cloud.permission.AUTOFILL_PROFILE_READ" />',
     'android:name="com.diegonmarcos.cloud.permission.AUTOFILL_PROFILE_READ" />\n    <uses-permission android:name="com.diegonmarcos.cloud.permission.AUTOFILL_PROFILE_WRITE" />'),
]
dead = []
for label, k, old, new in MUT:
    if old not in files[k]:
        dead.append(label + " (target not found: the tester drifted from the code)"); continue
    m = dict(files); m[k] = m[k].replace(old, new, 1)
    if run(m): print("ok     mutation caught: " + label)
    else: dead.append(label)
for d in dead: print("FAIL   mutation SURVIVED: " + d)
if dead: sys.exit(1)
print("keyboard autofill rows: all assertions passed, %d mutation(s) caught" % len(MUT))
PYEOF
