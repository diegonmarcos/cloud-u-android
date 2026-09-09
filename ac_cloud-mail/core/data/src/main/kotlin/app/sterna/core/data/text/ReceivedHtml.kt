package app.sterna.core.data.text

/**
 * THE SANITISATION POLICY for markup that arrived in somebody else's mail (#193).
 *
 * The reader already CONTAINS hostile mail: JavaScript is off on the WebView, the document carries
 * `default-src 'none'`, every subresource request is refused by scheme unless the user asked for
 * pictures, and a tapped link must clear a scheme allowlist and a real gesture. That containment is
 * the reason nothing here has ever executed.
 *
 * Containment is not the same as sanitisation, and this file exists because of the difference. Every
 * one of those defences is a SETTING or a CALLBACK somewhere else: a future `javaScriptEnabled =
 * true`, a WebView build that reads a `<meta>` policy differently, a print or share path that
 * forgets to install the blocking client, and the `on*` handlers and `<script>` blocks still sitting
 * in the DOM are live again. Removing them from the markup costs one pass and cannot be undone by
 * editing an unrelated file.
 *
 * So the rule is: what reaches a renderer is only what this file's tables name. The tables ARE the
 * policy — nothing below them decides anything they do not say.
 *
 * WHAT IS NOT THIS FILE'S JOB. Remote pictures are not dropped: `<img src="https://…">` survives
 * precisely so the "load images" affordance has something to load, and whether the fetch happens is
 * the load-time gate's decision, not the markup's. The same goes for a background image named by
 * CSS. Sanitisation decides what may be ASKED FOR; the gate decides when it is fetched.
 */

// --- The policy ---------------------------------------------------------------------------------

/**
 * Elements that survive with their tags. Mail is written by word processors, newsletter builders and
 * twenty years of table layout, so the list is broad on purpose: an element dropped here does not
 * merely lose its styling, it loses the shape the sender used to say something.
 */
private val KEPT_ELEMENTS: Set<String> = setOf(
    // Structure
    "p", "div", "span", "br", "hr", "center", "blockquote", "pre",
    "h1", "h2", "h3", "h4", "h5", "h6",
    // Inline formatting, including the legacy spellings newsletters still emit
    "b", "strong", "i", "em", "u", "s", "strike", "del", "ins", "sub", "sup",
    "small", "big", "code", "tt", "kbd", "samp", "var", "font", "mark", "abbr", "cite", "q", "wbr",
    // Lists
    "ul", "ol", "li", "dl", "dt", "dd",
    // Tables — still how a marketing mail lays itself out
    "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption", "colgroup", "col",
    // Links, pictures, and the quote fold this application injects into the document itself
    "a", "img", "details", "summary",
    // The stylesheet. Mail is styled with CSS and the policy permits inline style, so dropping this
    // would flatten every message. Its CONTENT is copied as CSS, never walked as markup.
    "style",
)

/**
 * Elements dropped WITH everything inside them, because their content is not prose: it is code, a
 * nested document, a control the user could operate, or foreign markup with its own grammar. An
 * element here loses text, and that is the intent — nobody reads a `<script>` body as a sentence.
 */
private val DROPPED_WITH_CONTENT: Set<String> = setOf(
    // Executable, in either spelling
    "script", "noscript",
    // Nested browsing contexts and plugins
    "iframe", "frame", "frameset", "noframes", "object", "embed", "applet", "param",
    // Anything that submits, or that the user could mistake for part of the application's own UI
    "form", "input", "button", "select", "option", "optgroup", "textarea",
    "label", "fieldset", "legend", "output", "datalist", "keygen",
    // Foreign markup: its own attribute grammar, and its own script surface
    "svg", "math",
    // Subresource and document-level directives. A message may not name a stylesheet, restate the
    // security policy, set a base URL, or hand the parser a document title.
    "link", "meta", "base", "title", "template", "portal",
    // Timed and streamed media, and the canvas
    "audio", "video", "source", "track", "canvas",
    // Historic parser-mode switches, every one of which is a way to change how what follows is read
    "marquee", "blink", "plaintext", "xmp", "listing",
)

