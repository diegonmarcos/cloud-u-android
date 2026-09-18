/*
 * Task #460 — image content reading sheet. Renders ONE scan outcome with a
 * specific message for every way a scan can come back empty (no barcode, no
 * text, unsupported format, permission refused, model not downloaded), a
 * typed action for each barcode payload kind, and selectable/copyable OCR
 * text with the recognised language stated. An empty box is never shown.
 */

package com.diegonmarcos.mediacenter.feature_node.presentation.scan

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diegonmarcos.mediacenter.R
import com.diegonmarcos.mediacenter.core.presentation.components.ModalSheet
import com.diegonmarcos.mediacenter.feature_node.domain.model.Media
import com.diegonmarcos.mediacenter.feature_node.domain.util.getUri
import com.diegonmarcos.mediacenter.feature_node.presentation.util.AppBottomSheetState
import com.diegonmarcos.superapp.image.mlkit.BarcodePayload
import com.diegonmarcos.superapp.image.mlkit.BarcodeScan
import com.diegonmarcos.superapp.image.mlkit.OcrResult

/**
 * Modal sheet showing the typed content of [media]. The scan starts as soon
 * as the sheet is visible and runs off the main thread (ImageScanViewModel).
 */
@Composable
fun ImageScanSheet(
    state: AppBottomSheetState,
    media: Media,
    onDismiss: () -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: ImageScanViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uri = media.getUri()

    LaunchedEffect(state.isVisible, media) {
        if (state.isVisible) {
            if (uiState is ScanUiState.Idle) viewModel.scan(uri)
        } else {
            viewModel.reset()
        }
    }

    ModalSheet(
        sheetState = state,
        content = {
            when (val current = uiState) {
                is ScanUiState.Idle -> Unit
                is ScanUiState.Scanning -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text(
                            text = stringResource(R.string.scan_reading),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                is ScanUiState.Done -> ScanOutcomeContent(current.outcome, onCopy = {
                    context.copyWithToast(it)
                })
            }
        },
        onDismissRequest = { onDismiss() },
        title = stringResource(R.string.scan_image_content_title),
        subtitle = media.label,
    )
}

@Composable
private fun ColumnScope.ScanOutcomeContent(
    outcome: ImageScanOutcome,
    onCopy: (String) -> Unit,
) {
    // The named reasons come first so a scan that could not run says so, even
    // when half of it (the barcode) succeeded.
    outcome.failure?.let { failure ->
        val stringRes = when (failure) {
            ScanFailure.PermissionRefused -> R.string.scan_permission_refused
            ScanFailure.UnsupportedFormat -> R.string.scan_unsupported_format
            ScanFailure.ModelNotDownloaded -> R.string.scan_model_not_downloaded
            is ScanFailure.Other -> null
        }
        if (stringRes != null) SectionLabel(stringResource(stringRes))
    }

    outcome.barcode?.let { barcode ->
        BarcodeSection(barcode, onCopy)
    } ?: run {
        if (outcome.barcodeAbsent && outcome.failure == null) {
            SectionLabel(stringResource(R.string.scan_no_barcode))
        }
    }

    outcome.ocr?.let { ocr ->
        OcrSection(ocr, onCopy)
    } ?: run {
        if (outcome.ocrAbsent && outcome.failure == null) {
            SectionLabel(stringResource(R.string.scan_no_text))
        }
    }

    // OCR failed for an unknown reason: give the engine's own message its own
    // line so the empty half is not silent (the barcode half is already shown).
    if (outcome.ocr == null && outcome.failure is ScanFailure.Other && outcome.barcode != null) {
        SectionLabel(stringResource(R.string.scan_read_failed, outcome.failure.reason))
    }

    // Nothing at all came back and the failure is not one of the fixed three
    // already rendered above: print the engine's reason verbatim.
    if (!outcome.hasAnything && outcome.failure is ScanFailure.Other) {
        SectionLabel(stringResource(R.string.scan_read_failed, outcome.failure.reason))
    }
}

@Composable
private fun ColumnScope.BarcodeSection(
    barcode: BarcodeScan,
    onCopy: (String) -> Unit,
) {
    // Captured here (composable scope) so the non-composable onClick lambdas
    // below can run the typed actions without reading LocalContext themselves.
    val context = LocalContext.current
    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
        Text(
            text = stringResource(R.string.scan_barcode_found),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(R.string.scan_barcode_format, barcodeFormatLabel(barcode.format)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }

    val payload = barcode.payload
    when (payload) {
        is BarcodePayload.Url -> ActionRow(Icons.Outlined.Link, stringResource(R.string.scan_open_link)) {
            contextAction(context) { ScanActions.openUrl(it, payload.url) }
        }
        is BarcodePayload.Wifi -> {
            Text(
                text = stringResource(R.string.scan_wifi_network, payload.ssid),
                style = MaterialTheme.typography.bodyLarge
            )
            ActionRow(Icons.Outlined.Wifi, stringResource(R.string.scan_join_wifi)) {
                contextAction(context) { ScanActions.joinWifi(it, payload.ssid, payload.password) }
            }
        }
        is BarcodePayload.Contact -> {
            payload.name?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.scan_contact_name, it),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            ActionRow(Icons.Outlined.PersonAddAlt, stringResource(R.string.scan_add_contact)) {
                contextAction(context) { ScanActions.insertContact(it, payload.vcard, payload.name) }
            }
        }
        is BarcodePayload.Calendar -> {
            payload.summary?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.scan_calendar_summary, it),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            ActionRow(Icons.Outlined.Event, stringResource(R.string.scan_add_calendar_event)) {
                contextAction(context) {
                    ScanActions.insertCalendarEvent(
                        it,
                        payload.summary.orEmpty(),
                        payload.location.orEmpty(),
                        payload.startTimeEpochMillis,
                        payload.endTimeEpochMillis
                    )
                }
            }
        }
        is BarcodePayload.Phone -> {
            Text(
                text = stringResource(R.string.scan_phone_number, payload.number),
                style = MaterialTheme.typography.bodyLarge
            )
            ActionRow(Icons.Outlined.Phone, stringResource(R.string.scan_call_number)) {
                contextAction(context) { ScanActions.dialNumber(it, payload.number) }
            }
        }
        is BarcodePayload.Email -> ActionRow(Icons.Outlined.MailOutline, stringResource(R.string.scan_compose_email)) {
            contextAction(context) { ScanActions.composeEmail(it, payload.address, payload.subject, payload.body) }
        }
        is BarcodePayload.Geo -> ActionRow(Icons.Outlined.Map, stringResource(R.string.scan_show_on_map)) {
            contextAction(context) { ScanActions.openGeo(it, payload.latitude, payload.longitude) }
        }
        is BarcodePayload.Plain -> {
            // The required fallback: the text, selectable, with a copy action.
            SectionLabel(stringResource(R.string.scan_not_recognised))
            CopyableText(payload.text, onCopy)
        }
    }

    // Every other payload stays copyable: an action that cannot run (fleet
    // browser missing, no calendar app) is never the end of the payload.
    if (payload !is BarcodePayload.Plain) {
        ActionRow(Icons.Outlined.ContentCopy, stringResource(R.string.scan_copy)) {
            onCopy(barcode.rawValue)
        }
    }
}

