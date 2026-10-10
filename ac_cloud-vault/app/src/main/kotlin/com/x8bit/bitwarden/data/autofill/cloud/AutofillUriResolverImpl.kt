package com.x8bit.bitwarden.data.autofill.cloud

import android.content.Context
import android.content.pm.PackageManager
import com.bitwarden.network.service.DigitalAssetLinkService
import com.x8bit.bitwarden.data.autofill.accessibility.util.getSupportedBrowserOrNull
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** The Digital Asset Links relation that lets an app use a site's saved logins. */
private const val RELATION_GET_LOGIN_CREDS = "delegate_permission/common.get_login_creds"

/** The App Links relation: the site's owner declares the app as theirs. */
private const val RELATION_HANDLE_ALL_URLS = "delegate_permission/common.handle_all_urls"

/** A fill request cannot wait long; past this the app's own URI is used. */
private const val ASSET_LINKS_TIMEOUT_MS = 1_500L

/**
 * The platform side of [AutofillUriPolicy]: which packages are browsers, which are fleet apps
 * (same signing key as Cloud Vault), and the Digital Asset Links check for everything else.
 * Every failure (package not visible, network error, timeout) falls back to the app's own URI,
 * i.e. fails closed. Logs carry package and host names only, never field values.
 */
class AutofillUriResolverImpl(
    private val context: Context,
    private val digitalAssetLinkService: DigitalAssetLinkService,
) : AutofillUriResolver {

    /** "<package>|<host>" -> linked. Only answers the network actually gave are cached. */
    private val assetLinkCache = ConcurrentHashMap<String, Boolean>()

    override suspend fun resolveForFill(uri: String?, packageName: String?): String? =
        when (val decision = decide(uri, packageName)) {
            is AutofillUriPolicy.Decision.Use -> decision.uri
            is AutofillUriPolicy.Decision.VerifyAssetLinks -> {
                val linked = withTimeoutOrNull(ASSET_LINKS_TIMEOUT_MS) {
                    isLinked(decision.packageName, decision.host)
                } == true
                Timber.d(
                    "Autofill web domain from non-browser ${decision.packageName}: " +
                        "${decision.host} asset-links linked=$linked",
                )
                if (linked) decision.website else decision.fallback
            }
        }

    override fun resolveForSave(uri: String?, packageName: String?): String? =
        AutofillUriPolicy.decideForSave(
            requestUri = uri,
            packageName = packageName,
            isKnownBrowser = isKnownBrowser(packageName),
            isFleetSigned = isFleetSigned(packageName),
        )

    private fun decide(uri: String?, packageName: String?): AutofillUriPolicy.Decision =
        AutofillUriPolicy.decide(
            requestUri = uri,
            packageName = packageName,
            isKnownBrowser = isKnownBrowser(packageName),
            isFleetSigned = isFleetSigned(packageName),
        )

    private fun isKnownBrowser(packageName: String?): Boolean =
        packageName != null &&
            (packageName == CLOUD_BROWSER_PACKAGE || packageName.getSupportedBrowserOrNull() != null)

    /** Same signing certificate as Cloud Vault: one of the owner's fleet apps. */
    private fun isFleetSigned(packageName: String?): Boolean {
        if (packageName == null || packageName == context.packageName) return false
        return runCatching {
            context.packageManager.checkSignatures(context.packageName, packageName) ==
                PackageManager.SIGNATURE_MATCH
        }.getOrDefault(false)
    }

    private suspend fun isLinked(packageName: String, host: String): Boolean {
        val key = "$packageName|$host"
        assetLinkCache[key]?.let { return it }
        val fingerprint = signingFingerprintOrNull(packageName) ?: return false
        for (relation in listOf(RELATION_GET_LOGIN_CREDS, RELATION_HANDLE_ALL_URLS)) {
            val result = digitalAssetLinkService.checkDigitalAssetLinksRelations(
                sourceWebSite = "https://$host",
                targetPackageName = packageName,
                targetCertificateFingerprint = fingerprint,
                relations = listOf(relation),
            )
            val response = result.getOrNull() ?: return false
            if (response.linked) {
                assetLinkCache[key] = true
                return true
            }
        }
        assetLinkCache[key] = false
        return false
    }

    /** SHA-256 of the app's signing certificate as "AB:CD:...", or null if it is not visible. */
    @Suppress("DEPRECATION")
    private fun signingFingerprintOrNull(packageName: String): String? = runCatching {
        val info = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return@runCatching null
        MessageDigest
            .getInstance("SHA-256")
            .digest(signer.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    }.getOrNull()
}
