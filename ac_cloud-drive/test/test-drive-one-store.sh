#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #676 — ONE canonical git store on the phone: no source file anywhere in ║
# ║ this repo roots a git tree outside <shared_root>/<git_subdir>           ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. The phone was measured (2026-09-30) carrying a GENUINE
# second repository tree at /storage/emulated/0/mounts/git (#599's cloud-code
# root: My-ai-memory, notes, vault) beside the declared store at
# /storage/emulated/0/CloudDrive/git (#575/#606: storage.shared_root +
# storage.git_subdir in this app's build.json). Everything compiles either
# way; only a scan can see that some source quietly re-roots a tree at a
# second path. test-drive-shared-store.sh pins cloud-drive's OWN sources;
# this file pins the WHOLE repo: the second-store path literals must never
# come back, in any app.
#
# The forbidden roots (each regex spelled with a bracketed slash so this
# file's own text never matches its own scan):
#   mounts[/]git        — the #599 second tree
#   emulated[/]0[/]git  — the pre-#575 hand-written cloud-code root
#
# The scan covers source files only (code + declarations). Docs, changelogs
# and comment-only lines may DESCRIBE the old paths (test-drive-shared-store.sh
# narrates the pre-#575 bug in its own header); prose roots nothing, so lines
# whose content starts with # or // are excused — a LIVE literal never is.
#
# MUTATION-PROVED: the same scan is run against a copy of a real source file
# with the forbidden literal re-added; the scan must go red there or this
# whole file proves nothing (the fleet's hollow-green rule).
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

FORBIDDEN='mounts[/]git|emulated[/]0[/]git'

# scan <dir> → every hit of a forbidden root in source files under it,
# excluding vendored/generated trees and this tester itself.
scan() {
    grep -rInE "$FORBIDDEN" "$1" \
        --include='*.json' --include='*.py' --include='*.js' --include='*.mjs' \
        --include='*.ts' --include='*.kt' --include='*.java' --include='*.gradle' \
        --include='*.sh' --include='*.xml' --include='*.gitignore' \
        --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=z_archive \
        --exclude-dir=dist --exclude-dir=build --exclude-dir=.gradle \
        2>/dev/null | grep -vF "$SELF" | grep -vE '^[^:]*:[0-9]+:[[:space:]]*(#|//)' || true
}

echo "── #676 no second git-store root in any source file ──"
HITS="$(scan "$ROOT")"
if [ -z "$HITS" ]; then
    pass "no source file names mounts/git or emulated/0/git as a path"
else
    fail "a source file roots a git tree outside the declared store:"
    printf '%s\n' "$HITS" | sed 's/^/        /'
fi

echo "── the one root is DECLARED, and cloud-code consumes the declaration ──"
GIT_SUBDIR="$(python3 -c 'import json,sys; print((json.load(open(sys.argv[1])).get("storage") or {}).get("git_subdir") or "")' "$ROOT/ac_cloud-drive/build.json")"
if [ -n "$GIT_SUBDIR" ]; then
    pass "ac_cloud-drive/build.json::storage.git_subdir = '$GIT_SUBDIR' (the one clone folder)"
else
    fail "ac_cloud-drive/build.json declares no storage.git_subdir — there is no canonical root to hold anyone to"
fi
RESOLVER="$ROOT/ac_cloud-code/tools/resolve-targets.py"
if grep -q 'get("git_subdir")' "$RESOLVER" && grep -q '"git_subdir": git_subdir(' "$RESOLVER"; then
    pass "cloud-code's resolver copies git_subdir from the store's declaration (no restated path)"
else
    fail "ac_cloud-code/tools/resolve-targets.py does not read storage.git_subdir — cloud-code roots its tree somewhere it made up (#676)"
fi
if grep -q 'targets.git_subdir' "$ROOT/ac_cloud-code/src/cloud/index.js"; then
    pass "cloud-code composes its git-tree paths through the declared git folder"
else
    fail "ac_cloud-code/src/cloud/index.js never uses targets.git_subdir — Backlog/Repos land outside the store's git folder"
fi

echo "── mutation proof: re-adding the literal goes red ──"
# Build the forbidden literal by concatenation so this file stays clean.
LIT="mounts/""git"
MUT="$(mktemp -d)"
printf '{ "repos_root": "/storage/emulated/0/%s" }\n' "$LIT" > "$MUT/poison.json"
if [ -n "$(scan "$MUT")" ]; then
    pass "mutation proved: a source file naming $LIT turns the scan red"
else
    fail "MUTATION SURVIVED: the scan missed a planted $LIT literal — it proves nothing"
fi
rm -rf "$MUT"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-one-store: all checks passed"; else echo "test-drive-one-store: $FAILURES check(s) FAILED"; exit 1; fi
