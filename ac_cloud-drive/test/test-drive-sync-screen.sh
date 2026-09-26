#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #603 — CONFIGS ▸ GIT is GitSync-class: one card per repository with the     ║
# ║ live glance (branch, upstream, ahead/behind, dirty, CONFLICTS, state),   ║
# ║ the last sync, stepped one-tap sync, per-repo period / network / auth,   ║
# ║ a persisted history, conflicts surfaced not swallowed; Rclone and Mounts ║
# ║ speak the same card language                                              ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
#   G1  the glance carries every field the card prints, read from the ENGINE
#       (GitEngine.status) — never a second git implementation in the app.
#   G2  the card: StatusLight from the last outcome + conflicts; the conflict count
#       in the off colour; Resolve opens the engine on the repo; Sync now / Open /
#       History / Settings pills; the auto footer.
#   G3  one-tap sync is stepped (staging → committing → pulling → pushing) through
#       the engine's own verbs; pushing says it cannot be interrupted.
#   G4  per-repo settings: the library model carries syncIntervalMinutes and
#       syncRequireUnmetered; the sheet offers the DECLARED periods
#       (ui.configs.git_periods_minutes), the network rule and the auth kinds, and
#       writes the ManagedRepo the engine reads; secrets go to GitCredentialStore.
#   G5  history: SyncHistory is app-owned, capped, written by BOTH the card's sync
#       and the worker (trigger manual / scheduled) and rendered.
#   G6  the scheduler decides per repository with SyncSchedule.isDue (pure, JVM-tested)
#       against the repo's own period and network rule; conflicts are not retried.
#   G7  declared-not-cloned cards compose <root>/<name> + the upstream URL once
#       (Declarations.cloneUrl) and hand both to the engine.
#   G8  Rclone and Mounts: cards with a Test light (a LOOK at the thing), progress
#       rows from the runner's stats, the fleet's declared status shown as
#       UNVERIFIABLE, never green; badges from strings, not literals.
#   M   mutation-proof: the isDue call dropped from the worker → G6 red; the
#       conflict line dropped from the card → G2 red; unmutated stays green.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
CARDS="$SRC/sync/GitReposScreen.kt"
COORD="$SRC/sync/GitSyncCoordinator.kt"
HIST="$SRC/sync/SyncHistory.kt"
RC="$SRC/sync/RcloneMountsScreens.kt"
RCC="$SRC/sync/RcloneCoordinator.kt"
WORKER="$SRC/GitSyncWorker.kt"
GIT_MOD="$(python3 -c 'import json,os,sys; b=json.load(open(sys.argv[1])); print(os.path.normpath(os.path.join(sys.argv[2], b["modules"]["libs:git-sync"]["dir"])))' "$BJ" "$APP")"
MODELS="$GIT_MOD/src/main/java/com/diegonmarcos/cloudlib/gitsync/GitModels.kt"
LIB_SCREEN="$GIT_MOD/src/main/java/com/diegonmarcos/cloudlib/gitsync/GitSyncScreen.kt"
TESTS="$APP/app/src/test/java/com/diegonmarcos/clouddrive"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$BJ" "$CARDS" "$COORD" "$HIST" "$RC" "$RCC" "$WORKER" "$MODELS" "$LIB_SCREEN"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

# g2 <GitReposScreen.kt> : the card prints the conflict count in the off colour and offers Resolve
g2() {
    grep -qE 'stringResource\(R\.string\.sync_conflicts, glance\.conflicts\), color = off' "$1" &&
    grep -qE 'Pill\(stringResource\(R\.string\.sync_resolve_conflicts, glance\.conflicts\), onOpen, filled = true\)' "$1" &&
    grep -qE 'glance\.conflicts > 0 -> StatusLight\.State\.OFF' "$1"
}
# g6 <GitSyncWorker.kt> : the worker decides per repository through SyncSchedule.isDue with the repo's own period and rule
g6() {
    grep -qE 'SyncSchedule\.isDue\(' "$1" && grep -qE 'repoIntervalMinutes = repo\.syncIntervalMinutes' "$1" && grep -qE 'requireUnmetered = repo\.syncRequireUnmetered' "$1" && grep -qE 'if \(!due\) return@forEach' "$1"
}

