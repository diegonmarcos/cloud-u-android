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
# The prefix was widened from "app://" to "app:" when the Power Saving theme's
# home_apps started writing the bare opaque spelling `app:com.x` — Uri.parse
# gives that form a null host AND a null authority, so the hierarchical-only
# check let it fall through to Intent.parseUri and open nothing. "app:" is the
# superset: it still matches every `app://` link this test was written for.
has 'uri.startsWith("app:")'                   "$SHELL_KT" "app: (both the // and the opaque spelling) resolves a package via PackageManager"
has 'u.schemeSpecificPart'                     "$SHELL_KT" "the opaque spelling's package is read from the scheme-specific part"
has 'Intent.parseUri'                          "$SHELL_KT" "intent:// and custom schemes still go through Intent.parseUri"
has 'tileId.startsWith("extapp:")'             "$SHELL_KT" "extapp: still routes to launchExternalApp"

echo "== T3: the embedded browser has a Back affordance on BOTH inputs =="
has 'OnBackPressedCallback'   "$WEB_KT" "system back gesture is wired (OnBackPressedCallback)"
has '.clickable { goBackOrClose() }' "$WEB_KT" "on-screen control is wired to the same action"
has 'canGoBack'               "$WEB_KT" "Back consults WebView.canGoBack"
has 'goBack()'                "$WEB_KT" "Back steps through WebView history"
has 'onBackPressedDispatcher.onBackPressed()' "$WEB_KT" "with no history left, Back closes the browser"
has 'R.string.action_back'    "$WEB_KT" "the control carries a contentDescription (screen readers)"

# The other reported page was PM Boards, and #311 removed that tile from
# Projects W — the only group that ever defined it. It is checked here no longer
# because it no longer exists, not because the rule stopped applying: the rule is
# about the dispatcher branch, which MySocials still exercises.
echo "== T4: the reported page is a plain URL — engine-routed, not special-cased =="
# MySocials left the Me row (owner order, Cloud Me restructure); the rule is about the dispatcher branch, which
# every https tile of Cloud > Apps exercises, so the check now holds each of them to a plain http(s) URL.
python3 - "$BJ" <<'PY' && ok "web links of the Lnktree pages have plain http(s) URL targets" || bad "no web tile is left, or one is not a plain URL target"
import json,sys
d=json.load(open(sys.argv[1]))
# #1331: the Lnktree row (was Projects W, the last group of https repo tiles) became in-app pages. What exercises the dispatcher's
# https branch now is a Lnktree page ENTRY: kind=entry panels with a declared source resolve (LnktreeTargets) to an http(s)
# URL and are opened through the same onOpen. So the check holds every such entry to a source that yields a plain http(s) URL.
found={}
for s_ in d["ui"]["sections"]:
    for k,stack in s_.items():
        if k.startswith("stack_") and isinstance(stack,list):
            for e in stack:
                if e.get("kind")=="entry" and not e.get("planned"):
                    if e.get("site"): found[s_["id"]+"/"+e["title"]]="https://linktree-label"
                    elif e.get("registry"): found[s_["id"]+"/"+e["title"]]=d["ui"]["app_urls"]["registry_sites"][e["registry"]]
                    elif e.get("repo"): found[s_["id"]+"/"+e["title"]]=d["ui"]["app_urls"]["repo_base"]+e["repo"]
# #1335: the Lnktree tiles now launch the Lnktree family apps (extapp:), whose entries open their own links; no entry is left in the SuperApp,
# so the loop below is vacuous until a page declares one again (the generic kind=entry renderer and LnktreeTargets stay).
for i,t in found.items():
    assert " " not in t and "#Intent" not in t, (i,t)
PY
lacks 'mysocials' "$SHELL_KT" "no per-page special case for MySocials in the dispatcher"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
