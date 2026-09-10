# Cloud Notes (AFFiNE) — feasibility, and why nothing was shipped

Status: **BLOCKED, awaiting owner decision.** No `ac_cloud-notes/` directory, no
`build.json`, no ship workflow, no Constellation registration was created. That
is deliberate: the fleet's own history is that a half-wired project produces a
false-green build or a 404 at install time, so an unresolved app is worse than
an absent one.

Investigated against upstream `toeverything/AFFiNE` at canary
`cfda4858d550c46ec1cd0b574801d7becdd6625b` (2026-09-09), release train
`v0.27.5`.

## 1. The licence is not uniformly MIT, and the exception lands inside the Android build

The owner's summary was "primarily MIT for the frontend/client code". *Primarily*
is load-bearing. Read from the actual files:

- **`LICENSE`** (repo root) — splits the repo explicitly:
  > All content that resides under the "packages/backend" and
  > "packages/common/native" directory of this repository, if that directory
  > exists, is licensed under the license defined in
  > "packages/backend/server/LICENSE". […] Content outside of the above
  > mentioned directories or restrictions above is available under the "MIT"
  > license as defined in "LICENSE-MIT".
- **`LICENSE-MIT`** — plain MIT, © 2022-present TOEVERYTHING PTE. LTD.
- **`packages/backend/server/LICENSE`** and **`packages/backend/native/LICENSE`**
  and **`packages/common/native/LICENSE`** — the *AFFiNE Enterprise Edition (EE)
  License*, a source-available term, not an open-source one:
  > Subject to the foregoing, it is forbidden to copy, merge, publish,
  > distribute, sublicense, and/or sell the Software.
  with a production-use gate on holding an AFFiNE EE subscription, and a partial
  carve-back:
  > Any part of this Software distributed as part of AFFiNE CE or is served
  > client-side as an image, font, cascading stylesheet (CSS), file which
  > produces or is compiled, arranged, augmented, or combined into client-side
  > JavaScript, in whole or in part, is copyrighted under the MPL2.0 license.

### What that permits

The parts we would actually want — the mobile web application
(`packages/frontend/apps/mobile`, `packages/frontend/apps/android/src`,
`packages/frontend/core`, `packages/frontend/mobile-native`,
`packages/common/graphql`, `blocksuite/`) — are **MIT**. MIT permits rebranding
and redistribution outright. What must be preserved is small and absolute: the
copyright notice and the MIT permission text, in every copy or substantial
portion. In practice that means shipping AFFiNE's `LICENSE-MIT` text and
copyright line inside the APK (an about/licences screen or a bundled asset), and
keeping it in our repo alongside any vendored source. Nothing obliges us to
publish our modifications.

### Why that is not the end of it

The Android app's `:service` Gradle module is a hard compile dependency of the
APK (`implementation project(':service')` in
`packages/frontend/apps/android/App/app/build.gradle`). It carries no source of
its own — only a `build.gradle`, which points Apollo's codegen at:

```groovy
apollo {
    service("affine") {
        srcDir("../../../../../common/graphql/src/graphql")
        schemaFiles.from("../../../../../backend/server/src/schema.gql")
```

Resolved from `packages/frontend/apps/android/App/service/`, that second path is
**`packages/backend/server/src/schema.gql`** — inside the directory the root
LICENSE carves out to the EE licence. (The first path is
`packages/common/graphql/src/graphql`, which is MIT and fine.)

So every Kotlin class in `:service` is generated from an EE-tree file, and those
classes are compiled into the APK we would redistribute. Under the EE text's own
carve-back the schema is arguably MPL-2.0 instead — AFFiNE CE ships a server, and
`schema.gql` is part of it — which *would* permit redistribution with notices and
per-file source availability. Either reading is non-MIT, and the two differ on
whether we may redistribute at all.

**This is the part to stop on.** It is not a lawyer's hypothetical about a
directory we would never touch; it is a build input of the artefact the owner
asked for. Resolving which reading applies is the owner's call, not an agent's.

## 2. There is no official Android APK to mirror. The mirror option does not exist.

Not a preference — a fact, checked three ways:

- **GitHub releases**: no `.apk` or `.aab` asset in any of the last 100
  releases. Assets are Linux/macOS desktop only (`.appimage`, `.deb`,
  `.flatpak`, `.dmg`, `.zip`) plus selfhost `docker-compose.yml`.
- **F-Droid**: `GET https://f-droid.org/api/v1/packages/app.affine.pro` and
  `.../pro.affine.app` both return `{"error":"NOT_FOUND"}` (HTTP 404).
- **Google Play**: the listing for `app.affine.pro` exists, and
  `capacitor.config.ts` sets `releaseType: 'AAB'` — Play-only, AAB not APK.
  Play is precisely what a de-Googled phone does not have.

So the Cloud Office (`ac_cloud-sheets`) mirror-and-pin pattern is not available
here even if we wanted it. Its `_doc` block explains the trade honestly and the
cost the fleet discovered: mirroring upstream's bytes means we cannot rebrand,
cannot add a menu item, cannot re-sign, and the app can never join
signature-gated CLOUD_EXEC flows. The owner asked to rebrand this one, which
would have ruled mirroring out regardless. **Build-from-source is the only
option** — the alternative lost because it does not exist.

## 3. The client IS usable local-first. No server is required, and none should be deployed.

Traced through the real code rather than assumed:

- `tools/utils/src/build-config.ts` — for `distribution === 'android'`,
  `isNative: true` and `isMobileEdition: true`.
- `packages/frontend/core/src/desktop/pages/index/index.tsx` (the mobile index
  route delegates to it) — `enableLocalWorkspace` is
  `c.features.includes(ServerFeature.LocalWorkspace) || BUILD_CONFIG.isNative`,
  so on Android it is true without ever contacting a server.
- With no workspaces and `enableLocalWorkspace`, it calls `createFirstAppData`,
  which is `buildShowcaseWorkspace(workspacesService, 'local', …)` in
  `packages/frontend/core/src/utils/first-app-data.ts` — a **local** flavour
  workspace seeded with the onboarding docs. The sign-in wall
  (`jumpToSignIn()`) is reached only when `!enableLocalWorkspace && !loggedIn`,
  which cannot happen on Android.
- `packages/frontend/core/src/modules/workspace-engine/index.ts` registers both
  `WorkspaceFlavoursProvider('LOCAL')` and `('CLOUD')`.
- `packages/frontend/apps/android/src/nbstore.worker.ts` binds `sqliteStorages`
  through `bindNativeDBApis` to the on-device Rust store, alongside
  `cloudStorages`. Local persistence is real SQLite on the phone.

**Therefore: one APK, no service.** No AFFiNE server should be deployed on
`oci-apps` for this. A self-hosted AFFiNE server (Postgres + Redis + the Node
server image) remains an available later step if the owner ever wants
cross-device sync, and is worth a separate scoped proposal at that point given
`oci-apps` has had load-shedding incidents — but it is not needed to ship notes.

## 4. The real build toolchain, and what it costs this CI

Establish before assuming — this is a Node/Rust/Gradle three-stage build, not
Cloud Office's NDK megabuild, but it is not a plain Gradle app either.

| Stage | Requirement | Pinned by |
|---|---|---|
| Web bundle | Node 22.23.2 (`engines: >=22.12.0 <23.0.0`) | `.nvmrc`, `package.json` |
| Package manager | **Yarn 4.18.0** (Berry, with vendored `.yarn/`) | `package.json` `packageManager` |
| Native store | **Rust 1.97.1** cross-compiled to Android via `mozilla-rust-android-gradle` 0.9.6 + uniffi, crate `packages/frontend/mobile-native`, `libname affine_mobile_native`, `apiLevel 28`, `profile release` | `rust-toolchain.toml`, `App/app/build.gradle` `cargo {}` |
| NDK | required for the Rust cross-compile; resolved at configure time as `new File(sdkDirectory, "ndk").listFiles().sort().last().name` — i.e. **whatever NDK happens to be installed on the runner**, unpinned | `App/app/build.gradle` |
| Native wrap | Capacitor 8.4.2 (`cap sync`) | `apps/android/package.json` |
| Gradle | **8.14.3**, **JDK 21**, AGP 8.13.2, Kotlin 2.2.20, compileSdk 36 / targetSdk 36 / minSdk 24 | `gradle-wrapper.properties`, `App/gradle/libs.versions.toml` |

