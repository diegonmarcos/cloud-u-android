package app.sterna.core.data.text

/**
 * HTML ↔ plain-text helpers for the composer (quoting, forwarding, flattening an imported HTML
 * signature) and the account layer's legacy HTML signature field. Regex-based, not a parser.
 */

/** Does [s] look like HTML (a tag opener), rather than plain text? */
fun looksLikeHtml(s: String): Boolean = Regex("<[a-zA-Z/!]").containsMatchIn(s)

/** Escape the three characters that are unsafe in HTML text context. */
fun htmlEscape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Escape for HTML and turn newlines into &lt;br&gt; so plain text keeps its line breaks. */
fun htmlEscapeMultiline(s: String): String =
    htmlEscape(s).replace("\n", "<br>")

/**
 * Best-effort HTML→plain-text for quoting/editing an HTML-only original and for flattening an
 * imported signature. Converts block boundaries to newlines so paragraphs/rows don't collapse.
 *
 * [keepLinkTargets] spells each link's ADDRESS out beside its label, `Whatsapp <https://wa.me/…>`.
 * Off by default, and deliberately: quoting a received message loses nothing when a href goes, because
 * the reader still holds the html part and renders it. A SIGNATURE's flattened half is the opposite
 * case — it is the only place those addresses appear in the `text/plain` alternative, so dropping them
 * ships a recipient four words that used to be links and now go nowhere. Same flattener either way, so
 * the two halves of one message cannot disagree about anything else.
 */
fun htmlToText(html: String, keepLinkTargets: Boolean = false): String =
    html
        .replace(Regex("(?is)<(script|style|head)\\b.*?</\\1>"), "")
        // Source layout between tags is not content: a signature table written one row per line
        // would otherwise flatten with a blank line between every row. Only whitespace that spans
        // a line break is dropped, so a deliberate space between two inline tags survives.
        .replace(Regex(">[ \\t]*\\r?\\n[ \\t\\r\\n]*<"), "><")
        .let { if (keepLinkTargets) spellOutLinkTargets(it) else it }
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|li|tr|h[1-6]|blockquote|ul|ol|table)\\s*>"), "\n")
        // A table cell boundary is a column break, not a paragraph one: keep the row on one line.
        .replace(Regex("(?i)</(td|th)\\s*>"), " ")
        .replace(Regex("<[^>]+>"), "")
        .let(::unescapeEntities)
        .replace(Regex("[ \\t]+\n"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

/**
 * Every `<a href>` rewritten as `label &lt;address&gt;`, for the [htmlToText] pass that keeps targets.
 *
 * The brackets are emitted ESCAPED and decoded later by [unescapeEntities], because the pass that
 * follows this one strips `<…>` as markup: written literally, `<https://wa.me/33…>` is indentical in
 * identical in shape to a tag and the address would be deleted by the very next line — the failure this function
 * exists to prevent, reintroduced one step downstream.
 *
 * The LABEL is left as raw markup for the rest of the pass to flatten, so a `<b>Whatsapp</b>` inside
 * the anchor is handled by the same rules as bold anywhere else.
 */
private fun spellOutLinkTargets(html: String): String =
    ANCHOR.replace(html) { m ->
        // Groups 1..3 are the three ways a href can be quoted; exactly one of them matched.
        val href = m.groupValues.subList(1, 4).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val label = m.groupValues[4]
        val text = htmlToText(label)
        when {
            // An anchor with no address, or none with a label to sit beside, is left exactly as it
            // was: this pass may ADD an address, never remove or reword one.
            href.isEmpty() || text.isBlank() -> m.value
            // An address the sanitiser would strip off the html half must not be smuggled into the
            // text half, where nothing sanitises it again and the two halves would then disagree.
            !linkTargetSurvives(href) -> m.value
            // The label already showing the address is the ordinary "https://acme.fr" line of a
            // signature; repeating it there would print the same URL twice on one line.
            namesTheSameTarget(text, href) -> m.value
            else -> "$label &lt;$href&gt;"
        }
    }

private val ANCHOR =
    Regex("""(?is)<a\b[^>]*?\bhref\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))[^>]*>(.*?)</a\s*>""")

/** Whether [label] already spells out [href], ignoring the parts a reader does not need to see. */
private fun namesTheSameTarget(label: String, href: String): Boolean =
    bareTarget(label) == bareTarget(href)

private fun bareTarget(s: String): String =
    s.trim().lowercase()
        .removePrefix("https://").removePrefix("http://")
        .removePrefix("mailto:").removePrefix("tel:")
        .removePrefix("www.")
        .trimEnd('/')

/**
 * Decode HTML character references in [s]: common named ones plus every numeric reference
 */
fun unescapeEntities(s: String): String =
    ENTITY_REF.replace(s) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") ->
                body.drop(2).toIntOrNull(16)?.let(::codePointOrNull) ?: m.value
            body.startsWith("#") ->
                body.drop(1).toIntOrNull()?.let(::codePointOrNull) ?: m.value
            else -> NAMED_ENTITIES[body]?.let(::codePointOrNull) ?: m.value
        }
    }

