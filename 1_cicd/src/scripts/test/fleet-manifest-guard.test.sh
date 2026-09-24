#!/usr/bin/env bash
# Meta-test for cloud-android-fleet-manifest-guard.py.
#
# A guard that is only ever watched succeeding is indistinguishable from a guard
# that returns 0 unconditionally. The defect this one catches — task #276, an
# empty CONSTELLATION_FLEET_B64 — is by its nature invisible at runtime, so the
# only way to know the guard works is to reintroduce the defect deliberately and
# require the guard to go red.
#
# Each case builds a throwaway repository shaped like the real one and points
# the guard at it with --root, so nothing here touches the working tree.
set -u

GUARD="$(cd "$(dirname "$0")/.." && pwd)/cloud-android-fleet-manifest-guard.py"
PASS=0
FAIL=0

# Build a synthetic repository. $1 = directory to create it in.
scaffold() {
    local root="$1"
    mkdir -p "$root/ab_cloud-libs-shared/libs/updater"
    mkdir -p "$root/aa_cloud-superapp/app/data" "$root/aa_cloud-superapp/data"
    mkdir -p "$root/ac_cloud-mail/app"

    cat > "$root/ab_cloud-libs-shared/libs/updater/build.gradle" <<'GRADLE'
def fleetOverride = file("${rootDir}/data/constellation-fleet.json")
def fleetCanonical = new File(projectDir, '../../../aa_cloud-superapp/data/constellation-fleet.json').canonicalFile
def fleetFile = fleetOverride.exists() ? fleetOverride : fleetCanonical
if (!fleetFile.exists()) {
    throw new GradleException("constellation-fleet.json not found")
}
def fleetB64 = fleetFile.text.bytes.encodeBase64().toString()
GRADLE

    printf 'dependencies {\n    implementation project(":libs:updater")\n}\n' \
        > "$root/aa_cloud-superapp/app/build.gradle"
    printf 'dependencies {\n    implementation(project(":libs:updater"))\n}\n' \
        > "$root/ac_cloud-mail/app/build.gradle.kts"

    printf '{"apps":[{"id":"cloud-mail"},{"id":"cloud-drive"}]}\n' \
        > "$root/aa_cloud-superapp/data/constellation-fleet.json"
}

# $1 = human description, $2 = expected exit status, $3 = mutation function name
case_is() {
    local description="$1" expected="$2" mutate="$3"
    local root; root="$(mktemp -d)"
    scaffold "$root"
    "$mutate" "$root"
    local output; output="$(python3 "$GUARD" --root "$root" 2>&1)"
    local actual=$?
    if [ "$actual" = "$expected" ]; then
        PASS=$((PASS + 1))
        printf 'ok    %s (exit %s)\n' "$description" "$actual"
    else
        FAIL=$((FAIL + 1))
        printf 'FAIL  %s — expected exit %s, got %s\n' "$description" "$expected" "$actual"
        printf '%s\n' "$output" | sed 's/^/        /'
    fi
    rm -rf "$root"
}

untouched()        { :; }

# The original defect, verbatim: rootDir-relative with an empty-string fallback.
reinstate_defect() {
    cat > "$1/ab_cloud-libs-shared/libs/updater/build.gradle" <<'GRADLE'
def fleetFile = file("${rootDir}/data/constellation-fleet.json")
def fleetB64 = fleetFile.exists() ? fleetFile.text.bytes.encodeBase64().toString() : ""
GRADLE
}

# The throw removed but the resolution kept — a missing manifest becomes green.
drop_the_throw() {
    grep -v "GradleException" "$1/ab_cloud-libs-shared/libs/updater/build.gradle" \
        > "$1/tmp.gradle"
    mv "$1/tmp.gradle" "$1/ab_cloud-libs-shared/libs/updater/build.gradle"
}

# The bake that stopped compiling on 2026-09-24: the manifest's base64 as ONE
# quoted literal. javac's 65,535-byte constant cap makes that a build break for
# every consumer the moment the fleet grows past it, so the guard must catch the
# SHAPE regardless of how big the manifest is on the day.
reinstate_single_constant_bake() {
    printf 'android { defaultConfig {\n    buildConfigField "String", "CONSTELLATION_FLEET_B64", "\\"${fleetB64}\\""\n} }\n' \
        >> "$1/ab_cloud-libs-shared/libs/updater/build.gradle"
}
add_chunked_bake() {
    printf 'android { defaultConfig {\n    buildConfigField "String", "CONSTELLATION_FLEET_B64", %s\n} }\n' \
        "'String.join(\"\", new String[]{' + (0..<fleetB64.length()).step(60000).collect { '\"' + fleetB64.substring(it, Math.min(it + 60000, fleetB64.length())) + '\"' }.join(', ') + '})'" \
        >> "$1/ab_cloud-libs-shared/libs/updater/build.gradle"
}
delete_canonical()  { rm -f "$1/aa_cloud-superapp/data/constellation-fleet.json"; }
empty_the_fleet()   { printf '{"apps":[]}\n' > "$1/aa_cloud-superapp/data/constellation-fleet.json"; }
corrupt_the_fleet() { printf '{"apps":[\n' > "$1/aa_cloud-superapp/data/constellation-fleet.json"; }

# A consumer that ships its own narrower fleet must still be accepted.
add_valid_override() {
    mkdir -p "$1/ac_cloud-mail/data"
    printf '{"apps":[{"id":"cloud-mail"}]}\n' > "$1/ac_cloud-mail/data/constellation-fleet.json"
}

echo "Fleet manifest guard — meta-test (task #276)"
case_is "healthy tree passes"                            0 untouched
case_is "own data/ override is accepted"                 0 add_valid_override
case_is "original rootDir + empty-string defect is CAUGHT" 1 reinstate_defect
case_is "removing the GradleException is CAUGHT"         1 drop_the_throw
case_is "chunked String.join bake is accepted"           0 add_chunked_bake
case_is "manifest baked as ONE string constant is CAUGHT" 1 reinstate_single_constant_bake
case_is "missing canonical manifest is CAUGHT"           1 delete_canonical
case_is "manifest listing no applications is CAUGHT"     1 empty_the_fleet
case_is "unparseable manifest is CAUGHT"                 1 corrupt_the_fleet

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
