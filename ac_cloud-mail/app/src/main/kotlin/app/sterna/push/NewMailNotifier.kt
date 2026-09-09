package app.sterna.push

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.FolderRefresh
import app.sterna.core.data.mail.PreviewSource
import app.sterna.core.data.mail.announceableAt
import app.sterna.core.data.mail.notifyFloor
import app.sterna.core.data.settings.NotificationContent
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.flow.first
import java.util.Calendar

/**
 * Notifies for newly arrived mail against a PERSISTED per-folder baseline (the ids already
 */
object NewMailNotifier {
    private const val PREFS = "push_baselines"
    private const val VERSION_KEY = "baseline_version"
    // v2: folder syncs went uncollapsed, so the first full re-query caches every thread member.
    private const val BASELINE_VERSION = 2

    private fun prefs(context: Context): SharedPreferences {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // On the version bump, wipe the stale baselines once so each folder reseeds silently
        // instead of announcing days-old members as new mail. One arrival in that window is lost.
        if (prefs.getInt(VERSION_KEY, 1) < BASELINE_VERSION) {
            prefs.edit().clear().putInt(VERSION_KEY, BASELINE_VERSION).apply()
        }
        return prefs
    }

    /** Baseline key for one account folder. Account ids are UUIDs, so ':' is unambiguous. */
    private fun key(accountId: String, mailboxId: String) = "$accountId:$mailboxId"

    /** Key of a baseline's last-pass timestamp. Disjoint from [key] ("ts" is no UUID). */
    private fun tsKey(accountId: String, mailboxId: String) = "ts:" + key(accountId, mailboxId)

    /** Move a pre-multi-folder baseline (bare accountId key, inbox-only) onto its per-folder key, so
     *  the app update costs neither a duplicate nor a missed notification. Call before any diff. */
    fun migrateLegacyBaseline(context: Context, accountId: String, inboxId: String) {
        val prefs = prefs(context)
        if (!prefs.contains(accountId) || prefs.contains(key(accountId, inboxId))) return
        prefs.edit()
            .putStringSet(key(accountId, inboxId), prefs.getStringSet(accountId, null).orEmpty())
            .remove(accountId)
            .apply()
    }

    /**
     * Reset a folder's baseline to [baselineIds] without notifying (first sight of a folder, or when
     */
    fun seed(context: Context, accountId: String, mailboxId: String, baselineIds: Collection<String>) {
        prefs(context).edit()
            .putStringSet(key(accountId, mailboxId), baselineIds.toSet())
            .putLong(tsKey(accountId, mailboxId), System.currentTimeMillis())
            .apply()
    }

    /** True once [seed] or [notifyDiff] has recorded a baseline for this folder. */
    fun hasBaseline(context: Context, accountId: String, mailboxId: String): Boolean =
        prefs(context).contains(key(accountId, mailboxId))

    /** Drop all of an account's baselines (sign-out), including the legacy bare key. */
    fun clear(context: Context, accountId: String) {
        val prefs = prefs(context)
        val edit = prefs.edit().remove(accountId)
        prefs.all.keys
            .filter { it.startsWith("$accountId:") || it.startsWith("ts:$accountId:") }
            .forEach { edit.remove(it) }
        edit.apply()
    }

    /** Drop one folder's baseline (folder deleted or no longer watched). */
    fun clear(context: Context, accountId: String, mailboxId: String) {
        prefs(context).edit()
            .remove(key(accountId, mailboxId))
            .remove(tsKey(accountId, mailboxId))
            .apply()
    }

