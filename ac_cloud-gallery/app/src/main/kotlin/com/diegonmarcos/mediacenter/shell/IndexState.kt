/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.shell

/** What the Objects tab indexes. Faces and Scenes are WorkManager jobs; Text and Documents come from the OCR content index. */
enum class IndexKind { FACES, SCENES, TEXT, DOCUMENTS }

/** Why a kind cannot be indexed right now. */
enum class IndexBlock {
    /** The on-device model files (face detector + embedding, CLIP) are not installed. */
    MODEL_MISSING,

    /** The shared image engine (ML Kit OCR) is not installed or too old. */
    ENGINE_MISSING,
}

/** The real state of one index: derived from the running job and what is stored, never assumed. */
sealed interface IndexStatus {
    data class Blocked(val why: IndexBlock) : IndexStatus

    /** Nothing indexed yet. */
    data object Idle : IndexStatus

    /** A pass is running; [percent] is null while its length is not known. */
    data class Running(val percent: Int?) : IndexStatus

    /** A pass stopped before covering everything: [indexed] of [total] are done; running again resumes. */
    data class Partial(val indexed: Int, val total: Int) : IndexStatus

    /** Everything that exists is indexed; [indexed] items carry a result. */
    data class Done(val indexed: Int) : IndexStatus
}

object IndexReducer {
    /**
     * A WorkManager-driven index (faces, scenes). [produced] is how many results the job left
     * (people, classified photos); [progress] is the job's own 0..100 figure, anything else = unknown.
     */
    fun fromWork(blocked: IndexBlock?, running: Boolean, progress: Float, produced: Int): IndexStatus = when {
        blocked != null -> IndexStatus.Blocked(blocked)
        running -> IndexStatus.Running(if (progress in 0f..100f) progress.toInt() else null)
        produced > 0 -> IndexStatus.Done(produced)
        else -> IndexStatus.Idle
    }

    /** A counted index (text, documents): [indexed] of [total] images have a row. */
    fun fromCounts(blocked: IndexBlock?, running: Boolean, indexed: Int, total: Int): IndexStatus = when {
        blocked != null -> IndexStatus.Blocked(blocked)
        running -> IndexStatus.Running(if (total > 0) (indexed * 100L / total).toInt().coerceIn(0, 100) else null)
        total <= 0 || indexed <= 0 -> IndexStatus.Idle
        indexed >= total -> IndexStatus.Done(indexed)
        else -> IndexStatus.Partial(indexed, total)
    }
}

/** The four statuses together, for the status card at the top of the tab. */
data class IndexOverview(val status: Map<IndexKind, IndexStatus>) {
    val anyRunning: Boolean get() = status.values.any { it is IndexStatus.Running }

    /** Every kind that can be indexed is done (a blocked kind does not hold the others back, and there must be at least one). */
    val settled: Boolean
        get() {
            val live = status.values.filterNot { it is IndexStatus.Blocked }
            return live.isNotEmpty() && live.all { it is IndexStatus.Done }
        }

    val blockedKinds: List<IndexKind> get() = status.filterValues { it is IndexStatus.Blocked }.keys.toList()

    companion object {
        val EMPTY = IndexOverview(IndexKind.entries.associateWith { IndexStatus.Idle })
    }
}

/** What counts as a "document" among OCR results: a page of text, not a sign or a label. */
object ContentKinds {
    const val DOCUMENT_MIN_WORDS = 25

    fun wordCount(text: String?): Int =
        if (text.isNullOrBlank()) 0 else text.trim().split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }

    fun isDocument(ocrText: String?): Boolean = wordCount(ocrText) >= DOCUMENT_MIN_WORDS

    /** [query] matches [ocrText] when every whitespace-separated term occurs in it, case-insensitive. A blank query matches all. */
    fun matches(ocrText: String?, query: String): Boolean {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true
        val text = ocrText ?: return false
        return terms.all { text.contains(it, ignoreCase = true) }
    }
}
