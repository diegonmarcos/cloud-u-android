#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #705 — an ENGINE that an app binds instead of compiling must be bindable,  ║
# ║ versioned, guarded and able to run, from its own Cloud-Lib APK alone       ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# Once an app stops compiling an engine (cloud-drive no longer links libs:gh), the
# app's ship stops watching the engine's directory. This repo's ship (Cloud Libs)
# is then the ONLY pipeline that runs when the engine changes, so the engine's
# half of the contract is checked here or nowhere.
#
# Engines are DISCOVERED, never listed: every module under the scan root whose
# manifest declares the engine CONTRACT meta-data on a service is one.
#
#   E1  the service is exported and guarded by CONSTELLATION_DATA (signature).
#   E2  a client can FIND it: an intent-filter action of exactly ${applicationId}.ENGINE.
#   E3  it declares its CONTRACT as a positive integer.
#   E4  its class extends DataBackendService -- or, for an engine whose input is
#       not a string (the image scan takes a file descriptor), is a Service whose
#       binder is its typed wire's Stub and answers methods() with methodNames()
#       -- and methodNames() names exactly the methods dispatch() answers — a method the engine answers but does not
#       list cannot be found by a client degrading knowingly, and one it lists
#       but does not answer is a contract it does not keep.
#   E5  the module compiles against libs:core (the base class and the permission).
#   E6  an engine that EXECS a binary out of nativeLibraryDir is listed in
#       lib_apks.exec_native, so its APK extracts the .so; every exec_native name
#       is a real module.
#   E7  the gh engine holds INTERNET: gh's whole job is talking to GitHub, from
#       the engine's process now.
#   E9  #729 the gh engine holds its own network for a sign-in: LOGIN_START
#       builds the GhLogin job with GhLoginKeeper.hold, and the manifest declares
#       that keeper as a specialUse foreground service with both permissions.
#       gh polls GitHub while the user is in the browser; Android 15 cuts the
#       network of a backgrounded process and the poll died as a DNS failure.
#   E8  every engine an app BINDS by handshake is found here: gh (Cloud Drive),
#       cal (Cloud Me and Cloud Agenda, engine-apk-split move 3), feed (SuperApp,
#       move 4), news (Cloud News, move 5) and ml-l-image-mlkit (the image scan
#       Drive, Mail, Camera, Media Center and Office reach through libs:ml-l-image,
#       move 6) and calc (Cloud Calc, #767). An engine whose contract meta-data
#       went missing would drop out of every check above without a word.
#   MUT each property, broken on a copy (and proven broken), goes red.
#
# OWN-SOURCE ONLY: reads ab_cloud-libs-shared/libs and lib-apks, nothing an app owns.
# python3 and grep only, no network, no build.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
SHARED="$ROOT/ab_cloud-libs-shared"
LIBS="$SHARED/libs"
BJ="$SHARED/lib-apks/build.json"
GH="$LIBS/gh"
for required in "$BJ" "$GH/src/main/AndroidManifest.xml" "$GH/build.gradle" "$LIBS/cal/src/main/AndroidManifest.xml" "$LIBS/feed/src/main/AndroidManifest.xml" \
                "$LIBS/news/src/main/AndroidManifest.xml" "$LIBS/calc/src/main/AndroidManifest.xml" \
                "$LIBS/ml-l-image-mlkit/src/main/AndroidManifest.xml" \
                "$GH/src/main/java/com/diegonmarcos/cloudlib/gh/GhBackendService.kt"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

