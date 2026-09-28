#!/usr/bin/env bash
# Plain shell + assert test (same convention as test-fork-engine-helpers.sh:
# no framework, source a fixture build.json, assert on resolved values).
#
# #628 Slice 1 — proves the COMPANION ASSET machinery in both terminal fork
# engines: one app publishing a SECOND signed, versioned APK beside its own, so
# a runtime payload a sibling app consumes (the terminals' ~400 MB root
# filesystems) can be installed and updated through the same path as every other
# fleet library instead of sitting on the release as a bare asset no store row
# can reach (#618 → #624 → #628).
#
# WHY THIS TESTER EXISTS AT ALL.  Slice 1 declares ZERO companions: the
# capability lands inert so the payload can move into it as one reviewable
# commit afterwards. An inert capability with no tester is exactly how #198 and
# #436 became fixes that never ran — shipped, green, and never executed. So this
# file asserts the machinery is present, WIRED (its entry points are actually
# called, not merely defined), carried by BOTH engines AND both vendored
# ./build.sh copies, and that every way of declaring a companion wrongly goes
# RED rather than silently resolving to nothing.
#
# No network, no Android SDK, no gradle, no adb, no device: every assertion is
# either a static read of the engine sources or a call to the pure resolution
# helpers against a throwaway fixture. The build/publish steps themselves are
# never invoked — only the resolution and validation in front of them.
set -euo pipefail

SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$SCRIPTS/../../.." && pwd)"

# The two engines and the vendored copy each one is `cp`'d to. The workflow
# generator md5-compares those pairs, so drift is already a red build — but a
# capability added to the engine and not re-synced is the specific drift this
# ticket could cause, so it is asserted here too, where the failure names it.
ENGINES=(
  "$SCRIPTS/cloud-termux-fork-engine.sh|$REPO/ac_cloud-termux/build.sh"
  "$SCRIPTS/cloud-nix-on-droid-fork-engine.sh|$REPO/ac_cloud-nix-on-droid/build.sh"
)

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

pass=0
fail=0
assert_eq() {
  local desc="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    printf '  [PASS] %s\n' "$desc"; pass=$((pass+1))
  else
    printf '  [FAIL] %s (expected=%q actual=%q)\n' "$desc" "$expected" "$actual"; fail=$((fail+1))
  fi
}
assert_contains() {
  local desc="$1" needle="$2" haystack="$3"
  case "$haystack" in
    *"$needle"*) printf '  [PASS] %s\n' "$desc"; pass=$((pass+1)) ;;
    *)           printf '  [FAIL] %s (must contain %q)\n' "$desc" "$needle"; fail=$((fail+1)) ;;
  esac
}

# $1 = engine path, $2 = fixture build.json, $3 = bash expr evaluated after
# sourcing. SCRIPT_DIR derives from $0/dirname, so the engine is sourced from
# inside a throwaway dir holding only the fixture. The engine's main-guard
# ([[ BASH_SOURCE == $0 ]]) keeps the dispatch block from running: only
# functions get defined.
run_in_fixture() {
  local engine="$1" json="$2" expr="$3"
  local dir; dir="$(mktemp -d "$WORK/fixture.XXXX")"
  printf '%s' "$json" > "$dir/build.json"
  ( cd "$dir" && bash -c '
      set -euo pipefail
      set -- help
      source "'"$engine"'"
      '"$expr"'
    '
  )
}

# Same, but for assertions about FAILURE: returns the exit status and captures
# both streams, because "this declaration is refused" is the property under test.
run_in_fixture_status() {
  local engine="$1" json="$2" expr="$3" out
  local dir; dir="$(mktemp -d "$WORK/fixture.XXXX")"
  printf '%s' "$json" > "$dir/build.json"
  set +e
  out="$( ( cd "$dir" && bash -c '
      set -euo pipefail
      set -- help
      source "'"$engine"'"
      '"$expr"'
    ' ) 2>&1 )"
  local status=$?
  set -e
  printf '%s\n--status=%d\n' "$out" "$status"
}

