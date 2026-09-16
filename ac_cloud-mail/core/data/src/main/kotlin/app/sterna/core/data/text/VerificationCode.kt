package app.sterna.core.data.text

/**
 * Extract the two-factor / one-time verification code from a message the way a person would read
 * it: every plausible token is a candidate, each is scored on the evidence around it, the highest
 * score wins, and nothing is copied when the best candidate is still a guess.
 *
 * A naive `\d{6}` is deliberately NOT what this does. "482913" appears in order numbers, invoices,
 * prices, phone numbers, dates, years, tracking numbers and URLs — the six digits are identical in
 * every one of those, so the only thing that tells them apart is the context:
 *
 *   * an introducing phrase near the code ("your code is", "verification code", "security code",
 *     "one-time", "OTP", "2FA", "confirm", «código», "code de vérification", …)
 *   * the code alone on its own line, or alone inside a table cell
 *   * a cell styled larger than the body text — the classic 2FA layout ships the code in a
 *     `font-size:24px` `<td>` or `<div>` holding nothing else
 *   * presence in the subject line
 *
 * against the things that say "shaped like a code but not one":
 *
 *   * a year (1900–2100) — excluded outright
 *   * a price (a currency symbol, code or word beside it), a percentage
 *   * a number introduced by order/invoice/reference/tracking/phone vocabulary
 *   * digits that are part of a URL or query string (stripped before scanning)
 *   * card and tracking runs: a run of 11+ digits has no 4–8-digit boundary match inside it
 *
 * Confidence, not presence, is the decision: a plausible token with no evidence scores below
 * [CONFIDENT_THRESHOLD] and the caller is told no code was found rather than handed a guess.
 *
 * [bodyText] is the message's flattened text (e.g. [htmlToText] output, or the plain-text part);
 * [html], when present, is the raw markup and is scanned only for the large-font-cell signal.
 * [subject] is scanned separately and earns the subject bonus.
 */
fun extractVerificationCode(subject: String?, bodyText: String, html: String? = null): String? {
    val candidates = ArrayList<VerificationCodeCandidate>()
    subject?.takeIf { it.isNotBlank() }?.let {
        collectTextCandidates(normaliseText(it), candidates, inSubject = true)
    }
    collectTextCandidates(normaliseText(bodyText), candidates, inSubject = false)
    html?.let { collectMarkupCandidates(it, candidates) }
    return candidates.maxWithOrNull(VerificationCodeCandidate.byConfidence)
        ?.takeIf { it.score >= CONFIDENT_THRESHOLD }
        ?.code
}

/** One candidate and the confidence it earned. Private: the caller only sees the winner. */
private data class VerificationCodeCandidate(val code: String, val score: Int) {
    companion object {
        /**
         * Highest score first; a tie goes to the longer code (more information to hand over).
         *
         * Deliberately ASCENDING comparators: [Iterable.maxWithOrNull] treats the comparator as a
         * standard less-than order and keeps the element for which every compare is positive —
         * which is the comparator's GREATEST element. A `compareByDescending` comparator inverts
         * less-than, so its greatest element is the LOWEST score, and the winner would be the
         * weakest candidate in the message.
         */
        val byConfidence = compareBy<VerificationCodeCandidate> { it.score }
            .thenBy { it.code.length }
    }
}

/** Below this nothing is copied. It is deliberately above the bare-shape scores below. */
private const val CONFIDENT_THRESHOLD = 40

/** The evidence addends. Each maps to one bullet in the file's doc comment. */
private const val STRONG_PHRASE = 22
private const val MODERATE_PHRASE = 14
private const val SUBJECT_BONUS = 14
private const val OWN_LINE_BONUS = 20
private const val SPLIT_FORM_BONUS = 6
private const val ISOLATED_CELL_BONUS = 10
private const val BIG_FONT_BONUS = 26
private const val INTRODUCER_BONUS = 8