# engines <libs root> <lib-apks build.json> : every check, on whatever tree it is handed
engines() {
    python3 - "$1" "$2" <<'PYTHON'
import json, os, re, sys
import xml.etree.ElementTree as ET

libs, bj_path = sys.argv[1], sys.argv[2]
A = "{http://schemas.android.com/apk/res/android}"
CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"
GUARD = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"
bad = 0
def no(msg):
    global bad
    print("    " + msg); bad = 1

exec_native = {k for k in json.load(open(bj_path, encoding="utf-8"))["lib_apks"].get("exec_native", {}) if not k.startswith("_")}
modules = sorted(d for d in os.listdir(libs) if os.path.isfile(os.path.join(libs, d, "build.gradle")))
for name in sorted(exec_native - set(modules)):
    no("lib_apks.exec_native names %r, which is not a module under the scan root" % name)

def sources(module):
    root = os.path.join(libs, module, "src", "main")
    for dirpath, _, files in os.walk(root):
        for f in files:
            if f.endswith((".kt", ".java")):
                yield os.path.join(dirpath, f)

def code(path):
    """The file with comment lines dropped: prose may NAME what code must not do."""
    return "\n".join(l for l in open(path, encoding="utf-8").read().split("\n")
                     if not re.match(r"\s*(\*|//|/\*)", l))

found = []
for module in modules:
    mf = os.path.join(libs, module, "src", "main", "AndroidManifest.xml")
    if not os.path.isfile(mf):
        continue
    try:
        tree = ET.parse(mf)
    except ET.ParseError as e:
        no("%s's manifest does not parse: %s" % (module, e)); continue
    for svc in tree.getroot().iter("service"):
        metas = {m.get(A + "name"): m.get(A + "value") for m in svc.iter("meta-data")}
        if CONTRACT_KEY not in metas:
            continue
        cls = svc.get(A + "name") or ""
        found.append(module)
        # E1
        if svc.get(A + "exported") != "true":
            no("E1 %s: %s declares a contract but is not exported — no client can bind it" % (module, cls))
        if svc.get(A + "permission") != GUARD:
            no("E1 %s: %s is not guarded by %s — any installed app could drive it" % (module, cls, GUARD))
        # E2
        actions = [a.get(A + "name") for f in svc.iter("intent-filter") for a in f.iter("action")]
        if "${applicationId}.ENGINE" not in actions:
            no("E2 %s: %s has no ${applicationId}.ENGINE action (has %s) — a client resolves engines by it" % (module, cls, actions))
        # E3
        v = metas.get(CONTRACT_KEY) or ""
        if not re.fullmatch(r"[1-9][0-9]*", v):
            no("E3 %s: the contract %r is not a positive integer" % (module, v))
        # E4
        simple = cls.rsplit(".", 1)[-1]
        src = [p for p in sources(module) if re.search(r"class\s+%s\b" % re.escape(simple), code(p))]
        if len(src) != 1:
            no("E4 %s: %s is declared but its class is not in the module's sources" % (module, cls)); continue
        text = code(src[0])
        typed = re.search(r"class\s+%s\s*:\s*Service\(\)" % re.escape(simple), text) \
            and re.search(r"object\s*:\s*I\w+\.Stub\(\)", text) \
            and re.search(r"override\s+fun\s+methods\(\)\s*:\s*Array<String>\s*=\s*methodNames\(\)", text)
        if not re.search(r"class\s+%s\s*:\s*DataBackendService\(\)" % re.escape(simple), text) and not typed:
            no("E4 %s: %s neither extends DataBackendService nor serves a typed wire Stub whose methods() is methodNames()" % (module, simple))
        consts = dict(re.findall(r'const\s+val\s+(\w+)\s*=\s*"([^"]*)"', text))
        def resolve(tok):
            tok = tok.strip()
            if tok.startswith('"') and tok.endswith('"'):
                return tok[1:-1]
            return consts.get(tok, "?" + tok)
        m = re.search(r"fun\s+methodNames\(\)\s*:\s*Array<String>\s*=\s*arrayOf\(([^)]*)\)", text)
        listed = {resolve(t) for t in m.group(1).split(",") if t.strip()} if m else set()
        if not listed:
            no("E4 %s: methodNames() names no method" % module)
        d = re.search(r"fun\s+dispatch\(.*?\)\s*:\s*String\s*=\s*when\s*\(\s*method\s*\)\s*\{(.*?)\n    \}", text, re.S)
        answered = set()
        if not d:
            no("E4 %s: dispatch() is not a `when (method)` this tester can read" % module)
        else:
            for label in re.findall(r"^\s{8}([A-Za-z_\"][^\n]*?)\s*->", d.group(1), re.M):
                if label.strip() != "else":
                    answered |= {resolve(t) for t in label.split(",")}
        if listed and d and listed != answered:
            no("E4 %s: methodNames() lists %s but dispatch answers %s" % (module, sorted(listed), sorted(answered)))
        unresolved = sorted(x for x in listed | answered if x.startswith("?"))
        if unresolved:
            no("E4 %s: method names that are not string constants: %s" % (module, unresolved))
        # E5
        gradle = open(os.path.join(libs, module, "build.gradle"), encoding="utf-8").read()
        if not re.search(r"""^\s*(api|implementation)\s+project\(['"]:libs:core['"]\)""", gradle, re.M):
            no("E5 %s: does not compile against libs:core — DataBackendService and CONSTELLATION_DATA live there" % module)
        # E6
        execs = any("applicationInfo.nativeLibraryDir" in code(p) for p in sources(module)) and \
                any("ProcessBuilder(" in code(p) for p in sources(module))
        if execs and module not in exec_native:
            no("E6 %s: its engine execs a binary out of nativeLibraryDir but lib_apks.exec_native does not list it — "
               "the APK would keep the .so stored and the exec would find nothing" % module)
        # E7
        if module == "gh":
            perms = [p.get(A + "name") for p in tree.getroot().iter("uses-permission")]
            if "android.permission.INTERNET" not in perms:
                no("E7 gh: the engine runs gh, which talks to GitHub, without INTERNET")
            # E9
            for perm in ("android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"):
                if perm not in perms:
                    no("E9 gh: the sign-in keeper cannot run in the foreground without %s" % perm)
            keepers = [s for s in tree.getroot().iter("service") if (s.get(A + "name") or "").endswith(".GhLoginKeeper")]
            if len(keepers) != 1 or "specialUse" not in (keepers[0].get(A + "foregroundServiceType") or "") \
                    or keepers[0].get(A + "exported") != "false":
                no("E9 gh: GhLoginKeeper is not declared once as a non-exported specialUse foreground service")
            if not re.search(r"GhLogin\(host,\s*hold\s*=\s*\{\s*GhLoginKeeper\.hold\(", text):
                no("E9 gh: LOGIN_START starts gh's sign-in without GhLoginKeeper.hold — its poll loses the network "
                   "the moment the browser is up")

for must in ("gh", "cal", "feed", "news", "ml-l-image-mlkit", "calc"):
    if must not in found:
        no("E8 no %s engine was found — the contract meta-data or the service moved, so every check above ran without it" % must)
print("    engines: %s" % found)
sys.exit(1 if bad else 0)
PYTHON
}

