# Security

This document describes Sterna's security posture, threat model, and how to report
vulnerabilities. It is aimed at security researchers and contributors.

Sterna is an email client: it renders untrusted content (email is attacker-authored
by definition) and talks to remote mail servers over the network. The two highest-risk
surfaces are therefore **message rendering** and **transport/credentials**, and most of
the hardening below concentrates there.

## Reporting a vulnerability

Please report security issues **privately** rather than opening a public issue:

- Open a confidential/security advisory on the project repository, or
- email the maintainer listed in the repository metadata.

Include a description, affected version (see `versionName` in `app/build.gradle.kts`),
and a proof-of-concept if you have one (e.g. a crafted `.eml`, a server response, or an
`adb`/intent invocation). I aim to acknowledge reports promptly and will credit
reporters who wish to be named once a fix ships.

Please do **not** test against servers or accounts you do not own. A local Stalwart
instance is the easiest safe target.

## Threat model

In scope:

- **Malicious email content**: HTML/CSS, MIME structure, headers, attachments, inline
  images. Assume the sender is hostile and the message is crafted to exploit the client.
- **Malicious or compromised mail server / network attacker**: a hostile server, or an
  active man-in-the-middle on the network path, feeding crafted protocol responses or
  attempting to downgrade/intercept the connection.
- **Local attacker with brief physical access**: recents/screenshots, device backups,
  and another app on the device attempting IPC against Sterna's components.

Out of scope:

- The security of the mail server you choose (Sterna cannot make a hostile provider
  private, see [PRIVACY.md](PRIVACY.md)).
- A fully compromised device / OS, root malware, or hardware attacks.
- Physical attacks with unlimited time against a powered-off device beyond what the
  Android KeyStore provides.

## Hardening measures

### Transport (JMAP / IMAP / SMTP)

- **TLS hostname verification is enforced.** The hand-rolled IMAP/SMTP clients enable
  RFC 2818 endpoint identification (`endpointIdentificationAlgorithm = "HTTPS"`) on every
  `SSLSocket`, for implicit TLS and after every STARTTLS upgrade, so a certificate that
  chains to a valid CA but does not match the server hostname is rejected. Credentials are
  only ever sent after the TLS handshake completes.
