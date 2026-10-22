package com.diegonmarcos.superapp.network

import android.content.Context

/**
 * #1120 What the owner WANTS of the mesh, kept apart from what the tunnel is doing right now.
 *
 * `wanted` is true until the owner taps Disconnect (Configs > Cloud Mesh > Status) and true again only
 * when they tap Connect or Reconnect: the supervisor ([MeshSupervisorRunner]) reconnects through network
 * changes, tunnel drops, process death and reboot for exactly as long as it is true, and never when it is
 * false. Every deliberate "down" in the app (the page, the Mesh badge's button, the Device controls
 * switch, the Guest profile) goes through [setWanted] so none of them is undone behind the owner's back.
 *
 * The three start-up switches are the owner's conditions on that reconnecting, all on by default:
 * [onBoot] (after a reboot the mesh comes back; off = a reboot is a Disconnect), [onWifi] and [onMobile]
 * (reconnect while Wi-Fi / mobile data is the network; off = wait for the other).
 */
object MeshIntent {
    const val PREFS = "mesh_intent"
    private const val K_WANTED = "wanted"
    private const val K_BOOT = "on_boot"
    private const val K_WIFI = "on_wifi"
    private const val K_MOBILE = "on_mobile"

    data class Policy(val wanted: Boolean = true, val onBoot: Boolean = true, val onWifi: Boolean = true, val onMobile: Boolean = true)

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun policy(ctx: Context): Policy = p(ctx).let {
        Policy(it.getBoolean(K_WANTED, true), it.getBoolean(K_BOOT, true), it.getBoolean(K_WIFI, true), it.getBoolean(K_MOBILE, true))
    }

    fun wanted(ctx: Context): Boolean = p(ctx).getBoolean(K_WANTED, true)
    fun setWanted(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean(K_WANTED, v).commit() }

    fun setOnBoot(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean(K_BOOT, v).commit() }
    fun setOnWifi(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean(K_WIFI, v).commit() }
    fun setOnMobile(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean(K_MOBILE, v).commit() }
}

/**
 * #1120 The reconnect rules, PURE: what the supervisor does about one reading of the world. A decision
 * always carries its [Why], so the journal, the tests and the Status page say the same sentence.
 */
object MeshSupervisor {
    const val BASE_MS = 5_000L
    const val MAX_MS = 300_000L

    enum class Net { WIFI, MOBILE, OTHER, NONE }

    enum class Why { WANTED_OFF, UP, NO_ENGINE, NO_KEY, NO_NETWORK, NET_NOT_ALLOWED, NEEDS_CONSENT, BACKOFF, RECONNECT }

    data class Input(
        val policy: MeshIntent.Policy,
        val engineInstalled: Boolean,
        val up: Boolean,
        val hasKey: Boolean,
        /** VPN consent is not held: a connect would need the system dialog, which nothing in the background can raise. */
        val consentNeeded: Boolean,
        val net: Net,
        val nowMs: Long,
        val lastAttemptMs: Long,
        val failures: Int,
    )

    /** [connect] true = bring it up now; [retryAtMs] when the supervisor should look again with no event (backoff), else null. */
    data class Decision(val connect: Boolean, val why: Why, val retryAtMs: Long? = null)

    /** 5 s, 10 s, 20 s ... capped at 5 min; [failures] 0 = no wait. */
    fun backoffMs(failures: Int): Long =
        if (failures <= 0) 0L else minOf(MAX_MS, BASE_MS shl (failures - 1).coerceAtMost(16))

    fun decide(i: Input): Decision = when {
        !i.policy.wanted -> Decision(false, Why.WANTED_OFF)
        i.up -> Decision(false, Why.UP)
        !i.engineInstalled -> Decision(false, Why.NO_ENGINE)
        !i.hasKey -> Decision(false, Why.NO_KEY)
        i.net == Net.NONE -> Decision(false, Why.NO_NETWORK)
        i.net == Net.WIFI && !i.policy.onWifi -> Decision(false, Why.NET_NOT_ALLOWED)
        i.net == Net.MOBILE && !i.policy.onMobile -> Decision(false, Why.NET_NOT_ALLOWED)
        i.consentNeeded -> Decision(false, Why.NEEDS_CONSENT)
        i.lastAttemptMs > 0 && i.nowMs < i.lastAttemptMs + backoffMs(i.failures) ->
            Decision(false, Why.BACKOFF, i.lastAttemptMs + backoffMs(i.failures))
        else -> Decision(true, Why.RECONNECT)
    }

    /** What a reboot does to the owner's intent: with Connect on boot off, a reboot counts as a Disconnect. */
    fun wantedAfterBoot(p: MeshIntent.Policy): Boolean = p.wanted && p.onBoot

    fun explain(w: Why): String = when (w) {
        Why.WANTED_OFF -> "disconnected by you - stays down until you tap Connect"
        Why.UP -> "connected"
        Why.NO_ENGINE -> "the WireGuard engine is not installed"
        Why.NO_KEY -> "no private key yet"
        Why.NO_NETWORK -> "no network - reconnecting when one returns"
        Why.NET_NOT_ALLOWED -> "this network type is switched off in Start-up"
        Why.NEEDS_CONSENT -> "Android's VPN permission is needed - tap Connect once"
        Why.BACKOFF -> "retrying shortly"
        Why.RECONNECT -> "reconnecting"
    }
}