/**
 * Elements with no closing tag: the tag IS the whole element. Kept here because the content-dropping
 * walk must not go looking for a `</input>` that no document will ever contain — it would swallow
 * the rest of the message.
 */
private val VOID_ELEMENTS: Set<String> = setOf(
    "br", "hr", "img", "col", "wbr",
    "input", "link", "meta", "base", "param", "source", "track", "embed", "keygen",
)

/** Attributes allowed on every kept element. */
private val GLOBAL_ATTRIBUTES: Set<String> = setOf(
    "style", "class", "id", "title", "dir", "lang", "align", "valign",
)

/** Attributes allowed only on the element that names them. */
private val ELEMENT_ATTRIBUTES: Map<String, Set<String>> = mapOf(
    "a" to setOf("href", "name"),
    "img" to setOf("src", "alt", "width", "height", "border", "hspace", "vspace"),
    "font" to setOf("color", "face", "size"),
    "ol" to setOf("start", "type"),
    "ul" to setOf("type"),
    "li" to setOf("value"),
    "table" to setOf("width", "height", "border", "cellpadding", "cellspacing", "bgcolor"),
    "tr" to setOf("bgcolor"),
    "td" to setOf("width", "height", "colspan", "rowspan", "bgcolor", "nowrap"),
    "th" to setOf("width", "height", "colspan", "rowspan", "bgcolor", "nowrap", "scope"),
    "col" to setOf("span", "width"),
    "colgroup" to setOf("span", "width"),
    "details" to setOf("open"),
    // `type="cite"` is how Thunderbird and the clients that follow it mark a quote, and the quote
    // fold reads the element back out of the rendered page. Inert markup, and information the
    // reader uses — `type` can never reach an element where it MEANS anything, because every such
    // element (input, button, link, script) is dropped whole.
    "blockquote" to setOf("cite", "type"),
)

/**
 * Attributes whose value is a URL, and the schemes each may carry. An attribute named here keeps its
 * value only when the scheme is one of these; anything else drops the ATTRIBUTE, leaving the element
 * and its text in place. `href` mirrors the schemes the tap handler is willing to open, so a link
 * that survives is a link that can actually go somewhere.
 */
private val URL_ATTRIBUTE_SCHEMES: Map<String, Set<String>> = mapOf(
    "href" to setOf("http", "https", "mailto", "tel", "sms", "geo", "cid"),
    "src" to setOf("http", "https", "cid", "data"),
    "cite" to setOf("http", "https"),
)

// --- The pass ----------------------------------------------------------------------------------

/**
 * [html] reduced to what the policy above allows. Never null and never throws: a body that cannot be
 * parsed still has to be readable, so every ambiguity resolves toward keeping the sender's WORDS and
 * dropping the markup around them.
 */
fun sanitiseReceivedHtml(html: String?): String {
    if (html.isNullOrEmpty()) return ""
    val out = StringBuilder(html.length)
    var i = 0
    while (i < html.length) {
        val lt = html.indexOf('<', i)
        if (lt < 0) {
            out.append(html, i, html.length)
            break
        }
        out.append(html, i, lt)
        // A comment, a CDATA section, a doctype or a processing instruction. Dropped rather than
        // copied: a conditional comment is how markup is smuggled past a scanner that reads the
        // whole construct as text.
        val nonElement = skipNonElement(html, lt)
        if (nonElement > 0) {
            i = nonElement
            continue
        }
        val tag = tagAt(html, lt)
        if (tag == null) {
            // A `<` that opens nothing — a naked comparison in a sentence, or a tag truncated by
            // the end of the input. Escaped, so no later pass over this string can read it as a tag.
            out.append("&lt;")
            i = lt + 1
            continue
        }
        i = tag.end
        when {
            // The stylesheet, both halves of it. Handled before the kept-element branch so a stray
            // `</style>` cannot emit a closing tag with nothing open.
            tag.name == "style" -> if (!tag.closing) {
                val (contentEnd, elementEnd) = styleContentEnd(html, tag.end)
                out.append("<style>")
                    .append(sanitiseCss(html.substring(tag.end, contentEnd)))
                    .append("</style>")
                i = elementEnd
            }

            tag.name in DROPPED_WITH_CONTENT ->
                if (!tag.closing && !tag.selfClosing && tag.name !in VOID_ELEMENTS) {
                    i = skipElementContent(html, tag.name, tag.end)
                }

            tag.name in KEPT_ELEMENTS -> out.append(tag.rendered())

            // Unknown, or structural. `<html>`, `<head>` and `<body>` cannot legally appear inside a
            // fragment that is itself inserted into a document body, and a `<o:p>` from a word
            // processor means nothing to anyone. The tag goes; the words inside it stay.
            else -> Unit
        }
    }
    return out.toString()
}

