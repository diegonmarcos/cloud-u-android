#!/usr/bin/env bash
# The keyboard's LLM path is ONE registry (build.json::keyboard_ai, baked into
# BuildConfig.AI_ROUTING_B64) consumed by Text Enhancements, Grammar "ai" mode
# and the ENHANCE toolbar key. This is the static proof that the wiring is
# complete WITHOUT a gradle build (this runner cannot build):
#
#   T1  keyboard_ai is well-formed: every provider's default_model is in its own
#       models list, default_provider / default_style resolve, styles carry prompts
#   T2  libs/keyboard/build.gradle bakes the block (AI_ROUTING_B64)
#   T3  ENHANCE is wired end-to-end: ToolbarKey enum, KeyCode, all three
#       KeyboardIconsSet maps, InputLogic dispatch, default first-row toolbar list
#   T4  every R.string the new screens/engines reference exists in strings.xml,
#       and the icon + per-provider title strings exist for each registry provider
#   T5  settings surface: nav destinations, MainSettingsScreen entries, container
#       registration, Grammar mode "ai" in both engine and screen
#   T6  the overlay patch carries every new mirror file (mirror rule: a file not
#       in patches/0001 is destroyed by the next sync-heliboard)
#   T7  pricing: every model entry is {id, open?, prompt?, completion?}; a provider
#       with catalog_url has pricing_as_of + baked prices; and, when the catalog is
#       reachable, every id exists there, 'open' matches hugging_face_id, and baked
#       $/M match the live price within 1 % (a drift = bump pricing_as_of + values)
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$APP/../ab_cloud-libs-shared"
K="$LIBS/libs/keyboard/src/main"
J="$K/java/helium314/keyboard"
PATCH="$LIBS/libs/keyboard/patches/0001-cloud-superapp-keyboard.patch"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }

echo "== keyboard AI routing: registry, ENHANCE key, settings, patch =="

# T1 registry
if python3 - "$LIBS/build.json" <<'EOF'
import json, sys
d = json.load(open(sys.argv[1]))["keyboard_ai"]
p = d["providers"]; s = d["styles"]
assert d["default_provider"] in p, "default_provider not a provider"
assert d["default_style"] in s, "default_style not a style"
for pid, pv in p.items():
    assert pv["default_model"] in [m["id"] for m in pv["models"]], f"{pid}: default_model not in models"
    assert pv["url"].startswith("http"), f"{pid}: url"
    assert isinstance(pv["needs_token"], bool), f"{pid}: needs_token"
for sid, sv in s.items():
    assert sv["prompt"].strip() and sv["label"].strip(), f"{sid}: empty"
assert "grammar" in s, "Grammar 'ai' mode needs the 'grammar' style"
assert int(d["timeout_ms"]) <= 60000, "keyboard cap must stay far below the 180 s bridge timeout"
EOF
then ok "T1 keyboard_ai registry consistent"; else bad "T1 keyboard_ai registry"; fi

# T2 gradle bake
has "$LIBS/libs/keyboard/build.gradle" 'AI_ROUTING_B64' "T2 build.gradle bakes AI_ROUTING_B64"
has "$LIBS/libs/keyboard/build.gradle" 'keyboard_ai' "T2 build.gradle reads keyboard_ai"

