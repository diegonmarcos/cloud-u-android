# Engine APK split — which shared libraries leave the apps that compile them

**Goal (interpreted).** A library that computes rather than draws should ship only as
its own Store-installed `Cloud-Lib-*.apk`, so an engine change republishes that one APK
instead of every app that links the module. Libraries that draw inside the host's
View/Compose tree stay compiled in. This page is the measured classification taken
before any module moved, and it records the decisions that move or keep each module.

## How this was measured (2026-10-01, origin/main `c2b4758a4`)

- **Size.** Every `Cloud-Lib-*.apk` on the rolling `latest` release, read through its
  zip central directory (HTTP range reads, no full download). `so64` = raw arm64
  native bytes; `dex` = raw dex bytes. The dex floor any lib APK carries (Kotlin
  stdlib + androidx core + `libs:core` + `libs:devtools`) is **5.5 MB raw**
  (`Cloud-Lib-Core.apk`); Compose-based libs carry ~49 MB raw of Compose that a
  Compose app already has. So the dex figure is an upper bound on what a lib adds to
  an app; native and asset bytes are exact.
- **Inside an app.** `Cloud-Drive.apk` (arm64, 93.65 MB) carries, compressed:
  `librclone.so` 29.05 MB, `libgh.so` 14.91 MB, `libgix.so` 9.92 MB,
  `libmlkit_google_ocr_pipeline.so` 4.69 MB. `Cloud-SuperApp.apk` (34.45 MB) carries
  `libgojni.so` (libs:firewall) 19.45 MB stored.
- **Who links what.** `project(':libs:…')` lines in every `app/build.gradle(.kts)` —
  what is actually compiled in — not the `build.json::modules` map (see F3).
- **Chattiness.** From the call sites: per user action (fine over IPC), per
  keystroke/frame (never over IPC), streaming (needs a poll or callback).

## The table

