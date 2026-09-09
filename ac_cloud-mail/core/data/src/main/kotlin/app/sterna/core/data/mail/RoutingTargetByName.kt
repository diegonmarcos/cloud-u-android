package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Mailbox

/** Can one of the app's actions pick [mailbox] as a destination on the strength of its name alone?
 *  The invariant: a folder the app is willing to file mail into stays visible — Stalwart answers
 *  `Archive` with `role: null` and `isSubscribed: false`. Answered by running the routing's own
 *  elections on a list holding [mailbox] alone. A visibility question; a true here moves no mail. */
fun isRoutingTargetByName(mailbox: Mailbox): Boolean {
    val alone = listOf(mailbox)
    return archiveFolderByName(alone) != null || trashFolderByName(alone) != null
}
