#!/usr/bin/env bash
# Tester: a plain http(s) link opens in superapp's OWN embedded browser, and
# only genuine app deep-links still leave the app.
#
# The regression this guards: ShellActivity.launchUri used to package-target
# ACTION_VIEW at com.diegonmarcos.cloudbrowser for every http(s) target, so the
# MySocials tile (a plain URL in build.json) task-switched out of superapp on
# first tap — and when Cloud-Browser was not installed the URL was
# dropped entirely in favour of an APK download. The fix routes that branch to
# WebPageFragment, the same embedded browser Cloud > Linktree already uses.
#
# Static: greps the Kotlin. Nothing here builds — CI proves it compiles; this
# proves the ROUTING did not silently regress back to an external launch.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # -> aa_cloud-superapp
SRC="$APP/app/src/main/java/com/diegonmarcos/superapp"
SHELL_KT="$SRC/ShellActivity.kt"
WEB_KT="$SRC/launcher/WebPageFragment.kt"
BJ="$APP/build.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has()     { grep -q "$1" "$2" && ok "$3" || bad "$3"; }
lacks()   { grep -q "$1" "$2" && bad "$3" || ok "$3"; }

echo "== T1: the http(s) branch renders in-app, never hands off to a browser APK =="
lacks 'com.diegonmarcos.cloudbrowser' "$SHELL_KT" "no package-targeted hand-off to the Cloud-Browser APK"
has 'openEmbeddedBrowser(uri)'        "$SHELL_KT" "launchUri's http(s) branch calls openEmbeddedBrowser"
has 'WebPageFragment.newInstance(url)' "$SHELL_KT" "openEmbeddedBrowser renders WebPageFragment (the linktree engine)"

echo "== T2: app deep-links still launch externally =="
has 'uri.startsWith("app://")'                 "$SHELL_KT" "app:// still resolves a package via PackageManager"
has 'Intent.parseUri'                          "$SHELL_KT" "intent:// and custom schemes still go through Intent.parseUri"
has 'tileId.startsWith("extapp:")'             "$SHELL_KT" "extapp: still routes to launchExternalApp"

echo "== T3: the embedded browser has a Back affordance on BOTH inputs =="
has 'OnBackPressedCallback'   "$WEB_KT" "system back gesture is wired (OnBackPressedCallback)"
has 'setOnClickListener { goBackOrClose() }' "$WEB_KT" "on-screen control is wired to the same action"
has 'canGoBack'               "$WEB_KT" "Back consults WebView.canGoBack"
has 'goBack()'                "$WEB_KT" "Back steps through WebView history"
has 'onBackPressedDispatcher.onBackPressed()' "$WEB_KT" "with no history left, Back closes the browser"
has 'R.string.action_back'    "$WEB_KT" "the control carries a contentDescription (screen readers)"

# The other reported page was PM Boards, and #311 removed that tile from
# Projects W — the only group that ever defined it. It is checked here no longer
# because it no longer exists, not because the rule stopped applying: the rule is
# about the dispatcher branch, which MySocials still exercises.
echo "== T4: the reported page is a plain URL — engine-routed, not special-cased =="
python3 - "$BJ" <<'PY' && ok "MySocials target is a plain http(s) URL" || bad "the reported tile is no longer a plain URL target"
import json,sys
d=json.load(open(sys.argv[1]))
found={}
def walk(n):
    if isinstance(n,dict):
        if n.get("id") == "mysocials" and "target" in n:
            found[n["id"]]=n["target"]
        for v in n.values(): walk(v)
    elif isinstance(n,list):
        for v in n: walk(v)
walk(d)
assert set(found)=={"mysocials"}, found
for i,t in found.items():
    assert t.startswith("http://") or t.startswith("https://"), (i,t)
PY
lacks 'mysocials' "$SHELL_KT" "no per-page special case for MySocials in the dispatcher"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
