#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════╗
# ║ wasm-purity-guard.test — prove the guard FAILS on each rule it holds  ║
# ╚══════════════════════════════════════════════════════════════════════╝
#
# #876. (1) the real tree must pass. (2) a synthetic fleet (the real guard, the real
# data file, the real ui-kit commonMain) is required to pass, then broken one rule
# at a time; each break is proven to have landed and the guard must go red NAMING
# the rule. A comment or a string that merely mentions android.* must stay green
# (the guard reads code, not prose).
#
# python3 and coreutils only; no network, no build.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-wasm-purity-guard.py"
DATA=1_cicd/src/data/wasm-migration.json
LIB=ab_cloud-libs-shared/libs/ui-kit
KT=$LIB/src/commonMain/kotlin/com/diegonmarcos/superapp/uikit/CloudKit.kt
LIB2=ab_cloud-libs-shared/libs/bottomnav
KT2=$LIB2/src/commonMain/kotlin/com/diegonmarcos/superapp/bottomnav/BottomNavBar.kt
for f in "$GUARD" "$ROOT/$DATA" "$ROOT/$KT" "$ROOT/$KT2"; do
    [ -f "$f" ] || { echo "ERROR missing source: $f — this test is unrun, not passing"; exit 1; }
done
export PYTHONDONTWRITEBYTECODE=1

FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
T="$WORK/t"

# ── (1) the real tree ───────────────────────────────────────────────────────
out="$(python3 "$GUARD" "$ROOT")"; rc=$?
if [ "$rc" -eq 0 ]; then ok "the real tree passes ($(tail -1 <<<"$out"))"
else fail "the real tree is red (rc=$rc)"; printf '%s\n' "$out" | grep FAIL; fi

# ── (2) the synthetic fleet ─────────────────────────────────────────────────
stage() {
    rm -rf "$T"; mkdir -p "$T/1_cicd/src/data" "$T/$LIB" "$T/ab_cloud-libs-shared/web" "$T/ac_cloud-fix/web/src/wasmJsMain/kotlin" "$T/ab_cloud-libs-shared/libs/other"
    cp "$ROOT/$DATA" "$T/$DATA"
    cp -r "$ROOT/$LIB/src" "$ROOT/$LIB/build.gradle" "$T/$LIB/"
    mkdir -p "$T/$LIB2/src"; cp -r "$ROOT/$LIB2/src/commonMain" "$T/$LIB2/src/"; cp "$ROOT/$LIB2/build.gradle" "$T/$LIB2/"
    printf '{"libs": ["ui-kit", "bottomnav"]}\n' > "$T/ab_cloud-libs-shared/web/web.json"
    touch "$T/ab_cloud-libs-shared/web/settings.gradle.kts" "$T/ac_cloud-fix/web/settings.gradle.kts"
    printf 'package fix\n// a comment may name android.util.Log and java.io.File and BuildConfig freely\nval s = "R.string.x android.os"\nfun main() {}\n' \
        > "$T/ac_cloud-fix/web/src/wasmJsMain/kotlin/Main.kt"
}

# mutate <label> <file> <python-edit-of-s> <expected-substring>
mutate() {
    stage
    local before after out rc
    before="$(cat "$T/$2")"
    python3 -c "import re,json,sys; p=sys.argv[1]; s=open(p).read(); $3; open(p,'w').write(s)" "$T/$2"
    after="$(cat "$T/$2")"
    if [ "$before" = "$after" ]; then fail "$1: the mutation did not land — the fixture moved"; return; fi
    out="$(python3 "$GUARD" "$T")"; rc=$?
    if [ "$rc" -eq 1 ] && grep -qF -- "$4" <<<"$out"; then ok "$1 goes red ($4)"
    else fail "$1 stayed green or named the wrong thing (rc=$rc)"; printf '%s\n' "$out" | tail -4; fi
}

