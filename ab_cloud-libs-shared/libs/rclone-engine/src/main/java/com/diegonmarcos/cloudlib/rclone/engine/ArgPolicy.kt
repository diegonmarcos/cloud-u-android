package com.diegonmarcos.cloudlib.rclone.engine

/**
 * What a CLIENT may ask this engine to run. The engine is guarded by the constellation's signature
 * permission, so the callers are the fleet's own apps; this is the second lock, so a bug or a
 * sibling app cannot turn "run rclone" into "run anything": the verb must be one of the declared
 * ones, and the flags that would repoint the config, run a command or open a control port are
 * refused. The engine adds its own `--config` and `--cache-dir`; a caller's would win over them.
 */
internal object ArgPolicy {

    /** The rclone verbs the screens use: probe, secrets, browse, one-file copy, and the five job operations. */
    val VERBS: Set<String> = setOf("version", "obscure", "lsjson", "copyto", "copy", "sync", "move", "check", "bisync")

    /** Flag prefixes that repoint the config or the cache, run a command, or open a remote-control / metrics port. */
    private val DENIED_FLAGS = listOf("--config", "--cache-dir", "--password-command", "--rc", "--metrics", "--temp-dir", "--log-file", "--use-server-modtime-cmd")

    /** Null when [args] may run, else the sentence that says why not. */
    fun refusal(args: List<String>): String? {
        val verb = args.firstOrNull() ?: return "no rclone verb given"
        if (verb !in VERBS) return "rclone verb '$verb' is not one this engine runs (${VERBS.joinToString(", ")})"
        for (a in args.drop(1)) {
            val flag = a.substringBefore('=')
            if (flag.startsWith("--") && DENIED_FLAGS.any { flag == it || flag.startsWith("$it-") })
                return "rclone flag '$flag' is not allowed through this engine"
        }
        return null
    }

    /** True when an argument names the shared store: only then does the all-files grant matter. */
    fun touchesSharedStorage(args: List<String>): Boolean =
        args.drop(1).any { it.startsWith("/storage/") || it.startsWith("/sdcard") || it.startsWith("/mnt/") || it.startsWith("/data/media/") }
}
