package app.sterna.core.jmap

    /** Any failure talking to a JMAP server (transport, HTTP, parsing, or a JMAP-level error).
     *  [httpCode] carries the HTTP status when the failure was an HTTP response, null for
     *  transport/parse errors — autodiscovery uses it to tell "reached the server but the credentials
     *  were rejected" (401/403) from "no server here". */
class JmapException(
    message: String,
    cause: Throwable? = null,
    val httpCode: Int? = null,
    /** The JMAP method-level error type (e.g. "anchorNotFound"), when the failure was one. */
    val errorType: String? = null,
        /** The OAuth protocol's `error` field (RFC 6749 §5.2), when the failure was an OAuth error
         *  response — never the response body and never `error_description`: a short closed vocabulary
         *  chosen by the protocol, where the body is the caller's tokens. */
    val oauthError: String? = null,
        /**
         * The server REFUSED this delivery, and replaying it can only duplicate what already went out.
         */
    val permanent: Boolean = false,
) : Exception(message, cause)
