#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ test-finterm-sources: Config > Sources, statically                ║
# ╚══════════════════════════════════════════════════════════════════╝
# Capital-FinTerm's source catalog (data/sources.json), dashboards (data/dashboards.json) and container control (data/control.json), from
# the sources alone: no build, no network, no device. It certifies
#   S1  the catalog: ids unique, auth none | free_key | paid, a keyed source says where its key goes, every connector is one Providers.kt names
#   S2  MAPPING: every FinTerm entry (build.json ui.stacks dashboard entries) has a dashboard and is fed by at least one declared source;
#       every feed names a tab and entry that exist; every panel's source feeds its entry
#   S3  PRIORITY: Cloud-Fincept (fin_api, the own-fleet primary) is the one primary, has the lowest priority number, feeds every entry,
#       and every fred_series panel declares it as its primary route (the public source is the fallback)
#   S4  KEYS: stored only through SafeEncryptedPrefs in the declared secret store; no plain SharedPreferences besides the holdings list;
#       nothing logs (no Log / println / printStackTrace in the app); no key-shaped literal in the data
#   S5  CONTROL: Stop and Restart ask first (data and UI), the control is MESH ONLY (enabled() needs onMesh, the endpoint is a declared fleet
#       mesh endpoint), the UI gates every button on it
#   S6  NO HARD-CODED URL: no address and no mesh host in the Kotlin, none in dashboards/control data; the mesh address is baked from the fleet declaration
# then plants a mutation per rule in a scratch copy and requires RED for each.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="${LNKTREE_ROOT:-$(cd "$APP/.." && pwd)}"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }
[ -f "$APP/data/sources.json" ] || { echo "ERROR: $APP/data/sources.json missing - zero checks ran"; exit 1; }

