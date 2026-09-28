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

# $FLEET_GUARD overrides the guard under test. Its ONLY purpose is mutation
# proof: point it at the previous revision of the guard and every case below
# that covers a NEW property must go red, which is the difference between "the
# guard passes" and "the guard can fail". A guard only ever watched succeeding
# is indistinguishable from one that returns 0 unconditionally, and this file
# exists because that shape is this repository's dominant defect.
#   git show HEAD~1:1_cicd/src/scripts/cloud-android-fleet-manifest-guard.py > /tmp/old.py
#   FLEET_GUARD=/tmp/old.py bash 1_cicd/src/scripts/test/fleet-manifest-guard.test.sh
GUARD="${FLEET_GUARD:-$(cd "$(dirname "$0")/.." && pwd)/cloud-android-fleet-manifest-guard.py}"
PASS=0
FAIL=0

# Build a synthetic repository. $1 = directory to create it in.
scaffold() {
    local root="$1"
    mkdir -p "$root/ab_cloud-libs-shared/libs/updater" "$root/ab_cloud-libs-shared/lib-apks"
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

    # #628 — the ONE naming rule every lib APK obeys. A rootfs lib is spelled
    # cloud-lib-{module} / com.diegonmarcos.cloudlib.{module-sans-dashes} /
    # Cloud-Lib-{Module}.apk because it IS a lib APK, and the guard reads those
    # three prefixes from here rather than restating them.
    cat > "$root/ab_cloud-libs-shared/lib-apks/build.json" <<'JSON'
{"lib_apks": {"application_id_prefix": "com.diegonmarcos.cloudlib",
              "asset_prefix": "Cloud-Lib-"},
 "release": {"ghcr": {"image_prefix": "cloud-lib-"}}}
JSON

    scaffold_rootfs_lib "$root"
    write_manifest "$root"
}

