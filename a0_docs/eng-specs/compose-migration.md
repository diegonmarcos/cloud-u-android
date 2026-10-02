# Compose migration: audit, plan, ratchet (#773)

The owner wants every first-party app UI in Jetpack Compose. This page has the audit,
the order, the risks, and how progress is measured. The data behind it is
`1_cicd/src/data/compose-migration.json`, and that file wins wherever the two disagree.
Every number below is a snapshot. To get the live table, run:

```sh
python3 1_cicd/src/scripts/cloud-android-compose-ratchet.py            # table + verdict
python3 1_cicd/src/scripts/cloud-android-compose-ratchet.py --list aa_cloud-superapp   # the files behind a row
```

## How progress is measured: the ratchet

`compose-ratchet-guard.yml` runs on every push. It counts four things per **unit**. A unit
is a top-level app directory, or one lib under `ab_cloud-libs-shared/libs/`. Only main
source sets count, never tests or vendored trees:

| metric | counts |
|---|---|
| `view_screens` | Activity / Fragment / Dialog / PopupWindow classes whose file still builds Views (or sets a content view and hosts no Compose) |
| `layout_xml` | `res/layout*/*.xml` |
| `view_ui_files` | source files that build or inflate Views (`TextView(`, `.inflate(`, `findViewById`, …) |
| `custom_views` | classes extending a View type (`AbstractComposeView` excluded) |

The job fails in four cases:

- A count goes **above** its baseline. New UI is Compose.
- A count goes **below** its baseline. Lower `units[<id>].baseline` in the same commit as
  the migration, so the gain is recorded and cannot slide back.
- A unit is not declared and has View UI. It gets an implicit baseline of 0, so a new app
  or lib written in Views fails on its first push.
- A declared fork root has disappeared. If it went quietly green instead, a missing root
  would count zero, which looks exactly like a finished migration.

`1_cicd/src/scripts/test/compose-ratchet.test.sh` runs the real rules against a fixture
tree. It covers 16 cases: regression, an untightened baseline, a new unit, mixed files,
comments and strings, Compose `Button(`, fork roots, a missing root and tests. Each
guard branch was mutated once, and every mutant fails at least one case.

## Audit at #773 start (main @ 183b23491)

Fleet total for the in-scope units: **114 View screens, 60 layouts, 175 View-building
files, 52 custom Views.**

| unit | UI | screens / layouts / view files / custom | wave | size |
|---|---|---|---|---|
| aa_cloud-superapp | Views (Compose only for the bottom-nav island and the libs:auth surface) | 38 / 25 / 63 / 9 | 2 | XL |
| ac_cloud-nav | Views + MapLibre | 13 / 1 / 13 / 8 | 4 | L |
| ac_cloud-c3 | Views (copies of SuperApp C3 pages) | 12 / 7 / 11 / 0 | 4 | L |
| ac_cloud-me | XML shell + Views | 6 / 2 / 5 / 0 | 3 | M |
| ac_cloud-myterminal | programmatic Views | 5 / 0 / 6 / 0 | 3 | M |
| ac_cloud-wallet | XML shell (libs:wallet is Compose) | 2 / 1 / 2 / 0 | 3 | S |
| ac_cloud-browser | FrameLayout engine host | 2 / 1 / 1 / 0 | 3 | S |
| ac_cloud-dialer (our additions only) | XML + ViewBinding on Fossify bases | 2 / 3 / 2 / 0 | 4 | M |
| ac_c3-morpheus / -watchtower / -watchdog | programmatic Views, 1 screen each | 1 / 0 / 1 / 0 each (watchdog 1/0/0/0) | 3 | S |
| ac_cloud-drive | Compose; PdfReaderActivity left | 1 / 0 / 1 / 1 | 0 | S |
| ab_cloud-libs-shared (engine-APK hosts) | Views | 1 / 0 / 1 / 0 | 5 | S |
| libs:keyboard | settings Compose, IME surface Views | 0 / 15 / 23 / 19 | 5 | XL |
| libs:maps | Views around MapLibre | 10 / 0 / 10 / 1 | 3 | L |
| libs:mail | XML + RecyclerView | 6 / 8 / 6 / 0 | 1 | M |
| libs:appstore | programmatic Views | 3 / 0 / 10 / 0 | 1 | L |
| libs:battery | dialogs + icon view | 2 / 0 / 3 / 1 | 1 | S |
| libs:launcher-onehand | gesture canvases | 0 / 0 / 3 / 4 | 1 | M |
| libs:translate, libs:voice, libs:media | IME bars | 0/0/2/3, 0/0/1/2, 0/0/1/1 | 5 / 5 / 1 | S |
| libs:updater, search, ops, chat, firewall, datamanager, kde-connect, launcher-apptabs, fin, browser | one screen each | 1 / 0 / 1–2 / 0–1 | 1 (fin, browser: 3) | S (kde-connect M) |
| libs:launcher-zoomies, panoramaviewer | one custom view | 0 / 0 / 0 / 1 | 1 | S / – |
| ac_cloud-calc, -writer, -c3-webserver, libs:bottomnav, libs:scrollbar | Compose | 0 / 0 / 0 / 0 | 0 | – |