- **Certificate authorities installed by the user count as valid.** Since Android 7 an app
  trusts only the system CA store by default, which leaves a self-hosted server with its own
  authority unreachable. The app's network security configuration
  (`app/src/main/res/xml/network_security_config.xml`) adds the user store next to the system
  one, so a certificate chaining to an authority you installed yourself in Android's settings
  is accepted (Codeberg #93). Nothing else moves: the chain and the hostname are still
  verified on every handshake, and there is still no "trust this certificate anyway" prompt
  anywhere in Sterna. The other side of that choice, plainly: a CA pushed by an employer on a
  managed profile, or one slipped onto the device by someone else, now also validates for
  Sterna, and whoever controls it can intercept the connection. K-9 Mail and FairEmail decide
  the same way; banking apps decide the opposite way. For a client whose users largely run
  their own servers, trusting the store the user controls is the coherent trade, and the
  decision stays where Android already manages it, with its own permanent warning and a place
  to review and remove the certificate. The configuration is app-wide rather than mail-only,
  so such an authority is equally trusted for any remote content you choose to load inside a
  message (remote content stays blocked until you ask for it).
- **Redirects, and where credentials go.** The JMAP HTTP client follows redirects, because
  `/.well-known/jmap` discovery relies on them, and the rule it applies to credentials is
  OkHttp's own: the `Authorization` header survives a redirect only within the same origin
  (scheme, host and port). When a redirect crosses origins the header is stripped, so the
  request that reaches the target arrives anonymous. Sterna therefore sends it once more,
  authenticated, to the origin it was redirected to. That replay is triggered by the origin
  having changed and by nothing else: not by the status code, and not by what came back. It
  has to be, because a JMAP server can legitimately answer the anonymous request with a valid
  but empty session rather than a 401, and a mailbox that genuinely has no account answers the
  same way. It happens once per call, on discovery and on every later reconnection, since the
  session is re-fetched on sync and on push reconnect, not only when you add the account. That
  is a deliberate trade, and it is worth stating plainly: whatever host is named in a
  `Location` header receives your credentials, over HTTPS. Naming a different host for the API
  is how some real deployments work, Fastmail among them. Note also which parties get to name
  it: discovery tries several hosts derived from your address (the domain itself first, then
  `mail.`, `jmap.` and `api.` under it), and each is offered the credentials in turn, so
  whoever answers HTTPS on those names is in this position, not only your mail server.
- **No scheme change on a redirect, in either direction.** A redirect that changes the scheme
  is never followed (`followSslRedirects(false)`): the response is surfaced as a failure
  instead, so no request is made to the target at all. Note what that setting does and does
  not do. It is not what keeps the header off the wire, since OkHttp drops `Authorization` on
  any origin change, a scheme change included; it is what keeps the request from being made
  in the first place. A third layer sits underneath both: Android refuses cleartext HTTP for
  the app (`targetSdk` 36, with no exemption added in the network security config). That third
  layer covers the HTTP stack, which is what JMAP and OAuth use; it says nothing about the
  IMAP and SMTP clients, which open their own sockets and are governed by the connection
  security you pick for the account (see below). Credentials cannot reach a cleartext endpoint
  through a JMAP redirect.
- **The session document carries the same trust.** The `apiUrl`, `downloadUrl`, `uploadUrl`
  and `eventSourceUrl` a server advertises are called with your credentials, whichever host
  they name. An `http://` URL advertised over a session that was itself fetched over HTTPS is
  rewritten to `https://` rather than followed in the clear.
- **The OAuth calls that carry a secret follow no redirect at all.** The four POSTs of the
  OAuth flows (device authorization, device-token polling, the authorization-code exchange,
  and every token refresh) use a client of their own, not the JMAP one, built with
  `followRedirects(false)` and `followSslRedirects(false)`. Their URLs are already resolved
  when they are called (they come from the metadata just fetched, or are hard-coded for
  Microsoft), so they have no discovery hop left to make and a 3xx there could only move a
  secret to another host: OkHttp replays a POST body verbatim on a 307/308, and on the refresh
  path that body is the long-lived refresh token, sent at every sync; a 301/302/303 becomes a
  bodyless GET whose answer the caller would then read as a token grant, which would let the
  redirect target choose the access token the app uses. Such a response is surfaced as a
  failure instead, and the target is never contacted.
- **Metadata discovery is the deliberate exception, and what it may name is bounded.**
  `discoverMetadata` keeps the shared JMAP client and does follow 3xx: it is a GET that
  carries no secret, and real servers behind a proxy or a vhost answer a redirect there
  (Codeberg #54, #137). That lookup is what names the token endpoint, so whoever answers it,
  redirects included, would otherwise choose the host the token POSTs then go to over HTTPS.
  Refusing to follow redirects protects the hops that carry a secret; it does not constrain
  the choice of who receives one, so that choice is constrained where it is made instead: an
  endpoint the document names is kept only over HTTPS, and only on the host that was asked,
  on the domain of the address being signed in, or on a subdomain of either. The comparison
  is the one autoconfiguration uses below, leading dot included. A refused endpoint is
  blanked, which leaves no flow to start, and the sign-in says the server hands sign-in to
  another domain rather than claiming it offers no OAuth. The test runs at discovery, so it
  bounds every account added from then on; an account already set up keeps the token endpoint
  it was given, since re-judging one would lock a working account out with no way back in.
- **Autoconfiguration follows redirects by hand, and only within your own domain.** The
  published-config lookup (`autoconfig.<domain>`, then the `.well-known` path on the domain
  itself) also uses a client of its own, with `followRedirects(false)`: OkHttp's built-in
  following pins the scheme but not the host, and a single 302 would be enough to carry the
  lookup to a third party such as Mozilla's ISPDB, which the privacy policy says the app never
  contacts. 3xx answers are walked in code instead, at most five hops per URL, and a hop is
  taken only if it either stays on the very origin already reached (a path-only redirect,
  which opens no new connection) or passes an explicit test: HTTPS, and a host that is the
  domain itself or a subdomain of it, matched case-insensitively with the leading dot in
  place, so `evil-<domain>` and `<domain>.evil.tld` are refused. A refused redirect ends that
  lookup with nothing, and the refused target is never contacted at all.
- **Cleartext is never a default and never silent.** The sign-in screen offers no cleartext
  option: an account is created over TLS or STARTTLS. The account editor does offer
  `ConnectionSecurity.NONE`, because a bridge on 127.0.0.1 (Proton Bridge and the like) is a
  legitimate cleartext target, but selecting it puts a warning under the selector naming what
  it costs. A K-9 settings import whose file does not state a connection security I recognise
  falls back to an encrypted setting and reports the account as one to check, rather than
  quietly configuring it in the clear.

### Message rendering (WebView)

Everything below the sanitisation policy is *containment* — it stops hostile markup from
doing anything. The sanitiser is the layer that stops the markup from *arriving*, and it
exists because every containment measure here is a setting or a callback in another file:
one `javaScriptEnabled = true`, one renderer that forgets to install the blocking client,
and inline handlers still sitting in the DOM are live again.

#### Sanitisation policy for received markup

Applied in `sanitiseReceivedHtml` (`core/data/.../text/ReceivedHtml.kt`) at the single point
where a message's own markup becomes a fragment a renderer is given (`readerBody`) — so the
reader and the print document inherit it, and so will the next renderer. The tables in that
file are the policy; nothing else in it decides anything they do not say.

**Allowed** — element tags survive:
paragraph and structure (`p div span br hr center blockquote pre h1`–`h6`), inline
formatting including the legacy spellings mail still uses (`b strong i em u s strike del ins
sub sup small big code tt kbd samp var font mark abbr cite q wbr`), lists (`ul ol li dl dt
dd`), tables (`table thead tbody tfoot tr td th caption colgroup col`), `a`, `img`,
`details`/`summary`, and `style` — whose content is copied as CSS, never walked as markup.

**Allowed attributes** are default-deny: a global set (`style class id title dir lang align
valign`) plus a per-element set (`a`: `href name`; `img`: `src alt width height border hspace
vspace`; `font`: `color face size`; the table family's sizing and span attributes;
`blockquote`: `cite type`). Everything else is dropped. Every `on*` handler is refused by a
*rule* rather than a list, so it cannot be outgrown by a new platform attribute.

**Dropped with their entire content** — the element and its text both go, because the text is
not prose: `script noscript`, `iframe frame frameset noframes object embed applet param`,
`form input button select option optgroup textarea label fieldset legend output datalist
keygen`, `svg math`, `link meta base title template portal`, `audio video source track
canvas`, `marquee blink plaintext xmp listing`. Comments, CDATA sections, doctypes and
processing instructions are dropped too — a conditional comment is how markup is smuggled
past a scanner that reads the whole construct as text.

**Unwrapped** — tags removed, words kept — is the default for anything unrecognised,
including `html`/`head`/`body` (which cannot legally appear inside a fragment) and word
processor namespaces like `<o:p>`. Content loss is the failure this pass must not have, so
every ambiguity resolves toward keeping the sender's words.

**URL-valued attributes** keep their value only for an allowed scheme: `href` for `http https
mailto tel sms geo cid`, `src` for `http https cid data`, `cite` for `http https`. The value
is entity-decoded and stripped of control characters *before* the scheme is read, so
`java&#115;cript:` and `java<TAB>script:` are both refused. A `data:` URL is allowed only
when it decodes to an image. `@import` is stripped from stylesheets, since it fetches a
remote sheet and no user affordance would ever want it.

**Deliberately NOT dropped**: a remote `<img src="https://…">`. Stripping it would leave the
"show images" affordance with nothing to load. Whether the fetch happens is the load-time
gate's decision below — sanitisation decides what may be *asked for*. Conversely `srcset`,
`background` and `poster` ARE dropped, because the affordance detects remote references by
reading `src`: a picture arriving past it would be blocked with no way for the user to ask
for it.

**Deferred behind a user action**: remote content (below), opening a link (a real gesture,
plus an optional confirmation dialog), and — for a link whose visible text names one host
while its `href` goes to another — a marker naming the real target, since both halves are
legal markup and it is their *disagreement* that is the attack. Only a link whose whole
visible text reads as a bare host or URL is marked; "click here" over a redirector made no
claim to contradict, and a marker on every tracked newsletter link is one the user learns to
ignore. The real host is read from after the last `@`, so
`https://www.bank.example@evil.example/` is named as `evil.example`.

- **JavaScript is disabled** in the message WebView, there is **no `JavascriptInterface`
  bridge**, and content is loaded with a **null base URL** (opaque origin). File and
  content access are disabled, as are DOM storage, geolocation, and file-URL access.
- **Remote content is blocked by default**, default-deny: only `data:`, `cid:`, and
  `about:` sub-resources load; everything else (including protocol-relative `//host` URLs)
  is refused until the user shows images or allowlists the sender. This prevents tracking
  pixels and read receipts on open.
- A **Content-Security-Policy** meta tag is injected as defense-in-depth, blocking
  scripts, plugins, iframes, and form submissions outright.
- **Link handling is allowlisted.** Only `http`, `https`, `mailto`, `tel`, `sms`, and
  `geo` links are handed to the system; `intent:`, `file:`, `content:`, `javascript:`,
  and `data:` are never forwarded. Auto-navigations (e.g. `<meta refresh>`) without a user
  gesture are ignored, and tracking parameters are stripped from opened links.

### Parsing untrusted input

- IMAP literals are **size-capped** so a hostile `{N}` cannot trigger an out-of-memory
  allocation; oversized literals are drained and discarded.
- MIME parsing is **depth- and part-count-bounded** to prevent stack overflow / quadratic
  blow-up from deeply nested or part-flooded multipart messages.
- Decoded header display values are stripped of **control characters and Unicode bidi
  overrides** to reduce display-name spoofing.
- Outgoing message headers, the SMTP envelope (`MAIL FROM` / `RCPT TO`) and IMAP command
  arguments all reject embedded **CR/LF**, closing SMTP header injection (e.g. a hidden
  `Bcc:`), envelope injection (a hidden recipient) and IMAP command injection. An address
  carrying a line break is also refused before the message is queued, so the two paths that
  skip the composer's own validation (a notification quick reply, whose address comes from a
  `From` header a hostile server can craft, and a `mailto:` link hiding a `%0D%0A`) fail
  visibly instead of reaching an address the sender never saw.

### Credentials & data at rest

- Account secrets (passwords / OAuth refresh tokens) are encrypted with an **AES-256-GCM**
  key held in the **Android KeyStore** (non-exportable); only IV + ciphertext are stored.
  Each blob is bound to its account via GCM **AAD** so it cannot be relocated between
  slots, and the key is deleted on a full account reset.
- **Backups are disabled** (`allowBackup="false"`), with backup/data-extraction rules as a
  backstop that exclude the credential store, the Room cache, and settings from cloud
  backup and device transfer.
- With **app lock** enabled, the window is marked `FLAG_SECURE`, keeping message content
  and the credential-entry screen out of the recents thumbnail and screenshots.
- **The app lock is a visual barrier, not encryption, and the message cache is not
  encrypted.** `BiometricPrompt` is called without a `CryptoObject`, and nothing is
  re-encrypted when the app locks; the Room database (message headers, cached bodies, the
  search index) is stored unencrypted in the app's private directory. So the lock is worth
  what it claims and no more: it keeps someone who picks up your unlocked phone from reading
  your mail. The widget that lists recent mail on the home screen answers to that same reader:
  it shows only what the notification-content setting allows, and with the lock enabled it
  names neither sender nor subject. It does not defend against extraction: root, an ADB or
  recovery path into the app's private directory, and a filesystem image all reach the cache
  without ever meeting the prompt (Android's own backup is disabled, per the bullet above, but
  that is one route out of several). Account secrets are the exception: they stay behind the
  KeyStore-held key described above. I would rather state the real scope than leave a false
  assurance standing.

### OpenPGP (end-to-end encryption)

- OpenPGP is delegated to an **external OpenPGP app** over the standard `openpgp-api`
  bound-service interface, found by querying that interface's service intent: private
  keys and passphrases live in that app and never enter Sterna's process. Reading
  decrypts + verifies; composing signs and/or encrypts as PGP/MIME (RFC 3156), on both
  JMAP and IMAP/SMTP.
- **Public keys travel in a message header** (Autocrypt Level 1 `Autocrypt:`), in both
  directions, and only that. Composing announces the sending account's own public key,
  minimized to that account's identity, on every message an account with OpenPGP enabled
  and a key chosen sends; a send whose `From` is another mailbox announces nothing. What
  travels is the public half CACHED on the account when the key was chosen, so a read that
  failed then (no provider, or one asking to be unlocked) leaves that cache empty and the
  account announces nothing at all; the composer retries on EVERY opening
  (`pgpPublicKeyBackfill`) and writes only on `Success`, so an unavailable provider simply
  postpones it again. Incoming, a key reaches the OpenPGP app's public keyring from the
  paths that already hold a message's full headers, which is not the same set on the two
  protocols. On JMAP: the inbox prefetch (Inbox only, and only the newest ids not already
  cached, capped at `PREFETCH_COUNT`, so a refresh that brought no new mail imports
  nothing), plus the origin read of an open, which `openMessage` reaches only for a
  non-crypto message, since it returns on `cryptoKindOf` first, so opening a signed or
  encrypted message imports NOTHING on JMAP. On IMAP: `openEmailImap`, reached by every path
  that needs the whole source and not only by an open, namely `fetchEmail` (reply, forward,
  notification quick reply), `decryptMessage` and the uncached fallback of `rawHeaders` /
  `rawSource`, so any message whose source had to be fetched imports, crypto included, and
  one nothing touched never does. Not every announced key is taken: the key is filed against
  the server's own timestamp for the message (IMAP `INTERNALDATE`, JMAP `receivedAt`, never
  the sender's `Date:`), and a timestamp that cannot be read, or that stands more than 24 h
  ahead of the device clock, refuses the import outright, in silence, so a device clock more
  than 24 h slow stops taking keys from mail that has just arrived. No `prefer-encrypt`
  attribute is emitted and none is honoured, so nothing here turns encryption on by itself;
  gossip headers and the Autocrypt Setup Message are not implemented. A key obtained this
  way is opportunistic and unconfirmed: it makes encryption possible and proves nothing
  about who is at the other end, and confirming it stays a deliberate act in the OpenPGP
  app.
- **Sterna does not verify who it is talking to.** Any installed app answering that
  service intent can be bound. One package is refused by the vendored api library
  itself, for an incompatible implementation of the interface rather than out of
  suspicion; there is no signature, publisher or certificate check, before or after
  binding. What the user controls is which OpenPGP apps are installed, and, when more
  than one is present, which of them Sterna talks to; with a single one there is
  nothing to pick and it is bound without being asked. The trust placed in that app is
  the user's to place.
- **Decrypted content is never persisted**: plaintext of an encrypted message is held in
  an in-memory cache only, never written to the Room body cache and never added to the
  local search index (which indexes headers only). An encrypted message in the outbox
  stores only its ciphertext entity; the plaintext body is cleared at rest. Attachments
  are the exception: one you open is written to the app's own storage until the cache
  is cleared, and one you save is written to the document the system picker returns —
  outside the app, where neither clearing the cache nor signing the account out reaches
  it. Saving is an explicit gesture, one attachment at a time. Printing a message is the
  other exception: the decrypted body is handed to the system print service. Choosing
  "Save as PDF" there writes that plaintext into a document outside the app, where the
  same two clearances do not reach it either; choosing a printer hands it to the print
  spooler and to the printer instead, and leaves no document to point at. Both happen
  only on that request.
- The message **subject is not encrypted** on the way out (sent in the clear, matching
  common OpenPGP practice). Protected headers (RFC 9788) are **read, and written on an
  encrypting send**: the entity that gets encrypted opens with a `Subject` field holding the
  real subject, above a top-level `Content-Type` marked `protected-headers="v1"`, so the subject
  is covered by the encryption and, when the message is signed too, by the signature. That is
  **integrity, not confidentiality** — the envelope still carries the subject in the clear, and
  nothing that was visible before is hidden now. What it buys is that the subject a relay
  rewrites in transit is no longer the only copy there is. Sterna itself does not compare the
  two, and does not warn when they differ. Only `Subject` is written: the sender, the recipients
  and the date stay those of the envelope, no legacy-display part is emitted, a message sent
  with no subject writes nothing, and a signed-only message carries neither the field nor the
  parameter. On the way in, a
  decrypted PGP/MIME entity whose own header block carries a non-blank `Subject` is shown
  under that subject instead of the envelope's, while an empty or absent one leaves the
  envelope's subject untouched. It is sender-controlled content just like the envelope's
  own subject, and it is read through `MimeParser.decodedHeaderOf`, the app's single header
  sanitising chain: coming from inside the ciphertext makes a subject no more trustworthy
  than one on the envelope, and decryption is not signature verification. The substitution
  is **display-only and contained**: it lives in the reader's in-memory copy and reaches
  neither the Room row, nor the search index, nor a notification, nor the widget, so the
  message list deliberately keeps the cover subject; and a reply, a forward and a read
  receipt all take the cover subject too, since anything else would send the real subject of
  an encrypted message in the clear.

### Android platform surface

- Four components are exported by Sterna's own manifest; the libraries it embeds add more, and
  those are listed at the end of this section. The first is the launcher `MainActivity`, and it reads
  untrusted input from three kinds of entry point. Two of them are compose-screen prefill:
  `mailto:` deep links (VIEW+BROWSABLE and SENDTO filters) plus `ACTION_SEND` /
  `ACTION_SEND_MULTIPLE` in `*/*`, from which it reads `EXTRA_SUBJECT`, `EXTRA_TEXT` and
  `EXTRA_STREAM`. All of it is treated as prefill and nothing more. A `mailto:`
  is parsed with the platform `MailTo` parser inside a `runCatching`, so a malformed or
  hostile URI opens nothing at all; the text extras only fill the subject and body fields; and
  of the shared URIs, **only `content:` ones are accepted**, so no app can hand over a `file:`
  path and have Sterna read its own private storage on the sender's behalf. No filesystem path
  is derived from a shared URI, and the display name is CR/LF-filtered before it can reach a
  MIME header. No intent extra selects an account, grants a permission, or sends anything, an
  address carrying a line break is refused before submission, and a consumed payload is
  stripped from the retained intent so it cannot replay on the next read. The third entry
  point is not prefill: a further VIEW+BROWSABLE filter registers `<applicationId>://oauth`,
  the address a browser returns to at the end of an OAuth authorization-code sign-in, and the
  answer arrives as the intent's data URI (carrying `code`, `state` and `error`) rather than
  as an extra. It is the one exported input that can end with an account being added, so what
  happens to it is decided before any network call, in this order. If no sign-in of this app's
  own is in flight, the redirect is refused and says so on screen: the PKCE verifier that
  alone can redeem a code is held in memory only and never persisted, so a process death
  during the browser trip leaves nothing to redeem. If something is in flight, the `state` in
  the URI is compared against the one this app drew for that request, and a redirect that does
  not match is refused without its `code` or `error` being looked at, so a URI fired by
  another app cannot get an authorization code of its own choosing exchanged against this
  client. A mismatch deliberately leaves the pending request armed, so an outside link cannot
  cancel a sign-in the user really started; a redirect that passes is consumed, and the same
  one delivered twice finds nothing in flight. Push service, the notification receiver and
  the FileProvider are not exported, and neither is `RecentMailWidgetService`, the row factory
  behind the recent-mail widget. That factory serves the sender and subject of every message
  in every mailbox, so not being exported is not left to carry the guard alone: it also
  requires `android:permission="android.permission.BIND_REMOTEVIEWS"`, a signature permission
  held by the system alone, so the widget host binds it and nothing else can.
- The second is `BootReceiver`, which restarts the push connection after a reboot or an
  in-place update. It has to be exported: a manifest-declared receiver that is not exported
  never sees a system broadcast at all. Its filter carries `BOOT_COMPLETED` and
  `MY_PACKAGE_REPLACED`, both protected broadcasts that only the system can send, and the
  receiver reads **nothing but `intent.action`**, compares it against those two constants and
  returns on anything else. No extra, no data URI, no other field of the intent is ever read,
  so a broadcast forged by another app carries no payload into the app.
- The third and fourth are the home-screen widget providers, `UnreadWidgetProvider` and
  `RecentMailWidgetProvider`. They are exported for the same reason as the boot receiver: a
  manifest-declared receiver that is not exported never sees a system broadcast at all. Each
  filter carries a single action, `APPWIDGET_UPDATE`, again a protected broadcast that only
  the system can send, and neither provider overrides `onReceive`: nothing is read out of the
  intent, no extra and no data URI, only the `appWidgetIds` the framework passes in.
- Notification `PendingIntent`s are `IMMUTABLE` (except the RemoteInput reply, which must
  be mutable and targets a non-exported receiver explicitly).
- The `FileProvider` shares only `cacheDir/attachments/`, with sanitized filenames and
  read-only, single-URI grants.
- **The libraries add five more exported components to the merged manifest**, and the count above
  does not include them. Three are guarded by a permission the caller must hold:
  `androidx.work.impl.background.systemjob.SystemJobService` (`BIND_JOB_SERVICE`, held by the
  system), `androidx.work.impl.diagnostics.DiagnosticsReceiver` and
  `androidx.profileinstaller.ProfileInstallReceiver` (both `DUMP`, a signature/privileged
  permission). The other two belong to the UnifiedPush connector and carry **no permission of
  their own**: `MessagingReceiverImpl`, whose filter is how a UnifiedPush distributor delivers a
  push (`MESSAGE`, `NEW_ENDPOINT`, `UNREGISTERED`, `REGISTRATION_FAILED`, `TEMP_UNAVAILABLE`), and
  `RaiseToForegroundService`. That is the shape the UnifiedPush specification requires — delivery
  is an inter-app broadcast by design — so another app can forge one. It carries no mail: the
  fetch a forged event triggers runs over the account's own authenticated connection, and
  nothing of the mailbox travels through the broadcast. It is not inert, though, and what
  follows is what I have traced, not a proof that there is nothing else. A `PushVerification`
  payload is refused outright on any non-JMAP account, so a forged one cannot push Sterna into
  an authentication round-trip against a host that speaks IMAP; that guard is scoped to the
  protocol, and claims nothing beyond it. An IMAP account that has published a relay push
  address and is still waiting for its first delivery treats a forged event as that delivery:
  the account arms, its transport switches to UnifiedPush and its IMAP IDLE drops, and it stays
  that way until the address is removed in settings, the distributor is uninstalled or the
  endpoint rotates. Once it is armed, a forged event still stamps that account's last wake, so
  the status line can report a fresh wake for a relay nobody has posted to in weeks. Only an
  account that asked for a relay address has a transport that can be moved this way; a JMAP
  account does not, nor does an IMAP account without one.

## Coordinated disclosure

I prefer coordinated disclosure: give me a reasonable window to ship a fix before any
public write-up. Because Sterna is distributed through F-Droid and Obtainium (with the
Codeberg releases as the source of truth), users may take time to update, so please
factor that into disclosure timing.
