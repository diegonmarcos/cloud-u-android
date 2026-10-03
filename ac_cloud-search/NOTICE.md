# Cloud Search — what it reads, what it reuses, and why

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
