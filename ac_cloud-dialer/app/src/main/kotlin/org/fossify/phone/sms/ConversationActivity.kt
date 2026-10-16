package org.fossify.phone.sms

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.extensions.dialerKitPalette
import org.fossify.phone.sms.mms.MmsService

/**
 * One conversation: the bubbles, the composer, the SIM chip. Opening it as the default app marks it
 * read and clears its notification; leaving it keeps an unsent text as a draft in the provider.
 */
class ConversationActivity : SimpleActivity() {
    private val repo by lazy { TelephonyRepo(this) }
    private val prefs by lazy { SmsPrefs(this) }
    private val sender by lazy { SmsSender(this, repo) }

    private var threadId = 0L
    private var addresses: List<String> = emptyList()
    private var title by mutableStateOf("")
    private var messages by mutableStateOf<List<SmsMessage>>(emptyList())
    private var draft by mutableStateOf("")
    private var pendingImage by mutableStateOf<String?>(null)
    private var isDefault by mutableStateOf(false)
    private var canRead by mutableStateOf(false)
    private var sims by mutableStateOf<List<SimInfo>>(emptyList())
    private var pickedSub by mutableStateOf<Int?>(null)
    private var askSim by mutableStateOf(false)
    private var draftLoaded = false

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) pendingImage = uri.toString() }
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshAccess() }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshAccess() }

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        threadId = intent.getLongExtra(EXTRA_THREAD, 0L)
        addresses = intent.getStringArrayListExtra(EXTRA_ADDRESSES).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE) ?: addresses.joinToString(", ")
        val palette = dialerKitPalette()
        val background = Color(getProperBackgroundColor())
        setContentView(ComposeView(this).apply {
            setContent {
                CloudKitTheme(palette) {
                    ConversationScreen(
                        title = title, messages = messages, isDefault = isDefault, canRead = canRead, sims = sims, selectedSim = selectedSim(),
                        draft = draft, pendingImage = pendingImage,
                        onDraftChange = { draft = it }, onSend = { onSend() }, onAttach = { imagePicker.launch("image/*") }, onClearAttachment = { pendingImage = null },
                        onPickSim = { pickedSub = it.subscriptionId }, onRetry = { retry(it) }, onDelete = { delete(it) }, onBack = { finish() },
                        onGrant = { permissionLauncher.launch(SmsAccess.permissions()) }, onMakeDefault = { SmsRole.requestIntent(this@ConversationActivity)?.let { roleLauncher.launch(it) } },
                        modifier = Modifier.background(background).systemBarsPadding(),
                    )
                    if (askSim) SimPickerDialog(sims, onPick = { askSim = false; pickedSub = it.subscriptionId; send(it.subscriptionId) }, onDismiss = { askSim = false })
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        try {
            contentResolver.registerContentObserver(Uri.parse("content://mms-sms/"), true, observer)
        } catch (e: SecurityException) {
        }
        refreshAccess()
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(observer)
        val text = draft
        if (isDefault && threadId > 0 && addresses.isNotEmpty()) {
            val first = addresses.first()
            Thread { repo.saveDraft(threadId, first, text) }.start()
        }
    }

    private fun refreshAccess() {
        isDefault = SmsRole.isDefault(this)
        canRead = SmsAccess.canRead(this)
        sims = sender.activeSims()
        reload()
    }

    private fun reload() {
        if (!canRead || threadId <= 0) return
        Thread {
            val loaded = repo.messages(threadId)
            val saved = if (!draftLoaded) repo.draftFor(threadId) else null
            if (isDefault && loaded.any { !it.read && it.type == SmsType.INBOX }) repo.markThreadRead(threadId)
            runOnUiThread {
                messages = loaded
                if (!draftLoaded) {
                    draftLoaded = true
                    if (draft.isEmpty() && saved != null) draft = saved
                }
            }
            AndroidSmsNotifier.cancel(this, threadId)
        }.start()
    }

    private fun choice(): SimChoice = SimSelection.choose(
        sims, picked = pickedSub, lastUsedInThread = prefs.lastSubscription(threadId),
        appPreferred = prefs.preferredSubscription, systemDefault = sender.systemDefaultSubscription(),
    )

    private fun selectedSim(): SimInfo? = (choice() as? SimChoice.Use)?.sim

    private fun onSend() {
        when (val c = choice()) {
            is SimChoice.Ask -> askSim = true
            is SimChoice.Use -> send(c.sim.subscriptionId)
            SimChoice.NoSim -> send(-1)
        }
    }

    private fun send(subscriptionId: Int) {
        val text = draft
        val image = pendingImage
        if (text.isBlank() && image == null) return
        val to = addresses
        draft = ""
        pendingImage = null
        if (subscriptionId >= 0) prefs.setLastSubscription(threadId, subscriptionId)
        Thread {
            if (image != null) {
                val ok = MmsService(this).send(to, text, Uri.parse(image), subscriptionId)
                if (!ok) runOnUiThread { toast(org.fossify.phone.R.string.sms_mms_failed) }
            } else {
                sender.send(to, text, subscriptionId)
            }
            repo.saveDraft(threadId, to.firstOrNull().orEmpty(), "")
            runOnUiThread { reload() }
        }.start()
    }

    private fun retry(message: SmsMessage) {
        Thread {
            sender.retry(message.id, selectedSim()?.subscriptionId)
            runOnUiThread { reload() }
        }.start()
    }

    private fun delete(message: SmsMessage) {
        Thread {
            repo.deleteMessage(message)
            runOnUiThread { reload() }
        }.start()
    }

    companion object {
        private const val EXTRA_THREAD = "thread_id"
        private const val EXTRA_ADDRESSES = "addresses"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, threadId: Long, addresses: List<String>, title: String): Intent =
            Intent(context, ConversationActivity::class.java)
                .putExtra(EXTRA_THREAD, threadId).putStringArrayListExtra(EXTRA_ADDRESSES, ArrayList(addresses)).putExtra(EXTRA_TITLE, title)
    }
}
