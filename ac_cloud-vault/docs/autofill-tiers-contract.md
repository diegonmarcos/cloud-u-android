# Autofill: the three tiers and the contract between them

Three apps fill forms on the phone. Each one has its own job, and none of them may do
another's job. This file is the contract. Cloud Vault owns it; the browser and keyboard
tiers follow it.

| Tier | App | Fills | Source of truth |
|---|---|---|---|
| 1. Vault | Cloud Vault (`ac_cloud-vault`, `com.diegonmarcos.cloudvault`) | passwords, usernames and emails of saved logins, one-time codes (TOTP), cards, passkeys, ID documents (Identity items) | the Vaultwarden server (`vault.diegonmarcos.com`) |
| 2. Browser | Cloud Browser (`ac_cloud-browser`, `com.diegonmarcos.cloudbrowser`) | non-secret profile fields inside web pages (name, address, phone, the account's email) | Cloud Account's non-secret profile |
| 3. Keyboard | the fleet keyboard IME | typed text and its own suggestions | Cloud Account's non-secret profile |

## Rules

1. **Secrets live only in the vault.** A password, TOTP seed or code, card number or
   security code, or passkey leaves Cloud Vault only through Android's Autofill framework
   (a dataset the user picked), Android's Credential Manager (a passkey or password the user
   approved), or a copy the user started. No ContentProvider, broadcast, intent extra, file or
   shared preference exposes a secret to another app, fleet apps included.
2. **The browser never fills secret fields.** Cloud Browser does not DOM-fill password, OTP or
   card fields (`type=password`, `autocomplete=current-password|new-password|one-time-code|cc-*`).
   It does not turn off Android autofill for its WebView (`importantForAutofill` stays on), so
   the framework can ask Cloud Vault about those fields.
3. **The framework fill wins.** When the user picks a Cloud Vault suggestion, the framework
   writes every field of that dataset, including a username or email field the browser had
   already pre-filled. The browser stops DOM-filling a node once the framework has autofilled
   it, and never writes over a value the framework set.
4. **The keyboard makes room.** On a password or OTP field, the keyboard hides its own
   suggestion strip so Cloud Vault's inline suggestion chips (Android 11+ inline autofill)
   are not covered. The keyboard sends an `InlineSuggestionsRequest`; Cloud Vault honours its
   presentation specs and maximum count. The keyboard never stores or suggests a secret.
5. **Locked means no data.** When the vault is locked, Cloud Vault offers one entry,
   "Vault is locked", which opens the unlock screen. No secret is put into any suggestion
   before the vault is unlocked. The vault's own lock timeout applies.
6. **Same-site only.** A saved login is offered only on the site it belongs to: same
   registrable domain (eTLD+1, by the public suffix list) unless the login says host, exact,
   starts-with or regular expression. A lookalike domain never matches.
7. **Only browsers name the site.** A web domain reported by a browser (Bitwarden's browser
   list, Cloud Browser, or a fleet app signed with the vault's own key) is used as reported.
   Any other app that reports a web domain gets that site's logins only if the site's
   Digital Asset Links file names the app; otherwise it is matched by its own package
   (`androidapp://<package>`) and the logins saved for that app.
8. **ID documents are the vault's; names and addresses are not.** Cloud Vault offers its
   Identity items only when the focused field is an ID-document field (national ID, passport,
   driving licence, support number, a document's expiry or issuing country; see below), and
   only after unlock, exactly like logins. Identity items are not matched to the site (an ID
   document belongs to a person, not a domain): the user picks one, the vault never
   auto-selects. When the user picks one, the dataset also writes that screen's name and
   address fields, and only those of that screen; those fields never show a vault suggestion of
   their own, and a name or address form without an ID-document field gets nothing from the
   vault, so plain name / address filling stays with the browser and keyboard tiers. A
   document field directly above a password field is that login's username (Spanish sites log
   in with "DNI / NIE" + password) and gets logins, not Identity items.

## What Cloud Vault does (tier 1)

- Is the Android autofill service and, on Android 14+, the Credential Manager provider for
  passkeys and passwords. Setup and Settings > Autofill show one green / red row per
  requirement and open Android's own screen to fix it; nothing writes a secure setting.
- Reads each page's structure: Android autofill hints (incl. the SMS and authenticator-app
  OTP hints), input types (all password variations, incl. number passwords), the HTML tag
  and `type` / `name` / `id` / `autocomplete` / label of WebView and browser fields, and the
  view id or placeholder as a last resort. Search boxes and other fields that are not ours
  are listed as ignored so the framework stops asking about them.
- Offers matching logins as inline chips (when the keyboard asks for them) and in the
  drop-down, plus an entry that opens the vault to search. Fills username, email, password
  and, on an OTP field, the login's current TOTP code. After a login with a TOTP seed is
  filled, the current code is copied and cleared from the clipboard within a minute.
- Offers to save a new or changed login (username + password; a username-only first screen
  waits for the password screen with `FLAG_DELAY_SAVE`). The save opens the vault's own
  add-item screen; nothing is written until the user confirms.

- Fills ID-document fields from Bitwarden Identity items (rule 8, mapping below), and adds
  Identity items another fleet app hands it through the add-identity entry point (below).

Code: `app/src/main/kotlin/com/x8bit/bitwarden/data/autofill/` (upstream Bitwarden service)
and its `cloud/` package (Cloud Vault's rules: `CloudFieldClassifier`, `IdentityFieldClassifier`,
`IdentityFieldMapping`, `AddIdentityRequest`, `AutofillUriPolicy`, `AutofillUriResolverImpl`,
`ProviderChecklist`).

