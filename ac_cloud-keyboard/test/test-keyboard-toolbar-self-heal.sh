#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #776 The toolbar row is rebuilt when it goes stale or draws nothing, the ║
# ║ keyboard log stops multiplying in Download/, and Config ▸ Update hands   ║
# ║ off to the fleet Store instead of growing a second updater               ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# THE BUG. The toolbar row was built ONCE, in SuggestionStripView's init, from
# SettingsValues.mToolbarMode - and SettingsValues says HIDDEN while the keyguard
# is locked. KeyboardSwitcher.updateKeyboardTheme recreates the input view on a
# config change (night mode, rotation, density, colours), so a change that lands
# while the phone is locked builds a row of ZERO keys. After the unlock nothing
# rebuilt it: the theme path only re-attached the strip. Only a new service -
# switching to another keyboard and back - built a fresh row.
#
#   T1  the lock -> config change -> unlock -> start-input sequence, played
#       through a model whose every step is read from the SOURCE: whether locked
#       settings mean HIDDEN, whether a theme update recreates the view, whether
#       LatinIME re-checks the row on attach/start, and ToolbarStatus.rebuildReason
#       itself, transliterated branch by branch. Without the fix it ends with 0
#       icons drawn - the reported state - and the test says so.
#   T2  the self-heal guard: a row that matches its layout and draws nothing is
#       rebuilt, and every rebuild is logged at WARN (the guard never hides a bug);
#       toolbarBuiltFor is declared ABOVE the init that builds the row (Kotlin runs
#       initialisers in text order - below it, the build is reset to null).
#   T3  /api/keyboard/toolbar is served: group from build.json, op `toolbar`,
#       payload carries expected vs drawn icons and the last rebuild reason.
#   T4  LogTakeout against a MediaStore model: a copy we do not own holds the
#       plain name. The old dump inserted "(1)", "(2)"... and died at 32 with
#       "Failed to build unique file"; the device had 31. Now: one own copy,
#       rewritten in place, and the 31 left behind are cleaned up.
#   T5  Config ▸ Update + Restart, and the #763 ratchet: no libs:updater here.
#   T6  the pref-change toolbar refresh touches Views on the main thread.
#
# SELF-PROVING. After the real tree passes, the tester copies what it reads,
# breaks one thing at a time and requires the named assertion to go red.
# python3 and coreutils only; a missing source file is fatal, never a pass.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
J=ab_cloud-libs-shared/libs/keyboard/src/main/java/helium314/keyboard
FILES=(
    "$J/latin/suggestions/SuggestionStripView.kt"
    "$J/latin/suggestions/ToolbarStatus.kt"
    "$J/latin/LatinIME.java"
    "$J/keyboard/KeyboardSwitcher.java"
    "$J/latin/settings/SettingsValues.java"
    "$J/latin/utils/LogTakeout.kt"
    "$J/latin/utils/KeyboardUpdate.kt"
    "$J/latin/utils/ToolbarUtils.kt"
    "$J/settings/screens/MainSettingsScreen.kt"
    "$J/settings/screens/UpdateScreen.kt"
    "$J/settings/SettingsNavHost.kt"
    ab_cloud-libs-shared/libs/keyboard/build.gradle
    ac_cloud-keyboard/build.json
    ac_cloud-keyboard/app/build.gradle
    ac_cloud-keyboard/app/src/main/java/com/diegonmarcos/cloudkeyboard/App.kt
    aa_cloud-superapp/build.json
    aa_cloud-superapp/app/src/main/AndroidManifest.xml
    aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/MainActivity.kt
)
for f in "${FILES[@]}"; do
    [ -f "$ROOT/$f" ] || { echo "FAIL   $ROOT/$f is missing — every assertion below would read an empty file and pass"; exit 1; }
done

