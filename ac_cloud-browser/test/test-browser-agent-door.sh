#!/usr/bin/env bash
# #913 - the fleet agent door to Cloud Browser: open a URL for the person (optionally in a tab group) and
# read one page's text, both behind the CONSTELLATION_DATA signature permission, both READ-ONLY.
#
#   A1  the manifest declares the open activity and the fetch provider exported AND guarded by the signature permission
#   A2  the fetch provider sends a GET only: no POST/PUT/DELETE, no request body, no cookie header, no CookieManager
#   A3  the contract refuses non-https, IP literals and local names before any lookup, and re-checks every redirect hop
#   A4  the open activity only files a tab and shows it: no WebView, no script, no form fill, no click
#   A5  the contract names the permission and authority the engine really declares (so Cloud Search and the browser agree)
#   MUT each property, broken on a copy, goes red
#
# OWN-SOURCE ONLY. python3 and grep only, no network, no build.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
D="$ROOT/ac_cloud-browser/app/src/main/java/com/diegonmarcos/cloudbrowser/agentapi"
MANIFEST="$ROOT/ac_cloud-browser/app/src/main/AndroidManifest.xml"
CONTRACT="$D/AgentApiContract.kt"; FETCH="$D/AgentFetchProvider.kt"; OPEN="$D/AgentOpenActivity.kt"
SEARCH_BJ="$ROOT/ac_cloud-search/build.json"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for r in "$MANIFEST" "$CONTRACT" "$FETCH" "$OPEN" "$SEARCH_BJ"; do
    [ -f "$r" ] || { echo "ERROR missing source: $r - this tester is unrun, not passing"; exit 1; }
done
_code() { grep -vE '^[[:space:]]*(\*|//|/\*)' "$1"; }

a1() {
    python3 - "$1" <<'PY'
import re, sys
s = open(sys.argv[1], encoding="utf-8").read(); bad = 0
for tag, name in (("activity", ".agentapi.AgentOpenActivity"), ("provider", ".agentapi.AgentFetchProvider")):
    m = re.search(r'<%s\b[^>]*%s[^>]*?>' % (tag, re.escape(name)), s, re.S)
    if not m: print("    %s is not declared" % name); bad = 1; continue
    t = m.group(0)
    if 'android:exported="true"' not in t: print("    %s is not exported - no fleet app could reach it" % name); bad = 1
    if 'android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"' not in t:
        print("    %s is not guarded by the signature permission - any app could call it" % name); bad = 1
if "com.diegonmarcos.cloudbrowser.action.AGENT_OPEN" not in s: print("    the open action is not declared"); bad = 1
if "${applicationId}.agentapi" not in s: print("    the fetch authority is not ${applicationId}.agentapi"); bad = 1
sys.exit(bad)
PY
}
a2() {
    local f="$1" bad=0
    grep -qE 'requestMethod = "GET"' "$f" || { echo "    the fetch is not a GET"; bad=1; }
    local w; w="$(_code "$f" | grep -nE '"(POST|PUT|DELETE|PATCH)"|doOutput|outputStream|getOutputStream|setRequestProperty\("Cookie"|CookieManager|setDoOutput|FormBody|HttpPost' || true)"
    [ -z "$w" ] || { echo "    the fetch writes or carries a session:"; printf '%s\n' "$w" | sed 's/^/        /'; bad=1; }
    return $bad
}
a3() {
    local c="$1" f="$2" bad=0
    grep -qE 'uri\.scheme\?\.lowercase\(\) != "https"' "$c" || { echo "    https-only is not enforced"; bad=1; }
    grep -qE 'an IP address is refused' "$c" || { echo "    IP literals are not refused"; bad=1; }
    grep -qE 'localhost' "$c" || { echo "    local names are not refused"; bad=1; }
    grep -qE 'fun addressRefusal' "$c" && grep -qE 'AgentApiContract\.addressRefusal\(a\)' "$f" || { echo "    a resolved private address is not refused"; bad=1; }
    grep -qE 'AgentApiContract\.hopRefusal\(url\)' "$f" || { echo "    a redirect hop is not re-checked"; bad=1; }
    grep -qE 'instanceFollowRedirects = false' "$f" || { echo "    redirects are followed by the platform, unchecked"; bad=1; }
    return $bad
}
a4() {
    local f="$1" bad=0
    local w; w="$(_code "$f" | grep -nE 'WebView|evaluateJavascript|loadUrl|fill_form|agent_fill|agent_click|performClick|CookieManager|HttpURLConnection' || true)"
    [ -z "$w" ] || { echo "    the open activity does more than show a page:"; printf '%s\n' "$w" | sed 's/^/        /'; bad=1; }
    grep -qE 'tabs\.setGroup\(parsed\.url, parsed\.group\)' "$f" || { echo "    the group is not applied"; bad=1; }
    return $bad
}
a5() {
    local c="$1" bad=0
    grep -qE 'PERMISSION = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"' "$c" || { echo "    the contract names another permission"; bad=1; }
    python3 - "$SEARCH_BJ" "$c" <<'PY' || bad=1
import json, re, sys
j = json.load(open(sys.argv[1]))["search"]["agents"]["engines"]["browser"]
k = open(sys.argv[2]).read(); bad = 0
for key, const in (("authority_suffix", "AUTHORITY_SUFFIX"), ("open_action", "ACTION_OPEN"), ("fetch_method", "METHOD_FETCH_TEXT")):
    m = re.search(r'const val %s = "([^"]+)"' % const, k)
    if not m or m.group(1) != j.get(key):
        print("    search.agents.engines.browser.%s=%r but the engine says %r" % (key, j.get(key), m and m.group(1))); bad = 1
sys.exit(bad)
PY
    return $bad
}

