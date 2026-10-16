package org.fossify.phone.sms

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.fossify.commons.extensions.getBlockedNumbers
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.phone.activities.SimpleActivity
import java.util.UUID

/**
 * The state behind the SMS page of the main pager: the conversations, whether we are the default
 * app, and the two system prompts (runtime grants, the default-SMS role). A plain class holding
 * Compose state so the page, a View fragment hosting a ComposeView, can own it without a ViewModel.
 */
class SmsListController(private val activity: SimpleActivity) {
    private val repo = TelephonyRepo(activity)
    private val prefs = SmsPrefs(activity)
    private val contacts = ContactLookup(activity)

    var all by mutableStateOf<List<Conversation>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    var isDefault by mutableStateOf(SmsRole.isDefault(activity))
        private set
    var canRead by mutableStateOf(SmsAccess.canRead(activity))
        private set
    var showArchived by mutableStateOf(false)

    private val key = UUID.randomUUID().toString()
    private val roleLauncher: ActivityResultLauncher<android.content.Intent> =
        activity.activityResultRegistry.register("sms-role-$key", ActivityResultContracts.StartActivityForResult()) { refreshAccess() }
    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        activity.activityResultRegistry.register("sms-perm-$key", ActivityResultContracts.RequestMultiplePermissions()) { refreshAccess() }

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = reload()
    }
    private var observing = false

    fun start() {
        if (!observing) {
            try {
                activity.contentResolver.registerContentObserver(Uri.parse("content://mms-sms/"), true, observer)
                observing = true
            } catch (e: SecurityException) {
            }
        }
        refreshAccess()
    }

    fun stop() {
        if (observing) activity.contentResolver.unregisterContentObserver(observer)
        observing = false
    }

    fun dispose() {
        stop()
        roleLauncher.unregister()
        permissionLauncher.unregister()
    }

    fun refreshAccess() {
        isDefault = SmsRole.isDefault(activity)
        canRead = SmsAccess.canRead(activity)
        reload()
    }

    fun requestPermissions() = permissionLauncher.launch(SmsAccess.permissions())

    fun requestDefault() {
        SmsRole.requestIntent(activity)?.let { roleLauncher.launch(it) }
    }

    fun reload() {
        if (!canRead) {
            loaded = true
            all = emptyList()
            return
        }
        Thread {
            val addresses = repo.canonicalAddresses()
            val blocked = try { activity.getBlockedNumbers() } catch (e: Exception) { arrayListOf<org.fossify.commons.models.BlockedNumber>() }
            val built = Conversations.build(
                rows = repo.threads(),
                addressOf = { addresses[it] },
                nameOf = { contacts.nameOf(it) },
                unread = repo.unreadByThread(),
                pinned = prefs.pinned,
                archived = prefs.archived,
                drafts = repo.drafts(),
            ).filterNot { c -> c.addresses.isNotEmpty() && c.addresses.all { a -> blocked.isNotEmpty() && activity.isNumberBlocked(a, blocked) } }
            activity.runOnUiThread {
                all = built
                loaded = true
            }
        }.start()
    }

    /** What the list shows for [query]: the title / number / snippet matches, plus conversations holding the text in an older message. */
    fun visible(query: String, bodyHits: Set<Long>): List<Conversation> {
        val direct = Conversations.visible(all, showArchived, query)
        if (bodyHits.isEmpty()) return direct
        val have = direct.map { it.threadId }.toSet()
        return Conversations.order(direct + all.filter { it.archived == showArchived && it.threadId in bodyHits && it.threadId !in have })
    }

    fun searchBodies(query: String): Set<Long> = repo.searchThreadIds(query.trim())

    fun photoOf(address: String): String? = contacts.photoOf(address)

    fun togglePin(c: Conversation) { prefs.togglePinned(c.threadId); reload() }

    fun toggleArchive(c: Conversation) { prefs.setArchived(c.threadId, !c.archived); reload() }

    fun delete(c: Conversation) {
        Thread {
            repo.deleteThread(c.threadId)
            prefs.setArchived(c.threadId, false)
            activity.runOnUiThread { reload() }
        }.start()
    }

    fun openConversation(c: Conversation) {
        activity.startActivity(ConversationActivity.intent(activity, c.threadId, c.addresses, c.title))
    }

    fun newMessage() {
        activity.startActivity(SmsComposeActivity.intent(activity))
    }
}
