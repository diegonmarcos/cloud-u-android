#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #575 — cloud-drive is THE on-phone repo store: ONE declared root, every  ║
# ║ engine lands in it, no absolute /storage path anywhere, and the GitSync ║
# ║ flows (clone / open per declared repo, scheduled sync) are wired        ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. The audit (a0_docs/eng-specs/cloud-drive-parity-audit.md)
# found the store did not exist: the git manager cloned wherever the user typed,
# every engine's downloads sat in Android/data where no other app can read them,
# and cloud-code carried its own hand-written /storage/emulated/0/git root. All
# of that compiles. Gradle cannot see that a root is declared twice, or that a
# declared remote never reaches rclone.conf, or that the scheduler is never
# enqueued. This file can.
#
#   S1  build.json::storage.shared_root exists, is RELATIVE (no leading /, no
#       /storage/emulated), and app/build.gradle bakes it into BuildConfig.SHARED_ROOT
#       from that key — with the guard that fails the build on an absolute value.
#   S2  SharedStore resolves it at runtime from Environment.getExternalStorageDirectory()
#       and NOTHING in this app or the four engine libraries spells out
#       /storage/emulated/0 (the user id in it changes under a second profile).
#   S3  the git engine opens ON the store when the chrome names no target; the
#       Sync ▸ Git cards (#579) hand every declared-not-cloned repository a
#       "Clone into store" pill carrying <root>/<name> and the upstream URL
#       (composed ONCE by Declarations.cloneUrl); DriveActions.openEngine and the
#       activity extra carry it; the store is the hero of the Files Places sheet.
#   S4  scheduled sync: a WorkManager worker exists, is enqueued from MainActivity
#       with the interval and network rule build.json declares (baked, not literal),
#       syncs only opted-in repositories, and the library's model + Settings tab
#       carry that opt-in.
#   S5  declared rclone remotes reach rclone.conf through RcloneConfig.declare, the
#       library adds-without-overwriting (the JVM test says so by name), and a
#       job's relative local leg is resolved against the store.
#   S6  the data agrees: drive-git-repos.json declares the proof-case repository
#       (cloud-data-my-ai-memory) and every remote with an `rclone` block carries
#       a type and NO secret-shaped key.
#
# OWN-SOURCE ONLY: everything read here is under this app or a module dir its
# build.json declares.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
BUILD_JSON="$APP/build.json"
GRADLE="$APP/app/build.gradle"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
STORE="$SRC/SharedStore.kt"
CARDS="$SRC/sync/GitReposScreen.kt"
DECL="$SRC/Declarations.kt"
PLACES="$SRC/files/Places.kt"
MAIN="$SRC/MainActivity.kt"
HOST="$SRC/EngineActivity.kt"
WORKER="$SRC/GitSyncWorker.kt"
GIT_MOD="$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:git-sync"]["dir"])))' "$BUILD_JSON" "$APP")"
RCLONE_MOD="$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:rclone"]["dir"])))' "$BUILD_JSON" "$APP")"
GIT_SCREEN="$GIT_MOD/src/main/java/com/diegonmarcos/cloudlib/gitsync/GitSyncScreen.kt"
GIT_MODELS="$GIT_MOD/src/main/java/com/diegonmarcos/cloudlib/gitsync/GitModels.kt"
RCLONE_CONFIG="$RCLONE_MOD/src/main/java/com/diegonmarcos/cloudlib/rclone/RcloneConfig.kt"
RCLONE_TEST="$RCLONE_MOD/src/test/java/com/diegonmarcos/cloudlib/rclone/RcloneEngineTest.kt"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

for required in "$BUILD_JSON" "$GRADLE" "$STORE" "$CARDS" "$DECL" "$PLACES" "$MAIN" "$HOST" "$WORKER" "$GIT_SCREEN" "$GIT_MODELS" "$RCLONE_CONFIG" "$RCLONE_TEST"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required"; exit 1; }
done

