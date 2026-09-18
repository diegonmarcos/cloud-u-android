#!/usr/bin/env bash
# ac_cloud-affine tester: the tree is licence-clean.
# Runs WITHOUT a build (no node, no cargo, no ANDROID_HOME — needs only the
# tools build.json::tests.shell.requires declares: python3 + git).
#
# Proves, from the committed tree:
#  1. the EE directories are physically absent (the licence-boundary guard's
#     `restricted_present` check, restated here in words that cannot be read
#     as "we scanned and found it clean");
#  2. no workspace-member Cargo manifest or the lockfile names the EE Rust
#     crate `affine_common` — the Cargo-level reach the 2026-09-09 scoping
#     missed because a JS audit cannot see it;
#  3. the Cargo-level licence guard returns 0 on this tree, and returns
#     NONZERO against a copy where an EE directory is present (mutation).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$ROOT/.." && pwd)"
GUARD="$REPO/1_cicd/src/scripts/cloud-android-licence-boundary-guard.py"
[ -f "$GUARD" ] || GUARD="$REPO/1_cicd/dist/scripts/cloud-android-licence-boundary-guard.py"
FAIL=0

ok()   { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAIL=1; }

# 1. EE directories physically absent
for d in packages/backend packages/common/native; do
  if [ -d "$ROOT/$d" ]; then fail "EE dir present: $ROOT/$d"; else ok "EE dir absent: $d"; fi
done

# 2. no affine_common in the membership-relevant Cargo manifests or lockfile
hits="$(grep -rn "affine_common" "$ROOT/Cargo.lock" "$ROOT/packages/frontend/mobile-native/Cargo.toml" "$ROOT/packages/frontend/native/nbstore/Cargo.toml" 2>/dev/null || true)"
if [ -z "$hits" ]; then
  ok "no affine_common in Cargo.lock or the APK-crate manifests"
else
  fail "affine_common reach found in Cargo:\n$hits"
fi

# 3a. licence guard is green on the committed tree
if python3 "$GUARD" "$ROOT" affine >/dev/null 2>&1; then
  ok "licence guard green on this tree"
else
  fail "licence guard NOT green on this tree"
fi

# 3b. mutation — a copy with an EE-shaped directory present must go RED.
# This proves the guard can actually fail the boundary, instead of being a
# constant green. The committed mutation evidence lives in
# ac_cloud-affine/licence-proof/mutation-red.txt (restored with the real EE
# tree at commit time); here we re-derive the same verdict cheaply with an
# empty dir of the exact prohibited shape (the guard's restricted_present
# branch needs only the path to exist to refuse).
M="${TMPDIR:-/tmp}/affine-mutation-$$"
rm -rf "$M"; mkdir -p "$M/packages/common/native"
if python3 "$GUARD" "$M" affine >/dev/null 2>&1; then
  fail "licence guard GREEN on a tree WITH packages/common/native — the boundary is a no-op"
else
  ok "licence guard goes RED when packages/common/native is present (mutation)"
fi
rm -rf "$M"

if [ "$FAIL" -eq 0 ]; then
  printf '  cloud-affine licence-clean tester: ALL PASS\n'
else
  printf '  cloud-affine licence-clean tester: FAIL\n' >&2
  exit 1
fi