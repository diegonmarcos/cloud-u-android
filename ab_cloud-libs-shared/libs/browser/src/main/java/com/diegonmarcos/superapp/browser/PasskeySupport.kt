package com.diegonmarcos.superapp.browser

import android.content.Intent
import android.os.Bundle

/**
 * Passkeys (WebAuthn) in the browser. Android WebView ships WebAuthn OFF; two layers here:
 *
 *  1. [modeFor] / [apply]: turn on `WebSettingsCompat.setWebAuthenticationSupport(FOR_BROWSER)` where the
 *     installed WebView has the WEB_AUTHENTICATION feature. FOR_BROWSER is the only level that may assert
 *     another site's origin (accounts.google.com); FOR_APP covers only the app's own asset-linked origins and is
 *     useless for a browser. Credential providers (Google Password Manager) answer FOR_BROWSER only for a
 *     PRIVILEGED caller (package + signing cert on the provider's allowlist), so for a sideloaded build this can
 *     still be refused at the provider. That is why layer 2 exists.
 *  2. [shouldOfferSecureBrowser] / [secureBrowserIntent]: when the page cannot be served, offer to carry on in the
 *     system browser (a Custom Tab where available), where passkeys always work. We never see a password or a
 *     passkey in either path; the platform and the provider do.
 *
 * Pure Kotlin, no WebView: the decisions are unit-testable.
 */
object PasskeySupport {

    /** Support level asked of WebView; mapped to WebSettingsCompat's constants in [apply]. */
    enum class Mode { FOR_BROWSER }

    /** `featureSupported` is `WebViewFeature.isFeatureSupported(WEB_AUTHENTICATION)`; null = leave WebView as is. */
    fun modeFor(featureSupported: Boolean, enabled: Boolean = true): Mode? =
        if (featureSupported && enabled) Mode.FOR_BROWSER else null

    /** Gate and set. Returns the mode actually requested (null when the WebView cannot do WebAuthn). */
    fun apply(settings: android.webkit.WebSettings, enabled: Boolean = true): Mode? {
        val m = modeFor(androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_AUTHENTICATION), enabled)
            ?: return null
        runCatching {
            androidx.webkit.WebSettingsCompat.setWebAuthenticationSupport(
                settings, androidx.webkit.WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER)
        }.onFailure { return null }
        return m
    }

    private val GOOGLE_HOSTS = setOf("accounts.google.com", "myaccount.google.com", "accounts.youtube.com")

    /** Google's passkey challenge screens. A hint only: they load fine when WebAuthn works. */
    fun isPasskeyChallengeUrl(url: String?): Boolean {
        val u = runCatching { java.net.URI(url?.trim().orEmpty()) }.getOrNull() ?: return false
        if (u.scheme?.lowercase() != "https" || u.host?.lowercase() !in GOOGLE_HOSTS) return false
        val p = u.path.orEmpty().lowercase()
        return "/challenge/pk" in p || "/challenge/webauthn" in p || "/challenge/passkey" in p
    }

    private val UNSUPPORTED = Regex(
        "(passkey|security key|webauthn)[^.]{0,200}?(does not|doesn't|isn't|is not|not) support|" +
            "(browser|app)[^.]{0,80}?(does not|doesn't|not) support[^.]{0,60}?(passkey|security key|webauthn)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** The "browser you're using does not support ..." page, from its visible text. */
    fun isUnsupportedPage(url: String?, bodyText: String?): Boolean {
        val u = runCatching { java.net.URI(url?.trim().orEmpty()) }.getOrNull() ?: return false
        if (u.scheme?.lowercase() != "https" || u.host?.lowercase() !in GOOGLE_HOSTS) return false
        return UNSUPPORTED.containsMatchIn(bodyText.orEmpty().take(6000))
    }

    /** JS failures that mean "WebAuthn cannot be served here" (not a user cancel, which is also NotAllowedError after a prompt). */
    fun isWebAuthnFailure(errorName: String?, elapsedMs: Long): Boolean = when (errorName) {
        "NotSupportedError", "SecurityError", "InvalidStateError" -> true
        "NotAllowedError" -> elapsedMs < 400   // refused before any UI could have been shown = no provider/origin
        else -> false
    }

    /**
     * Offer the secure browser? Any of: the unsupported page, a WebAuthn call that failed, or a known
     * passkey-challenge URL while this WebView has no WebAuthn at all ([mode] == null).
     */
    fun shouldOfferSecureBrowser(url: String?, bodyText: String?, jsFailure: Boolean, mode: Mode?): Boolean =
        isUnsupportedPage(url, bodyText) || (jsFailure && url?.startsWith("https://") == true) ||
            (mode == null && isPasskeyChallengeUrl(url))

    /** Wraps navigator.credentials.get/create and reports a failure to `CloudPasskey.failed(name, ms)`. */
    const val HOOK_JS = "(function(){var c=navigator.credentials;if(!c||c.__cb)return;c.__cb=1;" +
        "['get','create'].forEach(function(k){var f=c[k];if(!f)return;c[k]=function(o){var t=Date.now();" +
        "if(!o||!o.publicKey)return f.apply(c,arguments);" +
        "return f.apply(c,arguments).catch(function(e){try{CloudPasskey.failed(String(e&&e.name),Date.now()-t)}catch(x){}throw e})}})})();"

    const val JS_BRIDGE = "CloudPasskey"

    private const val EXTRA_CUSTOM_TAB_SESSION = "android.support.customtabs.extra.SESSION"

    /**
     * The browser to hand the flow to: the user's default unless that is us (then the first other browser),
     * null when there is none. [candidates] = packages that handle an https VIEW intent.
     */
    fun pickBrowserPackage(candidates: List<String>, own: String, default: String?): String? {
        val others = candidates.filter { it != own }
        return if (default != null && default != own && default in others) default else others.firstOrNull()
    }

    /** What [secureBrowserIntent] builds, as plain data (the JVM tests have no android.jar behaviour). */
    data class Launch(val url: String, val browserPackage: String?, val customTab: Boolean = true)

    /** Only https is ever sent out; null otherwise. */
    fun secureBrowserLaunch(url: String, browserPackage: String?): Launch? =
        if (url.trim().lowercase().startsWith("https://")) Launch(url.trim(), browserPackage) else null

    /**
     * VIEW intent that opens as a Custom Tab on a browser that supports them (the session extra is the Custom
     * Tabs launch contract; browsers without support open it as a normal tab).
     */
    fun secureBrowserIntent(l: Launch): Intent {
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(l.url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (l.customTab) i.putExtras(Bundle().apply { putBinder(EXTRA_CUSTOM_TAB_SESSION, null) })
        if (l.browserPackage != null) i.setPackage(l.browserPackage)
        return i
    }
}
