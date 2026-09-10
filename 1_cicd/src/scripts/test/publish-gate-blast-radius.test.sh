#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ publish-gate-blast-radius.test — a change to ONE library must    ║
# ║ republish that library, not the whole shelf                      ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. Three times now the fleet has republished applications
# nobody touched. Task #146 was Collabora Office, 250 MB of it, rebuilt because
# a repo-wide commit moved a mirror's tree sha. Task #235 was cloud-ide, and
# called itself a recurrence of #146. On 2026-09-09 commit 34a089d7 fixed three
# holes in ab_cloud-libs-shared/libs/updater and ship-cloud-libs republished all
# 35 library APKs, of which 2 had actually changed; the owner's phone offered
# six applications at once, every one stamped sha-34a089d7.
#
# Both earlier fixes were real and both are still in the tree. Neither held
# because neither was ever the whole story: cloud-android-publish-gate.sh
# resolves ONE asset name out of build.json, ship-cloud-libs publishes 35 and
# so declares none, and the gate fails open when it cannot name an asset. The
# one workflow that fans a shared-library touch across the widest set of
# artifacts was the one workflow the gate structurally could not weigh — and
# nothing anywhere asserted that a gate existed, so the gap was invisible.
#
# So this file asserts the property, not the implementation:
#
#   1. blast radius   — exact, on a fixture graph this file builds, driving the
#                       REAL build.sh through a symlink rather than a copy.
#   2. blast radius   — on the repo's own module graph, against a closure this
#                       file derives INDEPENDENTLY from the build.gradle files.
#                       Two implementations that must agree; if they drift, one
#                       of them is wrong and the test says which module.
#   3. coverage       — the union of every asset's input set covers every file
#                       the workflow watches. This is the safety direction: a
#                       gate that skips a build a real change triggered is a
#                       silently stale phone, which is strictly worse than the
#                       phantom updates this all exists to stop.
#   4. wiring         — every ship workflow that publishes is gated, and no
#                       workflow refers to a `steps.gate` it does not define.
#                       ship-cloud-libs.yml carried exactly that dangling
#                       reference: always true, so the job LOOKED gated.
#   5. fail-open      — the awkward inputs (unknown module, missing path list,
#                       --asset without a scope, no readable release) must
#                       publish or refuse loudly, never silently skip.
#
# bash, python3, git, jq. No gradle, no gh, no network: a test that needs any
# of those is a test that goes red on someone else's outage and gets muted.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
HARNESS="$ROOT/ab_cloud-libs-shared/lib-apks"
BUILD_SH="$HARNESS/build.sh"
GATE="$ROOT/1_cicd/dist/scripts/cloud-android-publish-gate.sh"
IDENTITY="$ROOT/1_cicd/dist/scripts/cloud-android-source-identity.sh"
FAILURES=0

ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

