#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #893 Google sign-in finishes in Cloud Browser                    ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# Root causes (BrowserSignInTest proves the rules; this proves they are CALLED):
#   S1 ERR_CACHE_MISS on accounts.google.com/gsi/transform: a POST result was committed as the tab's url and
#      saved in its WebView state, then replayed from the cache. The store refuses it (shouldCommit) and a
#      restored state whose current page is one is refused (BrowserWebState.restore) -> the tab loads its url.
#   S2 the user agent is WebView's own (`; wv)`, `Version/x.x`) whenever the app does not configure one:
#      both modes pass through BrowserNavPolicy.cleanUserAgent.
#   S3 third-party cookies default ON (WebView's own default is off) and are off only when the user blocks them.
#   S4 child windows: setSupportMultipleWindows + javaScriptCanOpenWindowsAutomatically + onCreateWindow with a
#      WebViewTransport and onCloseWindow; the popup gets the same UA / cookie rules.
#   S5 shouldOverrideUrlLoading asks BrowserNavPolicy.decide (web pages load, app links never leave).
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, main

def check(root, ok):
    src = kotlin(root, LIB, APP)
    frag = src.get(LIB + "/BrowserHostFragment.kt", "")
    store = src.get(LIB + "/BrowserTabStore.kt", "")
    ws = src.get(LIB + "/BrowserWebState.kt", "")
    pol = src.get(LIB + "/BrowserNavPolicy.kt", "")
    ok("BrowserNavPolicy.isFormPostResult(u)) return false" in store, "S1 shouldCommit refuses a POST result page")
    ok("!BrowserNavPolicy.isFormPostResult(list.currentItem?.url)" in ws, "S1 a restored state sitting on a POST result is refused")
    ok('path.startsWith("/gsi/")' in pol and 'host == "accounts.google.com"' in pol, "S1 the policy knows Google's gsi endpoints")
    ok("if (!BrowserNavPolicy.isFormPostResult(it.url)) it.reload()" in frag, "S1 a settings change does not reload a POST result")
    ok(frag.count("BrowserNavPolicy.cleanUserAgent(") >= 2, "S2 both the mobile and the desktop user agent are cleaned")
    ok('ua.replace(Regex(";\\\\s*wv\\\\b")' in pol and 'Version/' in pol, "S2 the policy strips the wv token and the Version marker")
    ok('setAcceptThirdPartyCookies(wv, browserSettings.bool("block_third_party_cookies") != true)' in frag, "S3 third-party cookies are on unless blocked")
    ok("cookiesOf(this).setAcceptCookie(true)" in frag, "S3 cookies are accepted")
    ok(frag.count("settings.setSupportMultipleWindows(true)") >= 2 and "settings.javaScriptCanOpenWindowsAutomatically = true" in frag, "S4 the page may open child windows")
    ok(frag.count("override fun onCreateWindow(") >= 2 and "WebView.WebViewTransport" in frag and "transport.webView = child" in frag and "msg.sendToTarget()" in frag,
       "S4 onCreateWindow hands a child WebView to the transport (opener kept)")
    ok("override fun onCloseWindow" in frag, "S4 window.close() closes the child")
    ok("applyViewMode(child)" in frag and "setAcceptThirdPartyCookies(child" in frag, "S4 the popup gets the same agent and cookie rules")
    ok("BrowserNavPolicy.decide(target)" in frag and "BrowserNavPolicy.Decision.Block -> return true" in frag, "S5 app links never leave the browser")
    ok('low.startsWith("http://") || low.startsWith("https://")' in pol and "Decision.Load" in pol, "S5 web pages load as they are")

FRAG = LIB + "/BrowserHostFragment.kt"
main("sign-in", check, [
    ("a POST result is committed again", LIB + "/BrowserTabStore.kt", "if (BrowserNavPolicy.isFormPostResult(u)) return false", "", "refuses a POST result"),
    ("a POST state is restored again", LIB + "/BrowserWebState.kt", "!BrowserNavPolicy.isFormPostResult(list.currentItem?.url)", "true", "refused"),
    ("gsi leaves the policy", LIB + "/BrowserNavPolicy.kt", 'path.startsWith("/gsi/")', 'path.startsWith("/gsx/")', "gsi endpoints"),
    ("the mobile agent is left raw", FRAG, "BrowserNavPolicy.cleanUserAgent(config.userAgents[\"mobile\"] ?: s.userAgentString)", "(config.userAgents[\"mobile\"] ?: s.userAgentString)", "cleaned"),
    ("the wv token stays", LIB + "/BrowserNavPolicy.kt", 'ua.replace(Regex(";\\\\s*wv\\\\b")', 'ua.replace(Regex(";\\\\s*wvx\\\\b")', "strips the wv token"),
    ("third-party cookies go off again", FRAG, '!= true)\n        applyViewMode', '== true)\n        applyViewMode', "third-party cookies are on"),
    ("child windows are off", FRAG, "settings.setSupportMultipleWindows(true)", "settings.setSupportMultipleWindows(false)", "child windows"),
    ("onCreateWindow is gone", FRAG, "override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {\n                    val transport", "override fun onCreateWindowX(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {\n                    val transport", "onCreateWindow hands"),
    ("the transport is never sent", FRAG, "msg.sendToTarget()", "Unit", "opener kept"),
    ("an intent: link leaves the browser", FRAG, "BrowserNavPolicy.Decision.Block -> return true", "BrowserNavPolicy.Decision.Block -> Unit", "never leave"),
    ("a POST result is reloaded", FRAG, "if (!BrowserNavPolicy.isFormPostResult(it.url)) it.reload()", "it.reload()", "does not reload"),
])
PYEOF
