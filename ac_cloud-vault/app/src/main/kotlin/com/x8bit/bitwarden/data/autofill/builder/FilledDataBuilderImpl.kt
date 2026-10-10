package com.x8bit.bitwarden.data.autofill.builder

import android.widget.inline.InlinePresentationSpec
import com.bitwarden.ui.platform.base.util.isValidEmail
import com.x8bit.bitwarden.data.autofill.cloud.IdentityFieldMapping
import com.x8bit.bitwarden.data.autofill.model.AutofillCipher
import com.x8bit.bitwarden.data.autofill.model.AutofillPartition
import com.x8bit.bitwarden.data.autofill.model.AutofillRequest
import com.x8bit.bitwarden.data.autofill.model.AutofillView
import com.x8bit.bitwarden.data.autofill.model.FilledData
import com.x8bit.bitwarden.data.autofill.model.FilledPartition
import com.x8bit.bitwarden.data.autofill.provider.AutofillCipherProvider
import com.x8bit.bitwarden.data.autofill.util.buildFilledItemOrNull
import com.x8bit.bitwarden.data.autofill.util.buildUri
import timber.log.Timber

/**
 * The maximum amount of filled partitions the user will see. Viewing the rest will require opening
 * the vault.
 *
 * Note: The vault item is not included in this count.
 */
private const val MAX_FILLED_PARTITIONS_COUNT: Int = 20

/**
 * The maximum amount of inline suggestions the user will see. Viewing the rest will require
 * opening the vault.
 *
 * Note: The vault item is not included in this count.
 */
private const val MAX_INLINE_SUGGESTION_COUNT: Int = 5

/**
 * The default [FilledDataBuilder]. This converts parsed autofill data into filled data that is
 * ready to be loaded into an autofill response.
 */
class FilledDataBuilderImpl(
    private val autofillCipherProvider: AutofillCipherProvider,
) : FilledDataBuilder {
    override suspend fun build(autofillRequest: AutofillRequest.Fillable): FilledData {
        Timber.d("Autofill request constructing FilledData")
        val isVaultLocked = autofillCipherProvider.isVaultLocked()

        // Subtract one to make sure there is space for the vault item.
        val maxCipherInlineSuggestionsCount = (autofillRequest.maxInlineSuggestionsCount - 1)
            .coerceAtMost(maximumValue = MAX_INLINE_SUGGESTION_COUNT)

        // Track the number of inline suggestions that have been added.
        var inlineSuggestionsAdded = 0

        // A function for managing the cipher InlinePresentationSpecs.
        fun getCipherInlinePresentationOrNull(): InlinePresentationSpec? =
            if (inlineSuggestionsAdded < maxCipherInlineSuggestionsCount) {
                // Use getOrLastOrNull so if the list has run dry take the last spec.
                autofillRequest
                    .inlinePresentationSpecs
                    ?.getOrLastOrNull(inlineSuggestionsAdded)
            } else {
                null
            }
                ?.also { inlineSuggestionsAdded += 1 }

        val filledPartitions = when (autofillRequest.partition) {
            is AutofillPartition.Card -> {
                autofillCipherProvider
                    .getCardAutofillCiphers()
                    .map { autofillCipher ->
                        fillCardPartition(
                            autofillCipher = autofillCipher,
                            autofillViews = autofillRequest.partition.views,
                            inlinePresentationSpec = getCipherInlinePresentationOrNull(),
                        )
                    }
            }

            is AutofillPartition.Login -> {
                autofillRequest
                    .uri
                    ?.let { nonNullUri ->
                        autofillCipherProvider
                            .getLoginAutofillCiphers(
                                uri = nonNullUri,
                                includeTotpCode = autofillRequest.partition.views.any {
                                    it is AutofillView.Login.Totp
                                },
                            )
                            .map { autofillCipher ->
                                fillLoginPartition(
                                    autofillCipher = autofillCipher,
                                    autofillViews = autofillRequest.partition.views,
                                    inlinePresentationSpec = getCipherInlinePresentationOrNull(),
                                    packageName = autofillRequest.packageName,
                                )
                            }
                    }
                    .orEmpty()
            }

            is AutofillPartition.Identity -> {
                // Cloud Vault: every Identity item that has a value for one of the page's
                // document fields; no domain match (the user picks one), nothing while locked.
                autofillCipherProvider
                    .getIdentityAutofillCiphers()
                    .mapNotNull { autofillCipher ->
                        fillIdentityPartitionOrNull(
                            autofillCipher = autofillCipher,
                            partition = autofillRequest.partition,
                            inlinePresentationSpec = { getCipherInlinePresentationOrNull() },
                        )
                    }
            }
        }

        // Use getOrLastOrNull so if the list has run dry take the last spec.
        val vaultItemInlinePresentationSpec = autofillRequest
            .inlinePresentationSpecs
            ?.getOrLastOrNull(inlineSuggestionsAdded)

        return FilledData(
            filledPartitions = filledPartitions
                .filter { it.filledItems.isNotEmpty() }
                .take(n = MAX_FILLED_PARTITIONS_COUNT),
            ignoreAutofillIds = autofillRequest.ignoreAutofillIds,
            originalPartition = autofillRequest.partition,
            uri = autofillRequest.uri,
            vaultItemInlinePresentationSpec = vaultItemInlinePresentationSpec,
            isVaultLocked = isVaultLocked,
        )
    }

    /**
     * Construct a [FilledPartition] by fulfilling the card [autofillViews] with data from the
     * card [autofillCipher].
     */
    private fun fillCardPartition(
        autofillCipher: AutofillCipher.Card,
        autofillViews: List<AutofillView.Card>,
        inlinePresentationSpec: InlinePresentationSpec?,
    ): FilledPartition {
        val filledItems = autofillViews
            .mapNotNull { autofillView ->
                autofillCipher
                    .getAutofillValueOrNull(autofillView)
                    ?.let { value ->
                        autofillView.buildFilledItemOrNull(
                            value = value,
                        )
                    }
            }

        return FilledPartition(
            autofillCipher = autofillCipher,
            filledItems = filledItems,
            inlinePresentationSpec = inlinePresentationSpec,
        )
    }

    /**
     * Construct a [FilledPartition] by fulfilling the login [autofillViews] with data from the
     * login [autofillCipher].
     */
    private fun fillLoginPartition(
        autofillCipher: AutofillCipher.Login,
        autofillViews: List<AutofillView.Login>,
        inlinePresentationSpec: InlinePresentationSpec?,
        packageName: String?,
    ): FilledPartition {
        val filledItems = autofillViews
            .mapNotNull { autofillView ->
                if (autofillView.data.website == autofillCipher.website ||
                    buildUri(packageName.orEmpty(), "androidapp") == autofillCipher.website
                ) {
                    val value = when (autofillView) {
                        is AutofillView.Login.Email -> {
                            if (!autofillCipher.username.isValidEmail()) {
                                return@mapNotNull null
                            }
                            autofillCipher.username
                        }

                        is AutofillView.Login.Username -> autofillCipher.username
                        is AutofillView.Login.Password -> autofillCipher.password
                        is AutofillView.Login.Totp -> autofillCipher.totpCode
                            ?: return@mapNotNull null
                    }
                    autofillView.buildFilledItemOrNull(value = value)
                } else {
                    null
                }
            }

        return FilledPartition(
            autofillCipher = autofillCipher,
            filledItems = filledItems,
            inlinePresentationSpec = inlinePresentationSpec,
        )
    }
}

