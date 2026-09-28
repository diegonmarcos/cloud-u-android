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

    scaffold_artifact "$root"
    write_manifest "$root"
}

# ── #624: a runtime artifact declared as a fleet lib ─────────────────────────
# The guard also holds that every content-addressed artifact #618 publishes
# beside an APK has a fleet identity, a version and a Constellation row. That
# property is only worth having if it is observed FAILING, so the synthetic tree
# grows one artifact shaped exactly like the two real ones and the cases below
# break it in each of the ways the real tree could.
scaffold_artifact() {
    local root="$1"
    mkdir -p "$root/ac_cloud-term/rootfs" "$root/ac_cloud-term/app"
    printf '{"release":{"variants":[{"id":"arm64","abis":["arm64-v8a"]}]}}\n' \
        > "$root/ac_cloud-term/build.json"
    cat > "$root/ac_cloud-term/rootfs/rootfs.json" <<'JSON'
{"artifact":{"repo":"owner/repo","tag":"latest",
  "asset":"term-rootfs-{id}-{abi}.tar.zst",
  "url":"https://github.com/{repo}/releases/download/{tag}/{asset}",
  "identity_files":["rootfs/rootfs.json","rootfs/build-rootfs.sh"],
  "digest_asset":"rootfs.sha256","url_asset":"rootfs.url"}}
JSON
    printf '#!/bin/sh\necho build\n' > "$root/ac_cloud-term/rootfs/build-rootfs.sh"
    cat > "$root/ac_cloud-term/fleet-lib.json" <<'JSON'
{"module":"rootfs-term","kind":"artifact",
 "declared_in":"rootfs/rootfs.json","at":"artifact",
 "what":"A synthetic root filesystem, fetched beside the APK."}
JSON
    # The gradle half of the ONE content-address rule. The guard recomputes that
    # address in python to name the fleet row, so it also asserts the gradle it
    # mirrors still derives it the same way — this is the shape it looks for.
    cat > "$root/ac_cloud-term/app/build.gradle" <<'GRADLE'
def artifactId = {
    def digest = java.security.MessageDigest.getInstance("SHA-256")
    artifact.identity_files.each { rel ->
        def f = file("$projectDir/../$rel")
        digest.update(java.security.MessageDigest.getInstance("SHA-256").digest(f.bytes))
    }
    return digest.digest().encodeHex().toString().substring(0, 12)
}()
GRADLE
}

# The manifest is written from the guard's OWN --emit-artifact-libs, exactly as
# aa_cloud-superapp/data/regen.sh writes the real one. A hardcoded version would
# be a second content address to keep in step, and this harness would then be
# testing the fixture instead of the tree.
write_manifest() {
    local root="$1" rows
    rows="$(python3 "$GUARD" --root "$root" --emit-artifact-libs 2>/dev/null)" || rows='[]'
    python3 - "$root" "$rows" <<'PY'
import json, sys
root, rows = sys.argv[1], json.loads(sys.argv[2])
json.dump({
    "version": 1,
    "groups": [
        {"id": "apps", "label": "Apps", "blurb": "", "members": ["cloud-mail", "cloud-drive"]},
        {"id": "runtimes", "label": "Runtimes", "blurb": "", "members": [r["id"] for r in rows]},
    ],
    "apps": [{"id": "cloud-mail"}, {"id": "cloud-drive"}],
    "catalogue": [{"id": r["id"], "label": r["label"], "group": "runtimes",
                   "description": r["description"], "installable": False,
                   "version": r["version"]} for r in rows],
}, open(root + "/aa_cloud-superapp/data/constellation-fleet.json", "w"))
PY
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

# ── #624 mutations ───────────────────────────────────────────────────────────
# An artifact published with no fleet identity at all: the state #618 left the
# two terminals in, and the whole reason for this half of the guard.
undeclare_artifact()   { rm -f "$1/ac_cloud-term/fleet-lib.json"; }
# Declared, but regen.sh never ran — the row is missing, so Constellation cannot
# name the 400 MB it is about to fetch.
drop_artifact_row()    { write_manifest_without_artifacts "$1"; }
write_manifest_without_artifacts() {
    python3 - "$1" <<'PY'
import json, sys
path = sys.argv[1] + "/aa_cloud-superapp/data/constellation-fleet.json"
doc = json.load(open(path))
doc["catalogue"] = []
doc["groups"] = [g for g in doc["groups"] if g["id"] != "runtimes"]
json.dump(doc, open(path, "w"))
PY
}
# A pin bump with a stale manifest: the row names an asset nobody uploaded.
bump_artifact_identity() {
    printf '#!/bin/sh\necho build differently\n' > "$1/ac_cloud-term/rootfs/build-rootfs.sh"
}
# The declaration aims at something that is not a published artifact.
break_artifact_pointer() {
    printf '{"module":"rootfs-term","kind":"artifact","declared_in":"rootfs/rootfs.json","at":"nowhere","what":"x"}\n' \
        > "$1/ac_cloud-term/fleet-lib.json"
}
# THE #618 WALL, PUT BACK. `apps` is what Fleet.parse reads, so a runtime tree
# listed there is handed to the updater as an installable package — 437 MB as an
# APK is exactly the install-size wall #618 removed.
promote_artifact_to_apps() {
    python3 - "$1" <<'PY'
import json, sys
path = sys.argv[1] + "/aa_cloud-superapp/data/constellation-fleet.json"
doc = json.load(open(path))
doc["apps"] += [{"id": row["id"]} for row in doc["catalogue"]]
json.dump(doc, open(path, "w"))
PY
}
claim_artifact_installable() {
    python3 - "$1" <<'PY'
import json, sys
path = sys.argv[1] + "/aa_cloud-superapp/data/constellation-fleet.json"
doc = json.load(open(path))
for row in doc["catalogue"]:
    row["installable"] = True
json.dump(doc, open(path, "w"))
PY
}
# The lib renamed away from #474's one canonical spelling.
rename_artifact_lib() {
    python3 - "$1" <<'PY'
import json, sys
path = sys.argv[1] + "/aa_cloud-superapp/data/constellation-fleet.json"
doc = json.load(open(path))
for row in doc["catalogue"]:
    row["label"] = "Rootfs: term"
json.dump(doc, open(path, "w"))
PY
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

echo
echo "Runtime artifacts are declared fleet libs (task #624)"
case_is "an artifact published with NO fleet-lib.json is CAUGHT"     1 undeclare_artifact
case_is "a declared lib missing from the manifest is CAUGHT"         1 drop_artifact_row
case_is "a stale artifact version in the manifest is CAUGHT"         1 bump_artifact_identity
case_is "a fleet-lib.json pointing at no artifact is CAUGHT"         1 break_artifact_pointer
case_is "a runtime artifact promoted into \`apps\` is CAUGHT"          1 promote_artifact_to_apps
case_is "a runtime artifact claiming installable is CAUGHT"          1 claim_artifact_installable
case_is "a lib renamed off cloud-lib-{module} is CAUGHT"             1 rename_artifact_lib

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
