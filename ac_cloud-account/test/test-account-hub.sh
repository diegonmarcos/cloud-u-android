#!/usr/bin/env bash
# test-account-hub: the reorganised Cloud Account (Profiles, Secrets, Home, Configs), statically.
#
# Holds that:
#  1. build.json::ui.bottom_nav is profiles, secrets, home, configs (no Setup island), default home;
#  2. every old (section, page) id has its place in HubNav.LEGACY, and MainActivity's go() resolves through it;
#  3. the hub pages draw a secret value only through RevealGate (a reveal / copy asks device auth first), the
#     copy goes through SecretClipboard (auto-clear), MainActivity provides the device auth;
#  4. no hub file logs, and none reads the AI key (the decisions-use guard's key_readers ratchet);
#  5. Home holds the six blocks in order: summary, Connections, Import configs, Import tokens, Tokens, Setup all;
#     each provider card and each app profile has its own Setup.
# Then each mutation is planted in a scratch copy and the check must go RED. The behaviour itself is
# HubTest.kt's (Robolectric).
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="ac_cloud-account"
K="$A/app/src/main/java/com/diegonmarcos/cloudaccount"

check() {
python3 - "$1" "$A" "$K" <<'PY'
import json, os, re, sys
R, A, K = sys.argv[1:4]
fails = 0
def rd(p): return open(os.path.join(R, p), encoding="utf-8").read()
def code(s):
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    return re.sub(r"(?m)^\s*//[^\n]*|\s//\s[^\n]*", "", s)
def ok(c, good, bad):
    global fails
    print(("  ok   " if c else "  FAIL ") + (good if c else bad))
    if not c: fails += 1

ui = json.loads(rd(f"{A}/build.json"))["ui"]
ok(ui["bottom_nav"] == ["profiles", "secrets", "home", "configs"] and ui["default_section"] == "home",
   "island: Profiles, Secrets, Home, Configs; default Home", f"island is {ui['bottom_nav']}")
ok(all(s["id"] != "setup" for s in ui["sections"]), "no Setup island", "a Setup island is declared")

nav = code(rd(f"{K}/hub/HubNav.kt"))
old = {"account": ["profile", "connect", "autofill", "sites", "snippets", "import"], "profiles": ["devices", "working", "diff"],
       "setup": ["runbook", "apps", "configs", "perms", "adb-shell"], "secrets": ["connections", "secrets", "grants"], "settings": [""]}
pages = {s["id"]: [p["id"] for p in s["pages"]] for s in ui["sections"]}
legacy = dict(((a, b), (c, d)) for a, b, c, d in re.findall(r'\("([\w-]+)" to "([\w-]*)"\) to \("([\w-]+)" to "([\w-]*)"\)', nav))
missing = [f"{s}/{p}" for s, ps in old.items() for p in ps if (s, p) not in legacy]
ok(not missing, "every old page has a place in HubNav.LEGACY", f"no place for {missing}")
dead = [f"{a}/{b}->{c}/{d}" for (a, b), (c, d) in legacy.items() if c not in pages or (d and d not in pages[c])]
ok(not dead, "every LEGACY target is a declared page", f"LEGACY points at undeclared pages {dead}")
ma = code(rd(f"{K}/MainActivity.kt"))
ok("HubNav.resolve(s, p)" in ma, "MainActivity's go() resolves old ids", "go() does not go through HubNav.resolve")
ok("LocalDeviceAuth provides deviceAuth" in ma and "SystemDeviceAuth(this)" in ma, "MainActivity provides the device auth", "no device auth provided")

pg = code(rd(f"{K}/hub/HubPages.kt"))
ok(pg.count("gate.reveal(") >= 3, "reveal and copy go through RevealGate", "a reveal or copy bypasses RevealGate")
ok(not re.search(r"Text\(\s*HubAndroid\.value", pg) and not re.search(r"SecretClipboard\.copy\(ctx,\s*HubAndroid", pg),
   "no page draws or copies a value without the gate", "a value is drawn or copied without the gate")
ok("SecretClipboard.copy(ctx, it)" in pg, "copy goes through SecretClipboard (auto-clear)", "copy bypasses SecretClipboard")
rv = code(rd(f"{K}/hub/Reveal.kt"))
ok(re.search(r"auth\.authenticate\(reason\) \{ ok ->\s*if \(!ok\) \{ denied\(\); return@authenticate \}", rv) is not None and rv.index("if (!ok)") < rv.index("val v = read()"),
   "RevealGate reads only after a yes", "RevealGate can read before device auth")
ok("!km.isDeviceSecure) { finish(false)" in rv, "no screen lock = refused (fail closed)", "a phone without a lock can reveal")

hub = [f for f in os.listdir(os.path.join(R, K, "hub")) if f.endswith(".kt")]
logs = [f for f in hub if re.search(r"Log\.[a-z]\(|println\(|printStackTrace", code(rd(f"{K}/hub/{f}")))]
ok(not logs, "no hub file logs", f"hub files log: {logs}")
ai = [f for f in hub if "revealAiKey(" in code(rd(f"{K}/hub/{f}"))]
ok(not ai, "no hub file reads the AI key", f"hub files read the AI key: {ai}")

home = pg[pg.index("fun HomePage("):pg.index("private fun ConnectionsTable(")]
order = ["KitHero(", "ConnectionsTable(", "AutofillImportBody(", "TokenImporter(", "SecretLine(", "SetupAllCard {"]
pos = [home.find(x) for x in order]
ok(all(p >= 0 for p in pos) and pos == sorted(pos), "Home: summary, Connections, Import configs, Import tokens, Tokens, Setup all", f"Home blocks missing or out of order {pos}")
ok("HubAndroid.providerTarget(ctx, p).run()" in pg and "HubAndroid.appTarget(ctx, app, plan).run()" in pg,
   "each provider and each app profile has its own Setup", "a provider or app profile lost its Setup")
sys.exit(fails)
PY
}

