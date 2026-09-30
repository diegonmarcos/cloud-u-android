#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #669 — cloud-drive's on-device debug log: readable off the phone, no adb ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# A multi-day Sync ▸ Git failure was invisible because this app logged only to
# logcat. DriveDebugLog now writes the git flow's decision points into
# Download/<declared dir>/, the same self-written pattern the superapp keeps
# (CrashLogger/Trace → Download/superapp-logs/). What this pins:
#
#   T1  THE LOGGER EXISTS and its folder is DECLARED (build.json
#       diagnostics.debug_log_dir → BuildConfig.DEBUG_LOG_DIR), never a Kotlin
#       literal — the folder name lives in data, in ONE place.
#   T2  TIMESTAMPS ARE UTC WITH AN EXPLICIT Z. Fleet device logs have been
#       misread as stale because local-time (GMT+2/+3) lines were compared
#       against a UTC shell; a timestamp naming its own offset cannot be.
#   T3  THE FILE ROLLS at a cap (superapp Trace's shape: cap + .1 generation),
#       and a logging failure is swallowed, never a crash.
#   T4  THE CLONE PATH LOGS BOTH ENDS: a start line (URL HOST + destination,
#       never the full URL) and BOTH outcomes — success AND failure. Sync and
#       per-repo op outcomes land too.
#   T5  SEED OUTCOMES land in the same log (the pass whose truncation #629 is
#       about is readable off the phone).
#   T6  NO SECRET CAN REACH A CALL SITE: no token/cookie/authorization/secret
#       VALUE in any DriveDebugLog argument anywhere in the app, and the clone
#       start line carries hostOf(url), never url itself.
#   MUT mutation-proof: each property, broken on a copy, turns its check red.
#
# OWN-SOURCE ONLY. python3 and grep only, no network, no build.
# grep -c, never grep -q, after a pipe: under pipefail -q's early exit SIGPIPEs
# the upstream stage and a MATCH reads as a failed pipeline.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
BJ="$APP/build.json"
GRADLE="$APP/app/build.gradle"
LOGGER="$APP/app/src/main/java/com/diegonmarcos/clouddrive/DriveDebugLog.kt"
COORD="$APP/app/src/main/java/com/diegonmarcos/clouddrive/sync/GitSyncCoordinator.kt"
SEED="$APP/app/src/main/java/com/diegonmarcos/clouddrive/StoreSeed.kt"
SRC_MAIN="$APP/app/src/main/java"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

# a file's CODE, comment lines stripped, so prose about a defect never reads as one
_code() { grep -vE '^[[:space:]]*(\*|//|/\*)' "$1"; }

for required in "$BJ" "$GRADLE" "$LOGGER" "$COORD" "$SEED"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

# ── the checks as functions of their inputs, so the mutation block runs them on copies ──

# t1 <build.json> <build.gradle> <DriveDebugLog.kt> : declared folder, baked, no literal
t1() {
    local bj="$1" gradle="$2" logger="$3" bad=0
    python3 - "$bj" <<'PYTHON' || bad=1
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8")).get("diagnostics") or {}
v = (d.get("debug_log_dir") or "").strip()
if not v:
    print("    diagnostics.debug_log_dir is not declared: the logger would have no folder"); sys.exit(1)
if "/" in v or v.startswith("."):
    print("    debug_log_dir %r is not a plain Download/ subfolder name" % v); sys.exit(1)
PYTHON
    [ "$(grep -cE 'debug_log_dir' "$gradle")" -ge 1 ] \
        || { echo "    build.gradle never reads diagnostics.debug_log_dir"; bad=1; }
    [ "$(grep -cE '"DEBUG_LOG_DIR"' "$gradle")" -ge 1 ] \
        || { echo "    DEBUG_LOG_DIR is not baked into BuildConfig"; bad=1; }
    [ "$(_code "$logger" | grep -cE 'BuildConfig\.DEBUG_LOG_DIR')" -ge 1 ] \
        || { echo "    the logger does not read the baked declaration"; bad=1; }
    # DECLARED, NOT HARDCODED: the folder name is a literal in NO Kotlin file.
    local lit
    lit="$(_code "$logger" | grep -nE '"drive-debug"' || true)"
    [ -z "$lit" ] || { echo "    the logger hardcodes the folder name:"; printf '%s\n' "$lit" | sed 's/^/        /'; bad=1; }
    # And it writes under Download/, the no-adb-readable place, on both API branches.
    [ "$(_code "$logger" | grep -cE 'DIRECTORY_DOWNLOADS')" -ge 2 ] \
        || { echo "    the logger does not target public Download/ on both API branches"; bad=1; }
    return $bad
}

