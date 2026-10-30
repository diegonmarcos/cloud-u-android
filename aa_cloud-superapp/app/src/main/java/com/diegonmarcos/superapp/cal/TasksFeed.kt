package com.diegonmarcos.superapp.cal

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.core.DataBackendClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Tasks popup's data: the cal ENGINE (Cloud-Lib-Cal.apk, build.json::engines.cal), the one Cloud Tasks and Cloud Me
 * already read. Agenda = its events (the subscribed calendars' cache), Todo = its VTODO mirror. This app compiles no
 * calendar code and keeps no store of its own; it asks, and draws what comes back.
 *
 * THE HANDSHAKE COMES FIRST AND COSTS NO BIND (the RemoteFeed shape): PackageManager resolves the declared action in the
 * declared package and reads its CONTRACT; missing and too old are two different sentences, thrown, so the popup says why
 * instead of drawing an empty table. Off the main thread only: the bind blocks.
 */
object TasksFeed : TasksSource {

    /** The engine CONTRACT meta-data key (libs/cal's manifest). */
    const val CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"

    // The engine's method names (CalBackendService). Strings, not an import: this app does not compile libs:cal.
    const val EVENTS = "events"
    const val TODOS = "todos"

    @Volatile private var client: DataBackendClient? = null
    @Volatile private var app: Context? = null

    /** Remember the application context the [TasksSource] calls need (the popup passes its own). */
    fun attach(ctx: Context) { app = ctx.applicationContext }

    /** Null when the engine is ready, else the sentence that says what to do. */
    fun check(ctx: Context): String? {
        val pm = ctx.packageManager
        val pkg = BuildConfig.CAL_ENGINE_PACKAGE
        val needed = BuildConfig.CAL_ENGINE_MIN_CONTRACT
        val service = pm.resolveService(Intent(BuildConfig.CAL_ENGINE_ACTION).setPackage(pkg), PackageManager.GET_META_DATA)
            ?.serviceInfo
        if (service == null) {
            val installed = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
            return if (installed) tooOld(pkg, 0, needed) else "$pkg is not installed — install it from Store ▸ Cloud Constellation ▸ Libs"
        }
        val found = service.metaData?.getInt(CONTRACT_KEY, 0) ?: 0
        if (found < needed) return tooOld(pkg, found, needed)
        if (client == null) synchronized(this) {
            if (client == null) client = DataBackendClient(ctx.applicationContext, service.packageName, service.name)
        }
        return null
    }

    override fun events(fromMs: Long, toMs: Long): List<TaskEvent> =
        parseEvents(ask(EVENTS, fromMs.toString(), toMs.toString()), EVENTS)

    override fun todos(): List<TaskTodo> = parseTodos(ask(TODOS, ""), TODOS)

    /** The engine's `events` answer: an array of {title, start, end, allDay, ...}; a failure ({"error": …}) is thrown. */
    fun parseEvents(text: String, method: String = EVENTS): List<TaskEvent> {
        val arr = rows(text, method)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            TaskEvent(o.optString("title"), o.optLong("start"), o.optLong("end"), o.optBoolean("allDay", false))
        }
    }

    /** The engine's `todos` answer: {summary, status, due ("" or millis), ...}. */
    fun parseTodos(text: String, method: String = TODOS): List<TaskTodo> {
        val arr = rows(text, method)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            TaskTodo(o.optString("summary"), o.optString("status"), o.optString("due").toLongOrNull())
        }
    }

    private fun rows(text: String, method: String): JSONArray =
        runCatching { JSONArray(text) }.getOrElse {
            val error = runCatching { JSONObject(text).optString("error") }.getOrNull()
            throw IllegalStateException(error?.takeIf { it.isNotBlank() } ?: "the cal engine answered $method with something that is not a list")
        }

    private fun ask(method: String, vararg args: String): String {
        val ctx = app ?: throw IllegalStateException("the cal engine was asked before any context reached it")
        val why = check(ctx)
        val c = client
        if (why != null || c == null) throw IllegalStateException(why ?: "the cal engine is not ready")
        return c.call(method, *args)
    }

    private fun tooOld(pkg: String, found: Int, needed: Int) =
        "$pkg serves contract $found, this build needs $needed — update it from Store ▸ Cloud Constellation ▸ Libs"
}
