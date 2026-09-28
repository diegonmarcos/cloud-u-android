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

  # ── an app that declares nothing resolves nothing ─────────────────────
  # Slice 1 shipped with ZERO companions declared and this assertion was
  # written against the real build.json, so it turned red the moment Slice 2
  # declared one — which was its purpose: the follow-up could not land without
  # coming back through this file. Slice 2 landed, so the property moves to a
  # FIXTURE (where it is still worth holding: a companion-less app must not
  # inherit a sibling's) and the real declarations are asserted at the bottom.
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
# Slice 2's contract: each terminal declares EXACTLY ONE companion, and that
# companion is the rootfs library the store installs. Asserted against the
# shipped declarations rather than a fixture, because an engine capability that
# nothing declares changes no pipeline — which was Slice 1's state, and is the
# state this block used to pin.
#
# The identity assertions are deliberately NOT a second copy of the naming rule:
# they read what the ONE naming declaration (ab_cloud-libs-shared/lib-apks/
# build.json::lib_apks) produces for this module, so a prefix change moves both
# or fails here. cloud-android-fleet-manifest-guard.py holds the same rule from
# the fleet side; this holds it from the engine side, where the APK is built.
echo "== the shipped declarations =="
LIB_DECL="$REPO/ab_cloud-libs-shared/lib-apks/build.json"
id_prefix="$(jq -r '.lib_apks.application_id_prefix' "$LIB_DECL")"
asset_prefix="$(jq -r '.lib_apks.asset_prefix' "$LIB_DECL")"
assert_eq "the one lib naming declaration still names an applicationId prefix" \
  "com.diegonmarcos.cloudlib" "$id_prefix"

