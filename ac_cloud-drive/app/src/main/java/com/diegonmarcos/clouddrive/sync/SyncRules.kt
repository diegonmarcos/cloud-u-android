package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.cloudlib.rclone.RcloneJob

/**
 * #604 THE ONE mapping from a declared folder↔folder rule onto libs:rclone's own job
 * model. A rule is not a second transfer mechanism: it is an [RcloneJob] the engine,
 * the job store, the live stats and the run history already know how to handle, so
 * "Run now" on a rule card is the SAME code path as Run on a job row.
 *
 * bidirectional → bisync · upload → copy local→remote · download → copy remote→local.
 * copy never deletes; a rule that wants deletion is a job, declared in
 * data/drive-rclone-jobs.json, not a fifth direction here.
 *
 * Pure (no Android), so the JVM suite exercises the exact mapping the phone runs.
 */
object SyncRules {

    /** The rclone verb a direction means. */
    fun op(direction: String): String =
        if (direction == Declarations.DIRECTION_BIDIRECTIONAL) "bisync" else "copy"

    /** The job a rule runs: same id, so the store's last-run record follows the rule. */
    fun job(rule: Declarations.SyncRuleDecl): RcloneJob {
        val local = rule.localPath
        val remote = rule.remoteLeg
        val downward = rule.direction == Declarations.DIRECTION_DOWNLOAD
        return RcloneJob(
            id = "rule-" + rule.id,
            name = rule.id,
            op = op(rule.direction),
            source = if (downward) remote else local,
            destination = if (downward) local else remote,
            declared = true,
        )
    }

    /** `local  ⇄  remote:path` — the one-line reading of a rule, arrow from its direction. */
    fun arrow(direction: String): String = when (direction) {
        Declarations.DIRECTION_UPLOAD -> "→"
        Declarations.DIRECTION_DOWNLOAD -> "←"
        else -> "⇄"
    }
}
