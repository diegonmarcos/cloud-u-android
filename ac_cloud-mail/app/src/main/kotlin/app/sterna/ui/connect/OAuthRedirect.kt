package app.sterna.ui.connect

import app.sterna.core.jmap.OAuthMetadata
import app.sterna.core.jmap.Pkce

// The authorization-code round trip through the browser. Nothing here touches Android: the driver
// (OAuthCodeSignIn) parses the redirect and performs the network call.

/** The host half of the redirect this app registers, i.e. `<applicationId>://oauth`. */
const val OAUTH_REDIRECT_HOST = "oauth"

/** The redirect the authorization server sends the browser back to. Built from [applicationId],
 *  never from the literal `app.sterna`: the test build installs alongside production, and two
 *  packages claiming one scheme make the return non-deterministic. */
fun oauthRedirectUri(applicationId: String): String = "$applicationId://$OAUTH_REDIRECT_HOST"

/** True when a VIEW intent's [scheme]/[host] are this app's own OAuth redirect and nothing else. */
fun isOAuthRedirect(scheme: String?, host: String?, applicationId: String): Boolean =
    scheme.equals(applicationId, ignoreCase = true) && host.equals(OAUTH_REDIRECT_HOST, ignoreCase = true)

/** A fresh `state` for one authorization request (RFC 6749 §10.12), drawn from `SecureRandom`: a
 *  guessable one lets anything that can open a link on this phone hand the app an authorization
 *  code of its own choosing, and the account added is the attacker's. 43 chars is ~256 bits. */
fun newOAuthState(): String = Pkce.newCodeVerifier(43)

/** The one authorization request in flight, held in memory only. Nothing is persisted: on disk the
 *  `codeVerifier` would outlive the browser trip and have to be wiped on every path out. A process
 *  death loses the request, and the redirect says so ([RedirectVerdict.NoPendingRequest]). */
data class PendingAuthorization(
    val state: String,
    val codeVerifier: String,
    val host: String,
    val metadata: OAuthMetadata,
    val email: String,
    val accountName: String,
) {
    init {
        // No exchange without PKCE, ever. There is no fallback to a bare authorization code, so
        // a request that cannot prove possession cannot be built at all.
        require(codeVerifier.isNotBlank()) {
            "an authorization request without a code verifier could only be exchanged without PKCE"
        }
        require(state.isNotBlank()) { "an authorization request without a state cannot be matched" }
    }
}

/** What a redirect coming back from the browser means, decided before any network call. */
sealed interface RedirectVerdict {
    /** The only verdict that reaches the token endpoint. */
    data class Exchange(val code: String, val request: PendingAuthorization) : RedirectVerdict

    /** Nothing was in flight: the process died during the browser trip, or this is a stale link. */
    data object NoPendingRequest : RedirectVerdict

    /** Not our sign-in. The code is not exchanged. */
    data object StateMismatch : RedirectVerdict

    /** The server said no: the user refused, or the client is unknown. */
    data class Refused(val error: String) : RedirectVerdict

    data object NothingUsable : RedirectVerdict
}

/** Decide what a redirect is worth. The order is the guard, not the individual checks: something
 *  in flight, then the `state` we drew compared before `code` or `error` are looked at, then the
 *  server's answer. Consumption belongs to [AuthorizationRequestSlot]. */
fun redirectVerdict(
    pending: PendingAuthorization?,
    state: String?,
    code: String?,
    error: String?,
): RedirectVerdict {
    if (pending == null) return RedirectVerdict.NoPendingRequest
    // Blank never matches: a redirect with no state at all is not "the same as ours".
    if (state.isNullOrBlank() || state != pending.state) return RedirectVerdict.StateMismatch
    if (!error.isNullOrBlank()) return RedirectVerdict.Refused(error)
    if (code.isNullOrBlank()) return RedirectVerdict.NothingUsable
    return RedirectVerdict.Exchange(code, pending)
}

/** The four values the token endpoint is called with (RFC 6749 §4.1.3), as one comparable value. */
data class TokenExchange(
    val metadata: OAuthMetadata,
    val code: String,
    val redirectUri: String,
    val codeVerifier: String,
)

/** What to POST for a redirect that earned an exchange. The verifier is the one this request
 *  drew: a freshly drawn one is non-blank, passes every guard, and turns PKCE into decoration,
 *  because the challenge the server holds came from the other one. */
fun tokenExchangeFor(verdict: RedirectVerdict.Exchange, redirectUri: String): TokenExchange =
    TokenExchange(
        metadata = verdict.request.metadata,
        code = verdict.code,
        redirectUri = redirectUri,
        codeVerifier = verdict.request.codeVerifier,
    )

/** The one in-flight request, redeemed once: the browser can deliver the same redirect twice, so
 *  the request is cleared as soon as it produced a verdict — except on the two verdicts meaning
 *  "not our sign-in", where clearing would let any link cancel her real sign-in. */
class AuthorizationRequestSlot {
    private var pending: PendingAuthorization? = null

    @Synchronized
    fun arm(request: PendingAuthorization) {
        pending = request
    }

    @Synchronized
    fun disarm() {
        pending = null
    }

    @Synchronized
    fun isArmed(): Boolean = pending != null

    /** The verdict for this redirect, consuming the request unless it was not ours. */
    @Synchronized
    fun redeem(state: String?, code: String?, error: String?): RedirectVerdict {
        val verdict = redirectVerdict(pending, state, code, error)
        val keep = verdict is RedirectVerdict.NoPendingRequest || verdict is RedirectVerdict.StateMismatch
        if (!keep) pending = null
        return verdict
    }
}