echo "── S1 the ONE declaration ──"
SHARED_ROOT="$(python3 -c 'import json,sys; print((json.load(open(sys.argv[1])).get("storage") or {}).get("shared_root") or "")' "$BUILD_JSON")"
if [ -n "$SHARED_ROOT" ]; then pass "build.json::storage.shared_root = '$SHARED_ROOT'"; else fail "build.json has no storage.shared_root"; fi
case "$SHARED_ROOT" in
    /*|*storage/emulated*|*..*) fail "shared_root must be a relative folder under shared storage, not '$SHARED_ROOT'" ;;
    *) pass "shared_root is relative" ;;
esac
if grep -qE "buildJson\.storage" "$GRADLE" && grep -qE 'buildConfigField "String",\s*"SHARED_ROOT",\s*"\\"\$\{sharedRoot\}\\""' "$GRADLE"; then pass "app/build.gradle bakes SHARED_ROOT from build.json::storage"; else fail "SHARED_ROOT is not baked from build.json::storage"; fi
if grep -qE "sharedRoot\.startsWith\('/'\)" "$GRADLE" && grep -qE "throw new GradleException\(\"build\.json::storage\.shared_root" "$GRADLE"; then pass "an absolute or missing shared_root fails the build"; else fail "build.gradle does not refuse an absolute/missing shared_root"; fi
INTERVAL="$(python3 -c 'import json,sys; print(((json.load(open(sys.argv[1])).get("storage") or {}).get("git_sync") or {}).get("interval_minutes") or "")' "$BUILD_JSON")"
if [ -n "$INTERVAL" ] && [ "$INTERVAL" -ge 15 ] 2>/dev/null; then pass "storage.git_sync.interval_minutes = $INTERVAL (WorkManager floor is 15)"; else fail "storage.git_sync.interval_minutes missing or below WorkManager's 15-minute floor ('$INTERVAL')"; fi
if grep -qE 'buildConfigField "long",\s*"GIT_SYNC_INTERVAL_MINUTES"' "$GRADLE" && grep -qE 'buildConfigField "boolean", "GIT_SYNC_REQUIRE_UNMETERED"' "$GRADLE"; then pass "the schedule is baked, not literal"; else fail "GIT_SYNC_INTERVAL_MINUTES / GIT_SYNC_REQUIRE_UNMETERED not baked"; fi

echo "── S2 the store resolves at runtime; no absolute /storage path anywhere ──"
if grep -qE "Environment\.getExternalStorageDirectory\(\), BuildConfig\.SHARED_ROOT" "$STORE"; then pass "SharedStore.root() = shared storage + BuildConfig.SHARED_ROOT"; else fail "SharedStore does not derive the root from BuildConfig.SHARED_ROOT under shared storage"; fi
if grep -qE "fun resolve\(path: String\): String" "$STORE" && grep -qE "path\.startsWith\(\"/\"\) \|\| path\.contains\(':'\)" "$STORE"; then pass "SharedStore.resolve passes remote:path and absolute paths through, roots the rest"; else fail "SharedStore.resolve missing or does not implement the relative-leg rule"; fi
ABS="$(grep -rnE "/storage/emulated/0" "$APP/app/src" "$APP/data" "$GIT_MOD/src/main" "$RCLONE_MOD/src/main" \
      "$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:mounts"]["dir"])))' "$BUILD_JSON" "$APP")/src/main" \
      "$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:file-editor"]["dir"])))' "$BUILD_JSON" "$APP")/src/main" 2>/dev/null \
      | grep -vE "_doc|// |/\* |\* " || true)"
if [ -z "$ABS" ]; then pass "no /storage/emulated/0 literal in app or engine sources"; else fail "a device-absolute path is written down:"; printf '%s\n' "$ABS" | sed 's/^/        /'; fi

echo "── S3 the git manager lands in the store ──"
if grep -qE 'if \(engine == ENGINE_GIT\) SharedStore\.root\(\)\.absolutePath else null' "$HOST"; then pass "EngineActivity opens the git engine on the store when the chrome names no target"; else fail "EngineActivity does not default the git target to SharedStore.root()"; fi
if grep -qE 'val root = remember \{ SharedStore\.root\(\)\.absolutePath \}' "$CARDS"; then pass "the Sync ▸ Git cards read the store root from SharedStore"; else fail "GitReposScreen does not read SharedStore.root()"; fi
if grep -qE '"shared_root" -> SharedStore\.root\(\)' "$PLACES" && grep -qE 'hero = p\.hero' "$PLACES"; then pass "the store is a Files place (the hero of the Places sheet)"; else fail "Places does not offer the store"; fi
if grep -qE 'fun openEngine\(engine: String, target: String, url: String = ""\)' "$SRC/DriveActions.kt" && grep -qE 'const val EXTRA_URL' "$HOST" && grep -qE 'GitSyncScreen\(target, onOpenFile, onClose, cloneUrl = cloneUrl\)' "$HOST"; then pass "the clone URL travels DriveActions → activity → GitSyncScreen(cloneUrl)"; else fail "the clone-url seam is broken somewhere between openEngine(engine, target, url), EXTRA_URL and GitSyncScreen(cloneUrl)"; fi
if grep -qE "cloneUrl: String\? = null" "$GIT_SCREEN" && grep -qE "prefillUrl" "$GIT_SCREEN"; then pass "libs:git-sync accepts a clone URL and prefills the Add dialog"; else fail "GitSyncScreen has no cloneUrl / prefillUrl"; fi
if grep -qE 'fun cloneUrl\(repo: GitRepoDecl\): String\? = upstream\?\.let \{ "https://\$\{it\.host\}/\$\{repo\.githubOwner\}/\$\{repo\.name\}\.git" \}' "$DECL" && grep -qE 'actions\.openEngine\(EngineActivity\.ENGINE_GIT, File\(root, d\.name\)\.absolutePath, url\)' "$CARDS" && grep -qE 'val url = family\.cloneUrl\(d\) \?: ""' "$CARDS"; then pass "every declared-not-cloned repository gets Clone into store → <root>/<name> from the upstream instance"; else fail "the Git cards do not compose <root>/<name> + upstream URL per declared repository"; fi

echo "── S4 scheduled sync ──"
if grep -qE "class GitSyncWorker\(context: Context, params: WorkerParameters\) : Worker\(" "$WORKER"; then pass "GitSyncWorker is a WorkManager Worker"; else fail "GitSyncWorker is not a Worker"; fi
if grep -qE "PeriodicWorkRequestBuilder<GitSyncWorker>\(BuildConfig\.GIT_SYNC_INTERVAL_MINUTES, TimeUnit\.MINUTES\)" "$WORKER" && grep -qE "BuildConfig\.GIT_SYNC_REQUIRE_UNMETERED" "$WORKER" && grep -qE "ExistingPeriodicWorkPolicy\.UPDATE" "$WORKER"; then pass "the period and the network rule come from the baked declaration; UPDATE policy"; else fail "the worker's schedule is not the baked declaration (or not UPDATE)"; fi
if grep -qE "^\s*GitSyncWorker\.schedule\(this\)" "$MAIN"; then pass "MainActivity enqueues the scheduler"; else fail "nothing enqueues GitSyncWorker (a commented-out call does not count)"; fi
if grep -qE "\.filter \{ it\.autoSync \}" "$WORKER" && grep -qE "\.sync\(" "$WORKER" && grep -qE "credentials\.authFor\(repo\)" "$WORKER"; then pass "the worker syncs opted-in repositories with their stored credential"; else fail "the worker does not filter on autoSync / call sync() with authFor()"; fi
if grep -qE "val autoSync: Boolean = false" "$GIT_MODELS" && grep -qE "Switch\(checked = autoSync" "$GIT_SCREEN" && grep -qE "autoSync = autoSync," "$GIT_SCREEN"; then pass "ManagedRepo.autoSync exists, defaults off, and the Settings tab saves it"; else fail "the per-repo opt-in is missing from the model or the Settings tab"; fi
if grep -qE "androidx\.work:work-runtime" "$GRADLE"; then pass "WorkManager linked in the app"; else fail "app/build.gradle does not link androidx.work"; fi
if grep -qE "const val REGISTRY_FILE = \"git-sync/repos\.json\"" "$WORKER" && grep -qE "RepoRegistry\(File\(context\.filesDir, \"git-sync/repos\.json\"\)\)" "$GIT_SCREEN"; then pass "worker and screen read the SAME registry file"; else fail "the worker's registry path differs from the screen's"; fi

echo "── S5 declared rclone remotes and relative legs ──"
if grep -qE "fun declare\(file: File, remotes: List<RcloneRemote>\): List<RcloneRemote>" "$RCLONE_CONFIG" && grep -qE "it\.name !in have" "$RCLONE_CONFIG"; then pass "RcloneConfig.declare adds missing remotes only"; else fail "RcloneConfig.declare missing or overwrites"; fi
if grep -qE "fun configDeclareAddsMissingRemotesAndNeverTouchesExistingOnes" "$RCLONE_TEST"; then pass "the JVM suite proves declare() keeps the user's key"; else fail "no JVM test for RcloneConfig.declare"; fi
if grep -qE "BuildConfig\.RCLONE_REMOTES_B64" "$HOST" && grep -qE "RcloneConfig\.declare\(RcloneRunner\(context\)\.configFile, declaredRemotes\)" "$HOST"; then pass "EngineActivity declares the baked remotes into the runner's rclone.conf"; else fail "declared remotes never reach rclone.conf"; fi
if grep -qE 'source = SharedStore\.resolve\(j\.optString\("source"\)\), destination = SharedStore\.resolve\(j\.optString\("destination"\)\)' "$HOST"; then pass "a job's relative local leg resolves against the store"; else fail "declared job paths are not resolved through SharedStore"; fi

echo "── S6 the data agrees ──"
python3 - "$APP/data/drive-git-repos.json" "$APP/data/drive-remotes.json" <<'PYTHON' && pass "drive-git-repos.json declares the proof-case repo; every rclone block has a type and no secret" || fail "data disagrees (see above)"
import json, sys
repos = json.load(open(sys.argv[1]))
bad = 0
names = [r["name"] for r in repos["repos"]]
if "cloud-data-my-ai-memory" not in names:
    print("    drive-git-repos.json does not declare cloud-data-my-ai-memory"); bad += 1
if not any(i.get("kind") == "upstream" and i.get("host") for i in repos["instances"]):
    print("    no upstream instance with a host — the page cannot compose a clone URL"); bad += 1
remotes = json.load(open(sys.argv[2]))["remotes"]
SECRET = ("pass", "secret", "token", "key_id", "access_key", "refresh")
with_block = 0
for r in remotes:
    spec = r.get("rclone")
    if not spec: continue
    with_block += 1
    if not spec.get("type"):
        print("    remote %s: rclone block without type" % r["name"]); bad += 1
    for k, v in (spec.get("options") or {}).items():
        if any(s in k.lower() for s in SECRET) and str(v).strip():
            print("    remote %s: option %s looks like a credential" % (r["name"], k)); bad += 1
if with_block == 0:
    print("    no remote carries an rclone block — nothing would be declared"); bad += 1
sys.exit(1 if bad else 0)
PYTHON

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-shared-store: all checks passed"; else echo "test-drive-shared-store: $FAILURES check(s) FAILED"; exit 1; fi
