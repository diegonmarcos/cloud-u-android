package com.x8bit.bitwarden.ui.vault.feature.addedit.util

import com.bitwarden.ui.platform.base.util.toHostOrPathOrNull
import com.x8bit.bitwarden.data.autofill.cloud.IdentityDocumentKind
import com.x8bit.bitwarden.data.autofill.cloud.IdentityFieldMapping
import com.x8bit.bitwarden.data.autofill.model.AutofillSaveItem
import com.x8bit.bitwarden.ui.vault.feature.addedit.VaultAddEditState
import com.x8bit.bitwarden.ui.vault.feature.addedit.model.UriItem
import com.x8bit.bitwarden.ui.vault.model.VaultCardBrand
import com.x8bit.bitwarden.ui.vault.model.VaultCardExpirationMonth
import com.x8bit.bitwarden.ui.vault.model.VaultItemCipherType
import com.x8bit.bitwarden.ui.vault.model.findVaultCardBrandWithNameOrNull
import java.util.UUID

/**
 * Returns pre-filled content that may be used for an "add" type
 * [VaultAddEditState.ViewState.Content].
 */
fun AutofillSaveItem.toDefaultAddTypeContent(
    isIndividualVaultDisabled: Boolean,
): VaultAddEditState.ViewState.Content =
    when (this) {
        is AutofillSaveItem.Card -> {
            VaultAddEditState.ViewState.Content(
                common = VaultAddEditState.ViewState.Content.Common(),
                isIndividualVaultDisabled = isIndividualVaultDisabled,
                type = VaultAddEditState.ViewState.Content.ItemType.Card(
                    cardHolderName = this.cardholderName.orEmpty(),
                    number = this.number.orEmpty(),
                    expirationMonth = VaultCardExpirationMonth
                        .entries
                        .find { it.number == this.expirationMonth }
                        ?: VaultCardExpirationMonth.SELECT,
                    expirationYear = this.expirationYear.orEmpty(),
                    securityCode = this.securityCode.orEmpty(),
                    brand = this.brand
                        ?.findVaultCardBrandWithNameOrNull()
                        ?: VaultCardBrand.SELECT,
                ),
            )
        }

        is AutofillSaveItem.Login -> {
            val uri = this.uri
            val simpleUri = uri?.toHostOrPathOrNull()
            VaultAddEditState.ViewState.Content(
                common = VaultAddEditState.ViewState.Content.Common(
                    name = simpleUri.orEmpty(),
                ),
                isIndividualVaultDisabled = isIndividualVaultDisabled,
                type = VaultAddEditState.ViewState.Content.ItemType.Login(
                    username = this.username.orEmpty(),
                    password = this.password.orEmpty(),
                    uriList = listOf(
                        UriItem(
                            id = UUID.randomUUID().toString(),
                            uri = uri,
                            match = null,
                            checksum = null,
                        ),
                    ),
                ),
            )
        }

        is AutofillSaveItem.Identity -> toIdentityAddTypeContent(isIndividualVaultDisabled)
    }

/**
 * Cloud Vault: the new-Identity screen prefilled from an add-identity request. The number goes
 * into the Identity type's own field when it has one (passport, driving licence, SSN); every
 * other detail goes into a custom field named as the fill reads it back (IdentityFieldMapping):
 * the number under the document's own name ("DNI", "NIE", "Personalausweis", ...), "Support
 * number" and the number hidden, "Document type", "Issuing country", "Valid until" and "Issued"
 * as text.
 */
private fun AutofillSaveItem.Identity.toIdentityAddTypeContent(
    isIndividualVaultDisabled: Boolean,
): VaultAddEditState.ViewState.Content {
    val kind = IdentityDocumentKind.fromLabel(documentType)
    val number = documentNumber?.trim().orEmpty()
    fun text(name: String, value: String?): VaultAddEditState.Custom? =
        value?.trim()?.takeIf { it.isNotEmpty() }?.let {
            VaultAddEditState.Custom.TextField(
                itemId = UUID.randomUUID().toString(),
                name = name,
                value = it,
            )
        }

    fun hidden(name: String, value: String?): VaultAddEditState.Custom? =
        value?.trim()?.takeIf { it.isNotEmpty() }?.let {
            VaultAddEditState.Custom.HiddenField(
                itemId = UUID.randomUUID().toString(),
                name = name,
                value = it,
            )
        }

    val customFields = listOfNotNull(
        text(IdentityFieldMapping.CUSTOM_DOCUMENT_TYPE, documentType),
        kind.customFieldName?.let { hidden(it, number) },
        hidden(IdentityFieldMapping.CUSTOM_SUPPORT_NUMBER, supportNumber),
        text(IdentityFieldMapping.CUSTOM_ISSUING_COUNTRY, issuingCountry?.uppercase()),
        text(IdentityFieldMapping.CUSTOM_VALID_UNTIL, validUntil),
        text(IdentityFieldMapping.CUSTOM_ISSUED, issueDate),
    )
    val label = documentType?.trim()?.takeIf { it.isNotEmpty() } ?: "ID document"
    val country = issuingCountry?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
    return VaultAddEditState.ViewState.Content(
        common = VaultAddEditState.ViewState.Content.Common(
            name = if (country != null) "$label ($country)" else label,
            customFieldData = customFields,
        ),
        isIndividualVaultDisabled = isIndividualVaultDisabled,
        type = VaultAddEditState.ViewState.Content.ItemType.Identity(
            firstName = firstName?.trim().orEmpty(),
            middleName = middleName?.trim().orEmpty(),
            lastName = lastName?.trim().orEmpty(),
            passportNumber = number.takeIf { kind == IdentityDocumentKind.PASSPORT }.orEmpty(),
            licenseNumber = number
                .takeIf { kind == IdentityDocumentKind.DRIVING_LICENCE }
                .orEmpty(),
            ssn = number.takeIf { kind == IdentityDocumentKind.SSN }.orEmpty(),
        ),
    )
}

/**
 * Converts an [AutofillSaveItem] to a [VaultItemCipherType].
 */
fun AutofillSaveItem.toVaultItemCipherType(): VaultItemCipherType = when (this) {
    is AutofillSaveItem.Card -> VaultItemCipherType.CARD
    is AutofillSaveItem.Login -> VaultItemCipherType.LOGIN
    is AutofillSaveItem.Identity -> VaultItemCipherType.IDENTITY
}
