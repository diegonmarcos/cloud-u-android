package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.emailKey
import app.sterna.core.jmap.model.Email

/** One selected message a bulk action is about to act on, and whether its folder can be believed.
 *  [folderTrusted] is false for a row only ever drawn on screen — a search hit, whose `mailboxId`
 * was frozen at crawl time. A non-empty [Email.mailboxId] is not evidence. */
internal data class SelectionTarget(val email: Email, val folderTrusted: Boolean)

/** The selection turned into rows to act on, plus the keys nothing could be found for. [unresolved]
 *  must be counted as attempted and failed, or a batch that reached nothing says nothing. */
internal data class SelectionTargets(
    val targets: List<SelectionTarget>,
    val unresolved: Set<EmailKey>,
)

/** Which row every selected key stands for: the cached one when the local table has it, else the
 * row the list is drawing, else nothing. The walk is over [keys], not the cache query's answer:
 *  a search hit often has no row in `emails`, and iterating the answer skips it in silence. */
internal fun resolveSelectionTargets(
    keys: Set<EmailKey>,
    cached: List<Email>,
    displayed: List<Email>?,
): SelectionTargets {
    val cachedByKey = cached.associateBy { it.emailKey() }
    val displayedByKey = displayed.orEmpty().associateBy { it.emailKey() }
    val targets = mutableListOf<SelectionTarget>()
    val unresolved = LinkedHashSet<EmailKey>()
    for (key in keys) {
        val fresh = cachedByKey[key]
        val drawn = displayedByKey[key]
        when {
            fresh != null -> targets += SelectionTarget(fresh, folderTrusted = true)
            drawn != null -> targets += SelectionTarget(drawn, folderTrusted = false)
            else -> unresolved += key
        }
    }
    return SelectionTargets(targets, unresolved)
}
