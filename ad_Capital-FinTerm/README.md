# Capital-FinTerm

An app of the Lnktree family (#1335), behind the SuperApp's Cloud > Lnktree row. Package `com.diegonmarcos.capitalfinterm`, folder `ad_Capital-FinTerm` (the `ad_` prefix of the
Lnktree family, declared in `1_cicd/src/data/app-families.json`; the Cloud family is `ac_cloud-<x>`).

* The pages are `build.json::ui` (`bottom_nav`, `sections`, `stacks`) and are drawn by declared kinds (`Pages.kt`: `entry`,
  `section_title`, `holdings`, `about`). The shell is the fleet's: `libs:ui-nav-bars` (first row, bottom nav), `libs:bottomnav`
  (NavDecl), `libs:ui-kit` (tokens and kit), `libs:ui-icons` (the nav icons), `libs:theme-background`.
* An entry's target is read from a declared source (a linktree label, a registry code or a repo of `ui.app_urls`) by `app/build.gradle`
  at build time; no address is typed here. Every entry is a dashboard (`dashboard` kind); no figure is declared, a dashboard shows what its source returns.
* Tester: `test/test-lnktree-app.sh` (the shared template `1_cicd/src/templates/test-lnktree-app.sh`), JVM: `PagesTest`, `HoldingsTest`.
* Sources: `data/sources.json` (the catalog, Cloud-Fincept / fin_api first, upstream FinceptTerminal AGPL-3.0 attributed), `data/dashboards.json` (one dashboard per entry), `data/control.json` (the mesh-only container control); Config > Sources keeps your own API keys encrypted (SafeEncryptedPrefs). Tester: `test/test-finterm-sources.sh`.
* Ship: `1_cicd/src/cicd/ship-capital-finterm.yml` (APK to GHCR and the rolling release, installable from the SuperApp's Constellation).
