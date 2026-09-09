package app.sterna.util

/**
 * Strips known tracking query parameters (utm_*, fbclid, gclid, …) from a URL
 * before it is opened, so a sender can't tell when — or whether — a link was
 * clicked. Pure string handling (no android.net.Uri) so it is JVM-unit-testable.
 *
 * Deliberately conservative: only parameters that exist solely to track are
 * removed. Generic names (id, ref, source, …) are left alone so functional
 * links never break.
 */
object LinkCleaner {
    /** Exact parameter names (lower-cased) that only ever carry tracking data. */
    private val TRACKING_PARAMS = setOf(
        "gclid", "gclsrc", "dclid", "wbraid", "gbraid",        // Google Ads
        "fbclid",                                               // Facebook
        "msclkid",                                              // Microsoft Ads
        "yclid",                                                // Yandex
        "twclid",                                               // Twitter / X
        "ttclid",                                               // TikTok
        "igshid", "igsh",                                       // Instagram
        "mc_eid", "mc_cid",                                     // Mailchimp
        "_hsenc", "_hsmi", "__hssc", "__hstc", "__hsfp", "hsctatracking", // HubSpot
        "mkt_tok",                                              // Marketo
        "ml_subscriber", "ml_subscriber_hash",                 // MailerLite
        "vero_id", "vero_conv",                                 // Vero
        "oly_anon_id", "oly_enc_id",                            // Omeda
        "s_cid", "elqtrack", "elqtrackid",                      // Adobe / Oracle Eloqua
        "pk_campaign", "pk_kwd", "pk_source", "pk_medium", "pk_content", "pk_cid", // Piwik
        "piwik_campaign", "piwik_kwd", "matomo_campaign", "matomo_kwd",            // Matomo
        "spm", "scm",                                           // Alibaba
        "wickedid",                                             // Wicked Reports
    )

    /** Any parameter whose (lower-cased) name starts with one of these is tracking. */
    private val TRACKING_PREFIXES = listOf("utm_")

    /**
     * Return [url] with tracking parameters removed. The scheme, host, path and
     * fragment are preserved; the URL is returned unchanged if it has no query
     * or can't be parsed.
     */
    fun strip(url: String): String {
        val qIndex = url.indexOf('?')
        if (qIndex < 0) return url
        val base = url.substring(0, qIndex)

        var rest = url.substring(qIndex + 1)
        // A fragment (#…) follows the query; keep it untouched.
        var fragment = ""
        val hIndex = rest.indexOf('#')
        if (hIndex >= 0) {
            fragment = rest.substring(hIndex) // includes '#'
            rest = rest.substring(0, hIndex)
        }
        if (rest.isEmpty()) return url

        val kept = rest.split('&').filter { pair ->
            pair.isNotEmpty() && !isTracking(decodeName(pair.substringBefore('=')).lowercase())
        }
        // No tracking params found → return the original string verbatim.
        if (kept.size == rest.split('&').count { it.isNotEmpty() }) return url

        val query = kept.joinToString("&")
        return buildString {
            append(base)
            if (query.isNotEmpty()) {
                append('?')
                append(query)
            }
            append(fragment)
        }
    }

    private fun isTracking(name: String): Boolean =
        name in TRACKING_PARAMS || TRACKING_PREFIXES.any { name.startsWith(it) }

    /**
     * Percent-decode a parameter name so an encoded form (e.g. "utm%5Fsource") can't slip a
     * tracker past the matcher. '+' is treated as a space per form-encoding. Falls back to the
     * raw name if the encoding is malformed.
     */
    private fun decodeName(name: String): String = runCatching {
        java.net.URLDecoder.decode(name.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(name)
}
