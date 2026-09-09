package app.sterna.ui

import app.sterna.R
import app.sterna.core.data.mail.ImapNumberingUnconfirmed

/**
 * What the reader is told when one of ITS OWN actions on the open message did not go through —
 */
internal fun readerActionFailureText(t: Throwable, string: (Int) -> String): String = when (t) {
    is ImapNumberingUnconfirmed -> string(R.string.status_action_failed)
    else -> t.message ?: t.javaClass.simpleName
}
