#!/usr/bin/env bash
# test-account-configs-public: the Configs section of Cloud Account is PUBLIC (#1081), statically.
#
# Holds that:
#  1. build.json::ui.sections[configs] declares exactly the allowed leaves (runbook, apps, push, perms, adb-shell,
#     desktop, c3): the pages that hold or show secrets (providers, keys, grants, import, bundle, signin, settings,
#     connections) are not in it;
#  2. MainActivity's "configs" branch mounts only the allowed composables, and names no secret-bearing one;
#  3. the two pages added for it (Desktop, C3) draw a vault entry only through PublicConfig.rows, never touch
#     AccountVault / a reveal / a password field, and PublicConfig drops secret-looking keys AND values.
# Then each mutation is planted in a scratch copy and the check must go RED. The runtime half is SectionsTest.kt.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="ac_cloud-account"
MA="$A/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt"
SP="ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/SectionPages.kt"
SK="ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile/SectionKit.kt"

check() {
python3 - "$1" "$A" "$MA" "$SP" "$SK" <<'PY'
import json, os, re, sys
R, A, MA, SP, SK = sys.argv[1:6]
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
def leaves(pages):
    for p in pages:
        if p.get("pages"): yield from leaves(p["pages"])
        else: yield p["id"]
cfg = next(s for s in ui["sections"] if s["id"] == "configs")
allowed = {"runbook", "apps", "push", "perms", "adb-shell", "desktop", "c3"}
have = set(leaves(cfg["pages"]))
ok(have == allowed, "Configs declares exactly the allowed pages", f"Configs leaves are {sorted(have)}, allowed {sorted(allowed)}")

ma = code(rd(MA))
block = ma[ma.index('"configs" -> when (page) {'):ma.index('"data" -> when (page) {')]
calls = set(re.findall(r"\b([A-Z]\w*)\(", block))
okcalls = {"AppsPage", "FleetSetupPage", "AccountPermsPage", "RunbookPage", "DesktopConfigsPage", "C3ConfigsPage", "Column", "AdbShellPage"}
ok(calls <= okcalls, "the configs branch mounts only allowed composables", f"the configs branch mounts {sorted(calls - okcalls)}")
bad = [w for w in ("Secret", "Reveal", "AccountVault", "Password", "Providers", "Grants", "Import", "ConnectionsTab", "SettingsPage") if w in block]
ok(not bad, "the configs branch names no secret-bearing composable", f"the configs branch names {bad}")

sp = code(rd(SP))
for fn, nxt in (("fun DesktopConfigsPage(", "fun C3ConfigsPage("), ("fun C3ConfigsPage(", "fun BackupsPage(")):
    body = sp[sp.index(fn):sp.index(nxt)]
    name = fn[4:-1]
    ok("PublicConfig." in body, f"{name} reads the vault only through PublicConfig", f"{name} does not go through PublicConfig")
    leak = [w for w in ("AccountVault", "connection(", "PasswordVisualTransformation", "RevealGate", "SecretClipboard", "optString(", ".shown()", "HubAndroid") if w in body]
    ok(not leak, f"{name} touches no secret store", f"{name} touches {leak}")

sk = code(rd(SK))
ok("SECRET_KEY.containsMatchIn(key)" in sk and "SECRET_VALUE.containsMatchIn(value)" in sk and "if (isSecretKey(k)) return@mapNotNull null" in sk and "looksSecret(s)" in sk,
   "PublicConfig drops secret-looking keys and values", "PublicConfig no longer filters keys and values")
ok(all(w in sk for w in ("pass", "token", "key", "private", "secret", "ghp_", "-----BEGIN")), "PublicConfig's patterns cover passwords, tokens, keys, PEM and token prefixes", "PublicConfig's patterns lost a class")
sys.exit(fails)
PY
}

echo "-- real tree --"
check "$ROOT"; REAL=$?

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mutate() {  # mutate <label> <file> <old> <new>
  rm -rf "$TMP/r"; mkdir -p "$TMP/r/$A" "$TMP/r/$(dirname "$SP")"; cp "$ROOT/$A/build.json" "$TMP/r/$A/"
  mkdir -p "$TMP/r/$(dirname "$MA")"; cp "$ROOT/$MA" "$TMP/r/$MA"; cp "$ROOT/$SP" "$ROOT/$SK" "$TMP/r/$(dirname "$SP")/"
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
mutate "the secret list mounted in Configs" "$MA" '"desktop" -> DesktopConfigsPage()' '"desktop" -> DesktopConfigsPage()
                "keys" -> SecretsTab(openVault = {})' || M=1
mutate "Settings mounted in Configs" "$MA" '"c3" -> C3ConfigsPage()' '"c3" -> C3ConfigsPage()
                "settings" -> AccountSettingsPage("")' || M=1
mutate "a secrets page declared under Configs" "$A/build.json" '"id": "c3",' '"id": "providers",' || M=1
mutate "Desktop bypasses PublicConfig" "$SP" 'PublicConfig.desktops(model.server()?.body)' 'model.server()?.body' || M=1
mutate "C3 reads the vault directly" "$SP" 'PublicConfig.containers(model.server()?.body)' 'AccountVault(ctx).connections()' || M=1
mutate "PublicConfig stops filtering keys" "$SK" 'if (isSecretKey(k)) return@mapNotNull null' '' || M=1
mutate "PublicConfig stops filtering values" "$SK" 'if (s.isEmpty() || looksSecret(s)) null else k to s' 'if (s.isEmpty()) null else k to s' || M=1
mutate "the key pattern loses passwords" "$SK" '(pass|secret|token|key|' '(secret|token|key|' || M=1

echo "== RESULT: real tree $REAL failure(s), $([ "$M" -eq 0 ] && echo "every mutation caught" || echo "a mutation survived") =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