echo "── G1 the glance is the engine's status ──"
if grep -qE 'data class Glance\(' "$COORD" && for f in branch upstream ahead behind changed conflicts repositoryState error gone; do grep -qE "val $f:" "$COORD" || exit 1; done; then pass "Glance carries branch, upstream, ahead, behind, changed, conflicts, repositoryState, error, gone"; else fail "Glance lacks a field the card prints"; fi
if grep -qE 'GitEngine\(dir\)\.use \{ e ->' "$COORD" && grep -qE 'val s = e\.status\(\)' "$COORD"; then pass "the glance is GitEngine.status(), not a second git"; else fail "the coordinator does not read GitEngine.status()"; fi
if grep -rqE 'org\.eclipse\.jgit' "$SRC"; then fail "the app imports JGit directly — the library is the engine"; else pass "no JGit in the app: every verb goes through libs:git-sync"; fi
if grep -qE 'RepoRegistry\(File\(ctx\.filesDir, GitSyncWorker\.REGISTRY_FILE\)\)' "$COORD" && grep -qE 'const val REGISTRY_FILE = "git-sync/repos\.json"' "$WORKER" && grep -qE 'RepoRegistry\(File\(context\.filesDir, "git-sync/repos\.json"\)\)' "$LIB_SCREEN"; then pass "card, worker and engine screen read the SAME registry"; else fail "registry paths differ"; fi

echo "── G2 the card ──"
g2 "$CARDS" && pass "conflicts in the off colour, a red light, and a Resolve pill that opens the engine" || fail "conflicts are not surfaced on the card"
for s in sync_now sync_open sync_history sync_settings sync_last sync_never sync_auto sync_manual sync_glance_reading sync_glance_gone sync_glance_unreadable sync_clean sync_changed; do
    grep -qE "R\.string\.$s\b" "$CARDS" && pass "card prints $s" || fail "card lacks $s"
done
if grep -qE 'StatusLightRow|light = light' "$CARDS" && grep -qE 'tag = DriveTags\.SYNC_REPO_CARD' "$CARDS"; then pass "the card is a DriveCard with a StatusLight and its tag"; else fail "the repo card is not the shared card"; fi
if grep -qE 'append\(" → "\)\.append\(it\)' "$CARDS" && grep -qE 'append\(" *↑"\)\.append\(glance\.ahead\)\.append\(" ↓"\)\.append\(glance\.behind\)' "$CARDS" && grep -qE 'if \(glance\.repositoryState != "SAFE"\)' "$CARDS"; then pass "the glance line: branch → upstream ↑ahead ↓behind, state when not SAFE"; else fail "the glance line is incomplete"; fi

echo "── G3 stepped one-tap sync ──"
if grep -qE 'enum class Step \{ STAGING, COMMITTING, PULLING, PUSHING \}' "$COORD" && grep -qE 'e\.stageAll\(\)' "$COORD" && grep -qE 'e\.commit\(repo\.syncMessage' "$COORD" && grep -qE 'e\.pull\(rebase = repo\.pullRebase, auth = credentials\.authFor\(repo\)\)' "$COORD" && grep -qE 'e\.push\(auth = credentials\.authFor\(repo\)\)' "$COORD"; then pass "stage → commit → pull → push through the engine's verbs, step published"; else fail "the stepped sync is not the engine's four verbs"; fi
if grep -qE 'GitSyncCoordinator\.Step\.values\(\)\.forEach' "$CARDS" && grep -qE 'sync_step_uninterruptible' "$CARDS"; then pass "the card names the step in flight and the uninterruptible push"; else fail "steps not rendered"; fi

