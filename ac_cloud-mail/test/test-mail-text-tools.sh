#!/usr/bin/env bash
# cloud-mail Text tools: static proof of the wiring, WITHOUT a gradle build (this
# runner cannot build) and without an APK on a device.
#
#   C1  Configs ▸ Text declares four entries and each resolves to a REAL destination
#       IN THIS APP'S OWN settings graph - not a name that resolves to nothing
#   C2  mail owns its own copy of these pages and its own store for their settings,
#       each page EDITABLE, and reaches the keyboard's settings for none of them
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
HUB="$UI/settings/SettingsScreen.kt"
SCREENS="$UI/settings/TextToolsScreens.kt"
REG="$UI/text/MailAiRegistry.kt"
PREFS="$UI/text/MailTextToolsPrefs.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }
lacks() { grep -q -- "$2" "$1" && bad "$3 ($1)" || ok "$3"; }

echo "== cloud-mail Text tools: config entries, engine routing, credential, read-only body =="

# ── C1 the four entries exist and resolve to real destinations IN THIS APP ──
# The COUNT is not the property; the AGREEMENT is. Each row's route has to be a destination
# registered in mail's own settings NavHost, so a row that opens nothing cannot pass. These used
# to be names in the KEYBOARD's allowlist carried in an intent, which is exactly why the owner
# could not edit anything they opened. Written without a magic number so adding a fifth entry
# needs a real change here, not a number bumped.
n=$(grep -c 'route = "' "$SEC")
[ "$n" -ge 1 ] && ok "C1 the Text section declares $n entries" || bad "C1 the Text section declares nothing"
for want in $(grep -o 'route = "[A-Za-z_]*"' "$SEC" | sed 's/route = "//;s/"//'); do
  grep -qE "^[[:space:]]*composable\(\"$want\"\)" "$HUB" \
    && ok "C1 '$want' resolves to a real destination in mail's own settings graph" \
    || bad "C1 '$want' is not a composable() in SettingsScreen - the row would open nothing"
done
# and the rows must not leave this app for the keyboard's settings, which is the whole bug.
# COMMENTS STRIPPED FIRST, for the reason K1 gives below: TextToolsSection.kt explains at length
# that these rows USED to open the keyboard's SettingsActivity, and that sentence is what makes
# the change legible to the next reader. It must not be what fails the check.
code=$(sed 's://.*::' "$SEC" | grep -v '^\s*\*' | grep -v '^\s*/\*')
case "$code" in *EXTRA_OPEN_AT*|*open_at*|*SettingsActivity*|*startActivity*)
    bad "C1 a Text row deep-links into the keyboard's settings" ;;
  *) ok "C1 no Text row deep-links into the keyboard's settings" ;; esac

# ── C2 mail owns its OWN pages and its OWN store, and they are EDITABLE ──
# THE INVERSION, AND IT IS DELIBERATE. This block used to assert the opposite: that mail owned no
# copy of these pages and named none of these keys, because the design was one shared set of
# settings owned by the keyboard. That is what the owner rejected - from mail they could edit
# nothing, because there was nothing of mail's to edit. Two apps, two sets, each editable in its
# own app, neither affecting the other.
for page in MailAiRoutingScreen MailTextEnhanceScreen MailTextResumeScreen MailTranslationScreen; do
  found=$(grep -rl "fun $page" "$APP/app/src" 2>/dev/null | wc -l)
  [ "$found" -ge 1 ] && ok "C2 mail owns its own $page" || bad "C2 mail has no $page of its own"
done
# its own registry, baked from its own build.json, never the keyboard's
has "$APP/build.json" '"mail_ai"' "C2 mail declares its own text-tool registry"
has "$REG" 'BuildConfig.MAIL_AI_ROUTING_B64' "C2 mail's registry reads mail's own baked block"
grep -q 'BuildConfig\.AI_ROUTING_B64' "$REG" \
  && bad "C2 mail's registry reads the keyboard's baked block" \
  || ok "C2 mail's registry never reads the keyboard's baked block"
# its own store, in its own preference file
has "$PREFS" 'getSharedPreferences(FILE' "C2 mail's text-tool settings live in mail's own prefs file"
# EDITABLE is the requirement. A screen that only reads the store is the old bug with new code,
# so every page must have a path that WRITES.
for scr in MailAiRoutingScreen MailTextEnhanceScreen MailTextResumeScreen MailTranslationScreen; do
  body=$(awk "/fun $scr\(/,/^}$/" "$SCREENS")
  # Wired to a control, not merely declared - see the I3 block in
  # test-mail-text-tools-independent.sh for the version of this check that could not fail.
  wired=no
  case "$body" in *"onSelect = set"*) wired=yes ;; esac
  case "$body" in *onValueChange*MailTextToolsPrefs.put*) wired=yes ;; esac
  case "$body" in *"onSelect = {}"*|*"onValueChange = {}"*) wired=no ;; esac
  [ "$wired" = yes ] && ok "C2 $scr can write - it is editable, not a read-only mirror" \
                     || bad "C2 $scr has no control bound to a setter - it is read-only"
done
# and every write must land in mail's own store, never in a cross-app one
lacks "$SCREENS" 'startActivity\|SERVICE_PKG\|contentResolver' \
  "C2 no page writes a setting outside this app"

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
# NARROWED, BECAUSE THE OLD PATTERN NOW MATCHES THINGS THAT ARE FINE. It rejected any mention of
# openrouter|AiRouter|AI_ROUTING anywhere in mail, which worked while mail had no text settings of
# its own to name. Mail now owns its own routing REGISTRY - it names providers, picks models and
# bakes MAIL_AI_ROUTING_B64 - and none of that is the credential. So this asserts the property
# itself rather than the vocabulary: mail may choose a model, and may never hold, read, store or
# send the key that pays for it.
hits=$(grep -rlE "PREF_AI_TOKEN|ai_token_|OPENROUTER_API|openrouter\.ai/api" "$APP/app/src" 2>/dev/null)
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
# The registry mail copied carries PROMPTS AND MODEL LISTS, and must never carry a token. A key
# that arrived by being copied alongside the prompts would be the leak this whole binder design
# exists to prevent, and in a 562-line data block it would look like ordinary configuration.
if python3 - "$APP/build.json" <<'PYK'
import json, re, sys
blob = json.dumps(json.load(open(sys.argv[1]))["mail_ai"])
sys.exit(1 if re.search(r'(?i)"(api_?key|token|secret|authorization|bearer)"\s*:', blob) else 0)
PYK
then ok "K1 mail's copied registry carries no credential"
else bad "K1 mail's copied registry carries something credential-shaped"; fi
# Nor may the snapshot that seeds mail's copy of the owner's settings. It crosses the binder, and
# a token in it would put the key into a second app's SharedPreferences in one line.
snap=$(awk '/override fun settingsSnapshot\(/,/^        }$/' "$SVC")
case "$snap" in *TOKEN*|*token*) bad "K1 the settings snapshot carries a token" ;;
  *) ok "K1 the settings snapshot carries choices, never the credential" ;; esac

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