checks() {
python3 - "$ROOT" "$J" <<'PYEOF'
import json, re, sys
root, J = sys.argv[1], sys.argv[2]
def rd(p): return open(f"{root}/{p}", encoding="utf-8").read()
SV = rd(f"{J}/latin/suggestions/SuggestionStripView.kt")
TS = rd(f"{J}/latin/suggestions/ToolbarStatus.kt")
IME = rd(f"{J}/latin/LatinIME.java")
KS = rd(f"{J}/keyboard/KeyboardSwitcher.java")
SVAL = rd(f"{J}/latin/settings/SettingsValues.java")
LT = rd(f"{J}/latin/utils/LogTakeout.kt")
KU = rd(f"{J}/latin/utils/KeyboardUpdate.kt")
TU = rd(f"{J}/latin/utils/ToolbarUtils.kt")
MS = rd(f"{J}/settings/screens/MainSettingsScreen.kt")
US = rd(f"{J}/settings/screens/UpdateScreen.kt")
NAV = rd(f"{J}/settings/SettingsNavHost.kt")
KBG = rd("ab_cloud-libs-shared/libs/keyboard/build.gradle")
BJ = json.loads(rd("ac_cloud-keyboard/build.json"))
APPG = rd("ac_cloud-keyboard/app/build.gradle")
APP = rd("ac_cloud-keyboard/app/src/main/java/com/diegonmarcos/cloudkeyboard/App.kt")
SBJ = rd("aa_cloud-superapp/build.json")
SMAN = rd("aa_cloud-superapp/app/src/main/AndroidManifest.xml")

fails = 0
def ok(m): print(f"ok     {m}")
def bad(m):
    global fails; fails += 1; print(f"FAIL   {m}")
def check(c, m): ok(m) if c else bad(m)

def body(src, head):
    """The brace-balanced body following the first match of regex `head` ('' if absent)."""
    m = re.search(head, src)
    if not m: return ""
    i = src.find("{", m.end() - 1)
    if i < 0: return ""
    d = 0
    for k in range(i, len(src)):
        d += {"{": 1, "}": -1}.get(src[k], 0)
        if d == 0: return src[i:k + 1]
    return ""

# ── facts the T1 model is driven by ─────────────────────────────────────────
locked_hides = re.search(r"mToolbarMode\s*=\s*mIsLocked\s*\?\s*ToolbarMode\.HIDDEN", SVAL) is not None
theme = body(KS, r"public void updateKeyboardTheme\(")
theme_recreates = re.search(r"if \(themeUpdated\)\s*\{[^}]*setInputView\(onCreateInputView\(", theme, re.S) is not None
def code(src): return re.sub(r"//[^\n]*", "", src)  # a commented-out call is not a call
start = code(body(IME, r"void onStartInputViewInternal\("))
attach = code(body(IME, r"public void updateSuggestionStripView\("))
start_ensures = "mSuggestionStripView.ensureToolbar(" in start
attach_ensures = "mSuggestionStripView.ensureToolbar(" in attach
ensure = body(SV, r"fun ensureToolbar\(")
measure = body(SV, r"fun measureToolbar\(")
ensure_uses_rule = ("ToolbarStatus.rebuildReason(" in measure and "measureToolbar(" in ensure
                    and "buildToolbar(" in ensure)

# ToolbarStatus.rebuildReason, transliterated branch by branch into Python.
rule_src = re.search(r"fun rebuildReason\([^)]*\)[^=]*=\s*when\s*\{(.*?)\n    \}", TS, re.S)
branches = []
for line in (rule_src.group(1).splitlines() if rule_src else []):
    m = re.match(r"\s*(.+?)\s*->\s*(.+?)\s*$", line)
    if not m: continue
    cond, out = m.groups()
    py = (cond.replace("builtFor == null", "builtFor is None").replace("&&", " and ")
              .replace("||", " or ").replace("else", "True"))
    branches.append((py, "null" if out == "null" else out.strip('"')))

class L:  # a Layout: mode + the enabled keys of both rows
    def __init__(s, mode, first, second): s.mode, s.first, s.second = mode, first, second
    @property
    def expected(s): return 0 if s.mode == "HIDDEN" else len(s.first)
def rebuild_reason(builtFor, now, drawn):
    for cond, out in branches:
        if eval(cond, {}, {"builtFor": builtFor, "now": now, "drawn": drawn}):
            return None if out == "null" else out
    return None

KEYS = [f"K{i}" for i in range(22)]

def play():
    """lock -> config change -> unlock -> onStartInputView; returns (drawn, reasons)."""
    s = {"locked": False, "built": None, "drawn": 0, "attached": False, "reasons": []}
    def mode(): return "HIDDEN" if (locked_hides and s["locked"]) else "TOOLBAR_KEYS"
    def build(m):  # SuggestionStripView construction / buildToolbar
        s["built"] = L(m, KEYS, ["SETTINGS"]); s["drawn"] = s["built"].expected
    def ensure(trigger):
        if not ensure_uses_rule: return
        now = L(mode(), KEYS, ["SETTINGS"])
        r = rebuild_reason(s["built"], now, s["drawn"])
        if r: s["reasons"].append(r); build(now.mode)
    def attach_strip():  # LatinIME.updateSuggestionStripView: null while HIDDEN
        s["attached"] = mode() != "HIDDEN"
        if s["attached"] and attach_ensures: ensure("strip attached")
    build(mode()); attach_strip()                  # keyboard first shown, unlocked
    s["locked"] = True                             # screen locks
    if theme_recreates: build(mode()); attach_strip()   # night-mode switch while locked
    s["locked"] = False                            # unlock
    attach_strip()                                 # updateKeyboardTheme re-attaches
    if s["attached"] and start_ensures: ensure("onStartInputView")
    return s["drawn"], s["reasons"]

print("== T1 the locked-config-change sequence ends with a full toolbar ==")
check(locked_hides, "T1 premise: locked settings mean ToolbarMode.HIDDEN (SettingsValues)")
check(theme_recreates, "T1 premise: a theme update recreates the input view (KeyboardSwitcher.updateKeyboardTheme)")
check(len(branches) >= 3, f"T1 ToolbarStatus.rebuildReason parsed ({len(branches)} branches)")
drawn, reasons = play()
check(drawn == len(KEYS), f"T1 after lock/config-change/unlock/start the row draws {drawn}/{len(KEYS)} icons"
      + ("" if drawn else " — THE REPORTED EMPTY TOOLBAR"))
check(any("settings now say" in r for r in reasons),
      f"T1 the rebuild names the root cause (mode mismatch), not just a self-heal: {reasons}")

print("== T2 the self-heal guard ==")
check(rebuild_reason(L("TOOLBAR_KEYS", KEYS, []), L("TOOLBAR_KEYS", KEYS, []), 0) is not None,
      "T2 a row matching its layout that draws 0 of 22 icons is rebuilt")
check(rebuild_reason(L("TOOLBAR_KEYS", KEYS, []), L("TOOLBAR_KEYS", KEYS, []), 22) is None,
      "T2 a healthy row is left alone (no rebuild loop)")
check(rebuild_reason(L("HIDDEN", KEYS, []), L("HIDDEN", KEYS, []), 0) is None,
      "T2 a toolbar the user hid is not 'healed'")
check(rebuild_reason(L("TOOLBAR_KEYS", KEYS, []), L("TOOLBAR_KEYS", KEYS[:-1], []), 22) is not None,
      "T2 a changed key list rebuilds the live row")
check(re.search(r"Log\.w\(TAG,[^\n]*\$reason", ensure) is not None, "T2 every rebuild is logged at WARN with its reason")
check(re.search(r"ToolbarStatus\.built\(", body(SV, r"private fun buildToolbar\(")) is not None,
      "T2 every build is recorded for the debug API")
decl = SV.find("private var toolbarBuiltFor")
inits = [m.start() for m in re.finditer(r"\n    init \{", SV)]
build_init = next((i for i in inits if "buildToolbar(" in body(SV[i:], r"init")), -1)
check(decl >= 0 and build_init >= 0 and decl < build_init,
      "T2 toolbarBuiltFor is declared above the init that builds the row")
check(len(re.findall(r"buildRow\(toolbar,", SV)) == len(re.findall(r"buildRow\(toolbar,", body(SV, r"private fun buildToolbar\("))) > 0,
      "T2 the first row is built only in buildToolbar (one builder for init and rebuild)")
check(start_ensures, "T2 LatinIME re-checks the row on every onStartInputView")

print("== T3 /api/keyboard/toolbar ==")
group = BJ.get("debug_api", {}).get("group")
check(group == "keyboard", f"T3 build.json debug_api.group = {group!r}")
check('buildJson.debug_api.group' in APPG and "DEBUG_API_GROUP" in APPG, "T3 the group is baked from build.json, not a literal")
check(re.search(r'AppDebugServer\.route\(\s*BuildConfig\.DEBUG_API_GROUP', APP) is not None
      and re.search(r'op == "toolbar"\)\s*ToolbarStatus\.json\(\)', APP) is not None,
      "T3 App registers op `toolbar` -> ToolbarStatus.json()")
js = body(TS, r"fun json\(")
check("measureToolbar(" in js and "ensureToolbar(" not in js,
      "T3 the query measures and never rebuilds (asking cannot repair the evidence)")
for k in ("expected_icons", "drawn_icons", "last_rebuild_reason", "would_rebuild", "self_heals", "live"):
    check(f'"{k}"' in js, f"T3 the payload carries {k}")

print("== T4 LogTakeout against a MediaStore model ==")
wl = body(LT, r"private fun writeLatest\(")
own_fn = body(LT, r"internal fun isOwnCopy\(")
rx = re.search(r'Regex\("\$\{Regex\.escape\(base\)\}(.*?)\$\{Regex\.escape\(ext\)\}"\)', own_fn)
variant = rx.group(1).replace("\\\\", "\\") if rx else None
queries_own = "cr.query(" in wl and "isOwnCopy(" in wl
dedups = re.search(r"own\.drop\(1\)\.forEach\s*\{[^}]*cr\.delete", wl) is not None
rewrites = 'openOutputStream(uri, "wt")' in wl
check(rewrites, 'T4 the kept copy is rewritten in place ("wt", so a shorter log leaves no tail)')
FN = "cloud-keyboard-log.log"; BASE, EXT = "cloud-keyboard-log", ".log"
def is_own(name):
    return name == FN or (variant is not None and re.fullmatch(re.escape(BASE) + variant + re.escape(EXT), name) is not None)
class Store:
    def __init__(s): s.rows, s.t, s.nid = [], 0, 0
    def add(s, name, owner): s.nid += 1; s.t += 1; s.rows.append({"id": s.nid, "name": name, "owner": owner, "t": s.t})
    def insert(s, name):
        taken = {r["name"] for r in s.rows}
        for cand in [name] + [f"{BASE} ({n}){EXT}" for n in range(1, 32)]:
            if cand not in taken: return s.add(cand, "me")
        raise RuntimeError(f"Failed to build unique file: /storage/emulated/0/Download/{name}")
    def own(s): return [r for r in s.rows if r["owner"] == "me"]
def dump(st):
    if queries_own:
        own = [r for r in st.own() if r["name"].startswith(BASE) and is_own(r["name"])]
        own.sort(key=lambda r: (r["name"] == FN, r["t"]), reverse=True)
        if dedups:
            for r in own[1:]: st.rows.remove(r)
        if own:
            st.t += 1; own[0]["t"] = st.t
        else:
            st.insert(FN)
    else:  # delete the exact name only, then insert
        st.rows = [r for r in st.rows if not (r["owner"] == "me" and r["name"] == FN)]
        st.insert(FN)
st = Store(); st.add(FN, "previous-install")
err = None
try:
    for _ in range(40): dump(st)
except RuntimeError as e: err = str(e)
check(err is None and len(st.own()) == 1,
      f"T4 40 dumps beside a foreign {FN}: {len(st.own())} own copy, " + (err or "no failure"))
st = Store(); st.add(FN, "previous-install")
for n in range(1, 32): st.add(f"{BASE} ({n}){EXT}", "me")    # the phone today
err = None
try: dump(st)
except RuntimeError as e: err = str(e)
check(err is None and len(st.own()) == 1,
      f"T4 the device state (31 numbered copies) is cleaned to {len(st.own())} own copy, " + (err or "no failure"))
check("Log.e(TAG" in wl, "T4 landing in the private fallback is logged at ERROR, not WARN")

print("== T5 Config ▸ Update and Restart ==")
mods = BJ.get("modules", {})
check("libs:updater" not in mods, "T5 #763: this app compiles no libs:updater (the Store updates it)")
up = BJ.get("update", {})
check(all(up.get(k) for k in ("store_package", "store_activity", "store_extras", "fallback_url")),
      "T5 build.json::update declares the Store hand-off")
check("kbBuildJson.update" in KBG and "UPDATE_B64" in KBG and "BuildConfig.UPDATE_B64" in KU,
      "T5 the hand-off reaches the code through BuildConfig, not a literal")
target = (up.get("store_extras") or {}).get("shortcut_action", "")
page = target.rsplit("/", 1)[-1]
check(target.startswith("page:") and f'"id": "{page}"' in SBJ, f"T5 {target} is a page the SuperApp declares")
act = up.get("store_activity", "")
check(act.startswith(up.get("store_package", "?") + ".") and f'android:name=".{act.rsplit(".", 1)[-1]}"' in SMAN,
      f"T5 {act} is an activity in the SuperApp manifest")
check("github.com" not in MS and "com.diegonmarcos" not in MS, "T5 MainSettingsScreen holds no URL or package literal")
check(re.search(r"if \(KeyboardUpdate\.enabled\)", MS) is not None and "onClick = onClickUpdate" in MS,
      "T5 the Update entry opens the Update page, gated on build.json")
check("composable(SettingsDestination.Update)" in NAV and "UpdateScreen(" in NAV, "T5 the Update page is routed")
check("KeyboardUpdate.openStore(" in US and "KeyboardUpdate.restart(" in US, "T5 the page offers the Store hand-off and Restart")
rs = body(KU, r"fun restart\(")
check(re.search(r"LogTakeout\.dump\(.*Process\.killProcess\(Process\.myPid\(\)\)", rs, re.S) is not None,
      "T5 Restart dumps the log, then kills the process (the system re-binds the IME)")

print("== T6 pref-change refresh on the main thread ==")
pc = body(TU, r"fun setToolbarButtonsActivatedStateOnPrefChange\(")
check("GlobalScope.launch(Dispatchers.Main)" in pc, "T6 toolbar buttons are touched on Dispatchers.Main")

print(f"== {fails} failed ==")
sys.exit(1 if fails else 0)
PYEOF
}