# A companion declared the way Slice 2 will declare it, hyphen in the id and
# per-variant asset names included. The hyphen is not decoration: `.companions
# .rootfs-termux` is jq ARITHMETIC on two undefined functions, not a lookup, and
# it exits 0 having resolved nothing (#368, GHA run 30528694044).
FIXTURE='{
  "name": "cloud-terminal-termux",
  "forks": { "termux": { "tracker_dir": "ac_cloud-termux", "build": { "gradle_task": "assembleRelease", "apk_glob": "app/build/outputs/apk/release/*.apk" } } },
  "release": {
    "gh_release": { "rolling_tag": "latest", "asset_name": "cloud-terminal.apk" },
    "variants": [
      { "id": "arm64",  "abis": ["arm64-v8a"], "gh_asset": "cloud-terminal.apk" },
      { "id": "x86_64", "abis": ["x86_64"],    "gh_asset": "cloud-terminal-x86_64.apk" }
    ],
    "companions": [
      {
        "id": "rootfs-termux",
        "gradle_task": ":rootfs-lib:assembleRelease",
        "apk_glob": "rootfs-lib/build/outputs/apk/**/*.apk",
        "asset": "cloud-lib-rootfs-termux.apk",
        "assets": { "arm64": "cloud-lib-rootfs-termux.apk", "x86_64": "cloud-lib-rootfs-termux-x86_64.apk" },
        "paths_from": ["ac_cloud-termux/rootfs/rootfs.json"],
        "package": "com.diegonmarcos.cloudlib.rootfstermux"
      }
    ]
  }
}'