check() {   # check <app dir> <repo root>
python3 - "$1" "$2" <<'PY'
import json, os, re, sys
app, root = sys.argv[1], sys.argv[2]
bad = []
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def code(t):
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    return "\n".join(re.sub(r"(?<![:\"])//.*$", "", l) for l in t.splitlines())
bj = json.loads(read(os.path.join(app, "build.json")))
cat = json.loads(read(os.path.join(app, "data/sources.json")))
dash = json.loads(read(os.path.join(app, "data/dashboards.json")))
ctl = json.loads(read(os.path.join(app, "data/control.json")))
kt = {}
base = os.path.join(app, "app/src/main/java")
for d, _, fs in os.walk(base):
    for f in fs:
        if f.endswith(".kt"): kt[os.path.relpath(os.path.join(d, f), base)] = code(read(os.path.join(d, f)))
def ktf(name): return next((t for k, t in kt.items() if k.endswith("/" + name)), "")
S = cat["sources"]; ids = [s["id"] for s in S]; byid = {s["id"]: s for s in S}

# S1
if len(ids) != len(set(ids)): bad.append("S1 source ids are not unique")
conn = set(re.findall(r'"(\w+)"', (re.search(r"CONNECTORS[^=]*=\s*setOf\(([^)]*)\)", ktf("Providers.kt")) or [None, ""])[1]))
for s in S:
    for k in ("id", "name", "category", "coverage", "feeds", "auth"):
        if not s.get(k): bad.append("S1 source %s lacks %s" % (s.get("id"), k))
    a = s.get("auth", {})
    if a.get("type") not in ("none", "free_key", "paid"): bad.append("S1 source %s auth type %r" % (s["id"], a.get("type")))
    if a.get("type") in ("free_key", "paid") and not a.get("key_in"): bad.append("S1 keyed source %s does not say where its key goes" % s["id"])
    if s.get("category") not in cat.get("categories", []): bad.append("S1 source %s category %r is not declared" % (s["id"], s.get("category")))
    if not (s.get("endpoint") or s.get("user_endpoint") or s.get("fleet_endpoint")): bad.append("S1 source %s has no endpoint" % s["id"])
    if s.get("connector") and s["connector"] not in conn: bad.append("S1 source %s connector %s is not in Providers.kt CONNECTORS" % (s["id"], s["connector"]))
    if s.get("connector") and a.get("type") != "none": bad.append("S1 source %s is live but keyed" % s["id"])

# S2
tabs = bj["ui"]["bottom_nav"]; stacks = bj["ui"]["stacks"]
entries = [(t, e["title"], e.get("dashboard")) for t in tabs for e in stacks.get(t, []) if e.get("kind") == "dashboard"]
dbyid = {d["id"]: d for d in dash["dashboards"]}
def feeds(s, tab, entry): return any(f["tab"] == tab and ("*" in f["entries"] or entry in f["entries"]) for f in s["feeds"])
for tab, title, did in entries:
    if not any(feeds(s, tab, title) for s in S): bad.append("S2 FinTerm entry %s/%s maps to no declared source" % (tab, title))
    d = dbyid.get(did)
    if d is None: bad.append("S2 entry %s names no dashboard (%r)" % (title, did)); continue
    if (d["tab"], d["entry"]) != (tab, title): bad.append("S2 dashboard %s is for %s/%s, not %s/%s" % (did, d["tab"], d["entry"], tab, title))
    for p in d["panels"]:
        s = byid.get(p["source"])
        if s is None: bad.append("S2 panel %s/%s source %s is not declared" % (did, p["id"], p["source"]))
        elif not feeds(s, tab, title): bad.append("S2 panel %s/%s: source %s does not feed %s/%s" % (did, p["id"], p["source"], tab, title))
        if not (p.get("fetch") or p.get("placeholder") or p.get("view") == "alloc"): bad.append("S2 panel %s/%s is neither live nor a placeholder" % (did, p["id"]))
for s in S:
    for f in s["feeds"]:
        if f["tab"] not in tabs: bad.append("S2 source %s feeds the unknown tab %s" % (s["id"], f["tab"]))
        for e in f["entries"]:
            if e != "*" and not any(t == f["tab"] and n == e for t, n, _ in entries): bad.append("S2 source %s feeds %s/%s which is not a FinTerm entry" % (s["id"], f["tab"], e))
if len(entries) != len(dbyid): bad.append("S2 %d entries but %d dashboards" % (len(entries), len(dbyid)))

# S3
prim = [s for s in S if s.get("primary")]
if len(prim) != 1: bad.append("S3 want exactly one primary source, found %d" % len(prim))
else:
    p0 = prim[0]
    if any(s["priority"] <= p0["priority"] for s in S if s is not p0): bad.append("S3 the primary %s does not have the lowest priority number" % p0["id"])
    for tab, title, _ in entries:
        if not feeds(p0, tab, title): bad.append("S3 the primary %s does not feed %s/%s" % (p0["id"], tab, title))
    if not p0.get("fleet_endpoint") or p0.get("endpoint"): bad.append("S3 the primary's address must come from its fleet endpoint, not be typed")
    if any(s["priority"] > 10 for s in S if s.get("auth", {}).get("type") == "none" and not s.get("primary")): bad.append("S3 a key-free public source ranks below the keyed ones")
    if any(s["priority"] <= 10 for s in S if s.get("auth", {}).get("type") != "none"): bad.append("S3 a keyed source outranks the key-free public ones")
    for d in dash["dashboards"]:
        for p in d["panels"]:
            f = p.get("fetch")
            if f and f.get("provider") == "fred_series" and (f.get("primary") or {}).get("source") != p0["id"]:
                bad.append("S3 panel %s/%s is a FRED panel without the primary route %s" % (d["id"], p["id"], p0["id"]))

# S4
box = ktf("EncryptedSecretBox.kt")
m = re.search(r'SafeEncryptedPrefs\.prefs\(\s*\w+\s*,\s*PREFS\s*\)', box)
if not m: bad.append("S4 EncryptedSecretBox does not open its file through SafeEncryptedPrefs.prefs")
store = (re.search(r'const val PREFS\s*=\s*"([^"]+)"', box) or [None, ""])[1]
fc = json.loads(read(os.path.join(root, "ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json")))
st = fc.get("stores", {}).get(store)
if not st or st.get("kind") != "encrypted" or st.get("class") != "secret" or "ad_Capital-FinTerm" not in st.get("used_by", []) and "ad_Capital-Stack-FinTerminal" not in st.get("used_by", []):
    bad.append("S4 the key store %r is not declared encrypted + secret for this app in fleet-config.json" % store)
for k, t in kt.items():
    if re.search(r"\bgetSharedPreferences\(|EncryptedSharedPreferences\.create|\bMasterKey", t) and not k.endswith("HoldingsPanel.kt"):
        bad.append("S4 %s keeps state in plain/own preferences: keys go only through SafeEncryptedPrefs" % k)
    if re.search(r"\bLog\.[a-z]\(|\bprintln\(|\bprintStackTrace\(|\bTimber\.", t): bad.append("S4 %s logs or prints (a key must never reach a log)" % k)
if "SourceKeys(EncryptedSecretBox(" not in ktf("SourcesPanel.kt"): bad.append("S4 Config > Sources does not store keys through SourceKeys(EncryptedSecretBox")
if "visualTransformation = PasswordVisualTransformation()" not in ktf("SourcesPanel.kt"): bad.append("S4 the key field is not masked")
def strings(o):
    if isinstance(o, str): yield o
    elif isinstance(o, dict):
        for v in o.values(): yield from strings(v)
    elif isinstance(o, list):
        for v in o: yield from strings(v)
for name, doc in (("sources", cat), ("dashboards", dash), ("control", ctl)):
    for v in strings(doc):
        if re.fullmatch(r"[A-Za-z0-9_\-]{28,}", v): bad.append("S4 data/%s.json holds a key-shaped literal %s..." % (name, v[:6]))

# S5
acts = {a["id"]: a for a in ctl["actions"]}
for need in ("start", "stop", "restart"):
    if need not in acts: bad.append("S5 control.json declares no %s action" % need)
for a in ("stop", "restart"):
    if a in acts and acts[a].get("confirm") is not True: bad.append("S5 %s does not require confirmation in control.json" % a)
    if a in acts and not acts[a].get("enabled_when"): bad.append("S5 %s is available in no state" % a)
if ctl["api"].get("mesh_only") is not True: bad.append("S5 the control api is not declared mesh_only")
acct = json.loads(read(os.path.join(root, "ac_cloud-account/build.json")))["fleet_endpoints"]["services"]
fe = next((s for s in acct if s["id"] == ctl["api"].get("fleet_endpoint")), None)
if not fe or not fe.get("mesh_dns"): bad.append("S5 control fleet endpoint %r has no mesh address in ac_cloud-account fleet_endpoints" % ctl["api"].get("fleet_endpoint"))
if prim:
    pe = next((s for s in acct if s["id"] == prim[0].get("fleet_endpoint")), None)
    if not pe or not pe.get("mesh_dns") or pe.get("policy") != "mesh_only": bad.append("S5 the primary's fleet endpoint is not a mesh_only declared endpoint")
if not re.search(r"if\s*\(\s*!onMesh\s*\)\s*return false", ktf("Control.kt")): bad.append("S5 Control.enabled does not refuse off the mesh")
cc = ktf("ContainerControl.kt")
if "Control.enabled(decl, a.id, state, onMesh == true)" not in cc: bad.append("S5 ContainerControl does not gate each button on Control.enabled with onMesh")
if not re.search(r"Control\.needsConfirm\(decl, a\.id\)\)\s*pending = a\.id else run\(a\.id\)", cc): bad.append("S5 ContainerControl does not route a confirming action through the pending state")
if "KitConfirmDialog(" not in cc or "onConfirm = {" not in cc: bad.append("S5 ContainerControl draws no confirmation dialog")
if len(re.findall(r"(?<![.\w])run\(", cc)) != 3: bad.append("S5 ContainerControl calls run() from somewhere besides the button and the dialog")
if "R.string.ctl_mesh_only" not in cc: bad.append("S5 ContainerControl has no mesh-only note")
if "Authorization" in cc or "Authorization" in ktf("Http.kt") or "Authorization" in ktf("Control.kt"): bad.append("S5 the mesh control sends a bearer (WireGuard is its auth)")

# S6
for k, t in kt.items():
    for mm in re.finditer(r"https?://[^\s\"']+", t): bad.append("S6 %s spells the address %s" % (k, mm.group(0)))
    for mm in re.finditer(r"[\w.-]*(?:mesh\.diegonmarcos|diegonmarcos\.com|\.mesh\.)[\w.-]*", t): bad.append("S6 %s spells the host %s" % (k, mm.group(0)))
for name, doc in (("dashboards", dash), ("control", ctl)):
    for v in strings(doc):
        if re.search(r"https?://|mesh\.diegonmarcos", v): bad.append("S6 data/%s.json spells an address: %s" % (name, v[:50]))
gr = code(read(os.path.join(app, "app/build.gradle")))
if "fleetMeshUrl(" not in gr or "FLEET_MESH_B64" not in gr: bad.append("S6 build.gradle does not bake the mesh addresses from the fleet declaration")
man = read(os.path.join(app, "app/src/main/AndroidManifest.xml"))
if "android.permission.INTERNET" not in man or "networkSecurityConfig" not in man: bad.append("S6 the manifest lacks INTERNET or the network security config for the mesh")
for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }
echo "[$(basename "$APP")] Config > Sources rules"
out="$(check "$APP" "$ROOT")" && ok "S1-S6 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
FOLDER="$(basename "$APP")"
mutant() {   # mutant <name> <python statements on the scratch copy, cwd = the copy> <needle>
  rm -rf "${W:?}/$FOLDER"; cp -r "$APP" "$W/$FOLDER"
  ( cd "$W/$FOLDER" && python3 -c "$2" ) || { bad "mutant '$1' could not be planted"; return; }
  if out="$(check "$W/$FOLDER" "$ROOT" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
JE="import json;L=lambda p:json.load(open(p));D=lambda p,d:json.dump(d,open(p,'w'),indent=2)"
SUB="S=lambda f,a,b:(lambda t:open(f,'w').write(t.replace(a,b)) if a in t else (_ for _ in ()).throw(SystemExit('mutation target missing: '+a)))(open(f).read())"
KT="app/src/main/java/com/diegonmarcos/capitalfinterm"
mutant "an entry loses every source"       "$JE;d=L('data/sources.json');[s.__setitem__('feeds',[f for f in s['feeds'] if f['tab']!='pm']) for s in d['sources']];D('data/sources.json',d)" "S2 FinTerm entry pm/"
mutant "a dashboard is dropped"            "$JE;d=L('data/dashboards.json');d['dashboards'].pop();D('data/dashboards.json',d)" "S2 entry"
mutant "a panel uses a source that does not feed it" "$JE;d=L('data/dashboards.json');[p.__setitem__('source','databento') for x in d['dashboards'] if x['id']=='fx' for p in x['panels'][:1]];D('data/dashboards.json',d)" "S2 panel fx/"
mutant "a connector Kotlin lacks"          "$JE;d=L('data/sources.json');d['sources'][1]['connector']='nope';D('data/sources.json',d)" "S1 source .* connector nope"
mutant "the public source outranks Cloud-Fincept" "$JE;d=L('data/sources.json');[s.__setitem__('priority',-1) for s in d['sources'] if s['id']=='fred'];D('data/sources.json',d)" "S3 the primary"
mutant "a FRED panel loses the primary route" "$JE;d=L('data/dashboards.json');[p['fetch'].pop('primary') for x in d['dashboards'] for p in x['panels'] if x['id']=='fx' and p.get('fetch')];D('data/dashboards.json',d)" "S3 panel fx/"
mutant "the primary stops feeding an entry" "$JE;d=L('data/sources.json');d['sources'][0]['feeds']=d['sources'][0]['feeds'][1:];D('data/sources.json',d)" "S3 the primary cloud_fincept does not feed"
mutant "the primary's address is typed"    "$JE;d=L('data/sources.json');d['sources'][0]['endpoint']='x';D('data/sources.json',d)" "S3 the primary's address"
mutant "keys go to plain preferences"      "$SUB;S('$KT/ui/EncryptedSecretBox.kt','SafeEncryptedPrefs.prefs(app, PREFS)','app.getSharedPreferences(PREFS, 0)')" "S4"
mutant "a key reaches a log"               "$SUB;S('$KT/ui/EncryptedSecretBox.kt','prefs.edit().putString(id, value).apply()','prefs.edit().putString(id, value).apply(); android.util.Log.d(\"k\", value)')" "S4 .*logs or prints"
mutant "the key field is not masked"       "$SUB;S('$KT/ui/SourcesPanel.kt','PasswordVisualTransformation()','VisualTransformation.None')" "S4 the key field is not masked"
mutant "a key-shaped literal is committed" "$JE;d=L('data/sources.json');d['sources'][1]['auth']['note']='abcdefghijklmnopqrstuvwxyz0123456789';D('data/sources.json',d)" "S4 data/sources.json holds a key-shaped literal"
mutant "Stop needs no confirmation (data)" "$JE;d=L('data/control.json');[a.__setitem__('confirm',False) for a in d['actions'] if a['id']=='stop'];D('data/control.json',d)" "S5 stop does not require confirmation"
mutant "Restart needs no confirmation (data)" "$JE;d=L('data/control.json');[a.__setitem__('confirm',False) for a in d['actions'] if a['id']=='restart'];D('data/control.json',d)" "S5 restart does not require confirmation"
mutant "the UI skips the confirmation"     "$SUB;S('$KT/ui/ContainerControl.kt','if (Control.needsConfirm(decl, a.id)) pending = a.id else run(a.id)','run(a.id)')" "S5 ContainerControl does not route"
mutant "the dialog never asks"             "$SUB;S('$KT/ui/ContainerControl.kt','KitConfirmDialog(','Foo(')" "S5 ContainerControl draws no confirmation dialog"
mutant "the control works off the mesh"    "$SUB;S('$KT/Control.kt','if (!onMesh) return false','')" "S5 Control.enabled does not refuse off the mesh"
mutant "the buttons ignore the mesh"       "$SUB;S('$KT/ui/ContainerControl.kt','Control.enabled(decl, a.id, state, onMesh == true)','!busy')" "S5 ContainerControl does not gate"
mutant "the control api is not mesh only"  "$JE;d=L('data/control.json');d['api']['mesh_only']=False;D('data/control.json',d)" "S5 the control api is not declared mesh_only"
mutant "the control sends a bearer"        "$SUB;S('$KT/Control.kt','http.post(actionUrl(d, base, actionId))','http.post(actionUrl(d, base, actionId) + \"\\\"Authorization\\\"\")')" "S5 the mesh control sends a bearer"
mutant "an address is typed in Kotlin"     "open('$KT/Engine.kt','a').write('\nval x = \"https://example.org/a\"\n')" "S6 .*spells the address"
mutant "a mesh host is typed in Kotlin"    "open('$KT/Mesh.kt','a').write('\nval h = \"fin-api.mesh.diegonmarcos.com\"\n')" "S6 .*spells the host"
mutant "an address is typed in the dashboards" "$JE;d=L('data/dashboards.json');d['dashboards'][0]['panels'][0]['title']='see https://example.org';D('data/dashboards.json',d)" "S6 data/dashboards.json spells an address"
mutant "the build stops baking the mesh"   "$SUB;S('app/build.gradle','fleetMeshUrl(','fleetMeshUr(')" "S6 build.gradle does not bake"

[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