if [ -n "${SELF_HEAL_NO_MUTATIONS:-}" ]; then checks; exit $?; fi

echo "== the real tree =="
checks; rc=$?
[ "$rc" -eq 0 ] || { echo "test-keyboard-toolbar-self-heal: the real tree is RED"; exit 1; }

# mutate <label> <file> <python-edit-of-s> <assertion that must go red>
MUT_FAIL=0
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
mutate() {
    rm -rf "$WORK/t"; mkdir -p "$WORK/t"
    (cd "$ROOT" && cp --parents -t "$WORK/t" "${FILES[@]}")
    local before; before="$(cat "$WORK/t/$2")"
    python3 -c "import re,sys; p=sys.argv[1]; s=open(p).read(); $3; open(p,'w').write(s)" "$WORK/t/$2"
    if [ "$before" = "$(cat "$WORK/t/$2")" ]; then echo "FAIL   mutation '$1' did not land — the fixture moved"; MUT_FAIL=$((MUT_FAIL + 1)); return; fi
    local out; out="$(CLOUD_ANDROID_ROOT="$WORK/t" SELF_HEAL_NO_MUTATIONS=1 bash "$SELF" 2>&1)"
    if grep -q "^FAIL   $4" <<<"$out"; then echo "ok     mutation '$1' turns $4 red"
    else echo "FAIL   mutation '$1' left $4 green"; MUT_FAIL=$((MUT_FAIL + 1)); fi
}
echo "== mutations =="
mutate "LatinIME never re-checks the row (the pre-fix state)" "$J/latin/LatinIME.java" \
    "s=s.replace('mSuggestionStripView.ensureToolbar(','// mSuggestionStripView.ensureToolbar(')" "T1 after lock"
