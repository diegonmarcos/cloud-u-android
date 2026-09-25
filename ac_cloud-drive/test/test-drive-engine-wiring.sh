#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #567 push 5 / #579 — the chrome reaches every engine: the ids the Compose ║
# ║ screens ask for are exactly the ids the host activity routes, each to   ║
# ║ its own library's screen, and the hand-off comes back into Files         ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHAT IT PINS — the engine-id vocabulary is DERIVED from the chrome (every
# openEngine(EngineActivity.ENGINE_X / openEngine("id" call in a screen), never
# written here:
#
#   W1  every id a screen asks for is routed by EngineActivity's `when`, and every
#       id the activity routes is asked for by a screen (no dead door either way).
#   W2  each routed id calls the matching library screen — GitSyncScreen for git,
#       FileEditorScreen for editor, RcloneScreen for rclone, MountsScreen for
#       mounts — with the host contract (target, onOpenFile, onClose).
#   W3  the seam: DriveActions.openEngine is the ONE way a screen reaches an
#       engine; MainActivity implements it by starting EngineActivity for a result.
#   W4  the hand-off comes back: EngineActivity returns RESULT_PATH and MainActivity
#       reveals it in the active Files pane.
#   W5  the activity is in the manifest (not exported) and the app applies the
#       Compose compiler and links activity-compose.
#   W6  build-time declarations reach the engines: EngineActivity calls the rclone
#       job store's and the mount store's declare() from the baked data, and the
#       chrome calls declareFromBuild at launch.
#
# OWN-SOURCE ONLY. python3 and grep only.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
MAIN="$SRC/MainActivity.kt"
HOST="$SRC/EngineActivity.kt"
ACTIONS="$SRC/DriveActions.kt"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"
GRADLE="$APP/app/build.gradle"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$MAIN" "$HOST" "$ACTIONS" "$MANIFEST" "$GRADLE"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required"; exit 1; }
done

echo "── W1 the engine-id vocabulary, derived from the screens, matches the host's routes ──"
SCREEN_IDS="$(python3 - "$SRC" "$HOST" <<'PYTHON'
import os, re, sys
src, host = sys.argv[1], sys.argv[2]
consts = dict(re.findall(r'const val (ENGINE_\w+) = "([a-z]+)"', open(host, encoding="utf-8").read()))
ids = set()
for d, _, fs in os.walk(src):
    for f in fs:
        if not f.endswith(".kt") or f == "EngineActivity.kt": continue
        t = open(os.path.join(d, f), encoding="utf-8").read()
        ids |= {consts[c] for c in re.findall(r'openEngine\(EngineActivity\.(ENGINE_\w+)', t) if c in consts}
        ids |= set(re.findall(r'openEngine\("([a-z]+)"', t))
print(" ".join(sorted(ids)))
PYTHON
)"
HOST_IDS="$(python3 - "$HOST" <<'PYTHON'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
consts = dict(re.findall(r'const val (ENGINE_\w+) = "([a-z]+)"', text))
route = re.search(r"when \(engine\) \{(.*?)\n\s*\}", text, re.S)
used = re.findall(r"^\s*(ENGINE_\w+) -> (\w+Screen)\(target, onOpenFile, onClose[^)]*\)", route.group(1), re.M) if route else []
print(" ".join(sorted(consts[c] for c, _ in used if c in consts)))
PYTHON
)"
[ -n "$SCREEN_IDS" ] && pass "screens ask for: $SCREEN_IDS" || fail "no screen asks for an engine"
[ -n "$HOST_IDS" ] && pass "host routes: $HOST_IDS" || fail "EngineActivity routes no engine"
if [ "$(printf '%s\n' $SCREEN_IDS | sort -u | tr '\n' ' ')" = "$(printf '%s\n' $HOST_IDS | sort -u | tr '\n' ' ')" ]; then
    pass "screen ids == host routes"
else
    fail "screen ids ($SCREEN_IDS) differ from host routes ($HOST_IDS)"
fi

