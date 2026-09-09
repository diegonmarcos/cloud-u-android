package app.sterna.core.data.account

import app.sterna.core.data.text.htmlToText
import app.sterna.core.data.text.looksLikeHtml
import app.sterna.core.data.text.sanitiseReceivedHtml
import kotlinx.serialization.Serializable

/**
 * One named signature belonging to an identity (#206).
 *
 * Stored as the PAIR the app already stores a signature as: [text] is what the composer inserts into
 * the editable body, [html] the source the html alternative carries. Keeping both — rather than one
 * field plus a "this one is HTML" flag — is what lets a message say the same thing in both
 * alternatives, which is the property the send path has depended on since #131.
 *
 * [html] empty means the owner wrote PLAIN TEXT, and every path then behaves exactly as it did before
 * this type existed: no markup is generated, nothing is escaped, and nothing is sanitised away. That
 * is the promise the field's own label makes ("texto sin formato o HTML"), so it is a property of the
 * data and not of a code path a later caller could forget.
 */
@Serializable
data class StoredSignature(
    val id: String,
    /** What the owner calls this one in the pickers. Blank is legal — a migrated signature has no name
     *  the owner ever chose, and inventing one would put words in their mouth. */
    val name: String = "",
    /** The plain-text form. For an HTML signature this is [html] flattened, so the composer always has
     *  something to insert into a plain-text body. */
    val text: String = "",
    /** The HTML SOURCE exactly as the owner typed or imported it. Empty = this signature is plain text. */
    val html: String = "",
) {
    /** True when this signature carries markup, i.e. the owner chose the HTML half of the label. */
    val isHtml: Boolean get() = html.isNotBlank()

    /**
     * What the EDITOR shows and what the owner is editing: the HTML source when there is one.
     *
     * Showing [text] for an HTML signature is the defect that made the label a lie — the owner types
     * markup, the field flattens it on the next recomposition, and their source is gone.
     */
    fun source(): String = html.ifBlank { text }

    /**
     * The markup a renderer may be given for this signature, or empty for a plain-text one.
     *
     * SANITISED, through the same single policy the reader applies to a received message. A signature
     * is stored content that gets rendered, and "the owner supplied it" is not the same claim as "the
     * owner wrote it": Import HTML reads a file off the device, which is routinely a corporate
     * template someone else authored, and the composer's own preview renders it back to the owner.
     * The pair (text, html) also travels through backup/restore and K-9 import, neither of which the
     * owner typed.
     */
    fun renderableHtml(): String = if (isHtml) sanitiseReceivedHtml(html) else ""

    companion object {
        /**
         * The id a pre-#206 single signature keeps when it becomes the first of the new set. Fixed
         * rather than random so the migration is reproducible: running it twice on the same record
         * produces the same id, and [StoredIdentity.defaultSignatureId] can name it before it exists.
         */
        const val MIGRATED_ID = "migrated"

        /**
         * One signature from what the owner typed, telling the two halves of the label apart with
         * [looksLikeHtml] — the SAME discriminator [StoredIdentity.withSplitSignature] has always used
         * to decide whether a stored string was markup. A second, differently-tuned test would let the
         * editor and the legacy split disagree about one string, which is how a signature ends up
         * rendered as literal characters in one place and as markup in another.
         */
        fun of(id: String, name: String, source: String): StoredSignature =
            if (looksLikeHtml(source)) {
                StoredSignature(id, name, htmlToText(source), source)
            } else {
                StoredSignature(id, name, source, "")
            }
    }
}
