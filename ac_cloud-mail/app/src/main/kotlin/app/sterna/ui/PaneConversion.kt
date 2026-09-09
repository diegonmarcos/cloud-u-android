package app.sterna.ui

/**
 * What happens to a message that is open when the WINDOW changes width (#103).
 */
object PaneConversion {

    /**
     * A full-screen reader restored on a wide window, with the LIST directly under it: it moves into
     */
    fun readerToPane(twoPanes: Boolean, stack: List<String?>): Boolean {
        if (!twoPanes || stack.size < 2) return false
        return destination(stack[stack.size - 1]) == MESSAGE && destination(stack[stack.size - 2]) == INBOX
    }

    /**
     * An anchor on a narrow window: it goes back to being a route. Two panes keep it — there the
     * anchor IS the reader, and navigating would stack a full-screen copy of it over itself.
     */
    fun paneToReader(twoPanes: Boolean, hasAnchor: Boolean): Boolean = !twoPanes && hasAnchor

    private fun destination(route: String?): String? =
        route?.takeWhile { it != '/' && it != '?' }

    private const val INBOX = "inbox"

    private const val MESSAGE = "message"
}
