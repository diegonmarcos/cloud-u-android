# Vendoring — Cloud Mail is a clone of Sterna Mail

Repo, commit, version and the excluded-paths list live in `build.json::upstream`.
They are build inputs, so they belong there and are not repeated here.

This file records the two things a JSON block cannot express: **which upstream
files we edited and why**, and **what the previous app left behind**.

## Clone, not fork

Every file under `app/`, `core/`, `libs/`, `gradle/` and every root `*.md` is
upstream's, byte-for-byte, except the edits listed below. Nothing is patched to
add a feature. That is the whole point: the next re-clone against a newer
upstream is a diff over a handful of known files, not a merge across a fork.

Anything you are tempted to add to the app source belongs upstream first.

## The edits, and why each one is forced

| File | Edit | Why it cannot be left alone |
|---|---|---|
| `build.gradle`, `settings.gradle` | Upstream's `.kts` pair rewritten in Groovy | The ship engine identifies a buildable app by `build.gradle` + `app/` (`cloud-mail-engine.sh`, `materialize-fork`), and `settings.gradle` must read `build.json` with `JsonSlurper` so the module graph exists once. The per-module scripts are untouched `.kts`. |
| `app/build.gradle.kts` | `applicationId` read from `build.json::forks.mail.app_id` | The engine's `identity-assert` compares the built APK against that key. A literal here could not make them agree, only fail a ship after the build. |
| `app/build.gradle.kts` | `versionCode` from `COMMS_BUILD_TIMESTAMP` | The app this replaces shipped versionCode ≈ 3.35M. Upstream's 174 is a downgrade on every install in the field, and Android refuses those without a word. |
| `app/build.gradle.kts` | test-app label | Product name, same reason as the strings below. |
| `app/src/main/res/values*/strings.xml` | "Sterna"/"Sterna Mail" → "Cloud Mail" | It is the product name, in nine locales. Scoped to `strings.xml`: `themes.xml` holds a style *named* `Theme.Sterna` that the manifest and Kotlin reference by identifier, and renaming an identifier is not rebranding. |
| `app/src/main/res/drawable/ic_launcher_{background,foreground}.xml`, `mipmap-anydpi-v26/ic_launcher{,_round}.xml` | Cloud icon family | Carried over from the previous tree unchanged — the envelope glyph and the shared cloud tick that put this app in the family alongside the superapp. Upstream's `ic_launcher_monochrome.xml` was deleted with its only reference; ours use the foreground as their own monochrome layer. |

`namespace` stays `app.sterna`. It is the `R`/`BuildConfig` package that every
source file and every `proguard-rules.pro` `-keep` names; changing it would mean
editing upstream code, which is exactly what a clone does not do.

## What the FairEmail tree left behind

Dropped, because it was upstream FairEmail or plumbing for it: the whole
`eu.faircode.email` source tree, the `colorpicker` and `openpgp-api` vendored
subprojects, FairEmail's own `.github/` CI, its `metadata/`, `screenshots/`,
`tutorials/`, `eml/`, `oauth/`, `decrypt/`, `privacy/`, `tools/`, `index.html`,
`FAQ.md`, `CHANGELOG.md` and the `fork-ci-archive/` patch pair.

Dropped, and worth naming because it was **ours**:

- **`CloudSync`, `JmapService`, `FragmentDialogJmap`, `CommsUpdateWorker`,
  `FragmentOptionsCommsAbout`, `WorkerFeedSync`** — Java patches against
  `eu.faircode.email`. There is no Java and no `eu.faircode.email` here. The
  JMAP one is the least painful loss: upstream is JMAP-first by design, so the
  capability is native rather than bolted on.
- **`:libs:analytics` / `:libs:core` / `:libs:devtools`** — shared modules,
  included by reference from `ab_cloud-libs-shared/`. They are Java on AGP 9;
  this tree is Kotlin/Compose on AGP 8.9, and `libs/` here is upstream's own
  directory, so the old `project(':libs').projectDir = ...` redirect would now
  collide with a real module. **Cloud Mail reports no telemetry until someone
  writes a Compose-side hook.** The endpoints survive in
  `build.json::analytics` with `enabled: false`.
- **`COMMS_GH_OWNER` / `COMMS_GH_REPO` / `COMMS_RELEASE_ASSET_NAME` /
  `COMMS_RSS_BASE`** — `-P` properties feeding the two workers above. Updates
  now come from the rolling GH release asset via Obtainium, as for the other
  apps.

## Carried over verbatim

`build.sh` (byte-identical to `1_cicd/src/scripts/cloud-mail-engine.sh` — the
parity gate goes red otherwise), `ship.yaml` (symlink), the `app_id`, the GHCR
and GH-release coordinates in `build.json`, and the launcher icon.

Signing needed no work: upstream's `signingConfigs` already reads a root
`keystore.properties` with the same four keys the engine writes
(`storeFile`/`storePassword`/`keyAlias`/`keyPassword`).