/** One parsed tag. [end] is the offset just past its `>`. */
private class Tag(
    val name: String,
    val closing: Boolean,
    val selfClosing: Boolean,
    val attributes: List<Pair<String, String?>>,
    val end: Int,
)

/** The tag written back with only the attributes the policy keeps. */
private fun Tag.rendered(): String {
    if (closing) return "</$name>"
    val sb = StringBuilder(16).append('<').append(name)
    for ((attribute, value) in attributes) {
        if (!attributeSurvives(name, attribute, value)) continue
        sb.append(' ').append(attribute)
        if (value != null) sb.append("=\"").append(attributeValueEscape(value)).append('"')
    }
    // No trailing slash, on a void element or any other: the HTML parser does not need one and does
    // not honour it on a non-void element anyway, so emitting it would only be noise.
    return sb.append('>').toString()
}

/**
 * The tag starting at [at], or null when what is there is not one. A quote-aware scan, because
 * `<a title="a>b">` is one tag and a search for the next `>` would cut it in half — and the half
 * left behind is attacker-chosen.
 */
private fun tagAt(s: String, at: Int): Tag? {
    var i = at + 1
    if (i >= s.length) return null
    val closing = s[i] == '/'
    if (closing) i++
    val nameStart = i
    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == ':' || s[i] == '-')) i++
    if (i == nameStart) return null
    val name = s.substring(nameStart, i).lowercase()
    val attributes = ArrayList<Pair<String, String?>>()
    var selfClosing = false
    while (true) {
        while (i < s.length && s[i].isWhitespace()) i++
        // Ran out of input with the tag still open: not a tag. The caller escapes the `<` and keeps
        // reading, so the text that followed is still shown.
        if (i >= s.length) return null
        if (s[i] == '>') {
            i++
            break
        }
        if (s[i] == '/') {
            selfClosing = true
            i++
            continue
        }
        val attributeStart = i
        while (i < s.length && !s[i].isWhitespace() && s[i] != '=' && s[i] != '>' && s[i] != '/') i++
        if (i == attributeStart) {
            // A character that can neither start a name nor end the tag. Step over it rather than
            // spinning on it.
            i++
            continue
        }
        val attribute = s.substring(attributeStart, i).lowercase()
        while (i < s.length && s[i].isWhitespace()) i++
        var value: String? = null
        if (i < s.length && s[i] == '=') {
            i++
            while (i < s.length && s[i].isWhitespace()) i++
            val quote = if (i < s.length && (s[i] == '"' || s[i] == '\'')) s[i] else null
            if (quote != null) {
                i++
                val from = i
                while (i < s.length && s[i] != quote) i++
                value = s.substring(from, i)
                if (i < s.length) i++
            } else {
                val from = i
                while (i < s.length && !s[i].isWhitespace() && s[i] != '>') i++
                value = s.substring(from, i)
            }
        }
        attributes.add(attribute to value)
    }
    return Tag(name, closing, selfClosing, attributes, i)
}

