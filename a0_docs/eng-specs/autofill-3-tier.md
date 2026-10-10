# Autofill, three tiers — the contract

Status: Tiers 2 and 3 and the non-secret Source of Truth shipped (this document). Tier 1 (Cloud Vault's
AutofillService / credential provider) is specified and built separately — its contract is
[`ac_cloud-vault/docs/autofill-tiers-contract.md`](../../ac_cloud-vault/docs/autofill-tiers-contract.md); §8 below
is the boundary between the two documents.

Three things fill fields on this phone. They must never fight, never fill the same field twice, and
never put a secret where it does not belong. This file is the one place that says who does what.

| Tier | Who | What it fills | How |
|---|---|---|---|
| 1 | **Cloud Vault** (`ac_cloud-vault`) | passwords, passkeys, one-time codes, cards, **identity documents** | Android Autofill framework (AutofillService / credential provider): dropdown in apps, inline chips in the keyboard |
| 2 | **Cloud Browser** (`libs:browser`) | names, emails, phones, postal addresses, snippets — **non-secret** | an in-page DOM engine, on the user's tap of a chip |
| 3 | **Cloud Keyboard** (`libs:keyboard`) | the same non-secret values and snippets, in any app | its own candidate row (row 2); Vault's inline chips get a row of their own (row 1) |

## 1. Two Sources of Truth

| SOT | Holds | Owner | Read by |
|---|---|---|---|
| **Secrets** | passwords, passkeys, TOTP/OTP, cards, **IDs** (national ID, passport, residence permit: ES DNI/NIE, DE Personalausweis/Reisepass, BR RG/CPF…) as Bitwarden **Identity** items | Vaultwarden backend, via Cloud Vault | Cloud Vault's AutofillService / credential provider **only** |
| **Non-secret + config** | profiles, their addresses and contacts, per-site mapping rules, snippets | **Cloud Account** (`ac_cloud-account`) | Cloud Browser (read + user-confirmed insert), Cloud Keyboard (read only) |

Tiers 2 and 3 never see, store or fill anything from the secrets SOT. Cloud Vault does not need the
non-secret SOT. **No real personal value is ever in code, fixtures, commits or logs** (public repo): every
test uses obvious fakes (`example.invalid`, `00000`, `Testy Fakeson`).

## 2. The data model (non-secret SOT)

**A) Profiles** — named, several ("<Name> - Spain-A", "<Name> - Germany-A", "<Name> - Brazil"), because
each country's forms want the person differently. Per profile: title, given name, middle name(s), family
name 1 + family name 2 (the Spanish two-surname form; one family name elsewhere), display/full name,
birth date, nationality, organisation, job title — and:

* **Addresses**, several, each **typed**: `home` (residence, ES *Domicilio*), `postal` (mailing, DE
  *Postanschrift*), `co` (c/o address), `post_office` (post-office branch / Packstation, DE *Postfiliale*),
  `other`. Fields cover ES/DE/BR: c/o line, street, house number, floor/door (ES *P04 0001*), complement
  (BR *apto/complemento*), neighbourhood (BR *bairro*), postal code (CEP/PLZ/CP), city, state/province,
  country (ISO code). One default address per profile.
* **Contacts**, several: phones (with country and type mobile/landline/work), emails, links/websites. One
  default per kind and profile; a mobile phone wins over a landline when none is default.