    /** Re-key baselines after an IMAP rename (ids are folder paths there) — the folder and its
     *  subfolders. A false positive re-keys to an unused key, which the next pass reseeds. */
    fun rename(context: Context, accountId: String, oldMailboxId: String, newMailboxId: String) {
        val prefs = prefs(context)
        val oldKey = key(accountId, oldMailboxId)
        val newKey = key(accountId, newMailboxId)
        val edit = prefs.edit()
        prefs.all.keys
            .filter {
                val bare = it.removePrefix("ts:")
                bare == oldKey || bare.startsWith("$oldKey/") || bare.startsWith("$oldKey.")
            }
            .forEach { k ->
                if (k.startsWith("ts:")) {
                    edit.putLong("ts:" + newKey + k.removePrefix("ts:").removePrefix(oldKey), prefs.getLong(k, 0L))
                } else {
                    prefs.getStringSet(k, null)?.let { edit.putStringSet(newKey + k.removePrefix(oldKey), it) }
                }
                edit.remove(k)
            }
        edit.apply()
    }

    /**
     * The subset of [emails] genuinely new to this folder: not in its baseline, and past the age
     */
    fun newSince(context: Context, accountId: String, mailboxId: String, emails: List<Email>): List<Email> {
        val known = prefs(context).getStringSet(key(accountId, mailboxId), null).orEmpty()
        val lastPass = prefs(context).getLong(tsKey(accountId, mailboxId), 0L)
        // The floor is a pure function shared with the read that produces [emails]: the row set
        // handed here has to contain everything this predicate can let through.
        val floor = notifyFloor(lastPass)
        val selfMoved = (context.applicationContext as Application).container.mailRepository.recentLocalMoves
        return emails.filter {
            it.id !in known && receivedAfter(it, floor) && EmailKey(accountId, it.id) !in selfMoved
        }
    }

