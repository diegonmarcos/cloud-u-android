#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #802 I5 privacy: every clear box clears, private never records   ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# A clear-data box with no branch is a checkbox that clears nothing; a private
# tab whose visit reaches history.record is not private; a site permission
# whose Android permission the manifest lacks can never be granted; a WebView
# permission request nobody handles is silently denied (or, worse, a default
# grant). Matched in COMMENT-STRIPPED Kotlin.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import json, os, re, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, MANIFEST, kotlin, build_json, main

def check(root, ok):
    b = build_json(root)["ui"]["browser"]
    src = kotlin(root, LIB, APP)
    priv = src.get(os.path.join(LIB, "BrowserPrivacy.kt"), "")
    frag = src.get(os.path.join(LIB, "BrowserHostFragment.kt"), "")
    sug = src.get(os.path.join(LIB, "BrowserSuggest.kt"), "")
    api = src.get(os.path.join(APP, "debugapi", "BrowserDebugApi.kt"), "")
    clear = priv[priv.find("object BrowserClearData"):]
    calls = {"history": "BrowserHistory(ctx).clear()", "cookies": "removeAllCookies", "cache": "clearCache(true)",
             "storage": "WebStorage.getInstance().deleteAllData()", "previews": "clearPreviews()", "downloads": "BrowserDownloads(ctx).clear()"}
    for box in b.get("clear_data", []):
        i = box["id"]
        ok('"%s" ->' % i in clear, "clear box `%s` has its branch" % i)
        ok(i in calls and calls[i] in clear, "clear box `%s` makes its call (%s)" % (i, calls.get(i)))
    # the private guard sits BEFORE history.record in onPageFinished
    pf = frag[frag.find("override fun onPageFinished"):]
    g, r = pf.find("privateSession.visit(prefs.byId(tabKey)"), pf.find("history.record(")
    ok(0 <= g < r, "a private tab returns before history.record")
    ok("BrowserSitePolicy.shouldRecord(tab)" in src.get(os.path.join(LIB, "BrowserTabsBar.kt"), ""), "the private-session gate is the shouldRecord policy")
    ok("tabs.filter { BrowserSitePolicy.shouldRecord(it) }" in sug, "suggestions skip private tabs")
    ok("LOAD_NO_CACHE" in frag, "a private tab keeps no HTTP cache")
    ok("BrowserClearData.endPrivateSession(" in frag, "closing the last private tab ends the private session")
    ok("override fun onPermissionRequest" in frag and "override fun onGeolocationPermissionsShowPrompt" in frag,
       "WebView's permission and location prompts are handled")
    ok("decide(host, perms)" in frag, "camera/microphone go through the site rule")
    manifest = open(os.path.join(root, "ac_cloud-browser/app/src/main/AndroidManifest.xml"), encoding="utf-8").read()
    for p in b.get("site_permissions", []):
        for a in p.get("android", []):
            ok('android:name="%s"' % a in manifest, "the manifest declares %s (site permission `%s`)" % (a, p["id"]))
        ok(p.get("default") in ("allow", "deny", "ask"), "`%s` has a valid default" % p["id"])
    m = json.load(open(os.path.join(root, MANIFEST), encoding="utf-8"))["stores"]
    ok(m.get("browser_site_permissions", {}).get("class") == "config", "browser_site_permissions is declared config")
    ok('getSharedPreferences("browser_site_permissions"' in priv, "the rules live in that store")
    for op in ("sites", "sites/set", "privacy/clear"):
        ok('Op("%s"' % op in api and '"%s" ->' % op in api, "route %s is documented and handled" % op)
    ok('q["confirm"] != "1" -> JSONObject().put("ok", false).put("error", "add confirm=1")' in api, "privacy/clear needs confirm=1")

PRIV = "ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser/BrowserPrivacy.kt"
FRAG = "ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser/BrowserHostFragment.kt"
main("privacy wiring", check, [
    ("the cache box stops clearing", PRIV, "clearCache(true)", "settings.toString()", "`cache` makes its call"),
    ("private visits reach history", FRAG, "if (!privateSession.visit(prefs.byId(tabKey), origin)) return", "if (false) return", "before history.record"),
    ("a site permission the manifest lacks", "ac_cloud-browser/app/src/main/AndroidManifest.xml", 'android:name="android.permission.CAMERA"', 'android:name="android.permission.NOCAM"', "CAMERA"),
    ("privacy/clear without confirm", "ac_cloud-browser/app/src/main/java/com/diegonmarcos/cloudbrowser/debugapi/BrowserDebugApi.kt", 'q["confirm"] != "1" -> JSONObject().put("ok", false).put("error", "add confirm=1")', 'false -> JSONObject()', "confirm=1"),
    ("a clear box with no branch", "ac_cloud-browser/build.json", '"id": "storage"', '"id": "storage_all"', "storage_all"),
])
PYEOF
