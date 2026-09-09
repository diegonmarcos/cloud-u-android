package app.sterna.core.data.mail

import app.sterna.core.data.text.htmlToText
import app.sterna.core.imap.ImapTextPart
import app.sterna.core.imap.MimeParser

/** Nothing here shapes the result: capping happens once, in `MailNotificationText.shapePreview`. */
object BodyPreview {

    /** Null, never "" and never a blank line: an empty preview leaves the subject line alone. */
    fun fromPart(raw: String, part: ImapTextPart): String? {
        val bytes = MimeParser.decodeBytes(decodable(raw, part.encoding), part.encoding)
        // The fragment was cut at a byte boundary, so the last character may have been halved: the
        // decoder leaves U+FFFD, which reads as a corrupted message rather than a truncated one.
        val text = String(bytes, MimeParser.charsetNamed(part.charset)).trimEnd(REPLACEMENT)
        val readable = if (part.mime == "text/html") htmlToText(closedHtml(text)) else text
        return readable.takeIf { it.isNotBlank() }
    }

    /** U+FFFD, what a decoder puts where a byte sequence it could not read used to be. */
    private const val REPLACEMENT = '�'

    /**
     * The fragment was cut at an octet count that knows nothing of encoded units. A base64 cut off a
     */
    private fun decodable(raw: String, encoding: String?): String = when {
        encoding.equals("base64", ignoreCase = true) -> {
            val packed = raw.filter { it != '\r' && it != '\n' }
            packed.take(packed.length / 4 * 4)
        }
        encoding.equals("quoted-printable", ignoreCase = true) -> {
            val escape = raw.lastIndexOf('=')
            if (escape >= 0 && raw.length - escape <= 2) raw.substring(0, escape) else raw
        }
        else -> raw
    }

    /**
     * Damage [htmlToText] cannot undo: it drops `<style>`/`<script>`/`<head>` by matching a PAIR of
     * tags, so a block cut before its closing tag is read as text, and a halved `<div` survives.
     */
    private fun closedHtml(html: String): String {
        val cut = DANGLING_BLOCK.find(html)?.range?.first ?: html.length
        val head = html.substring(0, cut)
        val opener = head.lastIndexOf('<')
        return if (opener > head.lastIndexOf('>')) head.substring(0, opener) else head
    }

    /** An opener the cut swallowed. `[\s\S]` rather than DOTALL: explicit about spanning lines. */
    private val DANGLING_BLOCK = Regex("""(?i)<(script|style|head)\b(?![\s\S]*?</\1>)""")
}
