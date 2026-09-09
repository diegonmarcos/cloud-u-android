package app.sterna.core.data.mail

/**
 * The FALLBACK: find the unsubscribe link a sender buried in the body because they did not send the
 * header that exists for it.
 *
 * READ THIS BEFORE TRUSTING IT. [UnsubscribeHeader] is exact — `List-Unsubscribe` (RFC 2369) and
 * `List-Unsubscribe-Post` (RFC 8058) are a machine-readable promise, and what they name is what the
 * sender meant. This file is a GUESS over sender-controlled HTML, and it is wrong some of the time:
 *
 *  - a newsletter whose footer reads "unsubscribe or manage your preferences" as one anchor gives a
 *    preferences page, not an opt-out;
 *  - a forwarded newsletter, or a reply quoting one, still carries the original's footer, so the
 *    link found belongs to a list the reader may never have joined;
 *  - a phishing mail can put the word "unsubscribe" on any link it likes, and a scan that reads the
 *    word is reading the attacker's own text.
 *
 * Three things follow, and together they are what makes offering the guess defensible at all:
 *
 *  1. it runs ONLY when the headers gave nothing. A real header is never overridden by a guess.
 *  2. its result is always [UnsubscribeAction.OPEN_PAGE] — a page the user looks at — and NEVER a
 *     one-click POST. A POST to a guessed URL is an unattributable request to a stranger, sent by
 *     the app, on the strength of a word found in their own message.
 *  3. the confirmation names the full URL, not just the host, so what the user approves is the
 *     thing that was guessed rather than a summary of it.
 *
 * The word list is NOT in this file. It is per-language, so it lives where the app's per-language
 * data lives — a string-array resource the translators already own — and arrives as [words].
 */
object UnsubscribeBodyScan {

    /**
     * `<a href="...">text</a>`, tolerant of the attribute order and the quoting real mail uses.
     * Deliberately a regex over a parser: this reads one attribute and the anchor text out of a
     * document the app has already decided not to trust, and a full DOM would buy nothing here
     * except a second HTML implementation to keep correct.
     */
    private val ANCHOR = Regex(
        """<a\b[^>]*?href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val TAGS = Regex("<[^>]*>")

    /** A bare URL in a text/plain body, for the senders who write "To unsubscribe visit https://…". */
    private val BARE_URL = Regex("""https://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** How near a word must be to a bare URL, in characters, to be talking about it. One sentence. */
    private const val NEARBY_CHARS = 120

    /** A URL in the href scores this; the same word only in the anchor's TEXT scores less, because
     *  anchor text is prose and prose says "unsubscribe" about links that are not one. */
    private const val SCORE_IN_URL = 2
    private const val SCORE_IN_TEXT = 1

    /**
     * Below this an HTML anchor is not offered. Two = the word is in the URL itself, or in both the
     * anchor text and the URL. One weak signal is not enough: anchor text is prose, and prose says
     * "unsubscribe" about links that are not one.
     */
    private const val MIN_SCORE = 2

    /**
     * The plain-text threshold, and it is LOWER on purpose.
     *
     * A text/plain footer reads "To unsubscribe from this list visit https://l.example.com/u/7" —
     * the word is beside the URL and almost never inside it, because the URL is an opaque
     * subscriber token. Holding plain text to [MIN_SCORE] does not make it stricter, it makes it
     * DEAD: nothing would ever match, and a fallback that silently never fires is worse than one
     * that is honestly weaker, because nobody finds out. The proximity window is what carries the
     * weight here, and text/plain is only reached when the message has no HTML part at all.
     */
    private const val MIN_SCORE_PLAIN = 1

    /**
     * The best candidate in [html] (preferred) or [plain], or null when nothing scores high enough.
     *
     * Returns page URLs only — see the note above on why never a one-click POST. `https` only: an
     * `http` unsubscribe hands the click, and the identifier in it, to anyone on the path, and this
     * is already the low-confidence road.
     */
    fun scan(html: String?, plain: String?, words: List<String>): UnsubscribeOptions? {
        val needles = words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (needles.isEmpty()) return null
        val best = html?.let { bestAnchor(it, needles) } ?: plain?.let { bestBareUrl(it, needles) }
        return best?.let { UnsubscribeOptions(pageUrl = it) }
    }

    private fun bestAnchor(html: String, needles: List<String>): String? =
        ANCHOR.findAll(html)
            .mapNotNull { m ->
                val url = m.groupValues[1].trim()
                if (!url.startsWith("https://", ignoreCase = true)) return@mapNotNull null
                if (UnsubscribeHeader.hostOf(url) == null) return@mapNotNull null
                val text = TAGS.replace(m.groupValues[2], " ").lowercase()
                val inUrl = needles.any { it in url.lowercase() }
                val inText = needles.any { it in text }
                val score = (if (inUrl) SCORE_IN_URL else 0) + (if (inText) SCORE_IN_TEXT else 0)
                if (score >= MIN_SCORE) score to url else null
            }
            // The HIGHEST score, and on a tie the FIRST such anchor. A footer that offers both
            // "unsubscribe" and "manage preferences" puts the opt-out first far more often than not,
            // and picking the last would systematically prefer the preferences page.
            .maxByOrNull { it.first }
            ?.second

    /**
     * text/plain has no anchors, so the evidence is proximity: a bare URL with one of the words
     * within [NEARBY_CHARS] before it. Weaker than the HTML case by construction, which is why it
     * is only reached when there is no HTML part at all.
     */
    private fun bestBareUrl(plain: String, needles: List<String>): String? {
        val lower = plain.lowercase()
        return BARE_URL.findAll(plain)
            .mapNotNull { m ->
                val url = m.value.trimEnd('.', ',', ')', ';')
                if (UnsubscribeHeader.hostOf(url) == null) return@mapNotNull null
                val inUrl = needles.any { it in url.lowercase() }
                val window = lower.substring((m.range.first - NEARBY_CHARS).coerceAtLeast(0), m.range.first)
                val nearby = needles.any { it in window }
                val score = (if (inUrl) SCORE_IN_URL else 0) + (if (nearby) SCORE_IN_TEXT else 0)
                if (score >= MIN_SCORE_PLAIN) score to url else null
            }
            .maxByOrNull { it.first }
            ?.second
    }
}
