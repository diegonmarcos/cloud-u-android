#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ test-tools-app — Cloud Tools Small LLMs, statically              ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The JVM suite (core/ ToolsCoreTest, run by `gradle test`) proves the logic against recorded
# OpenRouter answers. This proves what a JVM test cannot see, from the sources alone:
#   A1  every icon build.json names (ui.sections, tools.tasks) is a branch of ui/IconCatalog.kt
#   A2  the token is stored ONLY through EncryptedSharedPreferences under a Keystore AES-256-GCM
#       master key, in the store fleet-config.json declares as `encrypted` / `secret`
#   A3  the token is never logged: no android.util.Log, println or printStackTrace anywhere in the
#       app or core sources, and the vault's toString is its mask
#   A4  decisions-use guard compliance: nothing here calls the fleet Account's key reveal
#       (revealAiKey) or the engine's DecisionsLink; the Account is asked only aiRoutingSnapshot
#   A5  the JVM suite uses recorded fixtures, never the network: every fixture it names exists,
#       and no test source opens a URL of its own
#   A6  no key-shaped literal in the module (the leak scan's sk-or- shape)
#   A7  every section id a Kotlin source names is one build.json::ui.sections declares
# then plants mutations in a scratch copy and requires RED for each.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {   # check <module dir> -> prints FAIL lines, exit 1 on any
python3 - "$1" "$ROOT" <<'PY'
import json, os, re, sys
app, root = sys.argv[1], sys.argv[2]
bad = []
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def code(p):
    t = re.sub(r"/\*.*?\*/", "", read(p), flags=re.S)
    return "\n".join(re.sub(r"(?<![:\"])//.*$", "", l) for l in t.splitlines())
def sources(*dirs):
    for d in dirs:
        for dp, dn, fn in os.walk(os.path.join(app, d)):
            dn[:] = [x for x in dn if x != "build"]
            for f in fn:
                if f.endswith((".kt", ".java")): yield os.path.join(dp, f)
bj = json.loads(read(os.path.join(app, "build.json")))
kt = os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudtoolssmallllms")
main = list(sources("app/src/main", "core/src/main"))

# A1
cat = code(os.path.join(kt, "ui/IconCatalog.kt"))
branches = set(re.findall(r'"([a-z_]+)"\s*->', cat))
icons = {s["icon"] for s in bj["ui"]["sections"]} | {t["icon"] for t in bj["tools"]["tasks"]}
for i in sorted(icons - branches): bad.append("A1 icon %r is not a branch of IconCatalog: it would draw the fallback" % i)

# A2
sp = code(os.path.join(kt, "data/SecretPrefs.kt"))
for need in ("EncryptedSharedPreferences.create(", "MasterKey.KeyScheme.AES256_GCM", "PrefValueEncryptionScheme.AES256_GCM"):
    if need not in sp: bad.append("A2 SecretPrefs does not use %s" % need)
store = re.search(r'PREFS\s*=\s*"([^"]+)"', sp)
fc = json.loads(read(os.path.join(root, "ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json")))
decl = fc["stores"].get(store.group(1)) if store else None
if not decl or decl.get("kind") != "encrypted" or decl.get("class") != "secret":
    bad.append("A2 the token store %r is not declared encrypted/secret in fleet-config.json" % (store.group(1) if store else None))
for f in main:
    c = code(f)
    if "getSharedPreferences(" in c and "openrouter_token" in c:
        bad.append("A2 %s writes the token to plain SharedPreferences" % os.path.relpath(f, app))
    if "SecretPrefs(" in c and not f.endswith(("SecretPrefs.kt", "ToolsModel.kt")):
        bad.append("A2 %s holds the secret store; only the model's vault may" % os.path.relpath(f, app))

# A3
for f in main:
    c = code(f)
    for w in ("android.util.Log", "Log.d(", "Log.i(", "Log.w(", "Log.e(", "Log.v(", "println(", "printStackTrace("):
        if w in c: bad.append("A3 %s calls %s: this app logs nothing" % (os.path.relpath(f, app), w))
vault = code(os.path.join(app, "core/src/main/kotlin/com/diegonmarcos/cloudtoolssmallllms/core/TokenVault.kt"))
if not re.search(r'override fun toString\(\): String = "TokenVault\(\$\{masked\(\)\}\)"', vault):
    bad.append("A3 TokenVault.toString is not its mask")

# A4
for f in main:
    c = code(f)
    if "revealAiKey(" in c: bad.append("A4 %s reads the fleet Account's key (decisions-use D5)" % os.path.relpath(f, app))
    if "DecisionsLink" in c: bad.append("A4 %s calls the decisions engine; this app pays with its own token" % os.path.relpath(f, app))
