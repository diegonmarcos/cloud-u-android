#!/usr/bin/env bash
# commit-msg-guard.test — prove the #670 guard goes red on every shape it
# claims to catch, stays green on house-style prose, and scans EVERY commit
# in a range (not only the tip). Works in a throwaway repo, never this tree.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-commit-msg-guard.py"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

expect() { # expect <0|1> <label> <message>
  printf '%s' "$3" > "$WORK/msg"
  python3 "$GUARD" --file "$WORK/msg" >/dev/null 2>&1; rc=$?
  [ "$rc" = "$1" ] && ok "$2 (exit $rc)" || fail "$2: expected exit $1, got $rc"
}

CLEAN=$'#1 cloud-x: the list scales from one density token\n\nWHY THIS EXISTS\nThe owner asked for denser rows; `MAX_ROWS` now drives it.\n\nCo-Authored-By: A <a@b.c>\n'
expect 0 "clean brief with capitals heading, code span and trailer" "$CLEAN"
expect 0 "indented tool output quote is not a blockquote" $'#2 fix\n\n  > For input string: "X" under radix 8\n'
expect 1 "profanity"                "$CLEAN"$'\nthis is a sh''it job\n'
expect 1 "profanity, mixed case"    "$CLEAN"$'\nWHAT A SH''IT JOB\n'
expect 1 "triple bang"              "$CLEAN"$'\ndo it now!!!\n'
expect 1 "triple question"          "$CLEAN"$'\nwhy apps???\n'
expect 1 "shouting run ending in !" "$CLEAN"$'\nI NEVER SAID LINK WITH IT!\n'
expect 1 "shouting run in quotes"   "$CLEAN"$'\nhe wrote "YOU WILL NEVER PUBLISH THIS"\n'
expect 1 "blockquote line"          "$CLEAN"$'\n> make it smaller\n'
expect 1 "verbatim: then quote"     "$CLEAN"$'\nThe ask, verbatim: "make it smaller"\n'
expect 1 "verbatim: then curly"     "$CLEAN"$'\n'"verbatim: “make it”"$'\n'

# Range mode scans every commit: a bad message buried under a clean tip.
G="$WORK/repo"; git init -q "$G"
gc() { git -C "$G" -c user.name=t -c user.email=t@t commit -q --allow-empty -m "$1"; }
gc "base"; base=$(git -C "$G" rev-parse HEAD)
gc "#3 bad one!!!"; gc "#4 clean tip"
( cd "$G" && python3 "$GUARD" --range "$base..HEAD" >/dev/null ); rc=$?
[ "$rc" = 1 ] && ok "range: bad commit under a clean tip is caught" || fail "range: buried bad commit not caught (exit $rc)"
( cd "$G" && python3 "$GUARD" --range "HEAD~1..HEAD" >/dev/null ); rc=$?
[ "$rc" = 0 ] && ok "range: clean-only range passes" || fail "range: clean range failed (exit $rc)"

[ "$FAILURES" = 0 ] && echo "commit-msg-guard.test: all cases pass" || { echo "commit-msg-guard.test: $FAILURES failure(s)"; exit 1; }
