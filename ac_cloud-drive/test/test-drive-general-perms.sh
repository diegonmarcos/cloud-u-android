#!/usr/bin/env bash
# #1333 Configs > General: the anchor row and the Perms section are DECLARED (build.json::ui.general), Kotlin only dispatches.
#   G1 the anchors are the sections GeneralPage draws, in order, each with a label and an IconCatalog icon
#   G2 the manifest's <uses-permission> lines and the Perms declaration agree, in BOTH directions
#   G3 the battery-optimisation entry exists, with its request intent first and a fallback
#   G4 the Samsung entry: Never sleeping apps component first, App info last
#   G5 every entry's check and grant are ones GeneralPerms dispatches on; an entry that grants has an intent (or a runtime dialog)
#   G6 Kotlin holds no permission name and no settings action of its own
#   G7 the declaration is baked and read: UI_GENERAL_B64 in app/build.gradle, Declarations.general, the status mapping order
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
py() { local what="$1"; shift; if python3 - "$APP" "$@" ; then pass "$what"; else fail "$what"; fi; }

echo "── G1 anchors = the sections, in order ──"
py "anchors are unique, labelled, iconed from IconCatalog, in the order GeneralPage draws them" "$SRC" <<'PY'
import json, re, sys
app, src = sys.argv[1], sys.argv[2]
g = json.load(open(app + "/build.json"))["ui"]["general"]
ids = [a["id"] for a in g["anchors"]]
assert len(ids) == len(set(ids)) and ids, "anchor ids not unique / empty"
catalog = set(re.findall(r'^\s+"([a-z_]+)" -> Icons', open(src + "/ui/IconCatalog.kt").read(), re.M))
for a in g["anchors"]:
    assert a.get("label"), a["id"] + " has no label"
    assert a.get("icon") in catalog, "%s: icon %r is not an IconCatalog name" % (a["id"], a.get("icon"))
page = open(src + "/configs/GeneralPage.kt").read()
body = page.split("when (a.id)", 1)[1].split("\n    }\n}", 1)[0]
drawn = re.findall(r'^\s+"([a-z_]+)" ->', body, re.M)
assert drawn == ids, "GeneralPage draws %s but ui.general.anchors declares %s" % (drawn, ids)
assert "perms" in ids, "no Perms anchor"
PY

echo "── G2 manifest <-> Perms, both ways ──"
py "every <uses-permission> is declared in ui.general.perms and every declared permission is in the manifest" <<'PY'
import json, re, sys
app = sys.argv[1]
g = json.load(open(app + "/build.json"))["ui"]["general"]
man = open(app + "/app/src/main/AndroidManifest.xml").read()
in_manifest = {m.replace("${", "{") for m in re.findall(r'<uses-permission\s+android:name="([^"]+)"', man)}
declared = {p for e in g["perms"] for p in e["permissions"]}
assert in_manifest, "no uses-permission found"
assert not (in_manifest - declared), "in the manifest, not declared: %s" % sorted(in_manifest - declared)
assert not (declared - in_manifest), "declared, not in the manifest: %s" % sorted(declared - in_manifest)
PY

echo "── G3 battery optimisation ──"
py "battery_opt entry: REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission, request intent first (package data), fallback after" <<'PY'
import json, sys
g = json.load(open(sys.argv[1] + "/build.json"))["ui"]["general"]
e = next((x for x in g["perms"] if x["id"] == "battery_opt"), None)
assert e, "no battery_opt entry"
assert "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in e["permissions"]
assert e["check"] == "battery_opt" and e["grant"] == "settings"
i = e["intents"]
assert i[0]["action"] == "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" and i[0].get("package_uri"), "first intent is not the exemption request for this package"
assert len(i) >= 2, "no fallback when the request is refused"
PY

echo "── G4 Samsung ──"
py "oem_samsung entry: samsung only, lool component first, App info last" <<'PY'
import json, sys
g = json.load(open(sys.argv[1] + "/build.json"))["ui"]["general"]
e = next((x for x in g["perms"] if x["id"] == "oem_samsung"), None)
assert e and e["oem"] == "samsung" and e["check"] == "oem"
assert e["intents"][0]["component"].startswith("com.samsung.android.lool/")
assert e["intents"][-1]["action"] == "android.settings.APPLICATION_DETAILS_SETTINGS"
PY

echo "── G5 dispatch coverage ──"
py "every check and grant is dispatched by GeneralPerms / GeneralPermsSection; granting entries carry an intent or a runtime dialog" "$SRC" <<'PY'
import json, sys
app, src = sys.argv[1], sys.argv[2]
g = json.load(open(app + "/build.json"))["ui"]["general"]
code = open(src + "/configs/GeneralPerms.kt").read() + open(src + "/configs/GeneralDecl.kt").read()
for e in g["perms"]:
    if e["check"] not in ("declared", "oem"):
        assert '"%s" ->' % e["check"] in code, "%s: check %r is not dispatched" % (e["id"], e["check"])
    assert e["grant"] in ("runtime", "settings", "none"), e["id"] + ": unknown grant " + e["grant"]
    assert e["why"], e["id"] + " has no reason"
    if e["grant"] == "settings":
        assert e["intents"], e["id"] + " grants through settings with no intent"
    if e["grant"] == "runtime":
        assert e["permissions"], e["id"] + " asks at runtime for no permission"
assert 'e.grant == "runtime"' in code or 'e.grant == "runtime"' in open(src + "/configs/GeneralPerms.kt").read()
PY

echo "── G6 Kotlin holds no list ──"
if grep -nE '"android\.(permission|settings)\.' "$SRC/configs/GeneralPerms.kt" "$SRC/configs/GeneralDecl.kt" "$SRC/configs/GeneralPage.kt"; then
  fail "Kotlin names a permission or a settings action: it belongs in build.json::ui.general"
else pass "no permission name or settings action in the Kotlin"; fi

echo "── G7 baked, read, status order ──"
py "baked as UI_GENERAL_B64, read by Declarations.general, status mapping checks the SDK window before granted" "$SRC" <<'PY'
import re, sys
app, src = sys.argv[1], sys.argv[2]
assert "UI_GENERAL_B64" in open(app + "/app/build.gradle").read(), "not baked"
assert "BuildConfig.UI_GENERAL_B64" in open(src + "/Declarations.kt").read(), "Declarations does not read it"
d = open(src + "/configs/GeneralDecl.kt").read()
m = d.split("fun status(", 1)[1]
order = [m.index(k) for k in ("e.minSdk", "e.maxSdk", "e.oem", "e.requiresPackage", '"declared"', "granted == true")]
assert order == sorted(order), "status mapping checks are out of order"
assert "Status.NOT_APPLICABLE" in m and "Status.INFO" in m and "Status.GRANTED" in m and "Status.DENIED" in m
page = open(src + "/configs/GeneralPage.kt").read()
assert "GeneralAnchorRow(" in page and "animateScrollToItem" in page, "anchor row does not scroll"
PY

echo; [ "$FAILURES" -eq 0 ] && { echo "OK general perms"; exit 0; } || { echo "FAILED $FAILURES"; exit 1; }
