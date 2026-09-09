#!/usr/bin/env bash
# cloud-mail Text tools: static proof of the wiring, WITHOUT a gradle build (this
# runner cannot build) and without an APK on a device.
#
#   C1  Configs ▸ Text declares three entries and each resolves to a REAL destination
#       the keyboard allowlists - not a name that resolves to nothing
#   C2  mail owns no copy of these pages and no second store for their settings
#   R1  Enhance resolves to the OpenRouter path and Translate to the translation
#       library, on BOTH sides of the binder. Fails if the two are crossed either way
#   R2  the two engines stay apart: neither method reaches the other's
#   K1  the credential is never reachable from mail - no second copy of the key path
#   B1  a RECEIVED message's stored body is not a write target of either tool
#   S1  progress and a real error, not a silent no-op
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$APP/.."
LIBS="$ROOT/ab_cloud-libs-shared"
TT="$LIBS/libs/text-tools/src/main"
KB="$LIBS/libs/keyboard/src/main/java"
SVC="$KB/com/diegonmarcos/superapp/texttools/TextToolsService.kt"
NAV="$KB/helium314/keyboard/settings/SettingsNavHost.kt"
ACT="$KB/helium314/keyboard/settings/SettingsActivity.kt"
UI="$APP/app/src/main/kotlin/app/sterna/ui"
SEC="$UI/settings/TextToolsSection.kt"
RUN="$UI/text/TextToolRun.kt"
PANEL="$UI/text/TextToolPanel.kt"
SCOPE="$UI/text/TextToolScope.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }
lacks() { grep -q -- "$2" "$1" && bad "$3 ($1)" || ok "$3"; }

echo "== cloud-mail Text tools: config entries, engine routing, credential, read-only body =="

# ── C1 the three entries exist and resolve to real destinations ──
# The COUNT is not the property; the AGREEMENT is. Mail's entries and the keyboard's allowlist have
# to be the same set, so neither a row that opens nothing nor a route reachable from outside that
# nobody meant to expose can pass. Written without a magic number so adding a fifth entry needs a
# real change here, not a number bumped -- Text Resume was the fourth.
n=$(grep -c 'screen = "' "$SEC")
[ "$n" -ge 1 ] && ok "C1 the Text section declares $n entries" || bad "C1 the Text section declares nothing"
for want in $(grep -o 'screen = "[a-z_]*"' "$SEC" | sed 's/screen = "//;s/"//'); do
  grep -q "screen = \"$want\"" "$SEC" \
    && ok "C1 entry '$want' declared" || bad "C1 entry '$want' missing"
  # THE point of the assertion: the name must be one the keyboard actually allowlists.
  # A row naming a screen nobody serves opens the keyboard's front page and looks fine.
  grep -qE "^\s*\"$want\" to " "$NAV" \
    && ok "C1 '$want' resolves to a real keyboard destination" \
    || bad "C1 '$want' is not in SettingsDestination.external - the row would open nothing"
done
# the allowlist may not be wider than what mail asks for
ext=$(awk '/val external: Map<String, String> = mapOf\(/,/^    \)/' "$NAV" | grep -cE '^\s*"[a-z_]+" to ')
[ "$ext" = "$n" ] && ok "C1 the keyboard allowlists exactly the $n mail asks for" \
  || bad "C1 allowlist holds $ext entries, mail declares $n"
has "$ACT" 'const val EXTRA_OPEN_AT = "open_at"' "C1 the activity takes the extra mail sends"
has "$SEC" 'EXTRA_OPEN_AT = "open_at"' "C1 mail sends the extra the activity takes"
has "$ACT" 'SettingsDestination.externalRoute(intent' "C1 the extra goes through the allowlist, not straight to the NavHost"

# ── C2 no rebuilt lookalikes, and no second settings store ──
for page in AiRoutingScreen TextEnhanceScreen TranslationInfoScreen; do
  found=$(grep -rl "fun $page" "$APP/app/src" 2>/dev/null | wc -l)
  [ "$found" = 0 ] && ok "C2 mail owns no copy of $page" || bad "C2 mail has rebuilt $page"
done
for key in PREF_AI_PROVIDER PREF_AI_TOKEN_PREFIX PREF_AI_MODEL_PREFIX PREF_ENHANCE_STYLE translate_default_target; do
  grep -rq "$key" "$APP/app/src" 2>/dev/null \
    && bad "C2 mail names $key - a second store for a setting the keyboard owns" \
    || ok "C2 mail never names $key"
done

# ── R1/R2 routing, on both sides, and not crossed ──
# service side: enhance -> AiRouter/TextEnhancer, translate -> Translator
enh=$(awk '/override fun enhance\(/,/^        }$/' "$SVC")
tr=$(awk '/override fun translate\(/,/^        }$/' "$SVC")
case "$enh" in *TextEnhancer.rewrite*) ok "R1 service enhance() goes through the OpenRouter rewriter" ;;
  *) bad "R1 service enhance() does not reach TextEnhancer/AiRouter" ;; esac
case "$enh" in *Translator.*|*TranslateEngines.*) bad "R2 service enhance() reaches the TRANSLATE engine - the two are crossed" ;;
  *) ok "R2 service enhance() never touches the translate engine" ;; esac
case "$tr" in *Translator.translateNow*) ok "R1 service translate() goes through the translation library" ;;
  *) bad "R1 service translate() does not reach Translator" ;; esac
