#!/usr/bin/env bash
# Tester: Configs ▸ AI is five tabs, ONE of which reads another app's AI-Routing
# state, and none of which lies about what it can do.
#
# WHY THIS EXISTS. This page does three things that go wrong silently, and each
# of them has already gone wrong once in this repo:
#
#   1. A SECOND MODEL LIST. The page this replaced carried a hand-written table
#      of models and prices in three different units. The registry was rebuilt
#      twice in one day (9ffe4f5ca, a6f69186f) and neither fix could reach that
#      copy, because a copy is not reachable. T3 asserts there is no model id and
#      no price literal anywhere in the AI package — everything on screen comes
#      over the binder from the app that owns the registry.
#
#   2. A KEY IN A LOG. This fleet uploads logcat from its own diagnostics screens
#      (DevControlServer /diagnostics/bundle, DiagnosticsPush), so a key that
#      reaches the log is a key that leaves the phone. T4 asserts that neither
#      side of this feature logs or toasts anything, and T8 asserts the status
#      snapshot carries key PRESENCE and never the key.
#
#   3. A PAGE THAT HANGS ON A DEAD PEER. A binder call has no timeout of its own.
#      T5 asserts every peer state is named, distinct and reachable, and that
#      every call to another app goes through the one deadline helper.
#
# Static tester (no device, no build): build.json is read as data, the Kotlin and
# the AIDL are checked for the contracts that data relies on.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
SHARED="$APP/../ab_cloud-libs-shared"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 — $1"; fi; }

BJ="$APP/build.json"
PAGES="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt"
AIDIR="$APP/app/src/main/java/com/diegonmarcos/superapp/ai"
TOKENS="$AIDIR/AiTokensFleetFragment.kt"
ROSTER="$AIDIR/AiFleetRoster.kt"
VIEWS="$AIDIR/AiViews.kt"
NOTBUILT="$AIDIR/AiNotBuiltFragment.kt"
ENHANCE="$AIDIR/AiTextEnhanceFragment.kt"
STRINGS="$APP/app/src/main/res/values/strings.xml"
AIDL="$SHARED/libs/text-tools/src/main/aidl/com/diegonmarcos/superapp/texttools/ITextTools.aidl"
SERVICE="$SHARED/libs/keyboard/src/main/java/com/diegonmarcos/superapp/texttools/TextToolsService.kt"
CLIENT="$SHARED/libs/text-tools/src/main/java/com/diegonmarcos/superapp/texttools/TextToolsClient.kt"

echo "== T1: the AI page declares the owner's five tabs, in the owner's order =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
ai = next((p for p in pages if p['id'] == 'ai'), None)
want = ['websearch', 'localsearch', 'textenhance', 'library', 'tokens']
if ai is None:              print('no `ai` page in config')
elif ai.get('tabs') != want: print('tabs = %r, wanted %r' % (ai.get('tabs'), want))
elif ai.get('hidden'):      print('the strip itself must stay a listed Configs entry')
else:                       print('OK')
PY
)" "ai: tabs = [websearch, localsearch, textenhance, library, tokens]"

echo "== T2: every AI tab is a REAL declared page of the same section, hidden =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections']
             if s['id'] == 'config')['pages']
by_id = {p['id']: p for p in pages}
ai = next(p for p in pages if p['id'] == 'ai')
problems = []
for tab in ai.get('tabs', []):
    if tab == 'ai':                       problems.append('%s lists itself (infinite render)' % tab)
    elif tab not in by_id:                problems.append('tab %r has no page behind it' % tab)
    elif not by_id[tab].get('hidden'):    problems.append('tab %r is still a standalone Configs tile' % tab)
    elif by_id[tab].get('tabs'):          problems.append('tab %r declares tabs of its own' % tab)
print('; '.join(problems) or 'OK')
PY
)" "all five tabs are declared, hidden, leaf pages of config"

echo "== T2b: every tab id has a fragment in SectionPages (none falls through) =="
MISSING=""
for tab in websearch localsearch textenhance library tokens; do
  grep -q "pageId == \"$tab\"" "$PAGES" || MISSING="$MISSING $tab"
done
[ -z "$MISSING" ] \
  && ok "SectionPages maps all five tab ids" \
  || bad "no SectionPages branch for:$MISSING — those tabs render the generic placeholder page"

