package app.sterna.ui.message

/**
 * What the reader shows of the in-place translation of ONE message.
 *
 * [source] is the fragment the translation was made from; the reader uses [fragment] only while its
 * own fragment still equals it (the reading mode flipped, the body changed: stale means original).
 * [fragment] non-null means a translation EXISTS - which is what enables "Show translated" - and
 * [shown] says whether it is the one on screen.
 */
data class ReaderTranslation(
    val lang: String = "",
    val source: String = "",
    val fragment: String? = null,
    val shown: Boolean = false,
    val running: Boolean = false,
    val error: String? = null,
) {
    val exists: Boolean get() = fragment != null

    /** The fragment to put in the document for [current], or null for the original. */
    fun fragmentFor(current: String?): String? =
        if (shown && fragment != null && current != null && source == current) fragment else null
}

/** The cached or running summary of ONE message, drawn as the collapsible box under the header. */
data class ReaderSummary(
    val text: String? = null,
    val running: Boolean = false,
    val error: String? = null,
) {
    /** Whether the box is drawn at all: a summary exists, is being made, or failed. */
    val visible: Boolean get() = text != null || running || error != null
}
