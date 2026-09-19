#!/usr/bin/env bash
# test-home-avatar-icon-cache.sh — ONE process-wide app-icon cache, and nobody
# talks to PackageManager for an icon except it.
#
# HISTORY. #498-ANR (2026-09-18): the Home shade fetched icons per group per
# re-render ON MAIN → three "Input dispatching timed out" ANRs. Fixed for the
# shade alone. Then 2026-09-19: the launcher crash-LOOPED — the ANR trace's
# main thread sat in Samsung Knox's ApplicationPolicy.getApplicationIconFromDb
# (seconds PER ICON), fed by the music island (per playback callback!), the
# apps grid's curated fallbacks, and the smart folders. The lesson is not
# "cache one call site": it is that PackageManager icon lookups are a
# device-priced foreign API and exactly ONE file may pay it —
# ui/AppIconCache.kt: one PM call per package per process, off-main where the
# path allows, misses remembered.
set -u
cd "$(dirname "$0")/.."
SRC="app/src/main/java"
CACHE="$SRC/com/diegonmarcos/superapp/ui/AppIconCache.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { [ "$1" = "OK" ] && ok "$2" || bad "$2 — $1"; }

echo "== T1: getApplicationIcon appears in code in EXACTLY one file: AppIconCache =="
check "$(python3 - "$SRC" <<'PY'
import os, sys
root = sys.argv[1]
offenders = []
for dirpath, _, files in os.walk(root):
    for f in files:
        if not f.endswith(".kt"): continue
        path = os.path.join(dirpath, f)
        for n, line in enumerate(open(path), 1):
            if "getApplicationIcon" in line and not line.strip().startswith(("//", "*", "/*")):
                if not path.endswith("ui/AppIconCache.kt"):
                    offenders.append("%s:%d" % (os.path.relpath(path, root), n))
if offenders:
    print("PROBLEM: direct PackageManager icon call(s) outside AppIconCache — each one re-pays Knox's per-icon binder price on whatever thread it runs: %s" % ", ".join(offenders))
else:
    print("OK")
PY
)" "no direct icon calls outside ui/AppIconCache.kt"

echo "== T2: the cache is a real cache — memory, misses, and a non-blocking view path =="
check "$(python3 - "$CACHE" <<'PY'
import sys
try:
    t = open(sys.argv[1]).read()
except OSError:
    print("PROBLEM: ui/AppIconCache.kt does not exist"); raise SystemExit
p = []
for needle, why in (
    ("ConcurrentHashMap", "no concurrent cache map"),
    ("misses", "no negative cache - uninstalled packages re-probed every render"),
    ("fun into(", "no non-blocking view path - main-thread renders would block on Knox"),
    ("view.tag", "into() is not tag-guarded - recycled views show stale icons"),
    ("constantState?.newDrawable", "cached drawables shared across ImageViews share bounds/state"),
):
    if needle not in t:
        p.append(why)
print("; ".join(p) or "OK")
PY
)" "AppIconCache carries cache + misses + tag-guarded into()"

echo "== T3: the known hot sites route through the cache =="
check "$(python3 - "$SRC" <<'PY'
import os, sys
root = sys.argv[1]
sites = [
    "com/diegonmarcos/superapp/launcher/AggregatorStackFragment.kt",
    "com/diegonmarcos/superapp/media/MusicIslandController.kt",
    "com/diegonmarcos/superapp/media/MusicControlsPopup.kt",
    "com/diegonmarcos/superapp/apps/PhoneSmartFolders.kt",
    "com/diegonmarcos/superapp/apps/SuitePhoneAppsFragment.kt",
]
p = []
for rel in sites:
    t = open(os.path.join(root, rel)).read()
    if "AppIconCache" not in t:
        p.append("%s no longer uses AppIconCache" % rel.split("/")[-1])
print("; ".join(p) or "OK")
PY
)" "shade, music island+popup, smart folders and apps grid all use AppIconCache"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