# t2 <DriveDebugLog.kt> : UTC with an explicit offset
t2() {
    local logger="$1" bad=0
    [ "$(_code "$logger" | grep -cE 'TimeZone\.getTimeZone\("UTC"\)')" -ge 1 ] \
        || { echo "    the line formatter is not pinned to UTC — a local-time log read from a UTC shell looks stale"; bad=1; }
    [ "$(_code "$logger" | grep -cE "yyyy-MM-dd'T'HH:mm:ss\.SSS'Z'")" -ge 1 ] \
        || { echo "    the timestamp does not carry an explicit Z offset"; bad=1; }
    return $bad
}

# t3 <DriveDebugLog.kt> : cap + rotation + never-throws
t3() {
    local logger="$1" bad=0
    [ "$(_code "$logger" | grep -cE 'MAX_BYTES = 256 \* 1024L')" -ge 1 ] \
        || { echo "    no size cap (superapp Trace's 256KB shape)"; bad=1; }
    [ "$(_code "$logger" | grep -cE '> MAX_BYTES')" -ge 2 ] \
        || { echo "    the cap is not enforced on both API branches"; bad=1; }
    [ "$(_code "$logger" | grep -cE '\.log\.1|FILE_NAME\}\.1|\$FILE_NAME\.1')" -ge 2 ] \
        || { echo "    the previous generation is not kept as .1 on both branches"; bad=1; }
    [ "$(_code "$logger" | grep -cE 'catch \(ignored: Throwable\)')" -ge 1 ] \
        || { echo "    a logging failure could crash the app it is meant to diagnose"; bad=1; }
    return $bad
}