# Plain substring containment. NOT `case "$s" in *"$needle"*)` — inside a case
# pattern [NEW] is a bracket expression matching one of N/E/W, and that bug
# shipped in this repository once already.
contains() { [ "${1#*"$2"}" != "$1" ]; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ══════════════════════════════════════════════════════════════════
# 1. EXACT BLAST RADIUS, on a graph this file controls
# ══════════════════════════════════════════════════════════════════
# alpha standalone, beta depends on alpha, gamma standalone, and one directory
# under the scan root that is not a module at all. build.sh is reached through
# a SYMLINK so the code under test is the real one: a copy would pass this file
# for as long as it took the two to drift, which is the #235 failure exactly.
FIX="$WORK/fixture"
mkdir -p "$FIX/harness" "$FIX/1_cicd/src/cicd" \
         "$FIX/libs/alpha" "$FIX/libs/beta" "$FIX/libs/gamma" "$FIX/libs/notamodule"

echo 'dependencies { }'                                  > "$FIX/libs/alpha/build.gradle"
echo "dependencies { implementation project(':libs:alpha') }" > "$FIX/libs/beta/build.gradle"
echo 'dependencies { }'                                  > "$FIX/libs/gamma/build.gradle"
echo 'not a gradle module'                               > "$FIX/libs/notamodule/README.md"

cat > "$FIX/harness/build.json" <<'JSON'
{
  "lib_apks": {
    "scan": ["../libs"],
    "exclude": {},
    "application_id_prefix": "com.example.fixture",
    "asset_prefix": "Fixture-Lib-"
  },
  "release": { "ghcr": { "image_prefix": "fixture-lib-" } }
}
JSON

cat > "$FIX/1_cicd/src/cicd/ship-fixture.yml" <<'YAML'
on:
  push:
    paths:
      - "1_cicd/src/cicd/ship-fixture.yml"
      - "harness/**"
      - "libs/**"
env:
  WORK_DIR: harness
YAML

ln -s "$BUILD_SH" "$FIX/harness/build.sh"
mkdir -p "$FIX/1_cicd/dist/scripts"
cp "$GATE" "$IDENTITY" "$FIX/1_cicd/dist/scripts/"
git -C "$FIX" init -q
git -C "$FIX" add -A >/dev/null 2>&1
git -C "$FIX" -c user.email=t@t -c user.name=t commit -qm fixture >/dev/null 2>&1

# The fixture has to be real before anything is asserted about it. Without
# this, a scaffolding failure - an unwritable temp dir, a dangling symlink -
# makes every module "not selected", which reads as a blast-radius regression
# and sends the next reader after a bug that is not there.
for required in "$FIX/harness/build.sh" "$FIX/harness/build.json" \
                "$FIX/1_cicd/dist/scripts/cloud-android-source-identity.sh"; do
    [ -e "$required" ] || { printf 'ERROR  fixture incomplete: %s\n' "$required"; exit 2; }
done

# Which fixture modules select a given path?
#
# The exit status is checked, not just the output. `cmd | grep -q` reports "no
# match" identically whether cmd printed a non-matching list or died before
# printing anything, so a broken harness would quietly assert the very thing
# this file exists to catch. A module that cannot be resolved aborts the run.
fixture_selects() {
    local probe="$1" module selected="" output
    for module in alpha beta gamma; do
        output="$(bash "$FIX/harness/build.sh" module-paths "$module" 2>&1)" || {
            printf 'ERROR  fixture module-paths %s failed: %s\n' "$module" "$output"
            exit 2
        }
        if printf '%s\n' "$output" | grep -qx "$probe"; then
            selected="$selected $module"
        fi
    done
    echo "${selected# }"
}

got="$(fixture_selects libs/alpha)"
[ "$got" = "alpha beta" ] \
    && ok "a change to libs/alpha selects alpha and its dependent beta, and nothing else" \
    || fail "libs/alpha should select 'alpha beta', selected '$got'"

got="$(fixture_selects libs/gamma)"
[ "$got" = "gamma" ] \
    && ok "a change to libs/gamma selects gamma alone — an unrelated library does not rebuild" \
    || fail "libs/gamma should select 'gamma', selected '$got'"

got="$(fixture_selects libs/beta)"
[ "$got" = "beta" ] \
    && ok "a change to a leaf does not reach the library it depends on" \
    || fail "libs/beta should select 'beta', selected '$got'"

# A directory under the scan root belonging to no module could feed any of
# them, so it must widen to all — the deliberate over-publish.
got="$(fixture_selects libs/notamodule)"
[ "$got" = "alpha beta gamma" ] \
    && ok "a non-module directory under the scan root widens to every APK (fail-safe)" \
    || fail "libs/notamodule should select all three, selected '$got'"

got="$(fixture_selects harness)"
[ "$got" = "alpha beta gamma" ] \
    && ok "a change to the shared harness widens to every APK (fail-safe)" \
    || fail "harness should select all three, selected '$got'"

# THE REGRESSION ITSELF: before this fix every module's identity was the whole
# app, so every probe above would have selected all three.
[ "$(fixture_selects libs/gamma)" != "$(fixture_selects harness)" ] \
    && ok "a one-module change and a harness change do NOT select the same set" \
    || fail "one-module and whole-harness changes select the same set — identity is not scoped"

# ══════════════════════════════════════════════════════════════════
# 2. BLAST RADIUS on the real module graph, independently derived
# ══════════════════════════════════════════════════════════════════
SHIPPED="$(bash "$BUILD_SH" list 2>/dev/null | awk 'NF { print $1 }')"
if [ -z "$SHIPPED" ]; then
    fail "build.sh list named no modules — the rest of section 2 would vacuously pass"
else
    ok "build.sh list names $(echo "$SHIPPED" | wc -w | tr -d ' ') shipped library APKs"

    # An independent closure over the SAME declarations build.sh parses. Two
    # implementations, one answer; agreement is evidence, a single one is not.
    python3 - "$ROOT" "$WORK" <<'PYEOF'
import os, re, sys
root, work = sys.argv[1], sys.argv[2]
libs = os.path.join(root, 'ab_cloud-libs-shared', 'libs')
modules = [d for d in sorted(os.listdir(libs))
           if os.path.isfile(os.path.join(libs, d, 'build.gradle'))]
deps = {}
for m in modules:
    text = open(os.path.join(libs, m, 'build.gradle')).read()
    deps[m] = [d for d in re.findall(r"""project\(['"]:libs:([A-Za-z0-9_.-]+)""", text)
               if d in modules]
def closure(start):
    seen, stack = set(), [start]
    while stack:
        cur = stack.pop()
        if cur in seen:
            continue
        seen.add(cur)
        stack.extend(deps[cur])
    return seen
with open(os.path.join(work, 'expected-closures'), 'w') as fh:
    for m in modules:
        fh.write('%s %s\n' % (m, ' '.join(sorted(closure(m)))))
PYEOF

    mismatch=0
    for module in $SHIPPED; do
        expected="$(awk -v m="$module" '$1 == m { $1 = ""; print }' \
                    "$WORK/expected-closures" | tr -s ' ' '\n' | awk 'NF' | sort)"
        actual="$(bash "$BUILD_SH" module-paths "$module" 2>/dev/null \
                  | awk -F/ '/^ab_cloud-libs-shared\/libs\// && NF == 3 { print $3 }' \
                  | sort)"
        # Only module directories are compared: the non-module entries under
        # the scan root are in every set by design and section 3 covers them.
        actual="$(comm -12 <(echo "$actual") \
                           <(awk '{ print $1 }' "$WORK/expected-closures" | sort))"
        if [ "$expected" != "$actual" ]; then
            fail "$module: gate hashes [$(echo $actual)] but its build.gradle graph is [$(echo $expected)]"
            mismatch=1
        fi
    done
    [ "$mismatch" -eq 0 ] \
        && ok "every shipped APK's input set is exactly its own gradle dependency closure"

    # The headline number, asserted against an independently derived
    # expectation rather than against "fewer than all". `selected < total` is
    # the tempting spelling and it is worthless: reverting the scoping entirely
    # still leaves a couple of modules out of the trigger list, so that form
    # reported green against the exact bug it was written for.
    total="$(echo "$SHIPPED" | wc -w | tr -d ' ')"
    expected=0
    for module in $SHIPPED; do
        awk -v m="$module" '$1 == m { $1 = ""; print }' "$WORK/expected-closures" \
            | grep -qw updater && expected=$((expected + 1))
    done
    selected=0
    for module in $SHIPPED; do
        bash "$BUILD_SH" module-paths "$module" 2>/dev/null \
            | grep -qx 'ab_cloud-libs-shared/libs/updater' && selected=$((selected + 1))
    done
    if [ "$selected" -eq "$expected" ]; then
        ok "34a089d7's libs/updater change selects $selected of $total library APKs — exactly those that compile it"
    else
        fail "libs/updater selects $selected of $total, but $expected compile it — the over-trigger of 2026-09-09 is back"
    fi
fi

# ══════════════════════════════════════════════════════════════════
# 3. COVERAGE — nothing the workflow watches falls outside every gate
# ══════════════════════════════════════════════════════════════════
# The direction that must never break. A file that triggers the workflow but
# sits in no asset's input set is a build every gate skips: green, silent, and
# the phone keeps the old APK.
if [ -n "$SHIPPED" ]; then
    : > "$WORK/union"
    for module in $SHIPPED; do
        bash "$BUILD_SH" module-paths "$module" 2>/dev/null >> "$WORK/union"
    done
    sort -u "$WORK/union" -o "$WORK/union"

    sh "$IDENTITY" paths ab_cloud-libs-shared/lib-apks > "$WORK/triggers" 2>/dev/null

    uncovered="$(python3 - "$ROOT" "$WORK" <<'PYEOF'
import os, subprocess, sys
root, work = sys.argv[1], sys.argv[2]
union = [l.strip() for l in open(os.path.join(work, 'union')) if l.strip()]
triggers = [l.strip() for l in open(os.path.join(work, 'triggers')) if l.strip()]

def covered(path):
    return any(path == u or path.startswith(u + '/') for u in union)

# A module under the scan root that ships no APK of its own AND that no shipped
# APK compiles against cannot end up in any published artifact - libs/keyboard
# ships as ac_cloud-keyboard, gated by its own workflow. Nothing owes it a
# publish, so "no gate weighs it" is the correct answer rather than a hole.
# Derived from the same union, never listed: a module that becomes a dependency
# of something shipped stops being exempt on its own.
libs_root = 'ab_cloud-libs-shared/libs'
hashed_modules = {u.split('/')[2] for u in union
                  if u.startswith(libs_root + '/') and u.count('/') == 2}
exempt = [os.path.join(libs_root, d)
          for d in os.listdir(os.path.join(root, libs_root))
          if os.path.isfile(os.path.join(root, libs_root, d, 'build.gradle'))
          and d not in hashed_modules]

missed = []
for trigger in triggers:
    listing = subprocess.run(['git', '-C', root, 'ls-files', '-z', '--', trigger],
                             capture_output=True, text=True)
    for tracked in filter(None, listing.stdout.split('\0')):
        if covered(tracked):
            continue
        if any(tracked.startswith(e + '/') for e in exempt):
            continue
        missed.append(tracked)
print('\n'.join(sorted(set(missed))[:10]))
PYEOF
)"
    if [ -z "$uncovered" ]; then
        ok "every tracked file the workflow watches is inside some APK's input set"
    else
        fail "files trigger ship-cloud-libs but no gate weighs them: $(echo $uncovered)"
    fi
