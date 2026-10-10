package com.x8bit.bitwarden.data.autofill.util

import com.x8bit.bitwarden.data.autofill.model.AutofillPartition
import com.x8bit.bitwarden.data.autofill.model.AutofillRequest
import com.x8bit.bitwarden.data.autofill.model.AutofillSaveItem

/**
 * Convert the [AutofillRequest.Fillable] to an [AutofillSaveItem].
 */
fun AutofillRequest.Fillable.toAutofillSaveItem(): AutofillSaveItem =
    when (this.partition) {
        is AutofillPartition.Card -> {
            AutofillSaveItem.Card(
                cardholderName = partition.cardholderNameSaveValue,
                number = partition.numberSaveValue,
                expirationMonth = partition.expirationMonthSaveValue,
                expirationYear = partition.expirationYearSaveValue,
                securityCode = partition.securityCodeSaveValue,
                brand = partition.brandSaveValue,
            )
        }

        is AutofillPartition.Login -> {
            // Skip the scheme for the save value.
            val uri = this
                .uri
                ?.replace("https://", "")
                ?.replace("http://", "")

            AutofillSaveItem.Login(
                username = partition.usernameSaveValue,
                password = partition.passwordSaveValue,
                uri = uri,
            )
        }

        // Cloud Vault: an Identity partition carries no SaveInfo, so no save request reaches
        // here for one; the processor drops it anyway. Never prefilled from a page.
        is AutofillPartition.Identity -> AutofillSaveItem.Identity()
    }
