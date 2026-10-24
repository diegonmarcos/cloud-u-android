#!/usr/bin/env bash
# Tester: Cloud > Apps: the Account tile lives in the Me group, immediately before the Me tile,
# and is gone from Tools Primary (array position is render position). Static only.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"; BJ="$APP/build.json"; PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
eq()  { [ "$2" = "$3" ] && ok "$1 ($2)" || bad "$1: expected '$3', got '$2'"; }
command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 2; }
G='.ui.sections[] | select(.id == "cloud") | .tile_groups[]'
eq "Me row starts Account, Me" "$(jq -r "$G | select(.title == \"Me\") | [.tiles[0:2][].label] | join(\",\")" "$BJ")" "Account,Me"
eq "Account tile opens the profile page" "$(jq -r "$G | select(.title == \"Me\") | .tiles[0].target" "$BJ")" "page:config/profile"
eq "Tools Primary has no Account tile" "$(jq -r "$G | select(.title == \"Tools Primary\") | [.tiles[] | select(.label == \"Account\")] | length" "$BJ")" "0"
eq "Tools Primary still ends with Light" "$(jq -r "$G | select(.title == \"Tools Primary\") | .tiles[-1].label" "$BJ")" "Light"
echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