/** Introductions that name a one-time code in so many words. */
private val STRONG_PHRASES = listOf(
    "your code", "verification code", "verify code", "security code", "one-time", "one time",
    "otp", "2fa", "two-factor", "two factor", "passcode", "login code", "access code",
    "sign-in code", "signin code", "activation code", "confirmation code", "code is",
    "use this code", "enter this code", "código", "codigo", "code de vérification",
    "verificatiecode", "bestätigungscode", "código de verificación", "verification pin",
)

/** Weaker hints: a bare "code", "PIN", "confirm" or "verify" still points at one, nearby. */
private val MODERATE_PHRASES = listOf("code", "pin", "confirm", "verify")

/** If one of these words sits just before the token, the number is a reference, not a code. */
private val NEGATIVE_KEYWORDS = listOf(
    "order", "invoice", "reference", "ref", "tracking", "track", "ticket", "case", "receipt",
    "bill", "phone", "tel", "telephone", "fax", "mobile", "call", "dial", "passport", "license",
    "licence", "serial", "imei", "card", "ssn", "social", "zip", "postal", "sku",
    "quote", "quotation", "total", "amount", "price", "cost", "charge", "salary", "deposit",
    "payment", "due", "balance", "tax", "vat", "interest", "promo", "coupon", "voucher",
    "discount", "gift", "redeem", "sn", "appointment", "booking", "reservation", "flight",
    "seat", "po",
)

/**
 * A split-form code ("123 456", "AB-1234") whose leading word is ordinary English must not be
 * consumed as one token: "code 1234" would otherwise copy "code1234" instead of leaving "1234" to
 * be judged on its own. The blocked match falls through to the single-token scan.
 */
private val BLOCKED_LEADING_WORDS = setOf(
    "code", "no", "nr", "id", "ref", "tel", "fax", "pin", "key", "pass", "sku", "po", "sn",
    "sum", "due", "tax", "vat", "amt", "your", "the", "use", "enter", "this", "is", "at", "on",
    "in", "of", "for", "per",
)

/**
 * A 5–8 character run holding both letters and digits, or a 4–8 digit run. The boundaries are real
 * word boundaries, so a slice of a longer run never matches (10 digits, a card number, a tracking
 * number: none of them contains a shorter token with a boundary on both sides).
 */
private val SINGLE_CODE = Regex("""(?i)\b(?:\d{4,8}|(?=[a-z0-9]*[a-z])(?=[a-z0-9]*\d)[a-z0-9]{5,8})\b""")

/** Two digit groups joined by spaces/hyphens: "123 456", "12-34". Runs FIRST, so a preceding
 *  word's match ("is 123") can never steal the "123" this form needs. */
private val SPLIT_DIGITS = Regex("""(?i)\b[0-9]{2,4}[- ]+[0-9]{2,6}\b""")

/** A split form with letters in it: "AB-1234", "1234-AB", "A1B2-C3D4". */
private val SPLIT_ALPHA = Regex(
    """(?i)\b(?:[a-z]{2,4}[- ]+[0-9]{2,6}|[0-9]{2,6}[- ]+[a-z]{2,4}|[a-z0-9]{2,6}[- ]+[a-z0-9]{2,6})\b""",
)

/** Letters only: accepted only when a strong introduction stands immediately before the token. */
private val LETTERS_ONLY = Regex("""(?i)\b[a-z]{4,8}\b""")

/**
 * Common continuations of "your code …" that are grammar, not codes: "expires", "valid",
 * "worked". An all-letter candidate alphabetically shaped like "ABCDEF" still passes; these do not.
 */
private val NON_CODE_WORDS = setOf(
    "expires", "expired", "expiry", "valid", "invalid", "active", "inactive", "failed",
    "works", "worked", "wrong", "correct", "matches", "matched", "matching", "confirms",
    "confirmed", "still", "used", "below", "above", "soon", "now", "entered", "required",
)

/** A `<td>`/`<div>`/… whose inline style names a font-size at least 18px. */
private val BIG_FONT_ELEMENT = Regex(
    """(?is)<(td|div|p|span|h[1-6])\b[^>]*?font-size\s*:\s*(\d+(?:\.\d+)?)\s*(px|pt|em|rem)[^>]*>(.*?)</\1>""",
)

