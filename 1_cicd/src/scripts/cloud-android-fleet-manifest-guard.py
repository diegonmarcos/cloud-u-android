#!/usr/bin/env python3
"""Fleet manifest guard — no app may bake an EMPTY constellation fleet.

WHAT IT HOLDS.  Every module that depends on `:libs:updater` must resolve a
real, non-empty `constellation-fleet.json` at build time, and `:libs:updater`
itself must not contain a path that quietly substitutes the empty string when
that file is missing.

WHY IT EXISTS (task #276).  `:libs:updater` used to read the manifest as
`file("${rootDir}/data/constellation-fleet.json")`.  `rootDir` is the CONSUMING
application's root, and only `aa_cloud-superapp` carries a `data/` directory —
so for ten of the eleven consumers that file did not exist and
`CONSTELLATION_FLEET_B64` was baked as the empty string.

That defect has no symptom.  An empty manifest draws an empty page that looks
exactly like a working one: no crash, no log line, no red build.  An
application that cannot see the fleet reports "nothing to update" in the very
same words as one that is genuinely up to date, which is why it survived from
the first companion application until it was found by accident while building
cloud-mail's Update page.

WHY A SCRIPT AND NOT A grep.  The property is not "this text appears".  It is
"every consumer RESOLVES a manifest that actually has applications in it",
which means walking the dependency declarations, repeating the resolution
order the Gradle file uses, and parsing the JSON at the end of it.  A grep
would pass the moment somebody re-added a fallback under a different name.

Exit status 0 = every consumer resolves a populated manifest.
Exit status 1 = at least one consumer would bake an empty or missing fleet.

ALSO HELD HERE (task #624).  #618 moved the two terminals' ~400 MB runtime
trees out of their APKs and onto the release beside them, addressed by content.
Mechanically right, and INVISIBLE: the fleet had no row, no name and no version
for either of them, so the constellation's own store could not say what the
phone was about to fetch.  A rootfs artifact is now a declared fleet lib like
everything else, and this guard holds the declaration closed at both ends —
an artifact with no `fleet-lib.json`, a `fleet-lib.json` pointing at nothing,
and a manifest whose row disagrees with the content address recomputed from the
tree all fail here.  `--emit-artifact-libs` is the SAME resolution, printed for
data/regen.sh, so the generator and the guard cannot hold different opinions.
"""

import argparse
import glob
import hashlib
import json
import os
import re
import sys

# The one canonical copy. Named here as well as in libs/updater/build.gradle so
# that moving it breaks this guard loudly instead of breaking auto-update
# silently, which is the whole failure mode being guarded against.
CANONICAL = "aa_cloud-superapp/data/constellation-fleet.json"
UPDATER_GRADLE = "ab_cloud-libs-shared/libs/updater/build.gradle"

# `project(':libs:updater')` / `project(":libs:updater")`, in a line that is not
# commented out. Gradle accepts either quote style and either dependency
# configuration, so match the coordinate rather than the whole statement.
DEPENDS = re.compile(r"""^[^/*]*project\(\s*['"]:libs:updater['"]\s*\)""")

# The defect itself: a ternary or elvis that hands back an empty string when the
# manifest is absent. This is what made the bug silent, so it is named directly.
EMPTY_FALLBACK = re.compile(r"""fleet\w*\s*=.*(\?|\?:).*["']{2}""")
# A bake of the manifest as ONE quoted literal: buildConfigField "String",
# "CONSTELLATION_FLEET_B64", "\"${...}\"". javac caps a single string constant at
# 65,535 UTF-8 bytes (JVMS 4.4.7); the manifest's base64 crossed that on
# 2026-09-24 (68,072 bytes at 72 entries) and every consumer's BuildConfig
# failed with "constant string too long". The bake has to be a NON-constant
# expression (parts joined at class init), whatever the manifest's size today.
SINGLE_CONSTANT_BAKE = re.compile(
    r"""buildConfigField\s*\(?\s*["']String["']\s*,\s*["']CONSTELLATION_FLEET_B64["']\s*,\s*["']\\?["']\$\{""")