acc = code(os.path.join(kt, "data/AccountStatus.kt"))
if "aiRoutingSnapshot()" not in acc: bad.append("A4 AccountStatus no longer asks the key-free aiRoutingSnapshot")

# A5
tests = list(sources("core/src/test"))
fx = os.path.join(app, "core/src/test/resources/fixtures")
for f in tests:
    t = read(f)
    for name in re.findall(r'fixture\("([^"]+)"\)', t):
        if not os.path.isfile(os.path.join(fx, name)): bad.append("A5 fixture %s is missing" % name)
    if re.search(r"UrlHttp|openConnection|HttpURLConnection|java\.net\.URL\(", t):
        bad.append("A5 %s reaches the network" % os.path.relpath(f, app))
if not tests: bad.append("A5 no JVM test sources")

# A6
key = re.compile(r"sk-or-[A-Za-z0-9-]{8,}")
for dp, dn, fn in os.walk(app):
    dn[:] = [x for x in dn if x not in ("build", ".gradle")]
    for f in fn:
        p = os.path.join(dp, f)
        try: t = read(p)
        except (UnicodeDecodeError, OSError): continue
        if key.search(t): bad.append("A6 %s holds a key-shaped literal" % os.path.relpath(p, app))

# A7
sections = {s["id"] for s in bj["ui"]["sections"]}
for f in sources("app/src/main"):
    for lit in re.findall(r'section\s*=\s*"([a-z_-]+)"', code(f)) + re.findall(r'^\s*"([a-z_-]+)"\s*->\s*\w+Page\(', code(f), re.M):
        if lit not in sections: bad.append("A7 %s names section %r, which build.json does not declare" % (os.path.relpath(f, app), lit))

for b in bad: print("  FAIL: " + b)
sys.exit(1 if bad else 0)
PY
}

PASS=0; FAILS=0
echo "[ac_cloud-tools-small-llms] static checks"
if check "$APP"; then PASS=$((PASS + 1)); echo "  PASS: A1-A7 hold on the tree"; else FAILS=$((FAILS + 1)); fi

# Mutations: each must turn the check RED, or the check cannot fail.
S="$(mktemp -d)"; trap 'rm -rf "$S"' EXIT
mutate() {   # mutate <name> <python statement on M (scratch module dir)>
  rm -rf "$S/m"; mkdir -p "$S/m"
  (cd "$APP" && tar --exclude=build --exclude=.gradle -cf - build.json app core) | (cd "$S/m" && tar -xf -)
  python3 - "$S/m" <<PY
import re, sys
M = sys.argv[1]
K = M + "/app/src/main/java/com/diegonmarcos/cloudtoolssmallllms"
def sub(p, a, b):
    s = open(p).read(); assert a in s, (p, a); open(p, "w").write(s.replace(a, b, 1))
$2
PY
  if check "$S/m" >/dev/null 2>&1; then FAILS=$((FAILS + 1)); echo "  FAIL: mutation '$1' stayed green"; else PASS=$((PASS + 1)); echo "  PASS: mutation '$1' is caught"; fi
}
mutate "unknown task icon" 'sub(M + "/build.json", "\"icon\": \"translate\"", "\"icon\": \"tranzlate\"")'
mutate "plain prefs token" 'sub(K + "/data/SecretPrefs.kt", "EncryptedSharedPreferences.create(", "PlainPrefs.create(")'
mutate "a log line" 'sub(K + "/data/Catalogue.kt", "    companion object {", "    fun dbg(t: String) = android.util.Log.d(\"tools\", t)\n\n    companion object {")'
mutate "Account key read" 'sub(K + "/data/AccountStatus.kt", "client.aiRoutingSnapshot() }", "client.revealAiKey(PROVIDER).text }")'
mutate "vault prints the token" 'D = chr(36); sub(M + "/core/src/main/kotlin/com/diegonmarcos/cloudtoolssmallllms/core/TokenVault.kt", "TokenVault(" + D + "{masked()})", "TokenVault(" + D + "{token()})")'
mutate "test goes online" 'sub(M + "/core/src/test/kotlin/com/diegonmarcos/cloudtoolssmallllms/core/ToolsCoreTest.kt", "import java.io.IOException", "import java.io.IOException\nimport com.diegonmarcos.superapp.decisions.UrlHttp")'
mutate "missing fixture" 'sub(M + "/core/src/test/kotlin/com/diegonmarcos/cloudtoolssmallllms/core/ToolsCoreTest.kt", "fixture(\"jev-route.json\")", "fixture(\"jev-gone.json\")")'
mutate "undeclared section" 'sub(K + "/ui/TasksPage.kt", "model.section = \"chat\"", "model.section = \"chatz\"")'

echo "-- test-tools-app: $PASS passed, $FAILS failed"
[ "$FAILS" -eq 0 ]
