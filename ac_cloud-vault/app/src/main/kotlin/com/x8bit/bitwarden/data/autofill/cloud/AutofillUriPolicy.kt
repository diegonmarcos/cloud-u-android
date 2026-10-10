package com.x8bit.bitwarden.data.autofill.cloud

import java.net.URI

/** The fleet's own browser (ac_cloud-browser). Its WebView reports webDomain natively. */
const val CLOUD_BROWSER_PACKAGE: String = "com.diegonmarcos.cloudbrowser"

private const val ANDROID_APP_PREFIX: String = "androidapp://"

/**
 * Which URI a fill (or save) request is matched against, i.e. which vault logins it may see.
 *
 * A browser tells the autofill framework the page's domain (`webDomain`) and we believe it: that
 * is the browser's job. Any other app can put any `webDomain` on its views, so a phishing app
 * that claims `bank.example` must not be shown the bank's login. Such an app is matched by its
 * own package (`androidapp://<package>`, the same rule Bitwarden uses for native apps, plus its
 * equivalent-domain mapping) unless the claimed site's Digital Asset Links file names the app.
 *
 * Trusted to report a web domain:
 * - the browsers Bitwarden knows (the compatibility list in autofill_service_configuration.xml);
 * - [CLOUD_BROWSER_PACKAGE];
 * - fleet apps signed with the same key as Cloud Vault (their WebViews host the owner's sites).
 */
object AutofillUriPolicy {

    /** What to match a request against. */
    sealed interface Decision {
        /** Match against [uri] (null: nothing to match, only the "open vault" entry is offered). */
        data class Use(val uri: String?) : Decision

        /**
         * The app is not a trusted browser: match against [website] only if the site at [host]
         * lists the app in its Digital Asset Links, otherwise against [fallback].
         */
        data class VerifyAssetLinks(
            val website: String,
            val host: String,
            val packageName: String,
            val fallback: String?,
        ) : Decision
    }

    /** `androidapp://<package>`. */
    fun appUri(packageName: String): String = "$ANDROID_APP_PREFIX$packageName"

    /** True for an `androidapp://` URI. */
    fun isAppUri(uri: String?): Boolean = uri?.startsWith(ANDROID_APP_PREFIX) == true

    /** The lower-cased host of a web URI, or null when it has none. */
    fun hostOf(uri: String?): String? =
        uri
            ?.takeUnless { isAppUri(it) }
            ?.let { runCatching { URI(it).host }.getOrNull() }
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

    /**
     * Decides which URI a fill request is matched against.
     *
     * @param requestUri What the parser derived: the page's website, or the app URI.
     * @param packageName The package that owns the window being filled.
     * @param isKnownBrowser Whether [packageName] is on the browser list.
     * @param isFleetSigned Whether [packageName] is signed with Cloud Vault's own key.
     */
    fun decide(
        requestUri: String?,
        packageName: String?,
        isKnownBrowser: Boolean,
        isFleetSigned: Boolean,
    ): Decision {
        val fallback = packageName?.let(::appUri)
        if (requestUri == null || isAppUri(requestUri)) return Decision.Use(requestUri)
        if (isKnownBrowser || packageName == CLOUD_BROWSER_PACKAGE || isFleetSigned) {
            return Decision.Use(requestUri)
        }
        val host = hostOf(requestUri)
        if (host == null || packageName == null) return Decision.Use(fallback)
        return Decision.VerifyAssetLinks(
            website = requestUri,
            host = host,
            packageName = packageName,
            fallback = fallback,
        )
    }

    /**
     * The same decision for a save request, which must answer synchronously: an unverified app's
     * claimed website is never written into the vault, the app URI is.
     */
    fun decideForSave(
        requestUri: String?,
        packageName: String?,
        isKnownBrowser: Boolean,
        isFleetSigned: Boolean,
    ): String? = when (val d = decide(requestUri, packageName, isKnownBrowser, isFleetSigned)) {
        is Decision.Use -> d.uri
        is Decision.VerifyAssetLinks -> d.fallback
    }
}