echo "── E1-E9 every engine on the shelf is bindable, versioned, guarded and runnable ──"
engines "$LIBS" "$BJ" && pass "every engine service is exported and signature-guarded, findable by \${applicationId}.ENGINE, versioned, lists exactly what it answers, links core, extracts what it execs; gh holds INTERNET and its sign-in holds the network" \
    || fail "an engine on the shelf cannot be bound, found, versioned or run as declared"

# ══ MUT ════════════════════════════════════════════════════════════════════
MUT="$(mktemp -d)"; trap 'rm -rf "$MUT"' EXIT
MUTATIONS=0; HOLLOW=0
_red() { local label="$1"; shift; MUTATIONS=$((MUTATIONS+1)); if "$@" >/dev/null 2>&1; then echo "  MUT-HOLLOW  $label — still passes"; HOLLOW=$((HOLLOW+1)); else echo "  MUT-RED     $label"; fi; }
_green() { local label="$1"; shift; "$@" >/dev/null 2>&1 && return 0; echo "  MUT-VOID    $label — unmutated already fails"; HOLLOW=$((HOLLOW+1)); return 1; }
# PROOF THE MUTATION APPLIED: the copy differs from the original AND carries the planted text.
_applied() {
    python3 -c 'import sys; a, b = (open(f, "rb").read() for f in sys.argv[1:3]); sys.exit(0 if a != b and sys.argv[3].encode() in b else 1)' "$1" "$2" "$3" \
        || { echo "  MUT-NOOP    the mutation did not apply ($3)"; HOLLOW=$((HOLLOW+1)); return 1; }
}
_sub() { python3 -c "import sys; p=sys.argv[1]; s=open(p).read(); open(p,'w').write(s.replace(sys.argv[2], sys.argv[3], 1))" "$1" "$2" "$3"; }
_json() { python3 -c "import json,sys; p=sys.argv[1]; d=json.load(open(p)); exec(sys.argv[2]); json.dump(d, open(p, 'w'), indent=1)" "$1" "$2"; }
# a fresh copy of the shelf's gh engine and the lib-apks declaration, laid out as the real tree
_stage() {
    rm -rf "$MUT/libs" "$MUT/build.json"; mkdir -p "$MUT/libs"
    cp -r "$GH" "$MUT/libs/gh"; cp -r "$LIBS/cal" "$MUT/libs/cal"; cp -r "$LIBS/feed" "$MUT/libs/feed"; cp -r "$LIBS/news" "$MUT/libs/news"; cp -r "$LIBS/ml-l-image-mlkit" "$MUT/libs/ml-l-image-mlkit"; cp -r "$LIBS/calc" "$MUT/libs/calc"; cp "$BJ" "$MUT/build.json"
}
M_MF="$MUT/libs/gh/src/main/AndroidManifest.xml"
M_SVC="$MUT/libs/gh/src/main/java/com/diegonmarcos/cloudlib/gh/GhBackendService.kt"
M_GRADLE="$MUT/libs/gh/build.gradle"
R_MF="$GH/src/main/AndroidManifest.xml"
R_SVC="$GH/src/main/java/com/diegonmarcos/cloudlib/gh/GhBackendService.kt"

