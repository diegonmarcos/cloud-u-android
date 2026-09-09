package app.sterna.ui.sender

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.R
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.accountAddresses
import app.sterna.core.data.getOrElseUnlessCancelled
import app.sterna.core.data.filter.BlockOutcome
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.addBlockRule
import app.sterna.core.data.filter.alreadyBlocked
import app.sterna.core.data.filter.blockableSender
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.FilterRulesState
import app.sterna.core.data.mail.ImapMailService
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.SenderVolume
import app.sterna.core.jmap.model.Mailbox
import app.sterna.ui.inbox.BulkOutcome
import app.sterna.ui.inbox.bulkOutcome
import app.sterna.ui.inbox.mailboxFilePath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The header's number, from the LINES the screen prints — never a second `COUNT(*)`: messages
 *  with no usable sender make no line, so the two numbers would not reconcile on screen. */
internal fun cachedTotal(rows: List<SenderVolume>): Int = rows.sumOf { it.total }

/** The path a server-side rule must name to file mail into this account's Trash, or null when no
 *  Trash can be named with certainty — the answer that HIDES the gesture. Read from the cached
 *  folder list, so the screen costs no network call. */
internal fun trashFilePath(mailboxes: List<Mailbox>): String? =
    mailboxes.firstOrNull { it.role == "trash" }?.let { mailboxFilePath(it, mailboxes) }

/**
 * Whether the "future mail to Trash" entry may appear, from the account's script read ([state],
 */
internal fun canBlockSender(state: FilterRulesState?, trashPath: String?): Boolean =
    blockAvailability(state, trashPath) == BlockAvailability.OFFERED

/**
 * The four answers behind [canBlockSender], kept apart because only one can be explained.
 */
internal enum class BlockAvailability { OFFERED, NO_TRASH, UNSUPPORTED, FOREIGN_SCRIPT }

internal fun blockAvailability(state: FilterRulesState?, trashPath: String?): BlockAvailability = when {
    trashPath == null -> BlockAvailability.NO_TRASH
    state is FilterRulesState.Unsupported -> BlockAvailability.UNSUPPORTED
    state is FilterRulesState.Loaded && state.foreignActiveScript -> BlockAvailability.FOREIGN_SCRIPT
    else -> BlockAvailability.OFFERED
}

/** The note at the foot of the list, or null. One case gets one, [BlockAvailability
 *  .FOREIGN_SCRIPT], and it carries no button: taking a running script over is what the Filters
 *  screen warns about in red, and a button here would be the dangerous half without the warning. */
internal fun blockNoteRes(availability: BlockAvailability): Int? =
    if (availability == BlockAvailability.FOREIGN_SCRIPT) R.string.sender_volume_foreign_script else null

internal enum class SenderScreenBody { NO_ACCOUNT, LOADING, FAILED, EMPTY, ROWS }

/** What the screen shows for [state]. The ORDER is the content: LOADING before anything a count
 *  could say, and FAILED before EMPTY — "no mail stored on this phone" is a count, and a read that
 *  threw made none. */
internal fun screenBody(state: MailBySenderUiState): SenderScreenBody = when {
    state.noAccount -> SenderScreenBody.NO_ACCOUNT
    state.loading -> SenderScreenBody.LOADING
    state.loadError != null -> SenderScreenBody.FAILED
    state.rows.isEmpty() -> SenderScreenBody.EMPTY
    else -> SenderScreenBody.ROWS
}

internal data class SenderMenuEntry(val action: SenderAction, val labelRes: Int, val enabled: Boolean)

internal enum class SenderAction { SEARCH, DELETE, BLOCK }

/**
 * What one row's overflow menu holds, given what the account can do, whether the script already
 */
