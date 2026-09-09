package app.sterna.core.jmap

    /** Entry point / constants for the JMAP protocol layer (RFC 8620 core, RFC 8621 mail). */
object Jmap {
    const val CORE_CAPABILITY = "urn:ietf:params:jmap:core"
    const val MAIL_CAPABILITY = "urn:ietf:params:jmap:mail"
    const val SUBMISSION_CAPABILITY = "urn:ietf:params:jmap:submission"
    const val VACATION_CAPABILITY = "urn:ietf:params:jmap:vacationresponse"
    const val QUOTA_CAPABILITY = "urn:ietf:params:jmap:quota"
    const val SIEVE_CAPABILITY = "urn:ietf:params:jmap:sieve"

    /** VAPID for JMAP WebPush (RFC 9749); advertises the server's application key. */
    const val WEBPUSH_VAPID_CAPABILITY = "urn:ietf:params:jmap:webpush-vapid"

    /** Well-known path for JMAP autodiscovery (RFC 8620 §2.2). */
    const val WELL_KNOWN_PATH = "/.well-known/jmap"

        /**
         * The per-id "failure" type a batched `Email/set` reports for ids it never got an answer for:
         */
    const val SET_ERROR_TRANSPORT = "sterna:transportFailed"

    /** Well-known path for OAuth 2.0 authorization-server metadata (RFC 8414). */
    const val OAUTH_METADATA_PATH = "/.well-known/oauth-authorization-server"

        /** OAuth client id Sterna identifies as. Stalwart accepts an arbitrary id (no
         *  pre-registration); servers requiring dynamic registration can adopt it too. */
    const val OAUTH_CLIENT_ID = "sterna"

    /** Scopes requested for the device flow: JMAP access + a refresh token. */
    const val OAUTH_SCOPE =
        "$CORE_CAPABILITY $MAIL_CAPABILITY $SUBMISSION_CAPABILITY offline_access"

        /** Build a JMAP Session URL from user input. Accepts a bare host, a base URL or a full
         *  well-known URL, and normalises to "https://host/.well-known/jmap". A pasted session URL
         *  (ending in "/session") is used verbatim. */
    fun sessionUrlFor(serverInput: String): String {
        val s = normalise(serverInput)
        if (s.isEmpty()) return s
        return if (s.endsWith(WELL_KNOWN_PATH) || s.endsWith("/session")) s else "$s$WELL_KNOWN_PATH"
    }

        /** Candidate session URLs for a manually-entered server, most likely first. A URL that
         *  [sessionUrlFor] already resolves yields itself only; an input with another path also tries
         *  "<input>/session" and the host's root well-known. Empty for blank input. */
    fun sessionUrlCandidates(serverInput: String): List<String> {
        val s = normalise(serverInput)
        if (s.isEmpty()) return emptyList()
        if (s.endsWith(WELL_KNOWN_PATH) || s.endsWith("/session")) return listOf(s)
        val pathStart = s.indexOf('/', s.indexOf("://") + 3)
        if (pathStart < 0) return listOf("$s$WELL_KNOWN_PATH") // bare host
        val root = s.substring(0, pathStart)
        return listOf("$s/session", "$s$WELL_KNOWN_PATH", "$root$WELL_KNOWN_PATH").distinct()
    }

    /** Trim, default the scheme to https, drop a trailing slash. */
    private fun normalise(serverInput: String): String {
        var s = serverInput.trim()
        if (s.isEmpty()) return s
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        return s.removeSuffix("/")
    }

        /** Candidate server hosts to probe when autodiscovering the JMAP server for an email address
         *  (RFC 8620 §2.2). The email domain comes first — its `/.well-known/jmap` typically redirects
         *  to the real session endpoint — then the conventional `mail.`/`jmap.`/`api.` subdomains.
         *  Empty for a malformed address. */
    fun autodiscoverHosts(email: String): List<String> {
        val domain = email.substringAfter('@', "").trim().lowercase().removeSuffix(".")
        if (domain.isEmpty() || !domain.contains('.')) return emptyList()
        return listOf(domain, "mail.$domain", "jmap.$domain", "api.$domain")
    }

    /** Build the OAuth metadata URL for a host (mirrors [sessionUrlFor]). */
    fun oauthMetadataUrlFor(serverInput: String): String {
        var s = serverInput.trim()
        if (s.isEmpty()) return s
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        s = s.removeSuffix("/").removeSuffix(WELL_KNOWN_PATH)
        return if (s.endsWith(OAUTH_METADATA_PATH)) s else "$s$OAUTH_METADATA_PATH"
    }
}
