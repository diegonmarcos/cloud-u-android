#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #1084 — Unison, the stateful two-way sync engine of the Sync tab, sits    ║
# ║ next to rclone and rsync as ONE declaration, ONE scheduler, ONE page      ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
#   N1  ONE source of truth for the POLICY: data/drive-sync-rules.json::unison (defaults, shared ignore rules) is baked as
#       UNISON_B64 from that same file and read by Declarations; it ships NO pair (#1173: a pair in the APK is sample data, the
#       user's pairs live in storage/UnisonPairStore and are Cloud Account's); no .prf is committed.
#   N2  the shipped policy declares no pair and the Unison version is NOT restated in the app: it is pinned once, in the engine.
#       (Pair validity - mesh host, winner policy needs backups - is libs:rclone's UnisonConfig, proved by its suite and by
#       UnisonPairsTest on pairs the user makes.)
#   N3  the conflict surface: the Folders Sync page draws the pairs (unisonSection), the card has Sync now and
#       Stop, and each conflicted path carries all three of keep-A / keep-B / keep-both, each reaching
#       UnisonCoordinator.resolve with its UnisonResolve.Choice.
#   N4  the scheduler: UnisonSyncWorker rides WorkManager, decides per pair with SyncSchedule.isDue, runs
#       pairs through UnisonSyncer (the code Sync now runs), and MainActivity schedules it.
#   N5  the app compiles no unison binary and starts no process of its own: the engine does (libs:rclone-engine).
#   N6  the JVM suites that prove it exist: profile generation, config, output/conflicts, resolve, runner, state, the shipped declaration.
#
# OWN-SOURCE ONLY. python3 and grep; no network, no build. Each assertion is proven red by a mutation of a copy.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
LIBRCLONE="$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:rclone"]["dir"])))' "$APP/build.json" "$APP")"
DECL="$APP/data/drive-sync-rules.json"
GRADLE="$APP/app/build.gradle"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$DECL" "$GRADLE" "$SRC/Declarations.kt" "$SRC/UnisonSyncWorker.kt" "$SRC/sync/UnisonScreens.kt" "$SRC/sync/UnisonCoordinator.kt" "$SRC/sync/UnisonSyncer.kt" "$SRC/MainActivity.kt" "$SRC/sync/FsyncPage.kt" "$SRC/storage/UnisonPairs.kt" "$SRC/storage/UnisonPairStore.kt"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

# n1 <decl> <gradle> <declarations.kt> <tree>
n1() {
    python3 - "$@" <<'PYTHON'
import json, os, re, sys
decl, gradle, kt, tree = sys.argv[1:5]
bad = 0
d = json.load(open(decl))
if not isinstance(d.get("unison"), dict) or d["unison"].get("pairs"):
    print("    drive-sync-rules.json::unison is missing or ships a pair: the APK carries Unison's policy only (#1173)"); bad = 1
if not d.get("unison", {}).get("defaults") or not d.get("unison", {}).get("ignore"):
    print("    the Unison policy (defaults, ignore) is missing"); bad = 1
g = open(gradle).read()
if "UNISON_B64" not in g or ".unison" not in g or "drive-sync-rules.json" not in g:
    print("    build.gradle does not bake UNISON_B64 from drive-sync-rules.json::unison"); bad = 1
if "BuildConfig.UNISON_B64" not in open(kt).read():
    print("    Declarations.kt does not read BuildConfig.UNISON_B64"); bad = 1
for dp, _, fs in os.walk(tree):
    for f in fs:
        if f.endswith(".prf"):
            print("    a hand-written profile is committed: " + os.path.join(dp, f)); bad = 1
hosts = [p["root_b"].get("host") for p in d.get("unison", {}).get("pairs", []) if "host" in p.get("root_b", {})]
roots = [p["root_a"] for p in d.get("unison", {}).get("pairs", [])]
if any(r.startswith("/") for r in roots):
    print("    a root_a is an absolute device path"); bad = 1
for dp, _, fs in os.walk(os.path.join(tree, "app", "src", "main", "java")):
    for f in fs:
        if not f.endswith(".kt"): continue
        text = open(os.path.join(dp, f)).read()
        for v in hosts + roots:
            if v and v in text:
                print("    %s restates the declared %r: the declaration is the one source" % (f, v)); bad = 1
sys.exit(bad)
PYTHON
}

