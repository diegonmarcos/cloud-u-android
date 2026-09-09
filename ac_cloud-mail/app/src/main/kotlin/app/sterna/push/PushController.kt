package app.sterna.push

import android.app.Application
import android.content.Context
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.settings.DeliveryMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** How one account's new mail arrives. */
enum class Transport { UNIFIED_PUSH, EVENT_SOURCE, IMAP_IDLE, PERIODIC }

/** Read-only per-account delivery status, for the account detail screen (UX rule 3). */
sealed interface PushStatus {
    /** Instant via UnifiedPush; [distributorPackage] resolves to an app label in UI. */
    data class ViaUnifiedPush(val distributorPackage: String?) : PushStatus

    /** Woken by a relay the user pasted this account's address into (#177). A timestamp, not a
     *  verdict: nothing here can tell a dead relay from a quiet one. */
    data class ViaRelay(val lastDeliveryMillis: Long) : PushStatus

    /** Instant via a direct connection we hold, open right now (JMAP EventSource or IMAP IDLE). */
    data object Direct : PushStatus

    /** Bring-up in flight — UnifiedPush registration, or the direct connection (re)opening. */
    data object Connecting : PushStatus

    /** No live connection right now — the periodic worker checks every ~30 minutes. */
    data object Periodic : PushStatus

    /** Not in the watched set at all (A8): nothing touches it until the app is opened. Distinct
     *  from [Periodic], whose 30-minute claim would be a lie here. */
    data object NotWatched : PushStatus
}

/**
 * Single decision point for push transports (#17): [apply] kicks the UnifiedPush state machine and
 */
object PushController {

    /**
     * The last selection the inbox list made: true if that was the unified inbox. Read by
     */
    @Volatile
    var unifiedInboxVisible: Boolean = false

    fun transportFor(context: Context, credentials: AccountCredentials): Transport =
        transportFor(context, credentials, isBatterySaver(context))

    private fun transportFor(context: Context, credentials: AccountCredentials, batterySaver: Boolean): Transport {
        val container = (context.applicationContext as Application).container
        val up = container.unifiedPushManager
        return when {
            // A linked sub-account holds no live push of its own (#31): the server never puts an
            // ACL-shared account's StateChanges on the login's EventSource.
            container.accountStore.account(credentials.id)?.isLinked == true -> Transport.PERIODIC
            // Not gated on JMAP: an IMAP relay rides this too, but only once ACTIVE — a payload
            // really arrived — or any distributor would trade IMAP IDLE for a dead endpoint.
            up.isActive(credentials.id) -> Transport.UNIFIED_PUSH
            batterySaver -> Transport.PERIODIC
            credentials.protocol == MailProtocol.JMAP -> Transport.EVENT_SOURCE
            else -> Transport.IMAP_IDLE
        }
    }

    /** Synchronous read: DataStore serves from memory after the first load, off any hot path. */
    private fun isBatterySaver(context: Context): Boolean {
        val settings = (context.applicationContext as Application).container.settingsRepository
        return runBlocking { settings.deliveryMode.first() } == DeliveryMode.BATTERY_SAVER
    }

    /** Whether [accountId] is in the push/worker watched set (A8). The notifications filter applies
     *  AFTER this, so an account outside the set is looked at by nothing and reads NotWatched
     *  rather than a 30-minute poll that never runs. */
    fun isWatched(accountId: String, currentId: String?, pushAllAccounts: Boolean): Boolean =
        pushAllAccounts || accountId == currentId

    /** Whether the "not being watched" note is telling the truth (A8): it names ONE action, and
     *  that is the whole action only while [notificationsEnabled] is still on. */
    fun shouldShowUnwatchedNote(isLinked: Boolean, isWatched: Boolean, notificationsEnabled: Boolean): Boolean =
        !isLinked && !isWatched && notificationsEnabled

    /** What an arm ASKED FOR and what it came away with. [held], never "open": a handle is
     *  recorded before the server has answered, so what it rules out is an arm that never got that
     *  far — offline, refused, a retired generation. */
    fun armSummary(accounts: Int, logins: Int, held: Int): String =
        "Push armed for $accounts account(s): $logins login connection(s) requested, $held held"

    /** An arm that found nothing to watch and stopped itself. Two causes look identical from here,
     *  so [candidates] counts the accounts considered before the filter. */
    fun nothingToWatchLine(candidates: Int): String =
        "Push arm found nothing to watch: 0 of $candidates account(s) need a direct connection, stopping"

    /** An empty account list in the fallback cycle. Four facts arrive here and counting cannot
     *  separate them, so this branches: [accountsUnreadable] is asked FIRST, an unreadable blob
     *  making `accounts()` answer empty, and [stored] then separates the genuinely empty install
     * from credentials that did not materialise. "For the watched set", not "for all of them". */
    fun nothingToPollLine(stored: Int, candidates: Int, accountsUnreadable: Boolean): String = when {
        accountsUnreadable ->
            "Fallback poll found nothing to fetch: the stored account list could not be read, " +
                "so no account was polled (not an empty install)"
        stored == 0 ->
            "Fallback poll found nothing to fetch: no account is configured, so there was nothing to poll"
        candidates == 0 ->
            "Fallback poll found nothing to fetch: $stored account(s) configured, but no credentials " +
                "came back for the watched set, so no account was polled (not a choice)"
        else ->
            "Fallback poll found nothing to fetch: 0 of $candidates watched account(s) have notifications on, cycle over"
    }