fi

# ══════════════════════════════════════════════════════════════════
# 4. WIRING — a workflow that publishes is a workflow that is gated
# ══════════════════════════════════════════════════════════════════
for workflow in "$ROOT"/1_cicd/src/cicd/ship-*.yml; do
    name="$(basename "$workflow")"
    # Comments stripped: this section asks what the workflow DOES. The comment
    # on ship-cloud-libs.yml explaining why it has no gate step names
    # steps.gate in prose, and matching that would fail the workflow for
    # documenting its own fix.
    body="$(awk '{ sub(/[[:space:]]*#.*$/, ""); print }' "$workflow")"

    # A `steps.gate` reference with no `id: gate` evaluates to the empty
    # string, so `!= 'true'` is always true and the step always runs. It reads
    # as a gate and is not one. ship-cloud-libs.yml shipped this for weeks.
    if contains "$body" 'steps.gate.' && ! contains "$body" 'id: gate'; then
        fail "$name refers to steps.gate but defines no step with id: gate — the condition is always true"
    fi

    # Publishing to the rolling release is what a phone sees. Either the
    # workflow gates in the job, or the build.sh it invokes gates per asset.
    if contains "$body" 'gh-release' || contains "$body" 'gh release upload'; then
        work_dir="$(awk '/^  WORK_DIR:/ { print $2; exit }' "$workflow")"

        # cloud-android-publish-gate.sh resolves ONE literal asset name and one
        # literal tag. ship-garmin-watchface declares both as templates
        # ("{design}.prg" on "fa_garmin-watchface-{design}-latest") because it
        # ships a matrix of watch faces, and the gate has no substitution. That
        # is a real remaining gap, printed rather than silently skipped so it
        # stays visible; closing it means teaching the gate the variant
        # substitution and testing it, not asserting it here.
        asset_name="$(jq -r '.release.gh_release.asset_name // empty' \
                      "$ROOT/$work_dir/build.json" 2>/dev/null)"
        if contains "$asset_name" '{'; then
            printf 'skip   %s publishes "%s" — a templated asset name the gate cannot resolve\n' \
                   "$name" "$asset_name"
            continue
        fi

        gated=0
        contains "$body" 'publish-gate.sh check' && gated=1
        if [ "$gated" -eq 0 ] && [ -n "$work_dir" ] && [ -f "$ROOT/$work_dir/build.sh" ]; then
            grep -q 'publish-gate.sh' "$ROOT/$work_dir/build.sh" && gated=1
        fi
        [ "$gated" -eq 1 ] \
            || fail "$name publishes to a release but nothing weighs whether the source moved"
    fi