/** Whether [attribute] survives on [element] carrying [value]. */
private fun attributeSurvives(element: String, attribute: String, value: String?): Boolean {
    // Every event handler in one rule rather than a list of them. They are all spelled `on` plus a
    // name, and a list would have to be extended for every attribute the platform ever adds —
    // which is the same as saying it would eventually be wrong.
    if (attribute.startsWith("on")) return false
    val allowed = attribute in GLOBAL_ATTRIBUTES || attribute in ELEMENT_ATTRIBUTES[element].orEmpty()
    if (!allowed) return false
    val schemes = URL_ATTRIBUTE_SCHEMES[attribute] ?: return true
    return urlSchemeSurvives(value, schemes)
}

/**
 * Whether a URL-valued attribute's [value] carries a scheme [allowed] names.
 *
 * The value is decoded and stripped of control characters BEFORE the scheme is read, because
 * `java&#115;cript:` and `java\tscript:` are both `javascript:` by the time a browser resolves them,
 * and a check against the raw text would pass them. What is written back is the ORIGINAL value: the
 * decoding exists to judge the reference, not to rewrite it.
 */
private fun urlSchemeSurvives(value: String?, allowed: Set<String>): Boolean {
    val decoded = unescapeEntities(value ?: return false)
        .filterNot { it < ' ' || it == '\u007F' }
        .trim()
    // No scheme to judge: an anchor, a relative path, or a protocol-relative `//host/x`. The
    // document is loaded with a NULL base URL, so none of them resolves to anywhere, and the
    // load-time gate refuses them by scheme regardless.
    val scheme = SCHEME_PREFIX.find(decoded)?.groupValues?.get(1)?.lowercase() ?: return true
    if (scheme !in allowed) return false
    // `data:` is allowed for pictures alone. `data:text/html` is a DOCUMENT, and a document is a
    // script surface wearing a URL.
    return scheme != "data" || DATA_IMAGE.containsMatchIn(decoded)
}

/**
 * An attribute value written back into double quotes. `&` is deliberately NOT escaped: the value
 * came out of HTML and is already encoded, so escaping it again would turn a sender's `&amp;` into a
 * visible `&amp;`. Only what could end the attribute or open an element is touched.
 */
private fun attributeValueEscape(value: String): String =
    value.replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * The stylesheet's content, kept as CSS. `@import` is the one rule that must go: it fetches a
 * stylesheet from a remote host, which is the tracking signal an opened message must not send, and
 * unlike a background image there is no user affordance that would ever want it.
 */
private fun sanitiseCss(css: String): String = css.replace(AT_IMPORT, "").replace("<", "")

/** Where a `<style>`'s content ends, and where the element does. */
private fun styleContentEnd(s: String, from: Int): Pair<Int, Int> {
    val close = STYLE_CLOSE.find(s, from) ?: return s.length to s.length
    return close.range.first to close.range.last + 1
}

/** Past a comment, CDATA section, doctype or processing instruction at [at]; 0 when none is there. */
private fun skipNonElement(s: String, at: Int): Int = when {
    s.startsWith("<!--", at) -> s.indexOf("-->", at + 4).let { if (it < 0) s.length else it + 3 }
    s.startsWith("<![", at) -> s.indexOf("]>", at + 3).let { if (it < 0) s.length else it + 2 }
    s.startsWith("<!", at) || s.startsWith("<?", at) ->
        s.indexOf('>', at + 2).let { if (it < 0) s.length else it + 1 }
    else -> 0
}

/**
 * Past the content of the element [name] opened just before [from], and past its closing tag.
 *
 * Nesting is counted, so `<form><form></form></form>` does not leave the second half of it behind.
 *
 * ponytail: when there is NO closing tag anywhere, this drops the opening tag alone and returns
 * [from] — the rest of the message is content, and losing it is the failure this function must not
 * have. The price is that an unterminated `<script>` shows its source as text instead of vanishing.
 * Nothing runs either way (JavaScript is off and the policy forbids it); if such a mail is ever seen
 * in the wild, give the raw-text elements their own to-end-of-input rule.
 */
