package app.sterna.core.data.text

import app.sterna.core.data.account.signatureWireHtml

/*
 * The composer's body is HTML. These are the pure rules around that model, kept out of the screen so a
 * JVM test can execute every one: what the editor's markup becomes on the wire, what a reopened draft
 * opens on, and how the blocks of a reply (signature, quote, forwarded original) are laid out.
 *
 * The editor (a WebView `contenteditable`, see ui/compose/HtmlBodyEditor) marks three kinds of block
 * with `data-sterna` so it can find them again - the signature, the quoted original, the forwarded
 * original - and tags a picked picture with `data-cid` so the wire can swap its preview for the
 * Content-ID part. Both attributes are the editor's own: [htmlForWire] removes them.
 */

/** The attribute that names an editor-managed block. */
const val STERNA_BLOCK_ATTR: String = "data-sterna"

/** The values of [STERNA_BLOCK_ATTR]. */
const val BLOCK_SIGNATURE: String = "sig"
const val BLOCK_QUOTE: String = "quote"
const val BLOCK_FORWARD: String = "forward"

/** [inner] as an editor block: `<div data-sterna="sig">…</div>`. Empty [inner] is no block at all. */
fun editorBlock(kind: String, inner: String): String =
    if (inner.isBlank()) "" else "<div $STERNA_BLOCK_ATTR=\"$kind\">$inner</div>"

/**
 * What a message opens with: the typed [typed] text (a mailto: body, a suggested reply) or an empty
 * line to type on, then the [signature] and [quote] blocks - the signature above the quote, or below
 * it when [signatureBelowQuote]. The caret belongs on the FIRST line, above everything else.
 */
fun composeOpeningHtml(
    typed: String,
    signature: String,
    quote: String,
    signatureBelowQuote: Boolean,
): String {
    val first = if (typed.isBlank()) "<div><br></div>" else plainTextToHtml(typed.trimEnd('\n'))
    return first + if (signatureBelowQuote) quote + signature else signature + quote
}

/**
 * The signature as an editor block, in its wire shape: the lead-in line (`-- ` when [delimiter]) and
 * then the signature's HTML, or - for a signature written as text - its text with line breaks kept.
 * Blank when the identity has none.
 */
fun signatureEditorBlock(signatureHtml: String, signatureText: String, delimiter: Boolean): String {
    val body = when {
        signatureHtml.isNotBlank() -> signatureWireHtml(signatureHtml)
        signatureText.isNotBlank() -> htmlEscapeMultiline(signatureText.trim('\n'))
        else -> return ""
    }
    val head = if (delimiter) "<br>-- <br>" else "<br>"
    return editorBlock(BLOCK_SIGNATURE, head + body)
}

private val IMG_TAG = Regex("(?is)<img\\b[^>]*>")
private val DATA_CID = Regex("(?i)\\sdata-cid\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
private val SRC_ATTR = Regex("(?i)\\ssrc\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+)")

/**
 * The editor's markup as it leaves the phone (and as a draft is stored): every picked picture's
 * preview `src` swapped for its `cid:` (the image part rides along as multipart/related), then the
 * whole thing through [sanitiseReceivedHtml] - the one policy for HTML that is going to be rendered
 * by somebody - which also drops the editor's own `data-sterna` / `data-cid` attributes.
 */
fun htmlForWire(editorHtml: String): String {
    val withCids = IMG_TAG.replace(editorHtml) { m ->
        val tag = m.value
        val cid = DATA_CID.find(tag)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }?.trim()
        if (cid.isNullOrEmpty()) {
            tag
        } else {
            val safe = cid.trim('<', '>').replace("\"", "")
            DATA_CID.replace(SRC_ATTR.replace(tag, " src=\"cid:$safe\""), "")
        }
    }
    return sanitiseReceivedHtml(withCids)
}

private val CID_REF = Regex("(?i)\\bsrc\\s*=\\s*[\"']?\\s*cid:([^\"'>\\s]+)")

/** The Content-IDs (no angle brackets) a wire HTML references - the inline parts worth sending. */
fun cidsReferencedBy(wireHtml: String): Set<String> =
    CID_REF.findAll(wireHtml).map { it.groupValues[1].trim().trim('<', '>') }.filter { it.isNotEmpty() }.toSet()

/**
 * The body a composer reopens on - a draft, a queued message, a scheduled one: its [html] when it has
 * one (the model is HTML, so nothing is parsed back into spans and nothing is lost), else its text
 * wrapped by [plainTextToHtml]. Sanitised on the way in: a stored body is data, not the owner's.
 */
fun htmlBodyFrom(html: String?, plainText: () -> String): RichBody {
    val source = html?.takeIf { it.isNotBlank() }?.let { sanitiseReceivedHtml(it) }?.takeIf { it.isNotBlank() }
        ?: plainTextToHtml(plainText())
    return RichBody.ofHtml(source)
}

/**
 * [parts] without the inline pictures [wireHtml] no longer references: a picture the owner deleted
 * from the body again must not travel as an orphan `Content-ID` part. File attachments always stay.
 */
fun withoutUnusedInlineParts(
    parts: List<app.sterna.core.jmap.model.EmailBodyPart>,
    wireHtml: String,
): List<app.sterna.core.jmap.model.EmailBodyPart> {
    val used = cidsReferencedBy(wireHtml)
    return parts.filter { p ->
        val cid = p.cid?.trim()?.trim('<', '>')
        val inline = p.disposition.equals("inline", ignoreCase = true) && !cid.isNullOrEmpty()
        !inline || cid in used
    }
}

private val DIV_TAG = Regex("(?i)<(/?)div\\b[^>]*>")

/**
 * The editor block [kind] out of [html] - its opening `<div data-sterna="…">` to the matching
 * `</div>`, nested divs counted - or null when there is none. Used to carry a forwarded original
 * into a body the owner has already begun, instead of dropping it.
 */
fun extractEditorBlock(html: String, kind: String): String? {
    val open = Regex("(?i)<div\\s+$STERNA_BLOCK_ATTR\\s*=\\s*\"${Regex.escape(kind)}\"[^>]*>").find(html) ?: return null
    var depth = 1
    for (tag in DIV_TAG.findAll(html, open.range.last + 1)) {
        depth += if (tag.groupValues[1] == "/") -1 else 1
        if (depth == 0) return html.substring(open.range.first, tag.range.last + 1)
    }
    return null
}
