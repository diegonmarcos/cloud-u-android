#!/usr/bin/env bash
# test-me-perms: Configs > Perms is DECLARED (build.json::ui.permissions) and the manifest agrees with it.
#   P1 every `entries` id is a row of the shared libs:perms catalogue, once
#   P2 AndroidManifest.xml <uses-permission> == the declared permissions, BOTH ways (catalogue rows' permission,
#      `install`, `health.read`, `health.write`)
#   P3 health.read is the SuperApp's exact Health Connect set (libs:health's manifest); every write has its read
#   P4 the Health Connect rationale activity, the Android 14 VIEW_PERMISSION_USAGE alias and the queries are declared
#   P5 wiring: libs:perms is a module the app depends on, the declaration is baked and read, the Kotlin names no permission
#   P6 the start-up rule: runtime asks only DENIED (never PERMANENTLY_DENIED), Health Connect stops after 2 asks, special
#      pages open once; MainActivity runs startup on open and Grant all from the banner; Configs shows the list
# Then each mutation below is planted in a scratch copy and must turn the check RED.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; APP="$(cd "$HERE/.." && pwd)"; ROOT="$(cd "$APP/.." && pwd)"
FAILS=0; ok() { echo "  PASS: $1"; }; bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }
CHECK="$(mktemp -d)/check.py"; S="$(mktemp -d)"; trap 'rm -rf "$S" "$(dirname "$CHECK")"' EXIT
cat > "$CHECK" <<'PY'
import json, re, sys
root = sys.argv[1]; app = root + "/ac_cloud-me"; src = app + "/app/src/main/java/com/diegonmarcos/cloudme"
out = []
def bad(m): out.append(m)
d = json.load(open(app + "/build.json")); p = d["ui"]["permissions"]
cat = {e["id"]: e for e in json.load(open(root + "/ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json"))["entries"]}
ids = [e["id"] for e in p["entries"]]
if len(ids) != len(set(ids)): bad("P1 an entry id is declared twice")
for i in ids:
    if i not in cat: bad("P1 %s is not a libs:perms catalogue row" % i)
for e in p["entries"]:
    if not e.get("why"): bad("P1 %s has no reason" % e["id"])
man = open(app + "/app/src/main/AndroidManifest.xml").read()
in_man = set(re.findall(r'<uses-permission\s+android:name="([^"]+)"', man))
declared = {cat[i]["permission"] for i in ids if i in cat and cat[i].get("permission") and cat[i]["kind"] != "adb_only"}
declared |= {x["perm"] for x in p["install"]} | set(p["health"]["read"]) | set(p["health"]["write"])
if in_man - declared: bad("P2 in the manifest, not declared: %s" % sorted(in_man - declared))
if declared - in_man: bad("P2 declared, not in the manifest: %s" % sorted(declared - in_man))
for need in ("special", "runtime"):
    if not any(cat.get(i, {}).get("section") == need or cat.get(i, {}).get("kind") == need for i in ids): bad("P2 no %s entry declared" % need)
hman = open(root + "/ab_cloud-libs-shared/libs/health/src/main/AndroidManifest.xml").read()
sa = set(re.findall(r'<uses-permission\s+android:name="(android\.permission\.health\.[^"]+)"', hman))
if set(p["health"]["read"]) != sa: bad("P3 health.read differs from the SuperApp's set: missing %s, extra %s" % (sorted(sa - set(p["health"]["read"])), sorted(set(p["health"]["read"]) - sa)))
for w in p["health"]["write"]:
    if w.replace(".WRITE_", ".READ_") not in p["health"]["read"]: bad("P3 %s has no matching read" % w)
if not p["health"].get("rationale"): bad("P3 no Health Connect rationale text")
act = re.search(r'<activity\s+android:name="\.health\.HealthRationaleActivity".*?</activity>', man, re.S)
if not act or "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" not in act.group(0): bad("P4 no rationale activity with ACTION_SHOW_PERMISSIONS_RATIONALE")
alias = re.search(r'<activity-alias.*?</activity-alias>', man, re.S)
if not alias or not all(k in alias.group(0) for k in ("android.intent.action.VIEW_PERMISSION_USAGE", "android.intent.category.HEALTH_PERMISSIONS", "START_VIEW_PERMISSION_USAGE", ".health.HealthRationaleActivity")):
    bad("P4 no VIEW_PERMISSION_USAGE / HEALTH_PERMISSIONS alias guarded by START_VIEW_PERMISSION_USAGE")