**B) IDs are NOT here.** National ID, passport, residence permit (type, issuing country, number,
support/serial/CAN, issue date, valid until) are sensitive identity data: they belong in **Cloud Vault as
Bitwarden Identity items**. The browser engine never fills an ID field; the keyboard never shows an ID on
its row and suppresses itself on one; Cloud Account's Import detects IDs in a paste and hands each to
Cloud Vault's add-identity entry point without storing it. **Filling ID fields is Vault's job through the
Autofill framework** (shipped): `dni`, `nie`, `nif`, `personalausweis`/`ausweisnummer`,
`passport`/`pasaporte`/`passaporte`/`reisepass`/`passnummer`, `cpf`, `rg`/`identidade`, `aufenthaltstitel`,
"ID number", "document number", "número de soporte"/CAN, document expiry ("valid until", "fecha de
caducidad", "gültig bis", "validade") and issuing country, mapped to the Identity item's `passportNumber`,
`licenseNumber`, `ssn` and custom fields (`DNI`, `Support number`, `Valid until`, ...); the table is in the
vault contract (rule 8).

**The name / address rule (no collision).** Plain name and address filling belongs to Tiers 2 and 3. Cloud
Vault writes a screen's name and address fields **only** as part of an Identity item the user picked on an
ID-document field of that same screen; those fields never show a Vault suggestion of their own, and a
name / address form with no ID-document field gets nothing from Vault. A field the framework filled that
way is the framework's (rule 3 of the vault contract): the browser never touches it again.

**C) Snippets** — labelled, multi-line, emoji-safe text blocks ("About me", a bio, standard messages).
Offered on keyboard row 2 only when the field is multi-line (`TYPE_TEXT_FLAG_MULTI_LINE`); in the browser
as an explicit chip on about/bio/message/comment textareas. **Never auto-filled.**

**Rules** — per site: `domain → form selector → field selector → field key | ignore | literal`, plus the
**default profile per site or country**: a rule with key `x-profile` whose value is a profile's name, on a
domain or a TLD (`de` covers every `.de` site). Without such a rule a site prefers the profile with an
address in its TLD's country (`.de` → the profile with a DE address), else the default profile.

**Address formats** (`AddressFormat`): line 1 is `street number` (DE/AT/CH/NL…), `street, number`
(ES/BR/PT/IT…), `number street` (US/GB/FR…); line 2 is floor/door + complement (and the c/o line when the
form has no c/o field of its own); `street-address` stacks c/o, line 1, line 2.

### 2.1 Field keys

The WHATWG autocomplete tokens map 1:1 (`given-name`, `additional-name`, `family-name`, `name`,
`honorific-prefix`, `bday`, `organization`, `organization-title`, `email`, `tel`, `tel-national`, `url`,
`street-address`, `address-line1`, `address-line2`, `address-level1..3`, `postal-code`, `country`,
`country-name`), plus `x-` keys for what ES/DE/BR forms ask separately: `x-family-name-1`,
`x-family-name-2`, `x-street`, `x-house-number`, `x-floor-door`, `x-complement`, `x-co`, `x-nationality`;
`x-snippet` (a free-text box); `x-profile` (the default-profile rule). Any `x-id*` key, `current-password`,
`new-password`, `one-time-code`, `username`, `webauthn` and `cc-*` are **secret**: no tier puts SOT data there.

## 3. The SOT provider

Contract module: `ab_cloud-libs-shared/libs/autofill` (`com.diegonmarcos.superapp.autofill`: `AutofillSot`,
`Fields`, `AddressType`, `ContactKind`, `AutofillProfile`, `AutofillAddress`, `AutofillContact`,
`FillTarget`, `AddressFormat`, `SiteRule`, `Snippet`, `AutofillCandidates`, `AutofillRows`,
`AutofillSotClient`). lib-classes: `contract`. Every reader and the owner compile the same constants.

**Authority** `com.diegonmarcos.cloudaccount.autofill` — served by
`ac_cloud-account/.../autofill/AutofillSotProvider.kt`, stored by `AutofillStore.kt` in the SharedPreferences
file `autofill_sot` (declared in `fleet-config.json`, class `config`: a new phone gets the same data
through the fleet setup contract). Contract version 2 (`call("version")`).

