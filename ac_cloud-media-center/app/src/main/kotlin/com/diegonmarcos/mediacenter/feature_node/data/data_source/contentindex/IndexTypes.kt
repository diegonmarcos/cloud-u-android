/*
 * Task #460 — folder content index types.
 *
 * IndexableMedia is the pure-JVM view of a media item the incremental walk
 * needs (identity + change fingerprint + the URI the scanner re-reads). It
 * deliberately carries no Android types so the incremental logic is fully
 * unit-testable on the JVM; the app maps Media.UriMedia to it at the boundary.
 */

package com.diegonmarcos.mediacenter.feature_node.data.data_source.contentindex

import com.diegonmarcos.mediacenter.feature_node.domain.model.Media

/** The parts of an image the indexer reads. No Android types on purpose. */
data class IndexableMedia(
    val id: Long,
    val size: Long,
    /** MediaStore DATE_MODIFIED (seconds) — changes when the bytes change. */
    val dateModified: Long,
    /** The content URI the scanner opens to re-read the image. */
    val uri: String
) {
    /**
     * The incremental-skip fingerprint: same size AND same modified time means
     * the bytes cannot have changed, so the image is not re-read.
     */
    fun fingerprint(): String = "$size:$dateModified"
}

/** Maps a gallery media item to the indexer's pure view. */
fun Media.UriMedia.toIndexableMedia(): IndexableMedia = IndexableMedia(
    id = id,
    size = size,
    dateModified = timestamp,
    uri = uri.toString()
)

/** What a single image contributed to the index. */
data class IndexedContent(
    val barcodeFormat: String? = null,
    val barcodeValue: String? = null,
    val payloadAction: String? = null,
    val ocrText: String? = null,
    val language: String? = null
)

/** The outcome of one folder walk. [total] is the number of images examined. */
data class IndexSummary(
    val total: Int,
    val unchanged: Int,
    val updated: Int,
    val barcodes: Int = 0,
    val withText: Int = 0,
    val failed: Int = 0
)