echo "── W2 each route lands on its own library's screen ──"
python3 - "$HOST" <<'PYTHON' && pass "every routed id calls <Lib>Screen from the matching cloudlib package" || fail "route/screen mismatch (see above)"
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
consts = dict(re.findall(r'const val (ENGINE_\w+) = "([a-z]+)"', text))
route = re.search(r"when \(engine\) \{(.*?)\n\s*\}", text, re.S).group(1)
imports = dict(re.findall(r"^import com\.diegonmarcos\.cloudlib\.(\w+)\.(\w+Screen)$", text, re.M))
expected = {"git": ("gitsync", "GitSyncScreen"), "editor": ("fileeditor", "FileEditorScreen"), "rclone": ("rclone", "RcloneScreen"), "mounts": ("mounts", "MountsScreen")}
bad = 0
for c, screen in re.findall(r"^\s*(ENGINE_\w+) -> (\w+Screen)\(target, onOpenFile, onClose[^)]*\)", route, re.M):
    eid = consts.get(c)
    pkg, want = expected.get(eid, (None, None))
    if want != screen or imports.get(pkg) != screen:
        print("    %s -> %s (expected %s from cloudlib.%s; imported: %r)" % (eid, screen, want, pkg, imports.get(pkg))); bad += 1
sys.exit(1 if bad else 0)
PYTHON

echo "── W3 the seam ──"
if grep -qE 'fun openEngine\(engine: String, target: String, url: String = ""\)' "$ACTIONS"; then pass "DriveActions.openEngine(engine, target, url) is the one door"; else fail "DriveActions lacks openEngine(engine, target, url)"; fi
if grep -qE 'override fun openEngine\(engine: String, target: String, url: String\)' "$MAIN" && grep -qE 'engineLauncher\.launch\(EngineActivity\.intent\(this, engine, target, url\)\)' "$MAIN"; then pass "MainActivity starts EngineActivity for a result with (engine, target, url)"; else fail "MainActivity does not start EngineActivity through the launcher"; fi
OTHER="$(grep -rlE 'EngineActivity::class\.java|EngineActivity\.intent\(' "$SRC" | grep -vE 'MainActivity\.kt|EngineActivity\.kt' || true)"
if [ -z "$OTHER" ]; then pass "no screen starts EngineActivity itself"; else fail "a screen bypasses DriveActions: $OTHER"; fi

echo "── W4 the hand-off comes back into Files ──"
if grep -qE "putExtra\(RESULT_PATH, path\)" "$HOST"; then pass "EngineActivity returns RESULT_PATH"; else fail "EngineActivity does not return the opened path"; fi
if grep -qE 'getStringExtra\(EngineActivity\.RESULT_PATH\)' "$MAIN" && grep -qE 'filesController\?\.reveal\(path\)' "$MAIN" && grep -qE 'fun reveal\(path: String\)' "$SRC/files/FilesController.kt"; then pass "MainActivity reveals the returned path in the active Files pane"; else fail "the returned path is not revealed"; fi

echo "── W5 manifest and toolchain ──"
python3 - "$MANIFEST" <<'PYTHON' && pass "EngineActivity declared, not exported" || fail "EngineActivity not declared (or exported) in the manifest"
import sys, xml.etree.ElementTree as ET
ns = "{http://schemas.android.com/apk/res/android}"
root = ET.parse(sys.argv[1]).getroot()
acts = [a for a in root.iter("activity") if a.get(ns + "name") == ".EngineActivity"]
sys.exit(0 if acts and acts[0].get(ns + "exported") == "false" else 1)
PYTHON
if grep -qE "id 'org\.jetbrains\.kotlin\.plugin\.compose'" "$GRADLE" && grep -qE "compose = true" "$GRADLE"; then pass "app applies the Compose compiler and enables compose"; else fail "app/build.gradle does not apply the Compose compiler / enable compose"; fi
if grep -qE "androidx\.activity:activity-compose" "$GRADLE"; then pass "activity-compose linked"; else fail "activity-compose not linked"; fi

echo "── W6 build-time declarations reach the engines ──"
if grep -qE "RcloneJobStore\(.*\)\.declare\(" "$HOST" && grep -qE "BuildConfig\.RCLONE_JOBS_B64" "$HOST"; then pass "declared rclone jobs from RCLONE_JOBS_B64"; else fail "EngineActivity does not declare rclone jobs from the baked data"; fi
if grep -qE "MountStore\(.*\)\.declare\(" "$HOST" && grep -qE "BuildConfig\.CONNECTIONS_B64" "$HOST"; then pass "declared mounts from CONNECTIONS_B64"; else fail "EngineActivity does not declare mounts from the baked data"; fi
if grep -qE '^\s*EngineActivity\.declareFromBuild\(this\)' "$MAIN"; then pass "the chrome declares at launch, so the Sync cards see the declared remotes and mounts before any engine opens"; else fail "MainActivity does not call declareFromBuild"; fi

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-engine-wiring: all checks passed"; else echo "test-drive-engine-wiring: $FAILURES check(s) FAILED"; exit 1; fi
