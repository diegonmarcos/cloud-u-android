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

ALSO HELD HERE (tasks #624 -> #628).  #618 moved the two terminals' ~400 MB
runtime trees out of their APKs and onto the release beside them, addressed by
content.  #624 gave each one a fleet row, but only a CATALOGUE row: no package,
no versionCode, no signature, nothing the store could install — so the terminal
still downloaded its own runtime at first launch, from a url baked into the APK.

#628 finished it.  A rootfs is now a REAL LIBRARY, indistinguishable from
cloud-lib-cal: its own signed, versioned APK, published as a companion beside
the app's own (`build.json::release.companions[]`), listed in `apps` under Libs,
installed and updated by the Store, and read by the terminal out of the sibling
APK it finds through PackageManager.  THE STORE DOWNLOADS IT; THE TERMINAL NEVER
DOES.  This guard therefore now asserts the OPPOSITE of what it asserted for
#624, and that inversion is the point:

  * a rootfs lib must be in `apps`, NOT in `catalogue` — `apps` is what
    Fleet.parse reads, so `apps` is the only place the Store's install and
    update paths can be handed it at all;
  * it must be `kind: "lib"` and carry a real `package`, declared exactly once,
    by the companion entry that builds the APK;
  * it must be drawn in the group whose `default_for_kind` is "lib";
  * and NO run-time download path may survive in either terminal — an
    HttpURLConnection, a `java.net.URL` or a baked url asset anywhere in the
    app's sources is a second way for the payload to arrive, and #628 says
    there is exactly one.

`--emit-rootfs-libs` is the SAME resolution, printed for data/regen.sh, so the
generator and the guard cannot hold different opinions about a lib's identity.
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
# ── javac's 65,535-byte string-constant cap ──────────────────────────────────
# A bake as ONE quoted literal — buildConfigField "String", "X_B64", "\"${...}\""
# — is a CONSTANT_Utf8 entry, and JVMS 4.4.7 caps one at 65,535 bytes. Past that
# every consumer's BuildConfig fails to COMPILE with "constant string too long",
# which is javac talking about a generated file and naming nothing useful.
#
# THIS HAS NOW HAPPENED TWICE, on two different blobs, for the same reason:
#   #567  the fleet manifest    68,072 bytes at 72 entries   2026-09-24 (c866529e8)
#   #646  the L5 folder tree    65,624 bytes                 2026-09-29
# The second is the tell. The tree had 147 bytes of headroom (65,388 at 10000dcb7,
# green) and two new library directories added 236 (65,624 at a9e7383f4, red), so
# the thing that broke the build was ADDING A DIRECTORY — measured from git at
# both commits. Nothing asserted on the new directories; the blob simply grew.
#
# So the rule is not about any one constant: EVERY BLOB DERIVED FROM A SCAN OF THE
# REPOSITORY must be baked as a non-constant expression (parts joined at class
# init), whatever its size today, because its size is not something anyone edits
# deliberately. Declarations baked from a JSON block are NOT on this list: those
# only grow when someone edits the very file whose size they changed.
GROWING_B64 = (
    "CONSTELLATION_FLEET_B64",       # derived: every fleet member (#567)
    "UI_STACK_FOLDER_TREE_B64",      # derived: the repo's directory tree, L5 (#646)
    "UI_STACK_FOLDER_TREE_L4_B64",   # derived: the same tree, L4
    "UI_STACK_FOLDER_TREE_L3_B64",   # derived: the same tree, L3
    "UI_AST_TREE_B64",               # derived: a walk of files and their declarations
    "UI_ASM_TREE_B64",               # derived: the page/section map
)
SINGLE_CONSTANT_BAKE = re.compile(
    r"""buildConfigField\s*\(?\s*["']String["']\s*,\s*["'](%s)["']\s*,\s*["']\\?["']\$\{(\w+)\}"""
    % "|".join(GROWING_B64))
# ONE EXEMPTION, and it is about DERIVATION rather than about which app it is.
# Seven applications bake a PLACEHOLDER for the folder tree — `def stackTreeB64 =
# "—".bytes.encodeBase64()...`, four bytes — because only the superapp actually
# scans the repository. A placeholder is not derived data: it cannot grow, so
# requiring the chunked form there would be churn in seven files that removes no
# risk. The exemption is granted by the build file ASSIGNING THE VARIABLE A
# LITERAL, so the day one of those apps starts scanning for real, the assignment
# stops matching and the guard fires on it.
PLACEHOLDER_ASSIGN = re.compile(
    r"""^\s*def\s+%s\s*=\s*["'][^"']{0,8}["']\s*\.\s*bytes""")
# A chunked bake that chunks too coarsely is the same defect wearing the fix's
# clothes: String.join over 70,000-char parts still emits an over-cap constant.
CHUNK_STEP = re.compile(r"""\.step\(\s*(\d+)\s*\)""")
CONSTANT_UTF8_CAP = 65535
# ── #654 ONE chunking helper ──────────────────────────────────────────────────
# The chunked form was pasted inline three times — superapp app/build.gradle,
# libs:appstore, libs:updater — beside a bakeB64 only the superapp could reach.
# Copies of a workaround drift one at a time, and the copy nobody fixed is where
# the next over-cap constant lands. So the chunking lives in ONE script every
# baker applies, and any other gradle script that slices a blob (`.step(`) or
# emits the parts (`new String[]`) is a copy. Named here so that moving the
# helper fails this guard loudly: the moved file is no longer exempt.
BAKE_HELPER = "ab_cloud-libs-shared/libs/updater/bake-b64.gradle"
INLINE_CHUNK = re.compile(r"""new\s+String\s*\[\s*\]|\.step\(""")


def gradle_scripts(root):
    """(path, lines) of every gradle script — build files AND applied scripts, so
    the helper's own chunk step is checked like any build file's."""
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames
                       if d not in (".git", "build", ".gradle", "node_modules", "z_archive")]
        for name in filenames:
            if not name.endswith((".gradle", ".gradle.kts")):
                continue
            path = os.path.join(dirpath, name)
            try:
                with open(path, encoding="utf-8", errors="replace") as handle:
                    yield path, handle.read().splitlines()
            except OSError:
                continue


