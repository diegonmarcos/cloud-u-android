# Cloud SuperApp — sign-in · identity · peer · get-everything: ONE flow (#573)

Diego, 2026-09-25: "THIS DESIGN, THIS FLOW IS JUST A MESSY PATCHWORK — MAKE A FULL
REDESIGN." The Profile page had accreted a Connect tab of loose boxes (account
email, bearer, mail code, vault code) and an Infos row of five import tiles, and
the first #573 pass wired two spinners on top. Nothing told the owner what order
things happen in, what is done, what is next, or which device they are standing
on. This spec replaces all of it with one designed journey and is what
`ProfileFragment` builds. Nothing on the Connect tab exists that this file does
not name.

## 1. Where it lives

Configs ▸ Profile keeps its strip: **Fleet | Connect | Infos | WireGuard | AI**.
Fleet stays the #570 cockpit (unchanged). Infos stays the contact card, privacy
disclosure and erase. **Connect is rebuilt from zero as THE JOURNEY** — four
numbered steps, top to bottom, in the cockpit's own visual family. Fleet's empty
state still points at Connect ("Connect the vault").

## 2. The journey

```
┌ HERO ──────────────────────────────────────────────────────────┐
│ (orb: identities icon)  Diego Coelho Marcos                    │
│                         me@diegonmarcos.com · Samsung Galaxy S21+ │
│                         ● On   Step 4 of 4 · everything applied │
│   ① Sign in ●   ② Who ●   ③ Device ●   ④ Get everything ●       │
└────────────────────────────────────────────────────────────────┘
┌ 1 · Sign in ─────────────────── ● On ──────────────────────────┐
│ Signed in as me@diegonmarcos.com via Authelia                  │
└────────────────────────────────────────────────────────────────┘
┌ 2 · Who ─────────────────────── ● On ──────────────────────────┐
│ ● me@diegonmarcos.com · personal · primary                     │
│ ○ 42@diegonmarcos.com · 42        ○ admin@… · admin   ○ x@… · x │
└────────────────────────────────────────────────────────────────┘
┌ 3 · Which device ────────────── ● On ──────────────────────────┐
│ ○ Surface Pro 8 · notebook · 10.0.0.5 · no phone profiles      │
│ ● Samsung Galaxy S21+ · phone · 10.0.0.9 · 4 profiles          │
│ ○ Samsung Galaxy A37 · phone · 10.0.0.10 · profiles pending    │
└────────────────────────────────────────────────────────────────┘
┌ 4 · Get everything ──────────── ● On ──────────────────────────┐
│ profile ✓ · wireguard 4 profiles for S21+ ✓ · mesh · services   │
│ [Apply to this device]                                          │
│ Vault export: [Send me the code] [code] [Fetch & open Fleet]    │
│ Import from a file instead                                      │
└────────────────────────────────────────────────────────────────┘
```

Exactly four steps, in this order, always all four on screen. A step is one
`FleetCockpitView.card` (round badge, bold label, the shared `StatusLight`, a
summary line, a body). The hero is `FleetCockpitView.hero`. Every button is
`FleetCockpitView.pill`. No colour, no font and no radius is declared by the
journey: it is the #570 cockpit's chrome, so Connect and Fleet read as one
screen and a theme change restyles both.

### Step lights (the shared StatusLight, nothing private)

| state | light | when |
|---|---|---|
| done | `● On` | the step's answer exists |
| active | `? Unknown` | this is the next thing to do; its body is open |
| locked | `? Not verifiable` | an earlier step is not done; the summary names which |
| failed | `○ Off` | the last attempt at this step reported an error (the text stays in the card) |

The hero's light is `● On` only when all four are done; its summary is
"Step N of 4 · <what step N asks>". The rail line under it repeats the four
glyphs so the whole state is one glance. Locked and done bodies are collapsed;
the active body is open; tapping a done card's header re-opens it — the
cockpit's own header toggle, so "change" is the same gesture on every step.

### Step 1 · Sign in

Body: one pill per provider in `build.json::ui.vault_connect.sign_in.providers`,
in declared order, filtered by the artifact's `auth_providers` policy once one is
known. The pill text is the provider's declared `label`; the flow it opens is
dispatched on `kind` and `grants` only — no Kotlin names a provider:

* `kind: authelia` → two pills: **`<label> · browser login`** (primary; the
  existing WebView login, cookie kept in memory for the request) and
  **`<label> · paste a bearer`** (the existing bearer dialog). A successful bearer
  fetch STORES the bearer paired with the primary identity the artifact names
  (`ConfigsPrefs.setAutheliaCredential`) — that is the durable sign-in the vault
  route needs; the separate email box and token box are gone.
* `kind: device_flow` → **`<label> · browser code`** (RFC 8628 device grant,
  `SignIn.kt`, one dialog for every such provider). If the provider `grants
  repo_artifact` a second pill **`<label> · SSH key`** offers the existing
  `GitSshVault` clone route.
* a provider whose `configured` is false renders its pill disabled with "· not
  configured in this build".

A sign-in that `grants config_artifact` or `repo_artifact` fetches the artifact
in the same tap (`runFetch`), remembers the registry (`UserRegistry.remember`)
and sets the session. A provider that grants `identity` only sets the session
with the proved address; step 2 then says "<label> proved <address> — sign in
with a provider that grants your config to load identities and devices".

Done summary: "Signed in as <identity> via <label>". With no session but a
stored bearer: "Bearer stored for <email> · tap to use it" and a pill
**`Use the stored bearer`** that fetches without pasting. A `Clear stored
bearer` pill sits under it (confirmed, as today). The Authelia mailed
identity-validation code keeps its one job inside this step as a small pill
**`Authelia mailed you a code?`** → dialog: paste, copied to the clipboard,
Authelia page opens (unchanged mechanics, no longer a permanent box).

### Step 2 · Who