def find_bakes(root):
    """Every build file that bakes CONSTELLATION_FLEET_B64, with the lines that do."""
    hits = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames
                       if d not in (".git", "build", ".gradle", "node_modules", "z_archive")]
        for name in filenames:
            if name not in ("build.gradle", "build.gradle.kts"):
                continue
            path = os.path.join(dirpath, name)
            try:
                with open(path, encoding="utf-8", errors="replace") as handle:
                    lines = handle.read().splitlines()
            except OSError:
                continue
            for number, line in enumerate(lines, 1):
                if line.lstrip().startswith("//"):
                    continue
                if SINGLE_CONSTANT_BAKE.search(line):
                    hits.append((path, number, line.strip()))
    return hits


def find_consumers(root):
    """Every build file that declares a dependency on :libs:updater."""
    hits = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames
                       if d not in (".git", "build", ".gradle", "node_modules")]
        for name in filenames:
            if name not in ("build.gradle", "build.gradle.kts"):
                continue
            path = os.path.join(dirpath, name)
            try:
                with open(path, encoding="utf-8", errors="replace") as handle:
                    lines = handle.read().splitlines()
            except OSError:
                continue
            if any(DEPENDS.match(line) for line in lines):
                hits.append(path)
    return sorted(hits)


def gradle_root_of(build_file, root):
    """The rootDir Gradle would use: the application directory under the repo.

    `ac_cloud-mail/app/build.gradle` -> `ac_cloud-mail`. A shared library such
    as `ab_cloud-libs-shared/libs/appstore` has no application root of its own;
    it is always built inside a consuming application, so it is reported as
    having no override and falls through to the canonical copy.
    """
    relative = os.path.relpath(build_file, root)
    return os.path.join(root, relative.split(os.sep)[0])


def resolve(build_file, root):
    """Repeat libs/updater/build.gradle's resolution order: override, then
    canonical. Returns (path, why) with path None when nothing resolves."""
    override = os.path.join(gradle_root_of(build_file, root),
                            "data", "constellation-fleet.json")
    if os.path.exists(override):
        return override, "own data/ override"
    canonical = os.path.join(root, CANONICAL)
    if os.path.exists(canonical):
        return canonical, "canonical copy"
    return None, "nothing resolved"


def application_count(path):
    """How many applications the manifest actually lists. A manifest that parses
    but holds nothing is the same outage as a missing one, so it is counted
    rather than merely opened."""
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    if isinstance(data, list):
        return len(data)
    for key in ("apps", "fleet", "applications"):
        if isinstance(data.get(key), list):
            return len(data[key])
    # An object keyed by application id is also a valid shape.
    return len([k for k in data if not k.startswith("_")])


# ── #624: runtime artifacts are fleet libs ──────────────────────────────────
#
# An app declares a content-addressed runtime artifact by carrying an `artifact`
# object with #618's shape. That SHAPE is the discovery rule, not a list of
# directories: a third terminal that publishes its tree the same way is found
# without editing this file, and — the point of the guard — cannot be published
# without a fleet identity.
ARTIFACT_SHAPE = ("identity_files", "digest_asset", "url_asset")
# The one file that says who an artifact is to the fleet. Its own file rather
# than a build.json key because ac_cloud-nix-on-droid/build.json is itself an
# identity_file: a fleet key there would re-address 370 MB of unchanged bytes.
FLEET_LIB = "fleet-lib.json"
# The content address, byte for byte as both app/build.gradle derive it: sha256
# over the per-file sha256 digests of identity_files, first 12 hex. Stated in
# three places now, so ARTIFACT_ID_GRADLE below fails loudly if either gradle
# stops agreeing with this one — a silent divergence would name the fleet row
# after an asset that does not exist.
ARTIFACT_ID_GRADLE = (
    re.compile(r"""getInstance\("SHA-256"\)\.digest\(f\.bytes\)"""),
    re.compile(r"""substring\(0,\s*12\)"""),
)
SKIP_DIRS = ("z_archive", ".git", ".github")


def _artifacts_in(blob, path=""):
    """Every `artifact` object of #618's shape inside `blob`, with its dotted path."""
    found = []
    if isinstance(blob, dict):
        for key, value in blob.items():
            here = "%s.%s" % (path, key) if path else key
            if key == "artifact" and isinstance(value, dict) \
                    and all(k in value for k in ARTIFACT_SHAPE):
                found.append((here, value))
            else:
                found.extend(_artifacts_in(value, here))
    return found