def find_inline_chunks(root):
    """Every gradle script other than BAKE_HELPER that chunks a blob itself."""
    helper = os.path.join(root, BAKE_HELPER)
    return [(path, number, line.strip())
            for path, lines in gradle_scripts(root) if path != helper
            for number, line in enumerate(lines, 1)
            if not line.lstrip().startswith("//") and INLINE_CHUNK.search(line)]


def find_bakes(root):
    """Every build file that bakes a GROWING_B64 constant as one literal."""
    hits = []
    for path, lines in gradle_scripts(root):
        for number, line in enumerate(lines, 1):
            if line.lstrip().startswith("//"):
                continue
            found = SINGLE_CONSTANT_BAKE.search(line)
            if found:
                placeholder = re.compile(PLACEHOLDER_ASSIGN.pattern % re.escape(found.group(2)))
                if not any(placeholder.match(other) for other in lines):
                    hits.append((path, number, found.group(1), line.strip()))
            # A chunk step at or over the cap is the defect wearing the fix's
            # clothes — String.join over 70,000-char parts is still one
            # over-cap constant per part. `.step()` in these build files is
            # only ever a blob being chunked.
            for step in CHUNK_STEP.finditer(line):
                if int(step.group(1)) >= CONSTANT_UTF8_CAP:
                    hits.append((path, number,
                                 "a blob in %s-char parts, each one still over the cap"
                                 % step.group(1), line.strip()))
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


# ── #628: a rootfs is a REAL LIBRARY the Store installs ──────────────────────
#
# An app declares a content-addressed runtime payload by carrying an `artifact`
# object with #618's shape. That SHAPE is the discovery rule, not a list of
# directories: a third terminal that publishes its tree the same way is found
# without editing this file, and — the point of the guard — cannot ship without
# being a declared, installable fleet library.
ARTIFACT_SHAPE = ("identity_files", "digest_asset", "url_asset")
# The one file that says who a payload is to the fleet. Its own file rather than
# a build.json key so the sibling terminals are read by one scan.
FLEET_LIB = "fleet-lib.json"
# The content address, byte for byte as both app/build.gradle derive it: sha256
# over the per-file sha256 digests of identity_files, first 12 hex. Stated in
# three places now, so ARTIFACT_ID_GRADLE below fails loudly if either gradle
# stops agreeing with this one — a silent divergence would version the fleet row
# after bytes nobody built.
ARTIFACT_ID_GRADLE = (
    re.compile(r"""getInstance\("SHA-256"\)\.digest\(f\.bytes\)"""),
    re.compile(r"""substring\(0,\s*12\)"""),
)
SKIP_DIRS = ("z_archive", ".git", ".github")

