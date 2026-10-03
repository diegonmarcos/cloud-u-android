#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #802 I10 Cloud Vault fills through Android, the browser holds    ║
# ║ no vault and spells no vault package                             ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# A Bitwarden SDK in the browser binds its licence and starts a second vault
# session; a literal package drifts from the fleet roster; a route that reads
# vault data puts a secret on the debug API. COMMENT-STRIPPED Kotlin.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import glob, json, os, re, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

def check(root, ok):
    b = build_json(root)["ui"]["browser"]
    v = [a for a in b.get("addons", []) if a["id"] == "vault"]
    ok(len(v) == 1, "the vault add-on is declared once")
    if not v: return
    v = v[0]
    ok(v.get("requires_fleet") == "vault" and "requires_package" not in v, "the vault package comes from the fleet roster (requires_fleet), not build.json")
    ok(v.get("permissions") == ["vault_autofill"], "the vault add-on asks for vault_autofill only")
    fleet = json.load(open(os.path.join(root, "aa_cloud-superapp/data/constellation-fleet.json")))
    pkg = [a["package"] for a in fleet["apps"] if a["id"] == "vault"][0]
    src = kotlin(root, LIB, APP)
    ok(not any(pkg in t for t in src.values()), "no Kotlin spells the vault package (%s)" % pkg)
    for g in glob.glob(os.path.join(root, "ac_cloud-browser/**/*.gradle"), recursive=True) + [os.path.join(root, "ab_cloud-libs-shared/libs/browser/build.gradle")]:
        t = open(g).read()
        ok("com.bitwarden" not in t and "bitwarden" not in t.lower().replace("(the fleet's bitwarden)", ""), "%s links no Bitwarden SDK" % os.path.relpath(g, root))
    gradle = open(os.path.join(root, "ac_cloud-browser/app/build.gradle")).read()
    ok("a.requires_package = row.package" in gradle and "vaultPackage: vaultPackage" in gradle, "app/build.gradle resolves the package and the <queries> entry")
    man = open(os.path.join(root, "ac_cloud-browser/app/src/main/AndroidManifest.xml")).read()
    ok('<package android:name="${vaultPackage}" />' in man, "the manifest queries the resolved vault package")
    va = src.get(os.path.join(LIB, "VaultAutofill.kt"), "")
    ok("requestAutofill(wv)" in va and "ACTION_REQUEST_SET_AUTOFILL_SERVICE" in va, "fill goes through the Android Autofill Framework")
    ok(not re.search(r"\.value\b|password\"\)|getString\(\"password", va), "VaultAutofill reads no field value")
    frag = src.get(os.path.join(LIB, "BrowserHostFragment.kt"), "")
    run = frag[frag.find("private fun runAction(id: String, args"):]
    for m in v.get("menu", []):
        ok(re.search(r'"%s"(\s*,\s*"[a-z_]+")*\s*->' % re.escape(m["id"]), run), "vault row `%s` has its runAction branch" % m["id"])
    api = src.get(os.path.join(APP, "debugapi", "BrowserDebugApi.kt"), "")
    for op in ("vault/status", "vault/request_fill"):
        ok('Op("%s"' % op in api and '"%s" ->' % op in api, "route %s is documented and handled" % op)
    ok('!config.addons.enabled("vault"' in api, "vault/request_fill refuses while the add-on is off")
    ok("IMPORTANT_FOR_AUTOFILL_NO" in frag and 'bool("autofill_enabled") == false' in frag, "the autofill_enabled setting gates the WebView's autofill participation")

BJ = "ac_cloud-browser/build.json"
LIBP = "ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser/"
main("vault add-on", check, [
    ("the package is spelled in Kotlin", LIBP + "VaultAutofill.kt", "object VaultAutofill {", 'object VaultAutofill {\n    const val PKG = "com.diegonmarcos.cloudvault"', "spells the vault package"),
    ("a Bitwarden SDK is linked", "ab_cloud-libs-shared/libs/browser/build.gradle", "dependencies {", "dependencies {\n    implementation 'com.bitwarden:sdk-android:1.0.0'", "links no Bitwarden SDK"),
    ("the fill row loses its branch", LIBP + "BrowserHostFragment.kt", '"vault_fill", "vault_request_fill" ->', '"vault_fillx", "vault_request_fill" ->', "`vault_fill` has its runAction"),
    ("request_fill ignores the switch", "ac_cloud-browser/app/src/main/java/com/diegonmarcos/cloudbrowser/debugapi/BrowserDebugApi.kt", '"vault/request_fill" -> if (!config.addons.enabled("vault"', '"vault/request_fill" -> if (false && !config.addons.enabled("vaultx"', "refuses while the add-on is off"),
])
PYEOF