mutate "the mode-mismatch branch is dropped" "$J/latin/suggestions/ToolbarStatus.kt" \
    "s=re.sub(r'\n\s*builtFor\.mode != now\.mode ->[^\n]*', '', s)" "T1 the rebuild names"
mutate "the self-heal branch is dropped" "$J/latin/suggestions/ToolbarStatus.kt" \
    "s=re.sub(r'\n\s*now\.expected > 0 && drawn == 0 ->[^\n]*', '', s)" "T2 a row matching"
mutate "rebuilds go unlogged" "$J/latin/suggestions/SuggestionStripView.kt" \
    "s=s.replace('Log.w(TAG, \"toolbar rebuild on','Log.d(TAG, \"toolbar rebuild on')" "T2 every rebuild is logged"
mutate "toolbarBuiltFor declared below the building init" "$J/latin/suggestions/SuggestionStripView.kt" \
    "d='    private var toolbarBuiltFor: ToolbarStatus.Layout? = null\n'; s=s.replace(d,'',1); s=s.replace('    private lateinit var listener: Listener\n', d+'    private lateinit var listener: Listener\n',1)" \
    "T2 toolbarBuiltFor is declared"
mutate "onStartInputView no longer re-checks" "$J/latin/LatinIME.java" \
    "s=s.replace('mSuggestionStripView.ensureToolbar(restarting','mSuggestionStripView.toString(); //(restarting')" "T2 LatinIME re-checks"
