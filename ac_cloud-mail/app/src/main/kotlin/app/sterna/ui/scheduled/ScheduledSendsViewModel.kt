package app.sterna.ui.scheduled

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.R
import app.sterna.container
import app.sterna.core.data.db.ScheduledSendEntity
import app.sterna.core.data.mail.accountDepartureIsProven
import app.sterna.send.ScheduledSends
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class ScheduledSendsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = application.container.mailRepository

    val items = repo.scheduledSendsFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList<ScheduledSendEntity>(),
    )

    /**
     * The rows whose cancellation is still running, so a second tap on the same X cannot start a
     */
    private val inFlight: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun clearMessage() { _message.value = null }

    /**
     * Cancel a scheduled send: file the message back in Drafts, then drop its row and then its job —
     */
    fun cancel(id: Long) {
        val app = getApplication<Application>()
        if (!inFlight.add(id)) return
        app.container.appScope.launch {
            val report = try {
                cancelScheduledSend(
                    id = id,
                    row = { repo.scheduledSend(it) },
                    credentials = { app.container.accountStore.credentials(it) },
                    // A `null` from `credentials` is not proof: the store answers null for an
                    // unreadable secret too, and an unreadable blob makes `accounts()` empty. Both
                    // verdicts are read here; what they MEAN is decided in one executable place.
                    accountIsGone = {
                        accountDepartureIsProven(
                            accountsUnreadable = app.container.accountStore.accountsUnreadable(),
                            accountStillListed = app.container.accountStore.accounts().any { a -> a.id == it },
                        )
                    },
                    saveDraft = { creds, d ->
                        repo.saveDraft(
                            credentials = creds,
                            to = d.to,
                            subject = d.subject,
                            body = d.body,
                            // The styling the message carried, as the draft's html part (#131).
                            // Dropped here, a cancelled scheduled send is filed with its bold gone.
                            html = d.html,
                            cc = d.cc,
                            bcc = d.bcc,
                            inReplyTo = d.inReplyTo,
                            references = d.references,
                            // Nothing to replace, said out loud: naming the server draft this
                            // message was edited from would let the save DESTROY it, and the
                            // deposit flattens the HTML body. A duplicate in Drafts is
                            // recoverable, a destroyed original is not (#63).
                            replacesEmailId = null,
                            // …and the numbering that goes with it, explicit because the parameter
                            // has no default (#99), and because we aim at no original.
                            replacesUidValidity = null,
                            // No composer in this story: the row is read from the database and
                            // deposited whole. Explicit because the parameter has no default, and
                            // `true` here would silently refuse a genuinely empty body.
                            composerBodyWasLost = false,
                            // Only ever READ when `composerBodyWasLost` is true, which this route
                            // never is: it is answered from the text being deposited, the only text
                            // this route has ever had.
                            typedBodyIsBlank = d.body.isBlank(),
                            fromName = d.fromName,
                            fromEmail = d.fromEmail,
                            requestReceipt = d.requestReceipt,
                        )
                    },
                    dropWorker = { ScheduledSends.cancel(app, it) },
                    deleteRow = { repo.deleteScheduledSend(it) },
                )
            } finally {
                inFlight.remove(id)
            } ?: return@launch // already fired or already cancelled: nothing happened, say nothing
            _message.value = app.getString(
                when (report) {
                    ScheduledCancelReport.RETURNED_TO_DRAFTS -> R.string.scheduled_cancelled_to_drafts
                    ScheduledCancelReport.ACCOUNT_GONE -> R.string.scheduled_cancelled_no_account
                    ScheduledCancelReport.NOT_CANCELLED -> R.string.status_action_failed
                },
            )
        }
    }
}
