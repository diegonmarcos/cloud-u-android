#!/usr/bin/env bash
# #310 — Cloud ▸ Apps: AGI moves below Tools Primary, and its three loose bot
# tiles become two FOLDERS: B-LLM (the three hosted agents) and S-LLM (sixteen
# small task models, laid out as a table rather than a grid of identical chat
# glyphs).
#
# What this pins is the shape the owner asked for and the one invariant a folder
# introduces: a folder is not a destination. It carries no target, so every
# reader that asks "what can be opened from here" must see its CHILDREN instead
# of it. That is the #284 resolver defect in a new form — the day AI Claude moved
# inside B-LLM, the left edge menu's first sector stopped resolving — so the data
# and all four readers of that invariant are checked here, the bake-time one
# included, because that one fails the build rather than a screen.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → aa_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher"

# Code only — a rule satisfied by a comment mentioning it is not satisfied.
code() { grep -vE '^[[:space:]]*(//|\*|/\*)' "$1" 2>/dev/null; }
has()  { code "$2" | grep -qF -- "$1" && ok "$3" || bad "$3"; }

q() { python3 -c "
import json
d = json.load(open('$BJ'))
cloud = [s for s in d['ui']['sections'] if s['id'] == 'cloud'][0]
groups = cloud['tile_groups']
agi = [g for g in groups if g['title'] == 'AGI'][0]
def folder(fid):
    return [t for t in agi['tiles'] if t['id'] == fid][0]
$1" 2>&1; }

echo "== A1: AGI sits AFTER Tools Primary =="
# Order is the whole first half of the task. Both titles are read from the same
# list so a rename of either cannot make this pass by accident.
ORDER=$(q "
names = [g['title'] for g in groups]
print('%d %d %s' % (names.index('Tools Primary'), names.index('AGI'), ','.join(names)))")
read -r TP AGI_AT NAMES <<<"$ORDER"
[ "$AGI_AT" = "$((TP + 1))" ] \
  && ok "AGI is the group immediately after Tools Primary ($NAMES)" \
  || bad "AGI is at $AGI_AT, Tools Primary at $TP — expected AGI directly after ($NAMES)"

echo "== A2: the two folders exist, in order, after the plain tiles =="
LAYOUT=$(q "print(','.join(t['id'] for t in agi['tiles']))")
case "$LAYOUT" in
  *,b-llm,s-llm) ok "AGI ends with b-llm then s-llm ($LAYOUT)" ;;
  *) bad "AGI must end with b-llm then s-llm, got: $LAYOUT" ;;
esac

echo "== A3: a folder is a tile holding tiles, and carries NO target =="
# The nested 'tiles' array is what MAKES it a folder — GroupedTilesFragment
# branches on it. A folder that also had a target would make the first tap
# ambiguous: open, or navigate?
for pair in "b-llm 3" "s-llm 16"; do
    set -- $pair
    GOT=$(q "f = folder('$1'); print('%d %s' % (len(f.get('tiles') or []), f.get('target', '')))")
    read -r COUNT TARGET <<<"$GOT"
    [ "$COUNT" = "$2" ] && ok "$1 holds $2 entries" || bad "$1 holds $COUNT entries, expected $2"
    [ -z "${TARGET:-}" ] && ok "$1 has no target of its own" \
      || bad "$1 carries a target ($TARGET) — a folder opens, it does not navigate"
done

echo "== A4: B-LLM holds the three hosted agents, unchanged =="
BOTS=$(q "print(','.join(t['label'] for t in folder('b-llm')['tiles']))")
[ "$BOTS" = "AI Hermes,AI Goose,AI Claude" ] \
  && ok "B-LLM is AI Hermes, AI Goose, AI Claude" \
  || bad "B-LLM is '$BOTS', expected 'AI Hermes,AI Goose,AI Claude'"

echo "== A5: S-LLM renders as a TABLE, not a grid =="
# Sixteen task models cannot be told apart by picture. Without this key the
# folder falls back to the icon grid and the owner gets sixteen identical tiles.
UI=$(q "print(folder('s-llm').get('children_ui', ''))")
[ "$UI" = "table" ] && ok "s-llm declares children_ui: table" \
  || bad "s-llm children_ui is '$UI', expected 'table'"

echo "== A6: all sixteen rows, each with its category, task, slug and model =="
# The owner's table, verbatim. A row is identified by four values and none of
# them is redundant: two rows share a model, two share a category, and the slug
# is what the bot actually receives.
ROWS="writer-grammar|Writing & Editing|Grammar & Rewriting|MiniCPM-3B
writer-social|Communication|Personal & Social Message Drafting|Llama-3.2-3B-Instruct
writer-translate|Translation|Real-Time Translation|Yi-1.5-6B
writer-email|Email & Summarization|Email Drafting & Digest|InternLM2.5-7B
voice-home|Smart Home|Voice Commands & Smart Home|Qwen2.5-7B-Instruct
doc-ocr|Vision & Extraction|PDF & Invoice OCR|Qwen2.5-VL-3B
calendar-parse|Productivity|Calendar & Event Parsing|Llama-3.2-3B-Instruct
code-complete|Software Development|Code Completion|Qwen2.5-Coder-7B
home-diy|Technical Support|DIY & Home Repair Troubleshooting|DeepSeek-R1-Distill-Qwen-7B
math-budget|Finance & Math|Math & Household Budgeting|DeepSeek-R1-Distill-Qwen-7B
study-cards|Education|Study Guides & Flashcard Creation|Gemma-2-9B-it
meal-plan|Food & Cooking|Meal Planning & Recipes|Qwen2.5-3B-Instruct
travel-plan|Travel & Planning|Travel & Local Itineraries|GLM-4-9B-Chat
fitness-plan|Health & Fitness|Fitness & Workout Routines|Yi-1.5-6B
search-summary|Search & Research|Web Search & Summary|GLM-4-9B-Chat
shop-compare|Shopping & Review|Gift Ideas & Product Comparisons|InternLM2.5-7B"