mutate "the debug op is renamed" ac_cloud-keyboard/app/src/main/java/com/diegonmarcos/cloudkeyboard/App.kt \
    "s=s.replace('op == \"toolbar\"','op == \"tb\"')" "T3 App registers"
mutate "the debug query repairs the row it reports on" "$J/latin/suggestions/ToolbarStatus.kt" \
    "s=s.replace('view.measureToolbar(\"debug api\")','view.ensureToolbar(\"debug api\")')" "T3 the query measures"
mutate "LogTakeout matches the exact name only (the pre-fix rule)" "$J/latin/utils/LogTakeout.kt" \
    "s=re.sub(r'return name == fileName \|\|[^\n]*', 'return name == fileName', s)" "T4 40 dumps"
mutate "own duplicates are no longer deleted" "$J/latin/utils/LogTakeout.kt" \
    "s=s.replace('own.drop(1).forEach','own.drop(1).take(0).forEach')" "T4 the device state"
mutate "plain \"w\" (no truncate)" "$J/latin/utils/LogTakeout.kt" \
    "s=s.replace('openOutputStream(uri, \"wt\")','openOutputStream(uri, \"w\")')" "T4 the kept copy"
mutate "the keyboard compiles libs:updater" ac_cloud-keyboard/build.json \
    "import json; d=json.loads(s); d['modules']['libs:updater']={'dir':'../ab_cloud-libs-shared/libs/updater'}; s=json.dumps(d)" "T5 #763"
mutate "the Store page id drifts" ac_cloud-keyboard/build.json \
    "s=s.replace('page:config/store-cloud','page:config/store-gone')" "T5 page:config"
mutate "Restart skips the log dump" "$J/latin/utils/KeyboardUpdate.kt" \
    "s=s.replace('runCatching { LogTakeout.dump(activity) }','')" "T5 Restart dumps"
mutate "the pref refresh goes back to a worker thread" "$J/latin/utils/ToolbarUtils.kt" \
    "s=s.replace('GlobalScope.launch(Dispatchers.Main)','GlobalScope.launch')" "T6 toolbar buttons"

echo
[ "$MUT_FAIL" -eq 0 ] && echo "test-keyboard-toolbar-self-heal: OK (real tree green, every mutation red)" \
    || { echo "test-keyboard-toolbar-self-heal: $MUT_FAIL mutation(s) not caught"; exit 1; }