## ID-document fields (rule 8)

`IdentityFieldClassifier` reads the same signals as the login rules: Android / androidx
`autofillHints`, the HTML `autocomplete` token, and the words of the id, name, label,
placeholder, `aria-label` and title (accents, `ß`, case and camel case folded). Never an ID
field: a password / OTP / username / email / phone / card hint or `autocomplete` token, a
password or email input type, `type=password|email|tel`, or password / PIN / one-time-code /
card wording ("PIN del DNIe", "Fecha de caducidad de la tarjeta"). A bare "ID" is never one,
and the generic wordings ("ID number", "document number") never match next to user, account,
customer, order, vehicle, ... ("User ID", "Apple ID", "Customer ID number").

| Field (`IdentityField`) | Wording (EN / ES / DE / PT) | Filled from the Identity item |
|---|---|---|
| `DNI` | dni, documento nacional de identidad | custom `DNI`, else `NIE`, else `NIF` |
| `NIE` | nie | custom `NIE` |
| `NIF` | nif | custom `NIF`, else `DNI`, else `NIE` |
| `PERSONALAUSWEIS` | personalausweis, ausweisnummer, ausweis | custom `Personalausweis` / `Ausweisnummer` / `ID card number` |
| `CPF` | cpf | custom `CPF` |
| `RG` | rg, registro geral, identidade, carteira de identidade | custom `RG` / `Identidade` |
| `RESIDENCE_PERMIT` | residence permit, aufenthaltstitel, tarjeta / permiso de residencia | custom `Residence permit` / `TIE` / `Aufenthaltstitel` |
| `NATIONAL_ID` | national id, id number, id card (number), identity card / number, número de identidad, cédula, carte d'identité | custom `National ID` / `ID number`, else the item's DNI / NIE / Personalausweis / CPF / RG / residence permit |
| `DOCUMENT_NUMBER` | document number, número de documento, documento, Dokumentnummer | custom `Document number` / `ID number`, else as `NATIONAL_ID`, else passport, else licence |
| `PASSPORT_NUMBER` | passport (number), pasaporte, passaporte, reisepass, passnummer, Pass-Nr. | **`passportNumber`**, else custom `Passport` |
| `LICENSE_NUMBER` | driver's / driving licence, permiso / carné de conducir, Führerschein, CNH | **`licenseNumber`**, else custom `Driver license` |
| `SSN` | ssn, social security, seguridad social, Sozialversicherungsnummer | **`ssn`**, else custom `SSN` |
| `SUPPORT_NUMBER` | número de soporte, soporte, support number, IDESP, CAN, Zugangsnummer | custom `Support number` / `Número de soporte` / `CAN` |
| `VALID_UNTIL` | valid until, expiry / expiration date, fecha de caducidad, válido hasta, gültig bis, Ablaufdatum, validade — on a field that also names a document | custom `Valid until` (or `Expiry date`, `Fecha de caducidad`, `Gültig bis`, `Validade`...) |
| `ISSUING_COUNTRY` | issuing country, country of issue, país de expedición / emisor, Ausstellungsland, país emissor | custom `Issuing country` (never the home address's country) |

Fill-only fields (filled only when an Identity item is picked on a document field of the same
screen; never show a suggestion): an expiry wording that names no document (`VALID_UNTIL_ON_PAGE`;
never on a card form), first / middle / last / full name (`firstName`, `middleName`,
`lastName`; "primer / segundo apellido" are left alone), address lines 1-3, city, state, postal
code and country (`address1-3`, `city`, `state`, `postalCode`, `country`), from `autocomplete`
(`given-name`, `family-name`, `address-line1`, `postal-code`, ...), the androidx hints
(`personGivenName`, `postalCode`, ...) or EN / ES / DE / PT wording. Custom fields are matched by
name with case, accents and punctuation ignored; only text and hidden custom fields count. An
item with no value for any of the screen's document fields is not offered.

## The add-identity entry point

`AddIdentityActivity` (action `com.diegonmarcos.cloudvault.action.ADD_IDENTITY`) is exported
only behind `com.diegonmarcos.cloud.permission.VAULT_ADD_IDENTITY`, a `signature` permission
(Cloud Vault and Cloud Account define it identically; Android grants it to constellation-signed
APKs only). On Android 14+ a caller that shares its identity is checked again in code. String
extras, all optional, prefixed `com.diegonmarcos.cloudvault.extra.`: `ID_TYPE` ("DNI",
"Passport", "Personalausweis", ...), `ID_NUMBER`, `ID_SUPPORT`, `ID_ISSUING_COUNTRY`,
`ID_VALID_UNTIL`, `ID_ISSUED`, `FIRST_NAME`, `MIDDLE_NAME`, `LAST_NAME` (and an int `VERSION`,
1). The activity reads them once, removes every extra from its intent, hands the document to
the main screen in memory (never in another intent) and finishes; the main screen unlocks
first when the vault is locked, then opens the new-Identity screen prefilled. Nothing is saved
until the user taps Save, and nothing is logged. Where the screen puts each value is the fill
mapping above read backwards: the number in `passportNumber` / `licenseNumber` / `ssn` for a
passport / licence / SSN, otherwise in a hidden custom field named after the document (`DNI`,
`NIE`, `NIF`, `TIE`, `Personalausweis`, `Residence permit`, `CPF`, `RG`, else `ID number`);
`Support number` (hidden), `Document type`, `Issuing country`, `Valid until`, `Issued` (text);
the names in `firstName` / `middleName` / `lastName`. Cloud Account's Import calls it for each
ID document it finds (`ac_cloud-account/.../autofill/VaultIdentityHandoff.kt`).
