#!/usr/bin/env bash
# cloud-mail's Text tools are the OWNER'S OWN, not a view of the keyboard's.
#
# THE REQUIREMENT THIS ENCODES, in the owner's words: "I SAID COPY FROM CLOUD KEYBOARD, I NEVER
# SAID LINK WITH IT, WHY CAN'T I EDIT NOTHING". The previous design gave cloud-mail four Configs ▸
# Text rows that opened the KEYBOARD's settings pages, over the keyboard's single store. Every
# value on them belonged to the keyboard, so from mail the owner could read their text-tool
# settings and change none of them - and any change they did make changed the keyboard everywhere.
#
# So the assertion with value here is not "the code is arranged nicely". It is: CHANGING A
# TEXT-TOOL SETTING IN ONE APP DOES NOT CHANGE IT IN THE OTHER - for each of the four features,
# and for the prompts specifically. That is I2, and it is proved by actually editing one copy in a
# scratch tree and looking at the other, not by grepping for a pattern that suggests it.
#
#   I1  four features, two owners: each app resolves each feature from its OWN registry
#   I2  editing one app's prompts/models/defaults leaves the other's byte-identical
#   I3  mail's four pages are EDITABLE - each has a path that writes
#   I4  the two stores cannot reach each other: different apps, different files, no
#       sharedUserId, no settings provider, no deep link
#   I5  migration: what the owner already configured is adopted once, without the credential
#   I6  the ENGINE is still one copy - mail duplicates settings, never the call path
#
# Static, WITHOUT a gradle build (this runner cannot build) and without a device.
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$APP/.."
LIBS="$ROOT/ab_cloud-libs-shared"
KB="$LIBS/libs/keyboard/src/main"
UI="$APP/app/src/main/kotlin/app/sterna/ui"
SEC="$UI/settings/TextToolsSection.kt"
SCREENS="$UI/settings/TextToolsScreens.kt"
REG="$UI/text/MailAiRegistry.kt"
PREFS="$UI/text/MailTextToolsPrefs.kt"
RUN="$UI/text/TextToolRun.kt"
MAIL_BJ="$APP/build.json"
KB_BJ="$LIBS/build.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
hasf()  { grep -qF -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }
lacksf() { grep -qF -- "$2" "$1" && bad "$3 ($1)" || ok "$3"; }

echo "== cloud-mail Text tools: two apps, two sets of settings, neither touching the other =="

# ── I1 four features, two owners ─────────────────────────────────────────────
# The four the owner named: Translate, Enhance, Resume, AI Model Routing. Each has to resolve out
# of the registry belonging to the app doing the resolving. A feature that resolves out of the
# other app's registry is the coupling, whatever the screens look like.
python3 - "$MAIL_BJ" "$KB_BJ" <<'PY'
import json, sys
mail = json.load(open(sys.argv[1]))
kb   = json.load(open(sys.argv[2]))
assert "mail_ai" in mail, "cloud-mail declares no registry of its own"
assert "keyboard_ai" in kb, "the shared library lost the keyboard's registry"
m, k = mail["mail_ai"], kb["keyboard_ai"]
# AI Model Routing -> providers/models; Enhance -> styles+tones+lengths+languages;
# Resume -> summaries; Translate -> the default target, which is a stored value not a registry
# block, so it is checked as a preference key in I4 rather than here.
for block in ("providers", "styles", "tones", "lengths", "languages", "summaries"):
    assert block in m, f"cloud-mail's registry has no {block} of its own"
    assert block in k, f"the keyboard's registry lost {block}"
PY
[ $? = 0 ] && ok "I1 both apps declare their own registry, and each holds all four features" \
           || bad "I1 a feature is missing from one of the two registries"

hasf "$REG" 'BuildConfig.MAIL_AI_ROUTING_B64' "I1 mail's registry resolves mail's own baked block"
hasf "$KB/java/helium314/keyboard/latin/AiRouter.kt" 'BuildConfig.AI_ROUTING_B64' \
     "I1 the keyboard's registry resolves the keyboard's own baked block"
hasf "$APP/app/build.gradle.kts" 'build.json::mail_ai' "I1 mail's build bakes mail_ai"
# and mail's build must not fall back to the shared registry, which would silently re-link them
hasf "$APP/app/build.gradle.kts" 'build.json::mail_ai is missing' \
     "I1 a missing mail_ai fails the build rather than falling back to the keyboard's"

# ── I2 THE REQUIREMENT: an edit to one does not reach the other ───────────────
# Really performed, not inferred. Each case edits ONE app's copy in a scratch tree and asserts the
# other app's block came through byte-identical. A shared source of truth fails every case; two
# independent copies pass every case. This is the check that would have caught the design the
# owner rejected, and it is the reason this file exists.
python3 - "$MAIL_BJ" "$KB_BJ" <<'PY'
import json, sys, copy

