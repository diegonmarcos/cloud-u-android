#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #567 push 5 — the chrome reaches every engine: the page's engine ids are ║
# ║ exactly the ids the host activity routes, each to its own library's     ║
# ║ screen, and the hand-off comes back into the page                        ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHAT IT PINS — the engine-id vocabulary is DERIVED from the page (every
# openEngine('<id>' / engineButton('<id>' call), never written here:
#
#   W1  every id the page uses is routed by EngineActivity's `when`, and every
#       id the activity routes is used by the page (no dead door either way).
#   W2  each routed id calls the matching library screen — GitSyncScreen for
#       git, FileEditorScreen for editor, RcloneScreen for rclone, MountsScreen
#       for mounts — with the host contract (target, onOpenFile, onClose).
#   W3  the seam exists at both ends: FilesBridge declares @JavascriptInterface
#       openEngine and refuses an unknown id; MainActivity constructs the bridge
#       with the engine launcher and starts EngineActivity for a result.
#   W4  the hand-off comes back: EngineActivity returns RESULT_PATH, MainActivity
#       evaluates window.revealPath, and the page defines it.
#   W5  the activity is in the manifest (not exported) and the app applies the
#       Compose compiler and links activity-compose — the host cannot setContent
#       without them.
#   W6  build-time declarations reach the engines: EngineActivity calls the
#       rclone job store's and the mount store's declare() from the baked data.
#
# OWN-SOURCE ONLY. python3 and grep only.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
PAGE="$APP/app/src/main/assets/drive.html"
BRIDGE="$APP/app/src/main/java/com/diegonmarcos/clouddrive/FilesBridge.kt"
MAIN="$APP/app/src/main/java/com/diegonmarcos/clouddrive/MainActivity.kt"
HOST="$APP/app/src/main/java/com/diegonmarcos/clouddrive/EngineActivity.kt"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"
GRADLE="$APP/app/build.gradle"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$PAGE" "$BRIDGE" "$MAIN" "$HOST" "$MANIFEST" "$GRADLE"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required"; exit 1; }
done

echo "── W1 the engine-id vocabulary, derived from the page, matches the host's routes ──"
PAGE_IDS="$(grep -oE "(openEngine|engineButton)\('[a-z]+'" "$PAGE" | sed -E "s/.*\('//; s/'//" | sort -u | tr '\n' ' ')"
HOST_IDS="$(python3 - "$HOST" <<'PYTHON'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
consts = dict(re.findall(r'const val (ENGINE_\w+) = "([a-z]+)"', text))
route = re.search(r"when \(engine\) \{(.*?)\n\s*\}", text, re.S)
used = re.findall(r"^\s*(ENGINE_\w+) -> (\w+Screen)\(target, onOpenFile, onClose[^)]*\)", route.group(1), re.M) if route else []
print(" ".join(sorted(consts[c] for c, _ in used if c in consts)))
PYTHON
)"
[ -n "$PAGE_IDS" ] && pass "page uses: $PAGE_IDS" || fail "the page calls no engine"
[ -n "$HOST_IDS" ] && pass "host routes: $HOST_IDS" || fail "EngineActivity routes no engine"
if [ "$(printf '%s\n' $PAGE_IDS | sort -u | tr '\n' ' ')" = "$(printf '%s\n' $HOST_IDS | sort -u | tr '\n' ' ')" ]; then
    pass "page ids == host routes"
else
    fail "page ids ($PAGE_IDS) differ from host routes ($HOST_IDS)"
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

echo "── W3 the seam at both ends ──"
if grep -qE "@JavascriptInterface" "$BRIDGE" && grep -qE "fun openEngine\(engine: String, target: String\): String" "$BRIDGE"; then pass "bridge declares openEngine"; else fail "bridge lacks @JavascriptInterface openEngine(engine, target)"; fi
if grep -qE "if \(engine !in known\) return failure\(" "$BRIDGE"; then pass "bridge refuses an unknown engine id"; else fail "bridge does not refuse an unknown engine id"; fi
if grep -qE "launchEngine\(engine, target, url\)" "$BRIDGE"; then pass "bridge hands off to the Activity-owned launcher (engine, target, url — #575)"; else fail "openEngine does not call launchEngine(engine, target, url)"; fi
if grep -qE "launchEngine = \{ engine, target, url -> engineLauncher\.launch\(EngineActivity\.intent\(this, engine, target, url\)\) \}" "$MAIN"; then pass "MainActivity starts EngineActivity for a result"; else fail "MainActivity does not construct the bridge with the engine launcher"; fi

echo "── W4 the hand-off comes back into the page ──"
if grep -qE "putExtra\(RESULT_PATH, path\)" "$HOST"; then pass "EngineActivity returns RESULT_PATH"; else fail "EngineActivity does not return the opened path"; fi
if grep -qE "window\.revealPath && window\.revealPath\(" "$MAIN"; then pass "MainActivity evaluates window.revealPath"; else fail "MainActivity does not call the page's revealPath"; fi
if grep -qE "^window\.revealPath = function" "$PAGE"; then pass "page defines window.revealPath"; else fail "page does not define window.revealPath"; fi

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

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-engine-wiring: all checks passed"; else echo "test-drive-engine-wiring: $FAILURES check(s) FAILED"; exit 1; fi