    /**
     * Post notifications for a folder's messages not in its baseline, then advance it. [emails] is
     */
    suspend fun notifyDiff(
        context: Context,
        credentials: AccountCredentials,
        mailboxId: String,
        folderName: String?,
        emails: List<Email>,
        baselineIds: Collection<String>,
        departedIds: List<String> = emptyList(),
        previewSources: Map<String, PreviewSource> = emptyMap(),
        previewBudget: NotificationPreviews.PreviewBudget =
            NotificationPreviews.PreviewBudget(System::currentTimeMillis),
    ) {
        // One notification per conversation: the uncollapsed sync hands us every new member of a
        // thread, so a reply burst would fire one per message. The whole burst still enters the
        // baseline below, so a skipped member cannot resurface as "new".
        val newMail = newSince(context, credentials.id, mailboxId, emails)
            .filter { !it.isSeen }
            .groupBy { it.threadId ?: it.id }
            .map { (_, members) -> members.maxBy { it.receivedAt.orEmpty() } }
        // #19: a message we announced that has since been read, here or elsewhere, is cleared.
        val active = Notifications.activeChildIds(context, credentials.id)
        val readIds = emails
            .filter { it.isSeen && Notifications.isChildActive(active, credentials.id, it.id) }
            .map { it.id }
        // #134: a message deleted from another client is gone from this folder, yet its banner
        // stayed on screen. The ids come from what the SERVER said left the folder, never from a
        val candidates = Notifications.departuresToCancel(active, credentials.id, departedIds, emails.map { it.id })
        // The confirmation, from the layer that talks to the server. It confirms NOTHING when it
        // cannot ask and does not throw: leaving a banner up is survivable, and this pass has new
        // mail to announce.
        val repository = (context.applicationContext as Application).container.mailRepository
        val departed = repository.confirmDepartures(credentials, mailboxId, candidates)
        if (newMail.isNotEmpty() || readIds.isNotEmpty() || departed.isNotEmpty()) {
            val (silent, content) = options(context)
            // Who announces this batch, decided BEFORE anything is posted (#56). Hoisted into a
            // `val` because the SAME set is what the summary must decide on at the bottom of this
            // pass — recomputing it there would read a shade the cancels have not reached (#134).
            val live = Notifications.liveChildIdsAfter(active, credentials.id, readIds + departed, newMail.map { it.id })
            val summarised = Notifications.summaryShownFor(live.size)
            // The openings are read HERE, in one pre-pass, BEFORE the first notification. A
            // round trip between two posts would put an exception mid-batch and skip the [seed]
            // below: the next pass would re-announce every message, and a re-announcement MOVES
            // MAIL server-side. [NotificationPreviews] therefore throws nothing.
            NotificationPreviews.postGatheredFirst(
                newMail,
                keyOf = { it.id },
                gather = {
                    NotificationPreviews.gather(
                        // Each argument on its own line and each one pinned: `content` makes the
                        // pre-pass conditional on the reader having asked for a body, `newMail`
                        // makes it read the mail being announced. Both would compile if replaced.
                        content,
                        newMail.map { it.id },
                        previewSources,
                        // The account pass's own budget, so this folder does not start afresh.
                        previewBudget,
                        numbering = { repository.recordedNumbering(credentials.id, mailboxId) },
                        fetch = { source, budgetMs ->
                            repository.notificationPreview(credentials, mailboxId, source, budgetMs)
                        },
                    )
                },
                // [mailboxId] is the folder this pass is for, so the tap can put the list there
                // (#91) — passed for the inbox too, where [folderName] is deliberately null. The
                // gathered opening travels ON the message; `?: email.preview` leaves JMAP's own
                // preview alone.
                post = { email, preview ->
                    Notifications.notifyNewMail(
                        context,
                        email.copy(preview = preview ?: email.preview),
                        credentials.id,
                        silent,
                        folderName,
                        mailboxId,
                        // The privacy setting this pass read, on its own line because a positional
                        // argument is invisible to anything but a whole-line pin (the class of #84).
                        content,
                        summarised,
                    )
                },
            )
            readIds.forEach { Notifications.cancelChild(context, credentials.id, it) }
            departed.forEach { Notifications.cancelChild(context, credentials.id, it) }
            // Rebuilt from ALL active children so successive passes accumulate. It re-posts, so it
            // would ring: only when this pass actually brought new mail.
            Notifications.updateGroupSummary(
                context,
                credentials.id,
                credentials.username,
                silent = silent || newMail.isEmpty(),
                // The set computed BEFORE anything was posted or cancelled, never recomputed here.
                expectedChildIds = live,
                // What this pass took down, and only that. Everything else stays in `children` and
                // is put back if the summary falls: a snooze wake-up posting into this group in the
                // seconds between the shade's read and here would otherwise be cascaded away.
                cancelledChildIds = Notifications.childIdsOf(credentials.id, readIds + departed),
            )
        }
        seed(context, credentials.id, mailboxId, baselineIds)
    }

    /** Whether [email] was received at or after [floorMs] (lenient: unknown dates pass). Shared
     *  with the read that produces the candidates — see [app.sterna.core.data.mail.announceableAt]. */
    private fun receivedAfter(email: Email, floorMs: Long): Boolean = announceableAt(email.receivedAt, floorMs)

    /** The user settings every posted mail notification must obey: quiet hours, and how much the
     *  notification may reveal. */
    data class Options(val silent: Boolean, val content: NotificationContent)

    /** Reads both notification settings for the moment the notification is posted. Shared with the
     *  snooze wake-up (#84) so the two cannot drift: it used to post with the function defaults and
     *  ignored both quiet hours and the content setting. */
    suspend fun options(context: Context): Options {
        val settings = (context.applicationContext as Application).container.settingsRepository
        return Options(quietHoursActive(context), settings.notificationContent.first())
    }

    /** Whether new mail should be posted silently right now (quiet-hours window). */
    private suspend fun quietHoursActive(context: Context): Boolean {
        val settings = (context.applicationContext as Application).container.settingsRepository
        if (!settings.quietHoursEnabled.first()) return false
        val now = Calendar.getInstance()
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return SettingsRepository.isWithinQuietHours(
            minutes,
            settings.quietHoursStart.first(),
            settings.quietHoursEnd.first(),
        )
    }
}
