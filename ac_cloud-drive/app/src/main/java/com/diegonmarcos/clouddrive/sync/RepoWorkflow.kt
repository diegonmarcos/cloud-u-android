package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitsync.ConflictSide
import com.diegonmarcos.cloudlib.gitsync.GitChange
import com.diegonmarcos.cloudlib.gitsync.GitStatusSnapshot

/**
 * #1289 WORKFLOW of a repository's menu, pure so the JVM suite holds it: List Dirty (the uncommitted files),
 * List Conflicts (the unmerged paths) and the Solve Conflicts PLAN. The plan resolves each conflicted file the
 * owner chose (keep ours / keep theirs / edited in an editor, then marked resolved), stages it, and continues the
 * merge or rebase in progress. It NEVER pushes: a push stays the owner's own tap.
 */
object RepoWorkflow {

    /** One uncommitted file: [code] is the two-letter porcelain code (index, worktree), e.g. "M ", " M", "??", "UU". */
    data class Dirty(val path: String, val code: String)

    private fun letter(c: GitChange?): Char = when (c) { GitChange.ADDED -> 'A'; GitChange.MODIFIED -> 'M'; GitChange.DELETED -> 'D'; GitChange.UNTRACKED -> '?'; null -> ' ' }

    /** Every uncommitted path (staged, unstaged, untracked; conflicted ones as "UU"), in path order. */
    fun dirty(s: GitStatusSnapshot): List<Dirty> = s.files.map { f ->
        val code = when {
            f.conflicting -> "UU"
            f.unstaged == GitChange.UNTRACKED || f.staged == GitChange.UNTRACKED -> "??"
            else -> "${letter(f.staged)}${letter(f.unstaged)}"
        }
        Dirty(f.path, code)
    }.sortedBy { it.path }

    /** The unmerged paths, in path order. */
    fun conflicts(s: GitStatusSnapshot): List<String> = s.files.filter { it.conflicting }.map { it.path }.distinct().sorted()

    /** What is in progress, from the snapshot's repository state (JGit's names and the terminal wire's). */
    enum class InProgress { NONE, MERGE, REBASE, CHERRY_PICK }

    fun inProgress(state: String): InProgress = when {
        state.startsWith("REBASING") || state.startsWith("REBASE") -> InProgress.REBASE
        state.startsWith("MERGING") -> InProgress.MERGE
        state.startsWith("CHERRY_PICKING") -> InProgress.CHERRY_PICK
        else -> InProgress.NONE
    }

    /** The owner's choice for one conflicted file. EDITED = they fixed it in an editor; the plan only stages it. */
    enum class Choice { OURS, THEIRS, EDITED }

    sealed class Step {
        /** Check out [side]'s version of [path] and stage it. */
        data class Resolve(val path: String, val side: ConflictSide) : Step()
        /** Stage [path] as the owner edited it. */
        data class Stage(val path: String) : Step()
        /** Continue the merge / cherry-pick (a commit) or the rebase; never a push. */
        data class Continue(val what: InProgress) : Step()
    }

    /** [missing] = conflicted files with no choice yet: the plan is not runnable until it is empty. */
    data class Plan(val steps: List<Step>, val missing: List<String>) {
        val ready: Boolean get() = missing.isEmpty() && steps.isNotEmpty()
    }

    fun solvePlan(conflicts: List<String>, choices: Map<String, Choice>, state: String): Plan {
        val missing = conflicts.filter { it !in choices }
        val steps = conflicts.mapNotNull { p ->
            when (choices[p]) {
                Choice.OURS -> Step.Resolve(p, ConflictSide.OURS)
                Choice.THEIRS -> Step.Resolve(p, ConflictSide.THEIRS)
                Choice.EDITED -> Step.Stage(p)
                null -> null
            }
        }
        val what = inProgress(state)
        val tail = if (missing.isEmpty() && steps.isNotEmpty() && what != InProgress.NONE) listOf(Step.Continue(what)) else emptyList()
        return Plan(steps + tail, missing)
    }
}

/**
 * #1289 CONFIGS-AUTOSYNC of one repository, pure: On/Off plus "every X mins" (the declared default, 15), floored at
 * WorkManager's 15; the minutes the ONE periodic GitSyncWorker must tick at so every opted-in repository is served;
 * and the next run a repository's status line shows.
 */
object AutoSyncPlan {
    const val FLOOR_MINUTES = 15L

    /** The period [repoMinutes] names, or [defaultMinutes] when it names none (0); never under the floor. */
    fun minutes(repoMinutes: Long, defaultMinutes: Long): Long = (if (repoMinutes > 0) repoMinutes else defaultMinutes).coerceAtLeast(FLOOR_MINUTES)

    /** Turning AutoSync on writes the period explicitly, so the worker and the status line agree on it. */
    fun toggled(on: Boolean, repoMinutes: Long, defaultMinutes: Long): Pair<Boolean, Long> = on to minutes(repoMinutes, defaultMinutes)

    /** The worker's tick: the smallest period an opted-in repository asks for, never above [baseMinutes] nor under the floor. */
    fun tickMinutes(baseMinutes: Long, optedInMinutes: List<Long>): Long =
        ((optedInMinutes.filter { it > 0 } + baseMinutes).minOrNull() ?: baseMinutes).coerceAtLeast(FLOOR_MINUTES)

    /**
     * Minutes until the repository next syncs: null when AutoSync is off; otherwise when its own period has elapsed since its
     * last sync, but never before the worker's next tick ([tickInMinutes], null when WorkManager has not said).
     */
    fun nextRunMinutes(on: Boolean, periodMinutes: Long, lastSyncEpochSeconds: Long, nowEpochSeconds: Long, tickInMinutes: Long?): Long? {
        if (!on) return null
        val due = if (lastSyncEpochSeconds <= 0) 0L else ((lastSyncEpochSeconds + periodMinutes * 60 - nowEpochSeconds).coerceAtLeast(0) + 59) / 60
        return maxOf(due, tickInMinutes ?: 0L)
    }
}
