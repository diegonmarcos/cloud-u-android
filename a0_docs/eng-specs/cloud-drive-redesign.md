# cloud-drive — the professional-grade redesign (#579)

Diego, 2026-09-25: "WHAT A SHIT JOB. Make it PROFESSIONAL-GRADE. A full cloud
drive as described, GitSync (SAME UI quality), and X-plore FULLY replaced. YOU
CANNOT BE LAZY IN THE UI." The app that earned that verdict was a 2789-line
Tailwind page in a WebView, talking to a 2023-line Kotlin class by string name,
with a two-colour status dot, emoji for icons and one pane plus a clipboard.
The four engines underneath it (#567) and the shared store (#575) are sound and
stay. Everything the owner SEES is rebuilt from zero, natively, and this file is
what it is rebuilt to. Nothing on screen exists that this file does not name.

## 0. Decisions

| Decision | Why |
|---|---|
| **Native Compose chrome.** `drive.html`, `tailwind.js` and `FilesBridge.kt` are deleted. `MainActivity` is a `ComponentActivity` that `setContent`s the shell. | #565 made Compose mandatory; the engines are already Compose; a WebView cannot do a dual pane, a tree, drag-select or a live progress row without inventing a second UI toolkit. The bridge existed only to serve the page. |
| **The fleet's bottom-nav island (`libs:bottomnav`)** draws the five tabs. | One visual family with superapp, mail, wallet and me. The island's geometry is that module's, not ours. |
| **StatusLight idiom**, four states, three appearances, glyph + word + colour, reproduced for Compose in this app (`ui/StatusLight.kt`) with the SAME resource names and values as superapp's. | superapp's `StatusLight` is app-module code no library can import; the idiom is what must match, and a tester pins the colours to superapp's when the sibling is present. |
| **The engine libraries keep their screens** and are reached from cards, never from placeholder rows. `libs:git-sync` gains two per-repo scheduler fields; nothing else in a library changes shape. | App is chrome, libraries are engines (#567). The chrome may READ an engine (`GitEngine.status()`, `RcloneRunner.start`) to light a card; it never re-implements one. |
| **The PDF reader is #577's native pdfium engine** (`PdfReaderActivity` over `PdfEngine`, `build.json::pdf`), reached from a PDF row and from any app's Open-with. Conversion (txt/md/html/csv) runs through the ONE `PdfConversion` path from the reader and from the Files row menu. | One-PDF-engine rule (`1_cicd/src/data/one-pdf-engine-guard.json`): the fleet has exactly one renderer. #577 landed it natively while this redesign was in flight; the redesign adopts it and keeps no WebView anywhere. |
| **The image viewer is native Compose** (`ImageViewerActivity`) over the shared scan engine. | Zoom, swipe and the scan/OCR sheet are ordinary Compose; nothing needs a page. |
| **All structure is declared** in `build.json::ui` (tabs, sync pages, places, filters, icon names) and baked through BuildConfig, as every other list in this app already is. | Fire rule 4. Kotlin holds no caption, no tab list, no place list. |

## 1. Visual family

Dark is the default and the only theme (#336/#337, `Theme.CloudDrive` →
`Theme.Material3.Dark.NoActionBar`; the Compose scheme is `DriveTheme`). Every
colour is a resource in `res/values/colors.xml`:

| role | resource | value | from |
|---|---|---|---|
| window background | `drive_background` | `#FF07040F` | superapp gradient's black end, one step lighter than the island so the island reads as a box |
| card / sheet surface | `drive_surface` | `#FF140B26` | the island fill (`libs:bottomnav` `bottom_nav_island_fill`) |
| raised surface (toolbar island, selected row) | `drive_surface_raised` | `#FF1F1238` | |
| hairline | `drive_outline` | `#33A78BFA` | superapp `glass_border` |
| accent | `drive_accent` | `#FFB794F4` | superapp `island_accent` |
| accent, on a card | `drive_accent_on_card` | `#FFC7AEFB` | superapp `theme_cloud_accent` (the 4.5:1-on-card variant) |
| text | `drive_text` / `drive_text_secondary` | `#FFFFFFFF` / `#AAFFFFFF` | superapp cloud theme |
| status | `status_light_on` / `status_light_off` / `status_light_unknown` | `#FF3DDC84` / `#FFF87171` / `#FFB0B8C4` | superapp, identical names and values |

Typography is Material 3's default scale; monospace (`FontFamily.Monospace`)
for paths, shas, diffs and job argv. Radii: cards 16dp, islands and pills 50%
(the island pill shape from `libs:bottomnav`). Spacing grid 4dp; the content
gutter is 12dp; a card's inner padding is 14dp. Every tappable surface is a
Compose `clickable`/`combinedClickable` (ripple by default); tab and page
changes go through `AnimatedContent` with a 220ms fade+slide, held still under
Power Saving (the island's own rule).

### Components (ui/Chrome.kt) — the whole inventory

| component | what it is | used by |
|---|---|---|
| `ToolbarIsland(title, subtitle?, leading?, actions)` | the top pill: raised surface, 50% radius, 56dp, clears the status bar/cutout inset, title + optional monospace subtitle, trailing icon actions | every tab |
| `DriveCard(header, light?, summary?, body)` | the ONE card: 16dp radius, surface, optional `StatusLight` at the header's end, one-line summary, body | repo, remote, mount, job, place, setting |
| `Pill(text, icon?, filled)` | pill button, 50% radius, accent when `filled` | every action |
| `SectionHeader(title, count?, action?)` | label-large title, optional count badge, optional trailing text action | every list |
| `StatusLightRow(state, label)` | glyph + word in the state's colour, content description for TalkBack | cards |
| `ProgressCard(title, fraction?, detail, onCancel)` | a card with a linear indicator (determinate when a fraction is known) and a monospace detail line | transfers, mirrors, rclone jobs, git sync |
| `EmptyState(icon, title, hint, action?)` | centred icon 48dp, title, hint, optional pill | every list when empty |
| `ErrorState(message, retry?)` | the `status_light_off` colour, the message as written by the engine, a Retry pill | every list on failure |
| `LoadingState()` | one centred indeterminate indicator | every list while its first read runs |
| `IconCatalog.painter(name)` | the declared icon name → Material glyph; an unknown name renders the `_default` glyph and a tester fails | tabs, pages, places, tiles |

There is no other visual primitive. A screen that needs one adds it here.

## 2. Shell and navigation

```
┌──────────────────────────────────────────┐
│ ▭ ToolbarIsland  (per tab)               │  clears status bar + cutout
├──────────────────────────────────────────┤
│                                          │
│   tab content (AnimatedContent)          │
│                                          │
│                                          │
├──────────────────────────────────────────┤
│      (● Files) Apps  Sync  Backups  ⚙    │  BottomNavIsland, 80% width
└──────────────────────────────────────────┘
```

* Tabs, in order, from `build.json::ui.tabs[]` — `{id, label, icon}`:
  **files · apps · sync · backups · configs**. `ui.default_tab` = `files`.
  Selection is `rememberSaveable`. Re-tapping the selected tab scrolls its
  content to the top.
* The island collapses to icons on scroll (`rememberBottomNavCollapse`) and
  reads its own bottom inset; the content consumes it once (the
  `libs:bottomnav` `BottomNavBar` pattern, with this app's entries).
* **Back**: inside Files, back walks the active pane's history (closes an
  archive, then goes up a folder, then closes a pane tab, then leaves the app);
  a selection or an open sheet is closed first. In every other tab back leaves
  the app. Sub-pages (Sync's Git / Rclone / Mounts strip) are tabs inside the
  tab, not a stack.
* Engines (git manager, full editor, rclone, mounts) open in `EngineActivity`
  for a result, exactly as before; the returned path is revealed in the active
  Files pane (`FilesController.reveal`).
* A PDF handed in by another app (`ACTION_VIEW application/pdf`) opens
  `PdfReaderActivity` directly, never the shell.

## 3. FILES — the X-plore replacement

### 3.1 Layout

Portrait (the phone):
```
┌ ToolbarIsland: "Files" · <active pane path, monospace> · [⇄ dual] [🔍] [⋮] ┐
├────────────────────────────────────────────────────────────────────────────┤
│ PANE A (active)                                                            │
│ ┌ tab strip: [Shared store ×] [Download ×] [+]                          ┐ │
│ │ breadcrumbs: Internal storage › CloudDrive › cloud-infra  ▸ tree|list │ │
│ │ toolbar row: [Sort: Name ↓] [Filter: All] [.hidden] [★]               │ │
│ │ storage bar (only at a volume root): ▓▓▓▓▓░░░ 41.2 GB free of 128 GB   │ │
│ │ ● entry rows …                                                         │ │
│ └────────────────────────────────────────────────────────────────────────┘ │
│ PANE B (inactive, collapsed to a header)                                   │
│ ┌ [ 📁 Download › Telegram    3 items   ▲ ]                               ┐ │
└────────────────────────────────────────────────────────────────────────────┘
│ selection bar (only while selecting): 3 selected [Copy →B] [Move →B] [⋮]   │
│ transfer ProgressCard (only while a job runs)                              │
```
* The active pane gets the height; the inactive pane is a 48dp header (its
  path, its item count, a chevron). Tapping the header makes it active (panes
  swap roles with a 220ms animation). In landscape, or on a width ≥ 600dp, the
  two panes sit side by side at 50/50 and both are full height.
* `ui.files.default_dual_pane` is the initial mode; the toolbar's ⇄ toggles
  it (persisted in `DrivePrefs`). Single mode is one full-height pane.
* Each pane keeps its OWN tabs (max `ui.files.tabs_per_pane_max`), sort,
  filter, hidden toggle, view mode and history; both panes are restored
  across process death (`rememberSaveable` through `FilesUiState`).

### 3.2 Pane anatomy

| element | behaviour |
|---|---|
| **tab strip** | one chip per open location (folder name, or `archive.zip` with the archive glyph); × closes; + opens the Places sheet; long-press a chip = bookmark it. The active chip is the raised surface. |
| **breadcrumbs** | one crumb per segment from the volume root, horizontally scrollable, the last crumb bold; tap a crumb = go there; the volume root crumb is the place's label (Shared store, Internal storage, SD card) never `/storage/emulated/0`. Inside an archive the crumbs continue past the archive name. |
| **view toggle** | list (rows) or tree. Tree = the same LazyColumn showing a collapsible hierarchy from the pane's root: a chevron per folder, 16dp indent per depth, children read lazily on expand; tapping a file in tree mode opens it, tapping a folder's name (not its chevron) makes it the pane's current folder in list mode. |
| **toolbar row** | Sort pill (sheet: name / size / modified / type, ascending/descending — `ui.files.sort_keys`), Filter pill (chips from `ui.files.filters`), hidden-files toggle, ★ bookmark toggle for the current folder. |
| **storage bar** | shown only when the pane is at a volume root or the shared store root: a 6dp track, accent fill = used fraction, text "X free of Y". |
| **entry row** | 56dp: 40dp leading glyph or image thumbnail (images only, decoded at 96px on IO, cached per path), name (one line, ellipsis middle), meta line (`folder · N items` / `size · date`), trailing ⋮. Selected rows are the raised surface with a check badge over the glyph. |
| **selection bar** | appears above the island when ≥1 selected: count, `Copy → other pane`, `Move → other pane`, and ⋮ (Delete, Rename (1) / Rename by pattern (n), Zip, Share, Properties, Select all, Invert, Clear). With one pane the copy/move verbs read `Copy…` and ask for a destination via the Places sheet. |
| **transfer ProgressCard** | one per running job (copy, move, delete, zip, extract): title = verb + count, determinate by bytes when known, detail = the current file name, Cancel. Finished jobs collapse into a one-line result for 4s (snackbar). |

### 3.3 Gestures

| gesture | result |
|---|---|
| tap row | folder → enter; archive → enter as folder; image → viewer; PDF → reader; text (`text/*`, json, md, xml, sh, …) → the full editor engine; anything else → open-with |
| long-press row | start selection with that row |
| tap row while selecting | toggle |
| drag on rows while selecting | range-select (pointer moves over rows toggle them on) |
| tap inactive pane header | activate that pane |
| swipe left/right on the tab strip | switch tabs |
| tap crumb | navigate |
| back | see §2 |

### 3.4 Archive browsing

A `.zip` (the list is `ui.files.archive_extensions`) is a folder: entering it
lists its entries as a tree (`ArchiveFs`, `java.util.zip.ZipFile`), with
folders synthesised from entry paths. Rows inside an archive show size and
compressed size. `Copy → other pane` from inside an archive extracts the
selection there, through the same Zip-Slip guard (`zipEntryTarget`) the whole
archive extraction uses. Nothing inside an archive is editable in place;
Rename/Delete/Move are absent from the selection bar there, and the
selection bar says so ("read-only inside archive").

### 3.5 Places sheet (Go to)

Opened by the pane's + chip or the toolbar's folder icon. A `ModalBottomSheet`:

1. **Shared store** — the store root as a hero card (accent border,
   the store's usage, "the one folder every fleet app reads and writes").
   It is `ui.files.places[]` entry `kind: shared_root` and is always first.
2. **Places** — the remaining declared places that exist on this device
   (`kind: external_root | public_dir | external_path`), one row each with
   the declared icon; removable volumes (`/storage/*`, when all-files access
   is live) and the app's slice on each volume; the persisted SAF tree grant
   as "SD card (granted)" and "Connect a storage drive…" when no removable
   path is visible — the same rules the old `places()` had.
3. **Bookmarks** — `DrivePrefs.bookmarks`, each with an × to remove.

Tapping a place opens it in a NEW tab of the active pane (or replaces the
current tab when the pane already has `tabs_per_pane_max`).

### 3.6 Search

The toolbar's 🔍 opens a search bar under the island: query, `names only /
names + text` toggle, results as entry rows with the matching folder as the
meta line; a Cancel that really cancels (the walk is a cancellable coroutine).
Ceilings unchanged: 300 results, 1 MB per text file.

### 3.7 Empty / loading / error

| state | what shows |
|---|---|
| no all-files access | `EmptyState(lock, "Cloud Drive needs all-files access", "Android 11+ …", Pill "Grant access")` in place of both panes |
| folder read failing (permission, gone) | `ErrorState(<engine message>, Retry)` in the pane |
| empty folder | `EmptyState(folder_open, "Nothing here yet", "Paste, create or move something into this folder")` |
| empty archive | `EmptyState(archive, "Empty archive", "")` |
| first read of a folder | `LoadingState()` for ≥ 120ms only (no flash on fast reads) |
| search, no hit | `EmptyState(search, "No match for “q”", hint) |

### 3.8 Data

`FilesState.kt` is pure Kotlin (JVM-tested): `Location` (`Local(path)` /
`Archive(zipPath, innerPath)`), `PaneTab(location, history)`, `PaneState`
(tabs, active index, sort, descending, showHidden, filterId, viewMode,
selection, expanded tree nodes), `FilesUiState` (pane A, pane B, active
pane, dualPane), and the reducers (`open`, `up`, `back`, `select`,
`toggleTree`, `switchPane`…). `FileOps.kt` is the typed IO core carried over
from the bridge: list/describe/sort, create, rename, transfer-with-progress
(free-space check first, rename-then-copy for a move), delete, archive,
extract (two passes, Zip-Slip guard), search, properties + hashes,
duplicates, bulk rename plan/apply with rollback, mirror with rsync's quick
check, read/write text atomically, thumbnails, EXIF, rotate-by-tag.

## 4. SYNC ▸ GIT — GitSync-class

```
┌ ToolbarIsland "Sync" · [Git] [Rclone] [Mounts]  (sub-tab strip in the island)  [＋ repo] ┐
│ ● Store  <root>  ·  4 repositories  ·  scheduled every 60 min on Wi-Fi  · next in 23 min │  (hero row)
│ ┌ DriveCard ─────────────────────────────────────────────────────────────────────────── ┐ │
│ │ cloud-infra                                   ● On                                      │ │
│ │ main → origin/main   ↑0 ↓2   clean   · last sync 14:02 · pulled 2 commits              │ │
│ │ [Sync now] [Open] [History] [Settings]                              ⟳ auto · 60 min · Wi-Fi │ │
│ └────────────────────────────────────────────────────────────────────────────────────────┘ │
│ ┌ DriveCard ─────────────────────────────────────────────────────────────────────────── ┐ │
│ │ cloud-data-my-ai-memory                       ○ Off                                     │ │
│ │ main → origin/main   ↑1 ↓0   3 changed · 2 CONFLICTS   · last sync failed: pull conflicts │ │
│ │ [Resolve 2 conflicts] [Open] [History] [Settings]                    ⟳ auto · 15 min · any │ │
│ └────────────────────────────────────────────────────────────────────────────────────────┘ │
│ ┌ declared, not cloned ────────────────────────────────────────────────────────────────── ┐ │
│ │ front-data   github.com/diegonmarcos/front-data  · public        [Clone into store]      │ │
│ └────────────────────────────────────────────────────────────────────────────────────────┘ │
│ History (last 50) ▸                                                                        │
```

* **Hero row**: the store path (monospace), repository count, the declared
  schedule (`storage.git_sync`), the next scheduled run (WorkManager's
  `WorkInfo.nextScheduleTimeMillis` where available, else "on schedule").
* **One card per managed repository** (`RepoRegistry`, the same file the
  engine screen writes). The card's light is the StatusLight of the LAST
  sync outcome: `● On` last sync ok and no conflicts; `○ Off` last sync
  failed or conflicts present; `? Unknown` never synced or the repo cannot be
  read; `? Not verifiable` when the folder is gone.
  Line 2 is the live glance (`GitEngine.status()` on IO, refreshed on tab
  entry, after every verb and on pull-to-refresh): `branch → upstream`,
  `↑ahead ↓behind`, `clean` / `N changed`, `N CONFLICTS` in the off colour,
  the repository state when not SAFE (MERGING…). Line 3: `last sync <time> ·
  <summary>`. Footer: `⟳ auto · <period> · <network rule>` or `manual`.
* **Sync now** runs the engine's one-tap sync with a stepped
  `ProgressCard` inside the card ("staging → committing → pulling →
  pushing", the step that runs is bold), Cancel not offered (JGit transport is
  not interruptible mid-push; the card says "cannot be interrupted" while
  pushing). The outcome lands in the history log and the card.
* **Resolve N conflicts** (only when conflicts exist) opens the engine on
  that repository, whose Changes tab puts conflicts first (ours / theirs /
  mark resolved / abort).
* **Open** opens the engine's full manager on the repository (changes, log,
  branches, remotes, settings). **History** expands the repository's own
  entries of the log inline. **Settings** opens a sheet: auto-sync on/off,
  period (chips: 15 · 30 · 60 · 180 · 360 min, from `ui.sync.git_periods_minutes`),
  network rule (any / unmetered only), auth kind (none / HTTPS token / SSH
  key: username, token/passphrase, key path — secrets in `GitCredentialStore`),
  author, pull-rebase, sync message, remove-from-list. It writes the
  `ManagedRepo` the engine reads.
* **Declared, not cloned** cards: every repo of `data/drive-git-repos.json`
  without a clone under `<root>/<name>`: label, upstream URL, `private`
  badge, `Clone into store` → the engine's Add dialog prefilled with
  `<root>/<name>` and the URL (`EngineActivity` `EXTRA_URL`, as #575).
* **History**: a collapsible section listing `SyncHistory` entries (app-owned
  JSON at `filesDir/git-sync/history.json`, capped at 200): time, repo,
  trigger (manual / scheduled), StatusLight glyph, summary; tap = details.
* **Scheduler**: `GitSyncWorker` keeps the ONE periodic request at the
  declared base period (`storage.git_sync.interval_minutes`, the floor); per
  repository it runs only when `now − lastSync ≥ repo.syncIntervalMinutes`
  (0 = the base) and the network rule holds (`ConnectivityManager` metered
  check against `repo.syncRequireUnmetered`). Every run writes a history
  entry with trigger `scheduled`. Conflicts leave the repository MERGING and
  the card red — surfaced, never swallowed.

Empty: `EmptyState(commit, "No repositories yet", "Clone a declared one below,
or add a folder", Pill "Add repository")` above the declared cards.

## 5. SYNC ▸ RCLONE and SYNC ▸ MOUNTS — same card language

**Rclone**: hero row "rclone <version> · N remotes · binary present/missing
(light)". One `DriveCard` per remote in the phone's `rclone.conf` (declared
ones badged `declared`, fleet-side `status: unreachable` ones carry their
declared `reason` as the summary and a `? Not verifiable` light): name,
type, endpoint options that are not secrets; light = last Test result
(`● On` reachable, `○ Off` failed, `? Unknown` untested); actions `Test`,
`Browse` (engine), `Edit` (engine). Then **Jobs**: one row per job of the
engine's store (declared + own): name, monospace `op src → dst`, last run;
`Run`/`Stop`; while running a `ProgressCard` with bytes, speed, ETA, files,
errors, and a 6-line log tail — the runner's stats callbacks drive it and the
run survives leaving the tab (the coordinator holds the handle). `New job` and
`Open rclone` go to the engine.

**Mounts**: one `DriveCard` per mount in the engine's store (declared badge),
`scheme://user@host:port/path` monospace, credential state (`key auth` /
`password stored` / `no credential`), light = last Test; actions `Test`,
`Browse` (engine), `Edit` (engine). Below, **Fleet connections**: the rest of
`data/drive-connections.json` (entries without a mountable `uri`) as compact
rows: name, kind, endpoint, `● On` when `status: ok`, `○ Off` with the
declared reason when `unreachable`.

## 6. APPS, BACKUPS, CONFIGS

**Apps**: a 3-column grid of 96dp tiles (`LazyVerticalGrid`), each the declared
glyph in a 56dp raised disc and the label; tap launches the resolved package
or the fallback URL; an entry that is not installed shows a `○` badge and
the tap says "<label> is not installed" (snackbar). Data: `UI_APPS_B64`.

**Backups**: **On-device mirrors** — one `DriveCard` per job of
`data/drive-mirror-jobs.json`: name, `source → destination` monospace
(relative, as declared), `--delete` badge when declared, last result and
time (`DrivePrefs`), light = last run (`● On` no failures, `○ Off` failures,
`? Unknown` never run); `Run` → a `ProgressCard` (scanned count, copied /
skipped / deleted / failed live, current file, Cancel — the mirror is a
cancellable coroutine, not a UI-thread call). Then **Fleet repositories** —
connections of kind borg/bup as rows with their light.

**Configs**: cards — *Storage access* (light = `isExternalStorageManager`,
Pill "Grant"); *Files* (default sort, hidden files, dual pane, thumbnails
on/off); *Sync schedule* (the declared base period and network rule, read
only, "declared in build.json"); *Removable storage* (the SAF grant: name,
Forget); *About* (version, sha, build time, the engine libraries with their
versions — JGit, rclone).

## 7. Viewers

**Image viewer** (`ImageViewerActivity`): black background, the pane's image
list as a `HorizontalPager`, pinch/double-tap zoom (`transformable`), a top
island (name, index/count, close) and a bottom action row: Rotate (EXIF tag),
Scan (barcode → typed actions: open URL / join Wi-Fi / add contact / add
event / dial / email / map / copy), OCR (text sheet: copy, share, save .txt /
.md beside), Info (EXIF sheet), Share, Delete (confirm). All through the
shared scan engine and `FileOps`.

**PDF reader** (`PdfReaderActivity`, #577): the native pdfium reader —
continuous and paged scrolling, pinch/double-tap zoom re-rendered sharp, text
search, outline, night mode, word copy, share, print. The Files row menu
offers **Convert PDF to…** (txt / md / html / csv, saved beside the file;
docx/xlsx/odt named as not produced on the phone) through the same
`PdfConversion` the reader's Convert menu calls. `test-drive-pdf-engine.sh`
holds the one engine and both doors to it.

## 8. Declarations added to build.json

```
ui.tabs[]                 {id,label,icon}     the five tabs, in order
ui.default_tab            "files"
ui.sync.pages[]           {id,label,icon}     git · rclone · mounts
ui.sync.git_periods_minutes [15,30,60,180,360]
ui.files.places[]         {id,label,icon,kind,dir?|path?,hero?}
ui.files.sort_keys[]      name size modified type
ui.files.default_sort     name
ui.files.filters[]        {id,label,icon,mime_prefix?|mime?|extensions?}
ui.files.archive_extensions[]  zip
ui.files.default_dual_pane true
ui.files.tabs_per_pane_max 6
ui.files.text_extensions[] (what opens in the editor besides text/*)
ui.icons._default          "circle"
```
Baked as `UI_TABS_B64`, `UI_SYNC_B64`, `UI_FILES_B64`, `UI_ICON_DEFAULT`.
`Declarations.kt` decodes them once; nothing else reads BuildConfig blobs.

## 9. What is deleted

`app/src/main/assets/drive.html` (and with it the whole `assets/` tree — #577
had already removed the vendored pdf.js), `FilesBridge.kt`,
`res/layout/activity_main.xml`, the `appcompat` and `material` (Views)
dependencies, `test/test-drive-bridge-contract.sh`.

## 10. Testers

* JVM (`app/src/test`): `FilesStateTest` (reducers: open/up/back across
  archive and folder, pane switch, tab cap, selection, tree expand,
  saveable round trip), `ArchivePathTest`, `TransferPlanTest`
  (unique names, free-space refusal, move-then-copy), `RenamePlanTest`,
  `ZipSlipTest`, `SyncHistoryTest` (cap, order, JSON round trip),
  `SyncScheduleTest` (per-repo period + network rule decision),
  `DeclarationsTest` (every declared icon resolves; tabs/pages/places parse).
* Robolectric Compose (`DriveShellTest`): the island renders the declared
  tabs in order with their tags; Files renders its tree
  (`files_pane_a` › `files_tab_strip` › `files_breadcrumbs` › `files_toolbar`
  › `files_list`) and the selection bar appears on long-press.
* Shell (`test/`): `test-drive-shell.sh` (declaration ↔ BuildConfig ↔ Kotlin
  for tabs/pages/places/filters; icons declared resolve in `IconCatalog`; no
  caption literals in Kotlin screens; no colour literal; StatusLight resources
  equal superapp's when the sibling is present; `libs:bottomnav` linked; the
  WebView shell is gone), `test-drive-files-screen.sh` (layout tree in order,
  dual pane, tree view, archive location, bookmarks, selection verbs, transfer
  progress, mutation-proved), `test-drive-sync-screen.sh` (repo card fields,
  conflicts surfaced, history, per-repo settings fields, scheduler decision,
  rclone/mounts cards, mutation-proved). `test-drive-engine-wiring.sh`,
  `test-drive-shared-store.sh` and #577's `test-drive-pdf-engine.sh` are
  repointed at the Kotlin chrome.