for app in ac_cloud-termux ac_cloud-nix-on-droid; do
  bj="$REPO/$app/build.json"
  n="$(jq -r '(.release.companions // []) | length' "$bj")"
  assert_eq "$app declares exactly 1 companion (#628 Slice 2)" "1" "$n"

  # The fleet identity and the build identity are ONE fact, stated by the
  # companion and POINTED AT by fleet-lib.json. A fleet-lib.json naming a
  # companion nobody declares is a store row for an APK no job produces.
  module="$(jq -r '.module' "$REPO/$app/fleet-lib.json")"
  companion="$(jq -r '.companion' "$REPO/$app/fleet-lib.json")"
  assert_eq "$app/fleet-lib.json is kind \"lib\", not #624's \"artifact\"" \
    "lib" "$(jq -r '.kind' "$REPO/$app/fleet-lib.json")"
  assert_eq "$app's declared companion is the one release.companions[] declares" \
    "$companion" "$(jq -r --arg c "$companion" '(.release.companions // [])[] | select(.id==$c) | .id' "$bj")"

  # Spelled like every other lib APK: prefix + module sans dashes, and
  # Cloud-Lib-<Title-Cased-Module>.apk. "Consistency means follow same pattern".
  want_pkg="$id_prefix.$(printf '%s' "$module" | tr -d '-')"
  want_asset="$asset_prefix$(printf '%s' "$module" | awk -F- '{for(i=1;i<=NF;i++){$i=toupper(substr($i,1,1)) substr($i,2)}}1' OFS=-).apk"
  assert_eq "$app's companion applicationId is the canonical lib spelling" \
    "$want_pkg" "$(jq -r --arg c "$companion" '(.release.companions // [])[] | select(.id==$c) | .package' "$bj")"
  assert_eq "$app's companion asset is the canonical lib asset name" \
    "$want_asset" "$(jq -r --arg c "$companion" '(.release.companions // [])[] | select(.id==$c) | .asset' "$bj")"

  # One published name per ABI job, or the two matrix jobs overwrite each
  # other's library on the release and one ABI ships the other's rootfs.
  variants="$(jq -r '[(.release.variants // [])[].id] | sort | join(",")' "$bj")"
  named="$(jq -r --arg c "$companion" '[(.release.companions // [])[] | select(.id==$c) | .assets | keys[]] | sort | join(",")' "$bj")"
  assert_eq "$app's companion names an asset for every release variant" "$variants" "$named"

  # THE INDEPENDENT GATE is the whole reason this mechanism exists. Without
  # paths_from the 400 MB library is gated on the app's identity and every
  # one-line code fix republishes it — the #618 regression, reintroduced.
  gated="$(jq -r --arg c "$companion" '[(.release.companions // [])[] | select(.id==$c) | .paths_from[]?] | length' "$bj")"
  case "$gated" in
    0) printf '  [FAIL] %s\n' "$app's companion declares no paths_from"; fail=$((fail+1)) ;;
    *) printf '  [PASS] %s\n' "$app's companion is gated on its own declared inputs ($gated path(s))"; pass=$((pass+1)) ;;
  esac

  # AND every one of them EXISTS. Found while writing this file:
  # cloud-android-source-identity.sh hashes a paths_from entry that names nothing
  # as nothing and still prints a confident 64-hex identity with status 0 — so a
  # typo does not fail, it silently NARROWS the gate's input set, and the gate
  # then skips a republish that was genuinely needed. That is strictly worse than
  # the phantom republish the gate exists to prevent, and it is invisible: the
  # identity looks exactly as real as a correct one. Asserted here, where the
  # declaration lives, because it is the declarations this ticket adds.
  while IFS= read -r decl_path; do
    [ -n "$decl_path" ] || continue
    if [ -e "$REPO/$decl_path" ]; then
      printf '  [PASS] %s\n' "$app's companion gates on $decl_path, which exists"; pass=$((pass+1))
    else
      printf '  [FAIL] %s\n' "$app's companion gates on $decl_path, which does not exist — the gate would hash it as nothing and narrow its own scope silently"; fail=$((fail+1))
    fi
  done < <(jq -r --arg c "$companion" '(.release.companions // [])[] | select(.id==$c) | .paths_from[]?' "$bj")

  # The gradle module the companion names must actually exist, and be included
  # in the gradle build — a gradle_task pointing at no project is a build that
  # fails only in CI, on a native runner, forty minutes in.
  gmod="$(jq -r --arg c "$companion" '(.release.companions // [])[] | select(.id==$c) | .gradle_task' "$bj" | sed 's/^://; s/:.*$//')"
  if [ -f "$REPO/$app/$gmod/build.gradle" ]; then
    printf '  [PASS] %s\n' "$app's companion gradle module $gmod exists"; pass=$((pass+1))
  else
    printf '  [FAIL] %s\n' "$app declares gradle_task in :$gmod but $app/$gmod/build.gradle does not exist"; fail=$((fail+1))
  fi
  assert_contains "$app/settings.gradle includes :$gmod" "':$gmod'" "$(cat "$REPO/$app/settings.gradle")"
done

# ── NO SECOND TRANSPORT ─────────────────────────────────────────────────
# #628's claim in one line: the STORE downloads the rootfs and the terminal
# reads it out of the installed sibling APK. A surviving HttpURLConnection is a
# path that still works, is no longer exercised by anyone, and is therefore the
# one that rots unnoticed — so it is asserted absent here as well as in the
# fleet guard, because this is the file a change to a terminal's build comes
# through.
echo
echo "== exactly one way the payload arrives =="
for app in ac_cloud-termux ac_cloud-nix-on-droid; do
  # `|| true` on the grep, not on the pipeline: no match is exit 1 and
  # `set -o pipefail` would kill the tester at exactly the point it is proving
  # the healthy case — a tester that dies while passing reports nothing at all.
  hits="$( { grep -rlE 'HttpURLConnection|java\.net\.URL' "$REPO/$app/app/src/main/java" 2>/dev/null || true; } | wc -l | tr -d ' ')"
  assert_eq "$app's sources open no HTTP connection (the store downloads the lib)" "0" "$hits"
  if [ -e "$REPO/$app/rootfs/publish-artifact.sh" ] || [ -e "$REPO/$app/bootstrap/publish-artifact.sh" ]; then
    printf '  [FAIL] %s\n' "$app still carries publish-artifact.sh — the payload has two transports again"; fail=$((fail+1))
  else
    printf '  [PASS] %s\n' "$app no longer carries publish-artifact.sh"; pass=$((pass+1))
  fi
done

echo
echo "Results: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