internal fun senderMenuEntries(
    canDelete: Boolean,
    canBlock: Boolean,
    blocked: Boolean,
    working: Boolean,
    address: String,
    ownAddresses: List<String>,
): List<SenderMenuEntry> = buildList {
    // Always there, always tappable: it writes nothing, and the screen stays on the back stack so
    // the batch in flight outlives the navigation.
    add(SenderMenuEntry(SenderAction.SEARCH, R.string.sender_volume_search, enabled = true))
    if (canBlock && blockableSender(address, ownAddresses)) {
        add(
            SenderMenuEntry(
                SenderAction.BLOCK,
                if (blocked) R.string.sender_volume_block_done else R.string.sender_volume_block,
                enabled = !blocked && !working,
            ),
        )
    }
    if (canDelete) {
        add(SenderMenuEntry(SenderAction.DELETE, R.string.sender_volume_delete, enabled = !working))
    }
}

/** The move-backs an Undo has to make: for each confirmed id, the folder it came from and the one
 *  it went to. An id whose source is unknown is dropped, and so is one whose source IS the
 *  destination — "restoring" it would report a success while the mail stays put. */
internal fun restoreTargets(
    succeeded: Set<String>,
    sources: Map<String, String?>,
    dest: String?,
): List<MailRepository.RestoreTarget> = sources.entries
    .filter { (id, source) -> id in succeeded && source != null && source != dest }
    .map { (id, source) -> MailRepository.RestoreTarget(id, source!!, dest) }

/** A delete being confirmed: the sender, and the ids MATERIALISED when the dialog opened, so the
 *  announcement and the delete are one list read once. Deliberately no `total` field: a dialog that
 *  cannot reach the row's staler number cannot print it. */
data class PendingDelete(
    val sender: SenderVolume,
    val ids: List<String>,
    /**
     * The IMAP numbering each source folder was recorded under WHEN THE IDS WERE READ (#99).
     */
    val numbering: Map<String, Long?> = emptyMap(),
)

data class MailBySenderUiState(
    val loading: Boolean = true,
    val noAccount: Boolean = false,
    val accountLabel: String = "",
    val rows: List<SenderVolume> = emptyList(),
    val total: Int = 0,
    /** Whether the row menu offers "delete these messages" — true on every account, the batch
     * resolving the Trash by role, by name, or creating it. Not the Sieve gesture's question:
     *  filing FUTURE mail needs a Trash the app can NAME. */
    val canDelete: Boolean = false,
    val canBlock: Boolean = false,
    /** The note under the last row, or null while the script has not been read: nothing is known
     *  yet, so there is nothing to explain. */
    val blockNote: Int? = null,
    /** The rules the account's script carries, as last read — for the duplicate check. */
    val rules: List<FilterRule> = emptyList(),
    /** The account's own addresses, so a row that IS the account does not offer to file its own
     *  mail away. Read from the store with the counts: an empty list is a menu that offers the
     *  gesture on oneself. */
    val ownAddresses: List<String> = emptyList(),
    /** Why the counting query could not be read. A read that throws must not leave [loading] set,
     *  nor fall through to the empty body, which claims a count. */
    val loadError: String? = null,
    /** A batch is on its way to the server; the row menus are closed to a second one. */
    val working: Boolean = false,
    val pending: PendingDelete? = null,
)

/** The state a confirmed delete leaves behind: the batch on its way, and the dialog GONE. Raising
 *  `working` without clearing `pending` leaves the dialog over a batch in flight, and its button
 *  then returns at the `working` guard — a second tap that does and says nothing. One function, so
 *  that half cannot be dropped without a test noticing. */
internal fun deleteStarted(state: MailBySenderUiState): MailBySenderUiState =
    state.copy(working = true, pending = null)

/** An offer to move a just-deleted batch back, and how many messages the announcement may claim.
 *  The two differ on purpose: [targets] holds only what can be put back, [deleted] what the server
 *  confirmed it moved. */
data class SenderUndo(
    val credentials: AccountCredentials,
    val targets: List<MailRepository.RestoreTarget>,
    val deleted: Int,
)

/** How many messages the "deleted" message may claim: the ids the server CONFIRMED it moved —
 *  not the batch that was sent, and not the Undo's targets. */
internal fun deletedCount(result: MailRepository.BulkResult): Int = result.succeeded.size

/** What a finished delete has to SAY, as a string resource, or null. [attempted] and [failed] go to
 *  [bulkOutcome], the inbox's own decision, so the two screens cannot drift apart. */