echo "-- A1 the door is exported behind the signature permission --"
a1 "$MANIFEST" && pass "activity and provider exported, signature-guarded, on the declared action and authority" || fail "the door is unguarded or undeclared"
echo "-- A2 the fetch is a bare GET --"
a2 "$FETCH" && pass "GET only: no body, no cookie, no verbs that write" || fail "the fetch can write or carries a session"
echo "-- A3 the fetch stays on the public internet --"
a3 "$CONTRACT" "$FETCH" && pass "https only, no IP literals or local names, resolved addresses and every redirect hop re-checked" || fail "the fetch can be aimed at the phone's network"
echo "-- A4 the open activity only shows a page --"
a4 "$OPEN" && pass "files a tab in the group and starts the browser; no script, fill or click" || fail "the open activity does more than show a page"
echo "-- A5 Cloud Search and the engine agree --"
a5 "$CONTRACT" && pass "search.agents.engines.browser matches the engine's constants" || fail "the declared contract and the engine disagree"

MUT="$(mktemp -d)"; trap 'rm -rf "$MUT"' EXIT
HOLLOW=0
_red() { local label="$1"; shift; if "$@" >/dev/null 2>&1; then echo "  MUT-HOLLOW  $label"; HOLLOW=$((HOLLOW+1)); else echo "  MUT-RED     $label"; fi; }
_mut() { # file py-replace-old py-replace-new
    python3 -c 'import sys;p,o,n=sys.argv[1:4];s=open(p).read();assert o in s,"mutation target absent: "+o;open(p,"w").write(s.replace(o,n,1))' "$@"
}
echo "-- MUT --"
cp "$MANIFEST" "$MUT/m.xml"; _mut "$MUT/m.xml" 'android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"
            android:excludeFromRecents' 'android:excludeFromRecents'; _red "A1 the open activity loses its permission" a1 "$MUT/m.xml"
cp "$MANIFEST" "$MUT/m.xml"; _mut "$MUT/m.xml" 'android:authorities="${applicationId}.agentapi"
            android:exported="true"
            android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"' 'android:authorities="${applicationId}.agentapi"
            android:exported="true"'; _red "A1 the fetch provider loses its permission" a1 "$MUT/m.xml"
cp "$FETCH" "$MUT/f.kt"; _mut "$MUT/f.kt" 'requestMethod = "GET"' 'requestMethod = "POST"; doOutput = true'; _red "A2 the fetch becomes a POST" a2 "$MUT/f.kt"
cp "$FETCH" "$MUT/f.kt"; _mut "$MUT/f.kt" 'c.setRequestProperty("Accept-Language"' 'c.setRequestProperty("Cookie", "a=b"); c.setRequestProperty("Accept-Language"'; _red "A2 the fetch carries a cookie" a2 "$MUT/f.kt"
cp "$FETCH" "$MUT/f.kt"; _mut "$MUT/f.kt" 'instanceFollowRedirects = false' 'instanceFollowRedirects = true'; _red "A3 redirects followed unchecked" a3 "$CONTRACT" "$MUT/f.kt"
cp "$FETCH" "$MUT/f.kt"; _mut "$MUT/f.kt" 'AgentApiContract.addressRefusal(a)' 'null'; _red "A3 resolved addresses unchecked" a3 "$CONTRACT" "$MUT/f.kt"
cp "$CONTRACT" "$MUT/c.kt"; _mut "$MUT/c.kt" 'uri.scheme?.lowercase() != "https"' 'false'; _red "A3 https-only dropped" a3 "$MUT/c.kt" "$FETCH"
cp "$CONTRACT" "$MUT/c.kt"; python3 -c 'import sys;p=sys.argv[1];s=open(p).read();open(p,"w").write(s.replace("an IP address is refused","ok"))' "$MUT/c.kt"; _red "A3 IP literals allowed" a3 "$MUT/c.kt" "$FETCH"
cp "$OPEN" "$MUT/o.kt"; _mut "$MUT/o.kt" 'finish()' 'android.webkit.WebView(this).evaluateJavascript("document.forms[0].submit()", null); finish()'; _red "A4 the open activity runs a script" a4 "$MUT/o.kt"
cp "$OPEN" "$MUT/o.kt"; _mut "$MUT/o.kt" 'tabs.setGroup(parsed.url, parsed.group)' 'Unit'; _red "A4 the group is dropped" a4 "$MUT/o.kt"
cp "$CONTRACT" "$MUT/c.kt"; _mut "$MUT/c.kt" 'const val AUTHORITY_SUFFIX = "agentapi"' 'const val AUTHORITY_SUFFIX = "agents"'; _red "A5 the authority drifts from the declaration" a5 "$MUT/c.kt"
cp "$CONTRACT" "$MUT/c.kt"; _mut "$MUT/c.kt" 'PERMISSION = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"' 'PERMISSION = "android.permission.INTERNET"'; _red "A5 another permission" a5 "$MUT/c.kt"

[ "$HOLLOW" -eq 0 ] || FAILURES=$((FAILURES + HOLLOW))
echo "-- agent door (browser): $FAILURES failure(s) --"
[ "$FAILURES" -eq 0 ]
