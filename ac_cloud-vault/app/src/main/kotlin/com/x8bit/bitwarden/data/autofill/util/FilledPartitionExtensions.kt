package com.x8bit.bitwarden.data.autofill.util

import android.content.IntentSender
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.Presentations
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.x8bit.bitwarden.data.autofill.model.AutofillAppInfo
import com.x8bit.bitwarden.data.autofill.model.FilledPartition
import com.x8bit.bitwarden.ui.autofill.buildAutofillRemoteViews
import com.x8bit.bitwarden.ui.autofill.util.createCipherInlinePresentationOrNull

/**
 * Build a [Dataset] to represent the [FilledPartition]. This dataset includes an overlay UI
 * presentation for each filled item. If an [authIntentSender] is present, add it to the dataset.
 */
fun FilledPartition.buildDataset(
    authIntentSender: IntentSender?,
    autofillAppInfo: AutofillAppInfo,
): Dataset {
    val remoteViewsPlaceholder = buildAutofillRemoteViews(
        autofillAppInfo = autofillAppInfo,
        autofillCipher = autofillCipher,
    )
    val datasetBuilder = Dataset.Builder()
    authIntentSender?.let { intentSender -> datasetBuilder.setAuthentication(intentSender) }

    if (autofillAppInfo.isVersionAtLeast(version = Build.VERSION_CODES.TIRAMISU)) {
        applyToDatasetPostTiramisu(
            autofillAppInfo = autofillAppInfo,
            datasetBuilder = datasetBuilder,
            remoteViews = remoteViewsPlaceholder,
        )
    } else {
        buildDatasetPreTiramisu(
            autofillAppInfo = autofillAppInfo,
            datasetBuilder = datasetBuilder,
            remoteViews = remoteViewsPlaceholder,
        )
    }

    return datasetBuilder.build()
}

/**
 * Apply this [FilledPartition] to the [datasetBuilder] on devices running OS version Tiramisu or
 * greater.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun FilledPartition.applyToDatasetPostTiramisu(
    autofillAppInfo: AutofillAppInfo,
    datasetBuilder: Dataset.Builder,
    remoteViews: RemoteViews,
) {
    val presentationBuilder = Presentations.Builder()
    inlinePresentationSpec
        ?.createCipherInlinePresentationOrNull(
            autofillAppInfo = autofillAppInfo,
            autofillCipher = autofillCipher,
        )
        ?.let { inlinePresentation ->
            presentationBuilder.setInlinePresentation(inlinePresentation)
        }

    val presentation = presentationBuilder
        .setMenuPresentation(remoteViews)
        .build()

    filledItems.forEach { filledItem ->
        if (presentationIds == null || filledItem.autofillId in presentationIds) {
            filledItem.applyToDatasetPostTiramisu(
                datasetBuilder = datasetBuilder,
                presentations = presentation,
            )
        } else {
            // Cloud Vault: filled when the dataset is picked, never shows it.
            filledItem.applyFillOnlyToDatasetPostTiramisu(datasetBuilder = datasetBuilder)
        }
    }
}

/**
 * Apply this [FilledPartition] to the [datasetBuilder] on devices running OS versions that predate
 * Tiramisu.
 */
private fun FilledPartition.buildDatasetPreTiramisu(
    autofillAppInfo: AutofillAppInfo,
    datasetBuilder: Dataset.Builder,
    remoteViews: RemoteViews,
) {
    presentationIds?.let { ids ->
        buildFieldPresentedDatasetPreTiramisu(
            autofillAppInfo = autofillAppInfo,
            datasetBuilder = datasetBuilder,
            remoteViews = remoteViews,
            presentationIds = ids,
        )
        return
    }
    if (autofillAppInfo.isVersionAtLeast(version = Build.VERSION_CODES.R)) {
        inlinePresentationSpec
            ?.createCipherInlinePresentationOrNull(
                autofillAppInfo = autofillAppInfo,
                autofillCipher = autofillCipher,
            )
            ?.let { inlinePresentation ->
                @Suppress("DEPRECATION")
                datasetBuilder.setInlinePresentation(inlinePresentation)
            }
    }

    filledItems.forEach { filledItem ->
        filledItem.applyToDatasetPreTiramisu(
            datasetBuilder = datasetBuilder,
            remoteViews = remoteViews,
        )
    }
}

/**
 * Cloud Vault, before Tiramisu: a dataset whose suggestion shows only on [presentationIds]. The
 * inline presentation is set per field (API 30+), never on the whole dataset, so a fill-only
 * field neither shows the drop-down nor an inline chip.
 */
private fun FilledPartition.buildFieldPresentedDatasetPreTiramisu(
    autofillAppInfo: AutofillAppInfo,
    datasetBuilder: Dataset.Builder,
    remoteViews: RemoteViews,
    presentationIds: Set<AutofillId>,
) {
    val inlinePresentation =
        if (autofillAppInfo.isVersionAtLeast(version = Build.VERSION_CODES.R)) {
            inlinePresentationSpec?.createCipherInlinePresentationOrNull(
                autofillAppInfo = autofillAppInfo,
                autofillCipher = autofillCipher,
            )
        } else {
            null
        }
    filledItems.forEach { filledItem ->
        if (filledItem.autofillId in presentationIds) {
            filledItem.applyWithInlineToDatasetPreTiramisu(
                datasetBuilder = datasetBuilder,
                remoteViews = remoteViews,
                inlinePresentation = inlinePresentation,
            )
        } else {
            filledItem.applyFillOnlyToDatasetPreTiramisu(datasetBuilder = datasetBuilder)
        }
    }
}