/** URLs and query strings: nothing inside them is code material, whatever it looks like. */
private val URLISH = Regex("""(?i)https?://[^\s<>"']+|www\.[^\s<>"']+""")

/** Email addresses: a local-part like "user1234" must not be read as a token. */
private val EMAILISH = Regex("""(?i)\b[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\b""")

/** Digit-group dates in any common separator style: 12/31/2024, 2024-12-31, 31.12.2024. */
private val DATE_LIKE = Regex("""\b\d{1,4}[-/.]\d{1,2}[-/.]\d{1,4}\b""")

/** Month-name dates, with and without the comma: "12 Dec 2024", "Dec 12, 2024". */
private val MONTH_DATE = Regex(
    """(?i)\b(?:\d{1,2}(?:st|nd|rd|th)? (?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]* \d{2,4}|\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]* \d{1,2},? \d{4})\b""",
)

/** Runs of digits and separators; a run of 11+ digits is a card/tracking shape, never a code. */
private val LONG_DIGIT_RUN = Regex("""\b\d[\d\s.\-]+\d\b""")

/** punctuation and currency markers checked in the few characters beside a token. */
private val CURRENCY_MARK = Regex("""(?i)[$€£¥₹₽₩₿]|(?:usd|eur|gbp|jpy|chf|cad|aud|sek|nok|dkk|pln|czk|hkd|sgd|nzd|dollar|euro|pound|yen|yuan|ruble|rupiah|reais|zł|kr)""")
private val PERCENT_AFTER = Regex("""^\s*%""")

/** Anything these say to look away from is removed before scanning, so it cannot be scored. */
private fun normaliseText(text: String): String =
    text.replace("\u00A0", " ")
        .replace(URLISH, " ")
        .replace(EMAILISH, " ")
        .replace(MONTH_DATE, " ")
        .replace(DATE_LIKE, " ")
        .replace(LONG_DIGIT_RUN) { m ->
            if (m.value.count { it.isDigit() } >= 11) " " else m.value
        }

private fun collectTextCandidates(
    text: String,
    out: MutableList<VerificationCodeCandidate>,
    inSubject: Boolean,
) {
    if (text.isBlank()) return

    // Split forms, digits first ("123 456" before "AB-1234"): the digit groups they carry must be
    // recognised as a code before the letter-led forms get a chance to swallow them ("is 123" must
    // not take the "123" that "123 456" needs). Accepted digit splits consume their range so the
    // parts are not also scored as weaker single tokens.
    val consumed = ArrayList<IntRange>()
    for (m in SPLIT_DIGITS.findAll(text)) {
        val code = normaliseCodeCandidate(m.value) ?: continue
        if (disqualified(text, m.range.first, m.range.last + 1)) continue
        var score = baseScore(code) + SPLIT_FORM_BONUS
        if (inSubject) score += SUBJECT_BONUS
        if (isolatedOnItsLine(text, m.range.first, m.value)) score += OWN_LINE_BONUS
        score += phraseBoost(text, m.range.first, m.range.last + 1)
        score += introducerBonus(text, m.range.first)
        consumed.add(m.range)
        out += VerificationCodeCandidate(code, score)
    }
    // Letter-led split forms ("AB-1234", "es 482913", "A1B2-C3D4"). A pure-letter leading group
    // that is an ordinary word does not become part of a code ("code 1234" must never copy
    // "code1234"); a foreign function word before the digits ("es 482913", "est 482913") is scored
    // honestly — the digits beside it score higher on the same evidence, and the best wins. The
    // range is deliberately NOT consumed: the digit group competes as its own candidate, so a
    // phrase-introduced "es 482913" still copies "482913", never "es482913".
    for (m in SPLIT_ALPHA.findAll(text)) {
        if (consumed.any { m.range.first in it }) continue
        val parts = m.value.trim().split(Regex("[- \t]+"))
        if (parts.size != 2) continue
        val leading = parts[0]
        if (leading.all { it.isLetter() } && leading.lowercase() in BLOCKED_LEADING_WORDS) continue
        val code = normaliseCodeCandidate(m.value) ?: continue
        if (disqualified(text, m.range.first, m.range.last + 1)) continue
        var score = baseScore(code) + SPLIT_FORM_BONUS
        if (inSubject) score += SUBJECT_BONUS
        if (isolatedOnItsLine(text, m.range.first, m.value)) score += OWN_LINE_BONUS
        score += phraseBoost(text, m.range.first, m.range.last + 1)
        score += introducerBonus(text, m.range.first)
        out += VerificationCodeCandidate(code, score)
    }

    for (m in SINGLE_CODE.findAll(text)) {
        if (consumed.any { m.range.first in it }) continue
        val raw = m.value
        // A bare 4-digit number in 1900–2100 is a year. Anything a code can be, a year cannot.
        if (raw.length == 4 && raw.all { it.isDigit() } && raw.toIntOrNull() in 1900..2100) continue
        if (disqualified(text, m.range.first, m.range.last + 1)) continue
        var score = baseScore(raw)
        if (inSubject) score += SUBJECT_BONUS
        if (isolatedOnItsLine(text, m.range.first, raw)) score += OWN_LINE_BONUS
        score += phraseBoost(text, m.range.first, m.range.last + 1)
        score += introducerBonus(text, m.range.first)
        out += VerificationCodeCandidate(raw, score)
    }

    // Letters-only codes ("ABCDEF") are indistinguishable from words without evidence, so only a
    // strong introduction immediately before the token promotes one — "your code is ABCDEF" — and
    // a common continuation word ("expires", "valid", …) never passes regardless of the phrase.
    for (m in LETTERS_ONLY.findAll(text)) {
        if (consumed.any { m.range.first in it }) continue
        if (m.value.lowercase() in NON_CODE_WORDS) continue
        if (!strongPhraseDirectlyBefore(text, m.range.first)) continue
        out += VerificationCodeCandidate(m.value, baseScore(m.value) + STRONG_PHRASE)
    }
}

