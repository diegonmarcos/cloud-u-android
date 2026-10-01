package com.diegonmarcos.cloudme

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.diegonmarcos.superapp.core.DataBackendClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * The calendar, REACHED, NOT CARRIED (engine-apk-split, move 3). Agenda used to compile
 * libs:cal and run CalEngine in this process, so every calendar change republished Cloud Me.
 * The engine now runs only in Cloud-Lib-Cal.apk (CalBackendService, installed by the Store) —
 * the same engine Cloud Agenda binds — and this is the thin client Agenda talks to instead.
 * Same shape as cloud-drive's GhEngine.
 *
 * THE HANDSHAKE COMES FIRST AND COSTS NO BIND: [check] asks PackageManager for the service
 * that answers the declared action in the declared package (build.json::engines.cal, both
 * resolved at build time from the fleet manifest) and reads the CONTRACT it declares. No
 * package → [Check.NotInstalled]; a package with no such service, or one declaring a contract
 * below what this build calls → [Check.TooOld]. Each is a different line on the tab, because
 * "install it" and "update it" are different next steps. Only a ready engine is bound.
 *
 * NO CREDENTIAL CROSSES: Agenda only reads (events, todos) and asks for the subscription
 * fetch, none of which takes a CalDAV config. Nothing here logs.
 *
 * BLOCKING: binding waits for the main thread to deliver the connection, so call everything,
 * [check] included, off the main thread.
 */
class CalEngineClient(context: Context) {

    private val ctx = context.applicationContext

    @Volatile private var client: DataBackendClient? = null

    /** What stands between this phone and the cal engine. */
    sealed class Check {
        object Ready : Check()
        /** The engine APK is not on the phone. */
        data class NotInstalled(val pkg: String) : Check()
        /** Installed, but its service declares [found] (0 = no engine service at all) and this build needs [needed]. */
        data class TooOld(val pkg: String, val found: Int, val needed: Int) : Check()
    }

    fun check(): Check {
        val pm = ctx.packageManager
        val pkg = BuildConfig.CAL_ENGINE_PACKAGE
        val needed = BuildConfig.CAL_ENGINE_MIN_CONTRACT
        val service = pm.resolveService(Intent(BuildConfig.CAL_ENGINE_ACTION).setPackage(pkg), PackageManager.GET_META_DATA)
            ?.serviceInfo
        if (service == null) {
            val installed = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
            return if (installed) Check.TooOld(pkg, 0, needed) else Check.NotInstalled(pkg)
        }
        val found = service.metaData?.getInt(CONTRACT_KEY, 0) ?: 0
        if (found < needed) return Check.TooOld(pkg, found, needed)
        if (client == null) synchronized(this) {
            if (client == null) client = DataBackendClient(ctx, service.packageName, service.name)
        }
        return Check.Ready
    }

    /** Events of the enabled subscriptions starting in [fromUtc, toUtc), from the engine's cache. */
    fun events(fromUtc: Long, toUtc: Long): JSONArray = rows(EVENTS, ask(EVENTS, fromUtc.toString(), toUtc.toString()))

    /** Every task in the engine's CalDAV VTODO mirror ("" = all collections). */
    fun todos(): JSONArray = rows(TODOS, ask(TODOS, ""))

    /** Fetch every subscription into the engine's cache. Blocking, networked. */
    fun sync(): String = ask(SYNC)

    private fun ask(method: String, vararg args: String): String {
        val why = check()
        val c = client
        if (why !is Check.Ready || c == null) throw IllegalStateException("the cal engine is not ready: $why")
        return c.call(method, *args)
    }

    /** The engine answers a list with an array and a failure with {"error": …}; the latter is thrown, never drawn as "empty". */
    private fun rows(method: String, text: String): JSONArray =
        runCatching { JSONArray(text) }.getOrElse {
            val error = runCatching { JSONObject(text).optString("error") }.getOrNull()
            throw IllegalStateException(error?.takeIf { it.isNotBlank() } ?: "the cal engine answered $method with something that is not a list")
        }

    companion object {
        /** The engine CONTRACT meta-data key every engine service declares (libs/cal's manifest). */
        const val CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"

        // The engine's method names (CalBackendService). Strings, not an import: this app does
        // not compile libs:cal, and that is the point.
        const val EVENTS = "events"
        const val TODOS = "todos"
        const val SYNC = "sync"
    }
}
