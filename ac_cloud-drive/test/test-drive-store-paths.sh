#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ cloud-shared-store: NO sync or clone path names the old location         ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# Owner addition (2026-10-17): every cloud-drive sync operation path — Sync-tab jobs
# (rclone + mirror), Git clone/sync locations, defaults, saved job configs, the debug
# API — points at cloud-shared-store. This pins it:
#
#   P1  the declared jobs (data/drive-rclone-jobs.json, drive-mirror-jobs.json,
#       drive-git-repos.json, drive-remotes.json) name no old location: no CloudDrive/…
#       leg, no /storage/…/CloudDrive, no old terminal link name (an rclone `remote:CloudDrive`
#       is a cloud remote's folder and is allowed).
#   P2  every clone / sync lands through SharedStore (repoDir / gitRoot / resolve), and
#       SharedStore roots at libs:shared-store's StoreAccess.root(), never shared storage.
#   P3  saved jobs are migrated on first launch, idempotently: JobPathMigration runs from
#       MainActivity, the git worker and the rclone coordinator, and rewrites BOTH the
#       registry (path + id + credential) and the rclone jobs through StorePaths.migrateLegacy.
#   P4  the debug API reports the store root and its access mode.
#   P5  the EXEC fallback exists at every git entry point (seed, page clone, worker).
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"; SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
fails=0
ok()  { echo "  PASS  $1"; }
bad() { echo "  FAIL  $1"; fails=$((fails + 1)); }

echo "── P1 declared jobs ──"
python3 - "$APP/data" <<'PY' && ok "no declared sync/clone path names the old location" || bad "a declared sync/clone path still names the old location"
import json, os, re, sys
d = sys.argv[1]; bad = []
OLD = re.compile(r"^(?:/storage/[^:]*/|/sdcard/|/data/media/\d+/)?CloudDrive(?:/|$)|cloud-drive-" "shared-store")
def walk(x, where):
    if isinstance(x, dict):
        for k, v in x.items():
            if k.startswith("_doc"): continue
            walk(v, where + "." + k)
    elif isinstance(x, list):
        for i, v in enumerate(x): walk(v, "%s[%d]" % (where, i))
    elif isinstance(x, str) and ":" not in x and OLD.search(x):
        bad.append("%s = %r" % (where, x))
for f in ("drive-rclone-jobs.json", "drive-mirror-jobs.json", "drive-git-repos.json", "drive-remotes.json"):
    p = os.path.join(d, f)
    if os.path.isfile(p): walk(json.load(open(p)), f)
for b in bad: print("        " + b)
sys.exit(1 if bad else 0)
PY

echo "── P2 every clone/sync lands in the store ──"
grep -qE 'fun root\(\): File = StoreAccess\.root\(\)' "$SRC/SharedStore.kt" && ok "SharedStore.root() is the store (libs:shared-store)" || bad "SharedStore.root() is not StoreAccess.root()"
hits="$(grep -rnE '"CloudDrive|/CloudDrive|getExternalStorageDirectory\(\), *"CloudDrive' "$SRC" | grep -vE '^\S+:\s*(\*|//)' || true)"
[ -z "$hits" ] && ok "no Kotlin source spells a CloudDrive path" || { bad "a Kotlin source spells a CloudDrive path:"; echo "$hits" | sed 's/^/        /'; }
for f in StoreSeed.kt sync/GitSyncCoordinator.kt; do
    grep -q 'SharedStore.repoDir(' "$SRC/$f" && ok "$f clones into SharedStore.repoDir" || bad "$f does not clone into SharedStore.repoDir"
done
grep -q 'path.startsWith(STORE_PREFIX) -> File(com.diegonmarcos.clouddrive.SharedStore.root()' "$SRC/backups/BackupsScreen.kt" && ok "mirror jobs resolve store:<rel> into the store" || bad "mirror jobs cannot address the store"

echo "── P3 saved jobs migrate on first launch, idempotently ──"
grep -q 'JobPathMigration.run(this)' "$SRC/MainActivity.kt" && grep -q 'JobPathMigration.run(applicationContext)' "$SRC/GitSyncWorker.kt" \
    && grep -q 'JobPathMigration.run(ctx)' "$SRC/sync/RcloneCoordinator.kt" && ok "JobPathMigration runs at launch, in the git worker and in the rclone coordinator" || bad "JobPathMigration is not run everywhere a saved path is read"
grep -q 'migrateLegacy' "$SRC/JobPathMigration.kt" && grep -q 'rekey(r.id, id)' "$SRC/JobPathMigration.kt" && grep -q 'source = s ?: j.source, destination = d ?: j.destination' "$SRC/JobPathMigration.kt" \
    && ok "registry paths (+ id + credential) and both rclone legs go through the one rule" || bad "JobPathMigration does not rewrite the registry and both rclone legs through StorePaths.migrateLegacy"
grep -q 'the migration is idempotent' "$APP/app/src/test/java/com/diegonmarcos/clouddrive/SharedStorePathsTest.kt" && ok "the JVM test pins idempotence" || bad "no unit test pins the migration's idempotence"

echo "── P4 debug API ──"
grep -q '"store_mode"' "$SRC/debugapi/DriveDebugApi.kt" && grep -q 'SharedStore.root().absolutePath' "$SRC/debugapi/DriveDebugApi.kt" && ok "/api/health reports store_root and store_mode" || bad "/api/health does not report the store and its mode"

echo "── P5 the EXEC fallback at every git entry point ──"
for f in StoreSeed.kt sync/GitSyncCoordinator.kt GitSyncWorker.kt; do
    grep -q 'StoreGit' "$SRC/$f" && ok "$f falls back to StoreGit (CLOUD_EXEC) when the shared uid is not in effect" || bad "$f has no EXEC fallback"
done

[ "$fails" -eq 0 ] || { echo "test-drive-store-paths: $fails failure(s)"; exit 1; }
echo "test-drive-store-paths: all checks passed"
