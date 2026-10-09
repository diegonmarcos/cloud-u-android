package app.sterna.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import app.sterna.R
import app.sterna.SternaApplication
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.settings.NotificationContent
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles new-mail notification quick actions (reply / copy code / remind; mark read and delete handlers stay for banners already on the shade) in the background.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val emailId = intent.getStringExtra(EXTRA_EMAIL_ID) ?: return
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)
        val code = intent.getStringExtra(EXTRA_CODE)
        val replyText = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()

        val appContext = context.applicationContext
        val container = (appContext as SternaApplication).container
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val credentials = container.accountStore.credentials(accountId)
                if (action == ACTION_COPY_CODE) {
                    if (code != null) copyCode(appContext, code)
                    // The code is on the clipboard; the banner has done its job.
                    dismiss(appContext, accountId, credentials, emailId, notifId)
                    return@launch
                }
                if (action == ACTION_REMIND) {
                    // Down first, then scheduled: dismiss() cancels nothing of the reminder, but the
                    // order keeps a failed schedule from leaving the banner up AND the tap spent.
                    dismiss(appContext, accountId, credentials, emailId, notifId)
                    NotificationReminders.schedule(appContext, accountId, emailId)
                    return@launch
                }
                // Reply leaves the common path BEFORE the dismissal below: it used to fall through
                // to it, so a reply that sent nothing still cleared the notification and the text.
                if (action == ACTION_REPLY) {
                    reply(
                        appContext, container.mailRepository, container.settingsRepository,
                        credentials, accountId, emailId, notifId, replyText,
                    )
                    return@launch
                }
                // Delete can find there is nothing to do, and the banner must then survive it.
                var mayDismiss = true
                if (credentials != null) {
                    when (action) {
                        ACTION_MARK_READ -> container.mailRepository.setRead(credentials, emailId, true)
                        ACTION_DELETE -> mayDismiss = deleteFromBanner(
                            container.mailRepository, credentials, accountId, emailId,
                        )
                    }
                    if (mayDismiss) dismiss(appContext, accountId, credentials, emailId, notifId)
                }
            } catch (_: Throwable) {
                // Best-effort; leave the notification so the user can retry from the app.
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Copy [code] and say so. Marked sensitive on Android 13+ so the clipboard preview and
     * cloud-synced clipboards leave it out. A toast needs the main thread.
     */
    private fun copyCode(context: Context, code: String) {
        val clip = android.content.ClipData.newPlainText("code", code)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, R.string.message_code_copied, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * The Delete button of a banner, and the question it never asked: would this delete MOVE
     */
    private suspend fun deleteFromBanner(
        repo: MailRepository,
        credentials: AccountCredentials,
        accountId: String,
        emailId: String,
    ): Boolean {
        val cached = runCatching { repo.cachedEmail(accountId, emailId) }.getOrNull()
        val role = runCatching { repo.mailboxRole(accountId, cached?.mailboxId) }.getOrNull()
        val hasTrash = runCatching { repo.hasCachedTrash(accountId) }.getOrDefault(true)
        when (bannerDeleteAct(role, hasTrash)) {
            BannerDeleteAct.DoNothing -> return false
            BannerDeleteAct.MoveToTrash -> repo.delete(credentials, emailId)
        }
        return true
    }

    /** The Reply path, whose only asset is the text the user just typed. Cache FIRST, exactly like
     *  the composer, so this never waits on a socket to learn where the reply is going. Three ends,
     *  none of them silent — queued, handed back, or nothing was typed. */
    private suspend fun reply(
        context: Context,
        repo: MailRepository,
        settings: SettingsRepository,
        credentials: AccountCredentials?,
        accountId: String,
        emailId: String,
        notifId: Int,
        text: String?,
    ) {
        val cached = credentials?.let {
            runCatching { repo.cachedEmail(accountId, emailId) }.getOrNull()
        }
        val outcome = quickReplyOutcome(text, cached)
        val queued = outcome is QuickReplyOutcome.Send && credentials != null &&
            runCatching { send(repo, credentials, emailId, outcome) }.isSuccess
        when (val act = quickReplyAct(outcome, queued)) {
            QuickReplyAct.DoNothing -> Unit
            QuickReplyAct.Dismiss -> dismiss(context, accountId, credentials, emailId, notifId)
            // Read here and not before: the setting is only needed on the end that posts a banner.
            // Guarded, and not for tidiness: the DataStore has no corruption handler, so a
            is QuickReplyAct.HandBack -> {
                val content = runCatching { settings.notificationContent.first() }
                    .getOrDefault(NotificationContent.NONE)
                Notifications.notifySendFailed(context, act.subject, content, act.body)
            }
        }
    }

    /**
     * Hand the reply to the Outbox, which is what makes the text durable: sent when there is a
     */
    private suspend fun send(
        repo: MailRepository,
        credentials: AccountCredentials,
        emailId: String,
        outcome: QuickReplyOutcome.Send,
    ) {
        val fetched = fetchWithinBudget(repo, credentials, emailId)
        repo.enqueueSend(
            credentials = credentials,
            to = listOf(outcome.to),
            subject = outcome.subject,
            body = outcome.body,
            inReplyTo = fetched?.messageId.orEmpty(),
            references = fetched?.references.orEmpty() + fetched?.messageId.orEmpty(),
            // A quick reply is typed in the shade and replaces no saved draft.
            draftUidValidity = null,
        )
    }

    /**
     * The original's threading headers, or null — ABANDONED after [FETCH_BUDGET_MS].
     */
    private suspend fun fetchWithinBudget(
        repo: MailRepository,
        credentials: AccountCredentials,
        emailId: String,
    ): Email? {
        val fetching = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            .async { repo.fetchEmail(credentials, emailId) }
        val fetched = runCatching { withTimeoutOrNull(FETCH_BUDGET_MS) { fetching.await() } }.getOrNull()
        fetching.cancel()
        return fetched
    }

    private fun dismiss(
        context: Context,
        accountId: String,
        credentials: AccountCredentials?,
        emailId: String,
        notifId: Int,
    ) {
        // The shade is read BEFORE the cancel below: `cancel` returns once the system has queued
        // the work and `getActiveNotifications` does not drain that queue, so a later read still
        // shows the banner just taken down and the summary would put it back (#134).
        val active = Notifications.activeChildIds(context, accountId)
        NotificationManagerCompat.from(context).cancel(notifId)
        credentials?.let {
            Notifications.updateGroupSummary(
                context, accountId, it.username, silent = true,
                expectedChildIds = Notifications.liveChildIdsAfter(active, accountId, listOf(emailId), emptyList()),
                cancelledChildIds = Notifications.childIdsOf(accountId, listOf(emailId)),
            )
        }
    }

    companion object {
        const val ACTION_MARK_READ = "app.sterna.action.MARK_READ"
        const val ACTION_DELETE = "app.sterna.action.DELETE"
        const val ACTION_COPY_CODE = "app.sterna.action.COPY_CODE"
        const val ACTION_REMIND = "app.sterna.action.REMIND"
        const val EXTRA_CODE = "code"
        const val ACTION_REPLY = "app.sterna.action.REPLY"
        const val EXTRA_EMAIL_ID = "email_id"
        const val EXTRA_ACCOUNT_ID = "account_id"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val KEY_REPLY = "key_reply"

        /** The leash on the threading fetch — well under goAsync's budget, deliberately. */
        private const val FETCH_BUDGET_MS = 5_000L
    }
}