def _dig(blob, dotted):
    for part in dotted.split("."):
        if not isinstance(blob, dict) or part not in blob:
            return None
        blob = blob[part]
    return blob


def _content_address(app_dir, artifact):
    """sha256 over each identity file's sha256, first 12 hex. None if one is missing."""
    outer = hashlib.sha256()
    for rel in artifact["identity_files"]:
        target = os.path.join(app_dir, rel)
        if not os.path.isfile(target):
            return None
        with open(target, "rb") as handle:
            outer.update(hashlib.sha256(handle.read()).digest())
    return outer.hexdigest()[:12]


def _variant_ids(app_dir):
    """release.variants[].id — what {abi} in an asset name is filled with."""
    try:
        with open(os.path.join(app_dir, "build.json"), encoding="utf-8") as handle:
            declared = json.load(handle)
    except (OSError, ValueError):
        return []
    return [v["id"] for v in (declared.get("release", {}).get("variants") or [])
            if isinstance(v, dict) and v.get("id")]


def discover_artifacts(root):
    """[(app_dir_name, json_file, dotted_path, artifact)] over the whole tree.

    Looked for in each app directory's build.json and in its one-level-deep
    json (ac_cloud-termux/rootfs/rootfs.json), which is where the two live.
    """
    found = []
    for name in sorted(os.listdir(root)):
        app = os.path.join(root, name)
        if not os.path.isdir(app) or name in SKIP_DIRS or name.startswith("."):
            continue
        for candidate in sorted(glob.glob(os.path.join(app, "*.json"))
                                + glob.glob(os.path.join(app, "*", "*.json"))):
            if os.path.basename(candidate) == FLEET_LIB:
                continue
            try:
                with open(candidate, encoding="utf-8") as handle:
                    blob = json.load(handle)
            except (OSError, ValueError):
                continue
            for dotted, artifact in _artifacts_in(blob):
                found.append((name, candidate, dotted, artifact))
    return found