private val ENTITY_REF =
    Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,10});")

/** A code point as a string, or null when it is not a usable scalar value. */
private fun codePointOrNull(cp: Int): String? =
    if (cp <= 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) null else String(Character.toChars(cp))

/**
 * Named references worth decoding — not the full HTML5 table, just what shows up in signatures
 * and quoted mail. `&nbsp;` decodes to a normal space, not U+00A0, so plain text still wraps.
 */
private val NAMED_ENTITIES: Map<String, Int> = mapOf(
    // Core
    "nbsp" to 0x20, "lt" to 0x3C, "gt" to 0x3E, "quot" to 0x22, "apos" to 0x27, "amp" to 0x26,
    // Punctuation and symbols
    "mdash" to 0x2014, "ndash" to 0x2013, "hellip" to 0x2026, "bull" to 0x2022,
    "lsquo" to 0x2018, "rsquo" to 0x2019, "ldquo" to 0x201C, "rdquo" to 0x201D,
    "sbquo" to 0x201A, "bdquo" to 0x201E, "prime" to 0x2032, "Prime" to 0x2033,
    "dagger" to 0x2020, "Dagger" to 0x2021, "permil" to 0x2030, "trade" to 0x2122,
    "copy" to 0xA9, "reg" to 0xAE, "sect" to 0xA7, "para" to 0xB6, "middot" to 0xB7,
    "laquo" to 0xAB, "raquo" to 0xBB, "lsaquo" to 0x2039, "rsaquo" to 0x203A,
    "deg" to 0xB0, "plusmn" to 0xB1, "times" to 0xD7, "divide" to 0xF7,
    "frac12" to 0xBD, "frac14" to 0xBC, "frac34" to 0xBE, "sup1" to 0xB9, "sup2" to 0xB2, "sup3" to 0xB3,
    "micro" to 0xB5, "ordf" to 0xAA, "ordm" to 0xBA, "iexcl" to 0xA1, "iquest" to 0xBF,
    "cent" to 0xA2, "pound" to 0xA3, "curren" to 0xA4, "yen" to 0xA5, "euro" to 0x20AC,
    "brvbar" to 0xA6, "uml" to 0xA8, "macr" to 0xAF, "acute" to 0xB4, "cedil" to 0xB8, "not" to 0xAC,
    "shy" to 0xAD, "ensp" to 0x20, "emsp" to 0x20, "thinsp" to 0x20,
    "larr" to 0x2190, "uarr" to 0x2191, "rarr" to 0x2192, "darr" to 0x2193, "harr" to 0x2194,
    // Accented letters (Latin-1 + the common ligatures)
    "Agrave" to 0xC0, "agrave" to 0xE0, "Aacute" to 0xC1, "aacute" to 0xE1,
    "Acirc" to 0xC2, "acirc" to 0xE2, "Atilde" to 0xC3, "atilde" to 0xE3,
    "Auml" to 0xC4, "auml" to 0xE4, "Aring" to 0xC5, "aring" to 0xE5,
    "AElig" to 0xC6, "aelig" to 0xE6, "Ccedil" to 0xC7, "ccedil" to 0xE7,
    "Egrave" to 0xC8, "egrave" to 0xE8, "Eacute" to 0xC9, "eacute" to 0xE9,
    "Ecirc" to 0xCA, "ecirc" to 0xEA, "Euml" to 0xCB, "euml" to 0xEB,
    "Igrave" to 0xCC, "igrave" to 0xEC, "Iacute" to 0xCD, "iacute" to 0xED,
    "Icirc" to 0xCE, "icirc" to 0xEE, "Iuml" to 0xCF, "iuml" to 0xEF,
    "Ntilde" to 0xD1, "ntilde" to 0xF1,
    "Ograve" to 0xD2, "ograve" to 0xF2, "Oacute" to 0xD3, "oacute" to 0xF3,
    "Ocirc" to 0xD4, "ocirc" to 0xF4, "Otilde" to 0xD5, "otilde" to 0xF5,
    "Ouml" to 0xD6, "ouml" to 0xF6, "Oslash" to 0xD8, "oslash" to 0xF8,
    "Ugrave" to 0xD9, "ugrave" to 0xF9, "Uacute" to 0xDA, "uacute" to 0xFA,
    "Ucirc" to 0xDB, "ucirc" to 0xFB, "Uuml" to 0xDC, "uuml" to 0xFC,
    "Yacute" to 0xDD, "yacute" to 0xFD, "yuml" to 0xFF, "szlig" to 0xDF,
    "OElig" to 0x152, "oelig" to 0x153, "Scaron" to 0x160, "scaron" to 0x161,
)
