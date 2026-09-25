# cloud-drive parity audit — #575 (2026-09-25)

Adversarial audit of the claim that cloud-drive is an EXACT replacement for the
GitSync app, a Google Drive client, a full rclone, and a Total Commander /
X-plore-class file manager — and, critically, that what it clones or mounts is
SHARED with every other fleet app the way `/storage/emulated/0` is.

Method: every claim was treated as FALSE until a file:line proved it. Line
numbers are against `9cd3b4738` (origin/main at audit time). Paths are
repository-relative; `libs/` means `ab_cloud-libs-shared/libs/`. The code-graph
MCP (`cloud-cgc-pvt-mcp`) was reachable but its index of this repository is
stale (it still returns `ac_cloud-ide`, which is not in the tree), so every
verdict below rests on the file reads cited, not on the graph.

Legend: **PROVEN** — the code path exists and is reachable from the UI.
**PARTIAL** — exists, but not reachable / not for the declared data / not where
the claim puts it. **MISSING** — no code does it.

## (a) GitSync exact replacement

| Claim | Verdict | Evidence |
|---|---|---|
| Native (no Flutter) git manager reachable from the Sync tab | PROVEN | `ac_cloud-drive/app/src/main/assets/drive.html:1350` `engineButton('git', '', 'Open git manager')` → `FilesBridge.kt:1281` `openEngine` → `EngineActivity.kt:58` → `libs/git-sync/.../GitSyncScreen.kt:93` (Compose) |
| Clone | PROVEN (engine + dialog) | `GitEngine.kt:359` `clone(url, dir, auth)`; `GitSyncScreen.kt:259-286` Add dialog, "Clone" mode |
| Pull / push / fetch / commit | PROVEN | `GitEngine.kt:182` pull, `:141` push, `:170` fetch, `:124` commit; buttons `GitSyncScreen.kt:392-397` |
| One-tap sync (stage → commit → pull → push) | PROVEN | `GitEngine.kt:208` `sync()`; list-row Sync `GitSyncScreen.kt:200-217`, repo Sync `:396` |
| Per-repo config (auth, rebase, message, author) | PROVEN | `GitModels.kt:553-570` `ManagedRepo`; `GitSyncScreen.kt:634` SettingsTab; secrets in `GitCredentialStore.kt:586` (EncryptedSharedPreferences) |
| Scheduled background sync | **MISSING** | `grep -rn WorkManager\|PeriodicWork\|JobScheduler\|AlarmManager libs/git-sync ac_cloud-drive/app/src` → 0 hits. The only periodic worker in the fleet is `libs/updater/.../Updater.kt:61` |
| Declared repositories (data/drive-git-repos.json) can be cloned from the page | **PARTIAL** | `drive.html:1314-1349` renders the 8 repos as TEXT (owner/name → mirror). The single button passes an empty target (`:1350`), so the user re-types URL and folder by hand (`GitSyncScreen.kt:265-268`). No per-repo clone. `cloud-data-my-ai-memory` is not declared at all |
| The build.json story matches the code | **PARTIAL** | `ac_cloud-drive/build.json:3` still says "vendored GitSync lib whose Flutter UI is embedded once the engine's add-to-app integration lands" — stale since #567 push 2 |
| Verbs proven by tests | PROVEN | `libs/git-sync/src/test/.../GitEngineTest.kt` (real repos over file://), `ac_cloud-drive/test/test-drive-git-sync-engine.sh` G1–G6 |

## (b) Google Drive client

| Claim | Verdict | Evidence |
|---|---|---|
| A Google Drive client exists | **MISSING** | No OAuth flow, no Drive API, no Google Sign-In anywhere in `ac_cloud-drive` or the four engine libs |
| rclone `drive` backend as a substitute | PARTIAL | `libs/rclone/src/main/assets/rclone-remote-types.json:21` type `drive`, label "Google Drive (token from another rclone)": the OAuth token has to be pasted from a desktop rclone; nothing on the phone obtains one |
| Fleet-declared gdrive remotes | PARTIAL | `data/drive-remotes.json` `gdrive` / `gdrive_photos` are `status: unreachable` (credential on the retired oci-p-flex_1 host); the page renders the reason (`drive.html:1226+`), the engine never sees them (see c) |
| "Google Drive (me@)" in Mounted | information only | `data/drive-connections.json` entry is `kind: google-drive` via MCP on oci-apps; it carries no `uri`, so `EngineActivity.kt:107-111` cannot turn it into a mount |

## (c) FULL rclone

| Claim | Verdict | Evidence |
|---|---|---|
| A real rclone binary runs on the phone | PROVEN | `libs/rclone/data/rclone-binary.json` (v1.75.1, sha256-pinned, static, PT_INTERP checked at build); `RcloneRunner.kt:15` execs `nativeLibraryDir/librclone.so`; `ac_cloud-drive/app/build.gradle:299` `useLegacyPackaging = true` |
| Remotes: add / edit / test / delete, obscured secrets | PROVEN | `RcloneScreen.kt:139-188`, `RcloneConfig.kt:177-231`, `RcloneRunner.kt:53` obscure |
| Jobs run with live stats and cancel | PROVEN | `RcloneScreen.kt:269-282` → `RcloneRunner.kt:67` `start()` (`--use-json-log --stats 1s`) |
| Browse a remote, download a file | PROVEN, sandboxed | `RcloneScreen.kt:366-410`; download lands in `RcloneRunner.kt:19` `getExternalFilesDir(null)/rclone-downloads` = `Android/data/com.diegonmarcos.clouddrive/…`, unreadable by any other app on Android 11+ |
| Declared remotes (drive-remotes.json) reach rclone.conf | **MISSING** | `EngineActivity.kt:90-113` `declareFromBuild` declares JOBS and MOUNTS only. `RCLONE_REMOTES_B64` is consumed solely by `FilesBridge.kt:1121` `rcloneRemotes()` for the page. On the phone `rclone.conf` starts empty (`RcloneScreen.kt:152`) |
| Declared jobs (drive-rclone-jobs.json) run on the phone | **MISSING in effect** | The only declared job is `kind: mount` (`data/drive-rclone-jobs.json`), which `EngineActivity.kt:96` drops (`kind !in RcloneJob.OPS`). Zero declared jobs reach the engine; the page renders it as fleet-side information |
| `rclone mount` | **MISSING (platform)** | FUSE mounts need root on Android; no code attempts it and no DocumentsProvider stands in for it |
| Local job paths relative to a shared root | **MISSING** | `RcloneJob.arguments()` (`RcloneJobs.kt:122`) passes `source`/`destination` verbatim; a phone-side leg has to be an absolute path typed by hand |

## (d) Total Commander / X-plore class

| Capability | Verdict | Evidence |
|---|---|---|
| Copy / move (cross-volume safe) | PROVEN | `FilesBridge.kt:428` `transfer()` (free-space check, rename-then-copy); page clipboard `drive.html:449-461` |
| Rename, bulk rename with preview + rollback | PROVEN | `FilesBridge.kt:412`, `:937-978` |
| Delete (recursive, per-entry report) | PROVEN | `FilesBridge.kt:477` |
| Zip / unzip (Zip-Slip guarded) | PROVEN | `FilesBridge.kt:725`, `:796` |
| Multi-select (long-press), selection bar | PROVEN | `drive.html:413-416`, `:374` |
| Search by name and content, duplicates, properties + hashes | PROVEN | `FilesBridge.kt:663`, `:1031`, `:845` (cancellable job runner `:630`) |
| Two-location workflow | PARTIAL | One pane + clipboard: stage in folder A (`stageTransfer`), navigate, paste in B (`pasteHere`). No dual-pane view |
| Remote ↔ local transfer (X-plore style) | PARTIAL | sftp/ssh/ftp/ftps/dav/davs browse, upload, download, mkdir, delete, rename (`libs/mounts/.../MountEngines.kt:45-157`); downloads land in `MountsScreen.kt:91` `getExternalFilesDir/mount-downloads` (sandboxed), upload picks through SAF (`:244`) |
| Removable volumes (SAF tree grant) | PROVEN | `FilesBridge.kt:274-323`, `MainActivity.kt:40-44` |
| Mounted list is the ONE connections catalogue | PROVEN | `drive.html:1289` renders `data/drive-connections.json` via `FilesBridge.kt:119` |

## (e) CRITICAL — where do clones and mounts LIVE, and who else can reach them

| Question | Verdict | Evidence |
|---|---|---|
| cloud-drive itself can read AND write all of shared storage | PROVEN | `AndroidManifest.xml:19` `MANAGE_EXTERNAL_STORAGE`; `FilesBridge.kt:76-81` roots = `Environment.getExternalStorageDirectory()` + app dirs; `:93` `isExternalStorageManager()` |
| Git clones land in a declared shared root | **MISSING** | `GitSyncScreen.kt:265` "Folder path" is free text with no default; `EngineActivity.kt:58` passes the page's empty target through. Nothing names a root. The registry (`GitSyncScreen.kt:99` `filesDir/git-sync/repos.json`) is sandboxed, which is correct — it holds no repo bytes |
| rclone / mount downloads are shared | **MISSING** | `RcloneRunner.kt:19`, `MountsScreen.kt:91` → `Android/data/<pkg>/files/…`: readable by cloud-drive only (Android 11+ hides another app's `Android/data` even from all-files-access holders) |
| A DocumentsProvider / exported ContentProvider | **MISSING** | `AndroidManifest.xml:87-95` declares only `androidx.core.content.FileProvider`, `exported=false`, `grantUriPermissions=true` (per-intent read grants for Open-with / Share, `FilesBridge.kt:520`). No `DocumentsProvider`, no `<provider android:exported="true">` |
| Other fleet apps can read and write those folders like `/storage/emulated/0` | **PARTIAL, by accident** | Only if (1) the user typed a path under plain shared storage when cloning and (2) the other app holds all-files access. cloud-code does (`ac_cloud-code/src/plugins/system/android/com/foxdebug/system/System.java:385`) and already reads `file:///storage/emulated/0/git/…` — a SECOND, hand-written root in `ac_cloud-code/src/cloud/nav.json` (`backlog.source_dir`, `repos.root`) that cloud-drive knows nothing about. Two declarations of the same root, in two apps, agreeing only by luck |
| Clone root declared ONCE (build.json) | **MISSING** | `grep -rn shared_root\|CloudDrive ac_cloud-drive/build.json` → 0. `data/drive-mirror-jobs.json` uses `CloudDrive/mirror/*` relative paths with the right rule (`_doc_paths`: never write `/storage/emulated/0` — the user id changes under a second profile) but that convention is local to the Backups tab |
| cloud-IDE (cloud-code) Backlog reads the memory repo through cloud-drive's store | **MISSING** | `ac_cloud-code/src/cloud/index.js:57-101` reads `nav.backlog.source_dir` (absolute, own declaration) with `fsOperation(...).readFile` only — READ, no write path; `seams.js:28` lists `.git` children of its own `repos.root`. There is no own-clone LOGIC to delete (nothing in cloud-code clones); the "own clone" is the hand-made one at `/storage/emulated/0/git` |

## Verdict summary

- GitSync: engine and native UI PROVEN; scheduled sync MISSING; declared-repo clone flow PARTIAL; docs stale.
- Google Drive: MISSING as a client; rclone `drive` with a pasted token is the only path.
- rclone: binary, remotes, jobs, browse PROVEN; declared remotes/jobs never reach the engine (MISSING); mount impossible without root.
- File manager: Total-Commander-class verbs PROVEN; dual pane PARTIAL (clipboard).
- Shared store: MISSING. No declared root, no provider, every engine's output sandboxed; cloud-code carries its own absolute root.

## Phase 2 (what this ticket fixes, in order)

1. ONE declaration `ac_cloud-drive/build.json::storage.shared_root` (relative to shared storage, `CloudDrive`), baked into cloud-drive and READ by cloud-code's build (`tools/resolve-targets.py`) — no per-app absolute path anywhere.
2. Proof case: cloud-code's Backlog resolves `cloud-data-my-ai-memory` under that root, reads it there and WRITES there through Acode's editor (open file / open repo folder); cloud-drive's git manager is the thing that commits and pushes it.
3. GitSync flows: per-repo Clone/Open buttons on the Sync ▸ Git subpage targeting `<root>/<repo>`, clone URL prefilled; scheduled background sync (WorkManager, interval and network rule declared in build.json, per-repo opt-in).
4. rclone: declared remotes land in the phone's rclone.conf as secret-less skeletons (the user adds the key on the device); relative local paths in jobs resolve against the shared root.

## What landed (Phase 2) and what did not

Landed, mutation-proven by `ac_cloud-drive/test/test-drive-shared-store.sh` (S1–S6)
and `ac_cloud-code/test/test-cloud-nav.sh` (the #575 block of `cloud_nav.mjs`):

1. **ONE declaration** — `ac_cloud-drive/build.json::storage.shared_root = "CloudDrive"`
   (relative; the build refuses an absolute value). Baked into `BuildConfig.SHARED_ROOT`;
   `SharedStore.kt` derives `<shared storage>/CloudDrive` on the device. `storage.git_sync`
   declares the scheduled-sync period and network rule the same way.
2. **Proof case** — cloud-code's `nav.json` names the store by fleet id (`shared_store.app =
   cloud-drive`); `tools/resolve-targets.py` reads that app's `storage.shared_root` at build time
   into `targets.gen.json`; `index.js` composes every path from the device's
   `externalRootDirectory` + that root. Backlog reads `cloud-data-my-ai-memory/1.1.Product-Backlog/dist/`
   from the store and WRITES through it: "Edit this file" opens the rendered file in Acode's editor
   (saved in place), "Open the repository" opens the clone as a folder. No absolute `/storage` path
   is left in `src/cloud/`; cloud-code clones nothing.
3. **GitSync flows** — Sync ▸ Git lists every declared repository (now including
   `cloud-data-my-ai-memory`, marked private) with a *Clone / open* button carrying
   `<root>/<name>` and the upstream URL; the git manager opens on the store when no target is
   named; `GitSyncWorker` (WorkManager, `ExistingPeriodicWorkPolicy.UPDATE`) runs one-tap sync for
   repositories that opted in (`ManagedRepo.autoSync`, Settings switch).
4. **rclone** — every remote in `data/drive-remotes.json` that carries an `rclone` block is
   declared into the phone's `rclone.conf` once, secret-less (`RcloneConfig.declare`, add-only,
   JVM-tested); a job's relative local leg resolves against the store (`SharedStore.resolve`).

Residuals — honest, not faked:

- **No DocumentsProvider.** The store is plain shared storage; a fleet app reaches it only with
  all-files access (cloud-drive and cloud-code both hold it). An app without that grant would need
  a `DocumentsProvider` in cloud-drive, which was not built: it is a second access path to the
  same bytes and nothing in the fleet needs it today.
- **No phone-side rclone job is declared.** The mechanism (skeleton remotes, relative legs) is in;
  the data is not, because every current bucket is fleet-owned (photos Takeout, OS images) and
  creating a phone bucket is a cloud-infra decision. `drive-rclone-jobs.json::_doc_paths` says so.
- **Google Drive stays "token from another rclone".** No on-device OAuth. The `gdrive` /
  `gdrive_photos` skeletons now land in `rclone.conf`, so the owner pastes a token once on the
  phone instead of editing a config file.
- **rclone / mount downloads still land in `Android/data`.** `RcloneRunner.downloadDir` and
  `MountsScreen.downloadDir` are library-owned; moving them to the store means the libraries
  taking a host-supplied directory. Not done in this run.
- **Scheduled sync does not resolve conflicts.** A conflicting pull leaves the repository in
  MERGING with `lastSyncSummary` saying so; the user resolves it in the manager. The worker
  reports success so the period stays regular (a retry storm would not fix a conflict).
- **On-device proof needs two installed apps.** Every assertion here is static; that a clone
  cloud-drive makes is readable by cloud-code on one phone is unverified until both APKs are
  installed together.
- The code-graph MCP index of this repository is stale (see top); it was reachable and was
  queried, but every claim rests on file reads.
