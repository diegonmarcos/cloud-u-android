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

    /**
     * #1173 the verbs of Cloud Drive's in-app `rclone config` (Mounts > Rclone). Each takes a subcommand from a closed list
     * ([SUBCOMMANDS]): `config` only providers/create/update/show/delete (never `edit`, `touch`, `file`, `password` or `dump`),
     * `backend` only `drives`, `serve` only `webdav` and only on loopback ([serveRefusal]). `lsd`, `about`, `authorize` and
     * `listremotes` have no subcommand.
     */
    val CONFIG_VERBS: Set<String> = setOf("config", "lsd", "about", "authorize", "listremotes", "backend", "serve")
    val SUBCOMMANDS: Map<String, Set<String>> = mapOf(
        "config" to setOf("providers", "create", "update", "show", "delete"),
        "backend" to setOf("drives"),
        "serve" to setOf("webdav"),
    )

    /** Flag prefixes that repoint the config or the cache, run a command, or open a remote-control / metrics port. */
    private val DENIED_FLAGS = listOf("--config", "--cache-dir", "--password-command", "--rc", "--metrics", "--temp-dir", "--log-file", "--use-server-modtime-cmd")

    /** Null when [args] may run, else the sentence that says why not. */
    fun refusal(args: List<String>): String? {
        val verb = args.firstOrNull() ?: return "no rclone verb given"
        if (verb !in VERBS && verb !in CONFIG_VERBS) return "rclone verb '$verb' is not one this engine runs (${(VERBS + CONFIG_VERBS).joinToString(", ")}): update Cloud-Lib-Rclone-Engine from Store"
        SUBCOMMANDS[verb]?.let { allowed ->
            val sub = args.getOrNull(1)
            if (sub == null || sub !in allowed) return "rclone $verb ${sub.orEmpty()} is not one this engine runs ($verb ${allowed.joinToString("|")})".replace("  ", " ")
        }
        if (verb == "serve") serveRefusal(args)?.let { return it }
        for (a in args.drop(1)) {
            val flag = a.substringBefore('=')
            if (flag.startsWith("--") && DENIED_FLAGS.any { flag == it || flag.startsWith("$it-") })
                return "rclone flag '$flag' is not allowed through this engine"
        }
        return null
    }

    /** `serve webdav` may only listen on this phone's loopback: an `--addr` is required and must be 127.0.0.1 or localhost. */
    private fun serveRefusal(args: List<String>): String? {
        val addr = args.drop(2).mapIndexedNotNull { i, a ->
            when {
                a.startsWith("--addr=") -> a.removePrefix("--addr=")
                a == "--addr" -> args.drop(2).getOrNull(i + 1) ?: ""
                else -> null
            }
        }
        if (addr.isEmpty()) return "rclone serve needs --addr 127.0.0.1:<port> (loopback only)"
        if (addr.any { !(it.startsWith("127.0.0.1:") || it.startsWith("localhost:")) }) return "rclone serve may only listen on 127.0.0.1"
        return null
    }

    /** True when an argument names the shared store: only then does the all-files grant matter. */
    fun touchesSharedStorage(args: List<String>): Boolean =
        args.drop(1).any { it.startsWith("/storage/") || it.startsWith("/sdcard") || it.startsWith("/mnt/") || it.startsWith("/data/media/") }
}
