package com.diegonmarcos.superapp.core

import android.content.Context

/**
 * Cloud Store ▸ Configs ▸ Notifications: WHICH Store messages are posted and on WHICH channel. Three kinds (an update
 * is available, an install finished, something failed) times two channels (a system notification / fleet alert, the
 * in-app feed). Everything is on until the owner turns it off, so a fresh install behaves as before.
 *
 * This only narrows [StoreNotifyGate]: a package the gate closes (the SuperApp) posts nothing whatever is stored here.
 * The posters ask [allows]; the settings page flips [set]. The matrix logic is pure ([allows] on a set of switched-off
 * keys) so StoreNotifyPrefsTest holds it without a device.
 */
object StoreNotifyPrefs {

    enum class Kind(val id: String, val label: String, val blurb: String) {
        UPDATE_AVAILABLE("update-available", "Update available", "An app has a newer build and is ready to install."),
        INSTALL_DONE("install-done", "Install done", "An install or update finished, and the unattended pass summary."),
        FAILURES("failures", "Failures", "An install or update failed, or needs a tap to finish."),
    }

    enum class Channel(val id: String, val label: String) {
        PUSH("push", "Notification"),
        IN_APP("in-app", "In-app feed"),
    }

    private const val PREFS = "store_notify"
    private const val KEY_OFF = "off"

    fun key(kind: Kind, channel: Channel): String = "${kind.id}.${channel.id}"

    /** Pure: is [kind] on [channel] allowed, given the keys the owner switched [off]? */
    fun allows(off: Set<String>, kind: Kind, channel: Channel): Boolean = key(kind, channel) !in off

    fun off(ctx: Context): Set<String> = runCatching {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_OFF, emptySet())?.toSet() ?: emptySet()
    }.getOrDefault(emptySet())

    /** The poster's one question: may this package post [kind] on [channel] right now? Gate first, then the owner's switch. */
    fun allows(ctx: Context, kind: Kind, channel: Channel): Boolean =
        StoreNotifyGate.mayPost(ctx) && allows(off(ctx), kind, channel)

    fun set(ctx: Context, kind: Kind, channel: Channel, on: Boolean) {
        val next = off(ctx).toMutableSet().apply { if (on) remove(key(kind, channel)) else add(key(kind, channel)) }
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_OFF, next).apply() }
    }
}
