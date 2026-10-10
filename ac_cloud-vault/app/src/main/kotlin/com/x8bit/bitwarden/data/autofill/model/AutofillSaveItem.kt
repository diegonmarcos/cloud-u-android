package com.x8bit.bitwarden.data.autofill.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents raw data from a user completing a form and deciding to save that data to their vault
 * via the autofill framework.
 */
sealed class AutofillSaveItem : Parcelable {

    /**
     * Data for a card item.
     *
     * @property number The actual card number (if applicable).
     * @property expirationMonth The expiration month in string form (if applicable).
     * @property expirationYear The expiration year in string form (if applicable).
     * @property securityCode The security code for the card (if applicable).
     * @property cardholderName The name on the card (if applicable).
     */
    @Parcelize
    data class Card(
        val cardholderName: String?,
        val number: String?,
        val expirationMonth: String?,
        val expirationYear: String?,
        val securityCode: String?,
        val brand: String?,
    ) : AutofillSaveItem()

    /**
     * Data for a login item.
     *
     * @property username The username/email for the login (if applicable).
     * @property password The password for the login (if applicable).
     * @property uri The URI associated with the login (if applicable).
     */
    @Parcelize
    data class Login(
        val username: String?,
        val password: String?,
        val uri: String?,
    ) : AutofillSaveItem()

    /**
     * Cloud Vault: an ID document another fleet app asked the vault to add (Cloud Account's
     * Import, through the add-identity entry point). It opens the new-Identity screen prefilled;
     * nothing is saved until the user taps Save there.
     *
     * @property documentType The document's type label as the caller named it ("DNI",
     * "Passport", "Personalausweis", ...), see `IdentityDocumentKind`.
     * @property documentNumber The document number.
     * @property supportNumber The support / serial number (ES "número de soporte", DE CAN).
     * @property issuingCountry The issuing country.
     * @property validUntil The expiry date, as the caller wrote it.
     * @property issueDate The issue date, as the caller wrote it.
     */
    @Parcelize
    data class Identity(
        val documentType: String? = null,
        val documentNumber: String? = null,
        val supportNumber: String? = null,
        val issuingCountry: String? = null,
        val validUntil: String? = null,
        val issueDate: String? = null,
        val firstName: String? = null,
        val middleName: String? = null,
        val lastName: String? = null,
    ) : AutofillSaveItem() {
        // The values are secrets: a log line never carries them.
        override fun toString(): String = "AutofillSaveItem.Identity(documentType=$documentType)"
    }
}
