# cloud-account — information architecture and how data is shown

Status: DONE (2026-10-14, owner request). Follows `cloud-account-redesign.md` (data, logic, ops) and
`cloud-account-ui.md` (the component set). The 2026-10-13 layout (#964) had the right four islands but a
poor organisation: pages grouped by where the code came from rather than by what the person is doing, long
pages that dumped every value, a grid that did not fit a phone, and no state before the data. This spec is
the one hierarchy and the display rules, screen by screen.

## 0. The two rules

**Organisation: group by what I am doing.** Four islands, each one question:

| Island | The question | Pages |
|---|---|---|
| **Home** | Who am I on this phone, am I connected, what do I feed in | Account · Connections · Connect · Import |
| **Profiles** | What the account holds for my apps, my forms and my devices | Apps · Autofill ▸ (Personal · Sites · Snippets) · Phone ▸ (Devices · Working · Diff) |
| **Secrets** | My credentials: by provider, the fleet keys, who may read them, the bundle | Providers · Fleet keys · Grants · Bundle |
| **Configs** | Make and keep the phone right: setup steps, the privileged channel, this app | Setup ▸ (Runbook · Install · Push · Perms) · ADB Shell · Settings |

A section's `pages` is its top strip; a page's own `pages` is a second strip under it (libs:bottomnav
`NavPage.pages`, both drawn by `PageTabs` on the surface the declared `background` picks through
`NavDecl.stripSurface`). Every leaf is at most two taps from the island; no strip has more than four pills,
so labels stay full-size on a 360 dp phone. Island order stays Profiles · Secrets · **Home** · Configs
(Home in the middle, the thumb's place; the app opens there).

**Display: state first, then the next action, then the detail on demand.**

1. Every list row leads with a `KitStatePill` (connected / expired / missing / signed in / not installed) and
   its secondary line names the next action ("next: re-auth 1 expired", "next: set up SSH"). The detail
   (the ways, the stored keys, the Setup steps) opens on a tap; a card opens by itself only when something
   needs doing.
2. A page opens with one banner that says the state of the whole page and carries the one action that
   fixes it (nothing connected → Connect; expired → Re-auth; all good → an OK banner).
3. No raw dumps, no wide grids: the old providers × ways table is a list of providers, each opening its
   three ways as rows; a secret is a fingerprint until revealed; an app's settings are counts, its endpoint
   is one line.
4. Consistent words: a provider is *connected / expired / missing*; an app is *signed in / signed out /
   configured / not captured / not installed*; a Setup step is *ok / failed*; counts carry their unit
   ("3 stored", "12 setting(s)", "2 key(s)").
5. Clear empty, loading and error states, drawn with the kit: `KitEmptyState` in a card, a BUSY
   `KitStatusBanner` while a plan is read off the main thread, a WARN banner when it cannot be read.
6. Related fields grouped in one `KitCard`; one primary (filled) action per block, the rest outlined.

Rules kept from the redesign: Setup is no island (each app profile and each provider has its own Setup;
Connections has Setup all); every secret is masked, reveal and copy go through `RevealGate` and device
auth, a copy clears itself, nothing logs a value.

## 1. Before / after, per screen

| Screen | Before (2026-10-13) | Why it confused | After |
|---|---|---|---|
| **Home ▸ Home** | One long page: hero, four tiles, the providers × ways grid, Import configs, Import tokens, every token, Setup all | Six jobs on one scroll; the grid clipped on a phone; the token list repeated Secrets; importing sat between status and setup | Split by job: **Account** (the person and phone), **Connections** (state + next action + provider list + Setup all), **Import** (feed data in) |
| **Home ▸ Account** | The libs:account profile page, reachable only as the second pill | It was the best first screen and was hidden | The first page of Home: hero, device card, four tiles that link into Profiles and Setup, Backup / Restore / Migrate, About |
| **Home ▸ Connections** (new) | The grid: Provider × Web OAuth / API token / SSH, a pill and a word per cell | 4 columns on 360 dp; no sentence said what to do first; nothing said "all good" | Tiles (credentials, expired, connection values, app settings) → one banner with the state and its action → one row per provider (worst state, "next: …") → the three ways on a tap, each with Test / Re-auth / Set up → Setup all |
| **Home ▸ Connect** | As it was | — | Unchanged (libs:account), now the third pill, after the status it fixes |
| **Home ▸ Import** | Import (personal data) page; the token importer only inside Home ▸ Home | Two importers in two places with different frames | One page, two blocks in the order they are used: personal data (review, save; ID documents routed to Cloud Vault), then tokens (masked table, per-row or Import all); a done banner says where the data landed |
| **Profiles** strip | Apps · Personal · Sites · Snippets · Devices · Working · Diff (seven pills) | Pills shrank to 8 sp; three unrelated groups in one row | Apps · **Autofill** (Personal · Sites · Snippets) · **Phone** (Devices · Working · Diff) |
| **Profiles ▸ Apps** | Plan built on the main thread; a flat list; the detail page listed fields with no state | Froze on open; no summary; the state was a word in a list | Loading banner while the plan is read; two tiles (set up / to set up); each row = state pill + endpoint + counts; the detail repeats the state pill, groups the fields in one card, Setup in its own card with the three steps and one primary action |
| **Profiles ▸ Autofill / Phone** | As they were | Only the grouping | Unchanged pages under their strip |
| **Secrets ▸ Providers** | Every provider card fully open: every secret line, every Setup block | A wall of masked lines; the state was lost in it | Each card is one status row (pill + "next: …" + ways); it opens on a tap, by itself when missing or expired; Test is disabled when there is nothing to test; an empty page offers Import tokens |
| **Secrets ▸ Fleet keys / Grants / Bundle** | Bundle was `connections`, beside Home's Connections | Two "connections" | Same pages; Bundle has its own id (`bundle`) |
| **Configs** strip | Settings · Runbook · Apps · Fleet push · Perms · ADB Shell (six pills; Apps collided with Profiles ▸ Apps by name) | Settings first though it is used last; "Apps" meant the install inventory here | **Setup** (Runbook · Install · Push · Perms) · ADB Shell · Settings; opens on the runbook |
| **Configs ▸ Setup ▸ …**, **ADB Shell**, **Settings** | As they were | Only the grouping and names | Unchanged pages (libs:account, libs:shizuku-adb-debug-tools) |

Page ids are stable across the move: a leaf keeps the id it had (`sites`, `diff`, `perms`, `apps`), so every
`open(section, page)` in libs:account and every test tag still names the same thing. New ids: the three
containers (`autofill`, `phone`, `setup`), `connections` under Home, and `bundle` (was `secrets/connections`).

## 2. The flow

Open → **Account** (who, which phone; backup) → **Connect** (sign in, fetch the vault) → **Import** (paste
what the vault does not hold yet) → **Connections** (is every provider reachable; Setup all) → **Profiles**
(what the account holds, per app, per form, per device) → **Configs ▸ Setup** (make the phone match) →
**Secrets** (rotate, grant, export). Every libs:account link lands on this path: Account's tiles open Setup ▸
Install, Phone ▸ Working, Phone ▸ Diff and Setup ▸ Perms; Restore / Migrate open Setup ▸ Runbook; the
runbook's `connected` step opens Connect; Perms' "Open ADB Shell" opens ADB Shell.

## 3. Old places still work (`hub/HubNav.LEGACY`, `MainActivity.deepLink`)

| Old (section, page) | Now |
|---|---|
| account / "", profile | home / account |
| account / connect · autofill · sites · snippets · import | home / connect · profiles / personal · sites · snippets · home / import |
| profiles / "" · devices · working · diff | profiles / apps · devices · working · diff |
| setup / "" · runbook · apps · configs · perms · adb-shell | configs / runbook · runbook · apps · configs · perms · adb-shell |
| secrets / "" · connections · secrets · grants | secrets / providers · **bundle** · secrets · grants |
| settings / "" | configs / settings |
| home / "" · home · account · connect · import (2026-10-13) | home / account · account · account · connect · import |
| configs / "" · settings · runbook · apps · configs · perms · adb-shell (2026-10-13) | configs / runbook · the same leaf |

`HubNav.resolve` translates an exact (section, page) pair first (so a renamed page inside a current section
is caught), passes any other current section through, and sends an unknown section to Home. A
`com.diegonmarcos.cloudaccount.OPEN` intent may carry `section` / `page` extras with old or new ids; they go
through the same map at launch and in `onNewIntent`.

## 4. Testers

- `ac_cloud-account/test/test-account-hub.sh`: the declaration (islands, strips, backgrounds), every old id's
  place, every LEGACY target a leaf, the resolver's order, deep links through the resolver, the second strip on
  its own surface, the Connections page's order (tiles, banner, list, Setup all) and its status-first list,
  the provider card's status row, the Apps page's loading / error / empty states, the security rules
  (RevealGate before read, fail closed without a lock, self-clearing copy, no logging), each with a planted
  mutation.
- `test-account-app.sh`: the nested declaration, every leaf a branch of `Page()`, both strips drawn.
- `HubTest.kt` (Robolectric): island order, every old page reachable, every LEGACY target a leaf, strip paths,
  OPEN-intent deep links, Cloud Chat's setup schema, plus the existing Connections / import / reveal / copy /
  Setup / no-log suites.
- `test-setup-schema-coverage.sh`: every fleet app setting has a setup schema or a named gap (section 5).

## 5. Per-app setup schemas (what Profiles ▸ Apps and Setup act on)

An app's setup schema is what libs:account `SetupPlan` reads from `fleet-config.json`: the migrating stores
declared `used_by` its module or its libs, the `bundle` routes from Cloud Account's Connections paths into
those stores, and the app's `items` saying where each setting lives (`store`, or `via: install | connect`).
Per-app Setup is then login (installed) → endpoint (the `*url` keys the plan carries) → test (push through
the app's `<package>.fleetsetup` provider and read back).

Cloud Chat (Mattermost mobile fork, `ac_cloud-chat`) had none: its server list lived only in the RN layer.
Added 2026-10-14: store `chat_setup` (prefs, config: `server_url`, `server_name`), routes `chat.server_url` /
`chat.server_name`, the `server-link` item; in the fork a read-only native module `CloudFleetSetup` reads the
store and the server screen uses it after a managed (EMM) config and before the local default, so an empty
phone opens Cloud Chat on the fleet's server. Sign-in stays interactive; the session token stays in the
Keychain and is never pushed. `test-setup-schema-coverage.sh` keeps the ratchet: today's remaining gaps
(Cloud Code's localStorage settings, Element X's session database, the news engine's files, the terminals'
dotfiles) are named in `test/setup-schema-gaps.json` with their reason; the list only shrinks.
