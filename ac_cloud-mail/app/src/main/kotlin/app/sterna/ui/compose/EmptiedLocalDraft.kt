package app.sterna.ui.compose

import app.sterna.mail.MessageDestroyWorker
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/*
 * Emptying a draft the phone kept for a SERVER draft it has not managed to replace yet (#95 × #69),
 * kept out of `ComposeViewModel` so a JVM test can run these decisions. A local row R names its server
 */

/**
 * What the emptying of a local row does about the server draft behind it. Two fields rather than one
 * nullable order: "nothing to destroy" and "kept on purpose, and said so" share the same null order.
 */
data class EmptiedLocalDraftPlan(
    /** The destroy to make durable, or null when nothing may be destroyed. */
    val order: MessageDestroyWorker.FolderDestroy?,
    /**
     * True when the server copy is deliberately KEPT because this phone never read all of it — and
     * the screen has to say so, or the app quietly does the opposite of what was asked.
     */
    val tellServerCopyKept: Boolean,
)

/**
 * The plan the emptying of a local row produces.
 */
fun planForEmptiedLocalDraft(
    replacedServerDraftId: String?,
    replacedServerDraftUidValidity: Long?,
    mailboxId: String?,
    bodyIsLossy: Boolean,
    addressingIsProvenEmpty: Boolean,
): EmptiedLocalDraftPlan {
    if (emptiedLocalDraftKeepsServerCopy(replacedServerDraftId, bodyIsLossy, addressingIsProvenEmpty)) {
        return EmptiedLocalDraftPlan(null, tellServerCopyKept = true)
    }
    val serverDraft = replacedServerDraftId ?: return EmptiedLocalDraftPlan(null, tellServerCopyKept = false)
    val folder = mailboxId?.takeIf { it.isNotEmpty() } ?: return EmptiedLocalDraftPlan(null, tellServerCopyKept = false)
    return EmptiedLocalDraftPlan(
        MessageDestroyWorker.FolderDestroy(folder, listOf(serverDraft), replacedServerDraftUidValidity),
        tellServerCopyKept = false,
    )
}

/**
 * Is the copy on the server KEPT by this gesture? Asked twice: the empty-save asks it afterwards to
 */
fun emptiedLocalDraftKeepsServerCopy(
    replacedServerDraftId: String?,
    bodyIsLossy: Boolean,
    addressingIsProvenEmpty: Boolean,
): Boolean = replacedServerDraftId != null && (bodyIsLossy || !addressingIsProvenEmpty)

/**
 * Who gets the phone's own draft row back once a DELETION asked for is over (#95 × #127): the row to
 */
fun localDraftLeaseAfterDelete(lease: String?, destroyed: Boolean): String? =
    if (destroyed) null else lease

/**
 * Make the destroy of the server draft DURABLE, take it off the phone, say what was kept, and only
 */
suspend fun destroyThenConsumeEmptiedLocalDraft(
    localDraftId: String,
    plan: EmptiedLocalDraftPlan,
    destroy: suspend (MessageDestroyWorker.FolderDestroy) -> Unit,
    evict: suspend (List<String>) -> Unit,
    consume: suspend (String) -> Unit,
    tell: suspend () -> Unit,
) {
    // The boundary, read BEFORE the shelter is entered: nothing destroyed yet, so a screen already
    // gone takes nothing with it and the draft survives on the phone.
    coroutineContext.ensureActive()
    withContext(NonCancellable) {
        plan.order?.let {
            destroy(it)
            evict(it.emailIds)
        }
        if (plan.tellServerCopyKept) tell()
        consume(localDraftId)
    }
}

/**
 * Whose credentials destroy the SERVER draft a reopened local row stands in front of: the account the
 */
fun <T : Any> credentialsDestroyingReplacedServerDraft(
    leasedUnderAccountId: String?,
    @Suppress("UNUSED_PARAMETER") composingAsAccountId: String?,
    lookup: (String) -> T?,
): T? = leasedUnderAccountId?.let(lookup)