stage
out="$(python3 "$GUARD" "$T")"; rc=$?
if [ "$rc" -eq 0 ]; then ok "the unbroken synthetic fleet passes, prose naming android.* included ($(tail -1 <<<"$out"))"
else fail "the unbroken synthetic fleet is red (rc=$rc)"; printf '%s\n' "$out" | grep FAIL; fi

mutate "an android import in a lib's commonMain"  "$KT" "s=s.replace('import androidx.compose.ui.Modifier','import android.util.Log\nimport androidx.compose.ui.Modifier')" "W1 $KT"
mutate "an androidx.fragment import"              "$KT" "s=s.replace('import androidx.compose.ui.Modifier','import androidx.fragment.app.Fragment\nimport androidx.compose.ui.Modifier')" "androidx.(activity|fragment|core|lifecycle|navigation|appcompat)"
mutate "an android import in bottomnav's commonMain" "$KT2" "s=s.replace('import androidx.compose.ui.Modifier','import android.os.PowerManager\nimport androidx.compose.ui.Modifier')" "W1 $KT2"
mutate "a resource id in bottomnav's commonMain"  "$KT2" "s+='\nval r = R.dimen.bottom_nav_pill_inset\n'" "R.<resource>"
mutate "bottomnav's commonMain lines go DOWN"     "$KT2" "s=s.replace('internal const val TAG_CONTENT = \"bottomnav_content\"\n','')" "W2 bottomnav"
mutate "java.io.File in commonMain"               "$KT" "s+='\nval f = java.io.File(\"x\")\n'" "java.io.File"
mutate "org.json in commonMain"                   "$KT" "s+='\nval j = org.json.JSONObject()\n'" "org.json"
mutate "BuildConfig in commonMain"                "$KT" "s+='\nval v = BuildConfig.DEBUG\n'" "BuildConfig"
mutate "a resource id in commonMain"              "$KT" "s+='\nval r = R.string.app_name\n'" "R.<resource>"
mutate "android in the wasm web root"             "ac_cloud-fix/web/src/wasmJsMain/kotlin/Main.kt" "s+='\nval c: android.content.Context? = null\n'" "W1 ac_cloud-fix/web"
mutate "commonMain lines go DOWN"                 "$KT" "s=s.replace('const val DIALOG_DISMISS: String = \"kit:dialog:dismiss\"\n','')" "W2 ui-kit"
mutate "commonMain lines go UP unrecorded"        "$KT" "s+='\nval extra = 1\n'" "raise baseline.libs.ui-kit"
stage
mkdir -p "$T/ab_cloud-libs-shared/libs/other/src/commonMain/kotlin"; echo 'package o' > "$T/ab_cloud-libs-shared/libs/other/src/commonMain/kotlin/O.kt"
out="$(python3 "$GUARD" "$T")"; rc=$?
if [ "$rc" -eq 1 ] && grep -qF "W3 other has src/commonMain but is not in" <<<"$out" && grep -qF "W4 other" <<<"$out"; then ok "a lib with commonMain, not in web.libs and without the gradle line goes red (W3, W4)"
else fail "an unlisted commonMain lib stayed green (rc=$rc)"; printf '%s\n' "$out" | tail -4; fi
mutate "the web root forgets the lib"             "ab_cloud-libs-shared/web/web.json" "s=s.replace('\"ui-kit\"','\"nope\"')" "W3 ab_cloud-libs-shared/web/web.json::libs names nope"
mutate "bottomnav's Android build stops adding commonMain" "$LIB2/build.gradle" "s=s.replace(\"srcDirs += 'src/commonMain/kotlin'\",'')" "W4 bottomnav"
mutate "the Android build stops adding commonMain" "$LIB/build.gradle" "s=s.replace(\"srcDirs += 'src/commonMain/kotlin'\",'')" "W4 ui-kit"

[ "$FAILURES" -eq 0 ] && { echo "wasm purity guard test: all rules proven"; exit 0; }
echo "wasm purity guard test: $FAILURES failure(s)"; exit 1
