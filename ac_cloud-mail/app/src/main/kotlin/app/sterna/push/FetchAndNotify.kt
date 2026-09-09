package app.sterna.push

import android.app.Application
import android.content.Context
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.mail.AccountGoneException
import app.sterna.core.data.mail.accountGoneCause
import app.sterna.core.data.rethrowIfCancelled
import app.sterna.widget.RecentMailWidgetDraw
import app.sterna.widget.UnreadWidgetDraw
import kotlinx.coroutines.flow.first

/** The one fetch+notify pass shared by every delivery path: refresh the account's watched folders
 *  and diff each against its persisted per-folder baseline. A single implementation keeps the paths
 *  from drifting apart or double-notifying; [onInboxRefreshed] is the foreground complement (#50). */
object FetchAndNotify {

    /**
     * @param includeInbox false when something else owns the inbox (IMAP IDLE is live).
     */
    suspend fun run(
        context: Context,
        credentials: AccountCredentials,
        includeInbox: Boolean = true,
        includeExtras: Boolean = true,
        resetBaselines: Boolean = false,
    ) {
        // Outside the `try` because the catch below needs the store to say WHY the account was gone.
        val container = (context.applicationContext as Application).container
        val store = container.accountStore
        try {
            val extras = if (includeExtras) store.watchedFolders(credentials.id) else emptySet()
            val refreshes = container.mailRepository.refreshAccountFolders(
                credentials,
                extraFolderIds = extras,
                includeInbox = includeInbox,
                onMissing = { staleId ->
                    // Deleted/renamed server-side: the watch intent is gone — prune quietly.
                    store.setFolderWatched(credentials.id, staleId, watched = false)
                    NewMailNotifier.clear(context, credentials.id, staleId)
                },
            )
            // The inbox is always the first refresh when requested (see refreshAccountFolders).
            val inboxId = if (includeInbox) refreshes.firstOrNull()?.mailboxId else null
            if (inboxId != null) NewMailNotifier.migrateLegacyBaseline(context, credentials.id, inboxId)
            val unarchiveOnReply = container.settingsRepository.unarchiveOnReply.first()
            // ONE budget for this ACCOUNT's whole pass, built here and not inside the loop: the
            // diff runs once per folder, so a per-folder budget would give an account watching
            // three extras four times the reads on one radio wake-up.
            val previewBudget = NotificationPreviews.PreviewBudget(System::currentTimeMillis)
            refreshes.forEach { folder ->
                // FIRST, and it leaves the folder entirely alone: null is the refresh saying its
                // page cannot state what this folder holds, and the only thing this iteration would
                val baselineIds = folder.baselineIds ?: return@forEach
                val isInbox = folder.mailboxId == inboxId
                val folderName = if (isInbox) null else folder.name
                val hasBaseline = NewMailNotifier.hasBaseline(context, credentials.id, folder.mailboxId)
                // #50 (opt-out, on by default): BEFORE this pass advances or reseeds the inbox
                // baseline, pull the archived members of threads that just received genuinely-new
                //
                // Best-effort, but NOT for a sign-out: the guards inside
                // `unarchiveThreadsOnReply` throw a CancellationException `runCatching` would catch
                // like any failure, and this pass would then write a baseline under the removed id.
                val returned = if (isInbox && hasBaseline && unarchiveOnReply) {
                    val threads = NewMailNotifier.newSince(context, credentials.id, folder.mailboxId, folder.emails)
                        .mapNotNull { it.threadId }
                        .toSet()
                    runCatching { container.mailRepository.unarchiveThreadsOnReply(credentials, threads) }
                        .rethrowIfCancelled()
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                // What the folder REMEMBERS: everything the pass read, plus the members just
                // re-filed. The two sets are different sizes on purpose.
                val remembered = baselineIds + returned.map { it.id }
                if (seedsSilently(resetBaselines, isInbox, hasBaseline)) {
                    // First sight of a folder, or an explicit reset: seed silently.
                    NewMailNotifier.seed(context, credentials.id, folder.mailboxId, remembered)
                } else {
                    // The folder's departures ride along, so a message deleted from another client
                    // loses its banner (#134); its preview sources ride along too, the opening line
                    // having to be READ on IMAP. Both empty on the paths that have neither.
                    NewMailNotifier.notifyDiff(
                        context, credentials, folder.mailboxId, folderName, folder.emails + returned, remembered,
                        folder.departedIds,
                        folder.previewSources,
                        previewBudget,
                    )
                }
            }
        } catch (gone: AccountGoneException) {
            // The refusal MailRepository's guards throw when the account this pass writes for was
            // signed out mid-flight. It arrives as a cancellation, and every caller turns whatever
            // comes out of this call into a Log.w/Log.e with a stack, so every sign-out would print
            // an error for a gesture that SUCCEEDED (#130).
            //
            // It must stay the FIRST arm: AccountGoneException IS a CancellationException, so any
            // arm above naming that type makes this one unreachable. What the line claims is
            // [accountGoneCause]'s to decide, an unreadable blob also making `accounts()` answer
            // empty.
            android.util.Log.i(
                "FetchAndNotify",
                "background pass: ${accountGoneCause(credentials.id, store.accountsUnreadable(), passKind = "pass")}, dropped",
                gone,
            )
        }
        // The home-screen widget, redrawn once the pass is over (#112) — the case the app-scoped
        // collection cannot cover: a process the DELIVERY woke.
        //
        // Outside the try on purpose: the arm above claims a CancellationException is a sign-out,
        // so a cancelled redraw would be logged as one nobody asked for. `rethrowIfCancelled` is not
        // decoration — `runCatching` would turn the pass being cancelled into a normal return. With
        // no widget placed it costs ONE binder call.
        runCatching { UnreadWidgetDraw.refresh(context) }
            .rethrowIfCancelled()
            .onFailure { android.util.Log.w("FetchAndNotify", "unread widget not redrawn after the pass", it) }
        // The same, for the other cell: separate presence, read and draw, and a list missing the
        // message the banner just announced is a plainer failure than a stale number.
        runCatching { RecentMailWidgetDraw.refresh(context) }
            .rethrowIfCancelled()
            .onFailure { android.util.Log.w("FetchAndNotify", "latest-messages widget not redrawn after the pass", it) }
    }

    /**
     * Foreground counterpart of [run] for one just-refreshed inbox (#50). The UI list refresh syncs
     */
    suspend fun onInboxRefreshed(context: Context, credentials: AccountCredentials, inboxMailboxId: String) {
        if (credentials.protocol == MailProtocol.IMAP) return
        val container = (context.applicationContext as Application).container
        if (!container.settingsRepository.unarchiveOnReply.first()) return
        NewMailNotifier.migrateLegacyBaseline(context, credentials.id, inboxMailboxId)
        // The same bounded read the push pass gets: this hook writes the SAME baseline, so the two
        // must read and remember the same way.
        val read = container.mailRepository.notifyRead(credentials.id, inboxMailboxId)
        val emails = read.emails
        if (!NewMailNotifier.hasBaseline(context, credentials.id, inboxMailboxId)) {
            NewMailNotifier.seed(context, credentials.id, inboxMailboxId, read.baselineIds)
            return
        }
        val threads = NewMailNotifier.newSince(context, credentials.id, inboxMailboxId, emails)
            .mapNotNull { it.threadId }
            .toSet()
        // The try opens HERE because this is where a refusal can come from, and closes on everything
        // the re-file feeds: `notifyDiff` and `seed` both write the shared baseline.
        try {
            // Same best-effort wrapper as the background pass, and the same exception: a move the
            // server refuses must not cost the notification, but a sign-out must leave it (#121).
            val returned = runCatching { container.mailRepository.unarchiveThreadsOnReply(credentials, threads) }
                .rethrowIfCancelled()
                .getOrDefault(emptyList())
            val remembered = read.baselineIds + returned.map { it.id }
            if (container.accountStore.notificationsEnabled(credentials.id)) {
                NewMailNotifier.notifyDiff(context, credentials, inboxMailboxId, null, emails + returned, remembered)
            } else {
                NewMailNotifier.seed(context, credentials.id, inboxMailboxId, remembered)
            }
        } catch (gone: AccountGoneException) {
            // Caught HERE for what it STOPS: `InboxViewModel` already wraps both of its calls, so
            // a refusal let out would change nothing for it. What matters is that `notifyDiff` and
            android.util.Log.i(
                "FetchAndNotify",
                "foreground inbox: ${accountGoneCause(credentials.id, container.accountStore.accountsUnreadable(), passKind = "refresh")}, dropped",
                gone,
            )
        }
    }
}

/**
 * Does this folder take its content into the baseline silently, instead of announcing the diff? Two
 */
internal fun seedsSilently(resetBaselines: Boolean, isInbox: Boolean, hasBaseline: Boolean): Boolean =
    (resetBaselines && isInbox) || !hasBaseline