@Composable
private fun ColumnScope.OcrSection(
    ocr: OcrResult,
    onCopy: (String) -> Unit,
) {
    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Outlined.TextSnippet, contentDescription = null)
        Text(
            text = stringResource(R.string.scan_recognised_text),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
    }
    // Bind to a local: ocr.language is a public-API property from another module
    // (libs:ml-l-image-mlkit), so Kotlin refuses to smart-cast it to non-null.
    val language = ocr.language
    Text(
        text = if (language.isNullOrBlank()) {
            stringResource(R.string.scan_language_unknown)
        } else {
            stringResource(R.string.scan_language, languageLabel(language))
        },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
    CopyableText(ocr.text, onCopy)
}

@Composable
private fun ColumnScope.ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
        Text(label)
    }
}

/** Runs an action handler with the sheet's context and toasts its result message. Context is passed in, not read via LocalContext: these actions run inside non-composable onClick lambdas, where a @Composable function cannot be called. */
private fun contextAction(context: Context, handler: (Context) -> String?) {
    val error = handler(context)
    if (!error.isNullOrBlank()) {
        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun ColumnScope.SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}

@Composable
private fun ColumnScope.CopyableText(text: String, onCopy: (String) -> Unit) {
    SelectionContainer(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Normal
        )
    }
    FilledTonalButton(
        onClick = { onCopy(text) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
        Text(stringResource(R.string.scan_copy_text))
    }
}

/** ZXing's format constants, humanised. Unmapped formats stay verbatim. */
private fun barcodeFormatLabel(format: String): String = when (format) {
    "QR_CODE" -> "QR code"
    "DATA_MATRIX" -> "Data Matrix"
    "AZTEC" -> "Aztec"
    "PDF_417" -> "PDF417"
    "CODE_128" -> "Code 128"
    "CODE_39" -> "Code 39"
    "CODE_93" -> "Code 93"
    "CODABAR" -> "Codabar"
    "EAN_13" -> "EAN-13"
    "EAN_8" -> "EAN-8"
    "UPC_A" -> "UPC-A"
    "UPC_E" -> "UPC-E"
    "RSS_14" -> "RSS-14"
    "RSS_EXPANDED" -> "RSS Expanded"
    else -> format
}

/** BCP-47 tag → English name. Unknown tags stay verbatim. */
private fun languageLabel(tag: String): String = when (tag) {
    "en" -> "English"
    "es" -> "Spanish"
    "pt" -> "Portuguese"
    "de" -> "German"
    "fr" -> "French"
    "it" -> "Italian"
    "nl" -> "Dutch"
    "pl" -> "Polish"
    "tr" -> "Turkish"
    "cs" -> "Czech"
    else -> tag
}

private fun Context.copyWithToast(text: String) {
    val copied = ScanActions.copyText(this, text)
    Toast.makeText(
        this,
        if (copied) getString(R.string.scan_copied) else getString(R.string.scan_copy_failed),
        Toast.LENGTH_SHORT
    ).show()
}