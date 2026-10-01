#!/usr/bin/env bash
# The SuperApp firewall must never silently take the network away from a fleet engine.
#
# ── THE FAILURE THIS EXISTS FOR (#726) ────────────────────────────────────
# Sync ▸ Git ▸ GitHub ▸ Sign in failed on the phone while the very same gh binary, run from
# another app, reached github.com. The engine split moved gh into its own APK and uid
# (Cloud-Lib-Gh.apk). libs:firewall's drain engine lets traffic through by EXCLUDING every
# installed package from its tun, and Android turns that list into uids when the tun is
# established — so a package installed AFTER that falls into the tun with no rule at all:
# no DNS, no connections, no log line. Every newly split engine was exactly that package.
#
# ── WHAT HOLDS ────────────────────────────────────────────────────────────
#  1. The exemption is DERIVED: libs/firewall/build.gradle bakes FLEET_ENGINES from
#     constellation-fleet.json, filtered by kind — not a typed list (no fleet package
#     literal anywhere in libs/firewall).
#  2. Every fleet row that is not an app (a lib / engine APK: it works for the app that binds
#     it) and declares INTERNET is inside that derived set. The kind the gradle filters on is
#     READ FROM the gradle, so a renamed kind, a new kind ('engine') or a gradle filter that
#     drifts goes red. Apps are deliberately NOT exempt: an app, the keyboard included, stays
#     under the rules its user writes.
#  3. The drain engine applies it (the block set excludes FLEET_ENGINES), rebuilds the tun
#     on a fresh install, and names what it captures (log + notification).
#
# Usage: ./test-firewall-never-captures-fleet-engines.sh   (static; no network)
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
UNIX="$(cd "$HERE/../.." && pwd)"   # → repo root

python3 - "$UNIX" <<'PY'
import json, os, re, sys
root = sys.argv[1]
fails = []
def need(path):
    p = os.path.join(root, path)
    if not os.path.isfile(p):
        print(f"FAIL missing {path}"); sys.exit(1)
    return open(p, encoding='utf-8').read()

fw = 'ab_cloud-libs-shared/libs/firewall'
gradle = need(f'{fw}/build.gradle')
svc = need(f'{fw}/src/main/java/com/diegonmarcos/superapp/firewall/FirewallVpnService.kt')
fleet = json.loads(need('aa_cloud-superapp/data/constellation-fleet.json'))

# 1. derived from the fleet, by kind
m = re.search(r"constellation-fleet\.json.*?\.apps\.findAll\s*\{\s*it\.kind\s*==\s*'([^']+)'\s*\}\*\.package", gradle, re.S)
if not m:
    fails.append("libs/firewall/build.gradle no longer derives the engine list from constellation-fleet.json's rows by kind")
kind = m.group(1) if m else None
if not re.search(r"buildConfigField\s+'String',\s*'FLEET_ENGINES',\s*\"\\\"\$\{fleetEngines\}\\\"\"", gradle):
    fails.append("FLEET_ENGINES is not baked from the derived fleetEngines")
literals = []
for d, _, files in os.walk(os.path.join(root, fw, 'src/main')):
    for f in files:
        if re.search(r'com\.diegonmarcos\.cloudlib\.\w', open(os.path.join(d, f), encoding='utf-8', errors='ignore').read()):
            literals.append(os.path.relpath(os.path.join(d, f), root))
if re.search(r'com\.diegonmarcos\.cloudlib\.\w', gradle):
    literals.append(f'{fw}/build.gradle')
if literals:
    fails.append(f"a hand-typed fleet package in libs/firewall: {literals}")

# 2. every engine that declares INTERNET is in the derived set
derived = {a['package'] for a in fleet['apps'] if a.get('kind') == kind}
engines = []
for a in fleet['apps']:
    url = a.get('repo_url') or ''
    if '/tree/main/' not in url:
        continue
    rel = url.split('/tree/main/', 1)[1]
    text = ''.join(open(os.path.join(root, rel, s), encoding='utf-8').read()
                   for s in ('src/main/AndroidManifest.xml', 'app/src/main/AndroidManifest.xml')
                   if os.path.isfile(os.path.join(root, rel, s)))
    if a.get('kind') != 'app' and 'android.permission.INTERNET' in text:
        engines.append(a['package'])
if 'com.diegonmarcos.cloudlib.gh' not in engines:
    fails.append("the gh engine is not among the INTERNET-declaring engines read from the manifests — the scan is blind")
missing = sorted(set(engines) - derived)
if missing:
    fails.append(f"engines that declare INTERNET but the firewall's derived set (kind == {kind!r}) does not exempt: {missing}")
else:
    print(f"PASS {len(engines)} INTERNET-declaring engines are all exempt: {', '.join(sorted(engines))}")

# 3. the drain engine applies it, re-reads installs, and says what it captures
checks = {
    "the block set excludes FLEET_ENGINES": r"filterKeys\s*\{[^}]*!in\s+FLEET_ENGINES",
    "FLEET_ENGINES comes from the baked BuildConfig": r"val\s+FLEET_ENGINES[^=]*=\s*BuildConfig\.FLEET_ENGINES",
    "a fresh install rebuilds the tun": r"ACTION_PACKAGE_ADDED\)\.apply\s*\{\s*addDataScheme\(\"package\"\)",
    "the install receiver rebuilds": r"EXTRA_REPLACING[^\n]*\n[^\n]*\n\s*onConditionsChanged\(\)",
    "the capture set is logged": r"Log\.i\(TAG,\s*\"tun up: capturing \$\{blocked",
    "the notification names blocked apps": r"\"Blocked: \$\{blocked\.sorted\(\)",
}
for what, rx in checks.items():
    if not re.search(rx, svc):
        fails.append(f"FirewallVpnService: {what} — no longer true")

for f in fails:
    print(f"FAIL {f}")
if fails:
    sys.exit(1)
print("PASS the firewall exempts every fleet engine by declaration and never captures silently")
PY
