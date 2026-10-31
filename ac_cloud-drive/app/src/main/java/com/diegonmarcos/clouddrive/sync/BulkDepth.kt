package com.diegonmarcos.clouddrive.sync

import java.io.File

/**
 * #1254 THE DEPTH COMMANDS' PURE RULES (build.json ui.sync.git.depth_actions), JVM-tested in BulkDepthTest.
 *
 *  - clone_missing: ONE CLONE PER REPOSITORY. A registry repository is cloned only when no folder on the device already
 *    holds it. Identity is the canonical registry name of the clone's ORIGIN URL (the renamed_from chains folded in by
 *    [canonical]), so a clone made under an old name, or in a folder named otherwise, still counts; a folder with no
 *    origin counts by its folder name. Two candidates naming the same repository yield one clone.
 *  - keep_depth: a clone is shrunk in place only when it is clean, conflict-free, in a SAFE state, has an upstream and
 *    nothing unpushed ([keepSkip] says why not otherwise). Nothing is re-cloned and the remote is never written.
 */
object BulkDepth {

    /** The repository identity of a name or a URL: the canonical registry name, lower-case; "" = none. */
    fun identity(nameOrUrl: String, canonical: (String) -> String): String {
        val n = if ('/' in nameOrUrl || ':' in nameOrUrl) LocalRepos.repoNameOf(nameOrUrl) else nameOrUrl.trim()
        return if (n.isBlank()) "" else canonical(n).lowercase()
    }

    /** A clone that exists on the device: its folder and its origin URL ("" = none). */
    data class Existing(val path: String, val origin: String)
    /** A registry repository the page could clone: its name and the URL it would clone from. */
    data class Target(val name: String, val url: String)
    /** [toClone] in the given order; [held] = name -> the folder that already holds that repository. */
    data class ClonePlan(val toClone: List<Target>, val held: Map<String, String>)

    fun planClones(candidates: List<Target>, existing: List<Existing>, canonical: (String) -> String): ClonePlan {
        val byId = LinkedHashMap<String, String>()
        existing.forEach { e ->
            val id = identity(e.origin.ifBlank { File(e.path).name }, canonical)
            if (id.isNotBlank()) byId.putIfAbsent(id, e.path)
        }
        val held = LinkedHashMap<String, String>()
        val out = ArrayList<Target>()
        val planned = HashSet<String>()
        candidates.forEach { t ->
            val ids = listOf(identity(t.name, canonical), identity(t.url, canonical)).filter { it.isNotBlank() }.distinct()
            val hit = ids.firstNotNullOfOrNull { byId[it] }
            when {
                hit != null -> held[t.name] = hit
                ids.isEmpty() || ids.any { it in planned } -> Unit
                else -> { planned += ids; out += t }
            }
        }
        return ClonePlan(out, held)
    }

    /** Why a clone must NOT be shrunk, or null when it may. Only facts read from the clone itself. */
    fun keepSkip(cloned: Boolean, changed: Int, conflicts: Int, ahead: Int, upstream: String?, state: String): String? = when {
        !cloned -> SKIP_NOT_CLONED
        conflicts > 0 -> "$SKIP_CONFLICT: $conflicts"
        state.isNotBlank() && state != "SAFE" -> "$SKIP_STATE: $state"
        changed > 0 -> "$SKIP_DIRTY: $changed uncommitted"
        upstream.isNullOrBlank() -> SKIP_NO_UPSTREAM
        ahead > 0 -> "$SKIP_AHEAD: $ahead unpushed"
        else -> null
    }

    const val SKIP_NOT_CLONED = "not cloned"
    const val SKIP_CONFLICT = "conflicts"
    const val SKIP_STATE = "busy"
    const val SKIP_DIRTY = "dirty"
    const val SKIP_NO_UPSTREAM = "no upstream"
    const val SKIP_AHEAD = "ahead"

    /** One depth command in flight or last finished: what the status window and the progress line show. */
    data class Run(
        val actionId: String,
        val kind: String,
        val total: Int,
        val done: Int = 0,
        val ok: Map<String, String> = emptyMap(),
        val skipped: Map<String, String> = emptyMap(),
        val failed: Map<String, String> = emptyMap(),
        val cancelled: Boolean = false,
        val ended: Boolean = false,
    ) {
        val finished: Boolean get() = ended
        /** The outcome line of one repository, or null when this run did not touch it. */
        fun outcomeOf(name: String): String? = ok[name]?.let { "ok: $it" } ?: skipped[name]?.let { "skipped: $it" } ?: failed[name]?.let { "failed: $it" }
        /**
         * The report, skipped and failed first (what needs the owner), then ok: #1358 ONE LINE PER REPOSITORY, `<status>  <name>  <reason>`.
         * The name is literal text, never Markdown (`_git-repos-master` and `cloudy-___` stay as they are), and the reason is the
         * real one ([withTail] picks git's own fatal / error line). The full git output stays in Activity Logs.
         */
        fun lines(): List<String> = skipped.map { block("skipped", it.key, it.value) } + failed.map { block("failed", it.key, it.value) } + ok.map { block("ok", it.key, it.value) }
    }

    /** #1358 One report line: `<status>  <name>  <msg>`, every line of [msg] folded onto it with " | " (one line per repository, always). */
    fun block(status: String, name: String, msg: String): String =
        (listOf("$status  $name") + msg.lines().map { it.trim() }.filter { it.isNotBlank() }.let { if (it.isEmpty()) emptyList() else listOf(it.joinToString(" | ")) }).joinToString("  ")

    /** The words of a line that says WHY a git job failed (git's fatal / error, a shell's refusal), not its progress. */
    private val WHY = Regex("(?i)^(fatal|error|mkdir|sh|git|cat|cd|chmod)\\b.*:|denied|not found|cannot|could not|unable|no such|refused|read-only|no space|not a git|timed out|outlived|not reachable|all files access")

    /** #1358 The real reason in [details]: the last line that says why (see [WHY]), else the last line; null when git printed nothing. */
    fun reasonOf(details: String): String? {
        val ls = details.lines().map { it.trim() }.filter { it.isNotBlank() }
        return ls.lastOrNull { WHY.containsMatchIn(it) } ?: ls.lastOrNull()
    }

    /** #1358 A failed git job's ONE-LINE message: the [summary] (with its exit code), then the real reason from what git printed. */
    @Suppress("UNUSED_PARAMETER")
    fun withTail(summary: String, details: String, n: Int = 6): String {
        val why = reasonOf(details) ?: return if ("git printed nothing" in summary) summary else "$summary: git printed nothing"
        return if (why in summary) summary else "$summary: $why"
    }
}