Locked until a registry exists. Body: the user name line ("Diego Coelho
Marcos · 4 identities · 3 devices" — every word from the artifact) and one
selectable row per identity: glyph `●`/`○`, address, label, "primary" tag.
Tapping persists the address (`UserRegistry.selectIdentity`). Default when
nothing is stored: the address the sign-in proved if it is one of the
identities, else the primary. Done summary: "<address> · <label>".

### Step 3 · Which device

Locked until step 2 is done. Body: one selectable row per peer from the
registry: the kind's icon (`cockpit.device_icons`, data), label, kind, wg0
address, and "N profiles" / "profiles pending" (a peer whose `wireguard` map is
empty). Tapping persists the peer id (`UserRegistry.selectPeer`), points the
Fleet cockpit at the peer's `vault_device` (`VaultCockpit.selectDevice`), and
re-applies nothing by itself. Default when nothing is stored: the primary peer.
Done summary: "<label> · <wg0 ip> · N profiles".

### Step 4 · Get everything

Locked until step 3 is done; also locked, with the reason, when the artifact is
not in memory (after a process restart: "Sign in again to re-fetch before
applying"). Body:

1. a checklist of what the artifact carries for this peer — profile,
   wireguard (N profiles of <peer>), mesh, services, configs — and the pill
   **`Apply to this device`** → `ConfigAutoImport.apply`, which writes the
   CHOSEN peer's profiles (`UserRegistry.selectedPeer`), reports per section in
   the card, and records `applied_at`;
2. the #566 vault export, two pills and a code box as today (`Send me the
   code` → `VaultConnect.start`; code; `Fetch & open Fleet` → `VaultConnect.fetch`
   then the strip jumps to Fleet, whose device is already the chosen peer);
3. `Import from a file instead` — the manual paste/file route, the one entry
   that needs no credential, demoted to a last line rather than a tile.

Done summary: "Applied <date> · vault <fetched|not fetched>".

## 3. Data model — ONE declaration, zero literals in Kotlin

```
cloud-infra 1_cloud-configs/src/inputs/superapp-users.json
  users.<slug>
    profile            (as before)
    identities[]       {email, label, primary}   exactly ONE primary
    peers.<id>         {label, kind, wg_client, vault_device, vault_wg_dir?, primary}
                       exactly ONE primary; wg_client must be a client on a mesh
    auth_providers[]   provider ids this user may be offered, in order
```

`deriveSuperappConfig` emits `identities`, `peers.<id>{label,kind,primary,
vault_device,wg0,wg_public,wireguard{<profile>{name,config_text,parsed}}}` and
`auth_providers` into `build-cloud-superapp-<slug>.json`; the top-level
`wireguard` block stays the PRIMARY peer's (what the APK's wireguard-profiles
guard mirrors). A peer's mesh identity is READ from the mesh declarations
through `wg_client`, never restated. `regen-superapp-wireguard-profiles.js`
publishes the redacted profiles PER PEER for every peer that declares
`vault_wg_dir`.

The app reads: providers from `build.json::ui.vault_connect.sign_in` (baked
`UI_VAULT_CONNECT_SIGN_IN_B64`); the registry from the artifact
(`UserRegistry.parse`); device icons from `ui.vault_connect.cockpit.device_icons`.
`test/test-profile-journey.sh` T2 fails the build on any user, address, device
or provider literal in the profile package.

### Samsung Galaxy A37 (new peer)

Declared on wg0 in cloud-infra `config.json#native.wireguard.clients.samsung-a37`
(10.0.0.10 / fd0c:1d00::10, its own X25519 key, keypair in cloud-vault
`A0_keys/providers/wireguard/samsung-a37{,-public}/`), and as the peer
`samsung-a37` of user `diego`. Its wg-public row lives in
`cloud-u-containers/infra-net_wireguard-public/build.json`, which #573 may not
touch; until that row exists the phone has NO published profiles (the vault
deriver refuses to invent an address), the artifact carries `wireguard: {}` for
it, and step 3 says "profiles pending". Adding the row + `vault_wg_dir` +
`derive-phone-profiles.py` is the whole remaining work — data, no code.

## 4. Persistence

| what | where | why |
|---|---|---|
| session (provider + identity) | memory (`SignIn.Current`) | dies with the process; a device-grant token is never stored |
| Authelia bearer + its address | `ConfigsPrefs` (encrypted) | the vault route needs it across restarts — as before |
| registry (identities, peers) | `user_registry` prefs, as the JSON the artifact carried | public data from a public repo; lets steps 2–3 stay answered across restarts |
| chosen identity, chosen peer, applied_at | `user_registry` prefs, ids only | the picks |
| the artifact | memory (`UserRegistry.Current.artifact`) | step 4 needs it; a restart asks for a re-fetch, it says so |
| vault bundle | memory (`VaultConnect.Imported`) | as #570 |

## 5. Top-nav strip spacing (same ticket)

Every tab strip in the app (`SectionTabsFragment`, Profile's, Launcher's, the
drawer's) is styled by `AppTabsStyle.apply`. Its vertical geometry is now ONE
declaration: `res/values/dimens.xml` `tab_strip_top_inset` (base above) and
`tab_strip_bottom_inset` (gap to the page below). `AppTabsStyle.apply` sets
both as margins and, for a strip under the toolbar island, adds the live
status-bar/cutout inset on top — the listener that used to live only in
`SectionTabsFragment`, so Profile's and Launcher's strips no longer sit higher
than a section's. `AppTabsStyleTest` reads the two dimens back off a styled
strip; `test-tab-strip-spacing.sh` fails on any per-page override.

## 6. What is deleted

The Connect tab's account-email box, bearer box, "Clear stored Authelia bearer
token" as a bare button, the permanent mail-code box, the "Vault configs"
header with its four controls, the Infos tab's "Imports" header and its five
tiles, `showGithubDeviceDialog`, the `ui.config_source.github_oauth` block and
the four `UI_GH_OAUTH_*` BuildConfig fields, the first-pass `buildSignIn` /
`buildRegistry` spinners.

## 7. Testers

* `app/src/test/.../ProfileJourneyTest.kt` — the state machine: which step is
  active, each step's light, the summaries, the defaults (proved address wins
  over primary; primary peer), locked reasons; the layout: four cards tagged
  `step:*` in order under the hero, active body open, locked bodies closed.
* `SignInTest`, `UserRegistryTest` (kept), `AppTabsStyleTest` (new).
* `test/test-profile-journey.sh` — data-only providers, no literals, strings
  in every locale, the journey is what Connect renders, tokens never stored,
  the picks drive the cockpit device and the apply, the old boxes are gone;
  cross-repo checks when cloud-infra sits beside.
* cloud-infra `9_others/test/superapp-users-model.test.sh` — one primary
  identity, one primary peer, every peer on a mesh, published profiles filed
  under their own peer.
* cloud-vault `A0_keys/providers/wireguard/test-phone-profiles.sh` — derived
  profiles are the deriver's, pending peers have none.
