#!/usr/bin/env bash
# upstream-name-guard.test.sh — #1091: proves cloud-android-upstream-name-guard.py
# fails on every way the upstream project's name can come back (content, path,
# any letter case, a second mention next to an allowed token), stays green for
# an allowlisted notice, and fails an allowlist entry that covers nothing.
# Runs on a throw-away git repository, never on the real tree. The word is
# assembled here for the same reason it is assembled in the guard.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
guard="$here/cloud-android-upstream-name-guard.py"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
fail=0
W="aff""ine"
UP="$(printf '%s' "$W" | tr '[:lower:]' '[:upper:]')"
CAP="$(printf '%s' "$W" | sed 's/^./\U&/')"
AL="1_cicd/src/data/upstream-name-allowlist.json"

fixture() {  # fixture [allowlist-json]
  rm -rf "$work/r"; mkdir -p "$work/r/1_cicd/src/data" "$work/r/src" "$work/r/docs"; cd "$work/r"
  git init -q . && git config user.email t@t && git config user.name t
  printf 'class Notes\n' > src/a.kt
  printf '# Cloud Notes\n' > docs/readme.md
  printf '%s\n' "${1:-{\"entries\":[]\}}" > "$AL"
  git add -A
}

expect() {  # expect <0|1|2> <label> [needle in output]
  local rc=0 out; out="$(python3 "$guard" . 2>&1)" || rc=$?
  if [ "$rc" != "$1" ]; then echo "FAIL $2 (exit $rc, wanted $1)"; fail=1; return; fi
  if [ -n "${3:-}" ] && ! grep -qF -- "$3" <<<"$out"; then echo "FAIL $2 (output lacks '$3')"; fail=1; return; fi
  echo "ok   $2"
}

fixture; expect 0 "a clean fixture passes"

fixture; printf 'val x = "%s"\n' "$W" >> src/a.kt; git add -A
expect 1 "a lower-case mention in source" "src/a.kt"

fixture; printf 'val x = "%s"\n' "$UP" >> src/a.kt; git add -A
expect 1 "an upper-case mention in source" "src/a.kt"

fixture; printf 'Welcome to %s\n' "$CAP" >> docs/readme.md; git add -A
expect 1 "a capitalised mention in a doc" "docs/readme.md"

fixture; printf 'x\n' > "docs/${W}-notes.md"; git add -A
expect 1 "a file name carrying the name" "file name"

fixture; mkdir -p "src/${W}"; printf 'x\n' > "src/${W}/a.txt"; git add -A
expect 1 "a directory name carrying the name" "src/${W}/a.txt"

# ── allowlist behaviour ──
fixture "{\"entries\":[{\"id\":\"lic\",\"category\":\"licence\",\"reason\":\"notice\",\"paths\":[\"LICENSE\"]}]}"
printf 'the %s software\n' "$UP" > LICENSE; git add -A
expect 0 "a mention in an allowlisted file passes"

fixture "{\"entries\":[{\"id\":\"lic\",\"category\":\"licence\",\"reason\":\"notice\",\"paths\":[\"LICENSE\"]}]}"
printf 'the %s software\n' "$UP" > NOTICE; git add -A
expect 1 "the same mention in a file the allowlist does not name fails" "NOTICE"

fixture "{\"entries\":[{\"id\":\"tok\",\"category\":\"external-identifier\",\"reason\":\"registry\",\"paths\":[\"Cargo.toml\"],\"patterns\":[\"${W}_preview\"]}]}"
printf 'dep = { package = "%s_preview" }\n' "$W" > Cargo.toml; git add -A
expect 0 "a line whose only mention is the allowed token passes"

fixture "{\"entries\":[{\"id\":\"tok\",\"category\":\"external-identifier\",\"reason\":\"registry\",\"paths\":[\"Cargo.toml\"],\"patterns\":[\"${W}_preview\"]}]}"
printf 'dep = { package = "%s_preview" } # %s\n' "$W" "$W" > Cargo.toml; git add -A
expect 1 "a second mention on a line with an allowed token fails" "Cargo.toml"

fixture "{\"entries\":[{\"id\":\"tok\",\"category\":\"external-identifier\",\"reason\":\"registry\",\"paths\":[\"Cargo.toml\"],\"patterns\":[\"${W}_preview\"]}]}"
expect 1 "an allowlist entry that covers nothing fails" "tok"

fixture "{\"entries\":[{\"id\":\"deep\",\"category\":\"licence\",\"reason\":\"proof\",\"paths\":[\"proof/**\"]}]}"
mkdir -p proof/a/b; printf '%s\n' "$W" > proof/a/b/c.txt; git add -A
expect 0 "a ** glob covers nested files"

fixture "{\"entries\":[{\"id\":\"noreason\",\"category\":\"licence\",\"paths\":[\"x\"]}]}"
expect 2 "an entry without a reason is a configuration error"

fixture "{\"entries\":[{\"id\":\"p\",\"category\":\"x\",\"reason\":\"r\",\"paths\":[\"x\"],\"patterns\":[\"(\"]}]}"
expect 2 "an invalid pattern is a configuration error"

[ "$fail" = 0 ] && echo "PASS" || { echo "FAILED"; exit 1; }
