#!/usr/bin/env bash
# webview-guard.test: prove cloud-android-webview-guard.py FAILS on each defect and PASSES a clean fleet (#1371).
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-webview-guard.py"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK:?}"' EXIT
LIB="ab_cloud-libs-shared/libs/web-core/src/main/java/com/diegonmarcos/cloudlib/web"
APP="ac_cloud-demo/app/src/main/java/demo"

fixture() {  # $1 = dir
  mkdir -p "$1/$LIB" "$1/$APP" "$1/1_cicd/src/data"
  printf 'enum class WebProfile {\n    UNTRUSTED_HTML(1),\n    APP_PAGE(2),\n    WEB_WORLD(3),\n    BROWSER(4),\n}\n' > "$1/$LIB/WebProfile.kt"
  echo 'fun create(c: Context) = WebView(c).apply { settings.javaScriptEnabled = true }' > "$1/$LIB/CloudWeb.kt"
  cat > "$1/$APP/Page.kt" <<'KT'
// a comment may say WebView(ctx) and settings.javaScriptEnabled = true
fun page(c: Context) = CloudWeb.create(c, WebProfile.APP_PAGE).apply { webViewClient = object : CloudWebClient(WebProfile.APP_PAGE) {} }
class Body(c: Context) : WebView(c)
fun body(c: Context) = CloudWeb.configure(Body(c), WebProfile.UNTRUSTED_HTML)
KT
  cat > "$1/1_cicd/src/data/webview-guard.json" <<JSON
{"lib": "ab_cloud-libs-shared/libs/web-core", "profiles_file": "src/main/java/com/diegonmarcos/cloudlib/web/WebProfile.kt",
 "exclude": ["ac_cloud-vendored"], "pending": [],
 "usages": [{"app": "Demo", "usage": "page", "profile": "APP_PAGE", "file": "$APP/Page.kt"}]}
JSON
}
expect() {  # name want(0|1) dir
  python3 "$GUARD" "$3" >"$WORK/out" 2>&1; local rc=$?
  [ "$rc" = "$2" ] && ok "$1" || { fail "$1 (exit $rc, wanted $2)"; cat "$WORK/out"; }
}
mutate() {  # name text appended to a new app file
  local d="$WORK/$1"; fixture "$d"; printf '%s\n' "$2" > "$d/$APP/Bad.kt"; expect "$1 fails" 1 "$d"
}
policy() {  # name python-expression editing dict p
  local d="$WORK/$1"; fixture "$d"
  python3 -c "import json,sys; f=sys.argv[1]; p=json.load(open(f)); $2; json.dump(p, open(f,'w'))" "$d/1_cicd/src/data/webview-guard.json"
  expect "$1 fails" 1 "$d"
}

fixture "$WORK/clean"; expect "clean fleet passes" 0 "$WORK/clean"
mutate constructs-webview        'fun w(c: Context) = WebView(c)'
mutate constructs-webview-this   'class A : Activity() { val w = WebView(this) }'
mutate subclass-without-configure 'class B(c: Context) : WebView(c)'
mutate sets-javascript           'fun s(w: WebView) { w.settings.javaScriptEnabled = true }'
mutate sets-file-access          'fun s(w: WebView) { w.settings.allowFileAccess = true }'
mutate sets-mixed-content        'fun s(w: WebView) { w.settings.mixedContentMode = 0 }'
mutate sets-user-agent           'fun s(w: WebView) { w.settings.userAgentString = "x" }'
mutate sets-autoplay             'fun s(w: WebView) { w.settings.mediaPlaybackRequiresUserGesture = false }'
mutate renderer-priority         'fun s(w: WebView) { w.setRendererPriorityPolicy(1, true) }'
mutate bare-webviewclient        'fun s(w: WebView) { w.webViewClient = WebViewClient() }'
mutate bare-chromeclient         'fun s(w: WebView) { w.webChromeClient = object : WebChromeClient() {} }'
mutate java-setter               'class J { void s(WebView w) { w.getSettings().setJavaScriptEnabled(true); } }'
d="$WORK/undeclared"; fixture "$d"; echo 'fun x(c: Context) = CloudWeb.create(c, WebProfile.WEB_WORLD)' > "$d/$APP/World.kt"; expect "a web view with no declared usage fails" 1 "$d"
policy unknown-profile    'p["usages"][0]["profile"] = "KIOSK"'
policy profile-not-used   'p["usages"][0]["profile"] = "WEB_WORLD"'
policy missing-file       'p["usages"].append({"app":"X","usage":"y","profile":"APP_PAGE","file":"ac_cloud-x/Gone.kt"})'
policy stale-pending      'p["pending"] = ["ac_cloud-demo"]'
# the lib itself, an excluded vendored fork, a test source and a comment are not findings
d="$WORK/excluded"; fixture "$d"; mkdir -p "$d/ac_cloud-vendored/src" "$d/ac_cloud-demo/app/src/test/java"
echo 'fun v(c: Context) = WebView(c)' > "$d/ac_cloud-vendored/src/V.kt"; echo 'fun t(c: Context) = WebView(c)' > "$d/ac_cloud-demo/app/src/test/java/T.kt"
expect "lib, vendored fork and tests pass" 0 "$d"
# a pending app that still violates passes (it is the declared migration debt)
d="$WORK/pending"; fixture "$d"; echo 'fun w(c: Context) = WebView(c)' > "$d/$APP/Bad.kt"
python3 -c "import json,sys; f=sys.argv[1]; p=json.load(open(f)); p['pending']=['ac_cloud-demo']; json.dump(p, open(f,'w'))" "$d/1_cicd/src/data/webview-guard.json"
expect "a declared pending app passes" 0 "$d"

echo "webview-guard.test: $FAILURES failure(s)"
[ "$FAILURES" = 0 ]