done
[ "$FAILURES" -eq 0 ] && ok "every publishing ship workflow is gated, and no gate condition dangles"

# ══════════════════════════════════════════════════════════════════
# 5. FAIL-OPEN — the awkward inputs publish or refuse, never skip quietly
# ══════════════════════════════════════════════════════════════════
out="$(bash "$BUILD_SH" module-paths no-such-module 2>&1)"; status=$?
[ "$status" -ne 0 ] && [ -n "$out" ] \
    && ok "an unknown module fails loudly instead of returning an empty input set" \
    || fail "module-paths on an unknown module exited $status with output '$out'"

out="$(sh "$IDENTITY" compute ab_cloud-libs-shared/lib-apks --paths-from "$WORK/absent" 2>&1)"; status=$?
[ "$status" -ne 0 ] && contains "$out" "no such file" \
    && ok "a missing path list is refused rather than silently widened to the whole app" \
    || fail "--paths-from on a missing file exited $status: '$out'"

out="$(sh "$GATE" check ab_cloud-libs-shared/lib-apks --asset Cloud-Lib-Updater.apk 2>&1)"; status=$?
[ "$status" -ne 0 ] && contains "$out" "needs --paths-from" \
    && ok "--asset without a scope is refused — it would gate one asset on the whole app" \
    || fail "--asset without --paths-from exited $status: '$out'"

# No GH_TOKEN and no readable release: the gate must publish. This is the
# branch that keeps a broken publish repairable instead of permanently skipped.
bash "$BUILD_SH" module-paths updater > "$WORK/updater-paths" 2>/dev/null
out="$(GITHUB_OUTPUT= GH_TOKEN= PATH="/usr/bin:/bin" sh "$GATE" check \
        ab_cloud-libs-shared/lib-apks --asset Cloud-Lib-Updater.apk \
        --paths-from "$WORK/updater-paths" 2>&1)"
if contains "$out" "skip=false"; then
    ok "an unreadable release publishes rather than skips"
else
    fail "gate with no readable release did not publish: '$out'"
fi

printf '\n'
if [ "$FAILURES" -eq 0 ]; then
    printf 'PASS — the gate weighs each library APK on its own source\n'
    exit 0
fi
printf 'FAIL — %d assertion(s)\n' "$FAILURES"
exit 1
