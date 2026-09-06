#!/usr/bin/env bash
# cloud-keyboard translate bar: static proof that the reliability chain is
# intact WITHOUT a gradle build (this runner cannot build). Each check is a
# regression that made the bar "flaky" once:
#
#   T1  engine reply contract {source, text[, why]} on BOTH engines + the
#       orchestrator reads slot 2 (errors used to collapse to {"und",""})
#   T2  AIDL client recovers a dead binding and retries a failed bind on use
#   T3  every ML Kit await inside translateBlocking is bounded
#   T4  long-press one-shot: keyboard-language hint + stale-field guard + supersede
#   T5  bar: debounce, engine-side generation, LRU cache, not-connected shown on open
#   T6  settings: every TranslatePrefs key is rendered by the settings screen,
#       registered in the container, and every R.string it references exists
#   T7  AIDL: translateFrom stays LAST (transaction codes follow declaration
#       order — an older companion must still answer translate()), both copies equal
#   T8  ownership rule: no "HeliBoard" wording in the translate-owned files
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$APP/.."
LIBS="$ROOT/ab_cloud-libs-shared"
T="$LIBS/libs/translate/src/main/java/com/diegonmarcos/superapp/translate"
M="$LIBS/libs/translate-mlkit/src/main/java/com/diegonmarcos/superapp/translate"
K="$LIBS/libs/keyboard/src/main"
J="$K/java/helium314/keyboard"
AIDL_CLIENT="$ROOT/ac_cloud-keyboard/app/src/main/java/com/diegonmarcos/cloudkeyboard/AidlTranslateEngineClient.kt"
SERVICE="$LIBS/keyboard-engines/app/src/main/java/com/diegonmarcos/cloudkeyboardlibs/TranslateEngineService.java"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }
lacks() { grep -q -- "$2" "$1" && bad "$3 ($1)" || ok "$3"; }

echo "== cloud-keyboard translate: reliability chain, settings, AIDL contract =="

# T1 reply contract
has "$SERVICE" 'private static String\[\] failed(Throwable t)' "T1 service maps exceptions to a 3-slot reply"
has "$SERVICE" 'return new String\[\]{"und", "", ' "T1 service reply carries the reason in slot 2"
lacks "$SERVICE" 'return new String\[\]{"und", ""};' "T1 service never squashes an error to {und,''}"
has "$M/LocalTranslateEngineClient.kt" 'private fun failed(e: Exception) = arrayOf("und", "", ' "T1 in-process client carries the reason"
has "$T/Translator.kt" 'fun Array<String>.reason(): String? = getOrNull(2)' "T1 Translator reads slot 2"
has "$T/Translator.kt" 'why != null -> Result(null, null, why)' "T1 Translator surfaces the engine reason first"
has "$M/TranslateEngine.java" 'timed out after " + timeoutS + " s' "T1 ML Kit timeouts name the stage + wait"
has "$M/TranslateEngine.java" 'Unsupported source language' "T1 explicit unsupported source is an error, not 'und'"

# T2 bind recovery
has "$AIDL_CLIENT" 'override fun onBindingDied' "T2 onBindingDied handled"
has "$AIDL_CLIENT" 'unbindService(this)' "T2 dead binding is unbound before rebinding"
has "$AIDL_CLIENT" 'override fun onNullBinding' "T2 null binder logged"
has "$AIDL_CLIENT" 'private fun engineOrRebind()' "T2 use-time rebind exists"
has "$AIDL_CLIENT" 'const val REBIND_MS' "T2 rebind is rate-limited"
has "$AIDL_CLIENT" 'Translator.NOT_CONNECTED' "T2 not-connected reason is the shared constant"
for fn in 'override fun translate(' 'override fun translateFrom(' 'override fun supportedLanguages()' 'override fun isConnected()'; do
  awk -v f="$fn" 'index($0,f){p=1} p&&/engineOrRebind\(\)/{found=1} p&&/^    }|^    override fun|^    private companion/{if(index($0,f)==0){exit}} END{exit !found}' "$AIDL_CLIENT" \
    && ok "T2 $fn goes through engineOrRebind()" || bad "T2 $fn bypasses engineOrRebind()"
done

