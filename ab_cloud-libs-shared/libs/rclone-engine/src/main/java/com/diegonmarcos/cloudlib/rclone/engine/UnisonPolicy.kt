package com.diegonmarcos.cloudlib.rclone.engine

/**
 * What a CLIENT may ask the Unison half of this engine to run (#1084). Same second lock as
 * [ArgPolicy]: the engine never runs a caller's argv. It runs `unison <profile> -batch -ui text`
 * and appends only the flags below, with values checked, so a bug or a sibling app cannot turn
 * "run unison" into "run anything" (-sshcmd, -servercmd, -rshcmd, -merge and -diff all name a
 * command; -logfile and -ignorearchives repoint or discard state; none is allowed).
 */
internal object UnisonPolicy {
    /** A profile name becomes a file name in the engine's own unison dir. */
    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    /** Paths handed to -path are relative to a root, never absolute and never climb out of it. */
    fun pathRefusal(p: String): String? = when {
        p.isBlank() -> "an empty -path"
        p.startsWith("/") -> "the -path '$p' is absolute; it is relative to the pair's roots"
        p.split('/').any { it == ".." } -> "the -path '$p' climbs out of the roots"
        '\n' in p || '\u0000' in p -> "the -path contains a control character"
        else -> null
    }

    fun nameRefusal(name: String): String? = if (NAME.matches(name)) null else "unison profile name '$name' is not [A-Za-z0-9._-] (max 64)"

    /**
     * [extra] is the client's flag list: `-path <p>` (repeatable), `-prefer <root|newer|older>`,
     * `-confirmbigdel=false` (only together with -path, the one scoped run a user chose), and the
     * backup trio the conflict resolution needs. Null when allowed, else the sentence why not.
     */
    fun refusal(extra: List<String>, roots: List<String>): String? {
        var i = 0
        var scoped = false
        var bigdel = false
        while (i < extra.size) {
            val flag = extra[i]
            when (flag) {
                "-path" -> {
                    val v = extra.getOrNull(i + 1) ?: return "-path needs a value"
                    pathRefusal(v)?.let { return it }
                    scoped = true; i += 2
                }
                "-prefer" -> {
                    val v = extra.getOrNull(i + 1) ?: return "-prefer needs a value"
                    if (v != "newer" && v != "older" && v !in roots) return "-prefer must be newer, older or one of the pair's roots"
                    i += 2
                }
                "-backup" -> {
                    val v = extra.getOrNull(i + 1) ?: return "-backup needs a value"
                    if (!v.startsWith("Path ") && v != "Name *") return "-backup must be 'Path <p>' or 'Name *'"
                    i += 2
                }
                "-maxbackups" -> {
                    val v = extra.getOrNull(i + 1)?.toIntOrNull() ?: return "-maxbackups needs a number"
                    if (v !in 1..50) return "-maxbackups must be 1..50"
                    i += 2
                }
                "-backuplocation" -> {
                    if (extra.getOrNull(i + 1) != "central") return "-backuplocation must be central"
                    i += 2
                }
                "-confirmbigdel=false" -> { bigdel = true; i += 1 }
                else -> return "unison flag '$flag' is not one this engine passes"
            }
        }
        if (bigdel && !scoped) return "-confirmbigdel=false is only allowed together with -path"
        return null
    }
}
