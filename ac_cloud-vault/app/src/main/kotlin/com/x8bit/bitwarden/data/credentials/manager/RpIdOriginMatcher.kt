package com.x8bit.bitwarden.data.credentials.manager

import com.bitwarden.ui.platform.base.util.prefixHttpsIfNecessary
import com.x8bit.bitwarden.data.platform.manager.ResourceCacheManager
import com.x8bit.bitwarden.data.platform.util.parseDomainOrNull
import com.x8bit.bitwarden.data.platform.util.toUriOrNull

/**
 * Decides whether a WebAuthn relying party ID may be used from a given web origin.
 *
 * The rule is the one in the WebAuthn spec: the RP ID must be the origin's host, or a domain
 * suffix of it that is not a public suffix (so `squarespace.com` is valid for
 * `https://account.squarespace.com`, but `com` and `co.uk` never are). Public suffixes come from
 * the Public Suffix List bundled in the app (`ResourceCacheManager`).
 */
class RpIdOriginMatcher(
    private val resourceCacheManager: ResourceCacheManager,
) {

    /**
     * Returns the host of [origin] (`https://www.squarespace.com`, or a bare host), lower-cased, or
     * null if [origin] has none.
     */
    fun hostOf(origin: String): String? =
        origin
            .trim()
            .prefixHttpsIfNecessary()
            .toUriOrNull()
            ?.host
            ?.lowercase()
            ?.trimEnd('.')
            ?.takeUnless { it.isEmpty() }

    /**
     * Returns true if [relyingPartyId] is acceptable for [origin].
     */
    fun isRpIdValidForOrigin(relyingPartyId: String, origin: String): Boolean {
        val rpId = relyingPartyId.trim().lowercase().trimEnd('.')
        val host = hostOf(origin) ?: return false
        if (rpId.isEmpty()) return false
        if (rpId == host) return true
        if (!host.endsWith(".$rpId")) return false

        // A strict suffix of the host: it is only valid when it is not a public suffix, that is,
        // when it is at least as long as the registrable domain (eTLD+1) of the host.
        val registrableDomain = "https://$host"
            .toUriOrNull()
            ?.parseDomainOrNull(resourceCacheManager)
            ?: return false
        return rpId == registrableDomain || rpId.endsWith(".$registrableDomain")
    }
}