    /** One account skipped whole — the widest of the worker's silent exits, since on JMAP
     * `hasExtrasToPoll` is false by construction. "A handle is HELD", never "a connection is
     *  open": a socket that died without telling us still answers true, and the line would assert
     *  the very thing under suspicion. [accountId] is the internal UUID, a logcat being public. */
    fun pollSkippedLine(accountId: String, protocol: MailProtocol, watchedFolders: Int): String =
        "Fallback poll skipped account $accountId whole: a push connection handle is held for it, " +
            "so its inbox and its $watchedFolders watched extra folder(s) are left to that handle ($protocol)"

    /** The half-skip, and the one that costs mail if the handle is lying: the cycle fetches with
     *  `includeInbox = false`. Worded apart from [pollSkippedLine], a logcat reader having only the
     * sentence. Same on "held" versus "open". */
    fun inboxSkippedLine(accountId: String, protocol: MailProtocol, watchedFolders: Int): String =
        "Fallback poll left account $accountId's inbox unfetched: a push connection handle is held " +
            "for it, so only its $watchedFolders watched extra folder(s) were polled ($protocol)"

    /** An arm dropped because the generation moved on. Both generations are printed: only their
     *  difference says why. [loginId] is the internal id. */
    fun abandonedArmLine(loginId: String, gen: Int, current: Int): String =
        "Stale push arm abandoned for login $loginId (generation $gen, service is at $current)"

    /** Same renouncement one step later: a reconnect scheduled, waited, and found retired. Worded
     *  apart from [abandonedArmLine], a logcat reader having only the sentence. */
    fun abandonedReconnectLine(loginId: String, gen: Int, current: Int): String =
        "Stale push reconnect abandoned for login $loginId (generation $gen, service is at $current)"

    /**
     * Third renouncement, and the only one that had something in its hands: the handle is taken out
     */
    fun retiredHandleDroppedLine(loginId: String, gen: Int, current: Int): String =
        "Stale push connection dropped for login $loginId (generation $gen, service is at $current), nothing rescheduled"

    fun statusFor(context: Context, accountId: String): PushStatus {
        val container = (context.applicationContext as Application).container
        val store = container.accountStore
        val up = container.unifiedPushManager
        return when {
            // A linked sub-account inherits the login's transport state, but the server never
            // pushes its changes there (#31).
            store.account(accountId)?.isLinked == true -> PushStatus.Periodic
            // An armed relay (#177) is tested ABOVE the UnifiedPush branch and above NotWatched:
            // a POST on the relay address enqueues [PushFetchWorker], which gates on
            // notificationsEnabled alone, so an unwatched account with a relay really is woken.
            up.relayArmed(accountId) -> PushStatus.ViaRelay(up.relayLastDeliveryMillis(accountId))
            // Not watched at all (A8), decided before every branch below so it cannot masquerade.
            !isWatched(accountId, store.currentId(), store.pushAllAccounts()) -> PushStatus.NotWatched
            up.isActive(accountId) -> PushStatus.ViaUnifiedPush(up.distributorLabel())
            isBatterySaver(context) -> PushStatus.Periodic
            // An open connection wins over a pending UnifiedPush bring-up: the EventSource stays up
            // through REGISTERING/VERIFYING, so delivery is direct right now (#53).
            PushService.isConnected(accountId) -> PushStatus.Direct
            up.isPending(accountId) -> PushStatus.Connecting
            // Watched by the running service but not open yet: bring-up, or a retry loop.
            PushService.isRunning -> PushStatus.Connecting
            else -> PushStatus.Periodic
        }
    }

    /**
     * Recompute every account's transport and (re)arm accordingly. [userInitiated] silently reseeds
     */
    fun apply(context: Context, userInitiated: Boolean) {
        val appContext = context.applicationContext as Application
        val container = appContext.container
        val store = container.accountStore
        val up = container.unifiedPushManager
        up.reconcileDistributorPresence()
        val watched = if (store.pushAllAccounts()) store.allCredentials() else listOfNotNull(store.load())
        val accounts = watched.filter { store.notificationsEnabled(it.id) }
        // Kick the UnifiedPush state machine (no-op for IMAP or without a distributor).
        accounts.forEach { up.ensureRegistered(it) }
        // An account counts as direct until UnifiedPush is fully ACTIVE, so bring-up never gaps
        // delivery. In battery saver none is: the 30-minute worker carries the rest.
        val batterySaver = isBatterySaver(appContext)
        val direct = accounts.filter { transportFor(appContext, it, batterySaver).isDirect }
        val currentId = store.currentId()
        if (direct.isEmpty()) {
            PushService.stop(appContext)
            // No foreground service: catch up through the worker path.
            accounts.forEach {
                val reset = shouldResetBaseline(it.id, userInitiated, currentId, unifiedInboxVisible)
                PushFetchWorker.enqueue(appContext, it.id, resetInbox = reset)
            }
        } else {
        // Android 12+ can refuse a foreground-service start at the CALLER, before the service is
        // created, so the #98 guard in PushService.onCreate never sees it. Left to propagate it
        // crashes a retried trigger, i.e. a crash loop.
            try {
                // The service applies [shouldResetBaseline] per account itself; this is only the
                // "was this a user action" half.
                PushService.start(appContext, userInitiated = userInitiated)
            } catch (e: RuntimeException) {
                android.util.Log.w(
                    "PushController",
                    "foreground push start refused at caller; periodic poll takes over",
                    e,
                )
                accounts.forEach {
                    val reset = shouldResetBaseline(it.id, userInitiated, currentId, unifiedInboxVisible)
                    PushFetchWorker.enqueue(appContext, it.id, resetInbox = reset)
                }
            }
        }
    }
}

/**
 * May a user-initiated arm swallow this account's inbox backlog into the baseline instead of
 */
internal fun shouldResetBaseline(
    accountId: String,
    userInitiated: Boolean,
    currentAccountId: String?,
    unifiedInbox: Boolean,
): Boolean = userInitiated && (unifiedInbox || accountId == currentAccountId)
