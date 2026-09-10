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

1. **Fork and de-cloud.** Vendor upstream at a pinned sha, patch out `:service`,
   `ai/`, and Firebase, rebrand, build. ~~Result is unambiguously MIT~~ — **this
   claim is withdrawn; see section 6.** De-clouding removes the `:service` EE
   reach but NOT the second one, through Rust. Cost: a maintained patch set that
   must be re-applied on every upstream bump, and no AI chat.
2. **Build upstream as-is.** Requires resolving the EE-vs-MPL-2.0 reading of
   `packages/backend/server/src/schema.gql`, and obtaining or synthesising a
   `google-services.json`. Keeps AI chat. Carries the licence risk and a Google
   dependency on a de-Googled phone.
3. **Do not ship an APK.** AFFiNE's web app is MIT and works in a browser; the
   fleet already has an edge. Cheapest by far, no build tier, no fork.

Option 1 is the one that matches what the owner actually asked for — an app they
own, rebranded, on a de-Googled phone. **It is not yet lawful as written.**
Section 6 records the blocker found on 2026-09-10 and the two ways out; neither
is an agent's call.


---

## 6. AMENDMENT 2026-09-10 — the EE boundary is tangled a SECOND way, through Rust

Recorded while executing the "build it de-clouded" decision. The de-cloud work
was **stopped before any `ac_cloud-notes/` directory, registration or tile change
was made**, per the brief's instruction to stop if the MIT/EE boundary proved
more tangled than section 1 described. It is.

### The finding

Section 1 found one reach into an EE directory: `:service` → `packages/backend/
server/src/schema.gql`. There is a **second, independent one**, and dropping
`:service`, `ai/` and Firebase does nothing about it:

```
packages/frontend/mobile-native/Cargo.toml:20
    affine_common  = { workspace = true, features = ["hashcash"] }
Cargo.toml (root workspace.dependencies)
    affine_common = { path = "./packages/common/native" }     <-- EE directory
packages/frontend/mobile-native/src/lib.rs:13
    use affine_common::hashcash::Stamp;                        <-- unconditional
packages/frontend/mobile-native/src/lib.rs:27-28
    pub fn hashcash_mint(resource: String, bits: u32) -> String {
      Stamp::mint(resource, Some(bits)).format()
```

`packages/common/native` is one of the **two** directories the root `LICENSE`
carves out to the EE licence — the other being `packages/backend`. Its
`LICENSE` is byte-identical to the backend's:

```
$ md5sum packages/common/native/LICENSE packages/backend/server/LICENSE
866b716b3d90fbfdea30e53a6a532302  packages/common/native/LICENSE
866b716b3d90fbfdea30e53a6a532302  packages/backend/server/LICENSE
```

`affine_mobile_native` is the crate the Gradle `cargo {}` block cross-compiles
into `libaffine_mobile_native.so` and packages into the APK. So **EE-licensed
Rust is statically linked into the artefact we would redistribute**, and the
EE licence says: "it is forbidden to copy, merge, publish, distribute,
sublicense, and/or sell the Software."

### Why this is worse than the `schema.gql` question, not the same

Section 1 noted that the EE licence carves *back* to MPL-2.0 anything "served
client-side as an image, font, cascading stylesheet (CSS), file which produces
or is compiled, arranged, augmented, or combined into client-side JavaScript"
(`packages/common/native/LICENSE:27-31`). That carve-back gave `schema.gql` an
arguable MPL-2.0 reading. **It does not reach this one.** A native ARM shared
object statically linked into an APK is not client-side JavaScript and is not
"distributed as part of AFFiNE CE". There is no second reading here.

Note also that the carve-back would not have saved the clone pattern anyway:
this fleet *vendors* upstream source into `cloud-u-android`. Committing
`packages/common/native/` is itself a "copy", forbidden independently of what
the binary contains.

### Why the section-1 method missed it, and the check that catches it

