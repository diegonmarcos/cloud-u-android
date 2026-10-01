#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #684 — cloud-browser ANSWERS the fleet auth mission, guarded by a ║
# ║ signature permission, reading the DECLARED contract, no literals   ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# cloud-browser is the fleet's browser, so a fleet app (cloud-drive Sync ▸ Git)
# fires an AUTH MISSION at it: open a sign-in page full-screen, capture the
# session cookie, and return it to the CALLER only. What this pins, each on
# the message:
#
#   M1  the mission contract is DECLARED by libs:auth, not by this app
#       (ab_cloud-libs-shared/build.json::auth.browser_mission), and this app
#       BAKES it (AUTH_MISSION_B64) with {package} resolved to itself.
#   M2  the answerer activity reads the DECLARED extra/result keys off that
#       baked contract — no extra/result key literal in the Kotlin.
#   M3  the activity is GUARDED by the signature-level permission: exported,
#       android:permission set to it, and the permission declared signature.
#   M4  it confines navigation to the declared hosts, returns the cookie for
#       the declared cookie_url, and REFUSES any other capture. #689 deleted
#       the OAuth redirect-landing capture with the GitHub OAuth-App flow it
#       served; a redirect capture coming back, in the contract or in the
#       activity, is red.
#   M5  every outcome is one of the declared set (captured/cancelled/refused).
#   MUT each property, broken on a copy, goes red.
#
# OWN-SOURCE ONLY. python3 and grep only, no network, no build.
set -eu

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
APP="$ROOT/ac_cloud-browser"
SHARED_BJ="$ROOT/ab_cloud-libs-shared/build.json"
GRADLE="$APP/app/build.gradle"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"
ACT="$APP/app/src/main/java/com/diegonmarcos/cloudbrowser/AuthMissionActivity.kt"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for r in "$SHARED_BJ" "$GRADLE" "$MANIFEST" "$ACT"; do
    [ -f "$r" ] || { echo "ERROR missing source: $r — this tester is unrun, not passing"; exit 1; }
done
_code() { grep -vE '^[[:space:]]*(\*|//|/\*)' "$1"; }

# m1 <shared bj> : the contract is declared by libs:auth, closed
m1() {
    python3 - "$1" <<'PYTHON'
import json, sys
m = json.load(open(sys.argv[1]))["auth"].get("browser_mission")
bad = 0
if not isinstance(m, dict):
    print("    auth.browser_mission is not declared — the browser has no contract to answer"); sys.exit(1)
if (m.get("fleet") or "") != "browser":
    print("    browser_mission.fleet is %r; it must resolve to the fleet browser" % m.get("fleet")); bad = 1
for k in ("action", "permission"):
    if "{package}" not in (m.get(k) or ""):
        print("    browser_mission.%s is not templated on {package}: %r" % (k, m.get(k))); bad = 1
ex = m.get("extras") or {}; rs = m.get("results") or {}
for k in ("url", "title", "allow_hosts", "capture", "cookie_url"):
    if k not in ex: print("    extras is missing %s" % k); bad = 1
for k in ("outcome", "cookie", "why"):
    if k not in rs: print("    results is missing %s" % k); bad = 1
# #689 the session cookie is the ONE capture: the redirect landing existed for the GitHub
# OAuth-App flow alone and went with it.
if (m.get("captures") or []) != ["cookie"]:
    print("    captures are %r; the session cookie is the only declared capture" % m.get("captures")); bad = 1
for k in list(ex) + list(rs):
    if "redirect" in k.lower():
        print("    the contract still carries a redirect key: %s" % k); bad = 1
if sorted(m.get("outcomes") or []) != ["cancelled", "captured", "refused"]:
    print("    outcomes are %r; captured/cancelled/refused expected" % m.get("outcomes")); bad = 1
sys.exit(1 if bad else 0)
PYTHON
}