/**
 * A cell holding exactly one code, in type larger than the body, is the 2FA layout — the markup is
 * the evidence. Cells that hold other text are scanned like ordinary text, with no markup bonus.
 */
private fun collectMarkupCandidates(html: String, out: MutableList<VerificationCodeCandidate>) {
    for (m in BIG_FONT_ELEMENT.findAll(html)) {
        val px = m.groupValues[2].toFloatOrNull() ?: 0f
        val pxEquivalent = px * when (m.groupValues[3].lowercase()) {
            "pt" -> 4f / 3f
            "em", "rem" -> 16f
            else -> 1f
        }
        if (pxEquivalent < 18f) continue
        val content = elementText(m.groupValues[4])
        if (content.isBlank()) continue
        val code = normaliseCodeCandidate(content)
        if (code == null) {
            // The cell holds more than a code — scan it as ordinary text, with no markup bonus.
            collectTextCandidates(normaliseText(content), out, inSubject = false)
            continue
        }
        out += VerificationCodeCandidate(code, baseScore(code) + ISOLATED_CELL_BONUS + BIG_FONT_BONUS)
    }
}

/** Tags stripped, entities decoded, whitespace collapsed — the readable text of an element. */
private fun elementText(inner: String): String =
    unescapeEntities(inner.replace(Regex("<[^>]+>"), ""))
        .replace(Regex("\\s+"), " ")
        .trim()

/**
 * Normalise a raw candidate (possibly with the split form's space or hyphen) and accept it only if
 * the result is a code shape. Letters-only never qualifies here: a styled cell holding a word is
 * marketing, not a code, and the phrase-adjacent path in [collectTextCandidates] is the place a
 * real alphabetic code is still found.
 */
private fun normaliseCodeCandidate(raw: String): String? {
    val code = raw.replace(Regex("[- \t]+"), "")
    if (code.length !in 4..8) return null
    val digits = code.count { it.isDigit() }
    val letters = code.length - digits
    val numeric = letters == 0
    val mixed = digits >= 1 && letters >= 1
    if (!numeric && !mixed) return null
    if (numeric && code.length == 4 && code.toIntOrNull() in 1900..2100) return null
    return code
}