The Android app's JavaScript workspace closure is clean — verified by walking
every `package.json` dependency edge from `@affine/android`:

```
ANDROID APP PACKAGE: @affine/android
workspace packages reachable: 92
EE-directory packages in closure: NONE
```

That result is **true and misleading**. `packages/common/native` has no
`package.json` at all — it is a pure Cargo crate. It is invisible to any
JS-level audit. The Android build reaches EE code through three different
build systems and an audit must cover all three:

| Build system | Reach into an EE directory | Removed by de-clouding? |
|---|---|---|
| Gradle/Apollo | `App/service/build.gradle:16` → `backend/server/src/schema.gql` | yes — drop `:service` |
| Cargo | `mobile-native/Cargo.toml:20` → `common/native` | **no** |
| Yarn/JS | none (92-package closure, clean) | n/a |

That table is the complete set. It is the output of grepping the entire Android
build tree for every reference to either EE directory:

```
$ grep -rn 'backend/server\|backend/native\|common/native\|affine_common\|@affine/server' \
      packages/frontend/apps/android/ packages/frontend/mobile-native/
packages/frontend/apps/android/App/service/build.gradle:16:  schemaFiles.from(".../backend/server/src/schema.gql")
packages/frontend/mobile-native/Cargo.toml:20:              affine_common  = { workspace = true, features = ["hashcash"] }
packages/frontend/mobile-native/src/lib.rs:13:              use affine_common::hashcash::Stamp;
```

Three hits, two reaches, no others.

### The good news: the EE surface is one function, and it is a cloud surface

Everything EE that reaches the APK is `hashcash::Stamp` — 175 lines in
`packages/common/native/src/hashcash.rs` — surfaced as exactly one uniffi
export, `hashcash_mint`, bridged by `plugin/HashCashPlugin.kt` and consumed at
one place in the MIT frontend:

```
packages/frontend/apps/android/src/app.tsx:180-184
    framework.impl(ValidatorProvider, {
      async validate(_challenge, resource) {
        const res = await HashCash.hash({ challenge: resource });
```

`ValidatorProvider` is the proof-of-work challenge the client solves for the
**AFFiNE cloud** auth endpoint. On a local-first build with no server and an
unreachable sign-in wall (section 3), it is dead weight — the same category as
`:service` and `ai/`. **The de-cloud principle does extend to it.** What it
does not do is extend by itself: section 1's patch list does not touch Rust.

### The two ways out — owner's call, not an agent's

1. **Extend the de-cloud into the Rust crate.** Drop the `affine_common`
   dependency from `mobile-native/Cargo.toml`, drop `use ... Stamp`, drop
   `hashcash_mint`, drop `HashCashPlugin.kt`, and stub `ValidatorProvider` in
   `app.tsx` — **and then patch `affine_nbstore` as well**, which the first
   draft of this section said was unnecessary. See the correction below.

