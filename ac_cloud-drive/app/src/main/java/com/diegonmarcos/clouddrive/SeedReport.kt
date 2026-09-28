package com.diegonmarcos.clouddrive

/**
 * #629 WHAT THE FIRST-RUN SEED DID, per repository — the part that was missing and that made a
 * truncated store look like a finished one.
 *
 * The defect this exists for: [StoreSeedWorker] logged a failure with `Log.w` and carried on, and
 * then returned `Result.success()` whatever the tally was. WorkManager's execution window is ten
 * minutes; twelve shallow clones do not fit it, so the worker was stopped mid-clone, every
 * remaining repository failed instantly against the torn-down thread, and the unique work ended
 * TERMINALLY GREEN with nine of twelve repositories present — the exact measured state of the
 * owner's device, missing the alphabetical tail.
 *
 * So the pass is now a REPORT: one [SeedOutcome] per DECLARED repository (no repository is ever
 * absent from it — a silent skip is impossible by construction), [complete] is the honest verdict,
 * and the worker turns "not complete" into `Result.retry()` so the tail is resumed instead of
 * being lost. Pure Kotlin, no Android: SeedReportTest runs it on the JVM.
 */
data class SeedOutcome(val name: String, val kind: String, val detail: String) {
    /** The one line the log and the persisted report both carry. */
    fun line(): String = "$kind $name${if (detail.isBlank()) "" else " — $detail"}"

    companion object {
        /** Already cloned before this pass — nothing to do, and not a failure. */
        const val PRESENT = "present"
        /** Cloned by this pass. */
        const val SEEDED = "seeded"
        /** The clone was attempted and failed. The pass is incomplete and must be retried. */
        const val FAILED = "failed"
        /** Not attempted because the worker was stopped. The pass is incomplete and must be retried. */
        const val DEFERRED = "deferred"
        /** The manifest names it but declares no upstream to clone from. Retrying cannot help. */
        const val UNDECLARED = "undeclared"

        /** The kinds a repository can carry and still leave the store finished. */
        val SETTLED = setOf(PRESENT, SEEDED, UNDECLARED)
        /** The kinds another pass could still turn into a clone. */
        val RESUMABLE = setOf(FAILED, DEFERRED)
    }
}

/** [declared] is the size of the declared seed set, so a missing outcome is itself a failure. */
data class SeedReport(val declared: Int, val outcomes: List<SeedOutcome>) {

    val present: List<SeedOutcome> get() = outcomes.filter { it.kind in SeedOutcome.SETTLED }
    val resumable: List<SeedOutcome> get() = outcomes.filter { it.kind in SeedOutcome.RESUMABLE }

    /**
     * The store is finished when EVERY declared repository produced an outcome and none of them is
     * resumable. Counting only the successes — or only the absence of exceptions — is how a
     * nine-of-twelve store reported itself green.
     */
    val complete: Boolean get() = outcomes.size == declared && resumable.isEmpty()

    /** The one-line verdict the tail of the report carries. */
    fun tally(): String = "seed ${present.size}/$declared " +
        (if (complete) "complete" else "INCOMPLETE, will retry: " + resumable.joinToString { it.name })

    /** One line per repository, then the tally — nothing aggregated away. */
    fun lines(): List<String> = outcomes.map { it.line() } + tally()

    fun text(): String = lines().joinToString("\n")
}