echo "── MUT each property, broken on a copy, goes red ──"
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_MF" 'android:exported="true"' 'android:exported="false"'
    _applied "$R_MF" "$M_MF" 'android:exported="false"' \
        && _red "E1 the engine service is not exported" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_MF" '"
            android:permission="com.diegonmarcos.cloud.permission.CONSTELLATION_DATA">' '">'
    python3 -c 'import sys; sys.exit(0 if "permission.CONSTELLATION_DATA\">" not in open(sys.argv[1]).read() else 1)' "$M_MF" \
        && _applied "$R_MF" "$M_MF" 'android:exported="true">' \
        && _red "E1 the engine service is bindable by any app (no signature guard)" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_MF" '${applicationId}.ENGINE' 'com.diegonmarcos.cloudlib.gh.ENGINE'
    _applied "$R_MF" "$M_MF" '"com.diegonmarcos.cloudlib.gh.ENGINE"' \
        && _red "E2 the action is a typed package literal instead of the applicationId's" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_MF" 'android:value="1"' 'android:value="one"'
    _applied "$R_MF" "$M_MF" 'android:value="one"' \
        && _red "E3 the contract is not an integer" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_SVC" 'arrayOf(STATUS, REPO_LIST, CREDENTIAL, LOGIN_START, LOGIN_POLL)' 'arrayOf(STATUS, REPO_LIST, CREDENTIAL, LOGIN_START)'
    _applied "$R_SVC" "$M_SVC" 'CREDENTIAL, LOGIN_START)' \
        && _red "E4 a method the engine answers is missing from methodNames()" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_SVC" '        LOGIN_POLL -> Login.poll()
