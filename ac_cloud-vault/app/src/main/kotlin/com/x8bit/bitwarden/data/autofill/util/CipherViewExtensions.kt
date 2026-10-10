package com.x8bit.bitwarden.data.autofill.util

import com.bitwarden.vault.CipherView
import com.bitwarden.vault.FieldType
import com.x8bit.bitwarden.data.autofill.cloud.IdentityValues
import com.x8bit.bitwarden.data.autofill.model.AutofillCipher
import com.x8bit.bitwarden.data.autofill.provider.AutofillCipherProvider
import com.x8bit.bitwarden.data.platform.util.isActive
import com.x8bit.bitwarden.data.platform.util.subtitle

/**
 * Creates a single-item [AutofillCipherProvider] based on the given [CipherView].
 */
fun CipherView.toAutofillCipherProvider(): AutofillCipherProvider =
    object : AutofillCipherProvider {
        override suspend fun isVaultLocked(): Boolean = false

        override suspend fun getCardAutofillCiphers(): List<AutofillCipher.Card> {
            val card = this@toAutofillCipherProvider.card ?: return emptyList()
            return listOf(
                AutofillCipher.Card(
                    cipherId = id,
                    name = name,
                    subtitle = subtitle.orEmpty(),
                    cardholderName = card.cardholderName.orEmpty(),
                    code = card.code.orEmpty(),
                    expirationMonth = card.expMonth.orEmpty(),
                    expirationYear = card.expYear.orEmpty(),
                    number = card.number.orEmpty(),
                    brand = card.brand.orEmpty(),
                ),
            )
        }

        override suspend fun getLoginAutofillCiphers(
            uri: String,
            includeTotpCode: Boolean,
        ): List<AutofillCipher.Login> {
            val login = this@toAutofillCipherProvider.login ?: return emptyList()
            return listOf(
                AutofillCipher.Login(
                    cipherId = id,
                    isTotpEnabled = login.totp != null,
                    name = name,
                    password = login.password.orEmpty(),
                    subtitle = subtitle.orEmpty(),
                    username = login.username.orEmpty(),
                    website = uri,
                ),
            )
        }

        override suspend fun getIdentityAutofillCiphers(): List<AutofillCipher.Identity> =
            listOfNotNull(this@toAutofillCipherProvider.toAutofillIdentityOrNull())
    }

/**
 * Cloud Vault: this Identity item as an [AutofillCipher.Identity], or null when it is no Identity
 * item. Only text and hidden custom fields count (a boolean or linked field holds no value to
 * type into a form).
 */
fun CipherView.toAutofillIdentityOrNull(): AutofillCipher.Identity? {
    val identity = this.identity ?: return null
    return AutofillCipher.Identity(
        cipherId = id,
        name = name,
        subtitle = subtitle.orEmpty(),
        values = IdentityValues(
            firstName = identity.firstName.orEmpty(),
            middleName = identity.middleName.orEmpty(),
            lastName = identity.lastName.orEmpty(),
            address1 = identity.address1.orEmpty(),
            address2 = identity.address2.orEmpty(),
            address3 = identity.address3.orEmpty(),
            city = identity.city.orEmpty(),
            state = identity.state.orEmpty(),
            postalCode = identity.postalCode.orEmpty(),
            country = identity.country.orEmpty(),
            ssn = identity.ssn.orEmpty(),
            passportNumber = identity.passportNumber.orEmpty(),
            licenseNumber = identity.licenseNumber.orEmpty(),
            customFields = fields
                .orEmpty()
                .filter { it.type == FieldType.TEXT || it.type == FieldType.HIDDEN }
                .mapNotNull { field ->
                    val fieldName = field.name ?: return@mapNotNull null
                    val fieldValue = field.value ?: return@mapNotNull null
                    fieldName to fieldValue
                },
        ),
    )
}

/**
 * Returns true when the cipher is not archived, not deleted and contains at least one FIDO 2
 * credential.
 */
val CipherView.isActiveWithFido2Credentials: Boolean
    get() = isActive && !(login?.fido2Credentials.isNullOrEmpty())

/**
 * Returns true when the cipher is not archived, not deleted and contains at least one Password
 * credential.
 */
val CipherView.isActiveWithPasswordCredentials: Boolean
    get() = isActive && !(login?.password.isNullOrEmpty())
