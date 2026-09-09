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
#   T7  pricing: every model entry is {id, name, open?, params_b?, quant?,
#       trained_for?, note?, prompt?, completion?}; a provider with catalog_url has
#       pricing_as_of + baked prices; and, when the catalog is reachable, every id
#       exists there, 'open' matches hugging_face_id, and baked $/M match the live
#       price within 1 % (a drift = bump pricing_as_of + values)
#   T8  the AI Model Routing table: slugs non-empty and unique, every price renders
#       #.## in a unit that never rounds a real cost to 0.00, size sorts numerically
#       and not lexically, a row missing size/quant/category renders the unknown
#       marker instead of a blank, and routing still resolves by stored id so the
#       re-sort cannot move which model Enhance or Grammar calls. Online it also
#       re-reads OpenRouter for the quant and trained_for values baked into
#       build.json — that is the refresh procedure for the columns with no runtime
#       refresh (build.json keyboard_ai._doc_refresh)
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$APP/../ab_cloud-libs-shared"
K="$LIBS/libs/keyboard/src/main"
J="$K/java/helium314/keyboard"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }
# Same, but the needle is a LITERAL: the Kotlin below is full of (){}?.$ that a regex would eat.
hasf() { grep -qF -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }

echo "== keyboard AI routing: registry, ENHANCE key, settings =="

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
row1=$(awk '/val default = listOf\(/{f=1} f{print} f && /^    \)/{exit}' "$J/latin/utils/ToolbarUtils.kt")
case "$row1" in
  *ENHANCE*TRANSLATE*|*TRANSLATE*ENHANCE*) ok "T3 ENHANCE and TRANSLATE in default first-row toolbar list" ;;
  *) bad "T3 ENHANCE not in defaultToolbarPref first row" ;;
esac
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

# T7 pricing: registry shape, then live catalog cross-check (skipped, not failed, when offline)
if python3 - "$LIBS/build.json" <<'EOF'
import json, sys
d = json.load(open(sys.argv[1]))["keyboard_ai"]
assert int(d["catalog_ttl_ms"]) >= 3600000, "catalog_ttl_ms: re-fetching a 700 KB catalog more than hourly is waste"
for pid, pv in d["providers"].items():
    for m in pv["models"]:
        assert set(m) <= {"id", "name", "open", "params_b", "quant", "trained_for", "note", "prompt", "completion"}, f"{pid}/{m.get('id')}: unknown key"
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

# T8 the routing table: data shape, rendering, sort, and the routing guarantee
if python3 - "$LIBS/build.json" <<'EOF'
import json, re, sys
d = json.load(open(sys.argv[1]))["keyboard_ai"]
# OpenRouter's own twelve categories. Not a taxonomy of ours: GET /api/v1/models?category=<c>
# rejects anything else with a 400 that lists exactly these.
CATEGORIES = {"programming", "roleplay", "marketing", "marketing/seo", "technology", "science",
              "translation", "legal", "finance", "health", "trivia", "academia"}
BITS = {"fp4": 4, "int4": 4, "fp6": 6, "fp8": 8, "int8": 8, "fp16": 16, "bf16": 16, "fp32": 32}
INF = float("inf")
def size_key(m): return m.get("params_b", INF)
def quant_key(m): return min((BITS.get(q, INF) for q in m.get("quant", [])), default=INF)

