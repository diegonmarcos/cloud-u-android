# Cloud Search — the plan per section (#797)

The design is the owner's mockup (`_dispatch/assets/cloud-search-mockup.html`): a glass theme with
light/dark tokens, a top bar (menu · dynamic island · profile menu), and the app's OWN bottom nav
(House · Jobs · Search · Groceries · Things, Phosphor icons). Every list below is data in
`build.json::search`; this file says what each section reads and why.

Rules that hold for every section:

- **One network door** (`core/Engine.kt` `UrlHttp`) through Android's resolver; one token door
  (`data/Account.kt`, the fleet Account's OpenRouter key, never stored or logged).
- **Caching**: every answer is stored raw under `filesDir` keyed by what was asked; an answer
  younger than `cache_ttl_minutes` (60) is reused, an older one is refetched.
- **Offline**: a failed refetch shows the last stored answer marked *stale* with its date; a
  source never fetched says *failed* and why. Nothing is ever filled in.
- **Legality**: only public APIs whose terms allow it are read. A site whose terms forbid
  automated access, or that has no API, is a `link` — its own search opens in cloud-browser.
  A source that cannot be done reliably is `enabled: false` with its reason, shown on screen.
- **The fleet scraping backend** (scrappers-api, successor of the archived crawlee-cloud) is not a
  source: it sits behind two-factor auth, `/scrape` returns a summary rather than the data, and
  every portal it could be pointed at (ImmoScout24, Immowelt, Kleinanzeigen, StepStone, Indeed,
  LinkedIn, REWE, Lidl, Kaufland, eBay, Vinted) forbids scraping. Its generic crawler now refuses
  any URL the site's robots.txt disallows (cloud-u-containers, #797), so it stays usable for
  sites that do permit it.

## House

| Part | Source | Engine |
|---|---|---|
| Listing | none legal — ImmoScout24, Immowelt, Kleinanzeigen Immobilien, WG-Gesucht are links (a DuckDuckGo `site:` search, because their own URLs need region ids); scrappers-api disabled | `SearchEngine.query` reports each as link/disabled |
| Analysis | **Bundesbank** MFI rate on new housing loans (SDMX-JSON), **Eurostat** house price index `prc_hpi_q` and HICP actual rentals `prc_hicp_minr` CP041 (JSON-stat) — all Germany-wide | `SearchEngine.market` → `Market.stat`: latest value, change vs the same period a year before (pp for a rate, % for an index), chart = last 6 HPI quarters |
| Feed | tagesschau Wirtschaft + Verbraucher RSS, filtered by declared keywords | `SearchEngine.feed` |
| Calculators | Rent vs Buy (monthly simulation), Max rent ratio | `Calculators.run`, golden-tested |

No source publishes city-level rents or prices that this app may read, so the market card says
"Germany" and never shows a Berlin figure.

## Jobs

| Part | Source | Engine |
|---|---|---|
| Listing | **Bundesagentur für Arbeit** Jobsuche v6 (official, published client key), **Arbeitnow** (free public API); StepStone, Indeed, LinkedIn are links | `SearchEngine.query`, parsers `ba`, `arbeitnow` |
| Analysis | BA open positions + occupational-field facet + advertised pay; Arbeitnow remote share | `Analysis.jobs` — medians with their sample size; time-to-hire has no public source and says so |
| Feed | tagesschau Wirtschaft RSS, keyword-filtered | `SearchEngine.feed` |
| Calculators | German payslip 2026 | the BMF's own PAP 2026, generated into `tax/Lohnsteuer2026.kt` (16 BMF golden rows to the cent) |

## Search

One page, as in the mockup: four engine boxes (Google, DuckDuckGo, Brave, Qwant — each opens the
engine's own results in cloud-browser; no engine's results are scraped) and an AI chat over
OpenRouter. Sessions are stored on the phone (`chat-sessions.json`), grouped Today / Previous 7
Days / Older. The model list is OpenRouter's live catalogue (cached `catalog_ttl_hours`), flagging
models that search the web natively; the Web switch uses native search or the `web` plugin. The
token is read from the fleet Account on every send; without one the chat says why and posts nothing.

## Groceries

**Open Prices** (prices from receipts and price tags within the city's radius; *verified* = backed by
a proof) and **Open Food Facts** search (products, Nutri-Score), both ODbL. REWE, Lidl and Kaufland
have no public API and forbid automated access: links.

## Things

One page, **Compare** (#903): the stores around the person that sell the item's kind, and what each
asks. Nothing is estimated; a store with no price says why.

| Part | Source | Engine |
|---|---|---|
| Area | the coarse location fix (`ACCESS_COARSE_LOCATION`, asked once, never stored; the centre leaves the phone rounded to ~1 km), else the city typed in settings (Nominatim, keyless, cached), else the selected city; radius 20 km by default, set in settings | `data/ThingsArea.kt`, `data/Locator.kt` |
| Stores | **OpenStreetMap** `shop=*` within the radius through the Overpass API (keyless, ODbL; two public instances tried in order), the item matched to shop types by `things.categories[].words`; cached per area, category and radius for a week | `ThingsEngine.compare`, `Things.overpassQuery/parseStores` |
| Shelf price | **Open Prices** (ODbL): a price seen at that very OSM place (receipt or price tag), per unit, dated; mostly groceries and drugstore goods, so most other items have none | `Things.parseShelf` |
| Online price | the chain's **own site**, read server-side by the fleet scraper (`scrappers-api` `GET /prices`, robots.txt-gated, schema.org JSON-LD offers, cached 30 min) behind the fleet bearer; OBI and Euronics today, the rest listed disabled with the verified reason (`user-data_scrappers-api/src/code/prices.json`). It is the chain's online price, not the branch shelf price, and the row says "online" | `Things.parseBackend`, `Things.compare` |
| Table | store, km, price (source and date), cheapest first, the unpriced below with the reason; a row opens the price page or the store's website | `ui/ThingsPage.kt` |

No marketplace offers a public search API this app may use: Kleinanzeigen, eBay and Vinted stay links
(open on their own search below the table) until the Account carries an eBay key.

## Screen-locked checks

`/api/search/verticals`, `/query?v=&q=&city=`, `/calc?name=&…`, `/analysis?v=`, `/feed?v=` and `/things?q=&lat=&lon=|city=&radius=` run the
same engine and cache as the screens (libs:devtools debug API, group `search`).

## Navigation (#913)

The island is Web Search · Cloud Search · Chat · Agents · Reports (`build.json::ui`):

| Section | Pages (PageTabs) | What it is |
|---|---|---|
| Web Search | House · Jobs · Groceries · Things | the search verticals above, each with its own subpage strip; Things is the #903 price comparison |
| Cloud Search | Apps · Messages · Code | Apps searches the fleet manifest and launches the app; Messages asks Cloud Mail through the read-only agent door; Code has no fleet engine yet, so it searches the owner's repositories on GitHub in Cloud Browser and opens Cloud Code |
| Chat | Assistant | today's Search page (engine boxes + AI chat), moved as is |
| Agents | Agents · Runs · Templates · Settings | draft-only agents |
| Reports | list, then detail | what agents published, newest first |

## Agents (#913): draft only

An agent READS and DRAFTS; a person copies the draft and sends it. Nothing is posted, submitted or sent for
the owner, and there is no button that does. Everything is declared in `build.json::search.agents`.

**House search.** (1) Cloud Mail's agent door lists the mails from `wg-gesucht.de` of the look-back window;
(2) each mail's body is read and the listing links extracted (host-checked, one per listing id, earlier runs'
listings skipped); (3) Cloud Browser's agent door fetches each listing page's text (a read-only GET); (4) the
owner's template is filled with their details and, inside the budget, one short model paragraph; (5) the drafts
land in a report. Each draft has Copy message, Open listing in Cloud Browser, and the owner's own "I sent it" /
Dismiss marks.

* **Doors** (`docs/agents-engine-audit.md`): mail `content://<mail pkg>.agentmail/{messages,body}` and browser
  `AGENT_OPEN` activity + `fetch_text` provider, all behind CONSTELLATION_DATA (signature). Read-only.
* **Key**: OpenRouter's, read from the fleet Account per model call (`Account.token`), only into the
  Authorization header. The model gets no tools; its text is cleaned (no links, addresses) before it is inserted.
* **Budget**: US-dollar caps per run and per day (Settings). Each call is priced at its worst case first and not
  made if it could pass a cap; the real cost (the provider's, else tokens x price) is recorded.
* **Audit**: every run logs what it read (mail ids and subjects, URLs) and drafted; never a body, page text or
  message. Runs page shows it.
* **Reports** (`ReportStore`, filesDir/agents/reports.json): the local API agents publish through
  (`ReportSink.publish`); the Reports page lists newest first and opens a detail with the drafts.
* **Owner setup**: the OpenRouter key in the fleet Account (SuperApp > Profile), Cloud Mail with the wg-gesucht.de
  alert mails delivered, Cloud Browser installed (both updated to the builds with the agent doors), your details
  and template on Agents > Templates, a budget on Agents > Settings.