# ── #628: a runtime payload declared as a real fleet LIBRARY ─────────────────
# The guard also holds that every content-addressed payload #618 publishes beside
# an APK is a LIBRARY the store installs: its own signed, versioned APK, a row in
# `apps` under Libs, and no way left for the app to fetch the payload itself.
#
# #624 asserted the OPPOSITE — that the row sat in `catalogue` — and that
# placement WAS the defect: a catalogue row has no package and no versionCode, so
# no install or update path could be handed it, and each terminal went on
# downloading its own ~400 MB runtime at first launch. Every case below is
# therefore the inverse of a #624 case, and each one is red against the previous
# revision of the guard (see $FLEET_GUARD above) — that is the proof the
# inversion actually landed rather than being described in a comment.
scaffold_rootfs_lib() {
    local root="$1"
    mkdir -p "$root/ac_cloud-term/rootfs" "$root/ac_cloud-term/app/src/main/java/term" \
             "$root/ac_cloud-term/rootfs-lib"
    # A terminal declaring: two ABI variants, and ONE companion APK — the library
    # that carries the payload. The companion owns the applicationId and the
    # per-ABI asset names; nothing else in the tree restates either.
    cat > "$root/ac_cloud-term/build.json" <<'JSON'
{"release": {
  "ghcr": {"registry": "ghcr.io", "namespace": "diegonmarcos"},
  "variants": [
    {"id": "arm64",  "abis": ["arm64-v8a"], "supported_abis": ["arm64-v8a", "armeabi-v7a"]},
    {"id": "x86_64", "abis": ["x86_64"],    "supported_abis": ["x86_64", "x86"]}
  ],
  "companions": [
    {"id": "rootfs-term",
     "gradle_task": ":rootfs-lib:assembleRelease",
     "apk_glob": "rootfs-lib/build/outputs/apk/release/*.apk",
     "asset": "Cloud-Lib-Rootfs-Term.apk",
     "assets": {"arm64": "Cloud-Lib-Rootfs-Term.apk",
                "x86_64": "Cloud-Lib-Rootfs-Term-x86_64.apk"},
     "paths_from": ["ac_cloud-term/rootfs", "ac_cloud-term/rootfs-lib"],
     "package": "com.diegonmarcos.cloudlib.rootfsterm"}
  ]}}
JSON
    cat > "$root/ac_cloud-term/rootfs/rootfs.json" <<'JSON'
{"artifact":{"repo":"owner/repo","tag":"latest",
  "asset":"term-rootfs-{id}-{abi}.tar.zst",
  "url":"https://github.com/{repo}/releases/download/{tag}/{asset}",
  "identity_files":["rootfs/rootfs.json","rootfs/build-rootfs.sh"],
  "digest_asset":"rootfs.sha256","url_asset":"rootfs.url"}}
JSON
    printf '#!/bin/sh\necho build\n' > "$root/ac_cloud-term/rootfs/build-rootfs.sh"
    cat > "$root/ac_cloud-term/fleet-lib.json" <<'JSON'
{"module":"rootfs-term","kind":"lib","companion":"rootfs-term",
 "declared_in":"rootfs/rootfs.json","at":"artifact",
 "what":"A synthetic root filesystem, installed by the store as a library."}
JSON
    # The app reads the payload out of the installed sibling APK. No HTTP: the
    # store downloads the library, so a second transport here would be a path
    # nobody exercises and nobody audits.
    cat > "$root/ac_cloud-term/app/src/main/java/term/Rootfs.java" <<'JAVA'
package term;
final class Rootfs {
    static String trusted(android.content.pm.PackageManager pm, String self, String lib) throws Exception {
        if (pm.checkSignatures(self, lib) != android.content.pm.PackageManager.SIGNATURE_MATCH)
            throw new java.io.IOException(lib + " is signed with a different key");
        return pm.getApplicationInfo(lib, 0).sourceDir;
    }
}
JAVA
    # The gradle half of the ONE content-address rule. The guard recomputes that
    # address in python to version the fleet row, so it also asserts the gradle it
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

# The manifest is written from the guard's OWN --emit-rootfs-libs, exactly as
# aa_cloud-superapp/data/regen.sh writes the real one. A hardcoded version would
# be a second content address to keep in step, and this harness would then be
# testing the fixture instead of the tree.
#
# The rows land in `apps`, under the group whose default_for_kind is "lib" —
# which is the entire #628 change, so the healthy fixture has to be built that
# way or every case below would be measuring the wrong tree.
write_manifest() {
    local root="$1" rows
    rows="$(python3 "$GUARD" --root "$root" --emit-rootfs-libs 2>/dev/null)" || rows='[]'
    python3 - "$root" "$rows" <<'PY'
import json, sys
root, rows = sys.argv[1], json.loads(sys.argv[2])
def row(r):
    out = {k: v for k, v in r.items() if k not in ("module", "app_dir", "what")}
    out["release_url"] = "https://example.invalid/download/latest/" + r["asset"]
    out["repo_url"] = "https://example.invalid/tree/main/" + r["app_dir"]
    out["ghcr_page"] = "https://example.invalid/pkgs/container/" + r["image"]
    out["group"] = "libs"
    return out
json.dump({
    "version": 1,
    "groups": [
        {"id": "apps", "label": "Apps", "default_for_kind": "app", "blurb": "",
         "members": ["cloud-mail", "cloud-drive"]},
        {"id": "libs", "label": "Libs", "default_for_kind": "lib", "blurb": "",
         "members": [r["id"] for r in rows]},
    ],
    "apps": [{"id": "cloud-mail"}, {"id": "cloud-drive"}] + [row(r) for r in rows],
    "catalogue": [],
}, open(root + "/aa_cloud-superapp/data/constellation-fleet.json", "w"))
PY
}

# $1 = human description, $2 = expected exit status, $3 = mutation function name,
# $4 (optional) = a substring the guard's own output MUST contain.
#
# $4 is not decoration. A mutated tree is broken in one way and a guard that
# exits 1 for some OTHER reason satisfies an exit-status-only assertion while
# proving nothing about the property under test — that is how four assertions on
# #628 Slice 1 came to pass for the wrong reason. Requiring the guard to NAME
# what it caught is what makes each case measure its own mutation, and it is why
# every case below that covers a #628 property carries one.
case_is() {
    local description="$1" expected="$2" mutate="$3" names="${4:-}"
    local root; root="$(mktemp -d)"
    scaffold "$root"
    "$mutate" "$root"
    local output; output="$(python3 "$GUARD" --root "$root" 2>&1)"
    local actual=$?
    local verdict="ok" why=""
    if [ "$actual" != "$expected" ]; then
        verdict="FAIL"; why="expected exit $expected, got $actual"
    elif [ -n "$names" ] && [ "$expected" != "0" ]; then
        case "$output" in
            *"$names"*) ;;
            *) verdict="FAIL"; why="exited $actual but never mentioned $(printf '%q' "$names") — it went red for some other reason" ;;
        esac
    fi
    if [ "$verdict" = "ok" ]; then
        PASS=$((PASS + 1))
        printf 'ok    %s (exit %s)\n' "$description" "$actual"
    else
        FAIL=$((FAIL + 1))
        printf 'FAIL  %s — %s\n' "$description" "$why"
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

