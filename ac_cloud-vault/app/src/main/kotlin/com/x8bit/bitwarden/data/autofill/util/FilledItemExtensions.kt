package com.x8bit.bitwarden.data.autofill.util

import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.x8bit.bitwarden.data.autofill.model.FilledItem

/**
 * Set up an overlay presentation for this [FilledItem] in the [datasetBuilder] for Android devices
 * running on API Tiramisu or greater.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun FilledItem.applyToDatasetPostTiramisu(
    datasetBuilder: Dataset.Builder,
    presentations: Presentations,
) {
    datasetBuilder.setField(
        autofillId,
        Field.Builder()
            .setValue(value)
            .setPresentations(presentations)
            .build(),
    )
}

/**
 * Set up an overlay presentation for this [FilledItem] in the [datasetBuilder] for Android devices
 * running on APIs that predate Tiramisu.
 */
fun FilledItem.applyToDatasetPreTiramisu(
    datasetBuilder: Dataset.Builder,
    remoteViews: RemoteViews,
) {
    @Suppress("DEPRECATION")
    datasetBuilder.setValue(
        autofillId,
        value,
        remoteViews,
    )
}

/**
 * Cloud Vault: set this [FilledItem]'s value without any presentation (Tiramisu+). The framework
 * fills it when the dataset is picked on another field, and shows nothing when it is focused.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun FilledItem.applyFillOnlyToDatasetPostTiramisu(
    datasetBuilder: Dataset.Builder,
) {
    datasetBuilder.setField(
        autofillId,
        Field.Builder()
            .setValue(value)
            .build(),
    )
}

/**
 * Cloud Vault: set this [FilledItem]'s value without any presentation (before Tiramisu).
 */
fun FilledItem.applyFillOnlyToDatasetPreTiramisu(
    datasetBuilder: Dataset.Builder,
) {
    @Suppress("DEPRECATION")
    datasetBuilder.setValue(autofillId, value)
}

/**
 * Cloud Vault: set this [FilledItem]'s value with its own drop-down and (API 30+) inline
 * presentation, before Tiramisu.
 */
fun FilledItem.applyWithInlineToDatasetPreTiramisu(
    datasetBuilder: Dataset.Builder,
    remoteViews: RemoteViews,
    inlinePresentation: InlinePresentation?,
) {
    if (inlinePresentation != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        @Suppress("DEPRECATION")
        datasetBuilder.setValue(autofillId, value, remoteViews, inlinePresentation)
    } else {
        applyToDatasetPreTiramisu(datasetBuilder = datasetBuilder, remoteViews = remoteViews)
    }
}
