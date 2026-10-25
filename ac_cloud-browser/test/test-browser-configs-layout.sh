#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #900 Configs is a section of top tabs, one page per category      ║
# ╚══════════════════════════════════════════════════════════════════╝
#
#   C1 build.json::ui.browser.settings_sections declares the pages (= the top tabs), in order; every setting
#      names one of them and every page holds a setting, a global row or a native block;
#   C2 ui.sections[configs].pages is the SAME list (ids and order): the tabs libs:ui-nav-bars draws;
#   C3 each page is drawn on its own (BrowserConfigsPageScreen): its settings, then its rows, then its native
#      blocks, from libs:ui-kit's rows and a chip row per enum; the host swaps the page when a tab is picked;
#   C4 the page is Compose (no View screen added: the compose ratchet counts it).
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import sys, os
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

PAGES = ["general", "search", "privacy", "site", "autocomplete", "passwords", "payment", "downloads",
         "appearance", "tabs", "sync", "advanced", "about"]

def check(root, ok):
    src = kotlin(root, LIB, APP)
    frag = src.get(LIB + "/BrowserHostFragment.kt", "")
    sheets = src.get(LIB + "/BrowserSheets.kt", "")
    screen = src.get(LIB + "/BrowserConfigsScreen.kt", "")
    cfg = src.get(LIB + "/BrowserConfig.kt", "")
    main_act = src.get(APP + "/MainActivity.kt", "")
    bj = build_json(root)
    b = bj["ui"]["browser"]
    topics = [s["id"] for s in b.get("settings_sections", [])]
    ok(topics == PAGES, "C1 the pages are declared, in order", str(topics))
    for s in b["settings"]:
        ok(s["section"] in topics, "C1 setting `%s` sits on a declared page" % s["key"], s["section"])
    held = {s["section"] for s in b["settings"]} | {r["page"] for r in b["configs"]["rows"]} | set(b["configs"]["native"])
    held |= {m["page"] for a in b["addons"] for m in a.get("menu", []) if m.get("scope") == "global"}
    for t in topics:
        ok(t in held, "C1 page `%s` holds something" % t)
    nav = [s for s in bj["ui"]["sections"] if s["id"] == "configs"][0]
    ok([p["id"] for p in nav.get("pages", [])] == topics, "C2 the configs section's tabs are the settings pages, in order")
    ok("NavBarTopSecondRow(" in main_act and "openConfigsPage(" in main_act and "host.openConfigsPage(id)" in main_act, "C2 the shell draws the tabs with NavBarTopSecondRow and swaps the page")
    ok("fun openConfigsPage(" in frag and "val configsPageIds" in frag, "C2 the host opens a page by id")
    ok("catalogue.settings.filter { it.section == pageId }" in screen and "BrowserSettingRow(s, v)" in screen, "C3 a page draws the settings of its own id")
    ok("rows.forEach" in screen and "native()" in screen, "C3 then its rows and its native blocks")
    ok("KitSectionHeader(" in screen and "KitSettingsRow(" in screen, "C3 with the fleet kit's header and rows")
    ok("KitSwitchRow(" in sheets and "FilterChip(" in sheets and "FlowRow(" in sheets, "C3 a bool is a switch, an enum a dense chip row")
    ok("pages.resolve(page, allFacts(), config.menu.whys)" in frag and "native = { ConfigsNative(pages.nativeFor(page)) }" in frag, "C3 the host hands the page its rows and blocks")
    ok('optJSONArray("settings_sections")' in cfg and "BrowserConfigPages.parse(" in cfg, "C3 the config parses them")
    ok("@Composable" in screen and "BrowserConfigsPageScreen" in screen, "C4 the page is Compose")

BJ = "ac_cloud-browser/build.json"
SC = LIB + "/BrowserConfigsScreen.kt"
main("configs layout", check, [
    ("a page is dropped from the declaration", BJ, '"id": "payment",\n          "label": "Payment"', '"id": "payment_gone",\n          "label": "Payment"', "declared, in order"),
    ("a setting lands on no page", BJ, '"label": "Tab strip",\n          "section": "tabs"', '"label": "Tab strip",\n          "section": "nowhere"', "sits on a declared page"),
    ("the nav tabs lose a page", BJ, '"id": "about",\n            "label": "About",\n            "icon": "info"\n          }\n        ]', '"id": "about",\n            "label": "About",\n            "icon": "info"\n          }\n        , {"id": "extra", "label": "Extra"}]', "tabs are the settings pages"),
    ("the page ignores its id", SC, "catalogue.settings.filter { it.section == pageId }", "catalogue.settings.filter { true }", "settings of its own id"),
    ("native blocks are not drawn", SC, "        native()\n", "", "native blocks"),
    ("enums become a list again", LIB + "/BrowserSheets.kt", "FilterChip(selected", "androidx.compose.material3.Text(selected", "chip row"),
    ("the host stops passing the rows", LIB + "/BrowserHostFragment.kt", "rows = pages.resolve(page, allFacts(), config.menu.whys),", "rows = emptyList(),", "hands the page"),
    ("the shell stops swapping pages", APP + "/MainActivity.kt", "host.openConfigsPage(id)", "host.openSettings()", "swaps the page"),
])
PYEOF
