package com.x8bit.bitwarden.data.autofill.model

import androidx.annotation.DrawableRes
import com.bitwarden.core.Uuid
import com.bitwarden.ui.platform.resource.BitwardenDrawable
import com.x8bit.bitwarden.data.autofill.cloud.IdentityValues

/**
 * A paired down model of the CipherView for use within the autofill feature.
 */
sealed class AutofillCipher {
    /**
     * The icon res to represent this [AutofillCipher].
     */
    abstract val iconRes: Int

    /**
     * Whether TOTP is enabled for this cipher.
     */
    abstract val isTotpEnabled: Boolean

    /**
     * The name of the cipher.
     */
    abstract val name: String

    /**
     * The subtitle for giving additional context to the cipher.
     */
    abstract val subtitle: String

    /**
     * The ID that corresponds to the CipherView used to create this [AutofillCipher].
     */
    abstract val cipherId: String?

    /**
     * The card [AutofillCipher] model. This contains all of the data for building fulfilling a card
     * partition.
     */
    data class Card(
        override val cipherId: String?,
        override val name: String,
        override val subtitle: String,
        val cardholderName: String,
        val code: String,
        val expirationMonth: String,
        val expirationYear: String,
        val number: String,
        val brand: String,
    ) : AutofillCipher() {
        override val iconRes: Int
            @DrawableRes get() = BitwardenDrawable.ic_payment_card

        override val isTotpEnabled: Boolean
            get() = false
    }

    /**
     * Cloud Vault: an Identity item, for ID-document fields. [values] are secrets: this class
     * never prints them.
     */
    data class Identity(
        override val cipherId: String?,
        override val name: String,
        override val subtitle: String,
        val values: IdentityValues,
    ) : AutofillCipher() {
        override val iconRes: Int
            @DrawableRes get() = BitwardenDrawable.ic_id_card

        override val isTotpEnabled: Boolean
            get() = false

        override fun toString(): String = "AutofillCipher.Identity(cipherId=$cipherId)"
    }

    /**
     * The card [AutofillCipher] model. This contains all of the data for building fulfilling a
     * login partition.
     */
    data class Login(
        override val cipherId: Uuid?,
        override val isTotpEnabled: Boolean,
        override val name: String,
        override val subtitle: String,
        val password: String,
        val username: String,
        val website: String,
        /**
         * The login's current TOTP code, computed only when the page has a one-time-code field
         * (null otherwise, or when the login has no TOTP seed). A secret: never logged.
         */
        val totpCode: String? = null,
    ) : AutofillCipher() {
        override val iconRes: Int
            @DrawableRes get() = BitwardenDrawable.ic_globe
    }
}
