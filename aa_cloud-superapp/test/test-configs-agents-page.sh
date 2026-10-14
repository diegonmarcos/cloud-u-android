#!/usr/bin/env bash
# Tester: Configs ▸ Apps ▸ Agents — every agent the fleet runs, in one page with two tabs.
#
#   A1 Configs reads Launcher, Apps, Setup; Apps = C3 · Peer Control · Agents; Agents comes
#      before About in the grid (About stays alone under Setup ▸ Observability)
#   A2 Agents is a page over exactly two tabs, SuperApp agents then Fleet agents, both hidden
#      pages of their own (reached through the strip), and the nav rule holds (2..8 tabs)
#   A3 both tabs route to AgentsFragment with their own scope; the page is drawn by PageTabs
#      (the strip branch of SectionPages) and the fragment is libs:ui-kit's (KitComposeFragment)
#   A4 the old deep links still land: page:config/c3 and page:config/kde are the same ids
#   A5 the registry is baked: FLEET_AGENTS_B64 from data/fleet-agents.json, and that file is
#      what the generator gives today (1_cicd/src/scripts/cloud-android-fleet-agents.py check)
#   A6 every agent declared in a build.json is in the baked file, and this app's own rows are
#      exactly its build.json fleet_agents
#   A7 live status is local only: no HTTP client in the agents package
#   A8 the page's three non-list states are drawn: loading, empty, error
#
# Static (no device, no build).
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { if [ "$1" = "OK" ]; then ok "$2"; else bad "$2 — $1"; fi; }

BJ="$APP/build.json"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp"
PAGES="$KT/launcher/SectionPages.kt"
FRAG="$KT/agents/AgentsFragment.kt"
READER="$KT/agents/AgentStatusReader.kt"
MODEL="$KT/agents/FleetAgents.kt"
DATA="$APP/data/fleet-agents.json"
GEN="$ROOT/1_cicd/src/scripts/cloud-android-fleet-agents.py"
for f in "$BJ" "$PAGES" "$FRAG" "$READER" "$MODEL" "$DATA" "$GEN" "$APP/app/build.gradle"; do
  [ -f "$f" ] || { echo "  ABORT: no such file: $f — a check over nothing passes"; exit 2; }
done

echo "== A1: Launcher, Apps, Setup; Agents before About =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')['pages']
vis = [p for p in pages if not p.get('hidden') and not p.get('is_action')]
groups = []
for p in vis:
    g = p.get('group', '')
    if not groups or groups[-1] != g: groups.append(g)
probs = []
if groups != ['Launcher', 'Apps', 'Setup']: probs.append('groups = %r' % groups)
apps = [p['label'] for p in vis if p.get('group') == 'Apps']
if apps != ['C3', 'Peer Control', 'Agents']: probs.append('Apps = %r' % apps)
ids = [p['id'] for p in vis]
if 'agents' not in ids or 'about' not in ids or ids.index('agents') > ids.index('about'):
    probs.append('Agents is not before About: %r' % ids)
obs = [p['label'] for p in vis if p.get('subgroup') == 'Observability']
if obs != ['About']: probs.append('Observability = %r' % obs)
print('; '.join(probs) or 'OK')
PY
)" "Configs = Launcher, Apps[C3, Peer Control, Agents], Setup; Agents before About; Observability keeps About alone"

echo "== A2: Agents is a strip over SuperApp agents | Fleet agents =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')['pages']
by = {p['id']: p for p in pages}
a = by.get('agents')
if not a: print('no agents page'); sys.exit()
probs = []
if a.get('tabs') != ['agents-superapp', 'agents-fleet']: probs.append('tabs = %r' % a.get('tabs'))
if not 2 <= len(a.get('tabs', [])) <= 8: probs.append('tab count outside 2..8')
for t, label in (('agents-superapp', 'SuperApp agents'), ('agents-fleet', 'Fleet agents')):
    p = by.get(t)
    if not p: probs.append('no %s page' % t); continue
    if p.get('label') != label: probs.append('%s label %r' % (t, p.get('label')))
    if not p.get('hidden'): probs.append('%s is a tile of its own (should be hidden: reached through the strip)' % t)
if a.get('action'): probs.append('agents carries an action %r' % a['action'])
print('; '.join(probs) or 'OK')
PY
)" "agents.tabs = [agents-superapp, agents-fleet], labelled SuperApp agents / Fleet agents, both hidden"

echo "== A3: the routes, the strip and the kit =="
grep -qF 'tabs.isNotEmpty() -> SectionTabsFragment.forPage(sectionId, pageId)' "$PAGES" \
  && ok "a page with tabs is drawn by the PageTabs strip (SectionTabsFragment)" || bad "the strip branch is gone"