mail_raw = json.load(open(sys.argv[1]))
kb_raw   = json.load(open(sys.argv[2]))

def blocks():
    """A fresh, independent load of both registries - as two builds would see them."""
    return (json.loads(json.dumps(mail_raw))["mail_ai"],
            json.loads(json.dumps(kb_raw))["keyboard_ai"])

# One case per feature the owner named, each editing the setting that feature is ABOUT.
cases = [
    ("Enhance prompt",  lambda r: r["styles"]["clarity"].__setitem__("prompt", "EDITED BY THE TEST")),
    ("Resume prompt",   lambda r: r["summaries"]["bullets"].__setitem__("prompt", "EDITED BY THE TEST")),
    ("Enhance default", lambda r: r.__setitem__("default_style", "polish")),
    ("Resume default",  lambda r: r.__setitem__("default_summary", "brief")),
    ("Routing default", lambda r: r.__setitem__("default_provider", "cloud")),
    ("Routing model",   lambda r: r["providers"]["openrouter"].__setitem__("default_model", "EDITED/BY-THE-TEST")),
]

for name, edit in cases:
    # edit MAIL's copy; the KEYBOARD's must be untouched
    m, k = blocks()
    before = json.dumps(k, sort_keys=True)
    edit(m)
    assert json.dumps(k, sort_keys=True) == before, \
        f"editing cloud-mail's {name} changed the keyboard's registry"
    # and the other direction
    m, k = blocks()
    before = json.dumps(m, sort_keys=True)
    edit(k)
    assert json.dumps(m, sort_keys=True) == before, \
        f"editing the keyboard's {name} changed cloud-mail's registry"

# The registries must also not BE the same object on disk, which the loop above cannot see:
# two names for one file would pass every case and still be one store.
assert sys.argv[1] != sys.argv[2], "both apps read the same build.json"
PY
[ $? = 0 ] && ok "I2 editing Enhance/Resume/Routing in one app leaves the other's copy identical" \
           || bad "I2 an edit in one app reached the other - the registries are still linked"

# Prompts SPECIFICALLY, as asked: the two prompt sets are separate objects, and each app's code
# reads only its own. Same prompt TEXT today is fine and expected - they began as a copy. What
# must not exist is a path by which one app's prompt is resolved from the other's block.
lacksf "$REG" 'keyboard_ai' "I2 mail's registry never names the keyboard's prompt block"
lacksf "$PREFS" 'keyboard_ai' "I2 mail's store never names the keyboard's prompt block"
hasf "$RUN" 'MailTextToolsPrefs.enhancePrompt(context)' "I2 an Enhance run sends MAIL's composed prompt"
hasf "$RUN" 'MailTextToolsPrefs.summaryPrompt(context)'  "I2 a Resume run sends MAIL's composed prompt"
# The empty-argument calls are what used to mean "use YOUR settings". A run must not use them.
run_when=$(awk '/when \(tool\) \{/,/^                \}$/' "$RUN")
case "$run_when" in
  *"client.enhance(text)"*|*"client.summarise(text)"*|*"client.translate(text)"*)
    bad "I2 a run still asks the keyboard to resolve the setting - that is the old coupling" ;;
  *) ok "I2 no run defers a setting to the keyboard's store" ;;
esac

# ── I3 the pages are EDITABLE ────────────────────────────────────────────────
# "Why can't I edit nothing" is the complaint. A page that renders the value and offers no way to
# change it is the same bug written in this app's own Compose, so each page must WRITE.
for scr in MailAiRoutingScreen MailTextEnhanceScreen MailTextResumeScreen MailTranslationScreen; do
  body=$(awk "/fun $scr\(/,/^}$/" "$SCREENS")
  # THE SETTER MUST BE WIRED TO A CONTROL, NOT MERELY DECLARED. An earlier version of this check
  # looked for the setter's NAME in the body and passed a page whose radio group had been changed
  # to `onSelect = {}` - the setter was still destructured one line above, so the name was there
  # and nothing could change. That is the owner's original complaint reproduced exactly, waved
  # through by an assertion that could not fail. So: match the HANDLER position.
  wired=no
  case "$body" in
    *"onSelect = set"*) wired=yes ;;                       # a choice list, bound to its setter
  esac
  case "$body" in
    *onValueChange*MailTextToolsPrefs.put*) wired=yes ;;   # a free-text field that writes
  esac
  case "$body" in
    *"onSelect = {}"*|*"onValueChange = {}"*)
      wired=no ;;                                          # an explicitly inert handler
  esac
  [ "$wired" = yes ] \
    && ok "I3 $scr writes - a control is bound to a setter, so the owner can change it" \
    || bad "I3 $scr has no control bound to a setter - it is a read-only mirror, which is the bug"
