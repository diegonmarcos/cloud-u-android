# Cloud KG Store

The fleet's knowledge-graph store and browser (#1207). Three sections on the bottom nav, Cloud, Phone and Web; the assistant is behind
the AI icon at the top right. Tile `KG Store` in Cloud > Apps > AGI (`SI AI | Search Code | KG Store Tools | MyTerminal Terminal`).

* Package `com.diegonmarcos.cloudkgstore`, image `cloud-kg-store`, asset `Cloud-KG-Store.apk`, ship workflow `ship-cloud-kg-store.yml`.
* The shell is the fleet's: `libs:ui-nav-bars` (first row with Profile opening Cloud Account, the second row, the bottom nav),
  `libs:bottomnav` (NavDecl, drawer, scroll collapse), `libs:theme-background`, icons from `libs:ui-icons`. Sections and pages are
  `build.json::ui`; what each page reads is `app/src/main/assets/kg/kg.json::pages` (same ids, held in step by
  `test/test-kg-store-app.sh` and `KgConfigTest`).
* The assistant is `libs:ai` (the page, sessions drawer, Markdown, streaming) over the fleet agent gateway (mesh only); the system
  prompt, suggestions and MCPs are `kg.json::ask`. This app holds no token.

## Cloud (two rows: groups on the first, pages on the second)

* **Application**: *Binary* (the fleet apps and their binaries from the generated manifest, plus a live GET of `c3-infra-api /apk`),
  *Web/Debug* (each app's declared debug port and its gateway route; an app with no port says so), *MCP* (the search tools of the MCP
  services apps declare: none do yet, the Apps MCP-API work #1211 is what will declare them, and the page says that), *API Data* and
  *API Debug* (GET and POST consoles on `cloud-me-api` / `c3-public-api` and on `c3-infra-api`).
* **Repo**: *Code* (code search of the owner's repositories, opened in the browser), *CGC* (the two code indexes with their service
  health and repositories), *MCP* (tools/list of `cgc-pub-mcp` and `cgc-pvt-mcp`), *API Semantic* (CGC semantic search, reranked by
  `embeddings-api` when it answers) and *API GraphRAG* (graph nodes and their relationships).
* Every address comes from `ac_cloud-account/build.json::fleet_endpoints` (baked by `app/build.gradle`): mesh first, then the public
  route with the fleet bearer that Cloud Account holds (read on use, never kept, sent to a public origin only). A service that is not
  declared, does not answer or refuses is reported with its own reasons; there is no sample data anywhere.

## Phone

* *Apps*: the installed apps index from PackageManager (package, version, installer; tap for permissions with their grant state and the
  activities, services, receivers and providers). Needs `QUERY_ALL_PACKAGES`, like Cloud Drive's Manage > Apps.

## Web

* *News, Media, Shopping, Real Estate, Jobs, Business* read Cloud Search's declaration: `ac_cloud-search/build.json::search` is baked by
  reference and `ac_cloud-search/core` (search-core: the sources, the parsers, the cache-backed engine, the CGC and MCP protocol and the
  fleet endpoint routing) is linked by reference, the way Cloud Browser links it. Nothing is copied; a source added to Cloud Search shows
  up here. Link-only sources are listed with their own search link, disabled ones with the reason.

## Tests

* `test/test-kg-store-app.sh` (assembly rules with a planted mutation each) and `test/test-fleet-setup.sh`.
* JVM suite in `app/src/test` (`:app:testDebugUnitTest`): declaration against `build.json::ui`, Cloud Search's verticals and the fleet
  declaration; the fleet calls on a fake network; Cloud Search's sources on its saved real responses; the installed apps index.
