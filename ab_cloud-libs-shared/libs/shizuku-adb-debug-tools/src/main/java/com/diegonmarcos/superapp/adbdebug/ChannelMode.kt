package com.diegonmarcos.superapp.adbdebug

/**
 * Which shell channel runs the app's commands, as pure rules (no Android): the owner's
 * "Privileged channel" setting, and the selection it drives.
 *
 * The two adb-flavoured channels are layers of ONE path: embedded adb pairs with adbd and can
 * launch the uid-2000 local server; the server then answers over loopback even when the adb
 * session is gone. LOCAL_SERVER keeps commands off adb entirely and uses adb only to (re)launch
 * the server.
 */
enum class ChannelMode(val id: String) {
    /** Commands only through the local server; embedded adb (or Shizuku) only (re)launches it. */
    LOCAL_SERVER("local"),
    /** Embedded adb, then the local server, then the external providers. */
    AUTO("auto"),
    /** Embedded adb only; the server is skipped. */
    EMBEDDED_ONLY("embedded"),
    /** The external Shizuku app's binder only. */
    SHIZUKU("shizuku");

    companion object {
        fun parse(id: String?, default: ChannelMode): ChannelMode = values().firstOrNull { it.id == id } ?: default
    }
}

object ChannelSelector {
    const val EMBEDDED = "embedded-adb"
    const val LOCAL = "local-server"
    const val SHIZUKU = "shizuku"
    /** The SuperApp's loopback exec route, a terminal's way to the shell (build.json::shizuku_client.providers). */
    const val BRIDGE = "superapp-bridge"

    /** Channel names allowed to RUN COMMANDS in [mode], in order, out of [ladder] (preference order). */
    fun execOrder(mode: ChannelMode, ladder: List<String>): List<String> = when (mode) {
        ChannelMode.AUTO -> ladder
        ChannelMode.LOCAL_SERVER -> ladder.filter { it == LOCAL }
        ChannelMode.EMBEDDED_ONLY -> ladder.filter { it == EMBEDDED }
        ChannelMode.SHIZUKU -> ladder.filter { it == SHIZUKU }
    }

    /** Channel names allowed to LAUNCH the server (one bootstrap line, no other command). */
    fun bootstrapSources(mode: ChannelMode, ladder: List<String>): List<String> = when (mode) {
        ChannelMode.EMBEDDED_ONLY, ChannelMode.SHIZUKU -> emptyList()
        else -> ladder.filter { it != LOCAL }
    }

    /**
     * The channel that runs commands, or null. [usable] = ready (and, for the server, answering).
     * [relaunch] gets the channels that may launch the server and returns true when it is up; it
     * is called lazily in LOCAL_SERVER (only when the server is not usable) and first in AUTO
     * (as before), never in EMBEDDED_ONLY. The caller's [relaunch] is the only code that may run
     * a command over a non-server channel in LOCAL_SERVER mode.
     */
    fun <T> select(
        mode: ChannelMode, ladder: List<T>, name: (T) -> String, usable: (T) -> Boolean,
        relaunch: (List<T>) -> Boolean,
    ): T? {
        val names = ladder.map(name)
        fun byName(n: String) = ladder.first { name(it) == n }
        val sources = bootstrapSources(mode, names).map(::byName)
        if (mode == ChannelMode.AUTO && sources.isNotEmpty()) relaunch(sources)
        val order = execOrder(mode, names).map(::byName)
        order.firstOrNull(usable)?.let { return it }
        if (mode == ChannelMode.LOCAL_SERVER && order.isNotEmpty() && sources.isNotEmpty() && relaunch(sources))
            return order.firstOrNull(usable)
        return null
    }

    /** The control's label in Shizuku mode. */
    fun shizukuLabel(state: ShizukuState): String = "Shizuku: " + when (state) {
        ShizukuState.UP -> "up"
        ShizukuState.NOT_RUNNING -> "not running"
        ShizukuState.PERMISSION_NEEDED -> "permission needed"
        ShizukuState.NOT_INSTALLED -> "not installed"
    }

    /** The control's label for the combined status. [via] = the channel that answered. */
    fun label(via: String?, down: Boolean): String = when {
        via != null -> "Wireless Dbg: up ($via)"
        down -> "Wireless Dbg: down - Reconnect"
        else -> "Wireless Dbg: not paired - Pair"
    }
}

/** Where the external Shizuku app stands, from three facts. */
enum class ShizukuState {
    UP, NOT_RUNNING, PERMISSION_NEEDED, NOT_INSTALLED;

    companion object {
        fun of(installed: Boolean, running: Boolean, granted: Boolean): ShizukuState = when {
            !installed && !running -> NOT_INSTALLED
            !running -> NOT_RUNNING
            !granted -> PERMISSION_NEEDED
            else -> UP
        }
    }
}

/** Display names of the four modes; the one place they are spelled. */
fun ChannelMode.title(): String = when (this) {
    ChannelMode.LOCAL_SERVER -> "Local server"
    ChannelMode.EMBEDDED_ONLY -> "Embedded adb"
    ChannelMode.SHIZUKU -> "Shizuku"
    ChannelMode.AUTO -> "Auto"
}

/** What a mode does, one line. */
fun ChannelMode.hint(): String = when (this) {
    ChannelMode.LOCAL_SERVER -> "Commands run through the local server on 127.0.0.1; adb or Shizuku only starts it."
    ChannelMode.EMBEDDED_ONLY -> "Embedded adb only; the local server is skipped."
    ChannelMode.SHIZUKU -> "The external Shizuku app only."
    ChannelMode.AUTO -> "Embedded adb, then the local server, then Shizuku."
}
