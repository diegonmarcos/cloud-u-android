# cloud-media-center

The fleet's media hub, as an APK — Rust and static web only. A Tauri 2 Android
app (`com.diegonmarcos.cloudmediacenter`) whose one webview shows seven
mobile-first pages built from the media data of
[diegonmarcos.github.io](https://github.com/diegonmarcos/diegonmarcos.github.io),
pinned at one commit (`build.json::data.source`).

| Page | Data | A tap opens |
|---|---|---|
| `web/music/` | `b-Media/mySocials/src/data/tidal.json` — every section, folder, playlist and track | a TIDAL search for "artist title" |
| `web/videos/` | `youtube.json` — ids parsed from the thumbnail URLs | the video on YouTube |
| `web/gallery/` | `pinterest.json` — board URLs by `main.ts`'s slug rule | the board on Pinterest |
| `web/books/` | `shelf.json` — covers from Open Library by ISBN | the book on Open Library |
| `web/feed/` | `b-Data/myfeed/src/data/sampleFeed.ts`, newest first | the article (markdown posts read in place) |
| `web/movies/` | `c-Circus/movies/src/data` — the site's shelves and posters | a detail sheet, then IMDb or a trailer search |
| `web/documentaries/` | the YouTube "Docs" playlist plus every Documentary-genre title | YouTube / the detail sheet |

Every link out goes through `tauri-plugin-opener` (`opener:default`, the one
capability), so TIDAL, YouTube and Pinterest open in their own apps.

| Piece | Where |
|---|---|
| Pages: pure render half (tested under node) + browser boot | `web/assets/pages.js` |
| The one stylesheet | `web/assets/app.css` |
| Page data, generated, never fetched at runtime | `web/data/*.json` ← `data/regen.py` |
| The app: runtime + opener plugin | `src-tauri/src/lib.rs` |
| Tauri's Android project (template, committed) | `src-tauri/gen/android` |
| Shared modules (fleet mesh membership), read by `settings.gradle` | `build.json::modules` |
| Toolchain pins: tauri CLI + sha256, NDK, AGP, gradle | `build.json::toolchain` |

Moving the data pin: change `build.json::data.source.commit`, run
`python3 data/regen.py`, commit `web/data/`. `python3 data/regen.py --check`
fails when the committed data no longer matches the pin.

**The name.** Until 2026-10 `cloud-media-center` was the gallery app; that app
is now `cloud-gallery` (`ac_cloud-gallery/`) and keeps its applicationId
`com.diegonmarcos.mediacenter`. This app has its own identity and reuses only
the freed names: `cloud-media-center.apk`, `cloud-media-center-x86_64.apk`,
the `cloud-media-center` GHCR image and `extapp:cloud-media-center`, which
Cloud Me's Media tiles and the SuperApp's Data Apps tile point at.

Build: `./build.sh build` (Nix devShell) or the `ship-cloud-media-center.yml`
workflow, which builds arm64 and x86_64 on ubuntu-latest and re-signs both
with the one shared constellation key. The only Kotlin is Tauri's template
glue, pinned by sha256 in `build.json::tauri.glue`;
`test/test-media-center-no-kotlin.sh` fails CI and `build.sh` fails the build
on anything else.

Testers: `test/test-media-center-pages.sh` (every page renders its data, the
data's pins, the link rules, the Tauri wiring), `test/test-media-center-no-kotlin.sh`,
`test/test-fleet-setup.sh`.
