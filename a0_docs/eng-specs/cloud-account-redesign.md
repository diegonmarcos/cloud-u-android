# cloud-account — the Cloud Account Manager redesign

Status: DESIGN (2026-10-08). No code yet. Dispatch after the owner approves.
Repo: cloud-u-android · app `ac_cloud-account` · code in `ab_cloud-libs-shared/libs/account` (SuperApp hosts the same lib).

## 0. The job of the app, in one line

Cloud Account turns an empty phone into *this owner's* phone and keeps it that way: it holds the account,
backs up every runtime config into the private vault repo, restores a device profile or DEFAULT, installs the
apps through Cloud Store, pushes configs into every fleet app, grants permissions, and manages fleet secrets.

## 1. What is wrong today

- Four island tabs (Connect / Profiles / Runtime / Drift, #778) with eight child pages bolted on by #873/#874
  (Connections, Data, Configs, Secrets, Fleet Setup). Three places say "configs", two say "connect", and
  Runtime ▸ Drift ▸ Fleet Setup are three views of one question: *does the phone match the file?*
- The S / R / L three-way model (server, runtime, local) is architect vocabulary. The owner has two things:
  **the file in the vault** and **the phone**.
- `ProfileFragment` is 1 550 lines of Views plus Compose islands (`ProfileJourneyView`, `FleetCockpitView`,
  `BusinessCardFragment`, `QrGalleryDialog`). It cannot be reshaped, only replaced.
- Reading the vault works (contents API, gh engine, SSH clone); **writing** exists only as `AccountUpload`
  (one blob PUT to GitHub). Nothing writes to Gitea; nothing knows "this device's file" vs DEFAULT.
- No shell channel: Account cannot turn on Wireless debugging, cannot grant a permission, cannot install
  Cloud Store on an empty phone. Cloud Store got its own channel in #894; Account did not.

## 2. Decisions

1. **Two sources, one shape.** The vault repo is read and written through the forge *contents API*, which
   GitHub (`api.github.com/repos/{repo}/contents/{path}`) and Gitea (`git.diegonmarcos.com/api/v1/repos/
   {owner}/{repo}/contents/{path}`) expose with the same GET/PUT body. One `ForgeClient`, two declared
   forges, no git push engine (gix has no push; JGit stays for the SSH *read* route only).
2. **Per-device files, owned by the app.** `C_A1-configs/devices/<device>.json` + `devices/DEFAULT.json`.
   Written only by this app (commit + push from the phone). No GitHub Actions, no hand fold, no sops.
   `emit.py check` must not see `devices/` as an input (one exclusion line in cloud-vault; the only change
   outside the app).
3. **Secrets never enter a device file.** A device file holds config-class and device-class keys (the setup
   contract's classes) and *references* secret-class keys by vault path. Secret values live in
   `profile-secrets.json` (the emitted file) and on the phone in the encrypted `AccountVault`.
4. **Account gets its own shell channel.** The engine is `libs:shizuku-adb-debug-tools` (embedded adb pairing,
   Shizuku fallback, `WRITE_SECURE_SETTINGS` self-grant). The host glue that Cloud Store wrote in
   `CloudStoreShell` (#894) moves into the lib as `HostShell` and both apps call it. Account is the first app
   on an empty phone, so it pairs first; it then installs Cloud Store through `pm install` and the Store
   keeps running its own channel as today.
5. **Cloud Store installs, Account decides.** Account hands the device's declared inventory to the Store
   (`StoreImport.EXTRA_IMPORT`, exists) and reads the Store's state back. No install code in Account except
   the one bootstrap `pm install` of Cloud Store itself.
6. **SuperApp owns the VPN.** WireGuard profiles, the active tunnel and the DNS preset are pushed into
   SuperApp over the setup contract (`<pkg>.fleetsetup`, #873). Account draws no tunnel UI; it shows the
   row "SuperApp: mesh ✓/✗" and the consent it can pre-grant (`appops ACTIVATE_VPN`, to verify on device).
7. **Cloud Vault (Vaultwarden) stays the password manager.** Account's Secrets section is the *fleet*
   secrets store with per-app grants; its header links to the Cloud Vault app for passwords. No duplication.
8. **Compose on libs:ui-kit, island + strips from libs:bottomnav, declared in build.json::ui.** Same look as
   Cloud Store: dense rows with a state line and one button each, a top bar with the bulk action, a plan
   sheet before anything acts.

## 3. Navigation

Five island entries (build.json::ui.bottom_nav), child pages as strips (ui.sections[].pages):

| Island | Pages | Answers |
|---|---|---|
| **Account** | `profile` · `connect` | Who am I, which device is this, am I connected, when was the last backup / restore |
| **Profiles** | `devices` · `working` · `diff` | Which device files exist, what the loaded one says, how the phone differs from it |
| **Setup** | `runbook` · `apps` · `configs` · `perms` | Make this phone match the loaded profile, step by step (the Store look) |
| **Secrets** | `connections` · `secrets` · `grants` | The fleet's tokens, keys and endpoints; who may read what |
| **Settings** | — | Device id, default forge, auto-backup, fleet token, debug API |

Deleted: Connect / Profiles / Runtime / Drift as tabs; Connections / Data / Configs / Fleet Setup as strips.
Every old page's *function* lands in exactly one new page (section 9 maps them).

## 4. Pages

### 4.1 Account ▸ profile
- Identity card: name, email, avatar from the vault `about` topic (through `InfoMask`). Tap = edit unmasked
  fields, Save writes the working profile.
- Device card: **this phone** = device id (picker over `electronics.fleet` + "new device…"), model, Android,
  loaded profile name, captured-at.
- Four tiles: Vault fetched @ · Last backup @ · Last restore @ · Drift (n keys). Tap Drift → Profiles ▸ diff.
- Actions row: **Backup now** (capture → this device's file → push) · **Restore** (loaded file → phone, opens
  Setup ▸ runbook) · **Migrate this phone** (Setup ▸ runbook, Run all).

### 4.2 Account ▸ connect
One pill per way, dispatched on `kind` (the #573 pattern, kept):

| Forge | Ways | Read | Write | Note |
|---|---|---|---|---|
| GitHub | gh WebAuth (`gh_auth_login`) · PAT (`github_pat`) · SSH key (`github_ssh`) | contents API / clone | contents API | SSH = read only (JGit clone, no push); say so on the pill |
| Gitea | Token (`gitea_token`) · Authelia WebAuth (`authelia_web`) | contents API | contents API | Authelia → private repos need the Gitea↔Authelia user mapping (infra prerequisite, flagged, not blocking: Token works today) |
| File | Pick the decrypted export (`vault_file`) | file | — | offline / air-gapped |

Below the pills: **Origin** = which forge is primary (radio), repo + branch (declared, read-only), **Fetch
now**, last fetch result. The token is filed in `AccountVault` Connections at the declared path; a token is
never shown back. Sign-in ends with the device pick (step 2 of the runbook) and nothing after (#766).

### 4.3 Profiles ▸ devices
One row per `devices/*.json` in the vault plus DEFAULT, in the Store's row language:
`galaxy · this phone · 142 settings · 61 apps · captured 2026-10-07 21:14` → buttons **Load** · **Backup
here** · **Set as DEFAULT** · **Delete**. DEFAULT's row has **Load** only. A device with no file yet shows
"not captured" and **Backup here**. Top bar: **Fetch** · **Backup now** (this phone's row).

### 4.4 Profiles ▸ working
The loaded profile by topic (schema.json order): about, mesh, mail, ai, git, settings (per app), apps, perms,
system. Every declared field, value or `empty`, masked where `InfoMask` says so. Edit unmasked → **Save**
(commit + push to the loaded file; the message names device, topic and key count). This is the old Profiles
tab minus Populate (that is Backup) and minus the S/R/L switch.

### 4.5 Profiles ▸ diff
File ↔ phone, per app, per key: `mail › accounts › smtp_host  file: … phone: …`. Per row **→ phone** (apply
one key over the contract) and **← file** (capture one key). Per app and at the top: **Apply all → phone**,
**Capture all ← file**. Values masked by class (secret-class rows show presence + fingerprint only).
This is the old Drift tab with one pair instead of three.

### 4.6 Setup ▸ runbook  (the migration)
An ordered checklist. Each row: state glyph (○ todo · ● running · ✓ done · ✗ failed with the reason) +
one button. **Run all** at the top walks them in order and stops at the first ✗; a row's button re-runs
that row only. A plan sheet (counts, names, never values) shows before Run all acts, like `StoreImport.show`.

1. **Connected** — a forge token present, vault fetched. Button: Connect.
2. **Device profile loaded** — this phone's file, else DEFAULT. Button: Pick.
3. **Shell channel** — Wireless debugging on + embedded adb paired (Shizuku if present). Button: Pair
   (starts `AdbPairingService`: the code is typed into the notification). On success: self-grant
   `WRITE_SECURE_SETTINGS` so the channel survives reboots (`HostShell`).
4. **Cloud Store installed** — fleet release asset through `pm install` on the channel. Button: Install.
5. **Apps installed** — declared inventory handed to the Store (`EXTRA_IMPORT`); the row reads the Store's
   plan back: `61 declared · 58 installed · 3 need a source`. Button: Open Store.
6. **Configs applied** — `FleetSetup.run` over every installed fleet app, SuperApp first (mesh profiles,
   active tunnel, DNS preset), then mail, then the rest. Row: `✓ 11 apps · ✗ me: mail.password: no grant`.
7. **Permissions granted** — Setup ▸ perms, Grant all. Row: `✓ 58 apps · 2 need the user`.
8. **Verified** — read back every app's export and diff: `drift 0`. Button: Re-check.

Steps 3 and 4 are skipped with "already" when the phone has them; a re-run of the runbook on a set-up phone
is therefore a no-op that proves it.

### 4.7 Setup ▸ apps
The device's declared inventory vs this phone, read from the Store's own classification (fleet / upstream
vendor / F-Droid / needs a source). Read-only rows plus **Install missing via Store** and **Capture
installed → profile**. No Play rung: an app with no vendor or F-Droid source shows "no source declared" and
a link to the source map, never a Play page.

### 4.8 Setup ▸ configs
`FleetSetupTab` as it is today (#873): one row per fleet app, keys held, last ✓/✗, Retry, Install.

### 4.9 Setup ▸ perms
Per app (fleet and upstream): the runtime permissions it declares, granted / denied, plus the special
grants the profile records. **Grant all** runs over the shell channel:

| Need | Shell verb |
|---|---|
| Runtime permission | `pm grant <pkg> <perm>` |
| App op (overlay, usage stats, install packages, VPN consent) | `appops set <pkg> <OP> allow` |
| Notification listener | `cmd notification allow_listener <component>` |
| Default app (dialer, sms, browser, home) | `cmd role add-role-holder <role> <pkg>` |
| Battery | `dumpsys deviceidle whitelist +<pkg>` |
| Play Protect, unknown sources | `PackageVerifier` (exists) |

A row the shell cannot grant (a device-admin or accessibility toggle) says "needs the user" and opens the
Settings page. The profile's `perms` topic is exactly this page captured.

### 4.10 Secrets ▸ connections / secrets / grants
`AccountVaultTabs` kept, re-homed: Connections (every `section.key` path, value shown only when its class
is non-secret, bundle export/import), Secrets (secret-class keys: presence + fingerprint, source, the Cloud
Vault link), Grants (package → keys, revoke; the `SecretGrants` UI).

### 4.11 Settings
Device id override · primary forge · **Auto-backup** (off / daily / on Wi-Fi: capture, compare, push only
when changed; WorkManager, like `ConstellationWorker`) · fleet token · debug API on/off · about/version.

## 5. Data

### 5.1 The device file — `C_A1-configs/devices/<device>.json`
```json
{ "kind": "cloud-account.device-profile", "schema": 1,
  "device":   { "id": "galaxy", "model": "SM-G996B", "android": 34, "captured_at": "…", "by": "cloud-account/<ver>" },
  "apps":     { … the cloud-sa.app-inventory JSON (schema 1) … },
  "settings": { "<app id>": { "<store>": { "<file>": { "<key>": value, "_types": {…} } } } },
  "perms":    { "<pkg>": { "granted": […], "appops": {…}, "roles": […], "battery": true } },
  "system":   { "wireless_debugging": true, "play_protect": false, "dns_preset": "…", "mesh_profile": "…" } }
```
`settings` is `AccountVault.appConfigs()`'s shape with secret-class keys replaced by `"@vault:<path>"`.
`apps/<device>-apps.json` folds into `apps` (one-time, by the app on first Backup; the old file is left).
DEFAULT is a device file whose `device.id` is `DEFAULT`; **Set as DEFAULT** copies a device file over it.

### 5.2 On the phone
`AccountVault` (four sections, encrypted) unchanged. New keys: `device.id`, `forge.primary`,
`forge.<id>.token`, `backup.last`, `restore.last`, the loaded profile as the `working` slot (replaces L; S
is the file's sha; R is read live, never stored).

### 5.3 New code (all in libs:account unless said)
- `ForgeClient` — `get(path, ref) → {text, sha}`, `put(path, text, sha, message)`; GitHub uses
  `Authorization: Bearer`, Gitea `Authorization: token`; 409/422 = stale sha → refetch and retry once.
- `DeviceProfile` — parse / build / capture (runtime → file) / plan (file → runtime).
- `SetupRunbook` — the eight steps as `(check, run)` pairs; the page only draws them.
- `PermsPlan` — declared vs granted per app; the shell verbs table above.
- `HostShell` (libs:shizuku-adb-debug-tools) — `CloudStoreShell` moved; Store and Account call it.
- `AccountDebugApi` — ops renamed to the pages: `devices`, `load`, `backup`, `restore`, `runbook
  (dry=1|run=1|step=)`, `perms (dry=1|run=1)`, `forge (get|put dry=1)`; `vault`, `setup`, `import` kept.

## 6. Declarations added to build.json (ac_cloud-account)
```
ui.bottom_nav: [account, profiles, setup, secrets, settings]
ui.sections: account[profile, connect] · profiles[devices, working, diff] · setup[runbook, apps, configs, perms]
             · secrets[connections, secrets, grants] · settings[]
ui.account.forges: [ {id: github, api: https://api.github.com, repo: <declared>, auth: bearer, ways: [gh_auth_login, github_pat, github_ssh]},
                     {id: gitea,  api: https://git.diegonmarcos.com/api/v1, repo: <owner/repo to confirm>, auth: token, ways: [gitea_token, authelia_web]} ]
ui.account.vault: { branch: main, devices_dir: C_A1-configs/devices, secrets_file: C_A1-configs/profile-secrets.json }
ui.account.runbook: the eight step ids in order (so a step can be disabled by declaration)
ui.account.perms.shell_verbs: the table in 4.9
shizuku_client: as cloud-store's (embedded first, Shizuku fallback)
```
The fleet-setup guard and the nav guard (1_cicd) read these; the Gitea repo path is the one value this
design does not know and must not invent.

## 7. What is deleted
`ProfileFragment`, `ProfileJourneyView`, `FleetCockpitView`, `BusinessCardFragment`, `QrGalleryDialog`,
`AccountStore`'s S/R/L slots (L → `working`, S → sha), `AccountUpload` (→ `ForgeClient.put`),
`AccountTabs`, the `profile.tabs` / `profile.connect` declarations in SuperApp's build.json (SuperApp's
Config ▸ Account page becomes a link that opens Cloud Account, as Store did for the store pages).
Kept as is: `AccountVault`, `ConfigsPrefs`, `SetupPlan`, `FleetSetup`, `FleetSetupTab`, `AccountVaultTabs`,
`AccountMigrate`, `AccountFleet`, `InfoMask`, `GhEngine`, `GitSshVault`, `VaultCockpit` (its layout data).

## 8. Testers (the debug API drives every one; green CI ≠ done, device-verify)
1. `runbook?dry=1` on a set-up phone → eight rows, all "already"; `run=1` changes nothing (drift 0).
2. `backup` → the device file's sha changes on the forge; `forge?get` returns it; a second `backup` is a no-op.
3. `restore` on a phone with one mail account deleted → the account is back; `diff` reports 0.
4. `forge?put&dry=1` against Gitea and GitHub → same body, two bases.
5. Pairing: `runbook?step=shell&run=1` with Wireless debugging off → the row says what to do; on → paired,
   `settings get global adb_wifi_enabled` = 1 after a reboot.
6. `perms?run=1` → `dumpsys package <pkg>` shows the grants; a "needs the user" row stays honest.
7. A device file with a secret value in it is refused on Save and on Backup (class check), named by key.

## 9. Where every old page went
Connect → Account ▸ connect · Profiles → Profiles ▸ working · Runtime → Setup ▸ configs + Profiles ▸ diff ·
Drift → Profiles ▸ diff · Connections / Data / Configs / Secrets → Secrets ▸ * (Data's identities → Account ▸
profile) · Fleet Setup → Setup ▸ configs · Fleet ▸ Apps "Apply list to Store" → Setup ▸ apps.

## 10. Dispatch order (one agent per task via fire.sh, model per task, ending GREEN + device-verified)
Never fable. Sonnet for a move/rename or a page that only draws existing data; opus for new engines,
anything touching the shell channel, secrets or the write path to the forge.

| # | Task | Model | Why |
|---|---|---|---|
| 1 | `HostShell` move + Account's channel + runbook steps 3–4 (pair, install Cloud Store from Account) | opus | privileged shell, reboot survival |
| 2 | `ForgeClient` (GitHub + Gitea) + `DeviceProfile` + the secret-class refusal + `emit.py` exclusion | opus | write path to the vault, secrets boundary |
| 3 | New shell: five islands, Account ▸ profile/connect, Settings; delete `ProfileFragment` and friends | sonnet | layout over existing lib data, nav declared in build.json |
| 4 | Profiles ▸ devices / working / diff over the one pair | sonnet | draws `DeviceProfile` + existing drift logic |
| 5 | Setup ▸ runbook + `SetupRunbook` + Setup ▸ apps (Store hand-off) | opus | orchestration across apps, plan sheet before acting |
| 6 | Setup ▸ perms + `PermsPlan` (shell verbs, "needs the user" honesty) | opus | grants through the shell |
| 7 | Secrets section re-home + `AccountDebugApi` renames + testers 1–7 | sonnet | existing tabs, API surface, scripted checks |
| 8 | SuperApp: Config ▸ Account becomes the link; `profile.*` declarations removed | sonnet | deletion + one intent |

Order: 1 and 2 in parallel (separate worktrees, disjoint files), then 3, then 4–7 in parallel, 8 last.