/**
 * Cloud Vault: fill the Identity [partition] from [autofillCipher], or null when the item has no
 * value for any of the page's ID-document fields (it is then not offered at all). Only the
 * document fields carry the suggestion ([FilledPartition.presentationIds]); the name, address
 * and undocumented-expiry fields are filled with it but never show it, so they stay the browser's
 * and keyboard's. [inlinePresentationSpec] is asked only for an item that is offered.
 */
private fun fillIdentityPartitionOrNull(
    autofillCipher: AutofillCipher.Identity,
    partition: AutofillPartition.Identity,
    inlinePresentationSpec: () -> InlinePresentationSpec?,
): FilledPartition? {
    val filled = partition
        .views
        .mapNotNull { autofillView ->
            IdentityFieldMapping
                .valueFor(field = autofillView.field, values = autofillCipher.values)
                ?.let { value -> autofillView.buildFilledItemOrNull(value = value) }
                ?.let { filledItem -> autofillView to filledItem }
        }
    val presentationIds = filled
        .filter { (autofillView, _) -> autofillView.field.isDocument }
        .map { (_, filledItem) -> filledItem.autofillId }
        .toSet()
    if (presentationIds.isEmpty()) return null
    return FilledPartition(
        autofillCipher = autofillCipher,
        filledItems = filled.map { (_, filledItem) -> filledItem },
        inlinePresentationSpec = inlinePresentationSpec(),
        presentationIds = presentationIds,
    )
}

/**
 * Get the autofill value for the given [autofillView], or null if no value is available.
 */
private fun AutofillCipher.Card.getAutofillValueOrNull(autofillView: AutofillView.Card): String? =
    when (autofillView) {
        is AutofillView.Card.CardholderName -> {
            cardholderName.takeIf { it.isNotEmpty() }
        }

        is AutofillView.Card.ExpirationMonth -> {
            expirationMonth.takeIf { it.isNotEmpty() }
        }

        is AutofillView.Card.ExpirationYear -> {
            expirationYear.takeIf { it.isNotEmpty() }
        }

        is AutofillView.Card.Number -> {
            number
                .filter { it.isDigit() }
                .takeIf { it.isNotEmpty() }
        }

        is AutofillView.Card.SecurityCode -> {
            code
                .filter { it.isDigit() }
                .takeIf { it.isNotEmpty() }
        }

        is AutofillView.Card.ExpirationDate -> {
            if (expirationMonth.isNotBlank() && expirationYear.isNotBlank()) {
                expirationMonth.padStart(2, '0') + expirationYear.takeLast(2)
            } else {
                null
            }
        }

        is AutofillView.Card.Brand -> {
            brand.takeIf { it.isNotEmpty() }
        }
    }

/**
 * Get the item at the [index]. If that fails, return the last item in the list. If that also fails,
 * return null.
 */
private fun <T> List<T>.getOrLastOrNull(index: Int): T? =
    getOrNull(index)
        ?: lastOrNull()
