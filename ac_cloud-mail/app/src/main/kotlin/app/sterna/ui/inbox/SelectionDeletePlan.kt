package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Email

/** How a bulk delete splits: what is permanently [destroy]ed, what is [move]d to the Trash, and
 *  what is [untreated] — deleted nowhere, because either would be a guess and the wrong guess
 *  destroys mail. */
internal data class SelectionDeletePlan(
    val destroy: List<Email>,
    val move: List<Email>,
    val untreated: List<Email>,
)

/** Who gets destroyed and who gets moved, decided without ever reading an untrusted row's folder:
 *  `deleteWouldDestroy` answers from the row's own `mailboxId`, so it is asked about trusted rows
 * only. With no Trash the destroy would rest on a supposition: the row goes to [untreated]. */
internal suspend fun planSelectionDelete(
    targets: List<SelectionTarget>,
    hasTrash: suspend (Email) -> Boolean,
    wouldDestroy: suspend (Email) -> Boolean,
): SelectionDeletePlan {
    val destroy = mutableListOf<Email>()
    val move = mutableListOf<Email>()
    val untreated = mutableListOf<Email>()
    val trashByAccount = mutableMapOf<String?, Boolean>()
    for (target in targets) {
        val email = target.email
        when {
            target.folderTrusted -> if (wouldDestroy(email)) destroy += email else move += email
            trashByAccount.getOrPut(email.accountId) { hasTrash(email) } -> move += email
            else -> untreated += email
        }
    }
    return SelectionDeletePlan(destroy, move, untreated)
}