| URI | Columns (all text; `_id`, `updated_at` set by the provider) |
|---|---|
| `…/profiles[/<id>]` | `_id, label, is_default, honorific_prefix, given_name, additional_name, family_name, family_name2, display_name, bday, nationality, organization, organization_title, updated_at` |
| `…/addresses[/<id>]` | `_id, profile_id, type, label, is_default, co_line, street, house_number, floor_door, complement, neighborhood, postal_code, city, state, country, updated_at` |
| `…/contacts[/<id>]` | `_id, profile_id, kind (tel\|email\|url), value, type, country, is_default, updated_at` |
| `…/rules[/<id>]` | `_id, domain, form_selector, field_selector, field_key, literal_value, enabled, updated_at` |
| `…/snippets[/<id>]` | `_id, label, text, updated_at` |

Anything else a writer sends is **dropped**: there is no column a password, code, card or ID could land
in. An address or contact needs an existing profile; deleting a profile deletes its addresses and contacts;
a rule must be valid (`SiteRule.valid`).

**Permissions** (defined by `libs:autofill`'s manifest, so the definition exists whichever app is
installed first; identical duplicate definitions are accepted because the fleet shares one signing key —
`SIGNING.md`):

| Permission | protectionLevel | Requested by | Grants |
|---|---|---|---|
| `com.diegonmarcos.cloud.permission.AUTOFILL_PROFILE_READ` | `signature` | Cloud Browser, Cloud Keyboard | `query`, `call(version)` |
| `com.diegonmarcos.cloud.permission.AUTOFILL_PROFILE_WRITE` | `signature` | Cloud Browser | `insert` (a user-confirmed new address) |

The provider is `exported` with `readPermission`/`writePermission` **and re-checks both in code**
(`call()` is never permission-checked by the framework). `update` and `delete` are **owner-only** (same
uid): consumers are read-mostly and can only *propose* a row. Not `CONSTELLATION_DATA`, which every fleet
app holds: personal data goes only to the apps that ask for it. The SuperApp's IPC roster
(`aa_cloud-superapp/build.json::ui.app_mesh`, id `autofill-sot`) lists the channel.

**Editor** — Cloud Account ▸ Account ▸ **Autofill** (profiles with their typed addresses and contacts: add,
edit in place, default, remove, delete with confirmation), **Sites** (rules and default-profile rules),
**Snippets**, **Import** (paste text or JSON → review → save; IDs found are listed, masked, for Cloud Vault and
never stored: each row's "Add to Cloud Vault" sends that document, with the holder's names from its profile,
to Cloud Vault's add-identity entry point (`com.diegonmarcos.cloudvault.action.ADD_IDENTITY`, behind the
signature permission `com.diegonmarcos.cloud.permission.VAULT_ADD_IDENTITY` that Cloud Account requests and
defines), which unlocks, opens the new-Identity screen prefilled and saves only on the user's tap. A vault
that is missing or older than the entry point gets the previous flow: open Vault, add the Identity item by
hand. `ac_cloud-account/.../autofill/VaultIdentityHandoff.kt`).

## 4. Tier 2 — Cloud Browser DOM autofill

Engine: `libs/browser/src/main/assets/browser/autofill_engine.js`, installed on every page load by
`DomAutofill.inject` (re-scanning on DOM mutations and SPA `pushState`/`replaceState`/`popstate`). Host:
`DomAutofill.kt` (bridge `CloudAutofill`, chip state, fill, save offer), `BrowserAutofillChip.kt`.

Classification of each `input`/`select`/`textarea`, in this order:

1. **Secret → never ours**: `type=password`; `autocomplete` `current-password`, `new-password`,
   `one-time-code`, `username`, `webauthn`, `cc-*`; words meaning password / OTP / verification code / card /
   CVV / IBAN / PIN; **ID documents** (DNI, NIE, NIF, Personalausweis, Ausweis, passport, Reisepass, CPF, RG,
   residence permit, tax id…) in EN/ES/PT/FR/DE; a short numeric "code" field. **No rule can override this.**
