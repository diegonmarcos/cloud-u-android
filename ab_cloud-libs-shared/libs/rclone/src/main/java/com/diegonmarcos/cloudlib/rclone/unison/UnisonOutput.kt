package com.diegonmarcos.cloudlib.rclone.unison

/**
 * #1084 what a Unison text run says, read back. Measured against unison 2.53.8 (ocaml 4.14.2) on
 * 2026-10-21; the lines this reads are the ones its text UI ends every run with:
 *
 *   Synchronization complete at 19:03:58  (2 items transferred, 0 skipped, 0 failed)
 *     skipped: one.txt (contents changed on both sides)
 *     failed: x.bin (Permission denied)
 *
 * Exit codes (Unison's manual): 0 every item synchronised, 1 some files were skipped, 2 non-fatal
 * failures, 3 fatal error. A SKIPPED item in batch mode is a CONFLICT: both sides changed, or one
 * changed and the other deleted, and Unison will not choose. That is the whole conflict surface of
 * the UI: nothing is overwritten until the user picks keep-A, keep-B or keep-both.
 */
object UnisonOutput {
    data class Conflict(val path: String, val reason: String)
    data class Failure(val path: String, val reason: String)

    data class Summary(val complete: Boolean, val transferred: Int, val skipped: Int, val failed: Int)

    data class Result(
        val rc: Int,
        val summary: Summary?,
        val conflicts: List<Conflict>,
        val failures: List<Failure>,
        val fatal: String?,
        val rolledBack: Boolean,
    ) {
        /** One line for the card: what happened, honest about skips, failures and an interrupted run. */
        fun headline(): String = when {
            rc == -2 -> "cancelled" + if (rolledBack) " · archives restored" else ""
            rc == 3 || fatal != null -> "fatal: ${fatal ?: "exit $rc"}" + if (rolledBack) " · archives restored" else ""
            rc < 0 -> "not run" + (fatal?.let { ": $it" } ?: "") + if (rolledBack) " · archives restored" else ""
            summary == null -> "exit $rc"
            else -> "${summary.transferred} transferred · ${conflicts.size} conflict(s) · ${failures.size} failed"
        }
        val ok: Boolean get() = rc == 0
    }

    private val SUMMARY = Regex("""Synchronization (complete|incomplete) at \S+\s+\((\d+) items? transferred, (\d+) skipped, (\d+) failed\)""")
    private val SKIPPED = Regex("""^\s+skipped: (.+) \(([^()]*)\)\s*$""")
    private val FAILED = Regex("""^\s+failed: (.+) \(([^()]*)\)\s*$""")
    private val FATAL = Regex("""^(Fatal error|Uncaught exception|Error): (.*)$""")
    private val PROGRESS = Regex("""^\s*\d+%\s""")

    /** Fold the whole output of a run (every line the engine streamed) into a [Result]. */
    fun parse(lines: List<String>, rc: Int, rolledBack: Boolean = false): Result {
        var summary: Summary? = null
        val conflicts = LinkedHashMap<String, Conflict>()
        val failures = LinkedHashMap<String, Failure>()
        var fatal: String? = null
        for (raw in lines) {
            val line = raw.replace("\r", "\n").lines().lastOrNull { it.isNotBlank() } ?: continue
            SUMMARY.find(line)?.let { m ->
                summary = Summary(m.groupValues[1] == "complete", m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt())
            }
            SKIPPED.find(line)?.let { conflicts[it.groupValues[1]] = Conflict(it.groupValues[1], it.groupValues[2]) }
            FAILED.find(line)?.let { failures[it.groupValues[1]] = Failure(it.groupValues[1], it.groupValues[2]) }
            if (fatal == null) FATAL.find(line)?.let { fatal = it.groupValues[2].trim() }
        }
        return Result(rc, summary, conflicts.values.toList(), failures.values.toList(), fatal, rolledBack)
    }

    /** True for the redrawn progress ticker and its [BGN]/[END] markers: noise in a log a person reads. */
    fun isNoise(line: String): Boolean = PROGRESS.containsMatchIn(line) || line.contains("[BGN]") || line.contains("[END]")

    /** A line as the Sync tab's log shows it: the last carriage-return segment, or null when it is noise. */
    fun display(line: String): String? {
        val seg = line.replace("\r", "\n").lines().lastOrNull { it.isNotBlank() }?.trimEnd() ?: return null
        return if (isNoise(seg)) null else seg
    }
}
