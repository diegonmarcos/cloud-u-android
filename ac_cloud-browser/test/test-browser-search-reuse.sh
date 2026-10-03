#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #802 I8 the Search add-on reuses cloud-search; it copies nothing ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# An engine URL or the chat endpoint spelled in the add-on is a second
# declaration that drifts from cloud-search's; a stored token is a token on
# disk. Matched in COMMENT-STRIPPED Kotlin and in build.json.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import json, os, re, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

def check(root, ok):
    bj = build_json(root)
    b = bj["ui"]["browser"]
    search = json.load(open(os.path.join(root, "ac_cloud-search/build.json")))["search"]
    src = kotlin(root, LIB, APP)
    allkt = "".join(src.values())
    # The address bar's own engines (ui.browser.search_engines) are the browser's; the ADD-ON's
    # side (the app's Kotlin and the add-on's block) must not restate cloud-search's.
    appkt = "".join(t for p, t in src.items() if p.startswith(APP))
    raw = json.dumps([a for a in b.get("addons", []) if a["id"] == "search"])
    for e in search["engines"]:
        host = re.sub(r"^https?://", "", e["url"]).split("/")[0]
        ok(host not in appkt and host not in raw, "engine `%s` (%s) is not spelled in the browser" % (e["id"], host))
    ok("openrouter.ai" not in allkt and "openrouter.ai" not in json.dumps(b), "the chat endpoint is not spelled in the browser")
    ok(bj["modules"].get("search-core", {}).get("dir") == "../ac_cloud-search/core", "search-core is linked by reference")
    ok("search-core" in bj["modules"]["app"]["depends_on"], "the app depends on search-core")
    gradle = open(os.path.join(root, "ac_cloud-browser/app/build.gradle")).read()
    ok("ac_cloud-search/build.json" in gradle and "it.engines = searchDecl.engines" in gradle and "SEARCH_CONFIG_B64" in gradle,
       "app/build.gradle reads cloud-search's declaration (engines + the baked search block)")
    ok("implementation project(':search-core')" in gradle, "the app links search-core")
    addon = src.get(os.path.join(APP, "search", "SearchAddon.kt"), "")
    ok(not re.search(r"put(String|StringSet)\([^)]*token", allkt, re.I), "no token is written to prefs")
    ok("Log." not in addon and "println" not in addon, "the search add-on logs nothing")
    ok('"no $provider token in the fleet Account"' in addon, "the missing-token answer is cloud-search's wording")
    ok(re.search(r'put\("[a-z_]+",\s*value\)', addon) is None and "outcomeJson" in addon, "the token never enters an answer")
    api = src.get(os.path.join(APP, "debugapi", "BrowserDebugApi.kt"), "")
    for op in ("search/engines", "search/open", "search/chat/sessions", "search/chat/send"):
        ok('Op("%s"' % op in api and '"%s" ->' % op in api, "route %s is documented and handled" % op)
    ok(api.count('!config.addons.enabled("search"') >= 2, "search/open and chat/send refuse while the add-on is off")
    frag = src.get(os.path.join(LIB, "BrowserHostFragment.kt"), "")
    ok('"search_with" -> { showSearchWith()' in frag and "config.addons.searchEngines()" in frag, "the Search with row is wired to the declared engines")

BJ = "ac_cloud-browser/build.json"
APPP = "ac_cloud-browser/app/src/main/java/com/diegonmarcos/cloudbrowser/"
main("search reuse", check, [
    ("an engine URL is copied into the browser", APPP + "search/SearchAddon.kt", 'class SearchAddon(val cfg: SearchConfig) {', 'class SearchAddon(val cfg: SearchConfig) {\n    val ddg = "https://duckduckgo.com/?q={q}"', "is not spelled in the browser"),
    ("the token is stored", APPP + "search/SearchAddon.kt", "val (value, why) = token()", "val (value, why) = token()\n        prefsX.putString(\"token\", value)", "no token is written"),
    ("the gradle stops reading cloud-search", "ac_cloud-browser/app/build.gradle", "it.engines = searchDecl.engines", "it.engines = []", "reads cloud-search's declaration"),
    ("chat/send ignores the switch", APPP + "debugapi/BrowserDebugApi.kt", '"search/chat/send" -> if (!config.addons.enabled("search"', '"search/chat/send" -> if (false && !config.addons.enabled("searchx"', "refuse while the add-on is off"),
])
PYEOF