**Out of scope.** Each unit is listed with its reason in the JSON:

- **Web wrappers:** agenda, contacts and news. Each is a single WebView layout plus a JS bridge.
- **Vendored trees:**
  - Compose already: mail (sterna), matrix (Element X), media-center (ReFra) and vault
    (Bitwarden).
  - React Native: chat (Mattermost).
  - WebView: notes (AFFiNE), office (Collabora) and code (Acode).
  - Additions with no UI: termux, nix-on-droid and camera (GrapheneOS).
- **Dialer:** half in scope. Only our additions are counted, through `roots`:
  ScreeningLogActivity, CallProviderChooserSheet and their three layouts.

## Shared kit (phase 2)

These pieces are what app migrations build on:

- **libs:bottomnav.** Already Compose (#531/#565). `BottomNavIslandView` is the View
  interop host that XML shells drop in.
- **libs:scrollbar.** The Compose half is shared. The View half stays until media-center's
  RecyclerView grids go.
- **libs:ui-kit** (new in #773). Contents:
  - `CloudKitTheme(KitPalette)` over the launcher palette roles.
  - Section header, selectable tile, card, settings row, switch row and confirm dialog.
  - Interop shims: `KitComposeFragment`, a Fragment whose whole body is a composable for
    hosts that still navigate by FragmentManager, and `Context.kitComposeView { }`, for
    dropping kit composables into a View page that has not migrated yet.

  Pages take their colours from `KitPalette`, never from literals. That is the Compose form
  of the `LauncherPalette` rule `test-launcher-theme-palette.sh` enforces.

## Order and why

| wave | contents |
|---|---|
| 0 | Already Compose; the ratchet keeps them there. |
| 1 | The shared kit, then the lib screens. `libs:updater` comes first, because one migration reaches eleven apps. Then the SuperApp-hosted libs (appstore, mail, battery, …). |
| 2 | **SuperApp**, one leaf screen at a time; detail below. |
| 3 | Small View apps. c3-watchdog (46 lines) is the template migration. Then morpheus, watchtower, browser, wallet, me and myterminal. |
| 4 | Apps that reuse SuperApp pages (c3) or centre on a map (nav, with libs:maps), plus the dialer additions. |
| 5 | IME surfaces: libs:keyboard bars, translate, voice and the engine-APK hosts. |

### SuperApp order (wave 2)

1. Leaf Configs pages: Import, Presets, OneHand, Control, DNS, WireGuard.
2. Profile/Account (#766): ProfileFragment hosts libs:auth already.
3. Store and Apps Mesh (libs:appstore).
4. Recent and Phone apps.
5. Cloud pages: C3 health and mesh, calendar, tasks, news, RSS.
6. Launcher pages: TileGrid, Section*, HomeGrouped/Drawer, AggregatorStack.
7. Last, the `ShellActivity` chrome: activity_main.xml, drawer, status strip and the
   overlay services.

## Risks (read before migrating)

- **SuperApp shell testers.** `aa_cloud-superapp/test` has 106 shell testers that grep
  Kotlin source. Renaming a function or moving a string breaks them. Rewrite the assertion
  against the new source in the same commit; never delete it.
- **Debug API and device checks.** These find UI by view tag and content description. A
  Compose port keeps the same strings as `Modifier.testTag` plus semantics
  `contentDescription`.
- **Robolectric suites assert on View trees.** Port them to `createComposeRule` with the
  same tags:
  - appstore: 9 Store*/AppsMesh* tests.
  - `BottomNavSelectedPillTest` and `ShellBottomNavCollapseTest` inflate activity_main.
- **Overlay windows.** FloatingNavService and ScreensaverService draw in their own windows,
  and the IME (`InputMethodService`) has the same problem. A ComposeView there needs a
  `ViewTreeLifecycleOwner` and a `SavedStateRegistryOwner` set on the window root before it
  attaches, or it crashes.
- **Keep native surfaces as `AndroidView`.** Do not rewrite KeyboardView, MapLibre
  `MapView`, GL surfaces or PdfRenderer canvases. Migrate the chrome around them.
- **c3 duplicates SuperApp pages.** Migrate the SuperApp's copy first and share the
  composable.

## Remaining plan per app (fan-out)

Each row is one agent's brief. The same workflow applies to every agent:

1. Pull main.
2. Read `--list <unit>`.
3. Migrate the screens.
4. Lower the baselines.
5. Keep or port the testers.
6. Land green by full sha.

See `compose-migration.json` → `units[].screens_left` / `risks` for the per-unit detail.
