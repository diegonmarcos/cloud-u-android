# #801 Cloud Browser upgrade — implementation plan (PLAN ONLY)

Written 2026-10-03 for the implementing agent (#802). Every path is relative to
`cloud-u-android/`. Line numbers drift; symbols do not. Read the real file before editing.

## 0. What exists today (read, not assumed)

| Piece | Where | State |
|---|---|---|
| App shell | `ac_cloud-browser/app/.../MainActivity.kt` (88 lines) | FrameLayout host; hands `BuildConfig.UI_BROWSER_CONFIG_B64` to the shared fragment; handles http/https VIEW intents; wires Updater. |
| Auth mission | `ac_cloud-browser/app/.../AuthMissionActivity.kt` | #684/#689 cookie capture for fleet apps. Untouched by this plan. |
| Engine + chrome | `ab_cloud-libs-shared/libs/browser/` (shared **by reference**, class `gui`, 1735 lines, Views) | `BrowserHostFragment` (670 lines): GRID / DETAIL / HISTORY modes, address bar with on-device suggestions, WebView, `PopupMenu` overflow. `BrowserTabPrefs` (tabs, pin, group, order, seed-once), `BrowserHistory` (cap 500, on-device only), `BrowserSettings` (ONE key: `search_engine_id`), `BrowserConfig` (per-app content from `build.json::ui.browser`), `BrowserSearch` (URL-or-query), `BrowserSuggest`, `BrowserTabGrid` (RecyclerView). `desktopMode` is a field, not persisted. |
| Overflow menu today | `BrowserHostFragment.showBrowserMenu` | 8 flat items: Pin/Unpin, History, Search engine, Open in browser…, Reload, View Desktop/Mobile, Copy URL, Share…. No sections, no bookmarks, no find, no downloads, no zoom, no reader, no incognito, no site permissions, no clear data. |
| Settings | `BrowserSettings` → prefs file `browser_settings` | One key. Declared in `libs/core/src/main/assets/fleet-config.json` as `browser_settings` (class `config`, used_by `lib-browser`); `tabs` (config; `active_url`, `defaults_seeded_v1` device); `browser_history` (content). |
| Debug API | `libs/devtools/AppDebugServer` on loopback port **38145** (`libs/devtools/debug-ports.json`) | Only the universal routes (docs, system/*, diagnostics/*, fleet/*, net/*). **The browser registers no route of its own.** The server parses the request line + headers only: **no request body is read**. `route(group, ops, handler)` self-documents into `/api/docs`. Fleet token required (`Authorization: Bearer`). devtools is a `mesh` lib with `budget_lines: 1670` (`1_cicd/src/data/lib-classes.json`); `src/main` is 1495 lines today → 175 lines of headroom. |
| Fleet config (#783) | `libs/core/FleetConfig.kt` + `FleetConfigProvider` (`<pkg>.fleetconfig`, CONSTELLATION_DATA) | Every declared `prefs`/`encrypted` store of class config/secret exports and imports with no per-app code. `fleet-config-guard.yml` fails the build on any store the manifest does not declare. The browser app entry (`apps.browser`) says "keeps no configuration of its own". |
| Fleet Account (#778) | `aa_cloud-superapp/.../profile/Account*.kt` | S/R/L vault-bundle files in the SuperApp (EncryptedFile). **No peer-facing read of S/L exists**; peers are reached by the SuperApp (Runtime reads `<pkg>.fleetconfig` exports, `ITextTools`), never the other way round. "Server → runtime" applies `FleetConfig.import` per app. |
| Vault bundle shape | `cloud-vault/C_A1-configs/schema.json` (private repo; skeleton mirrored in `aa_cloud-superapp/build.json::ui.profile.infos.schema`) | `about.profile` {name,email,location,website,titles}; `about.addresses[]` {first/middle/last/full name, email, phone, street_name, street_number, apartment, full_address, city, state, zip, country, label}; `autocomplete.*` (keyboard clipboard lists incl. `personal_data`, `cloud_keys`); `ai.tokens.openrouter_*`; git; mail; mesh; electronics. **Cards are NOT in the bundle**: `cloud-vault/A_A0-Providers/A_SERVICES-BURO/a4-banks/cards.json` (fields label, cardholder_name, card_number, expiration_month/year, cvv, card_type, billing_address, notes) has no `C_A1-configs/<section>/sources.json`. |
| OpenRouter token | `libs/text-tools` `ITextTools.revealAiKey(providerId)` via `TextToolsClient` | The pattern is `ac_cloud-search/app/.../data/Account.kt`: read per use, never stored, never logged, never in a debug answer. |
| cloud-search (#779/#797) | `ac_cloud-search/core/` (plain-JVM `search-core`: `Chat`, `SearchConfig`, `Engine`, `Http`, `Cache`), `ac_cloud-search/app/.../ui/AssistantPages.kt` (Compose chat), `SearchDebugApi.kt` (route pattern), `build.json::search.ai` (OpenRouter URLs, `default_model: openrouter/auto`, `web_plugin`, `native_web_param`), `search.engines` (google/duckduckgo/brave/qwant), `open_with.fleet: browser`. | Search vertical = subpages `web` (opens an engine URL in cloud-browser) + `chat`. |
| Bitwarden | `ac_cloud-vault/` **is a rebranded fork of Bitwarden Android** (`v2026.7.1-bwpm`, appId `com.diegonmarcos.cloudvault`), shipping the `AutofillService` (`BIND_AUTOFILL_SERVICE`) and the Credential Provider (passkeys). Server: `vaultwarden` (oci-apps → vault.diegonmarcos.com). SDK crates GPL-3.0-only. |
| Crawlee backend | `scrappers-api` (oci-apps → api.diegonmarcos.com; cloud-services-mcp `infra_scrappers_*`) | Live platforms: apify, cloudflare, **crawl** (url + CSS selector → text list), firecrawl, instagram, linkedin, pinterest, youtube. Targets are declared in `scrappers.json`, run by Dagu. Its auth/path contract was not readable from this container (knowledge_spec errored) — #802 must read `cloud-infra` for the route before Increment 7. |
| Compose ratchet (#773) | `1_cicd/src/data/compose-migration.json` | `libs:browser` baseline `view_screens 1, view_ui_files 2, custom_views 1`; `ac_cloud-browser` `view_screens 2, layout_xml 1`. **Any new screen must be Compose** (`libs:ui-kit` `KitComposeFragment` / `Context.kitComposeView`); a new View Fragment/Dialog/PopupWindow fails the guard. Note: `PopupMenu` is not in `screen_bases`, so deleting it is free. |
| Tests | `ac_cloud-browser/app/src/test/.../BrowserFeaturesTest.kt` (15 JVM tests), `ac_cloud-browser/test/*.sh` (3 shell testers run by `cloud-android-test-engine.sh` from `build.json::tests`). CI: `1_cicd/src/cicd/ship-cloud-browser.yml` runs testers + `:app:testDebugUnitTest` before build. |

### 0.1 Design decisions (justified once, applied everywhere)

1. **Declared, not coded.** The menu, the settings catalogue, the add-ons, the agent's tools: each is a JSON block in `ac_cloud-browser/build.json::ui.browser.*`, baked by `app/build.gradle` into `BuildConfig.*_B64`, parsed by one Kotlin reader, served verbatim by a debug route. A tester reads the same JSON and asserts every declared id is reachable in comment-stripped Kotlin (the `test-browser-features-wired.sh` recipe).
2. **Writes over the debug API need a body.** `AppDebugServer` reads no body. Increment 1 adds `Content-Length` body reading (~15 lines, inside the 175-line budget) and passes it to handlers as `query["_body"]`. This is an engine fix in the shared lib (FIRE RULE 1), with its own JVM test. Scalar setting writes still work with query params; bulk imports (profile JSON) use the body.
3. **Profile data arrives through #783, not through a new channel.** The browser declares an `encrypted` store `browser_autofill` (class `secret`) in `fleet-config.json`. The SuperApp's Account ▸ Runtime "server → runtime" already does `FleetConfig.import` per app; it needs ONE new cockpit runtime block mapping `about.profile` + `about.addresses` → `browser_autofill` keys. The browser never reads the SuperApp. A second path, import-from-file (Chrome/Firefox/Bitwarden JSON or CSV), writes the same store.
4. **Card numbers and CVV never enter the browser.** The profile holds card *metadata* only (label, cardholder, last4, exp month/year, type) for display; filling a card or a login is the Cloud Vault (Bitwarden fork) AutofillService's job through the Android Autofill Framework. This also removes the whole "secret in the browser" surface.
5. **Bitwarden add-on = Android Autofill Framework, not the Bitwarden SDK.** Reasons: the fork already ships the AutofillService and passkey provider; the SDK is GPL-3.0-only (would bind the browser APK); a second vault session in the browser would need master-password UX and a second unlock; the Framework path is what Chrome/Brave/DuckDuckGo use on Android. The add-on is therefore: make the WebView an autofill participant, a page action to request fill, a status row + deep link to set Cloud Vault as the autofill service, and a save-login hand-off (framework `onSaveRequest` fires from the service itself). Verify on device (see risks).
6. **Search + AI Chat are reused by lifting cloud-search's Search vertical into a shared Compose `gui` lib** (`libs/search-page`) that both APKs link by reference, plus `search-core` linked by reference as a module (`"dir": "../ac_cloud-search/core"`, same mechanism `settings.gradle` already applies to libs). No copying. cloud-search keeps its shell; the browser hosts the same composables in a `KitComposeFragment`.
7. **The agent acts only with consent.** Every tool is declared with `mutating: true|false`; mutating tools (click, fill, navigate, tab ops) show a confirmation sheet naming the exact action and target; the agent never receives vault or autofill values; tool execution is same-origin to the current tab unless the user confirms a navigation; a per-turn tool cap. Reading tools (read, summarize, find, scrape) run without a prompt.

## 1. Overflow menu — target structure and feature inventory

### 1.1 Menu (declared in `build.json::ui.browser.menu`, rendered as a Compose bottom sheet)

Modelled on DuckDuckGo Android (top row of icon actions, then grouped rows) and Brave Android (sections with dividers; shields entry).

```
Row 0 (icons): ← back · → forward · ↻ reload · ☆ bookmark · ⤓ download page · ⋯
Section "Tabs":       New tab · New private tab · Tab groups… · Close tab
Section "Page":       Find in page · Share… · Copy URL · Print / Save as PDF · Add to home screen · Desktop site [toggle] · Reader mode [toggle] · Zoom / text size… · Translate (via libs:text-tools translate, if bound)
Section "Privacy":    Site settings (this site) · Clear browsing data… · Shields: block images / block JS [toggles]
Section "Library":    Bookmarks · History · Downloads · Scraper exports
Section "Add-ons":    one row per enabled add-on (Scraper · Cloud Vault · AI chat · Search) + "Manage add-ons…"
Section "Settings":   Settings · Profile · About
```

JSON shape (one item):
```json
{"id":"find","section":"page","label":"Find in page","icon":"search","action":"find","kind":"action|toggle|screen","requires":[]}
```
`requires` names a settings key or add-on id that must be true for the row to be enabled; the debug route reports `enabled` + `why` per row (the `SearchDebugApi.verticals` convention).

### 1.2 Basic browsing features — exists vs missing

| Feature | Today | Plan (increment) |
|---|---|---|
| Tabs, pin, group, reorder, previews | yes (`BrowserTabPrefs`, grid) | keep; expose via `/api/browser/tabs*` (I2) |
| New tab (URL or search) | yes (dialog) | keep; menu row (I3) |
| Address bar suggestions | yes, on-device | keep |
| Search engine choice | yes (3 engines, 1 setting) | becomes catalogue key `search_engine_id` (I2) |
| Desktop site | toggle, not persisted, UA hardcoded | catalogue key `desktop_mode` + UA strings move to `build.json::ui.browser.user_agents` (I2/I3) |
| Reload / Copy URL / Share / Open in other browser | yes | keep, re-homed in sections (I3) |
| History (list, clear) | yes | keep; `/api/browser/history` (I2); Compose screen (I3) |
| Back / forward | **missing** (system back only; no `canGoBack` handling) | I3: WebView back stack + `OnBackPressedCallback` |
| Find in page | **missing** | I3: `WebView.findAllAsync/findNext` + Compose find bar |
| Bookmarks + folders | **missing** | I4: `BrowserBookmarks` store (`browser_bookmarks`, prefs, config) with folder path; menu star; screen |
| Downloads manager | **missing** (no `DownloadListener`) | I4: `DownloadManager` enqueue with cookies/UA; `browser_downloads` index (device); screen |
| Reader mode | **missing** | I3: on-device extraction JS (Readability-style heuristic) rendered as `loadDataWithBaseURL`; toggle |
| Zoom / text size | partial (pinch only; `builtInZoomControls`) | I3: catalogue key `text_zoom` (50..200) → `settings.textZoom`; `force_zoom` |
| Print / Save as PDF | **missing** | I3: `PrintManager.print(webView.createPrintDocumentAdapter)` |
| Add to home screen | **missing** | I4: `ShortcutManagerCompat.requestPinShortcut` |
| Incognito / private tab | **missing** | I5: separate `WebView` with `CookieManager` isolation is not possible per-WebView on Android; implement as a private *session*: `WebSettings` cache off, `saveFormData` off, no history/preview recording, cookies cleared on close; a `private: true` flag on `BrowserTab`. State this limit in UI. |
| Tab groups | yes (grid headers) | menu row "Tab groups…" lists groups (I3) |
| Per-site permissions | **missing** (`WebChromeClient` ignores geolocation/camera/mic) | I5: `browser_site_permissions` store (prefs, config; key `host:perm` → allow/deny/ask); `onPermissionRequest`, `onGeolocationPermissionsShowPrompt`; Site settings screen |
| Clear browsing data | partial (history only) | I5: dialog with checkboxes: history, cookies (`CookieManager.removeAllCookies`), cache (`clearCache`), site storage (`WebStorage.deleteAllData`), tab previews, downloads index |
| Default search engine | yes | I2 catalogue |
| Block images / JS per site (shields) | **missing** | I5: `loadsImagesAutomatically`, `javaScriptEnabled` per host from `browser_site_permissions` |
| Translate page | **missing** | I3: selection/whole-text through `TextToolsClient.translate` when a serving app is bound, else row disabled with `why` |
| Set as default browser | **missing** | I3: `RoleManager.ROLE_BROWSER` request row in Settings |

## 2. Increments (ordered; each is shippable and leaves CI green)

Conventions used below:
- **Routes** are served on `127.0.0.1:38145` under `/api/browser/...` with the fleet token; from this container, acceptance runs through cloud-superapp-mcp `superapp_call app=cloudbrowser path=browser/<op>` (the MCP adds the token). Every op is registered with an `Op(op, params, description)` so `/api/docs` lists it, and **no op is named `state`** (update-ack-guard owns `GET /api/state`).
- **JVM tests** live in `ac_cloud-browser/app/src/test/.../` (pure logic only; `org.json` is on the test classpath).
- **Shell testers** live in `ac_cloud-browser/test/test-browser-*.sh`, follow the comment-stripped-Kotlin recipe of `test-browser-features-wired.sh`, and end with a mutation block (break one property on a copy → red). `build.json::tests` already runs every `test/*.sh`.
- **Every new store** gets a `fleet-config.json` entry (kind, class, doc, used_by, key overrides) in the same commit, or `fleet-config-guard` goes red.
- Sizes: S ≤ 150 lines, M ≤ 400, L ≤ 900 (Kotlin + JSON + tests).

### I1 — Debug API can receive a body (shared engine fix) — S

Files: `ab_cloud-libs-shared/libs/devtools/src/main/java/com/diegonmarcos/superapp/devtools/AppDebugServer.kt` (`handle`: after the header loop, if `Content-Length` > 0 read exactly that many chars, cap 256 KiB, put under `query["_body"]`; `docsJson` mentions it), `libs/devtools/src/test/.../AppDebugServerBodyTest.kt` (a route sees `_body`; oversize → 413; no body → key absent). Budget: stays under 1670 (`cloud-android-lib-classes.py` L3).
Contract: `POST /api/<group>/<op>` with `Content-Type: application/json` → handler `query["_body"]`.
Acceptance: `/api/docs` of any fleet app lists the body rule; the browser's `profile/import` (I6) works end to end.

### I2 — Settings catalogue + `browser` route group + Runtime/Drift coverage — M

Files:
- `ac_cloud-browser/build.json::ui.browser.settings[]` — the catalogue: `{key, type: bool|int|string|enum, default, values?, label, section, class: config|device, doc}`. Initial keys: `search_engine_id` (enum of `search_engines`), `desktop_mode`, `text_zoom`, `force_zoom`, `reader_mode_default`, `javascript`, `load_images`, `block_third_party_cookies`, `save_form_data`, `autofill_enabled`, `restore_tabs_on_start`, `private_by_default`, `download_dir`, `homepage`, `theme` (follow/dark/light), `addons_enabled` (string set).
- `ac_cloud-browser/build.json::ui.debug_api.group = "browser"` (cloud-search's shape); `app/build.gradle` bakes `UI_BROWSER_SETTINGS_B64` and `DEBUG_API_GROUP`.
- `libs/browser/.../BrowserSettingsCatalogue.kt` (new, pure): parse, typed defaults, validate a `set(key, raw)` against type/enum/range, `snapshot(prefs)` → JSON with `_types` like FleetConfig.
- `libs/browser/.../BrowserSettings.kt`: generic `get/set` over the catalogue; keep `searchEngineId()` as a thin alias so existing tests pass.
- `ac_cloud-browser/app/.../debugapi/BrowserDebugApi.kt` + `BrowserDebugApiProvider.kt` (manifest `<provider>` not exported, the `SearchDebugApiProvider` trick) registering: `settings` (all, typed), `settings/catalogue`, `settings/set?key=&value=`, `tabs`, `tabs/open?url=`, `tabs/close?url=`, `tabs/pin?url=&on=`, `history?n=`, `history/clear?confirm=1`. Tab/history ops act on the stores (`BrowserTabPrefs`, `BrowserHistory`) which the fragment re-reads on resume; a `BrowserBus` (one `MutableSharedFlow<Change>` object in libs:browser) lets a live fragment refresh.
- `fleet-config.json`: `browser_settings` doc updated to "the settings catalogue (ac_cloud-browser/build.json::ui.browser.settings)"; key overrides `download_dir: device`; `apps.browser.items[0]` replaced by the real statement.
- Generated catalogue doc: `ac_cloud-browser/build.sh catalogue` writes `ac_cloud-browser/docs/settings-catalogue.md` from `build.json` (python3); the ship workflow's "generated files are up to date" step covers it by adding the path to the engine's generated-file list (`1_cicd/src/scripts/cloud-android-ship-repo-workflow-engine.sh` — read it first; it already regenerates `test-coverage.json`).
Tests: `BrowserSettingsCatalogueTest` (defaults, enum refusal, range, `_types`), `test-browser-settings-catalogue.sh` (every catalogue key is read somewhere in comment-stripped `libs/browser` + app Kotlin; no `getSharedPreferences("browser_settings"` outside `BrowserSettings`; the generated doc matches; mutations).
Acceptance (device): `browser/settings` returns every catalogue key with its value; `browser/settings/set?key=text_zoom&value=130` then `browser/settings` shows 130 and the page re-renders; `set?key=text_zoom&value=900` → `{"ok":false,"error":"..range.."}`; SuperApp Account ▸ Runtime shows `browser › browser_settings › text_zoom = 130`; Account ▸ Drift server↔runtime lists it.

### I3 — Overflow menu as declared data + page actions (back/forward, find, reader, zoom, print, desktop, translate, default-browser) — L

Files:
- `ac_cloud-browser/build.json::ui.browser.menu` (sections + items as in §1.1), `::ui.browser.user_agents {mobile, desktop}`; `app/build.gradle` bakes `UI_BROWSER_MENU_B64`.
- Browser gets Compose: `ac_cloud-browser/build.json::modules` adds `"libs:ui-kit": {"dir": "../ab_cloud-libs-shared/libs/ui-kit"}` and `app.depends_on += libs:ui-kit`; `ac_cloud-browser/build.gradle` adds `org.jetbrains.kotlin.plugin.compose version 2.3.20 apply false`; `app/build.gradle` applies it, `buildFeatures.compose = true`; `libs/browser/build.gradle` adds `implementation project(':libs:ui-kit')` (ui-kit exposes Compose with `api`). `compose-migration.json`: no baseline change (no new View screen; `PopupMenu` deleted).
- `libs/browser/.../BrowserMenu.kt` (pure): parse menu JSON; `rows(state)` → each item with `enabled/why` from settings + add-ons + page state (`canGoBack` etc.).
- `libs/browser/.../BrowserMenuSheet.kt` (Compose, `kitComposeView` inside the Views fragment): renders sections; emits `MenuAction(id)`.
- `libs/browser/.../BrowserPageActions.kt`: `findInPage` (find bar composable + `findAllAsync/findNext/clearMatches`), `readerMode` (injected JS extracts `<article>`/largest text block → `loadDataWithBaseURL` with a declared CSS; toggle restores `reload()`), `print()`, `desktopMode(on)` (persisted via catalogue; UA from `user_agents`), `textZoom(pct)`, `translate(text)` via `TextToolsClient` (optional dependency: `libs:text-tools` added to browser modules; row disabled with `why` when `isServingAppInstalled()` is false), `requestDefaultBrowser()` (`RoleManager`).
- `BrowserHostFragment`: back/forward buttons in the bar; `OnBackPressedCallback` → `webView.goBack()` before leaving DETAIL; `⋮` opens the sheet; `showBrowserMenu/showEnginePicker` deleted (engine picker becomes a Settings row).
- Routes: `menu` (rendered rows with enabled/why), `menu/act?id=` (runs a non-destructive action: back, forward, reload, find?q=, reader, desktop, zoom), `page/find?q=` (count), `page/text?n=` (first n chars of extracted text), `page/reader` (extracted title + length).
Tests: `BrowserMenuTest` (every item has a section; `requires` resolution; enabled/why), `test-browser-menu-wired.sh` (every declared `action` id appears as a `when` branch in comment-stripped Kotlin; no `PopupMenu` left; every UA string lives in build.json, none in Kotlin; mutations).
Acceptance: `browser/menu` lists the declared sections in order with `enabled`; `menu/act?id=find&q=the` returns `{"matches":N}` and the bar shows N; `menu/act?id=desktop` flips `settings.desktop_mode` and `page/text` later shows a desktop layout marker; `menu/act?id=reader` returns the extracted title; a screenshot (`superapp_screencap`) shows the sectioned sheet.

### I4 — Library: bookmarks with folders, downloads manager, add-to-home — M

Files: `libs/browser/.../BrowserBookmarks.kt` (store `browser_bookmarks`, prefs, class `config`; schema `[{url,title,folder:"a/b",ts}]`; pure ops `move/rename/delete folder` in `BrowserBookmarkOps`), `BrowserDownloads.kt` (`DownloadListener` → `DownloadManager.Request` with cookies + UA + `download_dir`; index store `browser_downloads`, prefs, class `device`; `BroadcastReceiver` for `ACTION_DOWNLOAD_COMPLETE` + notification tap opens the file), `BrowserLibraryScreens.kt` (Compose `KitComposeFragment`s: Bookmarks, Downloads; History moves here too so HISTORY mode's View code is deleted), `BrowserShortcuts.kt` (pinned shortcut). `fleet-config.json`: two new stores. Routes: `bookmarks`, `bookmarks/add?url=&title=&folder=`, `bookmarks/remove?url=`, `bookmarks/folders`, `downloads`, `downloads/enqueue?url=` (test hook), `downloads/clear`.
Tests: `BrowserBookmarksTest` (folder ops, idempotent add), `BrowserDownloadsIndexTest` (index round trip), tester `test-browser-library-wired.sh`.
Acceptance: `bookmarks/add?url=https://qwant.com&title=Q&folder=search` then `bookmarks/folders` → `["search"]`; `downloads/enqueue?url=<small public file>` then `downloads` shows `status: successful` and a file under Downloads; star icon state matches `bookmarks`.

### I5 — Privacy: private session, per-site permissions, shields, clear data — M

Files: `libs/browser/.../BrowserPrivacy.kt` (`BrowserSitePermissions` store `browser_site_permissions`, prefs, class `config`, key `host|perm` → `allow|deny|ask`; `WebChromeClient.onPermissionRequest` + geolocation prompt honouring it, with a Compose ask-dialog; shields = per-host `javascript`/`load_images` applied in `shouldOverrideUrlLoading`/`onPageStarted`), `BrowserClearData.kt` (checkbox dialog; each box maps to one call), `BrowserTab.private: Boolean` (+ `BrowserTabPrefs` schema field `private`, default false; private tabs never recorded in history/previews; closing the last private tab runs `CookieManager.removeSessionCookies` + `WebStorage.deleteAllData` **only when `private_by_default` is off and no normal tab shares state** — document the limit: cookies are process-wide on Android WebView, so "private" is best-effort and the UI says so). Routes: `sites`, `sites/set?host=&perm=&value=`, `privacy/clear?history=1&cookies=1&cache=1&storage=1&confirm=1`.
Tests: `BrowserSitePermissionsTest` (resolution incl. wildcard host suffix), `BrowserPrivateTabTest` (private tab excluded from history/suggestions), tester `test-browser-privacy-wired.sh` (each clear-data box maps to its API call; private flag guards `history.record`).
Acceptance: `sites/set?host=example.org&perm=geolocation&value=deny` → page's geolocation request is denied without a prompt; `privacy/clear?cookies=1&confirm=1` → `CookieManager.getCookie(url)` null afterwards (route answers `cookies_after: null`); a private tab visit is absent from `history`.

### I6 — Profile (autofill data) via FleetConfig + file import + in-page autofill — L

Files:
- `fleet-config.json`: store `browser_autofill` (kind `encrypted`, class `secret`, used_by `lib-browser`, doc: "identity + addresses + card metadata for autofill; never card numbers or CVV"). `ac_cloud-browser/app/build.gradle` must therefore link `androidx.security:security-crypto` (FleetConfig's cipher is `compileOnly` in core; `#783 4/n` explains).
- `libs/browser/.../BrowserProfile.kt` (pure model + JSON): `Identity {first,middle,last,full,email,phone,company,website,dob?}`, `Address {label,street,number,apartment,city,state,zip,country}`, `CardMeta {label,holder,last4,exp_month,exp_year,type}`; `fromVaultBundle(about)` maps `about.profile` + `about.addresses[]`; `fromChromeCsv`, `fromFirefoxJson`, `fromBitwardenJson(identities only)` importers (Bitwarden export format `items[].identity` = the one `cloud-vault/D_A0-Bitwarden/myvault.py export_bitwarden` writes). Card importers keep metadata only and **drop `card_number`/`cvv` at parse time**.
- `libs/browser/.../BrowserProfileStore.kt`: EncryptedSharedPreferences `browser_autofill`, keys `identity`, `addresses`, `cards_meta` (JSON strings) — flat keys so `FleetConfig.exportApp/importApp` move them with no per-app code.
- `libs/browser/.../BrowserAutofill.kt`: (a) Framework participation: `webView.importantForAutofill = IMPORTANT_FOR_AUTOFILL_YES` when `autofill_enabled`; (b) in-page fill of the browser's own profile: injected JS scans `input[autocomplete]`/`name`/`id`/`label` heuristics → candidate fields; a Compose chooser (identity/address) → `fill` sets values + dispatches `input` events. Never fills `type=password` or card number fields (those are Cloud Vault's).
- SuperApp side (one declared block, no new channel): `aa_cloud-superapp/build.json` cockpit runtime block for `browser`: `source: about.profile + about.addresses`, `target: fleetconfig browser_autofill` with the key map; `AccountRuntime` already applies `FleetConfig.import` for declared targets (#783) — read `AccountRuntime.kt` §"server → runtime" and add the mapping kind if the block needs a transform (bundle → browser JSON).
- Routes: `profile` (presence + masked summary: counts, last4s, initials — never full values; the mask rules of `ui.profile.infos.mask` apply), `profile/import` (POST body = Chrome CSV / Firefox JSON / Bitwarden JSON / native JSON; `?format=`), `profile/clear?confirm=1`, `profile/fill?dry=1` (fields detected on the current page and which profile key each would get, no write).
- Settings screen: Profile page (Compose) with Import file (`ACTION_OPEN_DOCUMENT`), the masked view, Clear.
Tests: `BrowserProfileTest` (vault bundle mapping on the `aa_cloud-superapp/app/src/test/resources/account/S.json` shape; Chrome CSV; Bitwarden JSON; card number dropped), `BrowserAutofillMatchTest` (field heuristics on fixture HTML field lists), tester `test-browser-profile-never-leaks.sh` (no route or log line can print `identity`/`addresses` values: comment-stripped scan that `profile` route uses only `BrowserProfile.masked()`; `card_number`/`cvv` never stored; the store is `encrypted` in the manifest; mutations).
Acceptance: SuperApp Account ▸ Runtime → browser row shows `browser_autofill` present after "server → runtime"; `browser/profile` → `{"identity":{"present":true,"initials":"DCM"},"addresses":3,"cards_meta":1}`; open a form page (e.g. `https://httpbin.org/forms/post`) → `profile/fill?dry=1` lists detected fields; menu "Fill from profile" fills name/email/phone/address visibly; `profile/import` with a 2-row Chrome CSV body → `addresses: 5`; Drift masks every value.

### I7 — Add-ons framework + Web Scraper — L

Files:
- `ac_cloud-browser/build.json::ui.browser.addons[]`: `{id, label, doc, default_enabled, permissions: ["page_read","page_write","network","downloads","vault_autofill","ai_model"], requires_fleet: ["search"|"vault"|null], engine: "device"|"remote"}`; `app/build.gradle` bakes `UI_BROWSER_ADDONS_B64`. Enabled set persists in catalogue key `addons_enabled`.
- `libs/browser/.../BrowserAddons.kt` (pure registry + `enabled(id)`), `BrowserAddonsScreen.kt` (Compose: toggle per add-on, permissions listed in words, `requires_fleet` row shows installed/not via `FleetPeers.list`), menu section "Add-ons" built from enabled rows.
- Scraper: `libs/browser/.../scraper/ScrapeEngine.kt` (pure: `Plan {selectors: [{name, css, attr?}], pagination?: {next_css, max_pages}, follow?: {link_css, max_links}}`, result table merge, CSV/JSON serialisers — JVM-tested), `ScrapeRunner.kt` (device: runs one page by injecting a declared JS extractor over `evaluateJavascript`, paginates by clicking/loading `next_css` with a page cap; `follow` runs in a hidden second WebView, capped), `ScrapeRemote.kt` (`platform: crawl` to scrappers-api for site-wide crawls: body `{url, selector, max_pages}`, polling the run id; endpoint + auth mode declared in `build.json::ui.browser.addons[scraper].remote` after #802 reads `cloud-infra`'s scrappers-api declaration; token, if any, from the fleet Account via `revealAiKey`-style method or `FleetConfig` secret — never baked), `ScraperSheet.kt` (Compose: pick elements by long-press → CSS path generator JS, name columns, preview rows, run, export to Downloads via `MediaStore.Downloads` + share). Exports indexed in `browser_downloads`.
- Routes: `addons`, `addons/set?id=&on=`, `scraper/run?css=&attr=&pages=` (current page, synchronous up to `pages`), `scraper/last` (last result as JSON), `scraper/remote?url=&css=&max_pages=` (starts a crawl; returns run id), `scraper/remote/status?id=`.
Tests: `ScrapeEngineTest` (merge, dedupe, CSV quoting, pagination cap), `BrowserAddonsTest` (defaults, requires_fleet), tester `test-browser-addons-declared.sh` (every add-on id in build.json has a registration branch and a menu row; every permission string is one of the declared vocabulary; mutations).
Acceptance: `addons` lists 4 add-ons with enabled flags; on `https://news.ycombinator.com`, `scraper/run?css=.titleline>a&attr=href&pages=2` → ≥ 50 rows; `scraper/last` matches; a CSV lands in Downloads; `scraper/remote?url=...` returns a run id and `status` reaches `done` (or the route answers `remote: unconfigured` with the reason, never a silent empty list).

### I8 — Search add-on: cloud-search's Search vertical inside the browser (shared lib, no copy) — M (+ cloud-search refactor)

Files:
- New `ab_cloud-libs-shared/libs/search-page/` (class `gui`, Compose): move `ac_cloud-search/app/.../ui/AssistantPages.kt` and the Search vertical's web page composables here, with their `SearchState` slice behind an interface (`SearchPageHost { cfg: SearchConfig; prefs; sessions; token(provider) }`). `ac_cloud-search` links it by reference and deletes the moved files (its `SearchShellTest` keeps passing through the lib). Register the lib: `lib-classes.json` (`gui`), `compose-migration.json` (wave 0, `ui: compose`), `debug-ports.json` + `constellation-fleet.json` lib row + lib-apks roster — follow what `#773 2b/n` (`e413962f7`) did for `libs:ui-kit`.
- `ac_cloud-browser/build.json::modules`: `"search-core": {"dir": "../ac_cloud-search/core"}`, `"libs:search-page": {...}`, `"libs:text-tools": {...}`; `::ui.browser.addons[search].config` points at `../ac_cloud-search/build.json::search` so the engine list and `ai` block are read from cloud-search's declaration at build time (one declaration; `app/build.gradle` reads the sibling file like it reads `ab_cloud-libs-shared/build.json` for the auth mission).
- `ac_cloud-browser/app/.../addons/SearchAddonFragment.kt` (`KitComposeFragment` hosting the lib's page; "simple search" opens the engine URL in a tab instead of firing an intent — `SearchPageHost.openUrl` is the browser's `navigateTo`), reachable from the grid header and the menu.
- Routes: `search/engines`, `search/open?q=&engine=` (opens a tab), `search/chat/sessions`, `search/chat/send?session=&text=&model=&web=` (ChatFlow; the token is read per call and never answered).
Tests: lib `SearchPageTest` (Robolectric composes web + chat pages against the fixture config, moved from `SearchShellTest`), browser tester `test-browser-search-reuse.sh` (no file under `ac_cloud-browser` or `libs/browser` contains `openrouter.ai` or an engine URL: all come from cloud-search's build.json; the chat token is never stored: no `putString(` with `token`; mutations).
Acceptance: `search/engines` equals cloud-search's `/api/search/verticals` engine list; `search/open?q=berlin&engine=duckduckgo` opens a tab whose URL is the DDG template; `search/chat/send?text=hi` returns an assistant message or `error: "no openrouter token in the fleet Account"` (same wording as cloud-search), never a token.

### I9 — AI Chat agent with page tools — L

Files:
- `ac_cloud-browser/build.json::ui.browser.addons[ai].tools[]`: `{id, label, description, params: {...}, mutating: bool, confirm: bool, route: "model"|"on_device"}`; initial: `read_page` (text, headings, links), `summarize_page` (route default `model`; `on_device` option via `libs:ml-l-text-mlkit` engine when installed — row disabled with `why` otherwise), `find_in_page`, `scrape` (css, attr), `click` (css) [mutating, confirm], `fill_form` (fields map) [mutating, confirm; refuses password/card fields], `navigate` (url) [mutating, confirm], `open_tab`/`close_tab`/`pin_tab` [mutating, confirm], `list_tabs`, `bookmark_page` [mutating, confirm]. `models.default` inherits `search.ai.default_model`; `max_tool_calls_per_turn: 6`; `page_text_cap_chars`.
- `search-core` (`ac_cloud-search/core/.../Chat.kt`): `body(...)` grows an optional `tools: List<ToolSpec>` → OpenAI-compatible `tools`/`tool_choice`; `reply()` parses `choices[0].message.tool_calls` into `Reply.toolCalls` (JVM-tested with a fixture `openrouter-tool-call.json`). This is the only engine change and it stays data-driven (names from the add-on JSON).
- `libs/browser/.../agent/PageTools.kt` (device: each tool over the WebView JS bridge; `click`/`fill_form` dispatch real DOM events; same-origin check against the active tab for `navigate`, else confirm names the new host), `AgentLoop.kt` (pure state machine: model turn → tool calls → confirmations → tool results → model turn; caps; JVM-tested with a fake model), `AgentConfirmSheet.kt` (Compose: "The assistant wants to click `button.buy` on shop.example — Allow / Deny"), `AgentChatPanel.kt` (Compose side panel over the page, reusing the lib's chat composables from I8).
- Routes: `ai/tools` (declared tools with route + confirm), `ai/ask?text=&session=` (runs a turn; mutating tools are answered `pending_confirmation` in the debug reply and the sheet shows on screen; `&allow=1` is **not** offered — confirmation is UI-only), `ai/sessions`.
Tests: `AgentLoopTest` (cap, confirm gating, denied tool → model told "denied", never executes), `PageToolsPolicyTest` (password/card refusal, same-origin), `ChatToolsTest` in search-core (body/reply shapes), tester `test-browser-agent-consent.sh` (every tool with `mutating: true` is `confirm: true` in build.json; every tool id has a branch; no tool reads `BrowserProfileStore` or `revealAiKey`; mutations).
Acceptance: `ai/tools` lists tools with `confirm` flags; `ai/ask?text=Summarize this page` on a Wikipedia article → an assistant message of ≤ N chars citing the title; `ai/ask?text=Click the first link` → reply `pending_confirmation` + the sheet on screen (screenshot); after Deny, `ai/ask` continues with `denied` in the transcript; `ai/ask?text=fill the password` → refusal text, no DOM write.

### I10 — Cloud Vault (Bitwarden) add-on — S/M

Files: `libs/browser/.../addons/VaultAutofill.kt`: `status()` = `AutofillManager.isEnabled/hasEnabledAutofillServices` + `Settings.Secure.autofill_service` equals Cloud Vault's component (package from `constellation-fleet.json` row `vault`, baked by `app/build.gradle` like `open_with` in cloud-search); `requestFill()` = `AutofillManager.requestAutofill(webView)` after focusing the first login field via JS; `openCloudVault()` deep link (`Intent` to the fork's `MainActivity` with the current host as a search extra if the fork exposes one — read `ac_cloud-vault/app/src/main/AndroidManifest.xml` first); `setAsAutofillService()` = `Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE`. Menu rows in the add-on section. Routes: `vault/status`, `vault/request_fill` (returns whether the framework accepted the request). Settings catalogue key `autofill_enabled` gates `importantForAutofill`.
Tests: tester `test-browser-vault-addon.sh` (no Bitwarden SDK or `com.bitwarden` dependency in any browser gradle file; the vault package comes from the fleet roster, no literal; mutations). Device-only behaviour is acceptance-tested.
Acceptance: `vault/status` → `{installed:true, autofill_service:"com.diegonmarcos.cloudvault/...", enabled:true}` (or the honest false + the Settings deep link); on a login page the Cloud Vault fill chip appears over the field (screenshot) and `vault/request_fill` returns `requested: true`; saving a new login triggers Cloud Vault's own save prompt.

## 3. Cross-cutting checklist for #802 (per increment)

1. `fleet-config.json` entry for every new store (class, doc, used_by, key overrides) — guard is red otherwise.
2. New lib → `lib-classes.json`, `compose-migration.json` unit, `debug-ports.json`, `constellation-fleet.json` lib row, lib-apks roster (copy `e413962f7`).
3. New routes → `Op(...)` docs; never `state`; nothing reads a token or a profile value into a reply.
4. Each increment: JVM test + shell tester + the device acceptance above; report run ids.
5. `./build.sh workflow` regenerates `1_cicd/dist` and `test-coverage.json`; commit the regenerated files in the same commit.
6. Commit messages explain the user-visible failure fixed, per repo rules.

## 4. Risks and limits

- **WebView autofill participation**: Chromium WebView exposes form fields to the Autofill Framework (API 26+) but hints depend on the page's `autocomplete` attributes; Bitwarden's heuristics cover the rest. Must be verified on the owner's phone before I10 is called done; fallback is the in-page fill path of I6 for identity/address (never passwords).
- **Private tabs are best-effort**: cookies/storage are per-process in Android WebView; true isolation needs a second process (`android:process` for a private activity) — deferred, stated in UI.
- **Page-acting agent**: prompt injection from page text can request tools; mitigations are consent on every mutating tool, same-origin, caps, no access to profile/vault values, tool results truncated. Keep the panel visible while tools run. Never allow `&allow=` over the debug API.
- **Reader mode and scraper rely on injected JS**; CSP does not block `evaluateJavascript`, but heavy SPAs may render late — run extraction after `onPageFinished` + a settle delay (the preview capture already waits 600 ms).
- **Compose in a Views fragment** (`kitComposeView`) is the #773 pattern; `libs:browser` baselines must not go up (no new `Fragment` with `onCreateView` Views; use `KitComposeFragment`).
- **scrappers-api contract** unknown from this container; remote crawl ships behind `remote.enabled` and reports `unconfigured` honestly until declared.
- **Card metadata source**: the vault bundle has no cards section. Either add `cloud-vault/C_A1-configs/finance/sources.json` (metadata-only resolver over `a4-banks/cards.json`, dropping `card_number`/`cvv` at emit) — cross-repo, owner decision — or import cards metadata from a Bitwarden export only. The plan ships the importer first.
- **devtools line budget**: I1 must stay under 1670 lines; if a later route helper is needed, raise the budget in `lib-classes.json` with the reason, never bypass.

## 5. Sizes and order of delivery

| # | Increment | Size | Depends on |
|---|---|---|---|
| I1 | Debug API body | S | — |
| I2 | Settings catalogue + browser routes + Runtime/Drift | M | — |
| I3 | Declared menu + page actions (Compose sheet) | L | I2 |
| I4 | Bookmarks, downloads, add-to-home | M | I3 |
| I5 | Privacy: private tabs, site permissions, shields, clear data | M | I3 |
| I6 | Profile via FleetConfig + import + in-page fill | L | I1, I2 |
| I7 | Add-ons framework + Scraper | L | I3 |
| I8 | Search add-on (lift cloud-search page into libs:search-page) | M | I7 |
| I9 | AI agent with page tools | L | I8 |
| I10 | Cloud Vault autofill add-on | S/M | I7 |

Deferred (not in #802): tab sync across devices (content class; would need a server), extensions beyond the four add-ons, a WebExtension-compatible API, process-isolated private mode, on-device summarisation model beyond the existing ML Kit engine, card-number autofill (Cloud Vault's job), the cloud-vault `finance` section (owner decision).