| module | kind | Cloud-Lib APK (total / so64 / assets / dex raw), MB | compiled into | calls | decision |
|---|---|---|---|---|---|
| analytics | engine (pings) | 0.92 / – / – / 2.58 | 13 apps + keyboard-engines | per screen/event | **stays** — tiny, chatty, and its job is to report *as* the host app |
| appstore | GUI | 15.66 / 2.13 / – / 19.95 | superapp | – | stays (GUI) |
| auth | mixed: Compose `SignInWays` + secret plumbing | 8.96 / – / – / 22.42 | superapp, drive | per sign-in | **stays** — draws in the host's Compose tree, and its whole output is bearer/cookie/vault secrets the host stores; a split puts them on IPC for no size win |
| battery | mixed | 12.82 / 2.13 / – / 15.74 | superapp | – | stays (GUI; usage-stats/Shizuku identity) |
| bottomnav | GUI | 15.92 / – / – / 54.50 | superapp, c3, drive, mail, me, wallet | per frame | stays (GUI) |
| browser | GUI (WebView) | 5.98 / – / – / 10.85 | browser | – | stays |
| cal | engine, already serves `IDataBackend` | 2.29 / – / – / 5.66 | me (agenda reaches it over IPC) | per sync | candidate (small); see F3 |
| chat | GUI | 3.49 / – / – / 8.69 | superapp | – | stays |
| contacts | engine, already serves `IDataBackend` | 2.27 / – / – / 5.61 | contacts | – | **blocked**: `READ_CONTACTS` is per package — the engine would read its own, ungranted permission |
| core | contract + platform | 2.23 / – / – / 5.54 | every app | – | **stays** — it IS the contract (`IDataBackend`, `CONSTELLATION_DATA`, crash provider) both sides link |
| cropper, gesture, panoramaviewer, scrollbar | GUI (upstream) | excluded from lib APKs | media-center | per frame | stay |
| datamanager | engine + fragment | 3.50 / – / – / 7.45 | superapp | – | **blocked**: `PACKAGE_USAGE_STATS` is special access, granted per package |
| devtools | in-process debug API | 2.19 / – / – / 5.46 | every app (via core) | – | **stays** — it inspects the host process |
| feed | engine, **already moved** | 2.24 / – / – / 5.55 | nothing (superapp uses `RemoteFeed`) | per refresh | done; see F3 |
| file-editor | GUI (Compose editor) | 15.83 / – / – / 54.27 | drive | per keystroke | stays |
| fin | GUI | 3.46 / – / – / 7.36 | me | – | stays |
| firewall | engine (firestack VPN) + fragment | 22.10 / 19.45 / – / 6.48 | superapp | per connection | later, not safe: VPN consent and always-on are per package (net-wg is the precedent, but it moves the user's VPN identity) |
| **gh** | engine (static `gh`) | 83.40 / 38.93 / – / 6.54 | drive (14.91 MB compressed) | per action: status, list, sign-in (streaming), credential | **MOVE 1** |
| git-sync | mixed: JGit + `GitSyncScreen` | 18.90 / – / – / 60.95 | drive | per action | **blocked for now**: repos live in the shared store (`<storage>/CloudDrive/git`); an engine in its own uid needs its own all-files grant |
| **gix** | engine (static gitoxide) | 44.52 / 18.77 / – / 6.54 | drive (9.92 MB compressed) — **zero call sites** | – | **MOVE 2** (unlink) |
| health | mixed (Health Connect) | 12.65 / – / – / 31.46 | superapp, me | – | blocked: Health Connect grants are per package |
| kde-connect | engine + device-admin receiver | 9.86 / – / – / 17.38 | superapp | per device event | later: device-admin enrolment is per package |
| keyboard | the IME itself | excluded | keyboard | per keystroke | stays |
| launcher-apptabs / -onehand / -zoomies | GUI | 5.90 / 3.58 / 3.62 | superapp | per frame | stay |
| mail (lib) | GUI (fragments, layouts) | 7.33 / – / – / 14.23 | superapp | – | stays |
| maps | GUI (MapLibre view) | 37.75 / 11.26 / 19.51 / 15.33 | nav | per frame | stays (draws in the host) |
| media | mixed: sticker/GIF payload + panel | 9.36 / – / 5.73 / 8.79 | keyboard, keyboard-engines | panel per keystroke | stays |
| ml-l-image-mlkit | engine (ZXing + ML Kit OCR) | 28.48 / 11.06 / 1.49 / 9.87 | drive, mail, camera, media-center | per image | **next candidate**: four apps republish per engine change today; needs a contract/engine split (consumers render its typed `BarcodePayload`), image crosses as a file descriptor |
| ml-l-text-mlkit | engine | 40.78 / 17.38 / 0.32 / 9.21 | keyboard-engines only | – | already separate (Cloud-Keyboard-Libs) |
| ml-l-voice-vosk | engine | 21.20 / 9.03 / – / 5.74 | keyboard-engines only | – | already separate |
| mounts | mixed: SFTP/SMB/WebDAV + `MountsScreen` | 21.42 / – / – / 64.94 | drive | per browse/transfer | blocked like git-sync: transfers land in per-package storage |
| net | contract + client of net-wg | 2.57 / – / – / 6.67 | superapp | – | stays (client half, already split) |
| net-wg | engine (WireGuard) | 19.80 / 8.49 / – / 6.70 | nothing (own APK) | – | done |
| news | engine, **already moved** | 2.33 / – / – / 5.76 | nothing (news uses `DataBackendClient`) | – | done; see F2, F3 |
| ops | GUI | 3.49 / – / – / 8.69 | superapp, c3 | – | stays |
| rclone | mixed: static `rclone` + `RcloneScreen` | 179.58 / 78.33 / – / 54.39 | drive (29.05 MB compressed) | per job, progress stream | **blocked as an IPC engine**: jobs read and write the shared store. The viable split is binary-only — Drive execs `librclone.so` out of the sibling lib APK in Drive's own uid (the rootfs precedent, `CloudRootfs.trustedLibSourceDir`) — and it needs an on-device check that an app may exec another package's extracted native lib |
| search | GUI + launcher search | 2.51 / – / – / 6.19 | superapp | per keystroke | stays |
| shizuku-adb-debug-tools | privileged channel | 11.02 / 2.13 / – / 12.52 | superapp (+ appstore, battery, updater) | – | blocked: the Shizuku grant is per package |
| text-tools | client of `ITextTools` (served by the keyboard) | 2.17 / – / – / 5.41 | superapp, keyboard, mail, writer | per action | **stays** — it is already the client half of an engine that lives in another APK |
| translate | client + host-drawn `TranslateBarView` | 2.21 / – / – / 5.47 | keyboard, keyboard-engines | per action | **stays** — the engine already runs in Cloud-Keyboard-Libs over `ITranslateEngine` |
| updater | self-update | 12.18 / 2.13 / – / 15.35 | most apps | – | **stays** — an app cannot depend on a separately updated APK to update itself |
| voice | client + host-drawn `VoiceBarView` | 2.23 / – / – / 5.51 | keyboard, keyboard-engines | – | **stays** (engine already in Cloud-Keyboard-Libs) |
| wallet | GUI-heavy | 18.88 / – / – / 61.08 | wallet | – | stays |
| watchdog | engine (.so + receiver + AIDL) | 4.45 / 2.24 / 0.05 / 5.41 | c3-watchdog | – | not assessed in depth (single consumer) |
| webserver | in-process HTTP server for the host's data | 2.18 / – / – / 5.43 | superapp | per request | stays |

`libs/gitsync` (vendored Flutter tree) and `libs/repos` have no `build.gradle`; they are
not modules.

## Why the first two are gh and gix

"Heaviest and safest first", with the one fact that decides safety here: **an engine
APK runs in its own uid**, so it holds its own permissions and its own private
storage. rclone (29 MB in Drive) and git-sync both read and write the shared store,
so as IPC engines they would need a second all-files grant on a package that has no
launcher icon — that is the datamanager barrier again. gh touches no shared files:
its only state is its own sign-in, and its calls are per user action. gix is linked
into Drive and called by nothing.

## Findings outside the two moves

- **F1 — the published binary engines could not run.** Every `Cloud-Lib-*.apk` stores
  its `.so` uncompressed, so Android never extracts it, and an engine that *execs* a
  binary (gh, gix, rclone, watchdog) finds `nativeLibraryDir` empty. Fixed for gh as
  part of move 1 (`lib_apks.exec_native`, per module, in data).
- **F2 — two apps cannot see their engines.** Cloud-News and Cloud-Agenda target SDK
  35 and bind `com.diegonmarcos.cloudlib.news` / `.cal`, but neither declares that
  package in `<queries>` (only their own id). Android 11+ hides an unqueried package,
  so `DataBackendClient.isInstalled()` reads "not installed" with the engine
  installed. (SuperApp's feed works only because it holds `QUERY_ALL_PACKAGES`.)
- **F3 — three moved engines still republish their old apps.** superapp→feed,
  news→news, agenda→cal: the code moved to the engine APK but each app's
  `build.json::modules` still declares the module, so its ship workflow watches the
  engine and its publish identity hashes it. An engine edit republishes an app that
  does not contain it.
- **F4 — the shared client has no version handshake.** `DataBackendClient` binds by
  class name and answers "not installed" for an engine that is installed but too old
  to carry the service. Move 1 adds the handshake on the gh client (contract number on
  the engine's service, read through PackageManager before binding); hoisting it into
  `libs:core` is the step after review.

## Move 1 — gh: Cloud Drive binds it, Cloud-Lib-Gh runs it

| piece | where | what it does |
|---|---|---|
| engine | `ab_cloud-libs-shared/libs/gh/…/GhBackendService.kt` | `DataBackendService` in Cloud-Lib-Gh.apk; runs gh in the engine's own process; methods `status`, `repoList`, `credential`, `loginStart`, `loginPoll` (sign-in is polled: a binder call must not block for the minutes GitHub takes to approve a code) |
| contract | engine manifest | service exported, guarded by `CONSTELLATION_DATA` (signature), found by action `${applicationId}.ENGINE`, versioned by meta-data `com.diegonmarcos.cloud.engine.CONTRACT` = 1. The contract only grows; a change that would break an answer ships under a new method name |
| runnable | `lib-apks/build.json::lib_apks.exec_native` | the gh flavor extracts its `.so` at install (variant API, this flavor only), so the engine can exec `libgh.so` — F1 for gh |
| client | `ac_cloud-drive/…/sync/GhEngine.kt` | handshake through PackageManager **before** binding (no package → "install it", no service or a lower contract → "update it", both naming Store ▸ Cloud Constellation ▸ Libs); then `DataBackendClient` calls |
| declaration | `ac_cloud-drive/build.json::engines.gh` | Store row (`lib-gh`), action template, `min_contract`, sign-in pacing; `app/build.gradle` resolves the package from the fleet manifest (unknown id fails the build) and bakes it into BuildConfig and `<queries>` |
| unlinked | Drive `build.json::modules`, `app/build.gradle`, `ship-cloud-drive.yml` (3 copies) | Drive no longer compiles libs:gh and its ship no longer watches `libs/gh/**`, so a gh change cannot republish Cloud Drive |

State moves with the uid: gh's sign-in now lives in the engine's own storage, so a
phone that signed gh in through Cloud Drive signs in once more (repositories already
cloned keep their credential in Drive's git credential store).

**Tests.** `lib-apks/test/test-engine-services.sh` (engine side, runs on every Cloud Libs
ship: exported, guarded, findable, versioned, lists exactly what it answers, links core,
extracts what it execs; 12 mutations). `ac_cloud-drive/test/test-drive-gh-engine.sh`
(client side, Drive's own source only: not carried, declared, visible, handshake before
bind, loud, no secret logged, host-checked sign-in page; 19 mutations).
`1_cicd/src/scripts/cloud-android-engine-contract-guard.py` + its workflow (both sides
at once, every push — the only place that may read both, because an app's tester that
reads an engine's source is downgraded to advisory; 10 mutations).

**Not verified on a device** (none was reachable from the agent): the bind, the
handshake strings and the sign-in poll are proven by CI build + static testers only.