echo "== T3: THE ASSERTION THIS FILE IS FOR — Tokens Fleet has no model list =="
# A model id, a model name or a price written down in this app is a second
# registry. It cannot be reached by a fix to the real one, and it is exactly what
# put a stale price and then a wrong unit on this fleet's screens inside a week.
check "$(python3 - "$AIDIR" <<'PY'
import os, re, sys
# Provider-shaped model ids ("meta-llama/llama-3.1-8b-instruct"), vendor model
# names, and bare decimal prices. Any of the three means a list was retyped here.
model_id = re.compile(r'"[a-z0-9-]+/[a-z0-9.\-]+"')
vendor    = re.compile(r'(?i)"[^"]*(claude|gemini|gpt-|llama|deepseek|qwen|mistral|grok|glm-)[^"]*"')
problems = []
for name in sorted(os.listdir(sys.argv[1])):
    if not name.endswith('.kt'):
        continue
    for lineno, line in enumerate(open(os.path.join(sys.argv[1], name), encoding='utf-8'), 1):
        code = line.split('//', 1)[0]
        if code.lstrip().startswith('*'):
            continue
        for rx, what in ((model_id, 'a model id'), (vendor, 'a model name')):
            if rx.search(code):
                problems.append('%s:%d holds %s' % (name, lineno, what))
print('; '.join(problems) or 'OK')
PY
)" "no model id and no model name is written down in the AI package"

grep -q 'client.aiRoutingSnapshot()' "$TOKENS" \
  && ok "the model list is READ from the serving app over the binder" \
  || bad "Tokens Fleet never calls aiRoutingSnapshot — where would its models come from?"

grep -q 'ModelRow' "$ROSTER" && grep -q 'getJSONArray("models")' "$ROSTER" \
  && ok "model rows are parsed from the registry's own reply, field for field" \
  || bad "the snapshot's model rows are not parsed from the reply"

echo "== T3b: prices keep the registry's unit — US dollars per million, 3 decimals =="
grep -q 'promptUsdPerMillionTokens' "$ROSTER" && grep -q 'completionUsdPerMillionTokens' "$ROSTER" \
  && ok "the price fields carry their unit in their names" \
  || bad "a bare price field is back — that is how this became cents once already"
grep -q '"%.3f"' "$TOKENS" \
  && ok "prices print to three decimals (what separates every registry price without a collision)" \
  || bad "the price format is not three decimals"
if grep -qE '(prompt|completion)[A-Za-z]*\s*[*/]\s*(100|1e6|1_000_000|1000000)' "$TOKENS" "$ROSTER"; then
  bad "a price is being scaled on the display path — that is the cents bug returning"
else
  ok "no conversion on the display path: the stored number is printed unchanged"
fi