# ── #628 mutations ───────────────────────────────────────────────────────────
# Every mutant is cut with `jq del(...)` / a python edit of the parsed document,
# NEVER with bash ${var/pat/} substitution: a pattern containing `/` or `[...]`
# silently fails to match and hands the assertion an UNMUTATED fixture, which
# then passes for the wrong reason. That is the same fail-open shape these
# testers exist to catch, so it is not used to build them.
jq_edit() { local file="$1" filter="$2"; jq "$filter" "$file" > "$file.tmp" && mv "$file.tmp" "$file"; }
fleet_edit() {    # $1 = root, $2 = python body operating on `doc`
    python3 - "$1" "$2" <<'PY'
import json, sys
path = sys.argv[1] + "/aa_cloud-superapp/data/constellation-fleet.json"
doc = json.load(open(path))
exec(sys.argv[2])
json.dump(doc, open(path, "w"))
PY
}

# A payload published with no fleet identity at all: the state #618 left the two
# terminals in, and the whole reason for this half of the guard.
undeclare_artifact()   { rm -f "$1/ac_cloud-term/fleet-lib.json"; }
# Declared, but regen.sh never ran — the library is missing from `apps`, so no
# install or update path can reach it.
drop_artifact_row()    { fleet_edit "$1" 'doc["apps"] = [a for a in doc["apps"] if not a["id"].startswith("lib-")]'; }
# A pin bump with a stale manifest: the row versions bytes nobody built.
bump_artifact_identity() {
    printf '#!/bin/sh\necho build differently\n' > "$1/ac_cloud-term/rootfs/build-rootfs.sh"
}
# The declaration aims at something that is not a published payload.
break_artifact_pointer() {
    printf '{"module":"rootfs-term","kind":"lib","companion":"rootfs-term","declared_in":"rootfs/rootfs.json","at":"nowhere","what":"x"}\n' \
        > "$1/ac_cloud-term/fleet-lib.json"
}
# The lib renamed away from #474's one canonical spelling.
rename_artifact_lib() { fleet_edit "$1" '
for a in doc["apps"]:
    if a["id"].startswith("lib-"): a["label"] = "Rootfs: term"'; }

# ── THE #628 INVERSION. Each of the seven below is GREEN against the previous ─
#    revision of this guard and RED against the current one, which is what makes
#    the inversion a fact about the tree rather than a claim in a comment.

# (1) #624's OWN SHAPE, put back: kind "artifact". That is the declaration that
#     produced a catalogue-only row with no package and no versionCode, which is
#     why the terminal had to download its own runtime at all.
revert_to_artifact_kind() { jq_edit "$1/ac_cloud-term/fleet-lib.json" '.kind = "artifact"'; }

# (2) #624's OWN PLACEMENT, put back: the row in `catalogue` instead of `apps`.
#     Fleet.parse reads `apps`; a library the store can draw and not install is
#     the exact half-measure #628 exists to finish.
demote_library_to_catalogue() { fleet_edit "$1" '
libs = [a for a in doc["apps"] if a["id"].startswith("lib-")]
doc["apps"] = [a for a in doc["apps"] if not a["id"].startswith("lib-")]
doc["catalogue"] = [{"id": l["id"], "label": l["label"], "group": "libs",
                     "description": "", "installable": False,
                     "version": l["version_name"]} for l in libs]'; }

# (3) THE RUN-TIME DOWNLOAD PATH, put back. #618 left each terminal fetching its
#     own ~400 MB from a url baked into the APK; #628 says the store downloads it
#     and the app reads the installed sibling APK. A surviving HttpURLConnection
#     is a second transport, and the one nobody exercises is the one that rots.
reinstate_runtime_download() {
    cat > "$1/ac_cloud-term/app/src/main/java/term/Fetch.java" <<'JAVA'
package term;
final class Fetch {
    static void get(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.getInputStream();
    }
}
JAVA
}

# (4) THE TRUST CHECK, removed. Removing the network is only half of #628: the
#     app now unpacks ~400 MB of executables out of ANOTHER package's APK, and an
#     applicationId is not a credential — without a signature comparison any
#     package installed under that id would be unpacked and exec'd.
strip_the_trust_check() {
    python3 - "$1/ac_cloud-term/app/src/main/java/term/Rootfs.java" <<'PY'
import sys
path = sys.argv[1]
body = open(path).read()
open(path, "w").write("\n".join(
    line for line in body.split("\n") if "SIGNATURE_MATCH" not in line and "different key" not in line))
PY
}

# (5) A library nothing builds: the fleet-lib.json names a companion that
#     release.companions[] does not declare. The row would point at an APK no job
#     ever produces — a green pipeline and a permanently failing install.
orphan_the_companion() { jq_edit "$1/ac_cloud-term/fleet-lib.json" '.companion = "nobody-builds-this"'; }

# (6) The companion gated on nothing. Without paths_from the 437 MB library is
#     gated on the whole app's identity and gets republished by every unrelated
#     one-line code change — which is precisely the regression #618 removed and
#     that #628 must not walk back.
ungate_the_companion() { jq_edit "$1/ac_cloud-term/build.json" 'del(.release.companions[0].paths_from)'; }

# (7) A library that only LOOKS like one: an applicationId off the one prefix
#     every other lib APK derives from. "Consistency means follow the same
#     pattern" is the whole ticket, so a bespoke spelling is a failure.
offbrand_the_package() { jq_edit "$1/ac_cloud-term/build.json" '
    .release.companions[0].package = "cld.term.rootfs"'; }

# (8) Half the ABI dimension described and the other half silently wrong: the
#     manifest's per-ABI assets no longer match what the companion publishes, so
#     an x86_64 phone is handed the arm64 library and fails
#     INSTALL_FAILED_NO_MATCHING_ABIS.
break_per_abi_assets() { fleet_edit "$1" '
for a in doc["apps"]:
    if a["id"].startswith("lib-"): a["assets"] = {"arm64-v8a": a["asset"]}'; }

# (9) The library drawn anywhere but the Libs tab. It is a lib APK; it belongs
#     beside cloud-lib-cal, not in a tab of its own.
strand_the_library_group() { fleet_edit "$1" '
doc["groups"].append({"id": "runtimes", "label": "Runtimes", "blurb": "", "members": []})
for g in doc["groups"]:
    if g["id"] == "libs":
        g["members"] = [m for m in g["members"] if not m.startswith("lib-")]
    if g["id"] == "runtimes":
        g["members"] = [a["id"] for a in doc["apps"] if a["id"].startswith("lib-")]'; }

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
echo "A runtime payload is a real fleet LIBRARY the store installs (task #628)"
case_is "a payload published with NO fleet-lib.json is CAUGHT"        1 undeclare_artifact \
        "NO fleet identity"
case_is "a declared library missing from \`apps\` is CAUGHT"           1 drop_artifact_row \
        "lists it in no \`apps\` row"
case_is "a stale payload version in the manifest is CAUGHT"           1 bump_artifact_identity \
        "the manifest is stale"
case_is "a fleet-lib.json pointing at no payload is CAUGHT"           1 break_artifact_pointer \
        "resolves to no #618 artifact"
case_is "a library renamed off cloud-lib-{module} is CAUGHT"          1 rename_artifact_lib \
        "has label"

echo
echo "THE #628 INVERSION — the guard must NAME each of these, not merely go red"
case_is "#624's kind:\"artifact\" declaration is CAUGHT"               1 revert_to_artifact_kind \
        "declares kind 'artifact', not \"lib\""
case_is "#624's catalogue-only placement is CAUGHT"                   1 demote_library_to_catalogue \
        "is in aa_cloud-superapp/data/constellation-fleet.json's \`catalogue\`"
case_is "a reinstated run-time download path is CAUGHT"               1 reinstate_runtime_download \
        "opens an HTTP connection"
case_is "a stripped signature check is CAUGHT"                         1 strip_the_trust_check \
        "compares no signatures"
case_is "a companion nothing declares is CAUGHT"                      1 orphan_the_companion \
        "points at companion"
case_is "a companion gated on nothing (no paths_from) is CAUGHT"       1 ungate_the_companion \
        "declares no paths_from"
case_is "an off-prefix library applicationId is CAUGHT"                1 offbrand_the_package \
        "not the canonical"
case_is "per-ABI assets disagreeing with the companion is CAUGHT"      1 break_per_abi_assets \
        "INSTALL_FAILED_NO_MATCHING_ABIS"
case_is "a library drawn outside the Libs tab is CAUGHT"               1 strand_the_library_group \
        "is drawn in group"

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
