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
# The fixture reproduces the reaching lines VERBATIM from AFFiNE at the
# revision pinned in licence-boundaries.json, rather than cloning 258 MB of
# upstream: CI has to be able to run this, and a test that needs the network
# is a test that goes red on someone else's outage.
#
# No ripgrep, and nothing else outside coreutils either. Four testers in this
# repository once passed only because `rg` was absent and the call failed open;
# the guard and this file are python3 and bash and nothing more, so there is no
# missing tool for a green result to be hiding behind.

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

# ── fixture: the three scan roots, carrying the four real reaches ─────────
build_fixture() {
  local t="$1"
  mkdir -p "$t/packages/frontend/apps/android/App/service" \
           "$t/packages/frontend/mobile-native/src" \
           "$t/packages/frontend/native/nbstore/src"
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
  # Verbatim from packages/frontend/native/nbstore/Cargo.toml:16 and
  # src/lib.rs:12. This is the crate affine_mobile_native names as
  # affine_nbstore, and it takes affine_common as a PLAIN dependency — so the
  # APK links EE code even with hashcash gone. It lives under neither of the
  # two scan roots the guard shipped with, which is why it was missed.
  cat > "$t/packages/frontend/native/nbstore/Cargo.toml" <<'EOF'
[package]
name = "affine_nbstore"

[dependencies]
affine_common = { workspace = true, features = ["napi"] }
EOF
  cat > "$t/packages/frontend/native/nbstore/src/lib.rs" <<'EOF'
use affine_common::napi_utils::to_napi_error;
EOF
}

# Strip the reaches a de-cloud removes: the :service gradle module and the
# hashcash mint. Everything this leaves behind is a reach de-clouding does NOT
# remove, which is the whole finding.
declould_frontend() {
  local t="$1"
  rm -rf "$t/packages/frontend/apps/android/App/service"
  mkdir -p "$t/packages/frontend/apps/android/App/app"
  : > "$t/packages/frontend/apps/android/App/app/build.gradle"
  cat > "$t/packages/frontend/mobile-native/Cargo.toml" <<'EOF'
[dependencies]
affine_nbstore = { workspace = true }
EOF
  cat > "$t/packages/frontend/mobile-native/src/lib.rs" <<'EOF'
pub fn placeholder() {}
EOF
}

# The rest of severance: the storage crate stops naming affine_common too.
sever_nbstore() {
  local t="$1"
  cat > "$t/packages/frontend/native/nbstore/Cargo.toml" <<'EOF'
[package]
name = "affine_nbstore"

[dependencies]
anyhow = { workspace = true }
EOF
  : > "$t/packages/frontend/native/nbstore/src/lib.rs"
}

run_guard()      { python3 "$GUARD" "$1" affine "$POLICY" 2>&1; }
run_guard_repo() { python3 "$GUARD" --repo "$1" affine "$POLICY" 2>&1; }

# ── case 1: pristine upstream shape → FAIL, naming ALL FOUR reaches ───────
# The regression that matters: a guard reporting 3 of 4 looks like a pass to
# anyone skimming, and 'de-clouding makes it MIT' is exactly the wrong lesson
# to draw from a partial list.
T="$WORK/pristine"; build_fixture "$T"
OUT="$(run_guard "$T")"; RC=$?
if [ "$RC" -ne 1 ]; then
  fail "pristine upstream: expected exit 1, got $RC"
else
  n=0
  for f in "App/service/build.gradle" "mobile-native/Cargo.toml" \
           "mobile-native/src/lib.rs" "nbstore/Cargo.toml" "nbstore/src/lib.rs"; do
    if contains "$OUT" "$f"; then n=$((n + 1)); else fail "pristine: guard did not name $f"; fi
  done
  [ "$n" -eq 5 ] && ok "pristine upstream: exit 1, all EE reaches named (gradle + cargo + rust + nbstore)"
fi

# ── case 2: de-clouded but nbstore untouched → STILL FAIL ─────────────────
# This is the finding the whole guard exists for, as an executable assertion:
# removing :service and hashcash does NOT clear the boundary, because the
# storage crate reaches into the same EE directory and a local-first notes app
# cannot drop its storage layer.
T="$WORK/declouded"; build_fixture "$T"; declould_frontend "$T"
OUT="$(run_guard "$T")"; RC=$?
if [ "$RC" -ne 1 ]; then
  fail "de-clouded only: expected exit 1 (nbstore still reaches EE), got $RC: $OUT"