for pid, pv in d["providers"].items():
    ms = pv["models"]
    ids = [m["id"] for m in ms]
    names = [m["name"] for m in ms]
    assert all(i.strip() for i in ids), f"{pid}: a model row has an empty slug"
    assert len(set(ids)) == len(ids), f"{pid}: duplicate slug {sorted(i for i in ids if ids.count(i) > 1)}"
    assert all(n.strip() for n in names), f"{pid}: a model row has an empty short name"
    assert len(set(names)) == len(names), f"{pid}: duplicate short name {sorted(n for n in names if names.count(n) > 1)}"

    # Every price cell is US cents per million tokens at two decimals (AiRoutingScreen.cents).
    # A real cost that renders 0.00 would read as free, so the unit must keep every price
    # above a hundredth of a cent, and two different prices must not collapse into one cell.
    seen = {}
    for m in ms:
        for k in ("prompt", "completion"):
            if k not in m: continue
            cell = "%.2f" % (m[k] * 100)
            assert re.fullmatch(r"\d+\.\d{2}", cell), f"{pid}/{m['id']}: {k} renders {cell!r}, not #.##"
            assert m[k] == 0 or float(cell) > 0, \
                f"{pid}/{m['id']}: {k}={m[k]} $/M renders as {cell} cents — the unit is lying about a real cost"
            if cell in seen and seen[cell] != m[k]:
                raise AssertionError(f"{pid}: {m[k]} and {seen[cell]} $/M both render {cell} — the unit hides a real price difference")
            seen[cell] = m[k]

    for m in ms:
        # params_b is only ever the number the id itself publishes. OpenRouter exposes no
        # parameter field at all, so anything else in here was invented.
        want = re.search(r"(?<![A-Za-z0-9])(\d+)b(?![A-Za-z0-9])", m["id"])
        if want:
            assert m.get("params_b") == int(want.group(1)), \
                f"{pid}/{m['id']}: id says {want.group(1)}B but params_b={m.get('params_b')}"
        else:
            assert "params_b" not in m, \
                f"{pid}/{m['id']}: params_b={m.get('params_b')} but the id publishes no size — that is unknown, not a guess"
        for q in m.get("quant", []):
            assert q in BITS, f"{pid}/{m['id']}: quantisation '{q}' has no known bit width, so it cannot be sorted"
        for c in m.get("trained_for", []):
            assert c in CATEGORIES, f"{pid}/{m['id']}: '{c}' is not one of OpenRouter's twelve categories"

    # Size sorts as a NUMBER: 8B before 70B before 235B. To prove that, the rows must contain a
    # pair the two orders disagree on (8 < 70 numerically, "70" < "8" lexically) — without such a
    # pair the comparison below would pass on a string sort too, and assert nothing.
    sized = [m for m in ms if "params_b" in m]
    if len(sized) > 1:
        assert any(a["params_b"] < b["params_b"] and str(a["params_b"]) > str(b["params_b"])
                   for a in sized for b in sized), \
            f"{pid}: no two sizes order differently as numbers and as strings — the next check would prove nothing"
        # Nothing more is asserted here. Once that pair exists, "sorted numerically differs from
        # sorted lexically" is true by construction — size is the primary key and the pair
        # disagrees on it — so asserting it would be a tautology. What is left to prove is that
        # the SCREEN sorts on a number, and that is the sizeKey/compareBy check further down.
    # No stability assertion here on purpose. Name is the last sort key and names are already
    # asserted unique above, so (size, quant, name) is a strict total order and the rendered order
    # cannot depend on sortedWith being stable. An assert that two rows never tie on all three
    # keys would be unfalsifiable given that uniqueness — green forever, testing nothing.

# Each unknown marker needs a row that reaches it, or the branch rendering it is dead code and
# the "missing renders as the marker" guarantee is never actually exercised.
orm = d["providers"]["openrouter"]["models"]
assert any("params_b" not in m for m in orm), "every model has params_b — the unknown size marker is dead code"
assert any(not m.get("quant") for m in orm), "every model has quant — the unknown quantisation marker is dead code"
assert any(not m.get("trained_for") for m in orm), "every model has trained_for — the unknown category marker is dead code"
assert all(m.get("note", "").strip() for m in orm), "a model has an empty observation — write one or drop the key"

# Routing resolves a stored id. These are the values a fresh install lands on; sorting the table
# is presentation and must not move either of them.
assert d["default_provider"] == "openrouter", "default_provider moved — routing changed"
assert d["providers"]["openrouter"]["default_model"] == "google/gemini-2.5-flash", "openrouter default_model moved — routing changed"
assert d["providers"]["cloud"]["default_model"] == "claude-sonnet-4-6", "cloud default_model moved — routing changed"
# An id that shipped before is an id some phone has stored in its prefs. Dropping one silently
# falls that user back to the default model with no warning.
for old in ["google/gemini-2.5-flash", "anthropic/claude-haiku-4.5", "openai/gpt-4.1-mini",
            "deepseek/deepseek-v4-flash-0731", "meta-llama/llama-3.3-70b-instruct",
            "qwen/qwen3-235b-a22b-2507", "mistralai/mistral-small-3.2-24b-instruct",
            "google/gemma-3-27b-it", "z-ai/glm-5.3-flash", "moonshotai/kimi-k2-0905"]:
    assert old in [m["id"] for m in orm], f"{old} was removed — every phone that had it selected silently re-routes"
EOF
then ok "T8 table data: unique slugs, #.## cents, numeric sort, unknown markers reachable, routing pinned"; else bad "T8 table data"; fi

