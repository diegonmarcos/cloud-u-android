package com.diegonmarcos.clouddrive.sync

/** #1138 the command types of a repository's detail, one row (wrapping chips) each, in this order. */
enum class RepoGroup { SYNC, STEPS, GOTO, WORKFLOW, DANGEROUS, REPO_CLEAN, OTHERS, CLONE_SETTINGS }

/**
 * #1138 THE GROUPED ACTIONS of a repository's detail, as data. A declared operation (build.json
 * ui.sync.git.ops) keeps its declared id and its dispatch; the ids added here are the compound sync
 * verbs, the history trim, the extra readers and the clone settings. [GROUPS] is the render order and
 * [route] the confirmation gate, both pure so the JVM suite pins them.
 */
object RepoActions {
    const val SYNC_NO_WINNER = "sync_no_winner"
    const val SYNC_REMOTE_WINS = "sync_remote_wins"
    const val SYNC_LOCAL_WINS = "sync_local_wins"
    const val META_BRANCH = "meta_branch"
    const val META_SYNC = "meta_sync"
    const val OPEN_FOLDER = "open_folder"
    const val CHECKOUT_MAIN = "checkout_main"
    const val BRANCHES = "branches"
    const val PRS = "prs"
    const val STASH_PUSH = "stash_push"
    const val RUNS = "runs"
    const val CLEAN_1 = "clean_1"
    const val CLEAN_5 = "clean_5"
    const val CLEAN_10 = "clean_10"
    const val SYNC_NOW = "sync_now"
    const val SETTINGS = "settings"
    const val CLONE_FULL = "clone_full"
    const val CLONE_DEPTH1 = "clone_depth1"
    const val CLONE_SSH = "clone_ssh"
    const val CLONE_OAUTH = "clone_oauth"

    data class Group(val group: RepoGroup, val actions: List<String>)

    val GROUPS: List<Group> = listOf(
        Group(RepoGroup.SYNC, listOf(SYNC_NO_WINNER, SYNC_REMOTE_WINS, SYNC_LOCAL_WINS)),
        Group(RepoGroup.STEPS, listOf("fetch", "pull", "push", "commit", "stats")),
        Group(RepoGroup.GOTO, listOf(CHECKOUT_MAIN, BRANCHES, OPEN_FOLDER, "web")),
        Group(RepoGroup.WORKFLOW, listOf("history", RUNS, "actions", PRS)),
        Group(RepoGroup.REPO_CLEAN, listOf(CLEAN_1, CLEAN_5, CLEAN_10)),
        Group(RepoGroup.DANGEROUS, listOf("force_pull", "force_push")),
        Group(RepoGroup.OTHERS, listOf("remote", "metadata", SYNC_NOW, "hooks", "size", SETTINGS)),
        Group(RepoGroup.CLONE_SETTINGS, listOf(CLONE_FULL, CLONE_DEPTH1, CLONE_OAUTH, CLONE_SSH)),
    )

    val DANGEROUS: List<String> get() = GROUPS.first { it.group == RepoGroup.DANGEROUS }.actions
    val REPO_CLEAN: List<String> get() = GROUPS.first { it.group == RepoGroup.REPO_CLEAN }.actions

    /** The depth a Repo clean id keeps, or 0 when [id] is not one. */
    fun cleanDepth(id: String): Int = when (id) { CLEAN_1 -> 1; CLEAN_5 -> 5; CLEAN_10 -> 10; else -> 0 }

    enum class Route { RUN, CONFIRM }

    /** Dangerous and Repo clean never run on the tap: they ask first. Everything else runs. */
    fun route(id: String): Route = if (id in DANGEROUS || id in REPO_CLEAN) Route.CONFIRM else Route.RUN

    /** The compound sync verbs and the history trim run through [GitSyncCoordinator.runCompound]. */
    fun isCompound(id: String): Boolean = id == STASH_PUSH || id == SYNC_NO_WINNER || id == SYNC_REMOTE_WINS || id == SYNC_LOCAL_WINS || cleanDepth(id) > 0

    /** The first row of the page: bulk commands over every repo, in this order (Cloud Store's bulk-bar shape). Plain sync = no winner. */
    enum class Bulk { CHECK_ALL, SYNC_ALL, CLONE_ALL, STASH_PUSH_ALL }
}