case "$tr" in *AiRouter.*|*TextEnhancer.*) bad "R2 service translate() reaches the OPENROUTER engine - the two are crossed" ;;
  *) ok "R2 service translate() never touches the LLM provider" ;; esac
# app side: the enum maps one-to-one, in the one place it is mapped
when=$(awk '/when \(tool\) \{/,/^                \}$/' "$RUN")
case "$when" in *"TextTool.ENHANCE -> client.enhance"*) ok "R1 mail routes ENHANCE to enhance()" ;;
  *) bad "R1 mail does not route ENHANCE to enhance()" ;; esac
case "$when" in *"TextTool.TRANSLATE -> client.translate"*) ok "R1 mail routes TRANSLATE to translate()" ;;
  *) bad "R1 mail does not route TRANSLATE to translate()" ;; esac
case "$when" in *"TextTool.ENHANCE -> client.translate"*|*"TextTool.TRANSLATE -> client.enhance"*)
  bad "R2 mail has the two tools crossed" ;;
  *) ok "R2 mail's two tools are not crossed" ;; esac
n=$(grep -c "when (tool)" "$RUN")
[ "$n" = 1 ] && ok "R2 the pairing is made in exactly one place" || bad "R2 tool->engine mapped in $n places"

# ── K1 the credential path is not duplicated ──
lacks "$SVC" 'Authorization\|Bearer ' "K1 even the service does not build the auth header itself"
# Scoped to the AI PROVIDER's credential. Mail holds a Bearer token for the mailbox it
# signs into (#54) and must keep doing so; what it must never hold is the key AI Routing
# stores. Matching a bare "Bearer" flagged the account sign-in and would have taught the
# next reader to ignore this rule.
hits=$(grep -rlE 'openrouter|AiRouter|PREF_AI_TOKEN|AI_ROUTING' "$APP/app/src" 2>/dev/null)
if [ -n "$hits" ]; then
  while read -r f; do bad "K1 ${f#$APP/} reaches the AI provider credential path"; done <<< "$hits"
else
  ok "K1 no file in mail reaches the AI provider credential path"
fi
has "$TT/aidl/com/diegonmarcos/superapp/texttools/ITextTools.aidl" 'String\[\] enhance' "K1 mail only ever sends TEXT across the binder"
# Signature lines only: the KDoc above them EXPLAINS that no credential crosses, and
# grepping the whole file made the explanation trip the assertion.
sigs=$(grep -E '^\s+(String\[\]|String|List<String>) [a-zA-Z]+\(' "$TT/aidl/com/diegonmarcos/superapp/texttools/ITextTools.aidl")
case "$sigs" in *token*|*Token*|*key*|*Key*|*credential*)
    bad "K1 an ITextTools method takes or returns a credential" ;;
  *) ok "K1 no ITextTools method carries a credential - only text crosses" ;; esac
has "$LIBS/libs/keyboard/src/main/AndroidManifest.xml" 'android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"' \
  "K1 the service is signature-guarded, so only Cloud-signed callers reach it"

# ── B1 a received message's stored body is never written ──
has "$UI/message/MessageScreen.kt" 'TextToolPanel(textTools, onApply = null)' \
  "B1 the reader passes NO apply callback - there is nothing to write the result into"
recv=$(awk '/fun textToolSource\(\)/,/^    }$/' "$UI/message/MessageScreen.kt")
case "$recv" in *TextToolScope.receivedScope*) ok "B1 the reader sends a quote-free COPY" ;;
  *) bad "B1 the reader does not cut the quoted history" ;; esac
# nothing in the reader's tool path may call a body writer
for writer in 'viewModel.saveBody' 'updateBody' 'setBody' 'body =' 'copy(bodyValues'; do
  case "$recv" in *"$writer"*) bad "B1 the reader's tool path writes the body ($writer)" ;; esac
done
ok "B1 the reader's tool path calls no body writer"
# and the panel only offers Apply when it was given somewhere to apply TO
has "$PANEL" 'if (onApply != null)' "B1 Apply is offered only when a target exists"
# the composer, by contrast, MUST apply - and through the splice, not a raw assignment
has "$UI/compose/ComposeScreen.kt" 'TextToolScope.splice(' "B1 the composer applies through the splice"
has "$UI/compose/ComposeScreen.kt" 'text_tool_stale' "B1 the composer refuses a reply whose range moved"

# ── S1 progress and a real error ──
has "$PANEL" 'LinearProgressIndicator' "S1 a run in flight is visible"
has "$PANEL" 'text_tool_running_with' "S1 the wait names the provider, as the keyboard's Enhance does"
has "$PANEL" 'outcome?.error != null -> Text(outcome.error' "S1 the engine's own reason is shown verbatim"
has "$RUN" 'outcome = TextToolOutcome(tool, result.text, result.error)' "S1 no branch out of a run is silent"
has "$TT/java/com/diegonmarcos/superapp/texttools/TextTools.kt" 'Text tools returned an empty reply' \
  "S1 an empty reply is a failure, not an empty rewrite"
# every R.string the tool files name must exist
STR="$APP/app/src/main/res/values/strings.xml"
miss=""
for s in $(grep -ho 'R\.string\.[a-z_0-9]*' "$SEC" "$PANEL" "$UI/text/"*.kt | sed 's/R.string.//' | sort -u); do
  grep -q "name=\"$s\"" "$STR" || miss="$miss $s"
done
[ -z "$miss" ] && ok "S1 every referenced R.string exists" || bad "S1 missing strings:$miss"

echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" -eq 0 ]