q = man.split("<queries>", 1)[-1].split("</queries>", 1)[0]
if "com.google.android.apps.healthdata" not in q or "ACTION_SHOW_PERMISSIONS_RATIONALE" not in q: bad("P4 Health Connect is not in <queries>")
if "libs:perms" not in d["modules"] or "libs:perms" not in d["modules"]["app"]["depends_on"]: bad("P5 libs:perms is not an app module")
g = open(app + "/app/build.gradle").read()
if "UI_PERMISSIONS_B64" not in g or "project(':libs:perms')" not in g: bad("P5 the declaration is not baked / libs:perms not compiled")
k = open(src + "/MePerms.kt").read()
if "BuildConfig.UI_PERMISSIONS_B64" not in k: bad("P5 MePerms does not read the baked declaration")
if re.search(r'"android\.(permission|settings)\.', k): bad("P5 MePerms.kt names a permission or settings action")
st = k.split("fun startup(", 1)[1].split("\n    fun ", 1)[0]
if "PermState.PERMANENTLY_DENIED" in st or "it.missing" in st: bad("P6 startup asks a permanently denied permission again")
if "it.kind == RowKind.RUNTIME && it.state == PermState.DENIED" not in st: bad("P6 startup does not ask the denied runtime permissions")
if "mem.healthAsks < HEALTH_MAX_ASKS" not in st or "HEALTH_MAX_ASKS = 2" not in k: bad("P6 Health Connect is asked without the two-ask limit")
if "it.id !in mem.specialOpened" not in st: bad("P6 special pages are reopened on every start")
ga = k.split("fun grantAll(", 1)[1].split("\n    fun ", 1)[0]
if "RowKind.SPECIAL && it.missing" not in ga or "healthStep(rows)" not in ga: bad("P6 Grant all skips special or Health Connect rows")
m = open(src + "/MainActivity.kt").read()
if "MePermPlan.startup(" not in m or "MePermPlan.grantAll(" not in m or "MePerms.noteSpecialOpened" not in m: bad("P6 MainActivity does not run the start-up walk / Grant all")
c = json.load(open(app + "/data/ui/configs/configs.json"))
if not any(x.get("kind") == "permissions" for x in c): bad("P6 Configs has no Perms card")
print("\n".join(out)); sys.exit(1 if out else 0)
PY
echo "[ac_cloud-me] Configs > Perms declaration"
r="$(python3 "$CHECK" "$ROOT" 2>&1)" && ok "declaration, manifest, Health Connect set and start-up rule agree" || bad "$r"
FILES="ac_cloud-me/build.json ac_cloud-me/app/src/main/AndroidManifest.xml ac_cloud-me/app/build.gradle ac_cloud-me/data/ui/configs/configs.json ac_cloud-me/app/src/main/java/com/diegonmarcos/cloudme/MePerms.kt ac_cloud-me/app/src/main/java/com/diegonmarcos/cloudme/MainActivity.kt ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json ab_cloud-libs-shared/libs/health/src/main/AndroidManifest.xml"
plant() { # <name> <python acting on T>
  rm -rf "$S/t"; for f in $FILES; do mkdir -p "$S/t/$(dirname "$f")"; cp "$ROOT/$f" "$S/t/$f"; done
  python3 - "$S/t" <<PY
import re, sys
T = sys.argv[1]; A = T + "/ac_cloud-me"; K = A + "/app/src/main/java/com/diegonmarcos/cloudme"
def sub(path, a, b):
    s = open(path).read(); assert a in s, "mutation anchor missing: " + a; open(path, "w").write(s.replace(a, b, 1))
$2
PY
  if python3 "$CHECK" "$S/t" >/dev/null 2>&1; then bad "mutation stayed green: $1"; else ok "red: $1"; fi
}
plant "a declared permission leaves the manifest"  'sub(A + "/app/src/main/AndroidManifest.xml", "<uses-permission android:name=\"android.permission.SCHEDULE_EXACT_ALARM\" />", "")'
plant "the manifest gains an undeclared permission" 'sub(A + "/app/src/main/AndroidManifest.xml", "<queries>", "<uses-permission android:name=\"android.permission.CAMERA\" />\n    <queries>")'
plant "a Health Connect read is dropped"            'sub(A + "/build.json", "\"android.permission.health.READ_STEPS\",", "")'
plant "an entry is not a catalogue row"             'sub(A + "/build.json", "{\"id\": \"overlay\"", "{\"id\": \"overlay_x\"")'
plant "the rationale action is removed"             'sub(A + "/app/src/main/AndroidManifest.xml", "<action android:name=\"androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE\" />\n            </intent-filter>\n        </activity>", "</intent-filter>\n        </activity>")'
plant "the Android 14 alias loses its guard"        'sub(A + "/app/src/main/AndroidManifest.xml", "android:permission=\"android.permission.START_VIEW_PERMISSION_USAGE\"", "")'
plant "the Kotlin names a permission"               'sub(K + "/MePerms.kt", "object MePerms {", "object MePerms {\n    const val X = \"android.permission.CAMERA\"")'
plant "start-up re-asks a permanently denied one"   'sub(K + "/MePerms.kt", "it.kind == RowKind.RUNTIME && it.state == PermState.DENIED }.map { it.permission }.distinct()\n            .takeIf { it.isNotEmpty() }?.let { out += PermStep.Runtime(it) }\n        if (mem", "it.kind == RowKind.RUNTIME && it.missing }.map { it.permission }.distinct()\n            .takeIf { it.isNotEmpty() }?.let { out += PermStep.Runtime(it) }\n        if (mem")'
plant "Health Connect asked with no limit"          'sub(K + "/MePerms.kt", "if (mem.healthAsks < HEALTH_MAX_ASKS) ", "")'
plant "libs:perms is not a module"                  'sub(A + "/build.json", "        \"libs:perms\",\n", "")'
echo; [ "$FAILS" -eq 0 ] && { echo "OK me perms"; exit 0; } || { echo "FAILED $FAILS"; exit 1; }
