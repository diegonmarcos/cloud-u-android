/*
 * Task #460 — image content reading. Runs the shared scan engine
 * (libs:ml-l-image-mlkit: ImageScanEngine — ZXing decode + ML Kit text
 * recognition) off the main thread and reduces the raw results into the
 * sheet's contract. Permission refusal is detected BEFORE the engine so it
 * can name the real reason instead of being swallowed into "no barcode".
 */

package com.diegonmarcos.mediacenter.feature_node.presentation.scan

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diegonmarcos.superapp.image.mlkit.ImageScanEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data object Scanning : ScanUiState
    data class Done(val outcome: ImageScanOutcome) : ScanUiState
}

@HiltViewModel
class ImageScanViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private val engine = ImageScanEngine(appContext)

    fun scan(uri: Uri) {
        if (_uiState.value is ScanUiState.Scanning) return
        _uiState.value = ScanUiState.Scanning
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runScan(uri) }
            _uiState.value = ScanUiState.Done(outcome)
        }
    }

    fun reset() {
        _uiState.value = ScanUiState.Idle
    }

    private fun runScan(uri: Uri): ImageScanOutcome {
        if (permissionRefused(uri)) {
            return ImageScanOutcome(failure = ScanFailure.PermissionRefused)
        }
        // decodeBarcode returns null for "no barcode" AND for "could not load";
        // the OCR result's error string is the tiebreaker that tells them apart
        // (ScanOutcomeReducer), so both engines are always asked.
        return ScanOutcomeReducer.reduce(engine.decodeBarcode(uri), engine.recognizeText(uri))
    }

    private fun permissionRefused(uri: Uri): Boolean = try {
        appContext.contentResolver.openInputStream(uri)?.close() ?: false
        false
    } catch (error: SecurityException) {
        true
    } catch (error: Exception) {
        // Any other stream failure (gone file, bad URI) is not a permission
        // problem; let the engine report it as an unsupported image.
        false
    }
}