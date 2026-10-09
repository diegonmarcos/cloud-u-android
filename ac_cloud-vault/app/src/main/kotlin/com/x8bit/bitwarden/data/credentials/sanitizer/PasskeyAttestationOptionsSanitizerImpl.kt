package com.x8bit.bitwarden.data.credentials.sanitizer

import com.x8bit.bitwarden.data.credentials.model.PasskeyAttestationOptions

/**
 * Default implementation of [PasskeyAttestationOptionsSanitizer].
 */
object PasskeyAttestationOptionsSanitizerImpl : PasskeyAttestationOptionsSanitizer {
    override fun sanitize(options: PasskeyAttestationOptions): PasskeyAttestationOptions {
        // The AliExpress Android app (com.alibaba.aliexpresshd) incorrectly appends a newline
        // to the user.id field when creating a passkey. This causes the operation to fail
        // downstream. As a workaround, we detect this specific scenario, trim the newline, and
        // re-serialize the JSON request.
        val trimmed = if (options.relyingParty.id == ALIEXPRESS_RP_ID &&
            options.user.id.endsWith("\n")
        ) {
            options.copy(
                user = options.user.copy(id = options.user.id.trimEnd('\n')),
            )
        } else {
            options
        }
        return trimmed.withDisplayNames()
    }

    /**
     * WebAuthn Level 3 made `rp.name` and `user.displayName` optional (and browsers forward
     * what the site sent), but the SDK still wants every one of them. Fill the gaps from the
     * data the request does carry rather than rejecting the request.
     */
    private fun PasskeyAttestationOptions.withDisplayNames(): PasskeyAttestationOptions {
        if (relyingParty.name != null && user.name != null && user.displayName != null) {
            return this
        }
        val userName = user.name ?: user.displayName.orEmpty()
        return copy(
            relyingParty = relyingParty.copy(name = relyingParty.name ?: relyingParty.id.orEmpty()),
            user = user.copy(
                name = userName,
                displayName = user.displayName ?: userName,
            ),
        )
    }
}

private const val ALIEXPRESS_RP_ID = "m.aliexpress.com"
