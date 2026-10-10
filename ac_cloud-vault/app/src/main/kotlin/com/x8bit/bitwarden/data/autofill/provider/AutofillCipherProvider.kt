package com.x8bit.bitwarden.data.autofill.provider

import com.x8bit.bitwarden.data.autofill.model.AutofillCipher

/**
 * A service for getting [AutofillCipher]s.
 */
interface AutofillCipherProvider {
    /**
     * Returns `true` if the vault for the current user is locked. This suspends in order to return
     * a value only after any unlocking vaults have fully unlocked (or failed to do so).
     */
    suspend fun isVaultLocked(): Boolean

    /**
     * Get all [AutofillCipher.Card]s for the current user.
     */
    suspend fun getCardAutofillCiphers(): List<AutofillCipher.Card>

    /**
     * Get all [AutofillCipher.Login]s for the current user.
     *
     * @param includeTotpCode Also compute each login's current TOTP code (Cloud Vault: only
     * when the page has a one-time-code field to fill).
     */
    suspend fun getLoginAutofillCiphers(
        uri: String,
        includeTotpCode: Boolean = false,
    ): List<AutofillCipher.Login>

    /**
     * Cloud Vault: get all [AutofillCipher.Identity]s for the current user, for an ID-document
     * field. Empty while the vault is locked. Not matched against the page: the user picks one.
     */
    suspend fun getIdentityAutofillCiphers(): List<AutofillCipher.Identity> = emptyList()
}