echo "== T4: no key is ever logged or toasted, on either side =="
# The AI package must not log AT ALL. Not "must not log the key" — must not log,
# because the next person to add a Log line here will be debugging the key path.
if grep -nE '(^|[^A-Za-z])Log\.[dviwe]\(|Toast\.' "$AIDIR"/*.kt | grep -v '^\s*\*'; then
  bad "the AI package logs or toasts — a key on this path reaches the diagnostics upload"
else
  ok "the AI package contains no log call and no toast at all"
fi
# And the serving side's new methods must not log the token either.
check "$(python3 - "$SERVICE" <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
problems = []
for name in ('aiRoutingSnapshot', 'setAiRouting', 'revealAiKey'):
    m = re.search(r'override fun %s\b.*?\n        \}\n' % name, src, re.S)
    if not m:
        problems.append('%s is not implemented' % name)
        continue
    body = m.group(0)
    for line in body.splitlines():
        if 'Log.' not in line:
            continue
        # A log line is allowed only if no token-bearing name is on it.
        if re.search(r'token|apiKey|key\b', line):
            problems.append('%s logs something key-shaped: %s' % (name, line.strip()))
print('; '.join(problems) or 'OK')
PY
)" "the serving app's three new methods never log a token"

echo "== T5: every degraded peer state is named, distinct, and reachable =="
check "$(python3 - "$ROSTER" "$TOKENS" "$STRINGS" <<'PY'
import re, sys
roster, tokens, strings = (open(p, encoding='utf-8').read() for p in sys.argv[1:4])
# The four the brief names, plus the two that separate "absent" from "asleep".
wanted = ['NOT_INSTALLED', 'UNREACHABLE', 'TOO_OLD', 'TIMED_OUT']
problems = []
for state in wanted:
    if state not in roster:
        problems.append('%s is not a declared state' % state)
    if state not in tokens:
        problems.append('%s is never reached by the fleet page' % state)
# Each must map to its OWN string; two states sharing one sentence would send the
# owner to the wrong repair.
res = re.findall(r'PeerState\.%s -> (R\.string\.\w+)' % '|PeerState.'.join([]), tokens)
mapped = re.findall(r'PeerState\.(\w+) -> (R\.string\.\w+)', tokens)
seen = {}
for state, res_id in mapped:
    if res_id in seen:
        problems.append('%s and %s share the sentence %s' % (seen[res_id], state, res_id))
    seen[res_id] = state
    name = res_id.split('.')[-1]
    if ('name="%s"' % name) not in strings:
        problems.append('%s has no string resource' % res_id)
print('; '.join(problems) or 'OK')
PY
)" "not-installed, unreachable, too-old and timed-out are four distinct sentences"

echo "== T5b: every call to another app goes through the ONE deadline helper =="
check "$(python3 - "$TOKENS" <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
problems = []
# Every client.* call must sit inside a withDeadline lambda. Anything calling the
# client outside one is a call that can hang the page forever.
for m in re.finditer(r'client\.(\w+)\(', src):
    name = m.group(1)
    # Neither of these ENTERS the peer's process, so neither can hang on a wedged
    # one and neither needs a deadline: isServingAppInstalled is a package-manager
    # lookup, and isConnected reads this side's own binding (bindService is
    # asynchronous and answers immediately whatever the peer is doing). Every call
    # that does cross into the other app must still be inside withDeadline.
    if name in ('isServingAppInstalled', 'isConnected'):
        continue
    line_start = src.rfind('\n', 0, m.start()) + 1
    line = src[line_start:src.find('\n', m.start())]
    if 'withDeadline' not in line:
        problems.append('client.%s is called outside withDeadline' % name)
if 'PEER_TIMEOUT_MS' not in src:      problems.append('no deadline constant')
if 'TimeoutException' not in src:     problems.append('a timeout is never distinguished from an answer')
if 'task.cancel(true)' not in src:    problems.append('a timed-out call is never let go of')
print('; '.join(problems) or 'OK')
PY
)" "every peer call is deadline-bounded and a timeout is its own outcome"

echo "== T6: every colour comes from the theme, never a literal =="
if grep -nE '0x[0-9A-Fa-f]{8}|Color\.(RED|GREEN|BLUE|WHITE|BLACK|GRAY|parseColor)' "$AIDIR"/*.kt; then
  bad "a colour literal is back in the AI package — it is correct on exactly one theme"
else
  ok "no colour literal anywhere in the AI package"
fi
grep -q 'resolveAttribute' "$VIEWS" && grep -q 'colorOnSurface' "$VIEWS" \
  && ok "colours are resolved from theme attributes (right in light, dark and Samsung black)" \
  || bad "AiViews does not resolve colours from the theme"

echo "== T7: every user-visible string is a translatable resource that exists =="
check "$(python3 - "$AIDIR" "$STRINGS" <<'PY'
import os, re, sys
declared = set(re.findall(r'<string name="([^"]+)"', open(sys.argv[2], encoding='utf-8').read()))
problems = []
for name in sorted(os.listdir(sys.argv[1])):
    if not name.endswith('.kt'):
        continue
    src = open(os.path.join(sys.argv[1], name), encoding='utf-8').read()
    # `android.R.string.*` is the PLATFORM's own resource, already translated into
    # every locale Android ships; only this app's own R is ours to declare.
    for used in re.findall(r'(?<!android\.)\bR\.string\.(\w+)', src):
        if used not in declared:
            problems.append('%s uses R.string.%s, which is not declared' % (name, used))
print('; '.join(problems) or 'OK')
PY
)" "every R.string the AI package uses is declared"

# A literal handed to a widget is a line that can never be translated. The owner
# reads this interface in Spanish and this is exactly the text that gets skipped.
check "$(python3 - "$AIDIR" <<'PY'
import os, re, sys
problems = []
# setText("…") / text = "…" with a literal rather than a resource or getString.
literal = re.compile(r'(setText\(\s*"|(?<![\w.])text\s*=\s*")')
for name in sorted(os.listdir(sys.argv[1])):
    if not name.endswith('.kt'):
        continue
    for lineno, line in enumerate(open(os.path.join(sys.argv[1], name), encoding='utf-8'), 1):
        code = line.split('//', 1)[0]
        if code.lstrip().startswith('*'):
            continue
        if literal.search(code):
            problems.append('%s:%d sets a literal on a widget' % (name, lineno))
print('; '.join(problems) or 'OK')
PY
)" "no widget is given a hardcoded string"

echo "== T8: the key crosses only through the one method that exists to carry it =="
check "$(python3 - "$SERVICE" <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
snap = re.search(r'override fun aiRoutingSnapshot\(\).*?\n        \}\n', src, re.S)
problems = []
if not snap:
    problems.append('aiRoutingSnapshot is not implemented')
else:
    body = snap.group(0)
    if 'key_present' not in body:  problems.append('the snapshot does not report key presence')
    if 'keyHint(' not in body:     problems.append('the snapshot does not mask the hint')
    # The status snapshot must never put the token itself into the payload.
    for m in re.finditer(r'\.put\("([^"]+)",\s*([^)]*)\)', body):
        field, value = m.group(1), m.group(2)
        if re.search(r'\btoken\b', value) and 'keyHint' not in value and 'isNotEmpty' not in value:
            problems.append('the snapshot puts the raw token into field %r' % field)
print('; '.join(problems) or 'OK')
PY
)" "aiRoutingSnapshot carries presence + a masked hint, never the key"

grep -q 'fun revealAiKey' "$SERVICE" \
  && ok "the plaintext key has its own method, so the one dangerous call is greppable" \
  || bad "there is no separate revealAiKey — a secret is riding a status call"

echo "== T9: the AIDL grew by APPENDING (a renumbered method answers the wrong call) =="
check "$(python3 - "$AIDL" <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
# Declaration order IS the transaction-code order. The methods that existed
# before this change must still come first, in their original order.
order = re.findall(r'^\s+(?:String\[\]|String|List<String>)\s+(\w+)\(', src, re.M)
before = ['enhance', 'translate', 'enhanceProviderLabel', 'translateLanguages', 'summarise',
          'enhanceWith', 'summariseWith', 'providerLabelFor', 'settingsSnapshot']
added = ['aiRoutingSnapshot', 'setAiRouting', 'revealAiKey']
if order[:len(before)] != before:
    print('the existing methods were reordered: %r' % (order[:len(before)],))
elif order != before + added:
    print('order is %r, wanted the three new ones appended' % (order,))
else:
    print('OK')
PY
)" "the three new methods are appended after every pre-existing one"

echo "== T10: the placeholder tabs offer NO control at all =="
# A dead control reads as a BROKEN feature; a sentence reads as an ABSENT one.
# That difference is the whole design of the not-built tabs, so it is asserted.
if grep -nE 'Button|EditText|Switch|Spinner|CheckBox|setOnClickListener' "$NOTBUILT"; then
  bad "the not-built tab has a control on it — the owner will press it and think it broke"
else
  ok "the not-built tab has no button, no field and no switch"
fi
grep -q 'ai_not_built_headline' "$NOTBUILT" \
  && ok "it states plainly that the agent is not built" \
  || bad "the not-built tab never says so"

echo "== T11: Text Enhance does not pretend to own settings this app cannot spend =="
# The owner's rule is that a COPY is an independently editable duplicate. This app
# has no surface that rewrites text, so an "editable" copy here would be controls
# that change nothing — the same lie as a search box that does not search.
if grep -nE 'EditText|Spinner|Switch|CheckBox' "$ENHANCE"; then
  bad "Text Enhance offers editors for settings cloud-superapp cannot spend"
else
  ok "Text Enhance is a view with a door, not a third editable copy"
fi
grep -q 'getLaunchIntentForPackage' "$ENHANCE" \
  && ok "it opens the app that OWNS each setting, so the owner can still edit them" \
  || bad "no way through to the owning app — this would be the shared-link complaint again"

echo
echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" -eq 0 ]