for pair in "${ENGINES[@]}"; do
  engine="${pair%%|*}"
  vendored="${pair#*|}"
  name="$(basename "$engine")"
  echo "== $name =="

  # ── the machinery is PRESENT ──────────────────────────────────────────
  src="$(cat "$engine")"
  for fn in _companion_ids _companion_json _companion_asset _companion_paths_file \
            _companion_should_publish _companion_stamp _build_companions _publish_companions; do
    assert_contains "$name defines $fn" "$fn() {" "$src"
  done

  # ── the machinery is WIRED ────────────────────────────────────────────
  # A defined-but-uncalled function is the inert-capability failure this whole
  # tester exists for, so the CALL SITES are asserted, not just the definitions.
  assert_contains "$name calls _build_companions from build-fork (from-source path)" \
    '_build_companions "$key"' "$src"
  assert_contains "$name calls _publish_companions from gh-release-fork" \
    '_publish_companions "$key" "$rolling_tag"' "$src"
  # Both build-fork exit paths: the upstream-APK branch returns early, and a
  # companion belongs to the APP, not to how the app's own APK was produced.
  assert_eq "$name calls _build_companions on BOTH build-fork exit paths" "2" \
    "$(grep -c '_build_companions "\$key"' "$engine")"

  # ── the vendored copy carries it too ──────────────────────────────────
  # CI runs ./build.sh, never the engine source, so an engine-only edit changes
  # nothing at ship time — silently (memory: the ABI gate and the GHCR
  # auto-delete gate both came to exist only in the file nobody executes).
  assert_eq "$(basename "$vendored") is byte-identical to $name" \
    "$(md5sum < "$engine" | awk '{print $1}')" \
    "$(md5sum < "$vendored" | awk '{print $1}')"

  # ── resolution ────────────────────────────────────────────────────────
  out="$(run_in_fixture "$engine" "$FIXTURE" 'echo "ids=$(_companion_ids | tr "\n" ",")"')"
  eval "$out"
  assert_eq "$name: _companion_ids lists the declared companion" "rootfs-termux," "$ids"

  # The hyphen trap: resolving through --arg must find the object. If this ever
  # regresses to path interpolation the read returns empty and jq exits 0.
  out="$(run_in_fixture "$engine" "$FIXTURE" 'echo "task=$(_companion_json rootfs-termux ".gradle_task")"')"
  eval "$out"
  assert_eq "$name: a HYPHENATED companion id still resolves (#368)" \
    ":rootfs-lib:assembleRelease" "$task"

  # Per-variant asset, so the two ABI jobs cannot overwrite each other.
  out="$(run_in_fixture "$engine" "$FIXTURE" 'CLOUDNAV_VARIANT=x86_64; echo "a=$(_companion_asset rootfs-termux)"')"
  eval "$out"
  assert_eq "$name: companion asset follows the active variant" \
    "cloud-lib-rootfs-termux-x86_64.apk" "$a"
  out="$(run_in_fixture "$engine" "$FIXTURE" 'echo "a=$(_companion_asset rootfs-termux)"')"
  eval "$out"
  assert_eq "$name: companion asset falls back to the variant-neutral name" \
    "cloud-lib-rootfs-termux.apk" "$a"

  # ── TODAY'S STATE: inert ──────────────────────────────────────────────
  # Slice 1 declares no companions, so every loop must iterate nothing. This is
  # the assertion that turns RED the moment Slice 2 lands, which is the point:
  # the follow-up cannot land without coming back through this file.
  out="$(run_in_fixture "$engine" '{"release":{}}' 'echo "ids=$(_companion_ids | tr "\n" ",")"')"
  eval "$out"
  assert_eq "$name: an app declaring no companions resolves NONE" "" "$ids"

  # ── MUTATIONS: every wrong declaration must go RED ────────────────────
  # The mutants are cut with jq, not with bash string substitution: a pattern
  # containing `/` or `[...]` silently fails to match and hands the assertion an
  # UNMUTATED fixture, which then passes for the wrong reason. That is the same
  # fail-open shape these testers exist to catch, so it is not used to build them.
  mutate() { jq -c "$1" <<<"$FIXTURE"; }

  # (1) no paths_from — would gate one asset against the whole app's identity
  #     and republish 437 MB on every unrelated app-code change, which is the
  #     #618 regression this design exists to avoid.
  out="$(run_in_fixture_status "$engine" "$(mutate 'del(.release.companions[0].paths_from)')" \
        '_companion_should_publish rootfs-termux x.apk')"
  assert_contains "$name MUTATION: a companion with no paths_from is REFUSED" "paths_from" "$out"
  case "$out" in *"--status=0"*) printf '  [FAIL] %s\n' "$name MUTATION: no-paths_from exited 0"; fail=$((fail+1)) ;;
                 *) printf '  [PASS] %s\n' "$name MUTATION: no-paths_from is a non-zero exit"; pass=$((pass+1)) ;; esac

  # (2) declared but unbuildable — no gradle_task. A declared companion that
  #     nothing builds must be a failure, not a silent skip: a skip here is a
  #     fleet row pointing at an asset nobody uploads.
  out="$(run_in_fixture_status "$engine" "$(mutate 'del(.release.companions[0].gradle_task)')" \
        '_build_companions termux')"
  assert_contains "$name MUTATION: a companion with no gradle_task is REFUSED" "gradle_task" "$out"
  case "$out" in *"--status=0"*) printf '  [FAIL] %s\n' "$name MUTATION: no-gradle_task exited 0"; fail=$((fail+1)) ;;
                 *) printf '  [PASS] %s\n' "$name MUTATION: no-gradle_task is a non-zero exit"; pass=$((pass+1)) ;; esac

  # (3) declared but unfindable — no apk_glob.
  out="$(run_in_fixture_status "$engine" "$(mutate 'del(.release.companions[0].apk_glob)')" \
        '_build_companions termux')"
  assert_contains "$name MUTATION: a companion with no apk_glob is REFUSED" "apk_glob" "$out"
  case "$out" in *"--status=0"*) printf '  [FAIL] %s\n' "$name MUTATION: no-apk_glob exited 0"; fail=$((fail+1)) ;;
                 *) printf '  [PASS] %s\n' "$name MUTATION: no-apk_glob is a non-zero exit"; pass=$((pass+1)) ;; esac

  # (4) declared with no publishable name for the active variant.
  out="$(run_in_fixture_status "$engine" \
        "$(mutate 'del(.release.companions[0].asset, .release.companions[0].assets)')" \
        '_build_companions termux')"
  assert_contains "$name MUTATION: a companion with no asset name is REFUSED" "asset name" "$out"
  case "$out" in *"--status=0"*) printf '  [FAIL] %s\n' "$name MUTATION: no-asset exited 0"; fail=$((fail+1)) ;;
                 *) printf '  [PASS] %s\n' "$name MUTATION: no-asset is a non-zero exit"; pass=$((pass+1)) ;; esac
  echo
done

# ── the REAL build.json files, not a fixture ────────────────────────────
# Slice 1's contract in one line: the capability is in the engines and nothing
# declares it yet, so no pipeline changes behaviour on this commit.
echo "== the shipped declarations =="
for app in ac_cloud-termux ac_cloud-nix-on-droid; do
  n="$(jq -r '(.release.companions // []) | length' "$REPO/$app/build.json")"
  assert_eq "$app declares 0 companions (Slice 1 is inert)" "0" "$n"
done

echo
echo "Results: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