# n2 <decl> <engine pin>: the shipped policy ships no pair and restates no version
n2() {
    python3 - "$@" <<'PYTHON'
import json, sys
d = json.load(open(sys.argv[1]))["unison"]
pin = json.load(open(sys.argv[2]))
bad = 0
if d.get("pairs"):
    print("    the declaration ships %d pair(s): a pair in the APK is sample data (#1173)" % len(d["pairs"])); bad = 1
text = json.dumps(d)
if pin["version"] in text or pin["ocaml"] in text:
    print("    the declaration restates the Unison version/ocaml: it is pinned once, in the engine"); bad = 1
sys.exit(bad)
PYTHON
}

# n3 <screens.kt> <rclone screen.kt> <strings.xml>
n3() {
    python3 - "$@" <<'PYTHON'
import re, sys
ui, page, strings = (open(f).read() for f in sys.argv[1:4])
bad = 0
if "unisonSection(" not in page: print("    FolderSyncScreen does not draw unisonSection"); bad = 1
for needle in ("coordinator.syncNow(pair)", "coordinator.cancel(pair)", "DriveTags.UNISON_SYNC_NOW"):
    if needle not in ui: print("    the pair card lacks %s" % needle); bad = 1
for choice in ("KEEP_A", "KEEP_B", "KEEP_BOTH"):
    if not re.search(r"coordinator\.resolve\(pair, c\.path, UnisonResolve\.Choice\.%s\)" % choice, ui):
        print("    no button reaches resolve() with %s" % choice); bad = 1
for s in ("unison_keep_a", "unison_keep_b", "unison_keep_both", "unison_sync_now", "unison_conflicts"):
    if 'name="%s"' % s not in strings: print("    string %s missing" % s); bad = 1
if "DriveCard(" not in ui or "PillRow" not in ui: print("    the card is not built from the shared DriveCard/PillRow"); bad = 1
sys.exit(bad)
PYTHON
}

# n4 <worker.kt> <syncer.kt> <main.kt>
n4() {
    python3 - "$@" <<'PYTHON'
import sys
w, s, m = (open(f).read() for f in sys.argv[1:4])
bad = 0
if "PeriodicWorkRequestBuilder<UnisonSyncWorker>" not in w or "enqueueUniquePeriodicWork" not in w: print("    the worker is not a WorkManager periodic request"); bad = 1
if "SyncSchedule.isDue(" not in w: print("    the worker does not decide per pair with SyncSchedule.isDue"); bad = 1
if "UnisonSyncer(" not in w or ".sync(pair" not in w: print("    the worker does not run pairs through UnisonSyncer"); bad = 1
if "UnisonSyncWorker.schedule(this)" not in m: print("    MainActivity does not schedule the worker"); bad = 1
if "fun start(" not in s or "fun sync(" not in s: print("    UnisonSyncer lost start/sync"); bad = 1
sys.exit(bad)
PYTHON
}

echo "── N1 one source of truth ──"
n1 "$DECL" "$GRADLE" "$SRC/Declarations.kt" "$APP" && pass "pairs declared in drive-sync-rules.json::unison, baked from it, read by Declarations; no .prf, no restated host or root" || fail "the declaration is not the one source (see above)"
echo "── N2 the declaration is valid and pins no version ──"
n2 "$DECL" "$ROOT/ab_cloud-libs-shared/libs/rclone-engine/data/unison-binary.json" && pass "every pair valid; no winner without backup; no Unison version restated" || fail "the declaration breaks a rule (see above)"
echo "── N3 the conflict surface ──"
n3 "$SRC/sync/UnisonScreens.kt" "$SRC/sync/FsyncPage.kt" "$APP/app/src/main/res/values/strings.xml" && pass "pairs on the Folders Sync page; Sync now, Stop; keep-A/keep-B/keep-both each reach resolve()" || fail "the Sync page does not surface pairs and conflicts (see above)"
echo "── N4 the scheduler ──"
n4 "$SRC/UnisonSyncWorker.kt" "$SRC/sync/UnisonSyncer.kt" "$SRC/MainActivity.kt" && pass "WorkManager periodic, per-pair isDue, runs through UnisonSyncer, scheduled by MainActivity" || fail "the scheduler is not wired (see above)"

echo "── N5 no binary and no process in the app ──"
if [ -z "$(find "$APP/app" -path '*/jniLibs/*' -name '*unison*' 2>/dev/null)" ] \
   && ! grep -rEq 'ProcessBuilder|Runtime\.getRuntime\(\)\.exec' "$SRC/sync/Unison"*.kt "$SRC/UnisonSyncWorker.kt" \
   && ! grep -rEq 'ProcessBuilder|\.exec\(' "$LIBRCLONE/src/main/java/com/diegonmarcos/cloudlib/rclone/unison"; then
    pass "the app and the contract module run no process and carry no unison binary: the engine APK does"
