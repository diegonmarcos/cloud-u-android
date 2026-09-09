package app.sterna.ui.outbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.db.OutboxEntity
import app.sterna.send.Outbox
import app.sterna.send.composeDraftOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** Backs the Outbox screen: lists queued/failed sends and offers retry, edit and delete. */
class OutboxViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = application.container.mailRepository
    private val sendOutbox = application.container.sendOutbox

    val items = repo.outboxFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList<OutboxEntity>(),
    )

    /**
     * The id of the item just staged for editing, so the screen can open compose on it — `null`
     */
    private val _readyToEdit = MutableStateFlow<Long?>(null)
    val readyToEdit: StateFlow<Long?> = _readyToEdit.asStateFlow()

    /** Re-queue an item for an immediate retry. */
    fun retry(id: Long) {
        viewModelScope.launch { repo.retryOutbox(id) }
    }

    /** Delete an item: cancel its worker and drop the row + its persistent attachments. */
    fun delete(id: Long) {
        Outbox.cancel(getApplication(), id)
        viewModelScope.launch { repo.deleteOutbox(id) }
    }

    /**
     * Reopen an item in compose for editing (#70): stage it into compose (re-staging IMAP
     */
    fun edit(id: Long) {
        viewModelScope.launch {
            val staging = File(getApplication<Application>().cacheDir, "outgoing")
            // takeOutboxForEdit throws rather than reopen an item whose attachment can't be read, so
            // the row and its durable files survive as a still-queued send. Swallow that here: the
            // edit simply doesn't open instead of crashing, and the item stays queued as it was.
            val draft = runCatching { repo.takeOutboxForEdit(id, staging) }
                .onFailure { android.util.Log.w("SternaOutbox", "couldn't reopen outbox item $id for edit", it) }
                .getOrNull() ?: return@launch
            // The mapping is a pure function ([composeDraftOf]) so a JVM test can execute it: what
            // it drops is not missing from the screen, it is CANCELLED — re-editing enqueues a new
            // row from the composer's state.
            sendOutbox.reopen(composeDraftOf(draft))
            _readyToEdit.value = id
        }
    }

    fun consumeEdit() {
        _readyToEdit.value = null
    }
}