# T3 ENHANCE wiring
has "$J/latin/utils/ToolbarUtils.kt" '^    ENHANCE' "T3 ToolbarKey.ENHANCE declared"
has "$J/latin/utils/ToolbarUtils.kt" 'ENHANCE -> KeyCode.ENHANCE' "T3 ToolbarKey.ENHANCE -> KeyCode.ENHANCE"
has "$J/keyboard/internal/keyboard_parser/floris/KeyCode.kt" 'const val ENHANCE' "T3 KeyCode.ENHANCE"
n=$(grep -c 'ToolbarKey.ENHANCE -> R.drawable.ic_toolbar_enhance' "$J/keyboard/internal/KeyboardIconsSet.kt")
[ "$n" = 3 ] && ok "T3 icon mapped in all 3 KeyboardIconsSet maps" || bad "T3 icon maps: $n/3"
has "$J/latin/inputlogic/InputLogic.java" 'case KeyCode.ENHANCE' "T3 InputLogic dispatches ENHANCE"
has "$J/latin/inputlogic/InputLogic.java" 'TextEnhancer.enhance' "T3 ENHANCE calls TextEnhancer"
grep -E 'val default = listOf\(.*TRANSLATE, ENHANCE,' "$J/latin/utils/ToolbarUtils.kt" >/dev/null \
  && ok "T3 ENHANCE in default first-row toolbar list" || bad "T3 ENHANCE not in defaultToolbarPref"
[ -f "$K/res/drawable/ic_toolbar_enhance.xml" ] && ok "T3 drawable exists" || bad "T3 ic_toolbar_enhance.xml missing"

# T4 strings + per-provider titles
for f in "$J/latin/AiRouter.kt" "$J/latin/TextEnhancer.kt" "$J/settings/screens/AiRoutingScreen.kt" \
         "$J/settings/screens/TextEnhanceScreen.kt" "$J/settings/screens/MainSettingsScreen.kt" "$J/settings/screens/GrammarCheckScreen.kt"; do
  grep -o 'R\.string\.[a-z_0-9]*' "$f"
done | sort -u | sed 's/R.string.//' | while read -r s; do
  grep -q "name=\"$s\"" "$K/res/values/strings.xml" || echo "$s"
done > /tmp/kb-ai-missing-strings.$$
[ -s /tmp/kb-ai-missing-strings.$$ ] && bad "T4 missing strings: $(tr '\n' ' ' < /tmp/kb-ai-missing-strings.$$)" || ok "T4 every referenced R.string exists"
rm -f /tmp/kb-ai-missing-strings.$$
for pid in $(python3 -c "import json,sys;print(' '.join(json.load(open(sys.argv[1]))['keyboard_ai']['providers']))" "$LIBS/build.json"); do
  has "$K/res/values/strings.xml" "name=\"ai_token_${pid}_title\"" "T4 provider '$pid' has its key title"
  has "$K/res/values/strings.xml" "name=\"ai_model_${pid}_title\"" "T4 provider '$pid' has its model title"
done
has "$K/res/values/strings.xml" 'name="enhance" tools:keep' "T4 toolbar key label 'enhance' kept for getStringResourceOrName"

# T5 settings surface
has "$J/settings/SettingsNavHost.kt" 'const val AiRouting' "T5 nav destination AiRouting"
has "$J/settings/SettingsNavHost.kt" 'const val TextEnhance' "T5 nav destination TextEnhance"
has "$J/settings/SettingsNavHost.kt" 'AiRoutingScreen(onClickBack' "T5 AiRoutingScreen routed"
has "$J/settings/SettingsNavHost.kt" 'TextEnhanceScreen(onClickBack' "T5 TextEnhanceScreen routed"
has "$J/settings/screens/MainSettingsScreen.kt" 'settings_screen_ai_routing' "T5 main menu: AI Model Routing"
has "$J/settings/screens/MainSettingsScreen.kt" 'settings_screen_enhance' "T5 main menu: Text Enhancements"
# order: Text Enhancements before Grammar; AI Model Routing after Voice transcript
awk '/settings_screen_enhance\)/{e=NR} /settings_screen_grammar\)/{g=NR} /settings_screen_voice_transcript\)/{v=NR} /settings_screen_ai_routing\)/{a=NR}
     END{exit !(e && g && v && a && e<g && v<a)}' "$J/settings/screens/MainSettingsScreen.kt" \
  && ok "T5 menu order: Enhance<Grammar, Voice<AI Routing" || bad "T5 menu order"
