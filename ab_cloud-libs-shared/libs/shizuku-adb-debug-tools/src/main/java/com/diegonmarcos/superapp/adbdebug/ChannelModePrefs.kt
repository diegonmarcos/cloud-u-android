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