# m2 <gradle> <activity> : the app bakes the contract and reads DECLARED keys
m2() {
    local gradle="$1" act="$2" bad=0
    grep -qE 'auth\?\.browser_mission' "$gradle" \
        || { echo "    the gradle does not read the shared auth.browser_mission"; bad=1; }
    grep -qE 'buildConfigField "String", "AUTH_MISSION_B64"' "$gradle" \
        || { echo "    the contract is not baked into BuildConfig"; bad=1; }
    grep -qE "replace\('\{package\}', missionPkg\)" "$gradle" \
        || { echo "    {package} is not resolved to this app at bake time"; bad=1; }
    grep -qE 'BuildConfig\.AUTH_MISSION_B64' "$act" \
        || { echo "    the activity does not read the baked contract"; bad=1; }
    grep -qE 'ex\.optString\("url"\)' "$act" && grep -qE 'ex\.optString\("capture"\)' "$act" \
        && grep -qE 'r\.optString\("outcome"\)' "$act" \
        || { echo "    the activity does not read the declared extra/result keys off the contract"; bad=1; }
    # NO extra/result key literal as an intent key in the Kotlin: the keys are the contract's.
    local lit
    lit="$(_code "$act" | grep -nE 'getStringExtra\("|putExtra\("' || true)"
    [ -z "$lit" ] || { echo "    the activity uses a literal intent key instead of the declared one:"; printf '%s\n' "$lit" | sed 's/^/        /'; bad=1; }
    return $bad
}

# m3 <manifest> : the activity is guarded by the signature permission
m3() {
    local mf="$1" bad=0
    python3 - "$mf" <<'PYTHON' || bad=1
import re, sys
s = open(sys.argv[1], encoding="utf-8").read()
bad = 0
# the permission is declared signature-level
perm = re.search(r'<permission\b[^>]*android:name="\$\{authMissionPermission\}"[^>]*?/>', s, re.S)
if not perm:
    print("    the signature permission is not declared with the placeholder name"); bad = 1
elif 'android:protectionLevel="signature"' not in perm.group(0):
    print("    the auth-mission permission is not protectionLevel=signature — any app could start it"); bad = 1
# the activity is exported, guarded, and answers the declared action
act = re.search(r'<activity\b[^>]*AuthMissionActivity.*?</activity>', s, re.S)
if not act:
    print("    AuthMissionActivity is not declared"); sys.exit(1)
a = act.group(0)
if 'android:exported="true"' not in a:
    print("    the activity is not exported — a cross-app mission could not reach it"); bad = 1
if 'android:permission="${authMissionPermission}"' not in a:
    print("    the activity is not guarded by the auth-mission permission"); bad = 1
if '${authMissionAction}' not in a:
    print("    the activity does not answer the declared action"); bad = 1
sys.exit(1 if bad else 0)
PYTHON
    return $bad
}

# m4 <activity> : navigation is confined, the cookie is the one capture, any other is refused
m4() {
    local act="$1" bad=0
    grep -qE 'if \(capture != CAPTURE_COOKIE\) \{ refuse\(' "$act" \
        || { echo "    a mission asking for another capture is not refused"; bad=1; }
    [ "$(_code "$act" | grep -ciE 'redirect')" -eq 0 ] \
        || { echo "    the activity still captures a redirect landing (#689 deleted it with the OAuth-App flow)"; bad=1; }
    grep -qE 'fun allowed\(' "$act" && grep -qE 'host == a \|\| host\.endsWith\("\.\$a"\)' "$act" \
        || { echo "    navigation is not confined to the declared hosts"; bad=1; }
    grep -qE 'CookieManager\.getInstance\(\)\.getCookie\(cookieUrl\)' "$act" \
        || { echo "    a cookie capture does not read the cookie for the declared cookie_url"; bad=1; }
    # the capture reaches the caller as an activity RESULT, never a broadcast/log
    grep -qE 'setResult\(Activity\.RESULT_OK' "$act" \
        || { echo "    the capture is not returned as an activity result"; bad=1; }
    local leak
    leak="$(_code "$act" | grep -nE 'Log\.[a-z]+\(|sendBroadcast|println\(' || true)"
    [ -z "$leak" ] || { echo "    the activity logs or broadcasts (the capture must reach the caller only):"; printf '%s\n' "$leak" | sed 's/^/        /'; bad=1; }
    return $bad
}

# m5 <activity> : every outcome is one of the declared set
m5() {
    local act="$1" bad=0
    for o in captured cancelled refused; do
        grep -qE "OUTCOME_${o^^} = \"$o\"" "$act" \
            || { echo "    the activity has no '$o' outcome"; bad=1; }
    done
    return $bad
}

echo "── M1 the contract is declared by libs:auth ──"
m1 "$SHARED_BJ" && pass "auth.browser_mission is declared, closed, and templated on {package}" || fail "the mission contract is missing or half-declared"
echo "── M2 the app bakes it and reads DECLARED keys ──"
m2 "$GRADLE" "$ACT" && pass "the contract is baked with {package} resolved and the activity reads the declared keys, no literals" || fail "the app hardcodes a key or does not bake the contract"
echo "── M3 the activity is guarded by the signature permission ──"
m3 "$MANIFEST" && pass "the activity is exported, guarded by the signature-level auth-mission permission, and answers the declared action" || fail "the mission activity is unguarded or not declared as data"
echo "── M4 navigation confined, cookie only, result-only ──"
m4 "$ACT" && pass "navigation stays on the declared hosts, the cookie for cookie_url is the one capture, any other is refused, and the capture is a result — never logged" || fail "the capture escapes its confinement or its result contract"
echo "── M5 the outcomes are the declared set ──"
m5 "$ACT" && pass "captured / cancelled / refused" || fail "an outcome is missing"

# ══ MUT ════════════════════════════════════════════════════════════════════
MUT="$(mktemp -d)"; trap 'rm -rf "$MUT"' EXIT
MUTATIONS=0; HOLLOW=0
_red() { local label="$1"; shift; MUTATIONS=$((MUTATIONS+1)); if "$@" >/dev/null 2>&1; then echo "  MUT-HOLLOW  $label"; HOLLOW=$((HOLLOW+1)); else echo "  MUT-RED     $label"; fi; }
_green() { local label="$1"; shift; "$@" >/dev/null 2>&1 && return 0; echo "  MUT-VOID    $label"; HOLLOW=$((HOLLOW+1)); return 1; }
# #689 a mutation that did not mutate reports the same green as a working check: prove the
# copy differs from the original AND carries the planted text before calling anything red.
_applied() {
    python3 -c 'import sys; a, b = (open(f, "rb").read() for f in sys.argv[1:3]); sys.exit(0 if a != b and sys.argv[3].encode() in b else 1)' "$1" "$2" "$3" \
        || { echo "  MUT-NOOP    the mutation did not apply ($3)"; HOLLOW=$((HOLLOW+1)); return 1; }
}

echo "── MUT ──"
cp "$ACT" "$MUT/act.kt"
_green m2 m2 "$GRADLE" "$MUT/act.kt" && {
    python3 -c "import sys;p=sys.argv[1];s=open(p).read().replace('intent.getStringExtra(ex.optString(\"url\"))','intent.getStringExtra(\"literal.url\")');open(p,'w').write(s)" "$MUT/act.kt"
    _red "M2 a literal intent key instead of the declared one" m2 "$GRADLE" "$MUT/act.kt"; }
cp "$MANIFEST" "$MUT/AndroidManifest.xml"
_green m3 m3 "$MUT/AndroidManifest.xml" && {
    python3 -c "import sys;p=sys.argv[1];s=open(p).read().replace('android:protectionLevel=\"signature\"','android:protectionLevel=\"normal\"');open(p,'w').write(s)" "$MUT/AndroidManifest.xml"
    _red "M3 the permission downgraded from signature (any app could sign in as the owner)" m3 "$MUT/AndroidManifest.xml"; }
cp "$MANIFEST" "$MUT/AndroidManifest.xml"
_green m3 m3 "$MUT/AndroidManifest.xml" && {
    python3 -c "import sys;p=sys.argv[1];s=open(p).read().replace('android:permission=\"\${authMissionPermission}\"\n            android:label','android:label');open(p,'w').write(s)" "$MUT/AndroidManifest.xml"
    _red "M3 the activity's permission guard removed" m3 "$MUT/AndroidManifest.xml"; }
cp "$SHARED_BJ" "$MUT/shared.json"
_green m1 m1 "$MUT/shared.json" && {
    python3 -c "import json,sys;p=sys.argv[1];d=json.load(open(p));m=d['auth']['browser_mission'];m['captures'].append('redirect');m['extras']['redirect_prefix']='{package}.extra.REDIRECT_PREFIX';json.dump(d,open(p,'w'))" "$MUT/shared.json"
    _applied "$SHARED_BJ" "$MUT/shared.json" REDIRECT_PREFIX && _red "M1 the OAuth redirect capture re-declared in the mission contract" m1 "$MUT/shared.json"; }
cp "$ACT" "$MUT/act.kt"
_green m4 m4 "$MUT/act.kt" && {
    python3 -c "import sys;p=sys.argv[1];s=open(p).read().replace('if (capture != CAPTURE_COOKIE) { refuse(','if (false) { refuse(');open(p,'w').write(s)" "$MUT/act.kt"
    _applied "$ACT" "$MUT/act.kt" 'if (false) { refuse(' && _red "M4 another capture is no longer refused" m4 "$MUT/act.kt"; }
cp "$ACT" "$MUT/act.kt"
_green m4 m4 "$MUT/act.kt" && {
    printf '\n    private fun returnRedirect(url: String) = url\n' >>"$MUT/act.kt"
    _applied "$ACT" "$MUT/act.kt" returnRedirect && _red "M4 a redirect-landing capture re-added to the activity" m4 "$MUT/act.kt"; }
cp "$ACT" "$MUT/act.kt"
_green m4 m4 "$MUT/act.kt" && {
    printf '\n    private fun leak() { android.util.Log.d("x", "cookie") }\n' >>"$MUT/act.kt"
    _red "M4 a log line added (the capture must reach the caller only)" m4 "$MUT/act.kt"; }

echo "── $MUTATIONS mutations, $HOLLOW hollow/void ──"
[ "$HOLLOW" -eq 0 ] || FAILURES=$((FAILURES + HOLLOW))

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-browser-auth-mission: all checks passed"; else echo "test-browser-auth-mission: $FAILURES check(s) FAILED"; exit 1; fi
