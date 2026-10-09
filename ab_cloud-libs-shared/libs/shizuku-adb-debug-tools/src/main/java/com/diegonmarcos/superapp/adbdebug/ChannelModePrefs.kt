package com.diegonmarcos.superapp.adbdebug

import android.content.Context

/** The "Privileged channel" setting: stored in the already-declared adb_shell prefs. */
object ChannelModePrefs {
    private const val PREFS = "adb_shell"
    private const val KEY = "channel_mode"

    /** True when this app declares its own local server (build.json::shizuku_diagnostics.local_server). */
    fun ownsServer(): Boolean = BuildConfig.ADB_SHELL_SERVER_OWN

    /** Local server where the app owns one (SuperApp, Store, Account), else Auto: a terminal has no server of its own. */
    fun default(): ChannelMode = if (ownsServer()) ChannelMode.LOCAL_SERVER else ChannelMode.AUTO

    fun current(ctx: Context): ChannelMode = runCatching {
        ChannelMode.parse(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null), default())
    }.getOrDefault(default())

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(ChannelMode) -> Unit>()

    /** Told right after the owner changes the mode, so a status can re-probe at once. */
    fun addListener(l: (ChannelMode) -> Unit) { listeners.addIfAbsent(l) }

    fun set(ctx: Context, mode: ChannelMode) {
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.id).commit() }
        // The next ShellChannels.active() already reads the new mode (the install route follows).
        listeners.forEach { runCatching { it(mode) } }
    }
}

/** Who launches the local server (Embedded adb or Shizuku): the page's choice, kept in the same prefs. */
object LaunchViaPrefs {
    private const val PREFS = "adb_shell"
    private const val KEY = "launch_via"

    fun current(ctx: Context): LaunchVia = runCatching {
        LaunchVia.parse(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
    }.getOrDefault(LaunchVia.ADB)

    fun set(ctx: Context, via: LaunchVia) {
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, via.id).commit() }
    }

    /** [sources] (channels allowed to launch the server) with the chosen launcher first. */
    fun order(ctx: Context, sources: List<ShellChannel>): List<ShellChannel> {
        val first = if (current(ctx) == LaunchVia.SHIZUKU) ChannelSelector.SHIZUKU else ChannelSelector.EMBEDDED
        return sources.sortedBy { if (it.name() == first) 0 else 1 }
    }
}