# T3 bounded awaits: inside translateBlocking every Tasks.await has a TimeUnit; only prefetchDefaults may be unbounded
n_await=$(awk '/translateBlocking\(Context context, String text, String sourceTag/,/^    }$/' "$M/TranslateEngine.java" | grep -c 'Tasks.await(')
n_bound=$(awk '/translateBlocking\(Context context, String text, String sourceTag/,/^    }$/' "$M/TranslateEngine.java" | grep -c 'TimeUnit.SECONDS)')
[ "$n_await" = "$n_bound" ] && [ "$n_await" -ge 3 ] && ok "T3 $n_await/$n_await awaits in translateBlocking are bounded" || bad "T3 awaits=$n_await bounded=$n_bound"
for c in ID_TIMEOUT_S DOWNLOAD_TIMEOUT_S TRANSLATE_TIMEOUT_S; do has "$M/TranslateEngine.java" "static final long $c" "T3 $c declared"; done

# T4 one-shot
has "$T/Translator.kt" 'val hint = keyboardLang.takeIf { it.isNotEmpty() && it != target }' "T4 one-shot passes the keyboard language as detection hint"
has "$T/Translator.kt" 'if (now != text) { toast(appCtx, "Field changed while translating' "T4 stale-field guard before replaceInField"
has "$T/Translator.kt" 'val gen = oneShot.incrementAndGet()' "T4 newer long-press supersedes"

# T5 bar
has "$T/TranslateBarView.kt" 'private const val DEBOUNCE_MS' "T5 debounce constant"
has "$T/TranslateBarView.kt" 'ui.postDelayed(job, DEBOUNCE_MS)' "T5 debounce applied"
has "$T/Translator.kt" 'if (gen != generation.get()) return@execute' "T5 superseded live request skips the engine"
has "$T/Translator.kt" 'override fun removeEldestEntry' "T5 LRU cache"
has "$T/TranslateBarView.kt" '!client.isConnected() -> Translator.NOT_CONNECTED' "T5 not-connected shown on open"
has "$T/TranslateBarView.kt" 'fun swap()' "T5 swap direction"
has "$T/TranslateBarView.kt" 'TranslatePrefs.recentPairs(context)' "T5 recent pairs in the picker"
for a in 'chip("Insert")' 'chip("Replace")' 'chip("Copy")' 'chip("Clear")'; do has "$T/TranslateBarView.kt" "$a" "T5 action $a"; done

# T6 settings surface
for key in $(grep -o 'const val KEY_[A-Z_]*' "$T/TranslatePrefs.kt" | awk '{print $3}' | grep -v KEY_RECENT_PAIRS); do
  has "$J/settings/screens/TranslationInfoScreen.kt" "TranslatePrefs.$key" "T6 settings screen renders $key"
done
has "$J/settings/SettingsContainer.kt" 'createTranslateSettings(context)' "T6 container registers translate settings"
grep -o 'R\.string\.[a-z_0-9]*' "$J/settings/screens/TranslationInfoScreen.kt" | sort -u | sed 's/R.string.//' | while read -r s; do
  grep -q "name=\"$s\"" "$K/res/values/strings.xml" || echo "$s"
done > /tmp/kb-tr-missing.$$
[ -s /tmp/kb-tr-missing.$$ ] && bad "T6 missing strings: $(tr '\n' ' ' < /tmp/kb-tr-missing.$$)" || ok "T6 every referenced R.string exists"
rm -f /tmp/kb-tr-missing.$$
has "$J/latin/LatinIME.java" 'mTranslateBar.onShown()' "T6 LatinIME calls onShown on every open"
has "$J/latin/LatinIME.java" 'mTranslateBar.bind(this::getCurrentInputConnection, kbLang' "T6 LatinIME re-binds with the subtype language"

# T7 AIDL: translateFrom last, copies identical
mapfile -t AIDLS < <(find "$ROOT/ac_cloud-keyboard" "$LIBS/keyboard-engines" -name ITranslateEngine.aidl -not -path '*/build/*' | sort)
[ "${#AIDLS[@]}" -ge 2 ] && ok "T7 ${#AIDLS[@]} AIDL copies found" || bad "T7 expected client+service AIDL copies, found ${#AIDLS[@]}"
for a in "${AIDLS[@]}"; do
  [ "$(grep -E '^\s*(String\[\]|List<String>) ' "$a" | tail -1 | grep -c translateFrom)" = 1 ] && ok "T7 translateFrom is last in ${a#$ROOT/}" || bad "T7 translateFrom not last in $a"
done
[ "${#AIDLS[@]}" -ge 2 ] && { cmp -s "${AIDLS[0]}" "${AIDLS[1]}" && ok "T7 AIDL copies identical" || bad "T7 AIDL copies differ"; }

# T8 ownership wording
for f in "$T"/*.kt "$M"/*.kt "$M"/*.java "$AIDL_CLIENT" "$SERVICE"; do
  lacks "$f" 'HeliBoard\|sync-heliboard' "T8 no HeliBoard wording in ${f##*/}"
done

echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" -eq 0 ]
