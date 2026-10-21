package com.diegonmarcos.cloudlib.rclone.unison

/**
 * #1084 CONFLICT RESOLUTION: what each of the three buttons asks the engine to run. Never a silent
 * overwrite: every choice keeps a copy of whatever it replaced (Unison's central backup, in the
 * engine's private dir) and keep-both keeps the replaced version as a visible file.
 *
 * A resolution is ONE scoped run (`-path <file>`), so nothing outside the conflicted file is touched.
 * `-confirmbigdel=false` is allowed only in that scoped run: a file one side deleted would otherwise
 * read as "a whole replica emptied" and be refused (measured on unison 2.53.8).
 */
object UnisonResolve {
    enum class Choice { KEEP_A, KEEP_B, KEEP_BOTH }

    /**
     * [extra] are the flags of the run; [keepCopyOf] is the root whose overwritten version must be copied
     * back beside the kept file once the run is done (keep-both), else null.
     */
    class Plan(val extra: List<String>, val keepCopyRoot: String?, val suffix: String?)

    fun plan(pair: UnisonConfig.Pair, path: String, choice: Choice, stamp: String): Plan {
        val rootA = pair.rootA
        val rootB = UnisonProfile.rootB(pair)
        val keep = when (choice) {
            Choice.KEEP_A -> rootA
            // keep-both: B's version wins the name on BOTH sides, and A's version, which lives on this phone
            // (root A is always local), is backed up where the engine can reach it and copied beside it.
            Choice.KEEP_B, Choice.KEEP_BOTH -> rootB
        }
        val extra = listOf(
            "-path", path,
            "-prefer", keep,
            "-backup", "Path $path",
            "-backuplocation", "central",
            "-maxbackups", pair.maxBackups.toString(),
            "-confirmbigdel=false",
        )
        return if (choice == Choice.KEEP_BOTH) Plan(extra, rootA, "conflict-local-$stamp") else Plan(extra, null, null)
    }

    /** What the card says each choice does, so the user sees the consequence before tapping. */
    fun describe(pair: UnisonConfig.Pair, choice: Choice): String = when (choice) {
        Choice.KEEP_A -> "keep the phone's copy; the other side's version is backed up"
        Choice.KEEP_B -> "keep the other side's copy; the phone's version is backed up"
        Choice.KEEP_BOTH -> "keep the other side's copy under the name and the phone's version beside it as .conflict-local-…"
    }
}