2. **Site rule** (only the rules for this host reach the page): `ignore`, a field key, or a literal.
3. **`autocomplete` token** (WHATWG; section-/shipping/billing prefixes skipped).
4. `type=email` / `type=tel`.
5. **Words** from name, id, `<label>`, `aria-label`/`aria-labelledby`, placeholder, title — EN/ES/PT/FR/DE,
   including *primer/segundo apellido*, *Hausnummer*, *piso/puerta*, *c/o*, *bairro*, *complemento*,
   *nacionalidad*. A form with its own house-number box gets the street alone in its street box. A textarea
   that reads about/bio/message/comment is `x-snippet`.

A **login form** (a password / OTP / username field, or a sign-in form with at most two text fields) leaves
its email, phone and name to Vault (the email collision rule). A checkout with card fields is not a login form.

Behaviour:

* **On intent only.** Focusing a classified field reports the form's *keys and field ids* (never a value)
  and the host shows a compact chip at the bottom: **"Fill address: <profile> · <address>"** with ▾ (the
  **profile picker**: every profile × address that has something for the form, the site's default first)
  and ×. A tap fills the whole block. On a snippet textarea: **"Insert snippet ▾"** — always a pick.
* **Fill**: the native `value` setter (so React/Vue/Angular controlled inputs register it), then `input`,
  `change`, `blur` (+`focusout`). `<select>` options match by value or text.
* **Deference**: a field the Android Autofill framework filled (`:autofill` / `:-webkit-autofill`, or flagged
  by `frameworkFilled()` right before every fill) is never DOM-completed again for the page session.
* **Incognito tabs**: the chip names no profile ("Autofill…") and fills only after an explicit pick; nothing
  is learnt or saved.
* **Save new address** (setting `offer_save_address`): on submit of a form with an address block, outside
  incognito, the typed non-secret values go to the host, which offers *"Save this address to <profile> in
  Cloud Account?"* only when no profile already holds that place (new profile when there is none). **Save**
  inserts it (WRITE permission); *Not now* drops it. One offer per page.
* **Android autofill structure**: the WebView keeps `importantForAutofill = YES` (unless the user turns
  "Autofill" off), so Chromium's virtual structure (htmlInfo: input type, name, autocomplete) reaches Cloud
  Vault's service. The engine never sets `autocomplete`, never rewrites attributes, never logs.
* Settings: `profile_fill`, `offer_save_address`, `autofill_enabled`. Legacy browser profiles (imported
  before the SOT) still fill when Cloud Account has none.

### 4.1 Clearing data: three categories, kept apart

| # | Category | What | Cleared by |
|---|---|---|---|
| 1 | **Cookies** | per site and all | Clear browsing data ▸ Cookies; Storage & cookies ▸ Cookies; menu ▸ Clear cookies / Clear site data (this site) |
| 2 | **Site data** | cache, local/session storage, IndexedDB, service workers / Cache Storage, Web SQL, WebView's own form entries; plus the browser's page data (history, previews, tab state, offline copies, downloads) | Clear browsing data and Storage & cookies (a box each, sizes shown); menu ▸ **Clear site data** (this site's origins only, its own boxes) |
| 3 | **Autofill data** | profiles, addresses, site/form rules, snippets | **its own section** "Autofill data (this site / all sites)" in Storage & cookies, with its own confirm; edited in **Cloud Account** |

* Category 3 is **not WebView storage**: it lives in the Cloud Account SOT, cached in browser memory only
  (`DomAutofill`), never in localStorage/IndexedDB/cookies. No box of categories 1–2 includes it, by
  default or otherwise (`BrowserClearCategories`), and a site reload / hard reload never touches it.
* Android's **"Clear storage" of Cloud Browser keeps category 3** (it is Cloud Account's). The one
  browser-local piece is the **legacy imported profile** (`browser_autofill`, from before the SOT): Clear
  storage removes it, and the section says so. "Forget browser-local" drops it and the in-memory copy —
  never a cookie, never WebView storage, never a Cloud Account row (the provider refuses a foreign delete).
* Incognito never writes category 3.

## 5. Tier 3 — Cloud Keyboard

The fleet keyboard is `libs:keyboard` (HeliBoard-derived `LatinIME`, shipped as `ac_cloud-keyboard`);
Tier 3 extends it — no new IME. Pure rules: `latin/autofill/ImeAutofill.kt`; wiring:
`latin/ImeAutofillController.kt`, `LatinIME`, `SuggestionStripView`, `suggestions_strip.xml`.

`onStartInputView(EditorInfo)` → `FieldPolicy.decide(inputType, imeOptions, hintText + fieldName + label +
autofill-like extras)`:

* **Suppression Mode** for: `TYPE_TEXT_VARIATION_PASSWORD`, `…WEB_PASSWORD`, `…VISIBLE_PASSWORD`,
  `TYPE_NUMBER_VARIATION_PASSWORD`; one-time codes (`one-time-code`, `smsOTPCode`, "verification code",
  "código de verificación", "Bestätigungscode"…); card numbers / CVV; **ID documents** (DNI/NIE, Ausweis,
  passport, CPF, RG…); `IME_FLAG_NO_PERSONALIZED_LEARNING`. The keyboard then shows **none of its own chips**
  (row 2 hidden, no clipboard chip), and the field is incognito (`InputAttributes.mAutofillSuppressed` →
  `SettingsValues.mIncognitoModeEnabled`): nothing typed is recorded or learnt.
* **Normal mode**: the field key (email, tel, postal-code, name parts, address line, city, organisation,
  country) from the input type/variation or the hint words; the SOT is read **off the main thread**; while
  the field is empty, row 2 shows **"<profile> ▾"** (the profile picker, when more than one profile has a
  value — a tap switches) followed by that profile's values, default profile first; on a multi-line field
  also the snippets (by label, "✎ About me"). A tap commits through the input connection
  (`commitText(text, 1)`) — never through the suggestion picker, so a personal value is never taught to the
  dictionary. ID numbers are never on the row (there are none in the SOT, and an ID field is suppressed).

### The two suggestion rows (owner's rule)

| Row | Holds | Visible |
|---|---|---|
| **Row 1** (top, key glyph first) | **only** Cloud Vault / Android inline autofill suggestions (`onCreateInlineSuggestionsRequest` / `onInlineSuggestionsResponse`), styled by the keyboard | only while there are some; zero height otherwise |
| **Row 2** | the keyboard's own: SOT candidates + picker, snippets, word suggestions | hidden in Suppression Mode |

The rows never mix and each scrolls on its own; same chip height (`config_suggestions_strip_height`); no
animation. An empty inline response clears row 1.

| inline suggestions | mode | row 1 | row 2 |
|---|---|---|---|
| present | Normal | Vault chips | keyboard candidates / words |
| present | Suppressed | Vault chips | hidden — **only Vault shows** |
| absent | Normal | collapsed | keyboard candidates / words |
| absent | Suppressed | collapsed | hidden — nothing |

## 6. Precedence matrix

| Situation | Browser (Tier 2) | Keyboard (Tier 3) | Vault (Tier 1) |
|---|---|---|---|
| Page load, address/contact form, user focuses a field | chip "Fill address: <profile>", fills the block on tap | row 2: SOT values for that field (a different surface from the chip) | — |
| Focus a login / password field | **nothing** (secret, or login-form identity) | **Suppression Mode** on password/OTP: no own chips; row 1 shows Vault's inline chips | dropdown / inline chips, fills |
| Focus an ID document field (DNI, passport, CPF…) | **nothing** | **Suppression Mode** | offers Identity items (after unlock, no domain match, never auto-selected); the picked one fills the document fields and that screen's name/address fields |
| Focus a name / address field of a screen that also has an ID field | chip as usual | row 2 as usual | **no suggestion** on that field; filled only when an Identity item is picked on the ID field |
| Focus an arbitrary unclassified input | nothing | row 2: word suggestions (+ snippets on multi-line) | — |
| About / bio / message textarea | "Insert snippet ▾", only on a pick | row 2: snippets (multi-line) | — |
| Email field, contact form | may pre-fill on tap | row 2 email candidates | may fill when the user picks a dataset; the browser then defers that field |
| Email/username field, login form | **nothing** — left to Vault | row 2 email candidates (row 1 = Vault) | fills when chosen |
| Field the framework filled (Vault dataset picked) | never touched again this page session | — | owns it |
| Incognito tab | no named prompt; fills only on explicit pick; never saves | unaffected (the page's `IME_FLAG_NO_PERSONALIZED_LEARNING`, if set, suppresses) | unaffected |

## 7. What each tier must never do

* **Cloud Account (SOT)**: hold a password, passkey, OTP, card number, security code or **ID document** (no
  column for one; Import routes IDs to Vault); answer a caller without READ; let a consumer update or
  delete; log a value.
* **Cloud Browser**: classify or fill `type=password`, `current-password`/`new-password`/`one-time-code`/
  `username`/`cc-*`, an ID field, or any field that reads as one — whatever a rule says; fill a login form's
  identity field; fill without the user's tap; insert a snippet without a pick; overwrite a framework-filled
  field; set `autocomplete=off` or hide fields from the Android Autofill framework; send a value to the host
  except a submitted address for a confirmed save; keep autofill data in WebView storage; clear autofill data
  from a cookie/site-data clear (or the reverse); save anything from incognito; log a value.
* **Cloud Keyboard**: show its own chips, record or learn anything in Suppression Mode; show an ID number;
  mix its candidates into row 1 or Vault's chips into row 2; teach the dictionary a SOT value; write the SOT;
  log a candidate.
* **Cloud Vault**: see §8.

## 8. Tier 1: Cloud Vault

Owned by the Cloud Vault work (`ac_cloud-vault`, not touched here); its contract is
[`ac_cloud-vault/docs/autofill-tiers-contract.md`](../../ac_cloud-vault/docs/autofill-tiers-contract.md) (rules 1–7:
secrets only in the vault, the browser never fills secret fields, the framework fill wins, the keyboard makes
room, locked means no data, same-site only, only browsers name the site; rule 8: ID documents are the vault's,
names and addresses are not). What Tiers 2/3 rely on from it: the
WebView's autofill structure is honoured (the browser keeps `importantForAutofill = YES`), inline
suggestions are offered to the IME (the keyboard returns an `InlineSuggestionsRequest` with three
presentation specs at strip height), Vault never fills non-secret profile data from this SOT, Vault fills ID
document fields from Identity items (§2 B), and names / addresses only inside a picked Identity item (§2 B,
the name / address rule).

## 9. Tests

| What | Where | Runs in CI |
|---|---|---|
| SOT provider: CRUD of profiles/addresses/contacts/rules/snippets, cascade delete, one default per scope, permission enforcement (none / read / write), owner-only update+delete, dropped columns, rule validation (incl. TLD default-profile rule), signature-level manifests; Import of fake ES/DE/BR text and JSON with IDs kept apart | `ac_cloud-account/app/src/test/.../AutofillSotProviderTest.kt` (Robolectric) | ship-cloud-account "JVM unit tests" |
| DOM engine: autocomplete tokens, EN/ES/PT/FR/DE labels, ES/DE/BR forms (two surnames, Straße + Hausnummer split, c/o, piso/puerta, bairro, complemento), password/OTP/card/IBAN/username/**ID** exclusion, rule override (never of secrets), login vs contact vs checkout email, React-controlled input + native setter, event order, `<select>`, hidden fields, framework deference, snippet fields (own chip, never in a block), save offer (never incognito, never a password), MutationObserver, SPA | `ac_cloud-browser/test/autofill_js_harness.js` via `test-browser-dom-autofill.sh` (node, fake DOM, + mutations) | ship-cloud-browser testers |
| Browser host: chip words, per-country values, picker order (site rule → TLD → default), secret/ID keys refused, literals, snippets, save proposal + dedupe, legacy profiles, per-host config; clear categories (no cookie/site clear reaches autofill data, per-site origins only) | `ac_cloud-browser/app/src/test/.../DomAutofillTest.kt` | ship-cloud-browser unit |
| Clear-data wiring: cookie/storage/site clears never touch autofill data and vice versa, own section + confirm, per-site clear scoped, nothing in WebView storage, incognito never writes | `ac_cloud-browser/test/test-browser-autofill-data-separate.sh` (+ mutations) | ship-cloud-browser testers |
| Vault: ID fields across EN/ES/DE/PT and their negatives ("User ID", card expiry, PIN), Identity → field mapping incl. custom fields, which screens become an Identity partition, nothing before unlock, only document fields show the suggestion; the add-identity entry point (signature permission in the manifest, caller re-check, extras prefilled and removed, in-memory hand-over, the new-Identity screen) | `ac_cloud-vault/app/src/test/.../data/autofill/cloud/Identity*Test.kt`, `CloudVaultIdentityFillTest.kt`, `AddIdentityEntryPointTest.kt` | ship-cloud-vault "Test → autofill tier" |
| Account → Vault hand-off: the ADD_IDENTITY intent and its extras (text and JSON imports, holder names), nothing sent without a vault that answers, the permission requested and defined | `ac_cloud-account/app/src/test/.../VaultIdentityHandoffTest.kt` (Robolectric) | ship-cloud-account "JVM unit tests" |
| Keyboard: EditorInfo → mode (password/OTP/card/ID/no-learning), field keys, row arbitration matrix (inline present/absent × normal/suppressed), candidates + profile picker, snippets only on multi-line | `libs/keyboard/src/test/.../autofill/ImeAutofillTest.kt` | ship-cloud-keyboard `./build.sh unit` |
| Keyboard wiring: row 1 layout, inline never on row 2, suppression → row 2 hidden + incognito + no clipboard chip, commitText, read-only permission | `ac_cloud-keyboard/test/test-keyboard-autofill-rows.sh` (+ mutations) | ship-cloud-keyboard testers |

## 10. On a device

1. Install Cloud Account, Cloud Browser and Cloud Keyboard from the fleet (same signing key, so both
   permissions are granted at install). Settings ▸ System ▸ Keyboard: enable **Cloud Keyboard** and make it
   the default; Settings ▸ Passwords & autofill: **Cloud Vault** as the autofill service.
2. Cloud Account ▸ Account ▸ Import: paste your data (Format help shows the shape), review, Save; for each
   listed ID document tap "Add to Cloud Vault" → Vault unlocks, shows the new Identity item prefilled →
   Save. Or add profiles by hand under Autofill.
3. Sites: add a default-profile rule `de` → your Germany profile.
4. A `.de` shop with an address form and a login form: focus the address form's PLZ → the chip "Fill address:
   <Germany profile> · Postal ▾" → tap → street, Hausnummer, PLZ, Ort fill, and a React/Vue checkout
   validates them. Focus the login form's email → no browser chip; the keyboard shows Vault's chips on row 1
   (key glyph); on the password field row 2 disappears so only row 1 shows.
5. Pick a Vault dataset on a contact form's email → the browser never overwrites that field afterwards.
6. Submit a new address → "Save this address to … in Cloud Account?" → Save → it appears under Autofill.
7. Private tab: the chip reads "Autofill…" and fills only from the list; submitting offers nothing.
8. Configs ▸ Data & storage ▸ Storage & cookies ▸ Clear everything → profiles and rules are still offered;
   Autofill data ▸ Forget browser-local → cookies still there (still signed in). Menu ▸ Clear site data →
   only that site signs out.
9. Any app's email field: row 2 offers "<profile> ▾" + its emails while the field is empty; a tap inserts.
   A multi-line message box offers the snippets. A DNI/passport field shows no keyboard chips.