# THE #628 INVERSION, as a rule rather than a promise.  Before #628 each
# terminal fetched its own ~400 MB tree at first launch from a url baked into
# the APK. The Store installs it now, so every one of these in an app's sources
# is a SECOND way for the payload to arrive — and two transports means the one
# nobody audits is the one that runs. Matched per app, on the app's own sources.
RUNTIME_FETCH = (
    (re.compile(r"\bHttpURLConnection\b"),
     "opens an HTTP connection. The Store downloads the rootfs library; the "
     "terminal reads it out of the installed sibling APK and fetches nothing."),
    (re.compile(r"\bjava\.net\.URL\b|\bnew URL\("),
     "builds a URL. #628 leaves exactly one way the payload arrives, and it is "
     "not the network."),
)
RUNTIME_FETCH_SCAN = ("app/src/main/java", "app/src/main/kotlin")

# The other half of the same rule. Removing the network is only half of #628's
# safety: the app now unpacks ~400 MB of executables out of ANOTHER package's
# APK, and the only thing that makes those bytes as trustworthy as its own is
# that the sibling carries the same signing key. Without this check the id is all
# that is checked, and an id is not a credential — any package installed under it
# would be unpacked and exec'd. So the check is REQUIRED, not merely encouraged:
# a terminal that consumes a rootfs library and never compares signatures fails
# here the same way a leftover download path does.
TRUST_CHECK = re.compile(r"\bSIGNATURE_MATCH\b")


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