> **CORRECTION, 2026-09-10, on wiring the guard into CI.** The sentence this
> section used to carry — *"the SQLite store (`affine_nbstore`) does not —
> `nbstore`'s own `affine_common` dependency is behind its `napi` feature"* —
> is wrong, and it is wrong in the direction that matters.
>
> `packages/frontend/native/nbstore/Cargo.toml:16` reads
> `affine_common = { workspace = true, features = ["napi"] }`, in plain
> `[dependencies]`. The `napi = ["affine_common/napi"]` line in `[features]`
> **adds a feature to that dependency; it does not gate it.** Only
> `optional = true` would. So `affine_common` is an unconditional edge of
> `affine_nbstore`, `affine_mobile_native` depends on `affine_nbstore`
> unconditionally, and `packages/common/native` therefore stays in the APK's
> Cargo build graph after every step of option 1 above. The tree will not even
> configure without that EE directory on disk.
>
> The scan that produced "three hits, two reaches, no others" could not have
> seen this: it grepped `packages/frontend/apps/android/` and
> `packages/frontend/mobile-native/`, and the nbstore crate is under neither.
> `affine_nbstore` is a *crate name*; the directory it resolves to is
> `packages/frontend/native/nbstore`, three levels away. That is the same trap
> as `affine_common` itself, one layer further out — the fourth spelling of the
> same boundary.
>
> Two things are NOT claimed here. The symbol use
> (`nbstore/src/lib.rs:12`, `use affine_common::napi_utils::to_napi_error`) sits
> behind `#[cfg(not(feature = "use-as-lib"))]`, and the Android build **does**
> set `use-as-lib`, so that import is compiled out on the Android path. And
> with `lto = "fat"`, dead-code elimination may well leave no EE bytes in the
> stripped `.so`. What survives regardless is the part the licence actually
> speaks to: the EE-licensed **source** must be vendored into a repository we
> publish for the build to run at all, and the EE licence forbids copying and
> distributing, not just linking. Whether any EE machine code reaches the
> handset is an optimiser question; whether we copied EE source is not.
>
> Practical effect: option 1 is still the right shape, it is just one patch
> larger — `nbstore/Cargo.toml` must drop `affine_common` and
> `nbstore/src/lib.rs` must lose the `to_napi_error` import and its
> `From<error::Error> for napi::Error` impl (both already dead on Android).
> `licence-boundaries.json` now lists `packages/frontend/native/nbstore` as a
> scan root, and the guard's case 2 asserts that a de-clouded-but-unpatched
> tree still FAILS. Before that scan root was added, the guard reported that
> tree CLEAN.
2. **Re-implement hashcash clean-room.** Hashcash is Adam Back's 1997
   proof-of-work, published and unpatented; a fresh implementation owned by us
   is lawful. It must be genuinely clean-room — written without reference to
   `packages/common/native/src/hashcash.rs`. Only worth it if the cloud auth
   path is ever wanted, which today it is not.

Option 1 is smaller, and is the one consistent with "no server, ever". Both
change the project's legal posture, which is why neither was taken unilaterally.

### What was deliberately NOT done, and why

Nothing was created or wired. No `ac_cloud-notes/`, no `build.json`, no ship
workflow, no `ui.external_apps` entry, no launcher tile change. Registering an
app that must not be published would produce this fleet's two documented
failure modes at once: a false-green ship workflow, and a Data Apps tile whose
`extapp:` miss path calls `Updater.installApk` against a release asset that
404s (`ShellActivity.kt:1665-1674`). An unresolved app is worse than an absent
one — the same reasoning that kept section 1 from scaffolding.

**The Obsidian tile was left alone**, and would have been safe to change: `md.obsidian`
is reachable from five places in `aa_cloud-superapp/build.json`, only one of
which is the Data Apps tile — the right-edge one-hand gesture (`:492`), the
gesture action-picker vocabulary (`:525`), the star/radial menu (`:575`), the
Data Apps *launcher folder* package list (`:2762`), and the productivity
taxonomy rule (`:5159`). Retargeting the tile at `:2491` would not have cost
the owner access to Obsidian. It would only have created a dead target.

### Verified while here, for whoever picks this up

- **Dropping Firebase genuinely removes the `google-services.json`
  requirement**, rather than hiding it. The file has exactly one consumer: the
  `com.google.gms.google-services` Gradle plugin, declared at
  `App/build.gradle:15` (classpath), `App/build.gradle:27` (`apply false`) and
  `App/app/build.gradle:12` (`alias libs.plugins.google.service`), resolving
  through `libs.versions.toml:106`. No Kotlin file and no manifest entry reads
  it. The Firebase SDKs consume the string resources that plugin *generates*,
  which is why the three `firebase-*` dependencies must go with it — removing
  the plugin alone would leave SDKs that fail at `FirebaseApp` init instead of
  at configure time. Remove plugin + deps and the requirement is gone at its
  source.
