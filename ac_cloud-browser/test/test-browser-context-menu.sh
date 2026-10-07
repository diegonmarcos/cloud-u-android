#!/bin/sh
# Long-press on page content: the host hooks the WebView, the rules live in BrowserContextMenu
# (JVM-tested in BrowserContextMenuTest), and the sheet stays dense.
#   C1 the page's WebView has a long-press listener; only links and images are taken (text keeps the system actions)
#   C2 a link's address and text come from requestFocusNodeHref
#   C3 every action is performed by the host; a background open never activates, in-group joins the opener's group
#   C4 the sheet sets no minimum row height
# Each is then run against planted mutations; one that leaves every check green fails this tester.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import sys
sys.path.insert(0, ".")
from browser_tester import LIB, kotlin, main
import os

def check(root, ok):
    src = kotlin(root, LIB, "ac_cloud-browser/app/src/main/java/com/diegonmarcos/cloudbrowser")
    frag = src.get(LIB + "/BrowserHostFragment.kt", "")
    menu = src.get(LIB + "/BrowserContextMenu.kt", "")
    ok("setOnLongClickListener { v -> onPageLongPress(v as WebView) }" in frag, "C1 the page WebView hooks long-press")
    ok("BrowserContextMenu.SRC_ANCHOR, BrowserContextMenu.SRC_IMAGE_ANCHOR ->" in frag and "BrowserContextMenu.IMAGE ->" in frag and "return false" in frag[frag.find("fun onPageLongPress"):frag.find("fun showContext")], "C1 only links and images are taken; everything else returns false")
    ok("wv.requestFocusNodeHref(" in frag and 'getString("url")' in frag and 'getString("title")' in frag, "C2 the link and its text come from requestFocusNodeHref")
    for a in ("COPY_LINK", "COPY_LINK_TEXT", "SHARE_LINK", "DOWNLOAD_LINK", "ADD_FAV", "IMAGE_DOWNLOAD", "IMAGE_COPY", "IMAGE_SHARE", "IMAGE_SEARCH"):
        ok("BrowserContextMenu.Action.%s ->" % a in frag, "C3 the host performs " + a)
    ok("BrowserContextMenu.placement(a, " in frag and "prefs.addNew(url, url, isPrivate = p.isPrivate)" in frag, "C3 an open is a NEW tab placed by the rules")
    ok("if (p.group && from != null) prefs.startOrJoinGroup(from.key, tab.key)" in frag, "C3 in-group joins the opener's group")
    ok("if (p.activate) { prefs.setActiveId(tab.key)" in frag, "C3 only an activating open takes focus")
    ok("OPEN_BACKGROUND -> Placement(activate = false" in menu, "C3 background does not activate")
    ok("minHeight" not in menu and "minimumInteractiveComponentSize" not in menu and "defaultMinSize" not in menu, "C4 the sheet sets no minimum height")
    ok(os.path.exists(os.path.join(root, "ac_cloud-browser/app/src/test/java/com/diegonmarcos/cloudbrowser/BrowserContextMenuTest.kt")), "C3 the rules have a JVM test")

FRAG = LIB + "/BrowserHostFragment.kt"
MENU = LIB + "/BrowserContextMenu.kt"
main("context menu", check, [
    ("no long-press hook", FRAG, "setOnLongClickListener { v -> onPageLongPress(v as WebView) }", "Unit", "hooks long-press"),
    ("the link text is not asked for", FRAG, "wv.requestFocusNodeHref(", "wv.hashCode(", "requestFocusNodeHref"),
    ("the host drops Add to Fav", FRAG, "BrowserContextMenu.Action.ADD_FAV ->", "BrowserContextMenu.Action.OPEN_TAB ->", "performs ADD_FAV"),
    ("in-group forgets the group", FRAG, "if (p.group && from != null) prefs.startOrJoinGroup(from.key, tab.key)", "", "joins the opener's group"),
    ("background takes focus", MENU, "OPEN_BACKGROUND -> Placement(activate = false", "OPEN_BACKGROUND -> Placement(activate = true", "background does not activate"),
    ("the sheet inflates", MENU, "modifier = Modifier.fillMaxWidth().clickable { onPick(a) }", "modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).defaultMinSize(minHeight = 48.dp).clickable { onPick(a) }", "no minimum height"),
])
PYEOF
