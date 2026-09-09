package app.sterna.push

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.sterna.container
import app.sterna.core.data.account.MailProtocol
import java.util.concurrent.TimeUnit

/**
 * Fallback new-mail poll for when live push is down (#11). Android 15+ budgets long-running
 */
class MailFetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as Application).container
        val store = container.accountStore
        val watched = if (store.pushAllAccounts()) store.allCredentials() else listOfNotNull(store.load())
        val accounts = watched.filter { store.notificationsEnabled(it.id) }
        // Coverage matrix (#16/#17): a JMAP EventSource open FOR THIS ACCOUNT covers its whole
        // watched set, IMAP IDLE only the INBOX. A UnifiedPush-active account gets the same safety
        // poll, its endpoint being able to die silently with the status still ACTIVE.
        val up = container.unifiedPushManager
        up.reconcileDistributorPresence()
        // Until this line the cycle ended on the same SUCCESS as one that fetched everything. Four
        // different things arrive here and only one is a choice, so the three facts that separate
        // them are read here and handed to the line.
        if (accounts.isEmpty()) {
            Log.i(
                TAG,
                PushController.nothingToPollLine(
                    stored = store.accounts().size,
                    candidates = watched.size,
                    accountsUnreadable = store.accountsUnreadable(),
                ),
            )
            return Result.success()
        }
        for (credentials in accounts) {
            // FAILED registrations retry on this cadence (cooldown inside the manager).
            runCatching { up.ensureRegistered(credentials) }
                .onFailure { Log.w(TAG, "UnifiedPush register check failed for ${credentials.id}", it) }
            runCatching { up.renewIfNeeded(credentials) }
                .onFailure { Log.w(TAG, "UnifiedPush renew check failed for ${credentials.id}", it) }
            if (up.isActive(credentials.id)) {
                runCatching { FetchAndNotify.run(applicationContext, credentials) }
                    .onFailure { Log.w(TAG, "Safety poll failed for account ${credentials.id}", it) }
                continue
            }
            // A linked sub-account gets no live push of its own (#31): the server only delivers
            // StateChanges for the login's member accounts, so its EventSource does not cover it.
            val linked = store.account(credentials.id)?.isLinked == true
            // Asked per ACCOUNT, never "is the service up": the service outlives the failure of
            // every connection it holds (#61). False when it is down or the account is unknown, so
            // the default is to poll — a wasted delta costs a request, a wrong skip costs mail.
            val connected = PushService.isConnected(credentials.id)
            val pollInbox = shouldPollInbox(connected, linked)
            // Read once, so the count the line reports is the one the decision was taken on.
            val watchedFolders = store.watchedFolders(credentials.id)
            val pollExtras = hasExtrasToPoll(
                isImap = credentials.protocol == MailProtocol.IMAP,
                watchedFolders = watchedFolders,
            )
            // The widest of the silent exits: on JMAP [hasExtrasToPoll] is false by construction,
            // so an account we hold a handle for is skipped WHOLE.
            if (!pollInbox && !pollExtras) {
                Log.i(TAG, PushController.pollSkippedLine(credentials.id, credentials.protocol, watchedFolders.size))
                continue
            }
            // The half-skip, and the one that costs mail if the handle is lying (IMAP only). Said
            // before the fetch, so the order in logcat is the order of events.
            if (!pollInbox) {
                Log.i(TAG, PushController.inboxSkippedLine(credentials.id, credentials.protocol, watchedFolders.size))
            }
            runCatching {
                FetchAndNotify.run(applicationContext, credentials, includeInbox = pollInbox)
            }.onFailure { Log.w(TAG, "Fallback fetch failed for account ${credentials.id}", it) }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "MailFetchWorker"
        private const val WORK_NAME = "mail-fetch-fallback"

        /** Idempotent: keeps the existing schedule if one is already enqueued. */
        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<MailFetchWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/**
 * Does the fallback cycle owe this account's INBOX a poll?
 */
internal fun shouldPollInbox(pushConnected: Boolean, linked: Boolean): Boolean = !pushConnected || linked

/**
 * With the inbox covered, is there anything left worth a cycle? Only IMAP's watched extras, IDLE
 */
internal fun hasExtrasToPoll(isImap: Boolean, watchedFolders: Set<String>): Boolean =
    isImap && watchedFolders.isNotEmpty()
