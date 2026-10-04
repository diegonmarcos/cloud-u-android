package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.util.Log
import com.diegonmarcos.superapp.updater.AutoUpdatePrefs
import com.diegonmarcos.superapp.updater.Fleet
import org.json.JSONObject
import kotlin.concurrent.thread
import com.diegonmarcos.superapp.updater.BuildConfig as AuConfig

/**
 * #857 UPDATES FIRST. On an unmetered network with Auto-update ON, every
 * pending update is queued and downloaded (the [StoreAuto] chain, at the
 * highest priority) before the Store page does any other work. The rule —
 * whether it applies, what runs first, what waits, how long — is declared in
 * assets/appstore-priority.json; this object only evaluates it.
 */
object StorePriority {
    private const val TAG = "StorePriority"
    const val ASSET = "appstore-priority.json"
    const val HIGHEST = "highest"

    class Decl(
        val enabled: Boolean,
        val priority: String,
        val needAuto: Boolean,
        val network: String,
        val first: String,
        val after: List<String>,
        val maxWaitMs: Long,
    )

    /** What applies when the asset is missing or unreadable: updates first. */
    val DEFAULT = Decl(true, HIGHEST, true, "unmetered", "download_all_updates",
        listOf("catalogue_refresh", "icons", "details"), 900_000L)

    fun parse(o: JSONObject): Decl {
        val u = o.getJSONObject("updates_first")
        val w = u.optJSONObject("when") ?: JSONObject()
        val after = u.optJSONArray("after")
        return Decl(
            enabled = u.optBoolean("enabled", true),
            priority = u.optString("priority", HIGHEST),
            needAuto = w.optBoolean("auto_update", true),
            network = w.optString("network", "unmetered"),
            first = u.optString("first", "download_all_updates"),
            after = if (after == null) DEFAULT.after else List(after.length()) { after.getString(it) },
            maxWaitMs = u.optLong("max_wait_s", 900L) * 1000L,
        )
    }

    fun load(ctx: Context): Decl = runCatching {
        parse(JSONObject(ctx.assets.open(ASSET).use { it.readBytes().decodeToString() }))
    }.getOrElse { DEFAULT }

    /** True when pending updates must run before any other Store work. Pure. */
    fun updatesFirst(d: Decl, autoOn: Boolean, unmetered: Boolean): Boolean =
        d.enabled && d.priority == HIGHEST && (autoOn || !d.needAuto) &&
            (unmetered || d.network != "unmetered")

    /** The order the Store page runs its work in. Pure. */
    fun plan(d: Decl, autoOn: Boolean, unmetered: Boolean): List<String> =
        if (updatesFirst(d, autoOn, unmetered)) listOf(d.first) + d.after else d.after

    fun autoOn(ctx: Context): Boolean = AuConfig.AUTO_UPDATE_ENABLED && AutoUpdatePrefs.enabled(ctx)

    fun unmetered(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)!!
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)!!
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    /**
     * Run the Store page's own work ([then], on a background thread) AFTER the
     * updates when [updatesFirst] holds: the auto chain is started at once at
     * max thread priority, and [then] waits until its download phase is over
     * (or [Decl.maxWaitMs]). Otherwise [then] runs straight away. Returns true
     * when the updates went first.
     */
    fun runUpdatesFirst(ctx: Context, fleet: List<Fleet.App>, then: () -> Unit): Boolean {
        val app = ctx.applicationContext
        val d = load(app)
        if (!updatesFirst(d, autoOn(app), unmetered(app))) { then(); return false }
        Log.i(TAG, "Wi-Fi + Auto-update: downloading every pending update before ${d.after}")
        val chain = thread(name = "store-updates-first", priority = Thread.MAX_PRIORITY) {
            runCatching { StoreAuto.run(app, fleet, StoreAuto.TRIGGER_STORE_REFRESH) }
                .onFailure { Log.w(TAG, "updates-first chain failed: ${it.message}") }
        }
        thread(name = "store-after-updates") {
            val until = System.currentTimeMillis() + d.maxWaitMs
            while (System.currentTimeMillis() < until) {
                val running = StoreAuto.isRunning()
                // Wait while the chain is still starting, or refreshing / downloading.
                if ((running && downloading(app)) || (!running && chain.isAlive)) Thread.sleep(250) else break
            }
            then()
        }
        return true
    }

    /** The chain is still refreshing its queue or downloading it. */
    private fun downloading(ctx: Context): Boolean =
        StoreAuto.phaseNow(ctx).let { it == StoreAuto.REFRESH || it == StoreAuto.DOWNLOAD }
}