internal fun deleteMessageRes(attempted: Int, failed: Int): Int? = when (bulkOutcome(attempted, failed)) {
    BulkOutcome.NONE -> null
    BulkOutcome.PARTIAL -> R.string.status_action_partly_failed
    BulkOutcome.TOTAL -> R.string.status_action_failed
}

/** Backs "Mail by sender": what this phone holds, per sender, for the CURRENT account — like the
 *  Filters screen whose rules it writes into. A global screen would have to disambiguate the sender
 *  who writes to two accounts before it could name one in a rule. */
class MailBySenderViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = application.container.mailRepository
    private val store = application.container.accountStore

    private val _state = MutableStateFlow(MailBySenderUiState())
    val state = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _undo = MutableStateFlow<SenderUndo?>(null)
    val undo = _undo.asStateFlow()

    init { load() }

    /** Read the numbers, then — separately, and only to decide what the row menu may offer — the
     *  account's filter rules. The counts land immediately; the rules are a network round-trip, so
     *  they follow instead of holding the screen up. */
    fun load() {
        val credentials = store.load()
        if (credentials == null) {
            _state.value = MailBySenderUiState(loading = false, noAccount = true)
            return
        }
        _state.value = MailBySenderUiState(loading = true, accountLabel = store.accountLabel())
        viewModelScope.launch {
            // Protected, or a throw between `loading` going up and the line that takes it down
            // leaves the spinner for the life of the ViewModel. Not getOrNull: a cancelled read is
            // not a failed one and must draw no error.
            val counted = runCatching { repo.senderVolumes(credentials.id) }
                .getOrElseUnlessCancelled { failure ->
                    _state.value = MailBySenderUiState(
                        loading = false,
                        accountLabel = store.accountLabel(),
                        loadError = failure.message ?: failure.toString(),
                    )
                    return@launch
                }
            val mailboxes = runCatching { repo.observeMailboxes(credentials.id).first() }
                .getOrElseUnlessCancelled { emptyList() }
            val trashPath = trashFilePath(mailboxes)
            _state.value = MailBySenderUiState(
                loading = false,
                accountLabel = store.accountLabel(),
                rows = counted,
                total = cachedTotal(counted),
                // Offered on every account, the bin being resolved by role, by name, or created.
                // Not the Sieve gesture below, which needs the Trash to be NAMEABLE.
                canDelete = true,
                canBlock = false,
                rules = emptyList(),
                // Written with the rows, not with the script: the row that is the account itself
                // must be refused from the moment it is drawn.
                ownAddresses = ownAddresses(credentials),
            )
            val loaded = runCatching { repo.loadFilterRules(credentials) }
                .getOrElseUnlessCancelled { null }
            _state.value = _state.value.copy(
                canBlock = canBlockSender(loaded, trashPath),
                // From the SAME two readings the availability is decided from, so the note and the
                // missing entry cannot describe different accounts.
                blockNote = blockNoteRes(blockAvailability(loaded, trashPath)),
                rules = (loaded as? FilterRulesState.Loaded)?.rules.orEmpty(),
            )
        }
    }

    /** Every address that IS this account: its send-as identities plus its login. A linked
     *  sub-account resolves through its login (#31). */
    private fun ownAddresses(credentials: AccountCredentials): List<String> =
        accountAddresses(store.identities(credentials.id), credentials.username)

    /** Open the confirmation for [sender], reading the ids it will act on NOW, so the dialog
     *  announces the size of the very list the delete receives. */
    fun askDelete(sender: SenderVolume) {
        val credentials = store.load() ?: return
        if (_state.value.working) return
        viewModelScope.launch {
            val ids = repo.senderMessageIds(credentials.id, sender.email)
            // Frozen HERE, with the ids, not at the confirmation: see [PendingDelete.numbering].
            val numbering = ids.mapNotNull { ImapMailService.mailboxOf(it) }.distinct()
                .associateWith { repo.recordedUidValidity(credentials, it) }
            _state.value = _state.value.copy(pending = PendingDelete(sender, ids, numbering))
        }
    }

    fun cancelDelete() { _state.value = _state.value.copy(pending = null) }

    /** Move the confirmed batch to the Trash, and offer to put it back. It acts on
     *  [PendingDelete.ids] — the list the dialog counted, not a fresh read — through `deleteAll`,
     *  which moves to Trash and never destroys. */
    fun confirmDelete() {
        val pending = _state.value.pending ?: return
        val credentials = store.load() ?: return
        if (_state.value.working) return
        _state.value = deleteStarted(_state.value)
        viewModelScope.launch {
            try {
                val ids = pending.ids
                // Captured BEFORE the delete: the rows are gone from the cache afterwards, and the
                // undo needs each message's source folder.
                val sources = repo.cachedEmailsByIds(ids.map { EmailKey(credentials.id, it) })
                    .associate { it.id to it.mailboxId }
                // The numbering arrives with the confirmation, as the ids do (#99).
                val result = runCatching { repo.deleteAll(credentials, ids, pending.numbering) }
                    .getOrElse { MailRepository.BulkResult(emptySet(), ids.toSet()) }
                val targets = restoreTargets(result.succeeded, sources, result.dest)
                // `ids` is what the repository was handed; the rejected set is what came back.
                deleteMessageRes(ids.size, result.failed.size)?.let {
                    _message.value = getApplication<Application>().getString(it)
                }
                _undo.value = if (targets.isEmpty()) {
                    null
                } else {
                    SenderUndo(credentials, targets, deletedCount(result))
                }
                load()
            } finally {
                // On EVERY way out. `cachedEmailsByIds` is unprotected on purpose, but the flag it
                // raised must not outlive a throw: left up, every row menu stays greyed.
                _state.value = _state.value.copy(working = false)
            }
        }
    }

    fun undoDelete() {
        val offer = _undo.value ?: return
        _undo.value = null
        viewModelScope.launch {
            val failed = runCatching { repo.restoreAll(offer.credentials, offer.targets) }
                .getOrElse { offer.targets.mapTo(mutableSetOf()) { t -> t.emailId } }
            if (failed.isNotEmpty()) {
                _message.value = getApplication<Application>().getString(R.string.status_action_failed)
            }
            load()
        }
    }

    fun dismissUndo() { _undo.value = null }

    fun clearMessage() { _message.value = null }

    /** Whether the script already sends [address] away, so no second identical rule is added. */
    fun isBlocked(address: String): Boolean = alreadyBlocked(_state.value.rules, address)

    /** Add the "future mail to Trash, marked read" rule for [sender], through [addBlockRule]: read
     * the rules, then save them with one more. `saveFilterRules` rewrites the whole script, so a
     *  save not preceded by a successful read deletes the account's filters instead. */
    fun blockSender(sender: SenderVolume) {
        val credentials = store.load() ?: return
        if (_state.value.working) return
        _state.value = _state.value.copy(working = true)
        viewModelScope.launch {
            try {
                val mailboxes = runCatching { repo.observeMailboxes(credentials.id).first() }
                    .getOrElseUnlessCancelled { emptyList() }
                val trashPath = trashFilePath(mailboxes)
                if (trashPath == null) {
                    _state.value = _state.value.copy(canBlock = false)
                    _message.value = getApplication<Application>().getString(R.string.status_action_failed)
                    return@launch
                }
                val outcome = addBlockRule(
                    address = sender.email,
                    trashFolder = trashPath,
                    load = { repo.loadFilterRules(credentials) },
                    save = { rules -> repo.saveFilterRules(credentials, rules) },
                )
                _message.value = getApplication<Application>().getString(
                    when (outcome) {
                        BlockOutcome.ADDED -> R.string.sender_volume_block_added
                        BlockOutcome.ALREADY_PRESENT -> R.string.sender_volume_block_done
                        BlockOutcome.FAILED -> R.string.status_action_failed
                    },
                )
                load()
            } finally {
                // Every way out, the early return included — see confirmDelete.
                _state.value = _state.value.copy(working = false)
            }
        }
    }
}
