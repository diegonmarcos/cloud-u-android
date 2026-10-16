package org.fossify.phone.sms

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import org.fossify.commons.extensions.toast
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.extensions.dialerKitPalette
import org.fossify.phone.sms.mms.MmsService

/**
 * A new message, and the target of every "send a text" in the system: ACTION_SENDTO / ACTION_SEND
 * with sms:, smsto:, mms: or mmsto: (the role requires an activity that handles them). The
 * recipient picker searches the contacts; a typed number is accepted as it is.
 */
class SmsComposeActivity : SimpleActivity() {
    private val repo by lazy { TelephonyRepo(this) }
    private val prefs by lazy { SmsPrefs(this) }
    private val sender by lazy { SmsSender(this, repo) }
    private val lookup by lazy { ContactLookup(this) }

    private var recipients by mutableStateOf<List<String>>(emptyList())
    private var query by mutableStateOf("")
    private var all by mutableStateOf<List<Recipient>>(emptyList())
    private var draft by mutableStateOf("")
    private var pendingImage by mutableStateOf<String?>(null)
    private var isDefault by mutableStateOf(false)
    private var canRead by mutableStateOf(false)
    private var sims by mutableStateOf<List<SimInfo>>(emptyList())
    private var pickedSub by mutableStateOf<Int?>(null)
    private var askSim by mutableStateOf(false)

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) pendingImage = uri.toString() }
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshAccess() }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshAccess() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)
        val palette = dialerKitPalette()
        val background = Color(getProperBackgroundColor())
        setContentView(ComposeView(this).apply {
            setContent {
                CloudKitTheme(palette) {
                    NewMessageScreen(
                        recipients = recipients, query = query, suggestions = Recipients.suggest(all, query), draft = draft, pendingImage = pendingImage,
                        isDefault = isDefault, canRead = canRead, sims = sims, selectedSim = selectedSim(),
                        onQueryChange = { query = it }, onAddRecipient = { addRecipient(it) }, onRemoveRecipient = { i -> recipients = recipients.filterIndexed { n, _ -> n != i } },
                        onDraftChange = { draft = it }, onSend = { onSend() }, onAttach = { imagePicker.launch("image/*") }, onClearAttachment = { pendingImage = null },
                        onPickSim = { pickedSub = it.subscriptionId }, onBack = { finish() },
                        onGrant = { permissionLauncher.launch(SmsAccess.permissions()) }, onMakeDefault = { SmsRole.requestIntent(this@SmsComposeActivity)?.let { roleLauncher.launch(it) } },
                        modifier = Modifier.background(background).systemBarsPadding(),
                    )
                    if (askSim) SimPickerDialog(sims, onPick = { askSim = false; pickedSub = it.subscriptionId; send(it.subscriptionId) }, onDismiss = { askSim = false })
                }
            }
        })
        Thread { val list = lookup.recipients(); runOnUiThread { all = list } }.start()
    }

    override fun onResume() {
        super.onResume()
        refreshAccess()
    }

    private fun refreshAccess() {
        isDefault = SmsRole.isDefault(this)
        canRead = SmsAccess.canRead(this)
        sims = sender.activeSims()
    }

    /** The recipients, text and image an incoming SENDTO / SEND carries. */
    @Suppress("DEPRECATION")
    private fun readIntent(intent: Intent?) {
        intent ?: return
        val data: Uri? = intent.data
        if (data != null) {
            recipients = RespondViaMessageService.recipientsOf(data)
            data.getQueryParameter("body")?.let { draft = it }
        }
        intent.getStringExtra("sms_body")?.let { draft = it }
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { draft = it }
        intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.let { recipients = recipients + it }
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { pendingImage = it.toString() }
    }

    private fun addRecipient(number: String) {
        val n = number.trim()
        if (n.isNotEmpty() && recipients.none { Recipients.digits(it) == Recipients.digits(n) }) recipients = recipients + n
        query = ""
    }

    private fun choice(): SimChoice = SimSelection.choose(
        sims, picked = pickedSub, appPreferred = prefs.preferredSubscription, systemDefault = sender.systemDefaultSubscription(),
    )

    private fun selectedSim(): SimInfo? = (choice() as? SimChoice.Use)?.sim

    private fun onSend() {
        // A number typed but not yet added counts as a recipient.
        if (query.isNotBlank()) addRecipient(query)
        if (recipients.isEmpty()) return
        when (val c = choice()) {
            is SimChoice.Ask -> askSim = true
            is SimChoice.Use -> send(c.sim.subscriptionId)
            SimChoice.NoSim -> send(-1)
        }
    }

    private fun send(subscriptionId: Int) {
        val to = recipients
        val text = draft
        val image = pendingImage
        Thread {
            val threadId = repo.threadIdFor(to.toSet())
            if (subscriptionId >= 0 && threadId > 0) prefs.setLastSubscription(threadId, subscriptionId)
            val ok = if (image != null || to.size > 1) {
                // several recipients in one message, or a picture: MMS
                if (image != null || text.isNotBlank()) MmsService(this).send(to, text, image?.let(Uri::parse), subscriptionId) else false
            } else {
                sender.send(to, text, subscriptionId).isNotEmpty()
            }
            runOnUiThread {
                if (ok) {
                    startActivity(ConversationActivity.intent(this, threadId, to, to.joinToString(", ") { lookup.nameOf(it) ?: it }))
                    finish()
                } else {
                    toast(org.fossify.phone.R.string.sms_mms_failed)
                }
            }
        }.start()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, SmsComposeActivity::class.java)
    }
}
