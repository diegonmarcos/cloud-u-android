/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.shell

import android.content.Context
import android.net.Uri
import com.diegonmarcos.mediacenter.feature_node.data.data_source.InternalDatabase
import com.diegonmarcos.mediacenter.feature_node.data.data_source.contentindex.FolderContentIndexer
import com.diegonmarcos.mediacenter.feature_node.data.data_source.contentindex.IndexScanner
import com.diegonmarcos.mediacenter.feature_node.data.data_source.contentindex.IndexableMedia
import com.diegonmarcos.mediacenter.feature_node.data.data_source.contentindex.IndexedContent
import com.diegonmarcos.mediacenter.feature_node.data.data_source.contentindex.toIndexableMedia
import com.diegonmarcos.mediacenter.feature_node.domain.model.Media
import com.diegonmarcos.mediacenter.feature_node.domain.util.isImage
import com.diegonmarcos.superapp.image.mlkit.BarcodePayload
import com.diegonmarcos.superapp.image.mlkit.ImageScanEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** A pass of the text index: [done] of [total] images read so far. */
data class TextIndexProgress(val running: Boolean = false, val done: Int = 0, val total: Int = 0)

/**
 * The text/document index: OCR (and barcodes) of every image, read on this phone by the shared
 * image engine (ML Kit, libs:ml-l-image) and stored in the app's own Room table. Incremental:
 * FolderContentIndexer skips an image whose size and modified time are unchanged, so a second
 * pass only reads what is new. One process-wide instance, so the Home and Objects screens show the
 * same pass.
 */
@Singleton
class TextIndexRunner @Inject constructor(
    @ApplicationContext private val appContext: Context,
    database: InternalDatabase,
) {
    private val dao = database.getMediaContentIndexDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine by lazy { ImageScanEngine(appContext) }
    private var job: Job? = null

    private val _progress = MutableStateFlow(TextIndexProgress())
    val progress: StateFlow<TextIndexProgress> = _progress.asStateFlow()

    private val _engineProblem = MutableStateFlow<String?>(null)

    /** The sentence saying why the engine cannot be used, or null when it is installed and answers. Set by [probe]. */
    val engineProblem: StateFlow<String?> = _engineProblem.asStateFlow()

    /** Asks the engine whether it can serve; binds it, so it runs off the main thread. */
    fun probe() {
        scope.launch { _engineProblem.value = runCatching { engine.check() }.getOrElse { it.message ?: "image engine unavailable" } }
    }

    /** Reads every image of [media] not yet indexed (or changed since). A pass already running is left alone. */
    fun start(media: List<Media.UriMedia>) {
        if (job?.isActive == true) return
        val byAlbum = media.filter { it.isImage }.groupBy { it.albumID }
        val total = byAlbum.values.sumOf { it.size }
        if (total == 0) return
        _progress.value = TextIndexProgress(running = true, done = 0, total = total)
        job = scope.launch {
            val problem = runCatching { engine.check() }.getOrElse { it.message ?: "image engine unavailable" }
            _engineProblem.value = problem
            if (problem != null) {
                _progress.value = TextIndexProgress()
                return@launch
            }
            val indexer = FolderContentIndexer(dao, IndexScanner { item -> scan(item) })
            var before = 0
            try {
                for ((albumId, items) in byAlbum) {
                    indexer.indexFolder(albumId, items.map { it.toIndexableMedia() }) { indexed, _ ->
                        _progress.value = TextIndexProgress(running = true, done = before + indexed, total = total)
                    }
                    before += items.size
                }
                _progress.value = TextIndexProgress(running = false, done = total, total = total)
            } catch (cancelled: CancellationException) {
                _progress.value = TextIndexProgress(running = false, done = before, total = total)
                throw cancelled
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private fun scan(item: IndexableMedia): IndexedContent {
        val uri = Uri.parse(item.uri)
        val readable = try {
            appContext.contentResolver.openInputStream(uri)?.close()
            true
        } catch (error: SecurityException) {
            false
        } catch (error: Exception) {
            true
        }
        if (!readable) return IndexedContent()
        val barcode = engine.decodeBarcode(uri)
        val ocr = engine.recognizeText(uri)
        return IndexedContent(
            barcodeFormat = barcode?.format,
            barcodeValue = barcode?.rawValue,
            payloadAction = barcode?.payload?.let(::actionKind),
            ocrText = ocr.text.takeIf { it.isNotBlank() },
            language = ocr.language?.takeIf { it.isNotBlank() },
        )
    }

    private fun actionKind(payload: BarcodePayload): String = when (payload) {
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
