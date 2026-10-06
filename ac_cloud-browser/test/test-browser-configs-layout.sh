#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #886 Configs is organised by topic, in the fleet's Compose kit   ║
# ╚══════════════════════════════════════════════════════════════════╝
#
#   C1 build.json::ui.browser.settings_sections declares the topics; every setting names one of them and
#      every topic holds a setting or a screen row (BrowserConfigsLayoutTest holds the same on the shipped file);
#   C2 the settings page is drawn per topic in the declared order (BrowserSettingsLayout.order), with
#      each topic's screen rows inside it (settings_section), from libs:ui-kit's rows and a chip row per enum;
#   C3 the host hands the page the declared topics and the in-topic rows;
#   C4 the page is Compose (no View screen added: the compose ratchet counts it).
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import sys, os
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

def check(root, ok):
    src = kotlin(root, LIB, APP)
    frag = src.get(LIB + "/BrowserHostFragment.kt", "")
    sheets = src.get(LIB + "/BrowserSheets.kt", "")
    cfg = src.get(LIB + "/BrowserConfig.kt", "")
    menu = src.get(LIB + "/BrowserMenu.kt", "")
    b = build_json(root)["ui"]["browser"]
    topics = [s["id"] for s in b.get("settings_sections", [])]
    ok(topics == ["search", "privacy", "tabs", "translate", "appearance", "data", "advanced"], "C1 the topics are declared, in order", str(topics))
    for s in b["settings"]:
        ok(s["section"] in topics, "C1 setting `%s` sits in a declared topic" % s["key"], s["section"])
    held = {s["section"] for s in b["settings"]} | {i.get("settings_section") for i in b["menu"]["items"]}
    for t in topics:
        ok(t in held, "C1 topic `%s` holds something" % t)
    ok("val order = BrowserSettingsLayout.order(catalogue.settings, sections, extraIn.keys)" in sheets, "C2 the page is drawn per topic in the declared order")
    ok("KitSectionHeader(title" in sheets and "KitSwitchRow(" in sheets and "KitSettingsRow(" in sheets, "C2 with the fleet kit's header and rows")
    ok("FilterChip(" in sheets and "FlowRow(" in sheets, "C2 an enum is a dense chip row, not a list per choice")
    ok("extraIn[id].orEmpty().forEach" in sheets, "C2 a topic's screen rows are drawn inside it")
    ok("sections = config.settingsSections, extraIn = inSection" in frag and "it.item.settingsSection" in frag, "C3 the host passes the topics and the in-topic rows")
    ok('optJSONArray("settings_sections")' in cfg and 'ifBlank { null }' in menu and "settings_section" in menu, "C3 the config parses them")
    ok("class BrowserTabGrid" in src.get(LIB + "/BrowserTabGrid.kt", "") and "BrowserSettingsScreen" in sheets and "@Composable" in sheets, "C4 the page is Compose")
    ok("BrowserSettingsScreen(" not in "".join(t for p, t in src.items() if p.endswith("BrowserSheets.kt") is False and "Fragment" not in p and "Settings" not in p), "C4 nothing else draws a second settings page")

BJ = "ac_cloud-browser/build.json"
SH = LIB + "/BrowserSheets.kt"
main("configs layout", check, [
    ("a topic is dropped from the declaration", BJ, '"id": "translate",\n          "label": "Translate & summary"', '"id": "translate_gone",\n          "label": "Translate & summary"', "declared, in order"),
    ("a setting lands in no topic", BJ, '"key": "tab_strip",\n          "type": "bool",\n          "default": true,\n          "label": "Tab strip",\n          "section": "tabs"', '"key": "tab_strip",\n          "type": "bool",\n          "default": true,\n          "label": "Tab strip",\n          "section": "nowhere"', "sits in a declared topic"),
    ("the page ignores the topic order", SH, "val order = BrowserSettingsLayout.order(catalogue.settings, sections, extraIn.keys)", "val order = listOf<Pair<String, String>>()", "per topic in the declared order"),
    ("enums become a list again", SH, "FilterChip(selected", "androidx.compose.material3.Text(selected", "chip row"),
    ("in-topic rows are not drawn", SH, "extraIn[id].orEmpty().forEach", "emptyList<SheetRow>().forEach", "inside it"),
    ("the host stops passing the topics", LIB + "/BrowserHostFragment.kt", "sections = config.settingsSections, extraIn = inSection", "extraIn = inSection", "passes the topics"),
])
PYEOF
