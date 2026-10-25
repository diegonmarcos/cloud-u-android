# data/

## `ui/` — one file per page

`ui/<section>/<page>.json` is the content of that page: the `stack_<page id>`
array the renderer walks. `build.json::ui.sections` keeps only the navigation
shape — id, label, icon, bar/drawer placement, and the `pages` list. The files
themselves ship as **assets**, and `Sections.stack()` reads one when its page
opens.

They used to be folded into `BuildConfig` at build time. That hit javac's 64KB
limit on a String constant the first time a page carried a real profile's worth
of text: a BuildConfig field is the wrong home for content that grows with the
data, because the shape is bounded and the content is not.

Two rules the loader enforces so the split cannot rot:

* a page declared in `build.json` with no file here **fails the build**;
* `test/test-ui-pages.sh` fails on the reverse — a file no page declares — and
  on any `page:`/`extapp:` target that points at something that is not there.

Adding a page is still a data edit: one entry in that section's `pages`, one
file beside its siblings. The folder is the section, so Buro's tabs, the
Agenda's two and the Projects' three each live together instead of in one
1100-line blob where a page was a key prefix and nothing else.

A tab that declares `pages` of its own is a container: it holds no file, its
children live one folder deeper and the app draws a second, quieter strip
beneath the first. No section uses one today: Buro and LLC use `nav_mode`
`many_rows` with `groups` (`{id,label,icon,pages:[ids]}`, every page in exactly
one group), the same contract as Health.

Buro and LLC pages are stacks that carry `ledger` blocks: the owner's typed
records (collections of flat records, fields typed text/money/date/choice) in
the `buro_user` / `llc_user` stores, plus derived reports. Nothing is seeded;
an empty collection says what it is for.

A section with a `target` and no `pages` — Wallet, in the bottom bar — has no
folder here. It launches Cloud Wallet and hosts nothing.

## `files/` — the wallet's source of truth

`files/wallet/{pay,ids,vcards,events}/…` is where the wallet records actually
live: one JSON file per record, in folders, browsed as folders by Buro ›
Wallet. `app/build.gradle` ships the tree verbatim as assets, so the browser
walks it with `AssetManager.list()` and needs no manifest.

Cloud Wallet reads a single bundled `assets/wallet.json` because its decks want
the whole set in memory at once. That file is **this tree flattened** — not a
second copy to edit:

```
./data/regen-wallet-json.py            # tree → ac_cloud-wallet/…/wallet.json
./data/regen-wallet-json.py --check    # drift check, run by test/
```

Edit a record here, run the script, commit both. `test/test-ui-pages.sh` fails
if they disagree, which is what stops the two apps from telling different
stories about the same card.

## No `calendars.json` here any more

Cloud Me used to compile `libs:cal`, which bakes its subscription list from
`${rootDir}/data/calendars.json`, so this directory carried a symlink into
`ac_cloud-tasks/data/`. Agenda now binds the cal engine (Cloud-Lib-Cal.apk,
`build.json::engines.cal`) instead of compiling it, and the engine bakes the
one list in `ac_cloud-tasks/data/calendars.json` when lib-apks builds it, so
the symlink went with the module.

## `health/` — the declared content of Health

`health/health_*.json` ship as assets (`assets.srcDirs += ../data/health`) and are read by
`health/HealthContent.kt`: `health_home.json` (home cluster cards), `health_formulas.json`
(every coefficient: BMR, TDEE multipliers, sauna loss, gut-flow curve, circadian points),
`health_plan.json` (the timeline, objectives, biomarker targets, culinary methods, absorption
rules; the matrix, shopping list, overlays and dossier are DERIVED from it) and
`health_workout.json`. The user's own entries are not here: they live in the SharedPreferences
store `health_user`, declared in fleet-config.json as a migrating `config` store. Health Connect
readings are never stored; the trackers read them live and show an empty state when there are none.
