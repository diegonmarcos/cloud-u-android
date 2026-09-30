#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ cloud-drive — the built git sha is readable ON THE DEVICE                ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# A released APK carried no git provenance a human could read: BuildConfig had
# an 8-char short sha only, and Cloud-Drive.apk.source on the release shelf is
# a content digest (64 hex), not a commit. So "which commit is this build?"
# could not be answered from the phone — not by the owner, not by a cache
# comparison. What this pins:
#
#   T1  THE GRADLE BAKES THE FULL SHA: a 40-hex BuildConfig.GIT_SHA, taken from
#       GITHUB_SHA first (Actions checks out shallow/detached; the env is the
#       one value the runner guarantees) with `git rev-parse HEAD` as the local
#       fallback, and the build FAILS on anything that is not 40 hex rather
#       than baking "". The short sha is DERIVED from it, not resolved twice.
#   T2  THE ABOUT SURFACE READS BuildConfig.GIT_SHA — never a literal — shows the
#       short sha, and the tap copies the FULL sha through the app's one
#       clipboard verb (DriveActions.copyText). The label is a string
#       resource, not Kotlin prose.
#   MUT mutation-proof: each property, broken on a copy, turns its check red.
#
# OWN-SOURCE ONLY. python3 and grep only, no network, no build.
# grep -c, never grep -q, after a pipe: under pipefail -q's early exit SIGPIPEs
# the upstream stage and a MATCH reads as a failed pipeline.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
GRADLE="$APP/app/build.gradle"
ABOUT="$APP/app/src/main/java/com/diegonmarcos/clouddrive/configs/OthersPage.kt"
STRINGS="$APP/app/src/main/res/values/strings.xml"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

# a file's CODE, comment lines stripped, so prose about a defect never reads as one
_code() { grep -vE '^[[:space:]]*(\*|//|/\*)' "$1"; }

for required in "$GRADLE" "$ABOUT" "$STRINGS"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

# t1 <build.gradle> : full sha baked, env-first with git fallback, 40-hex guard, short derived
t1() {
    local gradle="$1" bad=0
    [ "$(_code "$gradle" | grep -cE "System\.getenv\('GITHUB_SHA'\)")" -ge 1 ] \
        || { echo "    GITHUB_SHA is not consulted — a shallow/detached Actions checkout has no other truth"; bad=1; }
    [ "$(_code "$gradle" | grep -cE "commandLine 'git', 'rev-parse', 'HEAD'")" -ge 1 ] \
        || { echo "    no full-sha git fallback (rev-parse HEAD, not --short)"; bad=1; }
    [ "$(_code "$gradle" | grep -cE '\[0-9a-f\]\{40\}')" -ge 1 ] \
        || { echo "    the sha is not guarded to 40 hex — a build could bake an empty or short value"; bad=1; }
    [ "$(_code "$gradle" | grep -cE 'buildConfigField "String", "GIT_SHA",[[:space:]]+"\\"\$\{fullSha\}\\""')" -ge 1 ] \
        || { echo "    BuildConfig.GIT_SHA is not baked from the full sha"; bad=1; }
    [ "$(_code "$gradle" | grep -cE 'def shortSha = fullSha\.take\(8\)')" -ge 1 ] \
        || { echo "    the short sha is not derived from the full one — two resolutions can disagree"; bad=1; }
    return $bad
}

# t2 <OthersPage.kt> <strings.xml> : About reads BuildConfig, no literal, copies the full sha, declared label
t2() {
    local about="$1" strings="$2" bad=0
    [ "$(_code "$about" | grep -cE 'BuildConfig\.GIT_SHORT_SHA')" -ge 1 ] \
        || { echo "    the About surface does not show the short sha"; bad=1; }
    [ "$(_code "$about" | grep -cE 'copyText\(BuildConfig\.GIT_SHA\)')" -ge 1 ] \
        || { echo "    the tap does not copy BuildConfig.GIT_SHA through DriveActions.copyText"; bad=1; }
    local lit
    lit="$(_code "$about" | grep -nE '"[0-9a-f]{7,40}"' || true)"
    [ -z "$lit" ] || { echo "    a sha LITERAL sits in the About surface:"; printf '%s\n' "$lit" | sed 's/^/        /'; bad=1; }
    [ "$(_code "$about" | grep -cE 'stringResource\(R\.string\.configs_commit\)')" -ge 1 ] \
        || { echo "    the row's label is not the declared string resource"; bad=1; }
    [ "$(grep -cE '<string name="configs_commit">' "$strings")" -eq 1 ] \
        || { echo "    strings.xml does not declare configs_commit exactly once"; bad=1; }
    return $bad
}

