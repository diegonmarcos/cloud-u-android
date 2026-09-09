package app.sterna.ui.connect

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import app.sterna.BuildConfig
import app.sterna.R
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.jmap.JmapException
import app.sterna.core.jmap.OAuthMetadata
import app.sterna.core.jmap.Pkce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface OAuthCodeProgress {
    data object Idle : OAuthCodeProgress

    /** The browser must be sent to [authorizationUrl]; the answer comes back as a redirect. */
    data class AwaitingBrowser(val authorizationUrl: String) : OAuthCodeProgress

    data object Connecting : OAuthCodeProgress
}

/** One-shot terminal result of a sign-in (collected only while a screen is alive). */
sealed interface OAuthCodeOutcome {
    data object Success : OAuthCodeOutcome
    data class Error(val message: String) : OAuthCodeOutcome
}

/** App-scoped driver for the OAuth 2.0 authorization-code grant with PKCE (RFC 6749 §4.1 +
 *  RFC 7636), for servers that offer no device flow. App-scoped because the redirect comes back
 *  into `MainActivity`, possibly after the connect screen has been popped, and a screen-scoped
 * ViewModel would be gone before the code is exchanged. Nothing here is logged. */
class OAuthCodeSignIn(
    private val repo: MailRepository,
    private val scope: CoroutineScope,
    private val appContext: Context,
    /** The package the redirect belongs to. Injected so the test build's own id is followed. */
    private val applicationId: String = BuildConfig.APPLICATION_ID,
) {
    private val _progress = MutableStateFlow<OAuthCodeProgress>(OAuthCodeProgress.Idle)
    val progress: StateFlow<OAuthCodeProgress> = _progress.asStateFlow()

    private val _outcomes = MutableSharedFlow<OAuthCodeOutcome>(extraBufferCapacity = 1)
    val outcomes: SharedFlow<OAuthCodeOutcome> = _outcomes.asSharedFlow()

    /** The one request in flight, in memory only — see [PendingAuthorization]. */
    private val slot = AuthorizationRequestSlot()

    private var job: Job? = null

    /** `<applicationId>://oauth`, both in the authorization URL and in the token exchange. */
    val redirectUri: String get() = oauthRedirectUri(applicationId)

    /** Draw a verifier and a state, remember them, and expose the URL the screen sends the browser
     *  to. Nothing is sent from here: the user's tap is what leaves the app. */
    fun start(host: String, metadata: OAuthMetadata, email: String, accountName: String) {
        if (job?.isActive == true) return
        // An explicit start replaces whatever was in flight, even if the URL below cannot be built:
        // a request abandoned by the Back key never completes and nothing expires it. Not the
        // one-shot rule, which lives on the return, in the slot.
        slot.disarm()
        val verifier = Pkce.newCodeVerifier()
        val state = newOAuthState()
        val url = try {
            repo.authorizationUrl(metadata, redirectUri, state, Pkce.codeChallengeOf(verifier))
        } catch (t: Throwable) {
            // A non-https or unusable authorization endpoint (#2) — the URL is never half-built.
            fail(failureMessage(t, R.string.connect_oauth_failed))
            return
        }
        slot.arm(
            PendingAuthorization(
                state = state,
                codeVerifier = verifier,
                host = host,
                metadata = metadata,
                email = email.trim(),
                accountName = accountName,
            ),
        )
        _progress.value = OAuthCodeProgress.AwaitingBrowser(url)
    }

    /** A VIEW intent came in. True when it was ours and has been dealt with, false when it belongs
     *  to somebody else — in which case nothing of it has been read. */
    fun onRedirect(uri: Uri): Boolean {
        if (!isOAuthRedirect(uri.scheme, uri.host, applicationId)) return false
        val verdict = slot.redeem(
            state = param(uri, "state"),
            code = param(uri, "code"),
            error = param(uri, "error"),
        )
        when (verdict) {
            is RedirectVerdict.Exchange -> exchange(verdict)
            RedirectVerdict.NoPendingRequest -> fail(string(R.string.connect_oauth_code_lost))
            RedirectVerdict.StateMismatch -> fail(string(R.string.connect_oauth_code_mismatch))
            // The reader declined, or the server refused our client: its own word, and only if it
            // looks like one — oauthFailureSpec is where that vocabulary is closed.
            is RedirectVerdict.Refused -> fail(render(oauthFailureSpec(verdict.error, "", null)))
            RedirectVerdict.NothingUsable ->
                fail(render(oauthFailureSpec("", "", null, R.string.connect_oauth_failed)))
        }
        return true
    }

    /** Give up on the sign-in: no request is left in flight, so a late redirect exchanges nothing. */
    fun cancel() {
        job?.cancel()
        job = null
        slot.disarm()
        _progress.value = OAuthCodeProgress.Idle
    }

    private fun exchange(verdict: RedirectVerdict.Exchange) {
        val request = verdict.request
        // What is posted is tokenExchangeFor's answer, not four arguments spelled out here.
        val exchange = tokenExchangeFor(verdict, redirectUri)
        _progress.value = OAuthCodeProgress.Connecting
        job = scope.launch {
            try {
                val tokens = repo.exchangeCode(
                    metadata = exchange.metadata,
                    code = exchange.code,
                    redirectUri = exchange.redirectUri,
                    codeVerifier = exchange.codeVerifier,
                )
                // The account is persisted by the path the device flow already uses: it stores the
                // token endpoint and client id per account, so the refresher is grant-agnostic.
                val created = repo.addOAuthAccount(request.host, request.email, request.metadata, tokens, request.accountName)
                // The add may resolve onto an account already stored and refresh it; which sentence
                // that gets is accountAddedToast's.
                accountAddedToast(created, R.string.connect_account_added)?.let { toast(string(it)) }
                _outcomes.emit(OAuthCodeOutcome.Success)
                _progress.value = OAuthCodeProgress.Idle
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _progress.value = OAuthCodeProgress.Idle
                _outcomes.emit(OAuthCodeOutcome.Error(failureMessage(t, R.string.connect_oauth_failed)))
            }
        }
    }

    /** Say what the server said, in the protocol's own words, or the given fallback. */
    private fun failureMessage(t: Throwable, @StringRes fallbackRes: Int): String =
        render(oauthFailureSpec((t as? JmapException)?.oauthError.orEmpty(), "", null, fallbackRes))

    private fun render(spec: OAuthMessage): String =
        if (spec.arg == null) appContext.getString(spec.resId) else appContext.getString(spec.resId, spec.arg)

    /** Say it out loud and on the flow. `outcomes` has no replay and the screen may be gone during
     *  the browser trip, so an error that only travels on the flow reaches nobody. */
    private fun fail(message: String) {
        _progress.value = OAuthCodeProgress.Idle
        scope.launch {
            toast(message)
            _outcomes.emit(OAuthCodeOutcome.Error(message))
        }
    }

    /** A query parameter, or null — an opaque or malformed URI answers null instead of throwing. */
    private fun param(uri: Uri, name: String): String? =
        runCatching { uri.getQueryParameter(name) }.getOrNull()

    private fun string(@StringRes resId: Int) = appContext.getString(resId)

    private suspend fun toast(message: String) = withContext(Dispatchers.Main) {
        Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
    }
}
