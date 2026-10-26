# Cloud Search (called Cloud Research from #1162 to #1197) — what it reads, what it reuses, and why

The app is branded Cloud Search (launcher, store, tile; tile caption Search). Its identifiers never changed, on purpose: directory `ac_cloud-search`, fleet id `search` / `cloud-search`, applicationId `com.diegonmarcos.cloudsearch`, GHCR image and APK asset `Cloud-Search*.apk`. Renaming any of them would install as a second app (a new applicationId cannot update the old one) and break the Store's update path.

## Data sources (build.json::search.sources is the one declaration)

| Source | In-app | Licence / terms |
|---|---|---|
| Bundesagentur für Arbeit, Jobsuche API v6 | listings + analysis | Public API of the Federal Employment Agency (jobsuche.api.bund.dev); the `X-API-Key` is the published client id, not a secret. |
| Arbeitnow job board API | listings + remote share | "Free public API for jobs, please do not abuse … linking back" — every card links to the posting. |
| Open Prices (Open Food Facts) | grocery prices near the city | ODbL. |
| Open Food Facts search | grocery products | ODbL. |
| Deutsche Bundesbank time-series API (MFI rate on new housing loans, DE) | House analysis | Free reuse with the source named. |
| Eurostat dissemination API (`prc_hpi_q` house prices, `prc_hicp_minr` CP041 rents, DE) | House analysis | Reuse authorised with the source named (Commission Decision 2011/833/EU). |
| tagesschau RSS (Wirtschaft, Verbraucher) | Feed headlines, linked | Public RSS; only headlines and the feed's own teaser are shown, each linking to the article. |
| OpenRouter chat completions + model catalogue | AI chat | The user's own token from the fleet Account. |
| ImmoScout24, Immowelt, Kleinanzeigen, WG-Gesucht, StepStone, Indeed, LinkedIn Jobs, REWE, Lidl, Kaufland, eBay, Vinted | **never fetched** | No public API, or terms that forbid automated access: the site's own search opens in cloud-browser. |
| Fleet scraper (scrappers-api, successor of the archived crawlee-cloud) | **disabled** | Behind two-factor auth; `/scrape` returns a summary, not the data; and the portals above forbid scraping anyway. |

The test fixtures in `core/src/test/resources/fixtures/` are trimmed responses saved from these
sources on 2026-10-02 (teaser texts cut to a few words); the three market series
(`bbk-mortgage-rate.json`, `eurostat-hpi.json`, `eurostat-rent.json`) are whole answers saved on 2026-10-03.

## The payslip

`core/pap/Lohnsteuer2026.xml` is the Bundesministerium der Finanzen's Programmablaufplan for the
2026 wage tax (bmf-steuerrechner.de, Stand 2025-10-23), published for software developers.
`core/tools/pap2kt.py` translates it statement for statement into
`core/src/main/kotlin/…/tax/Lohnsteuer2026.kt`; nothing about the tax is decided by this app.
The golden table `bmf-lohnsteuer-2026.tsv` was produced by the BMF's external test interface,
whose stated purpose is checking one's own implementation.

Social insurance 2026: SV-Rechengrößenverordnung 2026 (as summarised by lohn-info.de), declared in
build.json::search.social_2026 and held equal to the limits the PAP itself carries.

## Reuse, and what was not reused

- libs:core / libs:devtools (mesh member, debug API) and libs:text-tools (the fleet Account's
  OpenRouter token) — shared by reference. #797: libs:bottomnav and libs:ui-kit are NOT linked any
  more; the app draws its own chrome from the owner's mockup.
- Icons: Phosphor Icons (@phosphor-icons/core 2.1.1, MIT, © 2023 Phosphor Icons), converted to
  vector drawables by `app/tools/phosphor2vd.py` from the list in `app/tools/phosphor.json`.
  The app icon (#803) is Phosphor `shooting-star` in the mockup's `.ai-nav-icon` gradient, generated
  by the same script from `phosphor.json::launcher`; cloud-superapp's Search tile carries a
  byte-identical copy of it.
- Cloud Calc's engine (Cloud-Lib-Calc, libqalculate behind a binder, #767) was considered for the
  calculators and not used: the payslip must reproduce the BMF's BigDecimal rounding statement by
  statement, which is the PAP's own code and not an expression; rent-vs-buy is a month-by-month
  simulation; neither gains anything from an expression engine in another process, and binding
  one would make three plain calculators depend on a second APK being installed.

## #1197 News, Media and the engine list (all keyless, all declared in build.json::search)

- **YouTube** channel feeds (`search.feeds` yt-*, Atom, `https://www.youtube.com/feeds/videos.xml?channel_id=...`): YouTube's own published feed per channel; read-only, no key.
- **Google News** topic feeds (`search.feeds` gn-*, RSS): Google's published per-topic feeds; each headline opens at its publisher.
- **Openverse** (images, CC-licensed; every result carries its licence), **Internet Archive** advancedsearch (moving images and its feature_films collection), **TVMaze** show search (CC BY-SA data), **Open Library** search (books): public APIs, no key, read through the same engine and cache as every other source.
- **Search engines** (`search.engines_major`): Google, Google AI Mode, Bing, DuckDuckGo, Brave, Qwant, Kagi, Perplexity, Ecosia, Startpage, Yahoo, SearXNG (searx.be), MetaGer. They are only OPENED in Cloud Browser with the query; nothing is scraped. The fleet declares no SearXNG or metasearch service of its own (ac_cloud-account fleet_endpoints), so none is called.
- **Price compare**: Shopping > Compare, Electronics and Clothing use the Things page (OpenStreetMap stores, Open Prices shelf prices, scrappers-api site prices); an item that names no category falls back to the page's own.