private fun skipElementContent(s: String, name: String, from: Int): Int {
    var depth = 1
    var i = from
    while (i < s.length && depth > 0) {
        val lt = s.indexOf('<', i)
        if (lt < 0) return from
        val nonElement = skipNonElement(s, lt)
        if (nonElement > 0) {
            i = nonElement
            continue
        }
        val tag = tagAt(s, lt)
        if (tag == null) {
            i = lt + 1
            continue
        }
        if (tag.name == name) {
            if (tag.closing) depth-- else if (!tag.selfClosing) depth++
        }
        i = tag.end
    }
    return if (depth == 0) i else from
}

// --- A link whose text disagrees with its target ------------------------------------------------

/**
 * [html] with [label] appended after every link whose VISIBLE TEXT names one host and whose `href`
 * goes to another (#193) — the standard phishing shape, and the one thing on this surface that
 * sanitisation cannot fix by dropping something: both the text and the target are legal, it is their
 * DISAGREEMENT that is the attack.
 *
 * Only a link whose whole visible text reads as a bare host or URL is examined. "Click here" over a
 * redirector is not marked, because a marker on every tracked newsletter link is a marker the user
 * stops seeing — and the text made no claim to be contradicted.
 *
 * [label] is called with the host the link ACTUALLY goes to, and returns the markup to insert. It is
 * a lambda because the wording is a localised resource and this module has none.
 */
fun markDeceptiveLinks(html: String, label: (actualHost: String) -> String): String =
    ANCHOR_ELEMENT.replace(html) { match ->
        val href = HREF_VALUE.find(match.groupValues[1])
            ?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        val actual = hostOf(href)
        val claimed = hostOf(urlLikeText(htmlToText(match.groupValues[2])))
        if (actual == null || claimed == null || actual == claimed) match.value
        else match.value + label(actual)
    }

/**
 * The host [url] addresses, comparably: lower-cased, without a leading `www.`, and — the part that
 * matters — taken from after the LAST `@`, since `https://www.bank.example@evil.example/` is a link
 * to `evil.example` that reads as one to the bank.
 */
private fun hostOf(url: String?): String? {
    val trimmed = (url ?: return null).trim()
    val authority = trimmed.substringAfter("://", missingDelimiterValue = trimmed)
        .substringBefore('/').substringBefore('?').substringBefore('#')
    val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
    return host.removePrefix("www.").ifEmpty { null }
}

/** [text] when the whole of it reads as a bare host or URL, else null. */
private fun urlLikeText(text: String): String? =
    text.trim().takeIf { BARE_URL_TEXT.matches(it) }

// --- Patterns -----------------------------------------------------------------------------------

/** A URI scheme per RFC 3986 §3.1, anchored: a relative reference matches nothing. */
private val SCHEME_PREFIX = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")

/** A `data:` URL that decodes to a picture. */
private val DATA_IMAGE = Regex("(?i)^data:image/[a-z0-9.+\\-]+[;,]")

/** An `@import` rule, to the semicolon that ends it or to the end of the stylesheet. */
private val AT_IMPORT = Regex("(?is)@import[^;]*(?:;|$)")

/** A `</style>` closing tag, however it is spaced. */
private val STYLE_CLOSE = Regex("(?i)</style\\s*>")

/** One anchor element: its attributes, then everything up to the first `</a>`. */
private val ANCHOR_ELEMENT = Regex("(?is)<a\\b([^>]*)>(.*?)</a\\s*>")

/** The `href` of an anchor's attribute text, quoted either way. */
private val HREF_VALUE = Regex("(?i)\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")

/**
 * Visible text that is nothing but a host or a URL: an optional scheme, a dotted name ending in a
 * letters-only label, and an optional path. Anchored at both ends, so a sentence CONTAINING a
 * hostname is not a claim about where the link goes.
 */
private val BARE_URL_TEXT =
    Regex("(?i)(?:[a-z][a-z0-9+.\\-]*://)?(?:[\\w\\-]+\\.)+[a-z]{2,}(?:[/?#][^\\s]*)?")