grep -qE 'pageId == "agents-superapp" *->' "$PAGES" && grep -qF 'AgentsFragment.SCOPE_SUPERAPP' "$PAGES" \
  && ok "config/agents-superapp -> AgentsFragment(SCOPE_SUPERAPP)" || bad "agents-superapp has no route"
grep -qE 'pageId == "agents-fleet" *->' "$PAGES" && grep -qF 'AgentsFragment.SCOPE_FLEET' "$PAGES" \
  && ok "config/agents-fleet -> AgentsFragment(SCOPE_FLEET)" || bad "agents-fleet has no route"
grep -qF 'class AgentsFragment : KitComposeFragment()' "$FRAG" \
  && ok "AgentsFragment is a libs:ui-kit KitComposeFragment" || bad "AgentsFragment is not a kit page"

echo "== A4: old deep links still land =="
check "$(python3 - "$BJ" <<'PY'
import json, sys
pages = next(s for s in json.load(open(sys.argv[1]))['ui']['sections'] if s['id'] == 'config')['pages']
by = {p['id']: p for p in pages}
probs = []
if by.get('c3', {}).get('action') != 'extapp:cloud-c3': probs.append('c3 = %r' % by.get('c3'))
if by.get('kde', {}).get('label') != 'Peer Control': probs.append('kde = %r' % by.get('kde'))
print('; '.join(probs) or 'OK')
PY
)" "page:config/c3 and page:config/kde keep their ids, so every old link resolves to the moved pages"

echo "== A5: the registry is baked and current =="
grep -qF 'def fleetAgentsFile = file("../data/fleet-agents.json")' "$APP/app/build.gradle" \
  && grep -qF 'buildConfigField "String", "FLEET_AGENTS_B64"' "$APP/app/build.gradle" \
  && ok "app/build.gradle bakes data/fleet-agents.json as FLEET_AGENTS_B64" || bad "the registry is not baked"
grep -qF 'cloud-android-fleet-agents.py" write' "$APP/data/regen.sh" \
  && ok "data/regen.sh regenerates fleet-agents.json on every build" || bad "regen.sh does not regenerate the registry"
if python3 "$GEN" check "$ROOT" >/dev/null 2>&1; then ok "data/fleet-agents.json equals the generator's output"
else bad "data/fleet-agents.json is stale or a declaration is invalid: $(python3 "$GEN" check "$ROOT" | head -3)"; fi

echo "== A6: every declared agent is in the baked file =="
check "$(python3 - "$ROOT" "$DATA" <<'PY'
import glob, json, os, sys
root, data = sys.argv[1], json.load(open(sys.argv[2]))
ids = {a['id'] for a in data['agents']}
probs = []
for p in sorted(glob.glob(root + '/*/build.json') + glob.glob(root + '/*/*/build.json')):
    try: bj = json.load(open(p))
    except Exception: continue
    for r in bj.get('fleet_agents', []):
        if 'from' in r: continue
        want = '%s/%s' % (bj.get('name'), r.get('id'))
        if want not in ids: probs.append('%s missing' % want)
self_bj = json.load(open(root + '/aa_cloud-superapp/build.json'))
mine = ['cloud-superapp/' + r['id'] for r in self_bj['fleet_agents']]
got = [a['id'] for a in data['agents'] if a['app'] == 'cloud-superapp']
if mine != got: probs.append('SuperApp rows %r != declared %r' % (got, mine))
if not any(a['status']['source'] == 'provider' for a in data['agents']):
    probs.append('no agent reads live status through a provider (Cloud Drive Screenshot agent)')
print('; '.join(probs) or 'OK')
PY
)" "every build.json fleet_agents row (and every expanded list) is a row of fleet-agents.json; SuperApp's tab is exactly its own declaration"

echo "== A7: live status is local only =="
if grep -nE 'HttpURLConnection|OkHttp|okhttp3|URL\(|openConnection|Retrofit|ktor' "$FRAG" "$READER" "$MODEL" >/dev/null; then
  bad "the agents package opens a network connection: $(grep -nE 'HttpURLConnection|OkHttp|okhttp3|URL\(|openConnection|Retrofit|ktor' "$FRAG" "$READER" "$MODEL" | head -2)"
else ok "no HTTP client in the agents package — WorkManager, services, PackageManager and providers only"; fi
grep -qF 'com.diegonmarcos.clouddrive.permission.READ_AGENT_RESULTS' "$APP/app/src/main/AndroidManifest.xml" \
  && ok "the manifest asks for Cloud Drive's agent-results permission (its provider's guard)" \
  || bad "no uses-permission for Cloud Drive's READ_AGENT_RESULTS — the Screenshot agent's state would answer SecurityException"

echo "== A8: loading, empty and error are drawn =="
for t in 'agents:loading' 'agents:empty' 'agents:error'; do
  grep -qF "\"$t\"" "$FRAG" && ok "$t is drawn" || bad "$t is not drawn"
done

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" = 0 ]