WANT_ORDER=$(echo "$ROWS" | cut -d'|' -f1 | paste -sd, -)
GOT_ORDER=$(q "print(','.join(t['id'] for t in folder('s-llm')['tiles']))")
[ "$GOT_ORDER" = "$WANT_ORDER" ] \
  && ok "the sixteen slugs are present in the owner's order" \
  || bad "slug order drifted: $GOT_ORDER"

while IFS='|' read -r slug category task model; do
    GOT=$(q "
row = [t for t in folder('s-llm')['tiles'] if t['id'] == '$slug']
print('MISSING' if not row else '%s|%s|%s' % (row[0].get('caption',''), row[0].get('label',''), row[0].get('note','')))")
    [ "$GOT" = "$category|$task|$model" ] \
      && ok "$slug → $category / $task / $model" \
      || bad "$slug reads '$GOT', expected '$category|$task|$model'"
done <<<"$ROWS"

echo "== A7: every row opens the SAME Hermes chat with its own start payload =="
# One bot, sixteen entry points: the model is chosen by the '?start=<slug>'
# payload, so a row pointing at a per-task bot that does not exist would look
# identical in the UI and be dead on tap. Telegram start payloads allow letters,
# digits, underscore and hyphen, which is why the slugs stay hyphenated.
BAD=$(q "
out = []
for t in folder('s-llm')['tiles']:
    target = t.get('target', '')
    for needle in ('domain=Cloud_agent_hermes_bot', 'start=' + t['id'],
                   'scheme=tg', 'package=org.telegram.messenger',
                   'S.browser_fallback_url=https%3A%2F%2Ft.me%2FCloud_agent_hermes_bot%3Fstart%3D' + t['id']):
        if needle not in target:
            out.append(t['id'] + ' is missing ' + needle)
print('; '.join(out) or 'none')")
[ "$BAD" = "none" ] && ok "all sixteen targets deep-link Hermes with their own start payload" \
  || bad "$BAD"

echo "== A8: a folder is NOT a destination — every reader sees its children =="
# The invariant, on the Kotlin side. TileGroup.destinations is the one
# chokepoint the edge-menu resolver, the drawer and the section menu all reach
# through; substituting children for the folder there repairs all of them at
# once, and is what its own doc comment already promised.
has 'val destinations: List<AggTile> get() =' "$SRC/Sections.kt" \
  "AggTile exposes its own destinations"
has 'if (children.isEmpty()) listOf(this)' "$SRC/Sections.kt" \
  "a plain tile is its own destination"
has 'else children.filterNot { it.separator }.flatMap { it.destinations }' "$SRC/Sections.kt" \
  "a folder contributes its children instead of itself"
has 'tiles.filterNot { it.separator }.flatMap { it.destinations }' "$SRC/Sections.kt" \
  "TileGroup.destinations flattens folders out"
# The two readers that walked group.tiles directly would otherwise list B-LLM
# and S-LLM as menu rows that open nothing.
has 'for (tile in group.destinations)' "$SRC/SectionMenuFragment.kt" \
  "the section menu lists destinations, not folder icons"
# The fourth reader, and the one that fails the BUILD rather than a screen: the
# edge-menu sector validator indexes every tile by target at bake time, so a
# tile that moved inside a folder simply stops existing for it and the whole
# APK dies on a sector that is perfectly fine. It must descend.
has 'ohHarvest(t.tiles as List)' \
  "$APP/../ab_cloud-libs-shared/libs/launcher-onehand/build.gradle" \
  "the onehand bake-time sector validator descends into folders"
# HomeDrawerFragment is deliberately NOT in this list: it reads ui.home_groups,
# an unrelated schema that has never nested a tile, so flattening there would
# only be a reference to a property its type does not have.
[ "$(python3 -c "
import json
d = json.load(open('$BJ'))
print(any('tiles' in t for g in d['ui']['home_groups'] for t in g.get('tiles', [])))")" = "False" ] \
  && ok "ui.home_groups stays flat, so the home drawer needs no flattening" \
  || bad "ui.home_groups now nests tiles — HomeDrawerFragment must flatten too"

echo "== A9: the row renderer opens the folder instead of dispatching it =="
has 'if (tile.children.isNotEmpty())' "$SRC/GroupedTilesFragment.kt" \
  "a tile with children opens the folder popup"
has 'TileFolderDialog.open(ctx, tile)' "$SRC/GroupedTilesFragment.kt" \
  "the popup is the shared TileFolderDialog"
has 'folder.childrenUi == "table"' "$SRC/TileFolderDialog.kt" \
  "the popup switches layout on childrenUi"
has 'children   = parseTilesInline(t.optJSONArray("tiles"))' "$SRC/Sections.kt" \
  "the parser descends into a nested tiles array"

echo
echo "-- $PASS passed, $FAIL failed"
exit "$FAIL"
