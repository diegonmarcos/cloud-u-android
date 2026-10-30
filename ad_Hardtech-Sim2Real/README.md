# Hardtech-Sim2Real

An app of the Lnktree family (#1335), behind the SuperApp's Cloud > Lnktree row. Package `com.diegonmarcos.hardtechsim2real`, folder `ad_Hardtech-Sim2Real` (the `ad_` prefix of the
Lnktree family, declared in `1_cicd/src/data/app-families.json`; the Cloud family is `ac_cloud-<x>`).

* The pages are `build.json::ui` (`bottom_nav`, `sections`, `stacks`) and are drawn by declared kinds (`Pages.kt`: `entry`,
  `section_title`, `holdings`, `about`). The shell is the fleet's: `libs:ui-nav-bars` (first row, bottom nav), `libs:bottomnav`
  (NavDecl), `libs:ui-kit` (tokens and kit), `libs:ui-icons` (the nav icons), `libs:theme-background`.
* An entry's target is read from a declared source (a linktree label, a registry code or a repo of `ui.app_urls`) by `app/build.gradle`
  at build time; no address is typed here. A `planned` entry is greyed with its note. No market data is shown.
* Tester: `test/test-lnktree-app.sh` (the shared template `1_cicd/src/templates/test-lnktree-app.sh`), JVM: `PagesTest`, `HoldingsTest`.
* Ship: `1_cicd/src/cicd/ship-hardtech-sim2real.yml` (APK to GHCR and the rolling release, installable from the SuperApp's Constellation).
