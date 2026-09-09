package app.sterna.core.data.mail

/** `subject` and `body` are null when absent OR blank: the caller's default beats an empty one. */
data class MailtoUnsubscribe(
    val address: String,
    val subject: String? = null,
    val body: String? = null,
)

/**
 * Three DIFFERENT gestures, not copies of one address: [oneClickUrl] is a POST that loads nothing,
 * [pageUrl] a browser page load with everything that implies. The two are mutually exclusive.
 */
data class UnsubscribeOptions(
    val oneClickUrl: String? = null,
    val pageUrl: String? = null,
    val mailto: MailtoUnsubscribe? = null,
)

/** A bare POST, a mail through the outbox, or a page handed to a browser — announced as such. */
enum class UnsubscribeAction {
    ONE_CLICK,

    MAIL,

    OPEN_PAGE,
}

/** A privacy order: opening a page is the only gesture that hands out an IP, so it comes last. */
fun UnsubscribeOptions.preferredAction(): UnsubscribeAction? = when {
    oneClickUrl != null -> UnsubscribeAction.ONE_CLICK
    mailto != null -> UnsubscribeAction.MAIL
    pageUrl != null -> UnsubscribeAction.OPEN_PAGE
    else -> null
}

/**
 * Not the same shape per gesture: ONE_CLICK names the HOST, who receives the POST being the whole
 */
fun UnsubscribeOptions.confirmationTarget(action: UnsubscribeAction): String? = when (action) {
    UnsubscribeAction.ONE_CLICK -> oneClickUrl?.let { UnsubscribeHeader.hostOf(it) }
    UnsubscribeAction.MAIL -> mailto?.address
    UnsubscribeAction.OPEN_PAGE -> pageUrl
}

/** Both protocols feed it the same text, so a malformed header is read one way on any server. */
object UnsubscribeHeader {

    /** RFC 8058's one and only legal `List-Unsubscribe-Post` value, matched after compaction. */
    private const val ONE_CLICK = "List-Unsubscribe=One-Click"

    private val WHITESPACE = Regex("[ \\t\\r\\n]")

    /** NOT "every blank": a `mailto:` may carry `?body=please remove me`. Unfolding may not rewrite. */
    private val FOLDING = Regex("[ \\t]*[\\r\\n\\t]+[ \\t]*")

    /**
     * Null when there is nothing usable: an unreadable header is never an error on screen. Bare URIs
     */
    fun parse(listUnsubscribe: String?, listUnsubscribePost: String?): UnsubscribeOptions? {
        if (listUnsubscribe.isNullOrBlank()) return null
        val oneClickAllowed = isOneClick(listUnsubscribePost)
        var httpsUrl: String? = null
        var mailto: MailtoUnsubscribe? = null
        for (uri in splitUris(listUnsubscribe)) {
            when {
                // Kept only with a HOST: a destination the confirmation cannot name is not offered.
                uri.startsWith("https://", ignoreCase = true) && hostOf(uri) != null ->
                    if (httpsUrl == null) httpsUrl = uri
                uri.startsWith("mailto:", ignoreCase = true) ->
                    if (mailto == null) mailto = parseMailto(uri)
                // http:// and anything else: no safe gesture behind it, dropped without a word.
                else -> Unit
            }
        }
        val options = UnsubscribeOptions(
            oneClickUrl = httpsUrl?.takeIf { oneClickAllowed },
            pageUrl = httpsUrl?.takeIf { !oneClickAllowed },
            mailto = mailto,
        )
        return options.takeIf { it.preferredAction() != null }
    }

    /** Strict: this header turns "open a page" into "send one POST", so an odd value promised nothing. */
    private fun isOneClick(post: String?): Boolean =
        post != null && post.replace(WHITESPACE, "").equals(ONE_CLICK, ignoreCase = true)

    private fun splitUris(header: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (c in header) {
            when {
                c == '<' -> { depth++; current.append(c) }
                c == '>' -> { if (depth > 0) depth--; current.append(c) }
                c == ',' && depth == 0 -> { out.add(current.toString()); current.clear() }
                else -> current.append(c)
            }
        }
        out.add(current.toString())
        return out.mapNotNull { token ->
            val trimmed = token.trim()
            val bare = if (trimmed.startsWith("<") && trimmed.endsWith(">") && trimmed.length >= 2) {
                trimmed.substring(1, trimmed.length - 1)
            } else {
                trimmed
            }
            bare.replace(FOLDING, "").takeIf { it.isNotEmpty() }
        }
    }

    private fun parseMailto(uri: String): MailtoUnsubscribe? {
        val rest = uri.substring("mailto:".length)
        val query = rest.substringAfter('?', "")
        // Several addresses are legal; the first is the one we write to.
        val address = decode(rest.substringBefore('?')).substringBefore(',').trim()
        if (address.isEmpty()) return null
        var subject: String? = null
        var body: String? = null
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val name = pair.substringBefore('=').lowercase()
            val value = decode(pair.substringAfter('=', "")).takeIf { it.isNotBlank() }
            when (name) {
                "subject" -> if (subject == null) subject = value
                "body" -> if (body == null) body = value
                else -> Unit
            }
        }
        return MailtoUnsubscribe(address, subject, body)
    }

    /** `+` is left ALONE: RFC 6068 spells a space `%20`, and `+` is literal in an `a+news@` id. */
    private fun decode(value: String): String = runCatching {
        java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(value)

    /** Userinfo (`user:pass@`) is dropped, the port kept — it is part of the address. */
    fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        return authority.substringAfterLast('@').takeIf { it.isNotEmpty() }
    }
}
