# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-fleet-manifest-guard.py ───
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
"""

import argparse
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=None,
                        help="repository root (default: two levels above this script's directory)")
    args = parser.parse_args()

    root = args.root or os.path.abspath(
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))

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