echo "── G4 per-repo settings ──"
if grep -qE 'val syncIntervalMinutes: Long = 0' "$MODELS" && grep -qE 'val syncRequireUnmetered: Boolean = true' "$MODELS"; then pass "ManagedRepo carries the per-repo period and network rule"; else fail "the library model lacks the per-repo fields"; fi
if grep -qE 'syncIntervalMinutes = intervalText\.toLongOrNull\(\) \?: 0L, syncRequireUnmetered = unmetered' "$LIB_SCREEN"; then pass "the engine's own Settings tab saves them too"; else fail "the library SettingsTab does not save the new fields"; fi
if grep -qE 'val periods = Declarations\.configs\.gitPeriodsMinutes' "$CARDS" && grep -qE 'autoSync = autoSync, syncIntervalMinutes = interval, syncRequireUnmetered = unmetered' "$CARDS" && grep -qE 'coordinator\.setSecret\(repo, secret\)' "$CARDS"; then pass "the sheet offers the declared periods, the rule and auth; writes the ManagedRepo; secrets to the credential store"; else fail "RepoSettingsSheet incomplete"; fi
if python3 -c 'import json,sys; p=json.load(open(sys.argv[1]))["ui"]["configs"]["git_periods_minutes"]; sys.exit(0 if p and all(x>=15 for x in p) and 0 not in p else 1)' "$BJ"; then pass "declared periods ≥ 15 and never 0 (0 means the base)"; else fail "ui.configs.git_periods_minutes invalid"; fi
if grep -qE 'listOf\("none" to R\.string\.sync_auth_none, "https" to R\.string\.sync_auth_https, "ssh" to R\.string\.sync_auth_ssh\)' "$CARDS"; then pass "auth kinds: none / HTTPS token / SSH key"; else fail "auth kinds missing"; fi

echo "── G5 history ──"
if grep -qE 'class SyncHistory\(private val file: File, private val cap: Int = CAP\)' "$HIST" && grep -qE 'const val CAP = 200' "$HIST" && grep -qE 'const val TRIGGER_MANUAL' "$HIST" && grep -qE 'const val TRIGGER_SCHEDULED' "$HIST"; then pass "SyncHistory is app-owned, capped, with the two triggers"; else fail "SyncHistory shape wrong"; fi
if grep -qE 'history\.append\(SyncEvent\(now, repo\.id, repo\.name, SyncHistory\.TRIGGER_MANUAL' "$COORD" && grep -qE 'history\.append\(SyncEvent\(at, repo\.id, repo\.name, SyncHistory\.TRIGGER_SCHEDULED' "$WORKER"; then pass "both the card's sync and the worker write the history"; else fail "a sync path does not write the history"; fi
if grep -qE 'private fun HistoryRow\(e: SyncEvent' "$CARDS" && grep -qE 'items\(events\.take\(50\)' "$CARDS" && grep -qE 'testTag\(DriveTags\.SYNC_HISTORY\)' "$CARDS"; then pass "the history is rendered (last 50, per-repo inline)"; else fail "history not rendered"; fi

echo "── G6 the scheduler decides per repository ──"
g6 "$WORKER" && pass "the worker asks SyncSchedule.isDue with the repo's own period and rule and skips the rest" || fail "the worker does not decide per repository"
if grep -qE 'fun isDue\(' "$HIST" && grep -qE 'val period = if \(repoIntervalMinutes > 0\) repoIntervalMinutes else baseIntervalMinutes' "$HIST" && grep -qE 'if \(requireUnmetered && networkUnmetered != true\) return false' "$HIST"; then pass "SyncSchedule.isDue: own period or base; Wi-Fi rule refuses an unknown network"; else fail "SyncSchedule.isDue rules missing"; fi
if grep -qE 'return Result\.success\(\)' "$WORKER" && grep -qE 'NET_CAPABILITY_NOT_METERED' "$WORKER"; then pass "conflicts are not retried; the network is read from the platform"; else fail "worker outcome/network rules missing"; fi
if [ -f "$TESTS/sync/SyncHistoryTest.kt" ] && grep -qE 'class SyncScheduleTest' "$TESTS/sync/SyncHistoryTest.kt" && grep -qE 'ownPeriodOverridesTheBase' "$TESTS/sync/SyncHistoryTest.kt" && grep -qE 'capDropsTheOldest' "$TESTS/sync/SyncHistoryTest.kt"; then pass "the JVM suite proves the decision and the cap"; else fail "no JVM proof of SyncSchedule / SyncHistory"; fi

