# Push v2: multi-folder watch (#16) and UnifiedPush transport (#17)

> **Historical document.** This is the design written before the work; it is kept
> for the reasoning, not as a description of the app. The work shipped in 1.2.0,
> and everything below has been built, changed or dropped since.
> For how push works today, read
> [ARCHITECTURE.md](../ARCHITECTURE.md#push--notifications) and
> [FEATURES.md](../FEATURES.md).

Design document for the `feature/push-v2` work. Three phases, each independently
shippable and device-testable; `main` stays releasable throughout.

- **Phase A**: watch folders beyond the Inbox on the existing pipeline
  ([#16](https://codeberg.org/emon/sterna-mail/issues/16)).
- **Phase B**: UnifiedPush transport for JMAP accounts behind automatic
  transport selection ([#17](https://codeberg.org/emon/sterna-mail/issues/17)).
  Zero behavior change when no distributor is installed.
- **Phase C**: the single "New mail delivery: Instant / Battery saver" setting,
  which also becomes the designated periodic mode for IMAP.

## New mail delivery: Instant vs Battery saver (user-facing behavior)

Sterna picks the best delivery mechanism for each account automatically. The
setting only chooses the outcome you want; you never have to configure a transport.

**Instant (default).** Mail is announced the moment the server receives it.

- JMAP account with a UnifiedPush app installed (ntfy, NextPush): your mail server
  pushes notifications through your UnifiedPush app. Sterna itself keeps no network
  connection open and shows no permanent notification; mail arrives instantly even
  when Sterna is closed.
- JMAP account without a UnifiedPush app: Sterna keeps its own connection to the
  server open (a foreground service with a permanent notification), and mail
  arrives instantly.
- IMAP account: Sterna keeps an IMAP IDLE connection open for the Inbox (same
  foreground service). Inbox mail is instant. Watched folders other than the Inbox
  are covered by the periodic check below, because IMAP IDLE can only watch a
  single folder.

In every case, a background check runs about every 30 minutes as a safety net, so
if the live channel dies silently (battery managers, network changes, a push
server outage), mail is at most half an hour late instead of never arriving.

**Battery saver.** Sterna never keeps a connection open: no foreground service, no
permanent notification.

- JMAP account with UnifiedPush: nothing changes, mail is still instant.
  UnifiedPush costs Sterna nothing, since your UnifiedPush app holds the one shared
  connection anyway.
- JMAP account without UnifiedPush, and IMAP accounts: new mail is picked up by the
  periodic background check, so notifications can arrive up to about 30 minutes
  after the mail does.

You can always see what an account is actually using in Settings, Accounts, under
the notifications switch: "Push: UnifiedPush (ntfy)", "Push: direct connection", or
"Checked every 30 minutes". Changing the setting takes effect immediately.

## UX ground rules (decided, non-negotiable)

These frame every choice below. The guiding principle: **expose outcomes, never
transports**.

1. The transport is picked **automatically** per account:
   - JMAP account + UnifiedPush distributor installed → **UnifiedPush**;
   - JMAP account without a distributor → **direct connection** (EventSource);
   - IMAP account → **direct connection** (IDLE).
   No transport setting is ever exposed.
2. Exactly **one** user-facing setting, outcome-framed: *"New mail delivery:
   Instant / Battery saver (30-min checks)"*. Battery saver drops the persistent
   foreground service entirely.
3. A read-only per-account **status line** (e.g. "Push: UnifiedPush (ntfy)") for
   transparency.
4. The UnifiedPush **distributor picker appears only when more than one**
   distributor is installed (Element X style). One installed → used silently.
5. Folder selection (#16) is a **simple per-folder switch** in the folder UI. No
   configuration matrix.
6. Decided during design review:
   - In Battery saver, **UnifiedPush subscriptions stay active and renewed**:
     they cost Sterna zero battery (the distributor owns the single shared
     connection and runs regardless). "Battery saver" means *Sterna holds no
     persistent connection*; the status line makes the actual behavior visible.
   - The watch switch is **hidden for sent/drafts/trash/junk** (visible for
     archive and role-less folders). The Inbox is always watched and shows no
     switch.
   - Notification **grouping stays per account**; non-Inbox mail shows the
     folder name as notification sub-text. Inbox notifications are unchanged.

## Current pipeline (shipped 1.1.8): what I build on

All in `app/src/main/kotlin/app/sterna/push/`:

- **`PushService`**: `specialUse` foreground service. One connection per
  account (`ConcurrentHashMap<accountId, Closeable>`), a `generation` counter
  retiring stale connections. `watch()` refreshes the inbox
  (`repo.refreshAccountInbox`), seeds or diffs the notification baseline, then
  opens the push connection via `repo.openAccountPush(credentials, onChanged,
  onClosed)`: JMAP EventSource (SSE) or IMAP IDLE. `onAccountChanged()`
  re-fetches **the inbox only**; the JMAP `StateChange` event carries no mailbox
  granularity and IMAP IDLE is bound to INBOX
  (`ImapMailService.openIdle` hardcodes `"INBOX"`).
- **`MailFetchWorker`**: 30-min periodic fallback (`"mail-fetch-fallback"`).
  **No-ops entirely while `PushService.isRunning`.** Otherwise fetches the inbox
  per account and diffs the baseline.
- **`NewMailNotifier`**: persisted baseline in SharedPreferences
  `"push_baselines"`: key = `accountId`, value = the set of email ids already
  seen. "New" = `id !in baseline && !isSeen`. Shared by the service and the
  worker so they never double-notify.
- **`Notifications`**: per-account grouping (`"mail:" + accountId`), reply /
  mark-read / delete actions, quiet hours.
- **Repository** (`core/data`): `refreshAccountInbox` is inbox-hardcoded but
  built on two generic pieces: `resolve()` (context-free session + mailbox
  list, safe for background multi-account work) and `syncMailbox(localAccountId,
  mailboxId)` (delta engine using `Email/queryChanges` + `Email/changes`, full
  query as fallback). Its `syncStates` cursors are **in-memory only**.
- **Folder identity**: JMAP mailbox id (stable across renames); IMAP folder path
  (= `MailboxEntity.id`, embedded in email ids `imap:$account:$path:$uid`;
  changes on rename).
- **Durable per-account data** lives in `StoredAccount` (JSON in
  `AccountStore`); the Room `mailboxes` table is a last-synced-account cache and
  must not hold durable flags.

---

# Phase A: multi-folder watch (#16)

Goal: Sieve-filtered (or any) folders can trigger notifications, on the existing
transports. JMAP gets live coverage; IMAP non-Inbox folders are covered by the
periodic worker (IDLE is single-folder; I deliberately do **not** open N IDLE
connections per account).

## A1. Watched-folder storage

New field on `StoredAccount` (defaulted → JSON-backward-compatible, no
migration):

```kotlin
/** Extra folders watched for new mail, by mailbox id (JMAP id / IMAP path).
 *  The Inbox is always watched and never stored here. */
val watchedFolders: Set<String> = emptySet(),
```

Invariant: **empty set = inbox only = today's behavior.** The effective watched
set is always `{inbox} ∪ watchedFolders`, with the inbox resolved by role at use
time.

`AccountStore` additions (mirroring `setNotificationsEnabled`):

```kotlin
fun watchedFolders(id: String): Set<String>
fun setFolderWatched(id: String, folderId: String, watched: Boolean)
fun replaceWatchedFolder(id: String, oldId: String, newId: String) // IMAP rename
```

Why not a Room table: the `mailboxes` table is an unreliable global cache, and a
new user-data table would need an explicit migration for what is realistically
0–5 strings per account. `StoredAccount` is durable, per-account, exported with
account backups, and removed atomically with the account.

## A2. Repository fan-out

New method next to `refreshAccountInbox` (which stays for existing callers /
delegates to it):

```kotlin
data class FolderRefresh(
    val mailboxId: String,
    val name: String,
    val role: String?,
    val emails: List<Email>,
)

/** Refresh the inbox (unless [includeInbox] is false) plus [extraFolderIds],
 *  context-free, safe for background push across accounts. Watched ids no
 *  longer present on the server are omitted and reported via [onMissing]. */
suspend fun refreshAccountFolders(
    credentials: AccountCredentials,
    extraFolderIds: Set<String>,
    includeInbox: Boolean = true,
    limit: Int = 50,
    onMissing: (String) -> Unit = {},
): List<FolderRefresh>
```

- **JMAP**: one `resolve()`; targets = inbox (by role, fallback first) + each
  extra id present in the resolved mailbox list; `syncMailbox` per target
  (cheap deltas), then read back from the DAO. One session fetch for N folders.
- **IMAP**: new `ImapMailService.loadWatchedFolders(credentials, extraPaths,
  includeInbox, limit)`: a single session: `listFolders()` once, then
  sequential `SELECT` + fetch per folder on the pooled connection.
- **Missing folders** (deleted/renamed server-side): auto-prune, the caller
  drops the watch flag and clears that folder's baseline. A folder deleted
  elsewhere means the watch intent is gone; no error surfaced.
- I deliberately do **not** reuse `refresh(credentials, mailboxId, …)`: it
  mutates the cached single-account context and rewrites the global mailbox
  cache, wrong for background multi-account work.

## A3. Per-folder baselines (+ migration)

Same prefs file `"push_baselines"`, new key format **`"$accountId:$mailboxId"`**
(account ids are UUIDs, never contain `:`, so prefix parsing is unambiguous).
`NewMailNotifier` signatures become folder-scoped:

```kotlin
fun seed(context, accountId, mailboxId, emails)
fun hasBaseline(context, accountId, mailboxId): Boolean
fun clear(context, accountId)              // sweeps "$accountId:*" + legacy bare key
fun clear(context, accountId, mailboxId)   // one folder
fun rename(context, accountId, oldMailboxId, newMailboxId)
suspend fun notifyDiff(context, credentials, mailboxId, folderName: String?, emails)
```

**Legacy migration** (`migrateLegacyBaseline(context, accountId, inboxId)`): if
the bare `accountId` key exists and `"$accountId:$inboxId"` does not, copy the
set over and delete the bare key. Called at the top of `PushService.watch` and
`MailFetchWorker.doWork`. This preserves notification continuity across the
update: no duplicate and no missed inbox notifications.

Non-inbox folders start with no baseline → first sight is a **silent seed**
(never a notification flood for pre-existing mail).

## A4. PushService changes

- `watch()`: migrate legacy baseline → `refreshAccountFolders(credentials,
  store.watchedFolders(id), onMissing = prune)` → per `FolderRefresh`, seed
  (if `resetBaseline` or no baseline) or `notifyDiff(..., mailboxId,
  folderName-if-not-inbox, emails)`. Transport wiring untouched.
- `onAccountChanged()`:
  - **JMAP**: re-sync the whole watched set, since `StateChange` has no per-mailbox
    granularity, and per-folder deltas make the fan-out cheap.
  - **IMAP**: inbox only (`extraFolderIds = emptySet()`): IDLE only ever
    signals INBOX; other folders belong to the worker (A5). No opportunistic
    polling of other folders on inbox activity.
- Toggling a folder watch re-arms the service via the existing
  `PushService.start(context)` idiom; the generation bump runs
  `watch(resetBaseline = true)`, which seeds silently.

## A5. MailFetchWorker: coverage matrix

The all-or-nothing `if (PushService.isRunning) return success` gate is replaced
by per-account coverage, because live push does **not** cover non-Inbox IMAP
folders:

| account | push service running | worker fetches |
|---|---|---|
| any | no | inbox + all watched folders |
| JMAP | yes | nothing (EventSource covers the whole watched set) |
| IMAP | yes | watched extras only (`includeInbox = false`; IDLE owns the inbox) |

Per-folder baselines keep the worker and the service from double-notifying, as
today. The reconnect-gap catch-up (`watch(resetBaseline=false)` diffing on
reconnect) is unchanged.

## A6. Notifications

- Grouping stays **per account** (`"mail:" + accountId`); the per-account
  summary keeps counting across folders. Notification ids (`email.id.hashCode()`)
  are already unique across folders (IMAP ids embed the path).
- `Notifications.notifyNewMail` gains `folderName: String? = null`; when
  non-null (non-Inbox mail) it renders as `setSubText(folderName)`. Inbox
  notifications are unchanged pixel-for-pixel.
- Quiet hours: unchanged (already inside `notifyDiff`).

## A7. Folder UI: the watch switch

Navigation drawer, `InboxScreen.kt`. The existing per-folder overflow
`DropdownMenu` (currently gated `role == null`) is un-gated to **every folder
except inbox, sent, drafts, trash and junk**:

- New first menu item: a toggle **"Notify about new mail"** (checkbox reflecting
  the watch state) → `viewModel.setFolderWatched(mailbox.id, !watched)`.
  Outcome-framed wording; no "push"/"transport" vocabulary.
- The existing New subfolder / Rename / Delete items keep their `role == null`
  gate inside the menu.
- The Inbox shows no switch (implicitly always on).
- `InboxViewModel`: expose `watchedFolders: StateFlow<Set<String>>`;
  `setFolderWatched` persists via `AccountStore` then re-arms
  `PushService.start(...)`.

## A8. Lifecycle edge cases

- **JMAP rename**: mailbox id stable → nothing to do.
- **IMAP rename**: after the server op, `replaceWatchedFolder(old, new)` +
  `NewMailNotifier.rename(...)`. Children of a renamed parent get a best-effort
  prefix rewrite of watched entries; anything missed self-heals via the A2
  auto-prune (the watch silently drops). Accepted loss.
- **Folder delete**: drop the flag + clear the folder baseline.
- **Sign-out**: `NewMailNotifier.clear(accountId)` is added to the sign-out path
  (also fixes a pre-existing leak, baselines currently survive sign-out).
  Watch flags die with the `StoredAccount`.
- **Watched folder vanishes server-side**: auto-prune (A2).

## A9. Files touched (Phase A)

`core/data`: `StoredAccount.kt`, `AccountStore.kt`, `MailRepository.kt`,
`ImapMailService.kt`. `app`: `PushService.kt`, `MailFetchWorker.kt`,
`NewMailNotifier.kt`, `Notifications.kt`, `ui/inbox/InboxScreen.kt`,
`ui/inbox/InboxViewModel.kt`, `ui/settings/AccountsViewModel.kt`, strings (all
locales get the new folder-menu string). **No DB migration, no new deps.**

## A10. Test plan (Phase A)

Device, on the Stalwart test server:

1. JMAP account: watch a second folder; a Sieve/filter rule files mail into it →
   notification with the folder sub-text, while inbox mail behaves exactly as
   before. Unwatch → silence for that folder.
2. IMAP account: watched non-Inbox folder receives mail → notification within
   one worker cycle (≤30 min) while IDLE runs for the inbox.
3. Upgrade-in-place over the previous build → no duplicate/missed inbox
   notifications (legacy-baseline migration).
4. Rename and delete a watched IMAP folder → flags/baselines follow, no stale
   notifications.
5. Sign-out → baselines gone.

---

# Phase B: UnifiedPush transport (#17)

Goal: JMAP accounts get push without any persistent connection (no foreground
service, no permanent notification) whenever a UnifiedPush distributor is
present. **Without a distributor, behavior is byte-identical to Phase A.**

## B1. Dependency & manifest

- `org.unifiedpush.android:connector:3.1.2` (Maven Central, **Apache-2.0**, no
  Google dependencies, satisfies the FOSS-only rule). App module only. The
  `embedded-fcm-distributor` artifact is explicitly **not** added. (Pinned to
  3.1.2, not the latest 3.3.x: 3.2.0+ is built with Kotlin 2.2/2.3 whose metadata
  the project's Kotlin 2.1 toolchain can't read, so bump alongside the Kotlin
  upgrade. See `libs.versions.toml`.)
- The connector does all the WebPush cryptography: it generates the P-256
  keypair + auth secret per registration (`PushEndpoint.pubKeySet` →
  `p256dh` / `auth`) and **decrypts** incoming RFC 8188/8291 payloads:
  `onMessage` delivers cleartext JSON. Sterna implements **no crypto**.
- Manifest:

```xml
<service android:name=".push.UnifiedPushReceiver" android:exported="false">
    <intent-filter>
        <action android:name="org.unifiedpush.android.connector.PUSH_EVENT" />
    </intent-filter>
</service>
```

`UnifiedPushReceiver` subclasses the connector's abstract
`org.unifiedpush.android.connector.PushService` (imported by FQN, it collides
by name with Sterna's own `PushService`).

## B2. core/jmap additions (pure JVM)

`PushSubscription` is **session-level** (RFC 8620 §7.2): no `accountId`, and
`using = [urn:ietf:params:jmap:core]` only. One subscription per Sterna account
(each account = its own credentials/session).

New models (`model/PushSubscription.kt`, `model/PushMessagePayload.kt`):

```kotlin
@Serializable data class PushKeys(val p256dh: String, val auth: String)
@Serializable data class PushSubscription(
    val id: String? = null,
    val deviceClientId: String,
    val url: String,
    val keys: PushKeys? = null,
    val verificationCode: String? = null,
    val expires: String? = null,       // UTCDate
    val types: List<String>? = null,   // ["Email"]
)

sealed interface PushMessagePayload {  // discriminates incoming UP payloads on "@type"
    data class Verification(val pushSubscriptionId: String, val verificationCode: String) : PushMessagePayload
    data class Change(val stateChange: StateChange) : PushMessagePayload
    companion object { fun parse(json: String): PushMessagePayload? }
}
```

`JmapClient` methods, following the existing `buildJsonObject` /
`methodResponseArgs` idiom:

```kotlin
suspend fun getPushSubscriptions(session, auth): List<PushSubscription>
suspend fun createPushSubscription(session, auth, sub): PushSubscription  // server id + (possibly capped) expires
suspend fun verifyPushSubscription(session, auth, id, verificationCode)
suspend fun updatePushSubscriptionExpires(session, auth, id, expires): String
suspend fun destroyPushSubscription(session, auth, id)
```

**VAPID (RFC 9749), optional**: `JmapSession.vapidPublicKey()` reads
`capabilities["urn:ietf:params:jmap:webpush-vapid"].applicationServerKey`; when
present it is passed to `UnifiedPush.register(vapid = …)`. Stalwart does not
ship VAPID yet; ntfy/NextPush don't require it. Nothing breaks either way.

## B3. App side: receiver, state machine, fetch path

**`UnifiedPushReceiver`** is thin: each callback delegates to
`UnifiedPushManager` with `instance` = local account id (one UP registration per
account).

**`UnifiedPushStateStore`**: SharedPreferences `"unifiedpush_state"`, one JSON
blob per account: `deviceClientId` (UUID per install+account), `endpoint`,
`subscriptionId`, `status` (`NONE / REGISTERING / VERIFYING / ACTIVE / FAILED`),
`expiresAtMillis`, `statusSinceMillis`, `lastError`. Deliberately **not** in
`StoredAccount`: endpoints/subscription ids are device-transport state and must
never travel in account backups.

**`UnifiedPushManager`**: the state machine:

- `ensureRegistered(credentials)`: `NONE/FAILED` → `UnifiedPush.register(ctx,
  instance = accountId, vapid?)` → `REGISTERING`.
- `onNewEndpoint(accountId, endpoint)`: destroy the old subscription if the
  endpoint changed, `createPushSubscription {deviceClientId, url = endpoint,
  keys = pubKeySet, types = ["Email"], expires = now+7d}` → `VERIFYING`.
- `onMessage(accountId, message)`:
  - `Verification` → `verifyPushSubscription(...)` **promptly** (Stalwart's
    verify window is ~1 min) → `ACTIVE` → `PushController.apply()`.
  - `Change` (Email changed for this account) → enqueue `PushFetchWorker`.
  - Defensive: if `PushMessage.decrypted` is false or the payload doesn't
    parse, treat it as a bare wake signal and still enqueue a fetch.
- `onRegistrationFailed` / `onUnregistered` → `FAILED` / `NONE` →
  `PushController.apply()` (direct connection resumes seamlessly).
- `renewIfNeeded(credentials)`: called from the 30-min worker; if `expires`
  lands within two worker cycles, update it (recreate on error). The server may
  cap the requested value; store what it returns.
- Verification watchdog: `VERIFYING` older than 2 min counts as `FAILED`,
  evaluated lazily at the next `apply()`/worker run, no timer.
- `teardown(credentials)` on sign-out: `UnifiedPush.unregister(instance)` +
  best-effort `destroyPushSubscription` + state cleared.

**`PushFetchWorker`**: expedited one-shot `OneTimeWorkRequest`
(`RUN_AS_NON_EXPEDITED_WORK_REQUEST_COMPATIBLE`), unique per account, policy
KEEP (a queued fetch covers coalesced pushes). Body = the shared per-account
fetch+notify helper:

```kotlin
object FetchAndNotify {
    suspend fun run(context, credentials, includeInbox: Boolean = true)
    // migrate baselines → refreshAccountFolders → seed/notifyDiff → prune missing
}
```

`PushService.watch`, `MailFetchWorker` and `PushFetchWorker` all call it, so the
three delivery paths can never drift apart. A worker (not an inline fetch in the
connector service) gives retry semantics, survives the connector service's short
lifetime, and behaves under Doze (the push already opened a network window).

## B4. Auto-selection: `PushController`

Single orchestration entry point; every current `PushService.start(context)`
call site becomes `PushController.apply(context)`:

```kotlin
enum class Transport { UNIFIED_PUSH, EVENT_SOURCE, IMAP_IDLE } // Phase C adds PERIODIC

fun transportFor(credentials) = when {
    credentials.protocol == JMAP && distributorReady()
        && (up.isActive(id) || up.isPending(id)) -> UNIFIED_PUSH
    credentials.protocol == JMAP -> EVENT_SOURCE
    else -> IMAP_IDLE
}
```

`apply(context)`:

1. Eligible accounts = the existing watched set (`pushAllAccounts` / current) ∩
   `notificationsEnabled`, unchanged.
2. JMAP accounts with a distributor: `ensureRegistered(...)`.
3. Accounts on a **direct** transport remaining? → `PushService.start`; none →
   `PushService.stop`. `PushService.reconnectAll` additionally filters out
   UP-active accounts.
4. UP-only accounts with no FGS get one `PushFetchWorker` pass to seed baselines
   / catch up.

Key behaviors:

- **No distributor → nothing changes**: every JMAP account is `EVENT_SOURCE`,
  no UP code path runs, no registration is attempted.
- **No delivery gap during bring-up**: an account keeps its EventSource until UP
  is **ACTIVE** (`REGISTERING`/`VERIFYING` count as pending, grace 2 min). A
  transient double-trigger just causes one redundant delta sync; baselines
  dedupe notifications.
- **Mixed accounts**: the FGS runs holding connections only for direct accounts.
  **All-UP**: no FGS at all, the headline win of the phase (no permanent
  notification).
- **Failure re-evaluation**: UP callbacks, every `apply()` call site (app open,
  settings, account changes), every 30-min worker run (which also calls
  `renewIfNeeded` and re-checks the distributor: a distributor uninstalled while
  the app was closed flips back to EventSource within ≤30 min, with mail still
  flowing via the worker meanwhile).

`MailFetchWorker` coverage matrix gains one row: JMAP + UP-`ACTIVE` → skip
(push covers the whole watched set); UP `FAILED`/expired → polled like today.

## B5. Distributor picker

- Exactly one distributor installed → saved and used silently, no UI, ever.
- More than one, none saved → a one-time dialog listing the distributors by app
  label ("Deliver notifications through…") → `saveDistributor` → `apply()`. No
  settings entry; plain `AlertDialog` (no `LinkActivityHelper`).
- Saved distributor uninstalled: registration fails / `onUnregistered` →
  fallback to direct (B4); if exactly one other distributor remains it is
  auto-saved, several → the picker shows again.

## B6. Persisted sync cursors

With UP-only accounts the process is routinely dead between pushes; the
in-memory `syncStates` would force a full re-query per wakeup. New
`SyncStateStore` (SharedPreferences `"sync_states"`, key
`"$localAccountId:$mailboxId"`, value = queryState + emailState): write-through
at the four `syncStates` mutation sites in `MailRepository`, lazy-load on miss,
cleared by `resetSyncState()`. Every UP wakeup is then a true delta.

## B7. Status line (ships with B)

Account detail screen, under the notifications switch: read-only text from
`PushController.statusFor(account)`: "Push: UnifiedPush (ntfy)" (distributor app
label) / "Push: direct connection" (EventSource and IDLE alike, the protocol
distinction is not user-relevant) / "Push: connecting…" / "Push: unavailable, checking every 30 min".

## B8. Files touched (Phase B)

Create: `core/jmap/.../model/PushSubscription.kt`, `model/PushMessagePayload.kt`,
`core/data/.../mail/SyncStateStore.kt`, `app/.../push/UnifiedPushReceiver.kt`,
`UnifiedPushManager.kt`, `UnifiedPushStateStore.kt`, `PushController.kt`,
`PushFetchWorker.kt`, `FetchAndNotify.kt`.
Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`,
`AndroidManifest.xml`, `JmapClient.kt`, `JmapSession.kt`, `MailRepository.kt`
(write-through), `PushService.kt`, `MailFetchWorker.kt`,
`SternaApplication.kt` (wiring), `SternaApp.kt`, `SettingsViewModel.kt`,
`AccountsViewModel.kt` (teardown on sign-out), `SettingsScreen.kt` (status
line), strings.

## B9. Test plan (Phase B)

Unit (core/jmap, MockWebServer, existing test style): request shape for
create/verify/renew/destroy (no `accountId`, `using=[core]`), response parsing,
`PushMessagePayload.parse` (StateChange / PushVerification / garbage),
`vapidPublicKey()` presence/absence.

Device (Stalwart + ntfy):

1. **No distributor**: byte-identical behavior: FGS notification present,
   EventSource connected, no PushSubscription created server-side.
2. Install ntfy → reopen app → subscription created + verified; FGS gone
   (single JMAP account); send mail → notification with the app swiped away.
3. Endpoint rotation (re-register in ntfy) → old subscription destroyed, new
   one verified.
4. Uninstall ntfy → FGS returns within one worker cycle; no mail lost.
5. Two distributors installed, none saved → picker; only one → no picker.
6. Mixed: add an IMAP account → FGS stays for IDLE while JMAP rides UP.
7. Verification timeout (distributor unreachable mid-registration) → falls back
   to EventSource within the grace period.

---

# Phase C: "Instant / Battery saver" + IMAP periodic mode

## C1. The setting

`SettingsRepository` (DataStore, follows the existing Flow-per-setting pattern):

```kotlin
enum class DeliveryMode { INSTANT, BATTERY_SAVER }
val deliveryMode: Flow<DeliveryMode>   // stringPreferencesKey("delivery_mode"), default INSTANT
suspend fun setDeliveryMode(mode: DeliveryMode)
```

Default `INSTANT` → existing installs keep their behavior, no migration.
Included in the settings backup (quiet-hours precedent).

UI: Notifications settings screen, first section, two radio rows:

- **Instant**: "Mail arrives as it lands. May keep a background connection
  open."
- **Battery saver**: "Checks for mail every 30 minutes. No background
  connection."

Nothing else. No transport vocabulary. Changing it persists then calls
`PushController.apply()`.

## C2. Semantics

`PushController.apply` gains the mode as its first gate:

- **INSTANT**: exactly Phase B.
- **BATTERY_SAVER**: the FGS is never started (`PushService.stop` enforced at
  every `apply()` call site, so it is sticky across boot/app-open). Direct
  accounts (JMAP without distributor, all IMAP) are served solely by the 30-min
  worker: this *is* the designated IMAP periodic mode; it already exists as the
  fallback path, C makes it a first-class outcome. **UnifiedPush subscriptions
  are kept and renewed** (decided): UP costs Sterna zero battery, dropping it
  would degrade delivery for no gain. `Transport` gains `PERIODIC`; in battery
  saver `transportFor` returns `UNIFIED_PUSH` (if active) else `PERIODIC`.

Final worker coverage matrix:

| account | INSTANT | BATTERY_SAVER |
|---|---|---|
| JMAP + UP active | skip (renew only) | skip (renew only) |
| JMAP direct | FGS running → skip; else all watched | all watched folders |
| IMAP | inbox skipped while IDLE runs; extras always | all watched folders |

## C3. Status line, final wording

"Push: UnifiedPush (ntfy)" · "Push: direct connection" · "Checked every 30
minutes (battery saver)" · "Push: connecting…" · "Push: unavailable, checking
every 30 min".

## C4. Files touched (Phase C)

`SettingsRepository.kt` (+ backup model), `PushController.kt`,
`MailFetchWorker.kt`, `SettingsScreen.kt`, `SettingsViewModel.kt`, strings.

## C5. Test plan (Phase C)

Device: toggle Battery saver → FGS notification disappears immediately, UP mail
still instant, direct-account mail arrives ≤30 min; toggle back → FGS returns;
reboot in battery saver → no FGS after first app open, worker alive.

---

# Phase B field notes: Stalwart interop quirks (found on-device, 2026-07-17)

Both worked around in `UnifiedPushManager`; both worth reporting upstream:

1. **Keys decode requires canonical padding.** Stalwart parses `keys` with rust
   base64's `URL_SAFE` engine (`crates/jmap/src/push/set.rs`), which rejects the
   unpadded RFC 7515-style base64url the WebPush ecosystem uses. Sterna re-encodes
   the connector's keys as padded base64url before `PushSubscription/set`.
2. **Push bodies are base64url text, not octets.** Stalwart ece-encrypts the
   payload, then base64url-encodes the whole aes128gcm blob and POSTs that string
   (`crates/services/src/state_manager/http.rs`), where RFC 8030 expects raw
   octets. The connector therefore never recognizes the body as encrypted and
   delivers it verbatim (`decrypted=false`). On parse failure of an undecrypted
   delivery, Sterna base64url-decodes and decrypts through the connector's
   `DefaultKeyManager`, then parses. This covers PushVerification and StateChange
   alike; a still-unreadable payload degrades to a bare wake-and-fetch.

Also learned the hard way (now encoded in the state machine): a failed
create/verify must NOT retrigger registration through the transport-changed
callback, and without the 15-min FAILED cooldown the loop hammered the mail server
with register+create attempts at double-digit Hz.

# Risks

1. **StateChange → whole-watched-set resync** amplifies traffic on chatty JMAP
   accounts. Mitigated by per-folder deltas + persisted cursors (B6). Watch for
   servers that cannot compute `queryChanges` (falls back to a full query per
   folder per event).
2. **Expedited-work quotas** under heavy push volume: degrades to regular work
   via the compat policy: delivery slightly delayed, never lost.
3. **Connector 3.x API drift**: pinned to 3.1.2; all connector calls are
   isolated inside `UnifiedPushManager` so surface changes localize.
4. **Stalwart's ~1-min verification window** vs slow distributor delivery:
   mitigated by keeping the EventSource open until `ACTIVE`; a missed
   verification just means staying on the direct connection.
5. **IMAP parent-folder rename** can orphan children's watch flags: best-effort
   prefix rewrite + self-healing auto-prune. Accepted.
6. **Baseline prefs growth**: bounded by watched folders; prefix-swept on
   sign-out.

# Delivery

Work happens on `feature/push-v2`. Suggested commit granularity:

- **Phase A** (~700–900 line diff): (1) watch flags in
  StoredAccount/AccountStore; (2) `refreshAccountFolders` +
  `loadWatchedFolders`; (3) per-folder baselines + migration + notification
  sub-text; (4) PushService/MailFetchWorker fan-out + coverage matrix;
  (5) folder UI switch + rename/delete/sign-out cleanup.
- **Phase B** (~1300–1600): (1) core/jmap PushSubscription models + methods +
  tests; (2) `PushMessagePayload` + tests; (3) `SyncStateStore`;
  (4) connector dep + receiver + state store; (5) `UnifiedPushManager`;
  (6) `PushController` + integration (+ `FetchAndNotify` extraction);
  (7) picker + status line + sign-out teardown.
- **Phase C** (~250–400): (1) `DeliveryMode` setting + backup;
  (2) controller/worker semantics; (3) settings UI + status strings.

Each phase ends with a device-test pass (temporary versionCode ≥ 217 for test
builds) and is releasable on its own.