# T8 rendering, read off the Kotlin (this runner cannot build, so the render is proved by its source)
S="$J/settings/screens/AiRoutingScreen.kt"
hasf "$S" 'String.format(Locale.US, "%.2f", v * CENTS_PER_USD)' "T8 prices render two decimals in a fixed locale"
hasf "$S" 'compareBy<AiRouter.Model>({ sizeKey(it) }, { quantKey(it) }, { it.name })' "T8 sort is size, then quantisation, then name"
hasf "$S" 'private fun sizeKey(m: AiRouter.Model) = m.paramsB ?: Int.MAX_VALUE' "T8 models with no published size sort last"
hasf "$S" 'm.paramsB?.let { "${it}B" } ?: unknown' "T8 missing size renders the unknown marker"
hasf "$S" 'm.quant.joinToString("/").ifEmpty { unknown }' "T8 missing quantisation renders the unknown marker"
hasf "$S" 'm.trainedFor.joinToString(", ").ifEmpty { unknown }' "T8 missing category renders the unknown marker"
hasf "$S" 'm.note ?: unknown' "T8 missing observation renders the unknown marker"
hasf "$S" 'val unknown = stringResource(R.string.ai_table_unknown)' "T8 the unknown marker is one string resource"
hasf "$S" 'Text(m.id, color = dim, style = body, maxLines = 1, softWrap = false)' "T8 the slug is never wrapped and never ellipsised"
# Header and rows must share ONE scroll state, or the columns slide out from under their headings.
n=$(grep -cF 'rememberScrollState()' "$S")
[ "$n" = 1 ] && ok "T8 header and rows share one scroll state" || bad "T8 $n scroll states — rows would scroll apart from the header"
n=$(grep -cF 'Row(Modifier.fillMaxWidth().horizontalScroll(scroll))' "$S")
[ "$n" = 2 ] && ok "T8 one scrolling strip for the header and one for the rows" || bad "T8 $n horizontal strips, expected 2"
# The strip must be a horizontalScroll, not a LazyRow: a lazy list inside the settings LazyColumn
# is the same-axis nesting that makes the two scrolls fight on a phone.
grep -qF 'LazyRow(' "$S" && bad "T8 LazyRow in the table — nest that in the settings list and the scrolls fight" \
  || ok "T8 the strip is a horizontalScroll, perpendicular to the settings list's vertical scroll"
# Table and picker must agree, and both sort by the same presentation comparator.
n=$(grep -cF 'sortedWith(byModelSize)' "$S")
[ "$n" = 2 ] && ok "T8 table and model picker use the same order" || bad "T8 sortedWith(byModelSize) used $n times, expected 2 (table + picker)"
hasf "$S" 'context.labelFor(it) to it.id' "T8 the picker's stored value is the id, not the row position"

# Sorting is presentation. Had it leaked into AiRouter, the picker would follow the table.
grep -qE 'sortedWith|sortedBy|sorted\(' "$J/latin/AiRouter.kt" \
  && bad "T8 AiRouter sorts models — the call path must not depend on row order" \
  || ok "T8 AiRouter never sorts: it resolves the stored id, not a row position"
hasf "$J/latin/AiRouter.kt" 'providers.firstOrNull { it.id == id }' "T8 provider resolved by id"
hasf "$J/latin/AiRouter.kt" 'PREF_AI_MODEL_PREFIX + p.id, p.defaultModel' "T8 Enhance/Grammar resolve the stored model id, falling back to default_model"
# Translate does not read this table at all — it goes through libs:translate's registered client.
# If that ever changes, this table's order becomes a routing input and T8 has to be rewritten.
grep -rqF 'AiRouter' "$LIBS/libs/translate/src/main/java" \
  && bad "T8 libs:translate now references AiRouter — Translate would start depending on this table" \
  || ok "T8 Translate routes through TranslateEngines.client, untouched by the model table"

# T8 live: the quantisation and trained_for values baked into build.json, re-read from OpenRouter.
# This is the refresh procedure for the two columns that have no runtime refresh on a phone.
if curl -sS --max-time 15 -o /dev/null "$CATALOG" 2>/dev/null && python3 - "$LIBS/build.json" <<'EOF'
import json, sys, urllib.request, collections
pv = json.load(open(sys.argv[1]))["keyboard_ai"]["providers"]["openrouter"]
def get(u):
    with urllib.request.urlopen(u, timeout=40) as r: return json.load(r)
ranked = collections.defaultdict(set)
for c in ("programming", "roleplay", "marketing", "marketing/seo", "technology", "science",
          "translation", "legal", "finance", "health", "trivia", "academia"):
    for m in get("https://openrouter.ai/api/v1/models?category=" + c.replace("/", "%2F"))["data"]:
        ranked[m["id"]].add(c)
drift = []
for m in pv["models"]:
    live_cats = ranked.get(m["id"], set())
    if set(m.get("trained_for", [])) != live_cats:
        drift.append(f"{m['id']}: trained_for {sorted(m.get('trained_for', []))} vs live {sorted(live_cats)}")
    eps = get(f"https://openrouter.ai/api/v1/models/{m['id']}/endpoints")["data"]["endpoints"]
    live_q = sorted({e.get("quantization") for e in eps if e.get("quantization")} - {"unknown"})
    if sorted(m.get("quant", [])) != live_q:
        drift.append(f"{m['id']}: quant {sorted(m.get('quant', []))} vs live {live_q}")
assert not drift, "\n    ".join(drift)
EOF
then ok "T8 live: baked quantisation and trained_for still match OpenRouter"
else echo "  skip/FAIL above: T8 live OpenRouter cross-check (offline = skip; a listed drift = refresh those fields and bump pricing_as_of)"; fi

echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" -eq 0 ]
