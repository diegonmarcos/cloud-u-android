# Autofill: the three tiers and the contract between them

Three apps fill forms on the phone. Each one has its own job, and none of them may do
another's job. This file is the contract. Cloud Vault owns it; the browser and keyboard
tiers follow it.

| Tier | App | Fills | Source of truth |
|---|---|---|---|
| 1. Vault | Cloud Vault (`ac_cloud-vault`, `com.diegonmarcos.cloudvault`) | passwords, usernames and emails of saved logins, one-time codes (TOTP), cards, passkeys | the Vaultwarden server (`vault.diegonmarcos.com`) |
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

Code: `app/src/main/kotlin/com/x8bit/bitwarden/data/autofill/` (upstream Bitwarden service)
and its `cloud/` package (Cloud Vault's rules: `CloudFieldClassifier`, `AutofillUriPolicy`,
`AutofillUriResolverImpl`, `ProviderChecklist`).