def _build_json(app_dir):
    try:
        with open(os.path.join(app_dir, "build.json"), encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return {}


def _variants(app_dir):
    """release.variants[] — one published APK per entry, per ABI."""
    declared = _build_json(app_dir).get("release", {}).get("variants") or []
    return [v for v in declared if isinstance(v, dict) and v.get("id")]


def _companion(app_dir, companion_id):
    """The release.companions[] entry that BUILDS this lib's APK, or None.

    Matched on == against the id as DATA. Never interpolated into a lookup: the
    ids are hyphenated and `.companions.rootfs-termux` is arithmetic on two
    undefined names, which resolves to nothing and reports success (#368)."""
    declared = _build_json(app_dir).get("release", {}).get("companions") or []
    for entry in declared:
        if isinstance(entry, dict) and entry.get("id") == companion_id:
            return entry
    return None


# The ONE naming rule every lib APK in the constellation already obeys, read
# from the declaration that owns it (ab_cloud-libs-shared/lib-apks/build.json::
# lib_apks) rather than restated here — a rootfs lib is spelled like cloud-lib-cal
# because it IS one, and a prefix change has to move both or neither.
LIB_APKS_DECL = "ab_cloud-libs-shared/lib-apks/build.json"


def _lib_naming(root):
    """(application_id_prefix, asset_prefix, image_prefix) for every lib APK."""
    declared = _build_json(os.path.join(root, "ab_cloud-libs-shared", "lib-apks"))
    libs = declared.get("lib_apks") or {}
    ghcr = (declared.get("release") or {}).get("ghcr") or {}
    return (libs.get("application_id_prefix"), libs.get("asset_prefix"),
            ghcr.get("image_prefix"))


def _asset_name(asset_prefix, module):
    """Cloud-Lib-Rootfs-Termux.apk — each '-' segment capitalised, exactly as
    ab_cloud-libs-shared/lib-apks/build.sh and data/regen.sh both name it."""
    return "%s%s.apk" % (asset_prefix,
                         "-".join(part[:1].upper() + part[1:] for part in module.split("-")))


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


def rootfs_libs(root):
    """(rows, failures): the fleet `apps` rows every declared runtime payload is,
    and everything the declaration got wrong. ONE resolution, read by
    aa_cloud-superapp/data/regen.sh (--emit-rootfs-libs) and by the guard below.

    The rows are shaped exactly like the Cloud-Lib-*.apk rows regen.sh emits for
    ab_cloud-libs-shared, minus the three url fields regen.sh owns the constants
    for. That sameness is the deliverable: the Store has no rootfs case."""
    rows, failures = [], []
    artifacts = discover_artifacts(root)
    claimed = set()
    id_prefix, asset_prefix, image_prefix = _lib_naming(root)
    if not (id_prefix and asset_prefix and image_prefix):
        failures.append(
            "%s no longer declares lib_apks.application_id_prefix / asset_prefix / "
            "release.ghcr.image_prefix.\n"
            "      Those three name EVERY lib APK in the fleet, rootfs libs included, so "
            "without them\n      a rootfs lib cannot be spelled the way every other library "
            "is (#474/#628)." % LIB_APKS_DECL)
        return [], failures

    for app, source, dotted, artifact in artifacts:
        app_dir = os.path.join(root, app)
        decl_path = os.path.join(app_dir, FLEET_LIB)
        shown = "%s::%s" % (os.path.relpath(source, root), dotted)
        if not os.path.isfile(decl_path):
            failures.append(
                "%s is a content-addressed runtime payload with NO fleet identity.\n"
                "      #628 says a payload a sibling app consumes is a LIBRARY: it gets its own\n"
                "      signed APK, a versionCode and a Store row like cloud-lib-cal. Add %s/%s\n"
                "      (module, kind:\"lib\", companion, declared_in, at, what)." % (shown, app, FLEET_LIB))
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
        pointed = os.path.join(app_dir, decl.get("declared_in") or "build.json")
        if not os.path.isfile(pointed):
            continue
        with open(pointed, encoding="utf-8") as handle:
            if _dig(json.load(handle), decl.get("at") or "") != artifact:
                continue
        claimed.add(decl_path)

        module = decl.get("module")
        if not module:
            failures.append("%s/%s declares no `module` — there is no name to derive an id, a "
                            "label, a package or an asset from (#474)." % (app, FLEET_LIB))
            continue

        # THE INVERSION. `kind: "artifact"` was #624's shape and it put the row
        # in `catalogue`, where Fleet.parse cannot see it — which is exactly why
        # the terminal had to fetch its own runtime. A rootfs is a lib now.
        kind = decl.get("kind")
        if kind != "lib":
            failures.append(
                "%s/%s declares kind %r, not \"lib\".\n"
                "      #624 emitted these as catalogue-only `artifact` rows: no package, no\n"
                "      versionCode, nothing the Store could install, so each terminal downloaded\n"
                "      its own ~400 MB runtime at first launch. #628 makes it a real library —\n"
                "      kind \"lib\" is what lands the row in `apps`, and `apps` is the only thing\n"
                "      Fleet.parse reads, so it is the only place an install path can reach it."
                % (app, FLEET_LIB, kind))
            continue

        # ONE declaration of the APK's identity: the companion entry that builds
        # it. A `package` in fleet-lib.json as well would be a second statement
        # of the one fact the built APK actually carries.
        companion_id = decl.get("companion")
        if not companion_id:
            failures.append(
                "%s/%s names no `companion`.\n"
                "      A library is an APK, and the APK is built and published by\n"
                "      build.json::release.companions[] — that entry owns the applicationId and\n"
                "      the per-ABI asset names, and this pointer is how the fleet row reaches\n"
                "      them without restating either." % (app, FLEET_LIB))
            continue
        companion = _companion(app_dir, companion_id)
        if companion is None:
            failures.append(
                "%s/%s points at companion %r, which %s/build.json::release.companions[] does\n"
                "      not declare. The fleet would offer a library nothing builds." % (
                    app, FLEET_LIB, companion_id, app))
            continue
        missing = [field for field in ("gradle_task", "apk_glob", "module_dir", "package")
                   if not companion.get(field)]
        if missing:
            failures.append(
                "%s/build.json::release.companions[%r] declares no %s.\n"
                "      Without gradle_task/apk_glob nothing builds or finds the APK; without\n"
                "      module_dir the wrapper module's own gradle and manifest -- bytes of the\n"
                "      library APK -- are outside its publish gate; without package the row\n"
                "      names no installable thing." % (app, companion_id, ", ".join(missing)))
            continue

        # #796: the publish gate's inputs are DERIVED -- this artifact's
        # identity_files (the content address the row's version_name is built
        # from) plus module_dir -- so the lib is rebuilt exactly when its content
        # address moves. #786 held a hand-kept paths_from to "cover" the identity
        # files; that caught the narrow side only, and the wide side is what
        # actually happened: termux gated on the whole rootfs/ directory and
        # republished 437 MB for every verify-script edit. A paths_from is now a
        # second declaration of the same inputs, and is refused as such.
        if "paths_from" in companion:
            failures.append(
                "%s/build.json::release.companions[%r] declares paths_from.\n"
                "      The gate's inputs are derived from %s::%s.identity_files plus module_dir\n"
                "      (#796); a hand-kept list is a second declaration of the same fact, and\n"
                "      the last one gated the 437 MB rootfs on verify scripts it never contained."
                % (app, companion_id, decl.get("declared_in") or "build.json", decl.get("at")))
            continue
        if not os.path.isdir(os.path.join(app_dir, companion["module_dir"])):
            failures.append(
                "%s/build.json::release.companions[%r].module_dir is %r, which is not a\n"
                "      directory under %s -- the gate would hash nothing for the wrapper module."
                % (app, companion_id, companion["module_dir"], app))
            continue

        package = companion["package"]
        canonical_package = "%s.%s" % (id_prefix, module.replace("-", ""))
        if package != canonical_package:
            failures.append(
                "%s/build.json::release.companions[%r].package is %r, not the canonical %r.\n"
                "      Every lib APK's applicationId is %s + the module name with dashes\n"
                "      stripped (%s). A rootfs lib spelled differently is a library that only\n"
                "      looks like one." % (app, companion_id, package, canonical_package,
                                           id_prefix, LIB_APKS_DECL))
            continue

        canonical_asset = _asset_name(asset_prefix, module)
        if companion.get("asset") != canonical_asset:
            failures.append(
                "%s/build.json::release.companions[%r].asset is %r, not the canonical %r\n"
                "      (%s::lib_apks.asset_prefix + the Title-Cased dashed module)." % (
                    app, companion_id, companion.get("asset"), canonical_asset, LIB_APKS_DECL))
            continue

        address = _content_address(app_dir, artifact)
        if address is None:
            failures.append("%s names identity_files that do not exist — %s/%s would be versioned "
                            "by nothing." % (shown, app, FLEET_LIB))
            continue

        # Per-ABI assets, keyed the way the updater matches Build.SUPPORTED_ABIS:
        # the companion names its APK per VARIANT (the engine reads
        # .assets[$CLOUDNAV_VARIANT]), and a variant covers several ABIs. Two
        # ABI jobs must not overwrite each other's library on the release.
        per_abi = {}
        for variant in _variants(app_dir):
            named = (companion.get("assets") or {}).get(variant["id"]) or companion["asset"]
            for abi in variant.get("supported_abis") or variant.get("abis") or [variant["id"]]:
                if isinstance(abi, str) and abi:
                    per_abi[abi] = named

        ghcr = (_build_json(app_dir).get("release") or {}).get("ghcr") or {}
        rows.append({
            "id": "lib-%s" % module,
            "label": "cloud-lib-%s" % module,
            "module": module,
            "app_dir": app,
            "package": package,
            "alt_id": None,
            "registry": ghcr.get("registry"),
            "namespace": ghcr.get("namespace"),
            "image": "%s%s" % (image_prefix, module),
            "tag": "latest",
            "asset": companion["asset"],
            "assets": per_abi,
            "blocked": False,
            "kind": "lib",
            # OUR version (#631), and for a payload addressed by content there is
            # no other honest one: it moves when the declaration that builds the
            # tree moves and never otherwise. No hand-maintained number to forget.
            "version_name": address,
            "what": decl.get("what", ""),
        })

        # The python above and the two gradle derivations must stay one rule.
        gradles = glob.glob(os.path.join(app_dir, "**", "build.gradle"), recursive=True)
        if not any(all(pattern.search(open(g, encoding="utf-8", errors="replace").read())
                       for pattern in ARTIFACT_ID_GRADLE) for g in gradles):
            failures.append(
                "%s no longer derives its version as sha256-of-sha256s truncated to 12 hex.\n"
                "      This guard and data/regen.sh recompute that address to version the fleet\n"
                "      row; if the gradle rule changed, the row versions bytes nobody built." % app)

        failures.extend(_one_transport(root, app, module))

    for app in sorted({a for a, _, _, _ in artifacts}):
        decl_path = os.path.join(root, app, FLEET_LIB)
        if os.path.isfile(decl_path) and decl_path not in claimed:
            failures.append("%s/%s's declared_in/at resolves to no #618 artifact — it names a fleet "
                            "lib for something that is not published." % (app, FLEET_LIB))

    duplicates = sorted({r["id"] for r in rows if [x["id"] for x in rows].count(r["id"]) > 1})
    if duplicates:
        failures.append("rootfs lib ids %s are declared more than once — one payload is one "
                        "library." % duplicates)
    return sorted(rows, key=lambda r: r["id"]), failures


def _one_transport(root, app, module):
    """Exactly ONE way the payload arrives, and it is verified on the way in.

    #628's whole claim is that the Store downloads the rootfs library and the app
    reads it out of the installed sibling APK. Two properties, both checked here
    because they are the same property from either side:

      * no SECOND transport survives — a leftover HttpURLConnection or URL is a
        path that still works, is no longer exercised by anyone, and is therefore
        the one that will rot unnoticed, so it is refused rather than deprecated;
      * the one transport that remains is TRUSTED — the sibling APK is only as
        good as its signature, and an applicationId is not a credential."""
    failures = []
    trusted = False
    for relative in RUNTIME_FETCH_SCAN:
        base = os.path.join(root, app, relative)
        if not os.path.isdir(base):
            continue
        for current, _dirs, files in os.walk(base):
            for name in sorted(files):
                if not name.endswith((".java", ".kt")):
                    continue
                path = os.path.join(current, name)
                with open(path, encoding="utf-8", errors="replace") as handle:
                    body = handle.read()
                if TRUST_CHECK.search(body):
                    trusted = True
                for pattern, why in RUNTIME_FETCH:
                    if pattern.search(body):
                        failures.append(
                            "%s still %s\n"
                            "      cloud-lib-%s is installed and updated by the Store like every\n"
                            "      other library; #628 leaves exactly ONE way the payload arrives."
                            % (os.path.relpath(path, root), why, module))
    if not trusted:
        failures.append(
            "%s consumes cloud-lib-%s but compares no signatures.\n"
            "      It is about to unpack and exec ~400 MB out of another package's APK, and the\n"
            "      applicationId alone does not say who built it — any package installed under\n"
            "      that id would be unpacked. PackageManager.checkSignatures(...) ==\n"
            "      SIGNATURE_MATCH is what makes the sibling part of this build (#348)."
            % (app, module))
    return failures


def check_rootfs_libs(root):
    """Every declared rootfs library is IN the manifest's `apps`, current, out of
    `catalogue`, and drawn in the Libs tab.

    Every assertion here is the INVERSE of the one #624 made, because #624's
    placement is the defect #628 fixes: a catalogue row has no package and no
    versionCode, so no install and no update path can be handed it, and the
    terminal was left downloading its own runtime."""
    rows, failures = rootfs_libs(root)
    manifest = os.path.join(root, CANONICAL)
    if not rows:
        failures.append("no runtime payload resolves to a fleet library — either #618's artifacts "
                        "are gone or this guard stopped finding them, and it would now prove "
                        "nothing.")
        return failures
    try:
        with open(manifest, encoding="utf-8") as handle:
            fleet = json.load(handle)
    except (OSError, ValueError) as error:
        failures.append("%s does not parse: %s" % (CANONICAL, error))
        return failures
    apps = {row["id"]: row for row in fleet.get("apps", [])}
    catalogue = {row["id"] for row in fleet.get("catalogue", [])}
    groups = fleet.get("groups", [])
    members = {member: group.get("id") for group in groups for member in group.get("members", [])}
    for row in rows:
        # The catalogue check comes FIRST and does not depend on the `apps` row,
        # because demoting a library back to #624's placement removes it from
        # `apps` and adds it to `catalogue` in one move. Checked the other way
        # round the guard reports only the vaguer "missing from apps" and the
        # specific regression — catalogue-only, therefore uninstallable — is
        # never named, which is a guard that is right for the wrong reason.
        if row["id"] in catalogue:
            failures.append(
                "%s is in %s's `catalogue`.\n"
                "      That is #624's placement and it is the defect: a catalogue row carries no\n"
                "      package and no versionCode, so the Store can draw it and nothing more —\n"
                "      which is why the terminal downloaded its own runtime. It belongs in\n"
                "      `apps`, and in exactly one of the two." % (row["id"], CANONICAL))
        present = apps.get(row["id"])
        if present is None:
            failures.append(
                "%s is a declared fleet library but %s lists it in no `apps` row.\n"
                "      Run aa_cloud-superapp/data/regen.sh --constellation-only and commit the\n"
                "      result. `apps` is what Fleet.parse reads, so a library missing from it\n"
                "      cannot be installed or updated by anything (#628)." % (row["id"], CANONICAL))
            continue
        for field in ("label", "package", "asset", "image", "kind"):
            if present.get(field) != row[field]:
                failures.append("%s in %s has %s %r; the declaration resolves %r." % (
                    row["id"], CANONICAL, field, present.get(field), row[field]))
        if present.get("version_name") != row["version_name"]:
            failures.append(
                "%s in %s is versioned %r but the tree's content address is %r — the manifest "
                "is stale; regenerate it." % (
                    row["id"], CANONICAL, present.get("version_name"), row["version_name"]))
        if present.get("assets") != row["assets"]:
            failures.append(
                "%s in %s carries per-ABI assets %r, not the %r its companion declares.\n"
                "      An x86_64 phone handed the arm64 library fails INSTALL_FAILED_NO_MATCHING_ABIS."
                % (row["id"], CANONICAL, present.get("assets"), row["assets"]))
        libs_group = next((g.get("id") for g in groups if g.get("default_for_kind") == "lib"), None)
        if libs_group is None:
            libs_group = "libs"
        if members.get(row["id"]) != libs_group:
            failures.append(
                "%s is drawn in group %r, not %r — a library belongs in the Libs tab beside every "
                "other lib APK, which is the whole of #628's 'follow the same pattern'." % (
                    row["id"], members.get(row["id"]), libs_group))
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=None,
                        help="repository root (default: two levels above this script's directory)")
    parser.add_argument("--emit-rootfs-libs", action="store_true",
                        help="print the fleet `apps` row for every declared runtime payload "
                             "(#628) as JSON, for aa_cloud-superapp/data/regen.sh")
    args = parser.parse_args()

    root = args.root or os.path.abspath(
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))

    # The generator's view of the same resolution. Emitted, never re-derived in
    # jq, so regen.sh and this guard cannot disagree about a lib's id or version.
    if args.emit_rootfs_libs:
        rows, problems = rootfs_libs(root)
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
    for path, number, what, line in find_bakes(root):
        failures.append(
            "%s:%d bakes %s as ONE string constant.\n"
            "      javac refuses a constant over %d bytes (JVMS 4.4.7, CONSTANT_Utf8), so\n"
            "      every consumer's BuildConfig then fails to COMPILE with 'constant string\n"
            "      too long' — in a step named for the tests, before a test runs.\n"
            "      This blob is DERIVED, so it grows when nobody edits it: the fleet manifest\n"
            "      crossed on 2026-09-24 at 68,072 bytes (#567), and the L5 folder tree crossed\n"
            "      on 2026-09-29 at 65,624 because #646 added two library DIRECTORIES to a\n"
            "      constant with 147 bytes left. Bake it with bakeB64(blob) from %s —\n"
            "      String.join(\"\", new String[]{...}) parts, not a constant expression, so no cap.\n"
            "      %s" % (os.path.relpath(path, root), number, what, CONSTANT_UTF8_CAP,
                          BAKE_HELPER, line))

    for path, number, line in find_inline_chunks(root):
        failures.append(
            "%s:%d chunks a blob inline.\n"
            "      The chunking lives in ONE place, %s: apply it and call\n"
            "      bakeB64(blob) (#654). It was pasted three times before, and a copy is where\n"
            "      the next over-cap constant lands — the one nobody remembered to fix.\n"
            "      %s" % (os.path.relpath(path, root), number, BAKE_HELPER, line))

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

    # 3. #628 — every content-addressed runtime payload is a declared fleet
    #    LIBRARY: in `apps`, out of `catalogue`, current, drawn in the Libs tab,
    #    and with no run-time download path left in the app that consumes it.
    failures.extend(check_rootfs_libs(root))

    if failures:
        print("Fleet manifest guard — %d problem(s):\n" % len(failures))
        # Under GitHub Actions each problem is also an annotation: the job log needs a token
        # the owner's phone does not hold, and the reason (which lib, which two addresses) is
        # what a fix needs.
        if os.environ.get("GITHUB_ACTIONS"):
            for line in failures:
                print("::error title=fleet-manifest::" + line.replace("\n", " ")[:900])
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