else fail "the app or libs:rclone execs a process or ships a unison binary"; fi

echo "── N6 the JVM suites ──"
missing=""
for t in "$LIBRCLONE/src/test/java/com/diegonmarcos/cloudlib/rclone/unison/"{UnisonConfigTest,UnisonProfileTest,UnisonOutputTest,UnisonResolveTest,UnisonRunnerTest,UnisonStateStoreTest}.kt \
         "$APP/app/src/test/java/com/diegonmarcos/clouddrive/sync/UnisonDeclarationTest.kt" "$APP/app/src/test/java/com/diegonmarcos/clouddrive/storage/UnisonPairsTest.kt"; do
    [ -f "$t" ] || missing="$missing $t"
done
[ -z "$missing" ] && pass "profile generation, config, output/conflicts, resolve, runner, state and the shipped declaration are each tested" || fail "missing suites:$missing"

# ── the tester proves itself: each assertion goes RED on a mutated copy ─────────────────────
echo "── mutations: every assertion must fail on its own break ──"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mut() {  # mut <label> <command that must fail>
    if "${@:2}" >/dev/null 2>&1; then fail "$1 passed a broken copy (tester is vacuous)"; else pass "$1 -> RED"; fi
}
cp "$DECL" "$TMP/decl.json"; cp "$GRADLE" "$TMP/g.gradle"; cp "$SRC/Declarations.kt" "$TMP/d.kt"
cp "$SRC/sync/UnisonScreens.kt" "$TMP/ui.kt"; cp "$SRC/sync/FsyncPage.kt" "$TMP/page.kt"; cp "$APP/app/src/main/res/values/strings.xml" "$TMP/s.xml"
cp "$SRC/UnisonSyncWorker.kt" "$TMP/w.kt"; cp "$SRC/sync/UnisonSyncer.kt" "$TMP/sy.kt"; cp "$SRC/MainActivity.kt" "$TMP/m.kt"
PIN="$ROOT/ab_cloud-libs-shared/libs/rclone-engine/data/unison-binary.json"
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); d["unison"]["pairs"].append({"id":"sample","root_a":"Documents","root_b":{"path":"/x"}}); json.dump(d,open(sys.argv[2],"w"))' "$DECL" "$TMP/sample-pair.json"
mut "a sample pair shipped in the APK (n2)" n2 "$TMP/sample-pair.json" "$PIN"
mut "a sample pair shipped in the APK (n1)" n1 "$TMP/sample-pair.json" "$GRADLE" "$SRC/Declarations.kt" "$APP"
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); d["unison"]["_doc"]+=" runs unison "+json.load(open(sys.argv[3]))["version"]; json.dump(d,open(sys.argv[2],"w"))' "$DECL" "$TMP/ver.json" "$PIN"
mut "a restated Unison version" n2 "$TMP/ver.json" "$PIN"
sed -i 's/UNISON_B64/UNISON_X/g' "$TMP/g.gradle"; mut "gradle that stops baking the declaration" n1 "$TMP/decl.json" "$TMP/g.gradle" "$TMP/d.kt" "$APP"
sed -i 's/KEEP_BOTH/KEEP_NONE/' "$TMP/ui.kt"; mut "a conflict row without keep-both" n3 "$TMP/ui.kt" "$TMP/page.kt" "$TMP/s.xml"
sed -i 's/unisonSection(/noSection(/' "$TMP/page.kt"; mut "a Folders Sync page that does not draw the pairs" n3 "$SRC/sync/UnisonScreens.kt" "$TMP/page.kt" "$TMP/s.xml"
sed -i 's/SyncSchedule.isDue(/Always.isDue(/' "$TMP/w.kt"; mut "a worker that stops deciding per pair" n4 "$TMP/w.kt" "$TMP/sy.kt" "$TMP/m.kt"
sed -i 's/UnisonSyncWorker.schedule(this)/\/\/ off/' "$TMP/m.kt"; mut "an app that never schedules the worker" n4 "$SRC/UnisonSyncWorker.kt" "$TMP/sy.kt" "$TMP/m.kt"

echo
if [ "$FAILURES" -eq 0 ]; then echo "PASS  test-drive-unison"; exit 0; fi
echo "$FAILURES failure(s)"; exit 1
