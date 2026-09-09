package app.sterna.ui.message

import app.sterna.core.data.settings.PLAIN_TEXT_DEFAULT
import app.sterna.core.data.text.htmlEscape
import app.sterna.core.data.text.htmlToText
import app.sterna.core.jmap.model.Email
import app.sterna.ui.compose.bodySource

/**
 * What the reader is about to RENDER for one message (#149). Both modes end up in the same WebView and
 */
internal data class ReaderBody(
    /** The HTML fragment to insert into the document's body. */
    val fragment: String,
    /** Whether that fragment is the message's own rich HTML (drives the dark-theme invert). */
    val richHtml: Boolean,
    /** Whether the text in it was flattened out of HTML (drives the "derived text" line). */
    val derived: Boolean,
)

/**
 * The body fragment for [email], in HTML mode ([plainText] false) or plain-text mode.
 */
internal fun readerBody(
    email: Email,
    plainText: Boolean,
    derivedNotice: String,
    noContent: String,
): ReaderBody {
    if (!plainText) {
        email.htmlContent()?.takeIf { it.isNotBlank() }
            ?.let { return ReaderBody(it, richHtml = true, derived = false) }
        email.textContent()?.takeIf { it.isNotBlank() }
            ?.let { return ReaderBody(plainFragment(it, null), false, false) }
        val fallback = email.preview?.takeIf { it.isNotBlank() } ?: noContent
        return ReaderBody("<p>${htmlEscape(fallback)}</p>", false, false)
    }
    val (raw, isHtml) = bodySource(email)
    val text = (if (isHtml) htmlToText(raw) else raw).ifBlank { noContent }
    return ReaderBody(
        fragment = plainFragment(text, if (isHtml) derivedNotice else null),
        richHtml = false,
        derived = isHtml,
    )
}

/**
 * Whether switching reading mode would CHANGE what [email] puts on the screen (#149).
 */
internal fun readingModesDiffer(email: Email, derivedNotice: String, noContent: String): Boolean {
    val asHtml = readerBody(email, plainText = false, derivedNotice, noContent)
    val asText = readerBody(email, plainText = true, derivedNotice, noContent)
    // One document carries the message's own markup, or the other had to flatten it away: two
    // different pages by construction, whatever text they end up holding.
    if (asHtml.richHtml || asText.derived) return true
    // Neither side carries markup any more: both are text we wrapped ourselves, so compare the TEXT
    // and not the fragments — a message with no body shows the same preview in a <p> here and a
    // <pre> there, and a wrapper we chose is not a change the reader asked for.
    return htmlToText(asHtml.fragment) != htmlToText(asText.fragment)
}

/**
 * Whether remote images may be fetched for the page as it currently stands. The reader decides this
 */
internal fun showRemoteImages(plainText: Boolean, manualShow: Boolean, senderAllowed: Boolean): Boolean =
    !plainText && (manualShow || senderAllowed)

/**
 * Which mode the reader RENDERS, from this message's own answer and the stored setting (#149).
 */
internal fun plainTextForBody(override: Boolean?, setting: Boolean?): Boolean =
    override ?: setting ?: PLAIN_TEXT_DEFAULT

/**
 * The same two answers, read for the REMOTE-IMAGE question — where the unread setting counts the other
 */
internal fun plainTextForImages(override: Boolean?, setting: Boolean?): Boolean =
    override ?: setting ?: PLAIN_TEXT_UNLOADED_FOR_IMAGES

/** Text wrapped for the document: line breaks kept, and the "derived text" line above it if any. */
private fun plainFragment(text: String, notice: String?): String {
    val body = "<pre class=\"plain\">${htmlEscape(reflowFormatFlowed(text))}</pre>"
    return if (notice.isNullOrBlank()) body else "<p class=\"s-note\">${htmlEscape(notice)}</p>$body"
}
