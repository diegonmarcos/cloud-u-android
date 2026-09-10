#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ licence-boundary-guard.test — prove the guard FAILS, and prove   ║
# ║ it can pass, so a green result means something                   ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. A guard only ever watched succeeding is indistinguishable
# from `exit 0`. This repository shipped an assertion that compared an
# expression to itself and printed green, and four more that passed because a
# tool was missing from the runner.
#
# The guard's first draft was itself an example: it scanned for the marker
# 'packages/backend' and reported 2 of the 3 real reaches, because the Gradle
# one is written as a RELATIVE path with no 'packages/' prefix. It printed a
# confident, wrong, partial result. Case 1 below is that bug, frozen.
#
# The fixture reproduces the three reaching lines VERBATIM from AFFiNE at the
# revision pinned in licence-boundaries.json, rather than cloning 258 MB of
# upstream: CI has to be able to run this, and a test that needs the network
# is a test that goes red on someone else's outage.
#
# No ripgrep: it is not on the CI runner.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-licence-boundary-guard.py"
POLICY="$ROOT/1_cicd/src/data/licence-boundaries.json"
FAILURES=0

ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

# Plain substring containment. NOT `case "$s" in *"$needle"*)` — inside a case
# pattern, [NEW] is a bracket expression matching one of N/E/W, so the obvious
# spelling of "does the output say [NEW]" silently asserts something else. That
# bug was in this file's first draft and case 3 failed against a guard that was
# behaving correctly.
contains() { [ "${1#*"$2"}" != "$1" ]; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ── fixture: the two scan roots, carrying the three real reaches ──────────
build_fixture() {
  local t="$1"
  mkdir -p "$t/packages/frontend/apps/android/App/service" \
           "$t/packages/frontend/mobile-native/src"
  # Verbatim from packages/frontend/apps/android/App/service/build.gradle:16.
  # The path is RELATIVE — this is the line the first guard draft missed.
  cat > "$t/packages/frontend/apps/android/App/service/build.gradle" <<'EOF'
apollo {
    service("affine") {
        srcDir("../../../../../common/graphql/src/graphql")
        schemaFiles.from("../../../../../backend/server/src/schema.gql")
    }
}
EOF
  # Verbatim from packages/frontend/mobile-native/Cargo.toml:20.
  cat > "$t/packages/frontend/mobile-native/Cargo.toml" <<'EOF'
[dependencies]
affine_common  = { workspace = true, features = ["hashcash"] }
affine_nbstore = { workspace = true }
EOF
  # Verbatim from packages/frontend/mobile-native/src/lib.rs:13.
  cat > "$t/packages/frontend/mobile-native/src/lib.rs" <<'EOF'
use affine_common::hashcash::Stamp;

pub fn hashcash_mint(resource: String, bits: u32) -> String {
  Stamp::mint(resource, Some(bits)).format()
}
EOF
}

run_guard() { python3 "$GUARD" "$1" affine "$POLICY" 2>&1; }

# ── case 1: pristine upstream shape → FAIL, naming all THREE reaches ──────
# The regression that matters: a guard reporting 2 of 3 looks like a pass to
# anyone skimming, and 'de-clouding makes it MIT' is exactly the wrong lesson
# to draw from a partial list.
T="$WORK/pristine"; build_fixture "$T"
OUT="$(run_guard "$T")"; RC=$?
if [ "$RC" -ne 1 ]; then
  fail "pristine upstream: expected exit 1, got $RC"
else
  n=0
  for f in "App/service/build.gradle" "mobile-native/Cargo.toml" "mobile-native/src/lib.rs"; do
    if contains "$OUT" "$f"; then n=$((n + 1)); else fail "pristine: guard did not name $f"; fi
  done
  [ "$n" -eq 3 ] && ok "pristine upstream: exit 1, all 3 EE reaches named (gradle + cargo + rust)"
fi

# ── case 2: a fully de-clouded tree → PASS ────────────────────────────────
# Without this the guard could be `exit 1` and every other case would agree
# with it. This is the case that makes a green result mean something.
T="$WORK/declouded"; build_fixture "$T"
rm -rf "$T/packages/frontend/apps/android/App/service"
mkdir -p "$T/packages/frontend/apps/android/App/app"
: > "$T/packages/frontend/apps/android/App/app/build.gradle"
cat > "$T/packages/frontend/mobile-native/Cargo.toml" <<'EOF'
[dependencies]
affine_nbstore = { workspace = true }
EOF
cat > "$T/packages/frontend/mobile-native/src/lib.rs" <<'EOF'
pub fn placeholder() {}
EOF
OUT="$(run_guard "$T")"; RC=$?
if [ "$RC" -ne 0 ]; then
  fail "de-clouded tree: expected exit 0, got $RC — guard cannot go green: $OUT"
else
  ok "de-clouded tree: exit 0 (guard is not a constant failure)"
fi

# ── case 3: a NEW reach an upstream bump could introduce → FAIL, tagged NEW ─
# known_reaches is a to-do list, not an allowlist. A reach in a file nobody
# scoped must be louder than one already written down.
T="$WORK/bumped"; build_fixture "$T"
cat > "$T/packages/frontend/mobile-native/src/newfeature.rs" <<'EOF'
use affine_common::crypto::Key;
EOF
OUT="$(run_guard "$T")"; RC=$?
if [ "$RC" -ne 1 ]; then
  fail "new reach: expected exit 1, got $RC"
elif ! contains "$OUT" "[NEW]"; then
  fail "new reach: guard did not tag the unscoped file [NEW]: $OUT"
elif ! contains "$OUT" "newfeature.rs"; then
  fail "new reach: guard did not name newfeature.rs"
else
  ok "new reach: exit 1 and tagged [NEW] (upstream-bump alarm works)"
fi

# ── case 4: a scan root missing → refuse to report clean ──────────────────
# The false-green shape this fleet keeps hitting. An empty or wrong checkout
# has no reaches to find, and 'found nothing' must not read as 'is clean'.
T="$WORK/truncated"; build_fixture "$T"
rm -rf "$T/packages/frontend/mobile-native"
OUT="$(run_guard "$T")"; RC=$?
if [ "$RC" -eq 0 ]; then
  fail "missing scan root: guard reported CLEAN on an incomplete tree (false green)"
elif [ "$RC" -ne 2 ]; then
  fail "missing scan root: expected exit 2, got $RC"
else
  ok "missing scan root: exit 2, refuses to report clean"
fi

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "PASS   4/4 cases"; exit 0
else
  echo "FAIL   $FAILURES case(s)"; exit 1
fi