echo "-- real tree --"
check "$ROOT"; REAL=$?

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mutate() {  # mutate <label> <file> <old> <new>
  rm -rf "$TMP/r"; mkdir -p "$TMP/r/$A"; cp -r "$ROOT/$A/build.json" "$ROOT/$A/app" "$TMP/r/$A/" 2>/dev/null
  rm -rf "$TMP/r/$A/app/build"
  python3 - "$TMP/r/$2" "$3" "$4" <<'PY' || { echo "  MUTATION NOT APPLIED: $1"; return 1; }
import sys
p, o, n = sys.argv[1:4]; s = open(p, encoding="utf-8").read()
if o not in s: sys.exit(1)
open(p, "w", encoding="utf-8").write(s.replace(o, n, 1))
PY
  if check "$TMP/r" >/dev/null; then echo "  MUTATION SURVIVED: $1"; return 1; fi
  echo "  mutation caught: $1"
}
M=0
mutate "Setup island back" "$A/build.json" '"bottom_nav": [
      "profiles",' '"bottom_nav": [
      "setup",
      "profiles",' || M=1
mutate "an old page loses its place" "$K/hub/HubNav.kt" '("setup" to "perms") to ("configs" to "perms"),' '' || M=1
mutate "go() skips the resolver" "$K/MainActivity.kt" 'HubNav.resolve(s, p)' 'Pair(s, p)' || M=1
mutate "gate reads before auth" "$K/hub/Reveal.kt" 'if (!ok) { denied(); return@authenticate }' 'read(); if (!ok) { denied(); return@authenticate }' || M=1
mutate "no lock still reveals" "$K/hub/Reveal.kt" '!km.isDeviceSecure) { finish(false)' 'false) { finish(false)' || M=1
mutate "copy without the gate" "$K/hub/HubPages.kt" 'SecretClipboard.copy(ctx, it)' 'SecretClipboard.copy(ctx, HubAndroid.value(ctx, e.path).orEmpty())' || M=1
mutate "a hub file logs" "$K/hub/TokenImport.kt" 'val t = text.trim()' 'val t = text.trim(); android.util.Log.d("x", t)' || M=1
mutate "Setup all dropped from Home" "$K/hub/HubPages.kt" '        SetupAllCard { tick++ }' '' || M=1

echo "== RESULT: real tree $REAL failure(s), $([ "$M" -eq 0 ] && echo "every mutation caught" || echo "a mutation survived") =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