- **`service/` the Kotlin package is not `:service` the Gradle module**, and
  section 5 blurs them. Only `service/GraphQLService.kt` imports the EE-derived
  `com.affine.pro.graphql.*`. `service/OkHttp.kt` (`AuthHttp`, `CookieStore`)
  has MIT-only consumers — `plugin/AuthPlugin.kt` and `utils/logger/FileTree.kt`
  — and must be kept, minus its two Firebase call sites. A patch that deletes
  the whole package will not compile.
- **ABI is already a single-ABI env switch**, not a matrix to invent:
  `App/app/build.gradle:19-20` reads `AFFINE_ANDROID_NATIVE_TARGET`
  (`arm64`|`x86_64`) into both `abiFilters` and the Cargo `targets`.
- **Flavours exist**: `flavorDimensions = ['chanel']`, products
  `stable`/`beta`/`internal`/`canary`, so the task is `assembleStableRelease`,
  not `assembleRelease` — the same flavour trap that cost `ac_cloud-dialer` a
  red CI run (see its `build.json::forks.dialer.build._doc`).
- **The floating NDK pin is real**: `App/app/build.gradle:25`,
  `ndkVersion = new File(sdkDirectory, "ndk").listFiles().sort().last().name`.

### The guard that makes this checkable, and the next step

Prose boundaries get read once. This one is now data plus a check:

- `1_cicd/src/data/licence-boundaries.json` — the policy: pinned revision,
  the two restricted directories, the scan roots, the markers, and the three
  known reaches with what each needs.
- `1_cicd/src/scripts/cloud-android-licence-boundary-guard.py` — scans a tree
  against that policy. No path, upstream or crate name in the script.
- `1_cicd/src/scripts/test/licence-boundary-guard.test.sh` — 4 cases, each
  breaking the tree one specific way.

Against real upstream at the pinned sha it reports all three reaches and exits
1. Against a tree with them removed it exits 0, so a green result is worth
something. Against a truncated checkout it exits 2 rather than reporting clean.

Two bugs were found by RUNNING it, not by reading it, and both are the
false-green shape this fleet keeps hitting:

1. The guard's first marker set used repo-root paths (`packages/backend`). The
   Gradle reach is written relative (`../../../../../backend/server/...`), so it
   matched nothing and the guard confidently reported 2 of 3. Markers are now
   directory tails.
2. The tester's first spelling of "does the output say `[NEW]`" was
   `case "$OUT" in *"[NEW]"*)`, where `[NEW]` is a bracket expression matching
   one of `N`/`E`/`W`. It failed against a guard that was behaving correctly.
   Replaced with plain substring containment.

**NEXT STEP, in order.** (1) Owner picks one of the two ways out above.
(2) Only then create `ac_cloud-notes/` on the clone-plus-patch-series shape —
`build.json` with `mode: "single-app"` and a real `forks.notes` entry, which is
what makes `aa_cloud-superapp/data/regen.sh` self-register it into
`constellation-fleet.json`; the patch series applied by `materialize-fork` from
the pinned sha, NOT a vendored 58-workspace tree. (3) Wire this guard into that
app's ship workflow as a gate before the Gradle step. The guard must be a gate
on the real materialised tree; running it only against the fixture proves
nothing about what ships.

**Step 3 no longer waits for steps 1 and 2.** As written above it could not
happen until `ac_cloud-notes/` existed, which is why the guard sat wired into
nothing for a week: the gate was scheduled to be installed by the same commit
it was supposed to gate. `1_cicd/src/cicd/licence-guard.yml` now runs on every
push to `main` and every pull request, with no path filter, and invokes the
guard as `--repo .`. In that mode the guard walks this repository for a tree
whose *shape* matches the upstream, rather than a configured path — because the
directory name is the owner's undecided choice, and the commit that creates it
is exactly the event that must not slip through. Today it finds nothing
vendored and says so in those words; the day a tree lands under any name at
all, it gates it. When `ac_cloud-notes/` does exist, add the ship-workflow gate
too: the repo-wide check catches vendoring, the ship gate catches the
materialised tree at build time, and they are not the same event.
