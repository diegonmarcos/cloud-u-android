package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import org.json.JSONObject

/**
 * The "keep the channel armed" switch: may this app connect the embedded adb client (and re-arm Wireless
 * debugging) by itself, at start, at boot or when a privileged tool finds no channel? The default is
 * DATA: build.json::privileged_channel.default_on of the consuming app (SuperApp false, Cloud Store true),
 * baked into BuildConfig.CHANNEL_NEEDS_B64. The user's explicit choice (stored) wins over it.
 *
 * Every implicit caller goes through [EmbeddedAdbChannel.autoArm], which asks here; only a tap on the ADB
 * Shell page (connect / pair / reconnect) calls [EmbeddedAdbChannel.autoConnect] directly.
 */
object ChannelAutoArm {
    private const val PREFS = "adb_shell"
    private const val KEY = "auto_arm"

    /** Said by every gated caller when the switch is off. */
    const val OFF_MSG = "auto-arm is off (enable it in the app's settings)"

    /**
     * A host that already keeps the choice elsewhere (SuperApp: Configs > Keep Wireless debugging on) plugs
     * it in here from Application.onCreate, so there is ONE switch; null = this module's own stored choice.
     */
    @Volatile var hostSwitch: ((Context) -> Boolean)? = null

    /** Pure: the declared default of a `privileged_channel` block. A block without it keeps the old behaviour (on). */
    fun declaredDefault(json: String?): Boolean =
        if (json.isNullOrBlank()) true
        else runCatching { JSONObject(json).optBoolean("default_on", true) }.getOrDefault(true)

    /** Pure: the stored choice, else the declared default. */
    fun resolve(stored: Boolean?, json: String?): Boolean = stored ?: declaredDefault(json)

    /** This build's declared default (BuildConfig.CHANNEL_NEEDS_B64). */
    fun defaultOn(): Boolean = declaredDefault(DeclaredNeeds.decode(BuildConfig.CHANNEL_NEEDS_B64))

    fun enabled(ctx: Context): Boolean {
        hostSwitch?.let { h -> runCatching { h(ctx) }.getOrNull()?.let { return it } }
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return runCatching { resolve(if (p.contains(KEY)) p.getBoolean(KEY, false) else null,
            DeclaredNeeds.decode(BuildConfig.CHANNEL_NEEDS_B64)) }.getOrDefault(defaultOn())
    }

    fun set(ctx: Context, on: Boolean) {
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).commit() }
    }
}