def artifact_libs(root):
    """(rows, failures): the fleet catalogue rows every declared runtime artifact
    is, and everything the declaration got wrong. ONE resolution, read by
    data/regen.sh (--emit-artifact-libs) and by the guard below."""
    rows, failures = [], []
    artifacts = discover_artifacts(root)
    claimed = set()

    for app, source, dotted, artifact in artifacts:
        decl_path = os.path.join(root, app, FLEET_LIB)
        shown = "%s::%s" % (os.path.relpath(source, root), dotted)
        if not os.path.isfile(decl_path):
            failures.append(
                "%s is a content-addressed runtime artifact with NO fleet identity.\n"
                "      #618 publishes it beside the APK; #624 says every shared thing here is a\n"
                "      declared lib. Add %s/%s (module, kind, declared_in, at, what) so it gets a\n"
                "      cloud-lib-{module} name, a version and a Constellation row." % (shown, app, FLEET_LIB))
            continue
        try:
            with open(decl_path, encoding="utf-8") as handle:
                decl = json.load(handle)
        except (OSError, ValueError) as error:
            failures.append("%s/%s does not parse: %s" % (app, FLEET_LIB, error))
            continue
        # The declaration POINTS at #618's artifact rather than restating it, so
        # the pointer has to land on this very object. A fleet-lib.json aiming
        # somewhere else is reported by the pass below, not silently accepted.
        pointed = os.path.join(root, app, decl.get("declared_in") or "build.json")
        if not os.path.isfile(pointed):
            continue
        with open(pointed, encoding="utf-8") as handle:
            if _dig(json.load(handle), decl.get("at") or "") != artifact:
                continue
        claimed.add(decl_path)

        module = decl.get("module")
        if not module:
            failures.append("%s/%s declares no `module` — there is no name to derive an id or a "
                            "label from (#474)." % (app, FLEET_LIB))
            continue
        address = _content_address(os.path.join(root, app), artifact)
        if address is None:
            failures.append("%s names identity_files that do not exist — %s/%s would be versioned "
                            "by nothing." % (shown, app, FLEET_LIB))
            continue
        assets = [artifact["asset"].replace("{id}", address).replace("{abi}", abi)
                  for abi in _variant_ids(os.path.join(root, app))] \
                 or [artifact["asset"].replace("{id}", address)]
        rows.append({
            "id": "lib-%s" % module,
            "label": "cloud-lib-%s" % module,
            "kind": decl.get("kind", "artifact"),
            "version": address,
            "description": "%s  ·  version %s — the content address of %s, so the version moves "
                           "when the declaration that builds the tree moves and never otherwise.  "
                           "·  %s on %s@%s  ·  fetched and extracted state on THIS device is not "
                           "known to this page: the terminal app owns it." % (
                               decl.get("what", "A runtime artifact fetched beside the APK."),
                               address, shown, ", ".join(assets),
                               artifact.get("repo", "?"), artifact.get("tag", "?")),
        })

        # The python above and the two gradle derivations must stay one rule.
        gradles = glob.glob(os.path.join(root, app, "**", "build.gradle"), recursive=True)
        if not any(all(pattern.search(open(g, encoding="utf-8", errors="replace").read())
                       for pattern in ARTIFACT_ID_GRADLE) for g in gradles):
            failures.append(
                "%s no longer derives its asset id as sha256-of-sha256s truncated to 12 hex.\n"
                "      This guard and data/regen.sh recompute that address to name the fleet row;\n"
                "      if the gradle rule changed, the row now points at an asset nobody uploads." % app)

    for app in sorted({a for a, _, _, _ in artifacts}):
        decl_path = os.path.join(root, app, FLEET_LIB)
        if os.path.isfile(decl_path) and decl_path not in claimed:
            failures.append("%s/%s's declared_in/at resolves to no #618 artifact — it names a fleet "
                            "lib for something that is not published." % (app, FLEET_LIB))

    duplicates = sorted({r["id"] for r in rows if [x["id"] for x in rows].count(r["id"]) > 1})
    if duplicates:
        failures.append("runtime artifact ids %s are declared more than once — one artifact is one "
                        "fleet lib." % duplicates)
    return sorted(rows, key=lambda r: r["id"]), failures