elif ! contains "$OUT" "nbstore"; then
  fail "de-clouded only: guard did not name the surviving nbstore reach: $OUT"
else
  ok "de-clouded only: exit 1 — de-clouding alone does NOT clear the EE boundary"
fi

# ── case 3: fully severed tree → PASS ────────────────────────────────────
# Without this the guard could be `exit 1` and every other case would agree
# with it. This is the case that makes a green result mean something.
T="$WORK/severed"; build_fixture "$T"; declould_frontend "$T"; sever_nbstore "$T"
OUT="$(run_guard "$T")"; RC=$?
if [ "$RC" -ne 0 ]; then
  fail "fully severed tree: expected exit 0, got $RC — guard cannot go green: $OUT"
else
  ok "fully severed tree: exit 0 (guard is not a constant failure)"
fi

# ── case 4: a NEW reach an upstream bump could introduce → FAIL, tagged NEW ─
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

# ── case 5: a scan root missing → refuse to report clean ──────────────────
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

# ── case 6: EE source physically vendored → FAIL on its own terms ─────────
# The EE licence forbids copying. A restricted directory sitting in a repo we
# publish is already the prohibited act, with or without a compile edge, so it
# must not depend on a marker matching some line of code.
T="$WORK/vendored"; build_fixture "$T"; declould_frontend "$T"; sever_nbstore "$T"
mkdir -p "$T/packages/common/native/src"
: > "$T/packages/common/native/src/hashcash.rs"
OUT="$(run_guard "$T")"; RC=$?
if [ "$RC" -ne 1 ]; then
  fail "vendored EE dir: expected exit 1, got $RC: $OUT"
elif ! contains "$OUT" "[vendored]"; then
  fail "vendored EE dir: guard did not flag the EE directory itself: $OUT"
else
  ok "vendored EE dir: exit 1 — copying EE source in is caught with no marker needed"
fi

# ── case 7: repository mode, upstream not vendored → PASS, and SAY WHY ────
# The state of this repository today. It must pass, because nothing ships — but
# it must not print the same sentence as a scanned-and-clean tree, or the two
# stop being distinguishable in a log and 'found nothing to look at' quietly
# becomes 'looked and it was clean'.
T="$WORK/emptyrepo"; mkdir -p "$T/some_app/src"; : > "$T/some_app/src/Main.kt"
OUT="$(run_guard_repo "$T")"; RC=$?
if [ "$RC" -ne 0 ]; then
  fail "repo mode, nothing vendored: expected exit 0, got $RC: $OUT"
elif ! contains "$OUT" "not vendored"; then
  fail "repo mode, nothing vendored: verdict does not say the tree is absent: $OUT"
else
  ok "repo mode, nothing vendored: exit 0 and says so in words, not 'no reach'"
fi

# ── case 8: repository mode finds a tree by SHAPE, not by path ────────────
# The guard cannot be told where ac_cloud-notes/ will be, because that is the
# owner's undecided choice. Vendoring under any name at all must be caught.
T="$WORK/repowithtree"; mkdir -p "$T"
build_fixture "$T/ac_some_name_nobody_configured"
OUT="$(run_guard_repo "$T")"; RC=$?
if [ "$RC" -ne 1 ]; then
  fail "repo mode, tree vendored under an arbitrary name: expected exit 1, got $RC: $OUT"
elif ! contains "$OUT" "ac_some_name_nobody_configured"; then
  fail "repo mode: guard did not name the directory it found: $OUT"
else
  ok "repo mode: vendored tree found under a name no config mentions, exit 1"
fi

# ── case 9: repository mode must not fail OPEN on a broken tree ───────────
# Repo mode is the one that passes when it finds nothing, so it is the one that
# could quietly turn 'this checkout is broken' into a green tick.
T="$WORK/repotruncated"; mkdir -p "$T"
build_fixture "$T/ac_notes"
rm -rf "$T/ac_notes/packages/frontend/mobile-native"
OUT="$(run_guard_repo "$T")"; RC=$?
if [ "$RC" -eq 0 ]; then
  fail "repo mode, truncated tree: reported CLEAN (false green)"
elif [ "$RC" -ne 2 ]; then
  fail "repo mode, truncated tree: expected exit 2, got $RC: $OUT"
else
  ok "repo mode, truncated tree: exit 2, fails closed"
fi

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "PASS   9/9 cases"; exit 0
else
  echo "FAIL   $FAILURES case(s)"; exit 1
fi
