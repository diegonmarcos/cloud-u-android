#!/bin/sh
# Phase 0 (cloud-notes-hybrid) storage round trip, run in CI by the
# `storage_tests` job of ship-cloud-notes.yml. NOT named test-*.sh on purpose:
# the shell phase of cloud-android-test-engine.sh runs those BEFORE any
# toolchain is provisioned, and these two need node + rust.
#
#   rust: doc written through the pool API, pool dropped, new pool on the same
#         .db file, bytes identical (mobile-native/src/tests.rs)
#   js:   NbStoreNativeDBApis adapter round trip over a persisting fake plugin
#         (apps/android/src/plugins/nbstore/index.spec.ts)
#
# Each leg must PROVE it ran: a filter that matches nothing exits 0, and a
# test run that executed nothing is the lie this repo's test engine exists to
# refuse. So both legs grep their own output for the named tests passing.
set -eu
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
VITEST=4.1.10
RUST_TEST=doc_persists_across_reopen_byte_identical

echo "== rust: affine_mobile_native =="
cargo test -p affine_mobile_native --lib -- --nocapture 2>&1 | tee /tmp/phase0-rust.log
grep -q "test tests::$RUST_TEST ... ok" /tmp/phase0-rust.log || {
  echo "::error::Rust test $RUST_TEST did not run and pass"; exit 1; }

echo "== js: nbstore adapter spec =="
APP=packages/frontend/apps/android
TOOLS="$(mktemp -d)"
( cd "$TOOLS" && npm init -y >/dev/null && npm i --no-audit --no-fund --no-package-lock --legacy-peer-deps "vitest@$VITEST" >/dev/null )
ln -s "$TOOLS/node_modules" "$ROOT/$APP/node_modules"
( cd "$ROOT/$APP" && NO_COLOR=1 "$TOOLS/node_modules/.bin/vitest" run --root . --config vitest.phase0.config.mts ) 2>&1 | tee /tmp/phase0-js.log
rm -f "$ROOT/$APP/node_modules"
# vitest colours its summary on a CI tty: strip the escapes before matching
sed 's/\x1b\[[0-9;]*m//g' /tmp/phase0-js.log | grep -Eq "Tests +3 passed \(3\)" || {
  echo "::error::nbstore adapter spec did not run 3 tests green"; exit 1; }
echo "PHASE0 STORAGE OK"
