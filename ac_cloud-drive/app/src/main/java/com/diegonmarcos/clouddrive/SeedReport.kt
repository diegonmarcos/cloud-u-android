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

        /**
         * #683 The provider demanded a credential and this device holds none the declared chain
         * (vault import first, then `auth.git_chain`) can answer with. TERMINAL, like
         * [UNDECLARED]: a retry cannot mint a credential, so looping on it would burn battery
         * and quota against a clone that can never succeed. Distinct from [FAILED] because the
         * way out is different — not another pass, but the vault import or a fleet sign-in.
         */
        const val NEEDS_CREDENTIAL = "needs-credential"

        /** The kinds a repository can carry and still leave the store finished. */
        val SETTLED = setOf(PRESENT, SEEDED, UNDECLARED)
        /** The kinds another pass could still turn into a clone. */
        val RESUMABLE = setOf(FAILED, DEFERRED)
        /** #683 Terminal but NOT finished: no retry can help, and the store still lacks the repository. */
        val BLOCKED = setOf(NEEDS_CREDENTIAL)
    }
}

/** [declared] is the size of the declared seed set, so a missing outcome is itself a failure. */
data class SeedReport(val declared: Int, val outcomes: List<SeedOutcome>) {

    val present: List<SeedOutcome> get() = outcomes.filter { it.kind in SeedOutcome.SETTLED }
    val resumable: List<SeedOutcome> get() = outcomes.filter { it.kind in SeedOutcome.RESUMABLE }
    /** #683 the repositories no pass can reach without a credential this device does not hold. */
    val blocked: List<SeedOutcome> get() = outcomes.filter { it.kind in SeedOutcome.BLOCKED }

    /**
     * The pass is TERMINAL when EVERY declared repository produced an outcome and none of them is
     * resumable — this is what drives retry-vs-success, and counting only the successes (or only
     * the absence of exceptions) is how a nine-of-twelve store reported itself green. #683 a
     * [blocked] repository is deliberately terminal too: a retry cannot mint the credential it
     * lacks, so the worker must not loop on it — but the TALLY still refuses to call such a
     * store "complete", because it is not.
     */
    val complete: Boolean get() = outcomes.size == declared && resumable.isEmpty()

    /** The one-line verdict the tail of the report carries. */
    fun tally(): String = "seed ${present.size}/$declared " + when {
        !complete -> "INCOMPLETE, will retry: " + resumable.joinToString { it.name }
        blocked.isNotEmpty() -> "settled; needs a credential (no retry — the vault import or a fleet sign-in delivers one): " + blocked.joinToString { it.name }
        else -> "complete"
    }

    /** One line per repository, then the tally — nothing aggregated away. */
    fun lines(): List<String> = outcomes.map { it.line() } + tally()

    fun text(): String = lines().joinToString("\n")
}
