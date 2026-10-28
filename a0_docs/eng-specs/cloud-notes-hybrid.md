# Cloud Notes hybrid — Miro + Notion + Obsidian in one app

Status: **Phase 0 done (storage tests run in CI), top bar done, Phase 4 (graph) done, Phase 1 folder import started, gated web image and the sync server (server half) built in CI but not yet registered on the fleet, Canvas Boards list done; the rest of Phases 2, 3, 5, 7, 8 and the sync client planned.** Owner vision (2026-10): one app that
is a Notion-style page workspace, a Miro-style infinite canvas and an
Obsidian-style linked vault. Decisions already taken by the owner:

- **Extend our own Cloud Notes 0.27.5 copy** (`ac_cloud-notes/`, MIT, de-clouded #469), not npm presets.
  New features go in separate `blocksuite/cloud-notes/*` packages (or `packages/frontend/core/src/modules/*`)
  so a re-vendor never touches them.
- **Own Yjs sync server** (Hocuspocus or y-websocket) as a container behind the edge gate.
- **Web build twice**: gated container (behind Authelia 2FA) and bundled in the APK.
- **Keep SQLite** (nbstore) if Phase 0 passes. It did.
- **Import the Obsidian vault** (`/home/user/cloud-notes`, repo `diegonmarcos/cloud-me-pvt-notes`) and any Markor data.
- Web app behind Authelia 2FA; the sync socket authenticates with a Bearer token.

Licence boundary (unchanged, enforced by `test/test-licence-clean.sh`): `packages/backend` and
`packages/common/native` stay pruned (EE licence). Nothing below may add, copy or depend on them.
The sync server is **our own** code (Hocuspocus is MIT), never Cloud Notes's EE server.

Legend: **EXISTS** = in our Cloud Notes copy today; **PARTIAL** = a base exists, gaps named; **BUILD** = must build.

---

## Phase 0 — Foundations (DONE)

### 0a. Storage verification (the "native path removed for EE licence" worry)

Reviewed end to end. **Storage is not removed and not broken by the de-clouding.**

```
JS NbStoreNativeDBApis (apps/android/src/plugins/nbstore/index.ts)
  -> Capacitor 'NbStoreDocStorage' (base64 over the bridge)
  -> NbStorePlugin.kt (Kotlin, 832 lines)
  -> uniffi cloud_notes_mobile_native  (packages/frontend/mobile-native, Rust, MIT)
  -> cloud_notes_nbstore               (packages/frontend/native/nbstore, Rust, MIT, sqlx/SQLite)
  -> <filesDir>/workspaces/<type>/<peer>/<spaceId>.db
```

The EE-pruned `packages/common/native` is **not** on this path: `licence-proof/cargo-tree-cloud_notes_mobile_native.txt`
shows `cloud_notes_mobile_native -> cloud_notes_nbstore -> cloud_notes_doc_loader` with no `cloud_notes_common`, and the licence
guard fails the push if that changes. So no Kotlin-SQLite reimplementation is needed; keeping SQLite stands.

Findings and fix:

| Finding | Action |
|---|---|
| Rust tests only used `:memory:` — nothing proved a file survives a restart | Added `doc_persists_across_reopen_byte_identical` (`mobile-native/src/tests.rs`): writes a snapshot + update through the same pool API Kotlin calls, disconnects, builds a brand-new pool on the same `.db` file, asserts byte-identical (all 256 byte values, both directions) and identical timestamp |
| JS adapter: Kotlin answers a missing snapshot with a bare `call.resolve()`, which Capacitor delivers as `{}` (truthy); the adapter's `snapshot ? ... : null` then tried to base64-decode `undefined` | `getDocSnapshot` now treats a record without `bin` as absent |
| No JS test of the adapter | Added `plugins/nbstore/index.spec.ts`: fake plugin persisting to a JSON "disk" that outlives plugin instances; asserts snapshot and updates round-trip byte-identical across disconnect + new instance, and that an unknown doc reads `null` |

Honest limit: the Rust test runs in the ship workflow's cargo step and the vitest spec in the repo's vitest run;
neither was executed on the authoring machine (no local Gradle/JS build was allowed, disk ~1.3 GB). A Robolectric
test of `NbStorePlugin` was **not** written: the plugin is a thin shim over a `.so` that Robolectric cannot load,
so it would only test mocks. The device-level proof is the `androidTest` path (below, Phase 8 acceptance).

### 0b. Bottom-nav redesign (DONE)

Declared once in `ac_cloud-notes/build.json::ui`, drawn by `libs:bottomnav` (island + `PageTabsView` top tabs),
held by the nav-shape guard (N1–N9; `ac_cloud-notes` left the exemption ledger).

| Island (bottom) | Top tabs (page strip) | Pillar | Opens today |
|---|---|---|---|
| **Home** | Recent · Daily | all | `/home` · `/journals` |
| **Pages** | All · Tags · Collections · **+ New page** | Notion | `/all` · `/tag` · `/collection` · create doc |
| **Canvas** | Boards · **+ New board** | Miro | `/all` (until a boards filter exists, Phase 3) · create edgeless doc |
| **Graph** | Global · Local · Tags | Obsidian | `/graph` · `/graph/local` · `/tag` (Phase 4, below) |
| **Search** | Search · Ask | all | `/search` · libs:ai chat drawn by the shell (`native` row) |

Mechanics: the shell knows only ids. It dispatches `cloud-notes:nav {section, page}` to the WebView; the web half
(`core/src/mobile/components/native-nav-routes.ts`) maps `<section>/<page>` to a workbench route, an action, or a
"planned" toast. The editor's own bottom tabs hide (`window.cloudNotesNativeNav`). `test/test-nav-routes.sh` fails if
`build.json::ui`, the web table, the workbench routes and the Kotlin event name drift apart.

The Pages doc tree already exists: the Home page's virtual scroller shows the `modules/organize` folders, and the top
bar's drawer (0c) lists the newest pages. What is missing is only drag-to-re-parent on mobile (Phase 5).

### 0c. Top bar (DONE)

`build.json::ui.top_bar {"drawer":true,"ai":true,"settings":["account","configs"]}` is drawn by `libs:bottomnav`'s
`AppTopBarScaffold` in a ComposeView over the editor (`NativeTopBar.kt`): hamburger drawer (the five sections and
the editor's pages, pushed by the web half through `window.CloudNotesHost.setDocs`), title, AI (libs:ai's shared chat
via `AiFleetChatPanel`) and Settings (Account launches Cloud Account; Configs fires `cloud-notes:open-settings`, which
opens Cloud Notes's own settings dialog). Search › Ask is the same chat as a full page between strip and island
(`NativeNav.NATIVE_PAGES`; the web table row is `kind: 'native'`).

**One source of the top inset.** The bar owns the status bar and reports its height; `NativeNav.applyWebMargin` puts the
page strip and the WebView (and the Ask page) below `statusBar + bar + strip`; the web half zeroes
`--safe-area-inset-top` once `cloudNotesNativeNav` is set (`setup.ts`), so nothing pads for the status bar twice.
`test/test-notes-topbar.sh` pins the wiring, the Kotlin/TypeScript event and host names, the native-page set, and
that only `NativeNav` writes a top margin. Not yet verified on a device: the exact pixel gap (needs the phone).

---

## Phase 1 — Obsidian vault interop (OBSIDIAN pillar, part 1)

Goal: the existing vault `/home/user/cloud-notes` (and Markor files) round-trips with no loss.

| Feature | State | Notes |
|---|---|---|
| Import `.md` with YAML frontmatter | PARTIAL | `widgets/linked-doc/.../obsidian.ts` + `parseFrontmatter`; frontmatter is read, but unknown keys are dropped on re-export |
| Wikilinks `[[Note]]`, `[[Note\|alias]]`, `![[embed]]` → doc links | EXISTS (import) | `obsidian-wikilink` AST extension; export writes `[[ ]]` back (`:500-:580`) |
| Folders ↔ doc tree | PARTIAL | importer returns `ImportFolder`; our workspace has collections/organize folders, no persistent tree mirror |
| Attachments (images, pdf) | EXISTS (import) | `stageImportedAsset`, `blobsFromAssets` |
| Callouts (`> [!note]`) | EXISTS | `CALLOUT_TYPE_MAP` |
| Whole-vault import UI on Android | PARTIAL (text) | explorer › "Import as vault" walks the folder (`apps/android/src/vault-import.ts`, spec-tested) and hands all notes to the Obsidian importer in one call so wikilinks resolve; attachments need a binary-capable plugin method (BUILD) |
| Export doc/workspace → vault folder (`.md` + `attachments/` + frontmatter) | BUILD | reverse of the importer; keep unknown frontmatter keys in a doc property |
| Frontmatter ↔ doc properties (tags, aliases, created) | BUILD | |
| Markor data | BUILD | Markor stored plain `.md` in a folder: same path as vault import; one migration button |

Acceptance tests:
- **A1.1** Import `/home/user/cloud-notes` fixture (checked in as a trimmed 30-note vault with frontmatter, nested folders, `![[img.png]]`, aliases, callouts): 0 import errors; note count equals `.md` count.
- **A1.2** Round trip `vault -> import -> export -> diff`: every note's text is equal after normalising line endings; all frontmatter keys present; every attachment byte-identical (sha256).
- **A1.3** Every `[[link]]` that resolved in the source resolves to a doc link; unresolved links survive as text and re-export as `[[ ]]`.
- **A1.4** Folder structure of the export equals the import's.

## Phase 2 — Links, tags, daily notes (OBSIDIAN pillar, part 2)

| Feature | State | Notes |
|---|---|---|
| Backlinks panel | EXISTS | `modules/doc-link` (`doc-backlinks.ts`), shown in doc info |
| Unlinked mentions | BUILD | title/alias text search across docs (`docs-search` indexer) minus existing links; "Link" action converts the mention |
| `[[` autocomplete with alias | PARTIAL | `@` / linked-doc widget exists; `[[` trigger BUILD |
| `#tag` inline + tag pane | PARTIAL | `modules/tag` exists (property tags, `/tag` page); inline `#tag` parsing and nested `#a/b` BUILD |
| Daily notes | EXISTS | `modules/journal`, `/journals`, Home › Daily |
| Templates | EXISTS | `modules/template-doc`, `import-template`; daily-note template wiring BUILD |

Acceptance tests:
- **A2.1** Doc B links to A; A's backlinks list B (unit, `doc-links`); after deleting the link it disappears within one tick.
- **A2.2** Doc C contains the plain text of A's title: appears under A's "Unlinked mentions"; clicking "Link" turns it into a link and moves it to Backlinks.
- **A2.3** Typing `#project/x` creates tag `project/x`; the tag pane shows the nested tree with counts that equal the doc counts.
- **A2.4** Opening Home › Daily on a new day creates exactly one doc from the daily template (idempotent across two taps and an app restart).

## Phase 3 — Miro pillar (canvas)

| Feature | State | Notes |
|---|---|---|
| Edgeless canvas: shapes, text, brush, mind map, connectors, frames, groups | EXISTS | `blocksuite/cloud-notes/gfx/*`, `blocks/frame` |
| Present frames (navigator) | EXISTS | frame navigator in edgeless toolbar |
| Boards list (all edgeless docs) | BUILD | filter on `primaryMode = edgeless`; wires Canvas › Boards |
| Magnetic anchors (connector snaps to 4/8 anchor points, follows resize) | DONE (device: snap feel) | `connector-manager.ts` eight anchors, 16 px pull; spec `connector-anchors.unit.spec.ts` |
| Frames → PDF deck (one page per frame, in order) | DONE (device: canvas rendering, share sheet) | `export-manager/deck-pdf.ts`, export menu `frames-pdf`; spec `deck-pdf.unit.spec.ts` |
| Sticky speed-capture (one tap = new sticky at viewport centre, keyboard up, next-sticky on Enter) | BUILD | edgeless note shortcut in the Canvas toolbar |
| Doc inside canvas as live portal (edits flow both ways) | DONE (device: touch editing in a scaled frame) | `portal` prop on `embed-synced-doc`, toolbar toggle "Edit in place", `syncedDocStoreOptions`; spec `portal.unit.spec.ts` (A3.5) |
| Obsidian `.canvas` ↔ Edgeless import/export | BUILD | JSON Canvas spec: text/file/link/group nodes, edges with sides; file nodes become portals |

Acceptance tests:
- **A3.1** Canvas › Boards lists exactly the edgeless docs; New board creates one and opens it in edgeless mode.
- **A3.2** Move a shape with a connector attached to its anchor: the connector endpoint stays on the same anchor (geometry unit test, tolerance 0.5 px).
- **A3.3** A 3-frame board exports a 3-page PDF; page order = frame order; page size = frame size.
- **A3.4** Speed-capture: 10 stickies in 10 taps, no overlap (bounding boxes disjoint).
- **A3.5** Portal: typing in the portal changes the source doc's text and vice versa (Yjs state equal after both edits).
- **A3.6** `.canvas` fixture (12 nodes, 9 edges, 2 groups) imports with the same counts and re-exports valid JSON Canvas (schema check).

## Phase 4 — Graph (OBSIDIAN pillar, part 3) — DONE except the device run

Shipped: `blocksuite/cloud-notes/graph` (`@blocksuite/cloud-notes-graph`: builder, local BFS, backlinks, orphans, seeded grid force layout; golden/BFS/determinism tests run in the `storage_tests` CI job) and the mobile views `/graph` and `/graph/local` (`core/src/mobile/views/graph`: canvas renderer with pan/pinch/tap, tag + orphan filters, hops 1–3, highlight search, Linked from / Links to lists). A4.1, A4.3, A4.4 are automated; A4.2 is asserted only as a 3 s CI smoke on 2,000 nodes / 6,000 edges (the 1.5 s / 30 fps phone budget needs the device).

| Feature | State | Notes |
|---|---|---|
| Graph data (nodes = docs, edges = links, tags as nodes) | BUILD | from `doc-links` index + `tag` module; pure function, unit-testable |
| Global graph view | BUILD | canvas/WebGL force layout in a new `blocksuite/cloud-notes/graph` package (no new heavy dependency without a licence + size check) |
| Local graph (current doc, depth 1–3) | BUILD | Graph › Local follows the open doc |
| Filters (tag, folder, orphans), search highlight, tap = open | BUILD | |

Acceptance tests:
- **A4.1** For a fixture workspace the graph builder returns the expected node/edge set (golden JSON), including orphans and tag nodes.
- **A4.2** 2,000 nodes / 6,000 edges: first frame < 1.5 s and pan/zoom ≥ 30 fps on the reference phone (device test, recorded in the PR).
- **A4.3** Local graph depth 2 around a fixture note equals the BFS set computed independently in the test.
- **A4.4** Graph › Global/Local no longer toast: `native-nav-routes.ts` rows change from `planned` to `route`, and `test-nav-routes.sh` stays green.

## Phase 5 — Notion pillar (pages and databases)

| Feature | State | Notes |
|---|---|---|
| Nested pages (`/page` inline, sub-page blocks) | PARTIAL | linked-doc and embed exist; true `/page` that creates a child doc and shows it as a block BUILD |
| Doc tree sidebar (Notion-style, drag to re-parent) | PARTIAL | `modules/organize` folders exist (desktop); mobile tree under Pages BUILD |
| Database: Table, Kanban, Calendar views | EXISTS | `blocksuite/cloud-notes/data-view` |
| **Gallery view** | DONE (device: UI) | new view preset beside table/kanban/calendar (cover = first image property) |
| **Relations** (row ↔ row across databases, two-way) | DONE | new property type |
| **Rollups** (count/sum/min/max/percent over a relation) | DONE | depends on relations |
| **Synced blocks** (one block, many pages) | PARTIAL | two-way text binding `SyncedBlocksExtension` done and tested (A5.6 model); creation: `/synced` slash item, block toolbar "Copy as synced block" / "Paste as synced block" / "Sync to <doc>" (`synced-block-ui.ts`, spec `synced-actions.unit.spec.ts`); intercepting a plain system paste is not done |
| Templates, favourites | EXISTS | `template-doc`, `favorite` |

Acceptance tests:
- **A5.1** `/page` in doc P creates child C, P shows a child-page block, C's breadcrumb names P; deleting C removes the block.
- **A5.2** Moving C under another parent in the tree updates both parents' blocks, with no orphan blocks.
- **A5.3** Gallery: switching a 20-row table to Gallery keeps all rows and the view round-trips through snapshot export/import.
- **A5.4** Relation: linking row r1 (DB1) to r2 (DB2) shows the back-relation on r2 in DB2.
- **A5.5** Rollup `sum(price)` over a relation updates when a related row's price changes.
- **A5.6** Synced block edited in page X appears edited in page Y, and survives an app restart (Phase 0 storage).

## Phase 6 — Sync (own Yjs server)

### Built so far (all in `ship-cloud-notes.yml`, none deployed yet)

| Piece | Where | Proven by |
|---|---|---|
| Server: Hocuspocus 4.7 (MIT) + SQLite, `createSyncServer` | `ac_cloud-notes/sync-server/` | `sync_server` job: A6.1 (two clients' offline edits converge byte-identically), A6.2 (upgrade without a valid Bearer answers 401; header or `?access_token=`), A6.3 (documents survive a graceful server restart; a hard kill mid-write is not exercised yet), auth unit tests; mutation-checked |
| Fail-closed auth: RFC 7662 introspection (the fleet's introspect-proxy) or a static set for development; no verifier configured = the process refuses to start | `src/auth.mjs`, `main.mjs` | suite + image smoke (`refusing to start`) |
| HTTP delta API (Bearer, plain fetch, so a WebView can set the header a WebSocket cannot): `GET /v1/docs?after=ms` clocks, `GET /v1/docs/<space>%2F<doc>/diff?sv=` (missing update + `x-state-vector` + `x-doc-clock`), `POST /v1/docs/<room>/updates` (204 only after the document is persisted); a read never moves a clock; websocket clients see HTTP writes live | `src/server.mjs` | suite (`sync.spec.mjs`) |
| App-side remote peer `NotesSyncDocStorage` (nbstore `./notes-sync`): `DocStorageBase` over those calls, clock poll for other clients' changes, no echo of own writes | `packages/common/nbstore/src/impls/notes-sync/doc.ts` | `client.spec.mjs` runs the real class against the real server (push acked, byte-identical read back, diff only the missing part, per-space clocks, update events once, offline push fails and the retry converges); mutation-checked |
| Image `ghcr.io/<owner>/cloud-notes-sync` (node 22, non-root, `/data` volume, healthcheck) | `sync-server/Dockerfile` | image smoke: healthz, 401 on a bare upgrade, unconfigured start refused; pushed from main only |
| Gated web image `ghcr.io/<owner>/cloud-notes-web` (nginx, SPA fallback, `/healthz`) | `deploy/web/`, job `web_image` | smoke: health, shell, deep link, no-store shell; pushed from main only |

### Not built: the client half and the fleet registration

1. **Workspace flavour wiring.** The storage class exists and is tested; what is missing is the app using it: a `remotes` entry for `NotesSyncDocStorage` in the `local` flavour's engine options when a sync URL is configured (`packages/frontend/core/src/modules/workspace-engine/impls/local.ts`), the class added to the android nbstore worker's storage list (`apps/android/src/nbstore.worker.ts`, via `@cloud-notes/nbstore/notes-sync`), and the token: `libs:auth`'s fleet token provider through the existing `auth-access-token-channel` to the worker. A6.4 (airplane-mode edit survives app kill and syncs) and A6.5 (APK web and container web agree) need the app running.
2. **Registration on the fleet** (two repos, generator-driven, so not done blind): `cloud-u-containers/<service>/` for the web image (like `user-comm_matrix-element`: `build.json` with `proxy.primary.auth: two_factor`, image `cloud-notes-web`) and for the sync image (proxy tier public-protected: Authelia for the web, Bearer through the introspect-proxy for `/` websocket and `/v1/docs`; volume for `/data`; env `SYNC_INTROSPECT_URL`), then the cloud-infra `1_cloud-configs` derive (`link-builds`, `consolidate`, `derive`) and `ship.yml` for the two services, only when no other Ship run is active. Open decision for the edge: a browser/WebView `WebSocket` cannot send an `Authorization` header, so the socket authenticates with `?access_token=` (the server accepts both); the edge rule must read the same token and must not log the query string.

Design: a `cloud-notes-sync` container (Hocuspocus, MIT) on the fleet, reached only through the edge gate.
Workspace flavour `local` stays the default; a new flavour `cloud-notes-sync` replaces the removed Cloud Notes-cloud flavour.
Documents are the same Yjs updates nbstore already stores, so SQLite stays the local source of truth and the server a peer.

| Feature | State | Notes |
|---|---|---|
| Local-first storage and offline edit | EXISTS | Phase 0 |
| Doc/blob sync protocol client (peer in nbstore: pull/push clocks) | PARTIAL | nbstore has `doc_sync`/`blob_sync` peers; the Cloud Notes-cloud transport was removed — BUILD a websocket peer |
| Sync server container + persistence (SQLite or Postgres volume) | BUILD | Hocuspocus + `onAuthenticate` |
| Auth: web behind Authelia 2FA; WebSocket with `Authorization: Bearer` (fleet token, `libs:auth`) | BUILD | token provider already in the fleet (`FleetTokenProvider`); socket rejects without it |
| Web build (gated container) + bundled in the APK | BUILD | one source, two targets; CI builds both |
| Conflict policy | EXISTS | CRDT merge; no manual conflicts |

Acceptance tests:
- **A6.1** Two clients edit the same doc offline (10 edits each, interleaved), reconnect: both converge to byte-identical Yjs state; no lost edit.
- **A6.2** Socket without Bearer or with an expired one is refused (401 on upgrade); web without Authelia session redirects to the gate.
- **A6.3** Kill the server mid-write and restart: no doc loses acknowledged updates (compare state vectors).
- **A6.4** Airplane-mode edit survives app kill and syncs on reconnect (extends A0 round trip with the server as second peer).
- **A6.5** APK-bundled web and container web produce the same doc state for the same edit script.

## Phase 7 — Search, AI, optional two-way vault sync

| Feature | State | Notes |
|---|---|---|
| Full-text search | EXISTS | `docs-search`, indexer in nbstore (`/search`) |
| Quick switcher (`Ctrl-O`-style, fuzzy title/alias) | PARTIAL | `quicksearch`; alias search BUILD |
| Search › Ask (AI over the vault) | BUILD | `libs:ai` gateway (fleet), retrieval over the nbstore index; Cloud Notes's cloud AI stays removed |
| Two-way sync of a vault folder (shared storage or Cloud Drive) | BUILD, optional | watcher + three-way merge on frontmatter-stable `.md`; conflicts → `name (conflict).md`, never overwrite |

Acceptance tests:
- **A7.1** Quick switcher finds a note by alias with a typo (edit distance 1) in the top 3.
- **A7.2** Ask returns an answer citing ≥1 doc with working links; with the gateway off it shows an error, not an empty success.
- **A7.3** Edit a `.md` in the synced folder: the doc updates within 10 s; edit both sides while offline: two files result, none lost.
- **A7.4** Search › Ask no longer toasts; nav test stays green.

## Phase 8 — Fleet links and release

| Feature | State | Notes |
|---|---|---|
| Cloud Drive: attach a file from Drive, save export to Drive | BUILD | fleet IPC (`fleet-ipc.md`); no direct storage permission |
| Cloud Mail: send a doc as email / save a mail as a note | BUILD | fleet IPC to ac_cloud-mail (no edits to mail) |
| Share targets (Android share sheet → new note) | PARTIAL | `ExternalFilePlugin` opens files; ACTION_SEND intent BUILD |
| Device acceptance run (androidTest on the shipped APK) | BUILD | uses the Phase 0 round trip, nav taps, a vault import |
| Perf budget (cold start, 5,000-doc vault) | BUILD | numbers recorded per release |

Acceptance tests:
- **A8.1** Share text from another app creates a note containing it; Drive attach/save and Mail send/save work through fleet IPC, with the other app absent giving a visible message.
- **A8.2** `androidTest`: write doc → force-stop → relaunch → byte-identical read, on the CI emulator or device farm.
- **A8.3** Cold start < 2.5 s and vault of 5,000 docs searchable < 300 ms on the reference phone.
- **A8.4** All guards green: licence-boundary, nav-shape, capacitor-surface, root-scope, identity, `test-nav-routes`.

---

## Cross-cutting rules

- Every phase lands behind its guard: a `test/test-*.sh` derived from source, not a hand-kept list (see `test-nav-routes.sh`).
- Every `planned` row in `native-nav-routes.ts` names its phase; closing the phase flips the row to `route` in the same commit.
- Keep build size honest: `build.json::release.artifact_assert` floors stay as they are; any new native lib needs a licence note in `licence-proof/`.
- Nothing in these phases may re-introduce Cloud Notes, telemetry or EE code; the licence guard is the gate.

## Order and rough sizing

1 Vault interop (M) → 2 Links/tags/daily (M) → 3 Canvas (L) → 4 Graph (M) → 5 Notion (L) → 6 Sync (L) → 7 Search/AI (M) → 8 Fleet/release (M).
Phases 1–2 first because they make the existing vault usable on the phone; 6 can start in parallel (server is independent of the editor).

## Acceptance checks that still need a device

A4.2 (graph 1.5 s / 30 fps), A6.4 (airplane-mode edit survives kill), A6.5 (APK web and container web agree), the 0c top-bar pixel gap, frames-PDF canvas rendering and share sheet, snap feel of magnetic anchors, Gallery UI, touch editing inside a portal. Everything else above is asserted in the `storage_tests`, `sync_server` and `blocksuite_tests` CI jobs.