def check_artifact_libs(root):
    """Every declared runtime artifact is IN the committed manifest, current, and
    drawn in a declared group. A row that regen.sh never wrote is a lib the
    phone cannot see, which is the whole #624 complaint."""
    rows, failures = artifact_libs(root)
    manifest = os.path.join(root, CANONICAL)
    if not rows:
        failures.append("no runtime artifact resolves to a fleet lib — either #618's artifacts are "
                        "gone or this guard stopped finding them, and it would now prove nothing.")
        return failures
    try:
        with open(manifest, encoding="utf-8") as handle:
            fleet = json.load(handle)
    except (OSError, ValueError) as error:
        failures.append("%s does not parse: %s" % (CANONICAL, error))
        return failures
    catalogue = {row["id"]: row for row in fleet.get("catalogue", [])}
    members = {member for group in fleet.get("groups", []) for member in group.get("members", [])}
    for row in rows:
        present = catalogue.get(row["id"])
        if present is None:
            failures.append(
                "%s is declared as a fleet lib but %s has no catalogue row for it.\n"
                "      Run aa_cloud-superapp/data/regen.sh --constellation-only and commit the\n"
                "      result: an undeclared artifact is invisible in Constellation (#624)." % (row["id"], CANONICAL))
            continue
        if present.get("version") != row["version"]:
            failures.append("%s in %s is versioned %r but the tree's content address is %r — the "
                            "manifest is stale; regenerate it." % (
                                row["id"], CANONICAL, present.get("version"), row["version"]))
        if present.get("label") != row["label"]:
            failures.append("%s in %s is labelled %r, not the canonical cloud-lib-{module} %r (#474)."
                            % (row["id"], CANONICAL, present.get("label"), row["label"]))
        if present.get("installable") is not False:
            failures.append("%s in %s does not say installable:false. A 400 MB runtime tree must "
                            "never be offered as an APK install (#618)." % (row["id"], CANONICAL))
        if row["id"] in {app["id"] for app in fleet.get("apps", [])}:
            failures.append("%s is in %s's `apps`. Fleet.parse reads `apps`, so the updater would be "
                            "handed a 400 MB artifact as an installable package (#618/#624). It "
                            "belongs in `catalogue`." % (row["id"], CANONICAL))
        if row["id"] not in members:
            failures.append("%s is in no declared Constellation group, so it is drawn in no tab at "
                            "all." % row["id"])
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=None,
                        help="repository root (default: two levels above this script's directory)")
    parser.add_argument("--emit-artifact-libs", action="store_true",
                        help="print the fleet catalogue rows for every declared runtime artifact "
                             "(#624) as JSON, for aa_cloud-superapp/data/regen.sh")
    args = parser.parse_args()

    root = args.root or os.path.abspath(
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))

    # The generator's view of the same resolution. Emitted, never re-derived in
    # jq, so regen.sh and this guard cannot disagree about a lib's id or version.
    if args.emit_artifact_libs:
        rows, problems = artifact_libs(root)
        if problems:
            for line in problems:
                print(line, file=sys.stderr)
            return 1
        print(json.dumps(rows))
        return 0

    failures = []

    # 1. The engine must not be able to hand back an empty fleet at all.
    gradle = os.path.join(root, UPDATER_GRADLE)
    if not os.path.exists(gradle):
        print("FAIL   %s does not exist — nothing to guard." % UPDATER_GRADLE)
        return 1
    with open(gradle, encoding="utf-8") as handle:
        gradle_lines = handle.read().splitlines()
    for number, line in enumerate(gradle_lines, 1):
        if line.lstrip().startswith("//"):
            continue
        if EMPTY_FALLBACK.search(line):
            failures.append(
                "%s:%d hands back an empty fleet when the manifest is missing.\n"
                "      That is exactly the silent defect #276 removed: an empty manifest\n"
                "      is indistinguishable from a healthy one at runtime. Throw instead.\n"
                "      %s" % (UPDATER_GRADLE, number, line.strip()))
    if not any("throw new GradleException" in line for line in gradle_lines):
        failures.append(
            "%s no longer throws when the manifest is missing.\n"
            "      Without that throw a missing manifest is a green build and a\n"
            "      fleet-blind application." % UPDATER_GRADLE)

    # 2. Every consumer must resolve a populated manifest.
    for path, number, line in find_bakes(root):
        failures.append(
            "%s:%d bakes the manifest as ONE string constant.\n"
            "      javac refuses a constant over 65,535 bytes and the manifest's base64\n"
            "      passed that on 2026-09-24 — every consumer's BuildConfig then fails with\n"
            "      'constant string too long'. Bake it as String.join(\"\", new String[]{...})\n"
            "      parts, as libs/updater/build.gradle does.\n"
            "      %s" % (os.path.relpath(path, root), number, line))

    consumers = find_consumers(root)
    if not consumers:
        print("FAIL   no module depends on :libs:updater — this guard would prove nothing.")
        return 1

    for build_file in consumers:
        shown = os.path.relpath(build_file, root)
        path, why = resolve(build_file, root)
        if path is None:
            failures.append("%s resolves NO fleet manifest (%s)." % (shown, why))
            continue
        try:
            count = application_count(path)
        except (OSError, ValueError) as error:
            failures.append("%s resolves %s but it does not parse: %s"
                            % (shown, os.path.relpath(path, root), error))
            continue
        if count == 0:
            failures.append("%s resolves %s but it lists NO applications."
                            % (shown, os.path.relpath(path, root)))

    # 3. #624 — every content-addressed runtime artifact is a declared fleet lib,
    #    present in the manifest, current, not installable and drawn in a tab.
    failures.extend(check_artifact_libs(root))

    if failures:
        print("Fleet manifest guard — %d problem(s):\n" % len(failures))
        for line in failures:
            print("  " + line)
        print("\nSee task #276. :libs:updater bakes this manifest into")
        print("CONSTELLATION_FLEET_B64; when it is empty the unattended update pass")
        print("has no fleet to compare against and reports success having done nothing.")
        return 1

    print("Fleet manifest guard: %d consumer(s) checked, all resolve a populated fleet."
          % len(consumers))
    return 0


if __name__ == "__main__":
    sys.exit(main())
