# NOTICE — Cloud Notes

Cloud Notes is a modified redistribution of **Markor**.

- **Upstream project:** Markor — <https://github.com/gsantner/markor>
- **Upstream author:** Copyright 2017–2025 Gregor Santner and the Markor
  contributors (see `CONTRIBUTORS.md`, preserved verbatim)
- **Upstream licence:** Apache License, Version 2.0 (see `LICENSE.txt`,
  preserved verbatim). Localisation files (`string*.xml`) and the bundled
  samples are CC0-1.0 per upstream's README, preserved as `UPSTREAM-README.md`.
- **Vendored from commit:** `8d657fd20fff71719d782a3bc375c6f982d09b19`
  (authored 2026-08-24; `versionCode 163` / `versionName 2.16.1`)
- **Vendored on:** 2026-09-11

This tree is **owned**, not tracked. It was copied in once and is edited
directly; there is no upstream sync, no overlay patch and no regeneration step.
`build.json::forks.notes.upstream_repo` and `pinned_commit` are provenance only.

## Statement of changes (Apache-2.0 §4(b))

The following modifications were made to the upstream work:

1. **Package identity renamed** so this build coexists with an
   officially-signed Markor install on the same device, rather than colliding
   with it:
   - `net.gsantner.markor` → `com.diegonmarcos.cloudnotes`
   - `net.gsantner.opoc` → `com.diegonmarcos.cloudnotes.opoc`
   - source directories `app/src/{main,test}/java/net/gsantner/{markor,opoc}`
     moved to `app/src/{main,test}/java/com/diegonmarcos/cloudnotes{,/opoc}`
   - `applicationId`, `namespace` and the `manifest_package_id` resValue in
     `app/build.gradle`
   - the `flavorAtest` applicationId `net.gsantner.markor_test` →
     `com.diegonmarcos.cloudnotes_test`

   173 files were rewritten in one pass. The `FileProvider` authority required
   no edit: `AndroidManifest.xml` declares `${applicationId}.provider`, a
   manifest placeholder, so it followed the rename automatically.

2. **Application name rebranded** to "Cloud Notes" via
   `app/src/main/res/values/string-not_translatable.xml::app_name_real`.

3. **Not vendored:** the upstream `doc/` and `metadata/` directories (the
   F-Droid store listing and the README's presentation GIFs). No Gradle task
   reads them.

4. **Added** (not part of the upstream work): `build.json`, `build.sh`,
   `NOTICE.md`, `test/`, and this repository's CI wiring.

No change was made to the licensing of the upstream code. `LICENSE.txt`,
`UPSTREAM-README.md` and `CONTRIBUTORS.md` are excluded from all rewrites by
design, and `test/test-cloud-notes-identity.sh` (assertion N4) fails the build
if that stops being true.
