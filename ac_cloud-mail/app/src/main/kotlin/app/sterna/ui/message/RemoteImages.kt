package app.sterna.ui.message

import androidx.annotation.StringRes
import app.sterna.R

/**
 * Whether a message body asks for anything the reader's WebView will refuse to load — the question
 */
internal fun hasRemoteRefs(html: String?): Boolean {
    if (html.isNullOrEmpty()) return false
    return SRC_ATTR.findAll(html).any { match ->
        val value = (match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "").trim()
        value.isNotEmpty() && isRemoteRef(value)
    }
}

/**
 * The allowlist of `BlockingWebViewClient.shouldInterceptRequest`, applied to a raw attribute
 */
private fun isRemoteRef(value: String): Boolean {
    val scheme = SCHEME.find(value)?.groupValues?.get(1)?.lowercase()
    return scheme != "data" && scheme != "cid" && scheme != "about"
}

/**
 * A `src` attribute, quoted, single-quoted or bare. The leading whitespace keeps it off `data-src=`
 * (a lazy-loading placeholder the WebView never fetches), and `srcset` does not match at all.
 */
private val SRC_ATTR =
    Regex("""\ssrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

/** A URI scheme per RFC 3986 §3.1, or no match at all when the reference carries none. */
private val SCHEME = Regex("""^([a-zA-Z][a-zA-Z0-9+.\-]*):""")

/**
 * Whether the header carries an images strip at all for this page.
 */
internal fun imagesStripPresent(plainText: Boolean, senderAllowed: Boolean, html: String?): Boolean =
    !plainText && !senderAllowed && hasRemoteRefs(html)

/**
 * What the images strip SAYS, and whether it can still be pressed.
 */
internal enum class ImagesStripBody(@StringRes val label: Int) {
    /** The pictures are held back: the button is live and offers to fetch them. */
    ACTION(R.string.message_show_images),

    /** They are there: the same button, in the same place, greyed and saying so. */
    SHOWN(R.string.message_images_shown),
    ;

    /** Whether the strip still has something to unblock — the button's `enabled`. */
    val acts: Boolean get() = this == ACTION
}

/** See [ImagesStripBody]. */
internal fun imagesStripBody(blocked: Boolean): ImagesStripBody =
    if (blocked) ImagesStripBody.ACTION else ImagesStripBody.SHOWN
