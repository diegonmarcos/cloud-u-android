/*
 * Task #460 — folder content index runner. Owns the walk: the indexer runs
 * off the main thread (Dispatchers.IO inside the viewModelScope), reports
 * real per-image progress, honours Cancel via job cancellation, and persists
 * rows through the app's own Room database (no second store).
 */

package com.diegonmarcos.mediacenter.feature_node.presentation.contentindex

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diegonmarcos.mediacenter.feature_node.data.data_source.InternalDatabase
import com.diegonmarcos.mediacenter.feature_node.data.data_source.contentindex.FolderContentIndexer
import com.diegonmarcos.mediacenter.feature_node.data.data_source.contentindex.IndexScanner
import com.diegonmarcos.mediacenter.feature_node.data.data_source.contentindex.IndexSummary
import com.diegonmarcos.mediacenter.feature_node.data.data_source.contentindex.IndexableMedia
import com.diegonmarcos.mediacenter.feature_node.data.data_source.contentindex.IndexedContent
import com.diegonmarcos.mediacenter.feature_node.data.data_source.contentindex.toIndexableMedia
import com.diegonmarcos.mediacenter.feature_node.domain.model.Media
import com.diegonmarcos.superapp.image.mlkit.BarcodePayload
import com.diegonmarcos.superapp.image.mlkit.ImageScanEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface FolderIndexUiState {
    data object Idle : FolderIndexUiState
    data class Running(val indexed: Int, val total: Int) : FolderIndexUiState
    data class Finished(val summary: IndexSummary) : FolderIndexUiState
    data class Cancelled(val indexed: Int, val total: Int) : FolderIndexUiState
}

@HiltViewModel
class FolderIndexViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    database: InternalDatabase
) : ViewModel() {

    private val dao = database.getMediaContentIndexDao()
    private val engine = ImageScanEngine(appContext)

    private val _uiState = MutableStateFlow<FolderIndexUiState>(FolderIndexUiState.Idle)
    val uiState: StateFlow<FolderIndexUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    fun startIndex(albumId: Long, items: List<Media.UriMedia>) {
        val mediaList = items.map { it.toIndexableMedia() }
        if (mediaList.isEmpty()) {
            _uiState.value = FolderIndexUiState.Finished(IndexSummary(total = 0, unchanged = 0, updated = 0))
            return
        }
        job?.cancel()
        _uiState.value = FolderIndexUiState.Running(indexed = 0, total = mediaList.size)
        job = viewModelScope.launch(Dispatchers.IO) {
            val indexer = FolderContentIndexer(dao, IndexScanner { item -> scanItem(item) })
            try {
                val summary = indexer.indexFolder(
                    albumId = albumId,
                    items = mediaList,
                    onProgress = { indexed, total -> _uiState.value = FolderIndexUiState.Running(indexed, total) }
                )
                _uiState.value = FolderIndexUiState.Finished(summary)
            } catch (error: CancellationException) {
                val current = _uiState.value
                val indexed = (current as? FolderIndexUiState.Running)?.indexed ?: 0
                val total = (current as? FolderIndexUiState.Running)?.total ?: mediaList.size
                _uiState.value = FolderIndexUiState.Cancelled(indexed, total)
                throw error
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun reset() {
        job?.cancel()
        _uiState.value = FolderIndexUiState.Idle
    }

    /** The scanner the walk uses: permission pre-check, then decode + OCR. */
    private fun scanItem(item: IndexableMedia): IndexedContent {
        if (permissionRefused(Uri.parse(item.uri))) return IndexedContent()
        val barcode = engine.decodeBarcode(Uri.parse(item.uri))
        val ocr = engine.recognizeText(Uri.parse(item.uri))
        return IndexedContent(
            barcodeFormat = barcode?.format,
            barcodeValue = barcode?.rawValue,
            payloadAction = barcode?.payload?.actionKind(),
            ocrText = ocr.text.takeIf { it.isNotBlank() },
            language = ocr.language?.takeIf { it.isNotBlank() }
        )
    }

    private fun permissionRefused(uri: Uri): Boolean = try {
        appContext.contentResolver.openInputStream(uri)?.close() ?: false
        false
    } catch (error: SecurityException) {
        true
    } catch (error: Exception) {
        false
    }

    private fun BarcodePayload.actionKind(): String = when (this) {
        is BarcodePayload.Url -> "url"
        is BarcodePayload.Wifi -> "wifi"
        is BarcodePayload.Contact -> "contact"
        is BarcodePayload.Calendar -> "calendar"
        is BarcodePayload.Phone -> "phone"
        is BarcodePayload.Email -> "email"
        is BarcodePayload.Geo -> "geo"
        is BarcodePayload.Plain -> "plain"
    }
}