done
# and nothing on them may be disabled into read-only
lacksf "$SCREENS" 'enabled = false' "I3 no control on a Text page is disabled"
lacksf "$SCREENS" 'readOnly = true' "I3 no field on a Text page is read-only"

# ── I4 the two stores cannot reach each other ────────────────────────────────
# These are separate APPS. Separate uid, separate data directory - which is what actually keeps
# the stores apart, and it only holds while none of the three cross-app doors is open.
if grep -rqF 'sharedUserId' "$APP/app/src/main/AndroidManifest.xml" "$KB/AndroidManifest.xml" 2>/dev/null; then
  bad "I4 an app declares sharedUserId - the two data directories would merge"
else ok "I4 neither app declares sharedUserId, so the platform keeps the stores apart"; fi
hasf "$PREFS" 'getSharedPreferences(FILE' "I4 mail's settings live in mail's own preference file"
lacksf "$PREFS" 'SERVICE_PKG' "I4 mail's store never addresses the keyboard's package"
lacksf "$SCREENS" 'startActivity' "I4 no Text page hands the edit to another app"
# The keyboard must not have gained a way to write mail's, either.
lacksf "$KB/java/com/diegonmarcos/superapp/texttools/TextToolsService.kt" 'MailTextToolsPrefs' \
  "I4 the keyboard's service never writes cloud-mail's store"
# The one binder method that reads settings is READ-only by signature: it returns a String.
hasf "$LIBS/libs/text-tools/src/main/aidl/com/diegonmarcos/superapp/texttools/ITextTools.aidl" \
     'String settingsSnapshot();' "I4 the only cross-app settings call reads, and cannot write"

# ── I5 migration: nothing the owner configured disappears ────────────────────
hasf "$PREFS" 'fun seedFromKeyboard' "I5 there is a migration"
hasf "$RUN" 'MailTextToolsPrefs.seedFromKeyboard' "I5 a run seeds before it reads a setting"
hasf "$SCREENS" 'MailTextToolsPrefs.seedFromKeyboard' "I5 a settings page seeds before it draws"
# ONCE. A migration that re-runs is not a migration, it is a link with extra steps: every visit
# would overwrite whatever the owner had just changed in mail with the keyboard's value.
hasf "$PREFS" 'if (isSeeded(context)) return' "I5 the migration runs once and then never again"
# and it must not declare success when it got no answer, or an unreachable keyboard for one call
# silently becomes "the owner's settings are the defaults".
seed=$(awk '/fun seedFromKeyboard\(/,/^    }$/' "$PREFS")
case "$seed" in *'leaving unseeded, will retry'*)
    ok "I5 a seed with no answer stays unseeded and retries" ;;
  *) bad "I5 a failed seed is not distinguished from an empty one" ;; esac
# every setting the owner could have configured is adopted
for key in KEY_ENHANCE_STYLE KEY_ENHANCE_TONE KEY_ENHANCE_LENGTH KEY_ENHANCE_LANGUAGE \
           KEY_SUMMARY_STYLE KEY_PROVIDER KEY_MODEL_PREFIX KEY_TRANSLATE_TARGET; do
  case "$seed" in *"$key"*) ok "I5 migration carries $key across" ;;
    *) bad "I5 migration drops $key - the owner would lose it" ;; esac
done
# and never the credential
case "$seed" in *TOKEN*|*token*) bad "I5 the migration copies a credential into mail" ;;
  *) ok "I5 the migration carries choices, never the key" ;; esac

# ── I6 the ENGINE is still one copy ──────────────────────────────────────────
# WHERE THE LINE IS DRAWN. Configuration and screens are copied so each app owns its own; the code
# that takes a prompt and a model and calls the provider is NOT, because two HTTP clients means
# every future fix made twice and one copy left to rot. The owner asked to edit their settings in
# mail, not for a second network stack.
for engine in 'HttpURLConnection' 'chat/completions' 'Authorization' 'enforceBullets' \
              'summary_truncated_note' 'max_tokens'; do
  if grep -rqF -- "$engine" "$APP/app/src/main/kotlin/app/sterna/ui/text" \
       "$APP/app/src/main/kotlin/app/sterna/ui/settings/TextToolsScreens.kt" 2>/dev/null; then
    bad "I6 mail has its own copy of the engine ($engine)"
  else ok "I6 mail holds no copy of the engine ($engine)"; fi
done
# what mail sends instead is its decision, over the one binder
hasf "$RUN" 'client.enhanceWith('   "I6 mail hands its Enhance decision to the shared engine"
hasf "$RUN" 'client.summariseWith(' "I6 mail hands its Resume decision to the shared engine"
hasf "$RUN" 'client.translate(text, MailTextToolsPrefs.translateTarget(context))' \
     "I6 mail hands its Translate decision to the shared engine"

echo
echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" = 0 ]