' ''
    python3 -c 'import sys; sys.exit(0 if "LOGIN_POLL -> Login.poll()" not in open(sys.argv[1]).read() else 1)' "$M_SVC" \
        && _applied "$R_SVC" "$M_SVC" 'LOGIN_START -> Login.start(runner, arg(args, 0), applicationContext)' \
        && _red "E4 the engine lists a method it no longer answers (an older client would break)" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_SVC" 'class GhBackendService : DataBackendService()' 'class GhBackendService : android.app.Service()'
    _applied "$R_SVC" "$M_SVC" 'GhBackendService : android.app.Service()' \
        && _red "E4 the engine class does not speak IDataBackend" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_GRADLE" "    implementation project(':libs:core')
" ''
    python3 -c 'import sys; sys.exit(0 if "project(%s:libs:core%s)" % (chr(39), chr(39)) not in open(sys.argv[1]).read() else 1)' "$M_GRADLE" \
        && _applied "$GH/build.gradle" "$M_GRADLE" "implementation 'androidx.core:core-ktx" \
        && _red "E5 the engine no longer compiles against libs:core" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _json "$MUT/build.json" 'd["lib_apks"]["exec_native"].pop("gh"); d["lib_apks"]["exec_native"]["_planted"] = "gh-removed"'
    _applied "$BJ" "$MUT/build.json" 'gh-removed' \
        && _red "E6 the gh engine execs a binary its APK no longer extracts" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _json "$MUT/build.json" 'd["lib_apks"]["exec_native"]["gihub"] = "a typo"'
    _applied "$BJ" "$MUT/build.json" 'gihub' \
        && _red "E6 exec_native names a module that does not exist" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_MF" '    <uses-permission android:name="android.permission.INTERNET" />
' ''
    python3 -c 'import sys; sys.exit(0 if "android.permission.INTERNET" not in open(sys.argv[1]).read() else 1)' "$M_MF" \
        && _applied "$R_MF" "$M_MF" '<application>' \
        && _red "E7 the gh engine runs gh without INTERNET" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_SVC" 'GhLogin(host, hold = { GhLoginKeeper.hold(ctx) })' 'GhLogin(host)'
    _applied "$R_SVC" "$M_SVC" 'job = GhLogin(host) {' \
        && _red "E9 the sign-in runs without holding the engine's network" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_MF" '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
' ''
    python3 -c 'import sys; sys.exit(0 if "FOREGROUND_SERVICE_SPECIAL_USE" not in open(sys.argv[1]).read() else 1)' "$M_MF" \
        && _applied "$R_MF" "$M_MF" 'android.permission.FOREGROUND_SERVICE" />' \
        && _red "E9 the keeper's foreground type has no permission" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_MF" 'android:foregroundServiceType="specialUse"' 'android:foregroundServiceType="dataSync"'
    _applied "$R_MF" "$M_MF" '"dataSync"' \
        && _red "E9 the keeper's declared type does not match the type it starts with" engines "$MUT/libs" "$MUT/build.json"; }
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_MF" 'com.diegonmarcos.cloud.engine.CONTRACT' 'com.diegonmarcos.cloud.engine.VERSION'
    _applied "$R_MF" "$M_MF" 'engine.VERSION' \
        && _red "the contract key is renamed, so every check would run on nothing" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$MUT/libs/cal/src/main/AndroidManifest.xml" 'com.diegonmarcos.cloud.engine.CONTRACT' 'com.diegonmarcos.cloud.engine.VERSION'
    _applied "$LIBS/cal/src/main/AndroidManifest.xml" "$MUT/libs/cal/src/main/AndroidManifest.xml" 'engine.VERSION' \
        && _red "E8 the cal engine Cloud Me binds stops declaring its contract" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$MUT/libs/feed/src/main/AndroidManifest.xml" 'com.diegonmarcos.cloud.engine.CONTRACT' 'com.diegonmarcos.cloud.engine.VERSION'
    _applied "$LIBS/feed/src/main/AndroidManifest.xml" "$MUT/libs/feed/src/main/AndroidManifest.xml" 'engine.VERSION' \
        && _red "E8 the feed engine the SuperApp binds stops declaring its contract" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$MUT/libs/news/src/main/AndroidManifest.xml" 'com.diegonmarcos.cloud.engine.CONTRACT' 'com.diegonmarcos.cloud.engine.VERSION'
    _applied "$LIBS/news/src/main/AndroidManifest.xml" "$MUT/libs/news/src/main/AndroidManifest.xml" 'engine.VERSION' \
        && _red "E8 the news engine Cloud News binds stops declaring its contract" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$MUT/libs/calc/src/main/AndroidManifest.xml" 'com.diegonmarcos.cloud.engine.CONTRACT' 'com.diegonmarcos.cloud.engine.VERSION'
    _applied "$LIBS/calc/src/main/AndroidManifest.xml" "$MUT/libs/calc/src/main/AndroidManifest.xml" 'engine.VERSION' \
        && _red "E8 the calc engine Cloud Calc binds stops declaring its contract" engines "$MUT/libs" "$MUT/build.json"; }