/** What a shape is intrinsically worth, before any context. */
private fun baseScore(code: String): Int = when {
    code.length !in 4..8 -> 0
    code.all { it.isDigit() } -> when (code.length) {
        6 -> 30 // the classic one-time-password length
        4 -> 26 // bank-PIN length
        else -> 24
    }
    code.any { it.isLetter() } && code.any { it.isDigit() } -> 20
    code.all { it.isLetter() } -> 18
    else -> 0
}

/** Whether the neighbours say "this is a reference, not a code" loudly enough to refuse it. */
private fun disqualified(text: String, start: Int, end: Int): Boolean {
    if (containsAny(preceding(text, start, 12), NEGATIVE_KEYWORDS)) return true
    // Six characters before the token (a symbol sits directly against it) and ten after (a
    // currency WORD trails at a small distance: "123456 dollars").
    if (CURRENCY_MARK.containsMatchIn(preceding(text, start, 6) + following(text, end, 10))) return true
    if (PERCENT_AFTER.containsMatchIn(following(text, end, 3))) return true
    return false
}

/** The strongest introduction within reach of the token. */
private fun phraseBoost(text: String, start: Int, end: Int): Int {
    val wide = preceding(text, start, 120) + following(text, end, 120)
    if (containsAny(wide, STRONG_PHRASES)) return STRONG_PHRASE
    val near = preceding(text, start, 24) + following(text, end, 16)
    if (containsAny(near, MODERATE_PHRASES)) return MODERATE_PHRASE
    return 0
}

/**
 * A copula or label immediately before the token points at it: "the code for account 772211 IS
 * 883344" — the number after "is" is the code, and "code: 482913" says the same with a colon.
 */
private fun introducerBonus(text: String, start: Int): Int {
    val before = preceding(text, start, 6)
    if (Regex("(?i)\\bis\\b\\s*$").containsMatchIn(before)) return INTRODUCER_BONUS
    if (Regex("[:=]\\s*$").containsMatchIn(before)) return INTRODUCER_BONUS
    return 0
}

/** Case-insensitive word-boundary presence of any of [words] in [window]. */
private fun containsAny(window: String, words: List<String>): Boolean =
    words.any { word ->
        Regex("(?i)\\b" + Regex.escape(word) + "\\b").containsMatchIn(window)
    }

/**
 * Whether a strong introduction ends right before [start], with no digit run in between: "the
 * code is 123456 expires" introduced the NUMBER, not the word — the digit between the phrase and
 * the candidate means the phrase pointed somewhere else.
 */
private fun strongPhraseDirectlyBefore(text: String, start: Int): Boolean {
    val windowStart = (start - 24).coerceAtLeast(0)
    val window = text.substring(windowStart, start)
    if (window.any { it.isDigit() }) return false
    var lastEnd = -1
    for (phrase in STRONG_PHRASES) {
        val pattern = Regex("(?i)\\b" + Regex.escape(phrase) + "\\b")
        var from = 0
        while (true) {
            val m = pattern.find(window, from) ?: break
            lastEnd = maxOf(lastEnd, m.range.last + 1)
            from = m.range.last + 1
        }
    }
    // The phrase may trail a small separator: "code is ABCDEF" ends one char before the token.
    return lastEnd >= 0 && start - (windowStart + lastEnd) <= 4
}

private fun preceding(text: String, start: Int, count: Int): String =
    text.substring((start - count).coerceAtLeast(0), start)

private fun following(text: String, end: Int, count: Int): String =
    text.substring(end, (end + count).coerceAtMost(text.length))

/** The trimmed line that contains [start], its whitespace collapsed for comparison. */
private fun isolatedOnItsLine(text: String, start: Int, raw: String): Boolean {
    val lineStart = text.lastIndexOf('\n', start - 1) + 1
    val lineEnd = text.indexOf('\n', start).let { if (it == -1) text.length else it }
    val line = text.substring(lineStart, lineEnd).trim().replace(Regex("[ \\t]+"), " ")
    return line == raw.trim().replace(Regex("[ \\t]+"), " ")
}