Against the fleet's existing Android CI (`ship-cloud-news.yml` is
representative): it provisions **JDK 17**, **Gradle 8.10.2**, an Android SDK
without NDK, no Node, no Rust, and sets `timeout-minutes: 30`. Every one of
those is wrong for this app. None is *impossible* — the fleet already installs
Node in `1_cicd/src/actions/setup-deps/action.yml` and two workflows
(`ship-cloud-chat.yml`, `ship-cloud-ide.yml`) already touch Node/Rust — but the
honest cost is a new toolchain tier: Yarn 4 install over a ~58-workspace
monorepo, an rspack bundle of the full AFFiNE editor, a Rust NDK cross-compile,
then Gradle. Expect well past 30 minutes per ABI cold, and the arm64/x86_64
matrix doubles it. Tractable; not free.

Three things that would have to be pinned that upstream does not pin, because
this fleet has already been bitten by a rolling-tag hash race and by a network
fetch dying on an upstream hiccup:

1. **The upstream commit** — canary is a moving branch. Pin the sha, not `canary`.
2. **The NDK version** — `listFiles().sort().last()` silently takes whatever the
   runner image ships. That is a floating version resolved from the environment
   and must be replaced with an explicit pin.
3. **Yarn's network install** — must fail loudly, not fall back.

## 5. Two blockers beyond the licence, both requiring a maintained patch set

**Firebase / Google Play Services.** `App/app/build.gradle` applies
`com.google.gms.google-services` and `firebase.crashlytics`, and depends on
`firebase-analytics`, `firebase-crashlytics`, `firebase-storage`. The
`google-services.json` those need is **gitignored and absent from the
repository** (`App/app/.gitignore` line 3). The upstream build therefore cannot
be run as-is by anyone outside AFFiNE, and the target here is an explicitly
de-Googled phone. Firebase appears in 4 Kotlin files: `AFFiNEApp.kt`,
`service/OkHttp.kt`, `utils/logger/CrashlyticsTree.kt`, `utils/logger/FileTree.kt`.

**Rebranding.** `appId: 'app.affine.pro'` / `appName: 'AFFiNE'` in
`capacitor.config.ts`, `applicationId "app.affine.pro"` in `App/app/build.gradle`,
plus icons and the in-app product name. MIT permits all of it. The `namespace`
can stay `app.affine.pro` while `applicationId` moves to
`com.diegonmarcos.cloudnotes`, which keeps the patch small.

Note what these two have in common with the licence problem: Apollo/GraphQL
usage is confined to `service/GraphQLService.kt` and `ai/chat/ChatUiState.kt`,
and Firebase to the four files above. **Every EE-derived and Google-dependent
surface in this app is a cloud or telemetry feature.** For a local-first note
app the owner did not ask to connect to any server, dropping `:service`, the
`ai/` package and Firebase would leave a build that is pure MIT, de-Googled, and
free of the licence question entirely. That is the recommended path — but it is
a genuine fork with a maintained patch set over upstream, and it removes the AI
chat feature, which is a product decision the owner has not been asked to make.

## Options for the owner

1. **Fork and de-cloud (recommended).** Vendor upstream at a pinned sha, patch
   out `:service`, `ai/`, and Firebase, rebrand, build. Result is unambiguously
   MIT, works offline, no Google dependency, no server. Cost: a maintained patch
   set that must be re-applied on every upstream bump, and no AI chat.
2. **Build upstream as-is.** Requires resolving the EE-vs-MPL-2.0 reading of
   `packages/backend/server/src/schema.gql`, and obtaining or synthesising a
   `google-services.json`. Keeps AI chat. Carries the licence risk and a Google
   dependency on a de-Googled phone.
3. **Do not ship an APK.** AFFiNE's web app is MIT and works in a browser; the
   fleet already has an edge. Cheapest by far, no build tier, no fork.

Option 1 is the one that matches what the owner actually asked for — an app they
own, rebranded, on a de-Googled phone.
