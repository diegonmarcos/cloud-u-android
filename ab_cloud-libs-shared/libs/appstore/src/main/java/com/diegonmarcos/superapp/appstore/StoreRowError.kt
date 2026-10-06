package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.updater.source.DownloadFailure

/**
 * #831 HOW A FAILED ROW LOOKS, decided in one pure place.
 *
 * The owner saw "Unable to resolve host github.com…" drawn cut off mid-sentence
 * in a one-line slot, beside a ✓, above the row it belonged to. Three rules:
 *  · a row that failed never carries ✓ — its mark is ⚠;
 *  · the collapsed meta slot (one line, shared with name and size) gets a SHORT
 *    word, never the reason, so nothing is cut mid-sentence;
 *  · the full reason goes in the row's own error area, under the row, folded to
 *    [FOLDED_LINES] when long and expanded on tap — never truncated for good;
 *  · a DNS failure offers the DNS page as a BUTTON (#639), not as instructions;
 *  · a failed row keeps its ways out IN the error area — Retry and the direct
 *    APK link — since the quick button is hidden on a failed row.
 */
object StoreRowError {
    const val GLYPH = "⚠"
    const val META = "failed — reason below"
    const val FOLDED_LINES = 3
    const val DNS_BUTTON = "Open DNS page"
    const val RETRY_BUTTON = "↻ Retry"
    const val APK_BUTTON = "APK↗"

    data class Look(val glyph: String, val meta: String, val error: String, val dnsButton: Boolean)

    /** [stageFailure] (a stopped stage) wins over [stateError] (a failed check); null = row is fine. */
    fun of(stageFailure: String?, stateError: String?): Look? {
        val err = stageFailure?.takeIf { it.isNotBlank() } ?: stateError?.takeIf { it.isNotBlank() } ?: return null
        return Look(GLYPH, META, err, DownloadFailure.mentionsDns(err))
    }

    /** Long enough that folding it hides something: offer the tap to expand. */
    fun foldable(error: String): Boolean = error.length > 160 || error.count { it == '\n' } >= FOLDED_LINES

    /** The progress banner above the list names the app and points at its row; the reason lives there, whole. */
    fun banner(app: String): String = "$GLYPH ${app.ifEmpty { "Download" }} failed — full reason on its row (tap)"
}
