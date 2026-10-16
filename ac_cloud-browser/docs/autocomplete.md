# Cloud Browser Autocomplete (#900)

Owner decision 2026-10-16: the browser keeps the user's own profiles, per-site values, passwords and cards, and can
use Cloud Vault. This file states the model, the flows, and where it departs from
`a0_docs/eng-specs/autofill-3-tier.md` and `ac_cloud-vault/docs/autofill-tiers-contract.md`.

## Where it lives

- Configs > **Autocomplete** (sub-sections Profiles, Sites, Passwords, Cards, Cloud Vault), and the same blocks on the
  **Passwords**, **Payment** and **Sync & Vault** tabs.
- Page dropdown > This site > **Autocomplete for this site** (what is remembered for the site open now).
- A fill bar above the keyboard while a field has focus; a chooser sheet; a save prompt after a submit.

## Model

- **Profile** (`AcProfile`): a label ("Diego - ES"), a default flag, and LISTS of names (given / middle / family /
  full / alias), addresses (label + street, number, line 2, city, postal code, region, country), phones, emails,
  ID documents (type: national ID, passport, tax ID, residence permit, driving licence, other; number; issuing country;
  citizenship; expiry), birth dates, organisations. One record of each list is the default.
- **Fill** (`AcFill`): the form's fields are classified to HTML autocomplete tokens (`AcFieldClassifier`: the
  autocomplete attribute, then the type, then the words of name / id / label / placeholder / aria-label in EN, ES, PT,
  FR, DE). A token belongs to a GROUP (name, address, phone, email, date, organisation, document type); one record per
  group fills the whole group, so street, postal code and city always come from the same address. The chooser asks which
  profile (when several can fill) and which record of a group (when the profile holds several); the default is
  preselected and the profile last used on the site is preferred.
- **Per-site memory** (`AcSiteMemory`): a value typed into a field that is none of the above (customer number, member
  ID) is offered for saving and then kept by origin + form signature + field key, suggested on that site only; at most
  five values per field; managed per site and globally.
- **Passwords and cards** (`AcLogin`, `AcCard`): saved on this phone. A password, card number and code are written
  sealed (`AcSealedBox`: AES-256-GCM, fresh IV, record-bound AAD, Android Keystore key `cloud_browser_ac_v1`) inside an
  EncryptedSharedPreferences file. Revealing or filling asks for the fingerprint, face or screen lock (`AcUnlock`,
  `BiometricPrompt`; one success opens a 60 s window; a phone with no lock refuses). Matching is by registrable domain.

## Stores (declared in the fleet manifest)

| store | kind | class | holds |
|---|---|---|---|
| `browser_ac_profiles` | encrypted | secret | profiles, per-site memory, never-save lists, last profile per site |
| `browser_ac_secrets` | encrypted | device | sealed logins and cards (the Keystore key does not leave the phone) |

Both carry a `schema` number; `AcStore.migrate()` walks it forward and never deletes. Unreadable JSON is copied to a
`bak_` key before anything is written over it. A secret the key cannot open is skipped and counted, never dropped. The
old `browser_autofill` profile (identity + addresses) is imported ONCE into a profile labelled "Imported" and stays where
it was; Cloud Account profiles can be imported on request (Autocomplete > Profiles > Import from Cloud Account).

## Cloud Vault

Setting `autofill_use_vault` (default off): the browser fills from its own store first, then asks Cloud Vault, and what
Cloud Vault fills wins.

- Ask: Android's autofill framework (`AutofillManager.requestAutofill`) after the local suggestions; automatic only when
  the local store has nothing for the field.
- Vault wins: a field Cloud Vault's dataset filled is noticed (`:-webkit-autofill`, or a change the browser did not
  make), read back from the page, and merged over the local entry (`AcVaultMerge`): same site and user name (or card
  digits) gets the vault's password / details and the source `vault`; an entry the local store never had is kept as a
  copy marked `vault`; a local entry the vault knows nothing about stays.
- Save: a new password or card is saved locally, and Android's `AutofillManager.commit()` lets Cloud Vault's own save flow
  open for the user to confirm.
- Source: every suggestion shows `local` or `vault`; the Sync & Vault tab shows the state and the reason when it cannot
  be asked (off, not installed, Android autofill off, not the autofill service).

## Departure from the vault contract (read this)

`autofill-tiers-contract.md` rule 1 says no ContentProvider, broadcast, intent extra or file exposes a secret to another
app, and rule 2 that the browser never fills secret fields. The owner asked for local passwords and cards in the browser
and for the vault to be asked, so:

- The browser now stores and fills passwords and cards ITSELF, only when the user saves them here, behind the unlock.
  The old Tier 2 engine (`autofill_engine.js`, Cloud Account profiles) is unchanged and steps aside once the user keeps
  profiles of their own.
- The vault side stays inside rule 1: there is no secret-read API and none was added. Cloud Vault's value reaches the
  browser only as the autofill dataset the user picked, read back from the filled fields. "Vault wins" therefore applies
  when the user lets the vault fill; the browser cannot pull an entry silently, and cannot push one except through the
  framework's save flow. An API for that (a signature-protected provider in `ac_cloud-vault`) needs the owner's decision
  to change rule 1 and a vault patch.
- ID documents are the vault's Identity items by contract (rule 8). Here they are profile data in the browser's encrypted
  store, never sent to Cloud Account or Cloud Vault.

## Known limits

- The page bridge (`CloudAc`) is a WebView JavaScript interface, which every frame of the page can call; like the Tier 2
  bridge it cannot tell the main frame from a third-party iframe. What a hostile frame can do is bounded: a save prompt
  (nothing is saved without the user's tap, and the prompt names the real site) and a read-back of fields of the main page.
- Web notifications have no WebView API, so there is no per-site notification permission to offer; camera, microphone and
  location per site are in page dropdown > This site > Permissions.
- The fill suggestions are a bar above the keyboard, not a dropdown under the focused field.
- Passwords are matched by registrable domain with a short built-in list of two-label public suffixes; Cloud Vault holds the
  real public suffix list.

## Never log secrets

No autocomplete source calls `Log`, `println` or prints a model: every class holding a secret or personal data prints a
redaction from `toString()` (`AcNoSecretLogTest`, `test-browser-autocomplete.sh`). The page engine reports signals on
focus and values only once, on submit; it logs and stores nothing in the page.

## Tests

JVM: `AcProfileTest`, `AcFieldClassifierTest`, `AcSiteMemoryTest`, `AcVaultMergeTest`, `AcCryptoTest`, `AcStoreTest`,
`AcProposalsTest`, `AcNoSecretLogTest`. Compose (Robolectric): `BrowserPageDropdownUiTest`, `BrowserConfigsTabsUiTest`,
`AcProfileEditorUiTest`. Shell: `test-browser-autocomplete.sh` (engine under node, wiring, mutations),
`test-browser-page-menu.sh`, `test-browser-configs-layout.sh`.
