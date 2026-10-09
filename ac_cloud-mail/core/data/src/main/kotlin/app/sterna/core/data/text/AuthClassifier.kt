package app.sterna.core.data.text

/**
 * Rule "G0 _ Auth": is this message part of signing in or proving who you are? Three EXCLUSIVE classes,
 * named like the fleet's server-side mail rule axes (A0 _ SIZE ... F0 _ SENDER, each with Aa/Ab/... members):
 *
 *  - [CODE]  Ga Code          a verification or one-time code is present
 *  - [LINK]  Gb Link to auth  no code, but a magic-link / verify / confirm / sign-in / reset / activate link
 *  - [NONE]  Gc No Auth       everything else
 *
 * [value] is what the `emails.authClass` column stores; NULL there means "not classified yet".
 */
enum class AuthClass(val value: Int, val id: String, val label: String) {
    NONE(0, "Gc", "Gc No Auth"),
    CODE(1, "Ga", "Ga Code"),
    LINK(2, "Gb", "Gb Link to auth");

    companion object {
        const val RULE = "G0 _ Auth"
        fun of(value: Int?): AuthClass? = entries.firstOrNull { it.value == value }
    }
}

/**
 * The classifier. A code is decided by [extractVerificationCode], the extractor behind the reading pane's
 * Copy Code (`verificationCodeFromMessage` calls it), so the two can never disagree about what a code is;
 * a message with both a code and a link is therefore always [AuthClass.CODE]. Cheap enough to run on the
 * preview at ingest, then again on the body when it arrives ([classify] takes the markup when it has it).
 */
object AuthClassifier {
    fun classify(subject: String?, bodyText: String, html: String? = null): AuthClass {
        if (extractVerificationCode(subject = subject, bodyText = bodyText, html = html) != null) return AuthClass.CODE
        return if (hasAuthLink(subject, bodyText, html)) AuthClass.LINK else AuthClass.NONE
    }

    /** URL patterns: an auth verb in the path or query of a link. */
    private val authUrl = Regex(
        "(verif|confirm|magic|sign-?in|log-?in|reset|activat|passwordless|one-?time|otp|auth/|/auth|recover)",
        RegexOption.IGNORE_CASE,
    )
    private val urlInText = Regex("""https?://[^\s"'<>()\[\]]+""", RegexOption.IGNORE_CASE)
    private val hrefIn = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /** Link text / call-to-action phrases, for the preview that carries words but rarely the URL. */
    private val authPhrase = Regex(
        "(" +
            "(verify|confirm|validate) (your )?(e-?mail|account|address|identity|sign-?up|registration)" +
            "|reset (your )?password|password reset|forgot(ten)? (your )?password|choose a new password" +
            "|magic link|sign-?in link|log-?in link|one-?time (sign|log)-?in" +
            "|(sign|log)[ -]?in to (your|complete|continue)" +
            "|activate (your )?(account|membership)" +
            "|(click|tap) (the )?(link|button) below to (verify|confirm|sign|log|reset|activate)" +
            ")",
        RegexOption.IGNORE_CASE,
    )

    internal fun hasAuthLink(subject: String?, bodyText: String, html: String?): Boolean {
        val urls = buildList {
            urlInText.findAll(bodyText).forEach { add(it.value) }
            html?.let { h -> hrefIn.findAll(h).forEach { add(it.groupValues[1]) } }
        }
        // Only the part after the host is judged: "login.example.com" in a footer is not a sign-in link.
        if (urls.any { authUrl.containsMatchIn(afterHost(it)) }) return true
        val words = (subject.orEmpty() + "\n" + bodyText)
        return authPhrase.containsMatchIn(words)
    }

    private fun afterHost(url: String): String {
        val start = url.indexOf("://").let { if (it >= 0) it + 3 else 0 }
        val slash = url.indexOf('/', start)
        return if (slash < 0) "" else url.substring(slash)
    }
}