# t4 <GitSyncCoordinator.kt> : clone start + BOTH outcomes; sync and op outcomes
t4() {
    local coord="$1" bad=0
    [ "$(_code "$coord" | grep -cE 'clone start: host=\$\{hostOf\(url\)\} dest=')" -ge 1 ] \
        || { echo "    the clone's start is not logged with the URL's host and the destination"; bad=1; }
    [ "$(_code "$coord" | grep -cE 'DriveDebugLog\.i\(ctx, TAG, "clone \$name:')" -ge 1 ] \
        || { echo "    a SUCCESSFUL clone leaves no line — success must be as readable as failure"; bad=1; }
    [ "$(_code "$coord" | grep -cE 'DriveDebugLog\.e\(ctx, TAG, "clone \$name FAILED:')" -ge 1 ] \
        || { echo "    a FAILED clone leaves no line — the exact multi-day invisibility this exists for"; bad=1; }
    [ "$(_code "$coord" | grep -cE 'DriveDebugLog\.[ie]\(ctx, TAG, "sync ')" -ge 2 ] \
        || { echo "    the one-tap sync's outcome (both verdicts) does not reach the debug log"; bad=1; }
    [ "$(_code "$coord" | grep -cE 'DriveDebugLog\.[ie]\(ctx, TAG, "\$opId ')" -ge 2 ] \
        || { echo "    a per-repository operation's outcome (both verdicts) does not reach the debug log"; bad=1; }
    return $bad
}

# t5 <StoreSeed.kt> : every seed outcome and migration decision lands in the log
t5() {
    local seed="$1" bad=0
    [ "$(_code "$seed" | grep -cE 'report\.lines\(\)\.forEach \{ DriveDebugLog\.i\(applicationContext, TAG, it\) \}')" -ge 1 ] \
        || { echo "    the seed report's per-repository outcomes do not reach the debug log"; bad=1; }
    [ "$(_code "$seed" | grep -cE 'DriveDebugLog\.i\(applicationContext, TAG, "migration ')" -ge 1 ] \
        || { echo "    a migration decision does not reach the debug log"; bad=1; }
    return $bad
}

# t6 <src root or file...> : NO SECRET VALUE reaches any call site
# Every DriveDebugLog call line in the app is read; none may interpolate a
# token/secret/cookie/password/authorization VALUE, the raw url, or the
# credential store — header NAMES and status codes are what a log may carry.
t6() {
    local bad=0 calls
    calls="$(grep -rnE 'DriveDebugLog\.[iwe]\(' "$@" --include='*.kt' | grep -vE '/DriveDebugLog\.kt:' || true)"
    [ -n "$calls" ] || { echo "    no call site found at all — the logger is wired to nothing"; return 1; }
    local leak
    leak="$(printf '%s\n' "$calls" | grep -icE '\$\{?(token|secret|cookie|password|session|bearer)|authorization|credentials\.' || true)"
    [ "${leak:-0}" = "0" ] \
        || { echo "    a secret-bearing value reaches a logger call site:"; \
             printf '%s\n' "$calls" | grep -inE '\$\{?(token|secret|cookie|password|session|bearer)|authorization|credentials\.' | sed 's/^/        /'; bad=1; }
    # The clone start line must ride hostOf(url) — the raw url's userinfo can carry a credential.
    local rawurl
    rawurl="$(printf '%s\n' "$calls" | grep -cE '\$url|\$\{url\}' || true)"
    [ "${rawurl:-0}" = "0" ] \
        || { echo "    a call site interpolates the raw url instead of hostOf(url):"; \
             printf '%s\n' "$calls" | grep -nE '\$url|\$\{url\}' | sed 's/^/        /'; bad=1; }
    return $bad
}

# t7 <DriveDebugLog.kt> : ONE FILE, MANY LINES — the append MECHANISM.
# Measured on the owner's phone (2026-09-30): 13 files of one line each,
# "drive-debug.log.txt", "drive-debug.log (1).txt" … (12).txt. MediaStore
# renamed the text/plain row to <name>.txt on insert, the exact-name re-query
# then matched nothing, and every line minted a fresh " (N)" row. Four pins:
#   a) the resolved row Uri is CACHED for the process (one insert, ever)
#   b) the re-query matches the name MediaStore actually stored (<name> OR
#      <name>.txt), so a restarted process appends instead of duplicating
#   c) exactly ONE insert call in the logger, and the stream opens in "wa"
#      (append) mode — "w" would also produce one line per file
#   d) with All-Files-Access (this app IS a file manager) the write is a plain
#      path APPEND, no MediaStore naming semantics at all
t7() {
    local logger="$1" bad=0
    [ "$(_code "$logger" | grep -cE 'private var cachedRow')" -ge 1 ] \
        || { echo "    the row Uri is not cached — every line re-resolves and can re-insert"; bad=1; }
    [ "$(_code "$logger" | grep -cE 'cachedRow = target')" -ge 1 ] \
        || { echo "    the inserted row is never remembered — the cache is decorative"; bad=1; }
    [ "$(_code "$logger" | grep -cE 'var uri(: Uri\?)? = cachedRow')" -ge 1 ] \
        || { echo "    the append never consults the cache"; bad=1; }
    [ "$(_code "$logger" | grep -cE '"\$name\.txt"')" -ge 1 ] \
        || { echo "    the re-query ignores the .txt MediaStore appends — a restarted process mints ' (N)' duplicates again"; bad=1; }
    [ "$(_code "$logger" | grep -c 'cr.insert(')" -eq 1 ] \
        || { echo "    not exactly one insert call — a second insert is a second file"; bad=1; }
    [ "$(_code "$logger" | grep -cE 'uri \?: cr\.insert\(')" -ge 1 ] \
        || { echo "    the insert is not guarded by 'no existing row' — a fresh row per write"; bad=1; }
    [ "$(_code "$logger" | grep -cE 'openOutputStream\(target, "wa"\)')" -ge 1 ] \
        || { echo "    the stream is not opened in append mode — every write truncates to one line"; bad=1; }
    [ "$(_code "$logger" | grep -cE 'isExternalStorageManager\(\)')" -ge 1 ] \
        || { echo "    no All-Files-Access plain-path append — the file manager's own permission unused"; bad=1; }
    return $bad
}

echo "── T1 the folder is declared once, baked, and no Kotlin literal ──"
t1 "$BJ" "$GRADLE" "$LOGGER" && pass "diagnostics.debug_log_dir → BuildConfig.DEBUG_LOG_DIR → Download/, no hardcoded name" \
    || fail "the debug-log folder is missing, hardcoded, or not under Download/"

echo "── T2 UTC timestamps with an explicit offset ──"
t2 "$LOGGER" && pass "every line is stamped UTC with a literal Z — unmisreadable from any shell" \
    || fail "a local-time or offsetless stamp would read as stale again"

echo "── T3 the file rolls at a cap and never crashes its host ──"
t3 "$LOGGER" && pass "256KB cap, previous generation kept as .1 on both API branches, failures swallowed" \
    || fail "the log could grow without bound, lose its history, or crash the app"

echo "── T4 the clone path logs start, success AND failure; sync and ops too ──"
t4 "$COORD" && pass "clone start (host+dest), both clone verdicts, both sync verdicts, both op verdicts" \
    || fail "a git-flow decision point is invisible on the device again"

echo "── T5 seed outcomes are readable off the phone ──"
t5 "$SEED" && pass "every seed outcome and migration decision lands in Download/'s log" \
    || fail "an incomplete seed pass is invisible without reading the filesystem by hand"

echo "── T6 no secret can reach the logger ──"
t6 "$SRC_MAIN" && pass "no token/cookie/session/authorization value and no raw url at any call site" \
    || fail "a credential could land in a world-readable Download/ file"

echo "── T7 one file, many lines: cached row + .txt-aware re-query + append mode ──"
t7 "$LOGGER" && pass "one insert ever, cached Uri, .txt-aware match, 'wa' stream, All-Files-Access path append" \
    || fail "the log can shatter into one-line ' (N)' files again (measured: 13 of them)"

# ══ MUT every check above goes RED when its property is broken ══════════════
MUT="$(mktemp -d)"
trap 'rm -rf "$MUT"' EXIT
MUTATIONS=0; HOLLOW=0
W="$MUT/w"

_stage() {
    rm -rf "$W"; mkdir -p "$W/sync"
    cp "$BJ" "$W/drive.json"; cp "$GRADLE" "$W/build.gradle"
    cp "$LOGGER" "$W/DriveDebugLog.kt"; cp "$SEED" "$W/StoreSeed.kt"
    cp "$COORD" "$W/sync/GitSyncCoordinator.kt"
}

# _sub <file> <old> <new> : an EXACT replacement that MUST actually apply
_sub() {
    python3 - "$1" "$2" "$3" <<'PYTHON'
import sys
p, old, new = sys.argv[1:4]
src = open(p, encoding="utf-8").read()
if old not in src:
    sys.stderr.write("MUTATION DID NOT APPLY: %r absent from %s\n" % (old, p)); sys.exit(2)
open(p, "w", encoding="utf-8").write(src.replace(old, new, 1))
PYTHON
}

_json() {
    python3 - "$1" "$2" <<'PYTHON'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
exec(sys.argv[2])
json.dump(d, open(sys.argv[1], "w", encoding="utf-8"), indent=2)
PYTHON
}

_green() {
    local label="$1"; shift
    "$@" >/dev/null 2>&1 && return 0
    echo "  MUT-VOID    $label — the UNMUTATED copy already fails, so any red below is meaningless"
    HOLLOW=$((HOLLOW + 1)); return 1
}
_red() {
    local label="$1"; shift
    MUTATIONS=$((MUTATIONS + 1))
    if "$@" >/dev/null 2>&1; then
        echo "  MUT-HOLLOW  $label — mutated and STILL PASSES: that check proves nothing"
        HOLLOW=$((HOLLOW + 1))
    else
        echo "  MUT-RED     $label"
    fi
}

echo "── MUT each property, broken on a copy, must turn its own check red ──"

_stage && _green "t1" t1 "$W/drive.json" "$W/build.gradle" "$W/DriveDebugLog.kt" && {
    _json "$W/drive.json" 'd["diagnostics"].pop("debug_log_dir")'
    _red "T1 the declaration dropped — nowhere to write" t1 "$W/drive.json" "$W/build.gradle" "$W/DriveDebugLog.kt"; }
_stage && _green "t1" t1 "$W/drive.json" "$W/build.gradle" "$W/DriveDebugLog.kt" && {
    # target a CODE occurrence: the first BuildConfig.DEBUG_LOG_DIR in the file sits in
    # the doc comment, which _code strips — mutating it would prove nothing.
    _sub "$W/DriveDebugLog.kt" '"${Environment.DIRECTORY_DOWNLOADS}/${BuildConfig.DEBUG_LOG_DIR}"' '"${Environment.DIRECTORY_DOWNLOADS}/" + "drive-debug"'
    _sub "$W/DriveDebugLog.kt" 'Environment.DIRECTORY_DOWNLOADS), BuildConfig.DEBUG_LOG_DIR)' 'Environment.DIRECTORY_DOWNLOADS), "drive-debug")'
    _red "T1 the folder hardcoded in Kotlin — a second copy of the name" t1 "$W/drive.json" "$W/build.gradle" "$W/DriveDebugLog.kt"; }
_stage && _green "t2" t2 "$W/DriveDebugLog.kt" && {
    _sub "$W/DriveDebugLog.kt" 'fmt.timeZone = TimeZone.getTimeZone("UTC")' ''
    _red "T2 local-time stamps again — the misread-as-stale shape" t2 "$W/DriveDebugLog.kt"; }
_stage && _green "t2" t2 "$W/DriveDebugLog.kt" && {
    _sub "$W/DriveDebugLog.kt" "HH:mm:ss.SSS'Z'" "HH:mm:ss.SSS"
    _red "T2 the offset dropped from the stamp" t2 "$W/DriveDebugLog.kt"; }
_stage && _green "t3" t3 "$W/DriveDebugLog.kt" && {
    _sub "$W/DriveDebugLog.kt" 'MAX_BYTES = 256 * 1024L' 'MAX_BYTES = Long.MAX_VALUE'
    _red "T3 the cap removed — unbounded growth" t3 "$W/DriveDebugLog.kt"; }
_stage && _green "t3" t3 "$W/DriveDebugLog.kt" && {
    _sub "$W/DriveDebugLog.kt" 'catch (ignored: Throwable)' 'catch (ignored: OutOfMemoryError)'
    _red "T3 a logging failure can crash the host app" t3 "$W/DriveDebugLog.kt"; }
_stage && _green "t4" t4 "$W/sync/GitSyncCoordinator.kt" && {
    _sub "$W/sync/GitSyncCoordinator.kt" 'else DriveDebugLog.e(ctx, TAG, "clone $name FAILED:' '// else DriveDebugLog.e(ctx, TAG, "clone $name FAILED:'
    _red "T4 a failed clone silent again — the multi-day invisibility" t4 "$W/sync/GitSyncCoordinator.kt"; }
_stage && _green "t4" t4 "$W/sync/GitSyncCoordinator.kt" && {
    _sub "$W/sync/GitSyncCoordinator.kt" 'if (result.ok) DriveDebugLog.i(ctx, TAG, "clone $name:' 'if (false) DriveDebugLog.x(ctx, TAG, "clone $name:'
    _red "T4 a successful clone silent — half a story reads as no story" t4 "$W/sync/GitSyncCoordinator.kt"; }
_stage && _green "t4" t4 "$W/sync/GitSyncCoordinator.kt" && {
    _sub "$W/sync/GitSyncCoordinator.kt" '"clone start: host=${hostOf(url)} dest=' '"clone started dest='
    _red "T4 the clone start dropped its host+dest" t4 "$W/sync/GitSyncCoordinator.kt"; }
_stage && _green "t5" t5 "$W/StoreSeed.kt" && {
    _sub "$W/StoreSeed.kt" 'report.lines().forEach { DriveDebugLog.i(applicationContext, TAG, it) }' 'report.lines().forEach { }'
    _red "T5 seed outcomes back to logcat-only" t5 "$W/StoreSeed.kt"; }
_stage && _green "t6" t6 "$W" && {
    _sub "$W/sync/GitSyncCoordinator.kt" 'clone start: host=${hostOf(url)}' 'clone start: url=$url token=$token host=${hostOf(url)}'
    _red "T6 a token interpolated at a call site is caught" t6 "$W"; }
_stage && _green "t6" t6 "$W" && {
    _sub "$W/sync/GitSyncCoordinator.kt" 'host=${hostOf(url)} dest=${dir.name} auth=$authKind' 'url=$url dest=${dir.name} auth=$authKind'
    _red "T6 the raw url (userinfo-capable) at a call site is caught" t6 "$W"; }
_stage && _green "t7" t7 "$W/DriveDebugLog.kt" && {
    _sub "$W/DriveDebugLog.kt" 'cachedRow = target' 'Unit'
    _red "T7 the inserted row forgotten — every line re-resolves" t7 "$W/DriveDebugLog.kt"; }
_stage && _green "t7" t7 "$W/DriveDebugLog.kt" && {
    _sub "$W/DriveDebugLog.kt" 'arrayOf(name, "$name.txt", "$relativePath/")' 'arrayOf(name, name, "$relativePath/")'
    _red "T7 the re-query blind to MediaStore's .txt rename — the measured 13-file storm" t7 "$W/DriveDebugLog.kt"; }
_stage && _green "t7" t7 "$W/DriveDebugLog.kt" && {
    _sub "$W/DriveDebugLog.kt" 'openOutputStream(target, "wa")' 'openOutputStream(target, "w")'
    _red "T7 truncate-on-write — one line per file by another road" t7 "$W/DriveDebugLog.kt"; }
_stage && _green "t7" t7 "$W/DriveDebugLog.kt" && {
    _sub "$W/DriveDebugLog.kt" 'Environment.isExternalStorageManager()' 'false'
    _red "T7 the All-Files-Access plain append dropped" t7 "$W/DriveDebugLog.kt"; }
_stage && _green "t7" t7 "$W/DriveDebugLog.kt" && {
    _sub "$W/DriveDebugLog.kt" 'val target = uri ?: cr.insert(' 'val target = cr.insert('
    _red "T7 an unconditional insert — a fresh row per write even with a cache" t7 "$W/DriveDebugLog.kt"; }

echo
if [ "$FAILURES" -eq 0 ] && [ "$HOLLOW" -eq 0 ]; then
    echo "ALL GREEN — $MUTATIONS mutations, every one red"
    exit 0
else
    echo "RED — $FAILURES failing check(s), $HOLLOW hollow/void mutation(s)"
    exit 1
fi
