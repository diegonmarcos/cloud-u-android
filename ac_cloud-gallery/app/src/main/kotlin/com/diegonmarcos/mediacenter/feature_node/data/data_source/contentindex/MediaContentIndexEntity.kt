/*
 * Task #460 — folder content index.
 *
 * One row per indexed image, persisted in the app's OWN Room database
 * (InternalDatabase) — the requirement is "persist with the existing store
 * mechanism", so there is deliberately no second store here. The fingerprint
 * is what makes indexing incremental: when the folder is indexed again, a row
 * whose fingerprint still matches the media item is skipped without re-reading
 * the image bytes.
 */

package com.diegonmarcos.mediacenter.feature_node.data.data_source.contentindex

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_content_index",
    indices = [
        Index(value = ["albumId"]),
        Index(value = ["barcodeValue"]),
        Index(value = ["ocrText"])
    ]
)
data class MediaContentIndexEntity(
    @PrimaryKey
    val mediaId: Long,
    val albumId: Long,
    /** The image's content URI, for opening the item from a search result later. */
    val uri: String,
    /** "size:dateModified" — the incremental-skip fingerprint. */
    val fingerprint: String,
    /** ZXing format name ("QR_CODE", "CODE_128", …) or null when no barcode. */
    val barcodeFormat: String? = null,
    /** The raw decoded value (never shown raw; kept for copy/search). */
    val barcodeValue: String? = null,
    /** The typed payload kind ("url", "wifi", "contact", "calendar", "plain", …). */
    val payloadAction: String? = null,
    /** The recognised text, or null when OCR found none. */
    val ocrText: String? = null,
    /** BCP-47 tag of the language the recogniser identified, when it could. */
    val language: String? = null,
    val indexedAt: Long = 0L
)