echo "── G7 declared, not cloned ──"
if grep -qE 'fun cloneUrl\(repo: GitRepoDecl\): String\? = upstream\?\.let \{ "https://\$\{it\.host\}/\$\{repo\.githubOwner\}/\$\{repo\.name\}\.git" \}' "$SRC/Declarations.kt" && grep -qE 'actions\.openEngine\(EngineActivity\.ENGINE_GIT, File\(root, d\.name\)\.absolutePath, url\)' "$CARDS"; then pass "<root>/<name> + upstream URL composed once and handed to the engine"; else fail "clone-into-store composition missing"; fi
if grep -qE 'tag = DriveTags\.SYNC_DECLARED_CARD' "$CARDS" && grep -qE 'badge = if \(d\.private\) stringResource\(R\.string\.chrome_private\) else null' "$CARDS"; then pass "declared cards carry the private badge"; else fail "declared cards incomplete"; fi

echo "── G8 Rclone and Mounts, same language ──"
if grep -qE 'tag = DriveTags\.SYNC_REMOTE_CARD' "$RC" && grep -qE 'tag = DriveTags\.SYNC_MOUNT_CARD' "$RC" && grep -qE 'testTag\(DriveTags\.SYNC_JOB_ROW\)' "$RC" && grep -qE 'testTag\(DriveTags\.SYNC_CONNECTION_ROW\)' "$RC"; then pass "remote cards, mount cards, job rows, connection rows are tagged"; else fail "rclone/mounts tree not tagged"; fi
if grep -qE 'last != null -> StatusLight\.of\(last\)' "$RC" && grep -qE 'unreachableDeclared -> StatusLight\.State\.UNVERIFIABLE' "$RC" && grep -qE 'light = StatusLight\.of\(last\)' "$RC"; then pass "a Test result lights the card; a fleet-declared status is UNVERIFIABLE, never green"; else fail "rclone/mounts lights not honest"; fi
if grep -qE 'StatusLightRow\(if \(c\.status == "ok"\) StatusLight\.State\.UNVERIFIABLE else StatusLight\.State\.OFF, c\.name\)' "$RC"; then pass "fleet connections: ok → UNVERIFIABLE (the fleet's word), unreachable → OFF with its reason"; else fail "fleet connection lights wrong"; fi
if grep -qE 'runner\.start\(job,' "$RCC" && grep -qE 'onStats = \{ s ->' "$RCC" && grep -qE 'val runs = MutableStateFlow<Map<String, Run>>' "$RCC" && grep -qE 'LinearProgressIndicator\(progress = \{ s\?\.fraction \?: 0f \}' "$RC"; then pass "job progress rows drive from the runner's stats, held by the coordinator across tab changes"; else fail "rclone job progress not wired"; fi
if grep -qE 'prefs\.setLastTest\("remote:" \+ remote\.name, ok\)' "$RCC" && grep -qE 'prefs\.setLastTest\("mount:" \+ m\.id, ok\)' "$RCC"; then pass "the last Test per remote/mount is remembered"; else fail "test results not remembered"; fi
if grep -qE '"\(declared\)"' "$RC" "$CARDS"; then fail "a '(declared)' literal badge — use the string resource"; else pass "badges come from strings (chrome_declared / chrome_private / rclone_fleet_side)"; fi

echo "── M mutation-proof ──"
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
sed 's/if (!due) return@forEach/if (false) return@forEach/' "$WORKER" > "$TMP/worker.kt"
cmp -s "$WORKER" "$TMP/worker.kt" && fail "the worker mutation changed nothing (tester is stale)"
g6 "$TMP/worker.kt" && fail "G6 passed a worker that ignores the decision (tester is vacuous)" || pass "the per-repo decision dropped from the worker → G6 RED"
grep -v 'sync_resolve_conflicts' "$CARDS" > "$TMP/cards.kt"
cmp -s "$CARDS" "$TMP/cards.kt" && fail "the card mutation changed nothing (tester is stale)"
g2 "$TMP/cards.kt" && fail "G2 passed a card without Resolve (tester is vacuous)" || pass "Resolve dropped from the card → G2 RED"
g2 "$CARDS" && g6 "$WORKER" && pass "unmutated tree is still green" || fail "the unmutated tree is red"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-sync-screen: all checks passed"; else echo "test-drive-sync-screen: $FAILURES check(s) FAILED"; exit 1; fi