has "$J/settings/SettingsContainer.kt" 'createAiRoutingSettings(context)' "T5 container registers AI routing settings"
has "$J/settings/SettingsContainer.kt" 'createTextEnhanceSettings(context)' "T5 container registers enhance settings"
has "$J/latin/GrammarChecker.kt" '"ai" -> TextEnhancer.run' "T5 Grammar mode 'ai' routes through TextEnhancer"
has "$J/settings/screens/GrammarCheckScreen.kt" 'grammar_mode_ai) to "ai"' "T5 Grammar screen offers 'ai'"

# T6 patch carries the new mirror files
for f in latin/AiRouter.kt latin/TextEnhancer.kt settings/screens/AiRoutingScreen.kt settings/screens/TextEnhanceScreen.kt; do
  has "$PATCH" "^diff --git a/libs/keyboard/src/main/java/helium314/keyboard/$f" "T6 patch carries $f"
done
has "$PATCH" '^diff --git a/libs/keyboard/src/main/res/drawable/ic_toolbar_enhance.xml' "T6 patch carries the icon"

# T7 pricing: registry shape, then live catalog cross-check (skipped, not failed, when offline)
if python3 - "$LIBS/build.json" <<'EOF'
import json, sys
d = json.load(open(sys.argv[1]))["keyboard_ai"]
assert int(d["catalog_ttl_ms"]) >= 3600000, "catalog_ttl_ms: re-fetching a 700 KB catalog more than hourly is waste"
for pid, pv in d["providers"].items():
    for m in pv["models"]:
        assert set(m) <= {"id", "open", "prompt", "completion"}, f"{pid}/{m.get('id')}: unknown key"
        assert m["id"].strip(), f"{pid}: empty model id"
        assert ("prompt" in m) == ("completion" in m), f"{pid}/{m['id']}: prompt without completion or vice versa"
    if "catalog_url" in pv:
        assert pv["catalog_url"].startswith("https://"), f"{pid}: catalog_url must be https"
        assert pv.get("pricing_as_of", "").count("-") == 2, f"{pid}: pricing_as_of YYYY-MM-DD required with catalog_url"
        assert all("prompt" in m for m in pv["models"]), f"{pid}: every catalog model needs baked prices (offline fallback)"
        assert sum(1 for m in pv["models"] if m.get("open")) >= 5, f"{pid}: fewer than 5 open-weight models"
EOF
then ok "T7 model entries well-formed, catalog providers carry baked prices"; else bad "T7 pricing shape"; fi
CATALOG=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['keyboard_ai']['providers']['openrouter']['catalog_url'])" "$LIBS/build.json")
if curl -sS --max-time 30 -o /tmp/kb-ai-catalog.$$ "$CATALOG" 2>/dev/null; then
  if python3 - "$LIBS/build.json" /tmp/kb-ai-catalog.$$ <<'EOF'
import json, sys
pv = json.load(open(sys.argv[1]))["keyboard_ai"]["providers"]["openrouter"]
cat = {m["id"]: m for m in json.load(open(sys.argv[2]))["data"]}
bad = []
for m in pv["models"]:
    c = cat.get(m["id"])
    if not c: bad.append(f"{m['id']}: not in catalog"); continue
    if bool(m.get("open")) != bool(c.get("hugging_face_id")): bad.append(f"{m['id']}: open={m.get('open', False)} but hugging_face_id={c.get('hugging_face_id')!r}")
    for k in ("prompt", "completion"):
        live = float(c["pricing"][k]) * 1e6
        if abs(live - m[k]) > 0.01 * max(live, m[k]): bad.append(f"{m['id']}: {k} baked {m[k]} vs live {live:.4f}")
assert not bad, "\n    ".join(bad)
EOF
  then ok "T7 live catalog: ids exist, open flags match, baked prices within 1 %"; else bad "T7 live catalog cross-check"; fi
else
  echo "  skip: T7 live catalog unreachable ($CATALOG) — offline, baked prices unverified"
fi
rm -f /tmp/kb-ai-catalog.$$

echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" -eq 0 ]