M_IMGMF="$MUT/libs/ml-l-image-mlkit/src/main/AndroidManifest.xml"
M_IMGSVC="$MUT/libs/ml-l-image-mlkit/src/main/java/com/diegonmarcos/superapp/image/ImageScanBackendService.kt"
R_IMGSVC="$LIBS/ml-l-image-mlkit/src/main/java/com/diegonmarcos/superapp/image/ImageScanBackendService.kt"
_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_IMGMF" 'com.diegonmarcos.cloud.engine.CONTRACT' 'com.diegonmarcos.cloud.engine.VERSION'
    _applied "$LIBS/ml-l-image-mlkit/src/main/AndroidManifest.xml" "$M_IMGMF" 'engine.VERSION' \
        && _red "E8 the image engine five apps bind stops declaring its contract" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_IMGSVC" 'override fun methods(): Array<String> = methodNames()' 'override fun methods(): Array<String> = arrayOf(BARCODE)'
    _applied "$R_IMGSVC" "$M_IMGSVC" '= arrayOf(BARCODE)' \
        && _red "E4 the typed wire's methods() stops answering with methodNames() (a client would not see ocr)" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_IMGSVC" 'private val binder = object : IImageScanEngine.Stub() {' 'private val binder = object : android.os.Binder() {'
    _applied "$R_IMGSVC" "$M_IMGSVC" 'object : android.os.Binder()' \
        && _red "E4 a Service() engine that serves no typed wire Stub" engines "$MUT/libs" "$MUT/build.json"; }

_stage && _green engines engines "$MUT/libs" "$MUT/build.json" && {
    _sub "$M_IMGSVC" '        OCR -> scanner.ocrJson(image)
' ''
    python3 -c 'import sys; sys.exit(0 if "OCR -> scanner" not in open(sys.argv[1]).read() else 1)' "$M_IMGSVC" \
        && _applied "$R_IMGSVC" "$M_IMGSVC" 'BARCODE -> scanner' \
        && _red "E4 the typed engine lists ocr but its dispatch no longer answers it" engines "$MUT/libs" "$MUT/build.json"; }

echo "── $MUTATIONS mutations, $HOLLOW hollow/void/no-op ──"
[ "$MUTATIONS" -ge 19 ] || { echo "  only $MUTATIONS mutations ran — a mutation block that stops early proves less than it prints"; FAILURES=$((FAILURES + 1)); }
[ "$HOLLOW" -eq 0 ] || FAILURES=$((FAILURES + HOLLOW))

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-engine-services: all checks passed"; else echo "test-engine-services: $FAILURES check(s) FAILED"; exit 1; fi
