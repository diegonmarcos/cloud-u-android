/*
 * Task #460 — the incremental folder-content walk.
 *
 * Pure logic: given a DAO and a scanner it decides, per image, whether the
 * stored row is still fresh (same fingerprint → the image is NOT re-read) or
 * must be re-scanned and upserted. Progress is reported as real numbers
 * ("indexed 412 of 1,908"), never a spinner. The coroutine context's
 * cancellation is honoured between items so the sheet's Cancel actually stops
 * the walk. All logic here is JVM-pure and covered by unit tests.
 */

package com.diegonmarcos.mediacenter.feature_node.data.data_source.contentindex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The scanner signature the walk depends on: turn one image into the content
 * the index stores. In the app this is ImageScanEngine (decode + OCR); in
 * unit tests a counting fake proves unchanged files are never scanned.
 */
fun interface IndexScanner {
    suspend fun scan(item: IndexableMedia): IndexedContent
}

class FolderContentIndexer(
    private val dao: MediaContentIndexDao,
    private val scanner: IndexScanner
) {

    /**
     * Indexes [items] for [albumId], reporting [onProgress] after every image
     * as (indexed, total). Already-indexed unchanged images are skipped
     * without a scanner call. Returns the walk's [IndexSummary].
     */
    suspend fun indexFolder(
        albumId: Long,
        items: List<IndexableMedia>,
        onProgress: (indexed: Int, total: Int) -> Unit = { _, _ -> }
    ): IndexSummary {
        var unchanged = 0
        var updated = 0
        var barcodes = 0
        var withText = 0
        var failed = 0

        items.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            val existing = dao.byMediaId(item.id)
            if (existing != null && existing.fingerprint == item.fingerprint() && existing.albumId == albumId) {
                unchanged++
                if (existing.barcodeFormat != null) barcodes++
                if (!existing.ocrText.isNullOrBlank()) withText++
            } else {
                val content = try {
                    scanner.scan(item)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failed++
                    null
                }
                if (content != null) {
                    dao.upsert(
                        MediaContentIndexEntity(
                            mediaId = item.id,
                            albumId = albumId,
                            uri = item.uri,
                            fingerprint = item.fingerprint(),
                            barcodeFormat = content.barcodeFormat,
                            barcodeValue = content.barcodeValue,
                            payloadAction = content.payloadAction,
                            ocrText = content.ocrText,
                            language = content.language,
                            indexedAt = System.currentTimeMillis()
                        )
                    )
                    updated++
                    if (content.barcodeFormat != null) barcodes++
                    if (!content.ocrText.isNullOrBlank()) withText++
                }
            }
            onProgress(index + 1, items.size)
        }

        return IndexSummary(
            total = items.size,
            unchanged = unchanged,
            updated = updated,
            barcodes = barcodes,
            withText = withText,
            failed = failed
        )
    }
}