echo "── T1 the gradle bakes a full 40-hex sha, env-first, guarded ──"
t1 "$GRADLE" && pass "GITHUB_SHA ?: git rev-parse HEAD → 40-hex guard → BuildConfig.GIT_SHA; short derived" \
    || fail "the APK could ship without a readable commit"

echo "── T2 About shows the short sha and copies the full one, from BuildConfig ──"
t2 "$ABOUT" "$STRINGS" && pass "short sha shown, tap copies BuildConfig.GIT_SHA, label declared, no literal" \
    || fail "the provenance line is missing, hardcoded, or not copyable"

# ══ MUT every check above goes RED when its property is broken ══════════════
MUT="$(mktemp -d)"
trap 'rm -rf "$MUT"' EXIT
MUTATIONS=0; HOLLOW=0
W="$MUT/w"

_stage() { rm -rf "$W"; mkdir -p "$W"; cp "$GRADLE" "$W/build.gradle"; cp "$ABOUT" "$W/OthersPage.kt"; cp "$STRINGS" "$W/strings.xml"; }

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

_stage && _green "t1" t1 "$W/build.gradle" && {
    _sub "$W/build.gradle" "System.getenv('GITHUB_SHA')" "null"
    _red "T1 the env fallback dropped — a detached Actions checkout bakes garbage" t1 "$W/build.gradle"; }
_stage && _green "t1" t1 "$W/build.gradle" && {
    _sub "$W/build.gradle" "commandLine 'git', 'rev-parse', 'HEAD'" "commandLine 'git', 'rev-parse', '--short=8', 'HEAD'"
    _red "T1 the git fallback back to a short sha" t1 "$W/build.gradle"; }
_stage && _green "t1" t1 "$W/build.gradle" && {
    _sub "$W/build.gradle" '[0-9a-f]{40}' '[0-9a-f]*'
    _red "T1 the 40-hex guard loosened — an empty sha bakes fine" t1 "$W/build.gradle"; }
_stage && _green "t1" t1 "$W/build.gradle" && {
    _sub "$W/build.gradle" 'buildConfigField "String", "GIT_SHA",' '// buildConfigField "String", "GIT_SHA",'
    _red "T1 GIT_SHA no longer baked" t1 "$W/build.gradle"; }
_stage && _green "t1" t1 "$W/build.gradle" && {
    _sub "$W/build.gradle" 'def shortSha = fullSha.take(8)' "def shortSha = System.getenv('GITHUB_SHA').take(8)"
    _red "T1 the short sha resolved a second time" t1 "$W/build.gradle"; }
_stage && _green "t2" t2 "$W/OthersPage.kt" "$W/strings.xml" && {
    _sub "$W/OthersPage.kt" 'copyText(BuildConfig.GIT_SHA)' 'copyText(BuildConfig.GIT_SHORT_SHA)'
    _red "T2 the tap copies the short sha — the full one never leaves the APK" t2 "$W/OthersPage.kt" "$W/strings.xml"; }
_stage && _green "t2" t2 "$W/OthersPage.kt" "$W/strings.xml" && {
    _sub "$W/OthersPage.kt" 'copyText(BuildConfig.GIT_SHA)' 'copyText("0123456789abcdef0123456789abcdef01234567")'
    _red "T2 a sha literal in the About surface" t2 "$W/OthersPage.kt" "$W/strings.xml"; }
_stage && _green "t2" t2 "$W/OthersPage.kt" "$W/strings.xml" && {
    _sub "$W/OthersPage.kt" 'stringResource(R.string.configs_commit)' '"Commit"'
    _red "T2 the label typed into Kotlin instead of strings.xml" t2 "$W/OthersPage.kt" "$W/strings.xml"; }
_stage && _green "t2" t2 "$W/OthersPage.kt" "$W/strings.xml" && {
    _sub "$W/strings.xml" '<string name="configs_commit">' '<string name="configs_commit_x">'
    _red "T2 the string resource gone" t2 "$W/OthersPage.kt" "$W/strings.xml"; }

echo
if [ "$FAILURES" -eq 0 ] && [ "$HOLLOW" -eq 0 ]; then
    echo "ALL GREEN — $MUTATIONS mutations, every one red"
    exit 0
else
    echo "RED — $FAILURES failing check(s), $HOLLOW hollow/void mutation(s)"
    exit 1
fi
