package app.sterna.ui.assigned

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Base64
import app.sterna.BuildConfig
import app.sterna.core.data.tasks.MailTask
import app.sterna.core.data.tasks.decodeTaskList
import app.sterna.core.data.tasks.encodeTaskList
import app.sterna.core.data.tasks.toTodoJson
import app.sterna.core.data.tasks.upsertTask
import com.diegonmarcos.superapp.core.DataBackendClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mail's Assigned tasks. Kept in Mail's own `mail_tasks` file (what the Assigned list reads, so it works
 * with or without the engine) and MIRRORED into the fleet's task store, the cal engine Cloud Tasks
 * binds, so every task also shows under Tasks.
 */
object MailTasks {
    private const val FILE = "mail_tasks"
    private const val KEY = "tasks"

    private val _tasks = MutableStateFlow<List<MailTask>>(emptyList())
    val tasks: StateFlow<List<MailTask>> = _tasks.asStateFlow()
    @Volatile private var loaded = false
    @Volatile private var client: DataBackendClient? = null

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            _tasks.value = decodeTaskList(prefs.getString(KEY, null))
            loaded = true
        }
    }

    private fun persist(context: Context, list: List<MailTask>) {
        _tasks.value = list
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, encodeTaskList(list)).apply()
    }

    /**
     * Save [task] here, then into the task store. Returns whether the store took it: false means it is
     * saved in Mail only (the engine is not installed or did not answer), never that it was lost.
     */
    suspend fun save(context: Context, task: MailTask): Boolean {
        load(context)
        val engine = withContext(Dispatchers.IO) { engine(context) }
        val placed = if (task.collectionId.isBlank() && engine != null) {
            task.copy(collectionId = withContext(Dispatchers.IO) { firstProject(engine) } ?: "")
        } else {
            task
        }
        persist(context, upsertTask(_tasks.value, placed))
        engine ?: return false
        val answer = withContext(Dispatchers.IO) { ask("saveTodo", engine, toTodoJson(placed), "") }
        return !answer.contains("\"error\"")
    }

    suspend fun remove(context: Context, task: MailTask) {
        load(context)
        persist(context, _tasks.value.filterNot { it.uid == task.uid })
        val engine = withContext(Dispatchers.IO) { engine(context) } ?: return
        val id = Base64.encodeToString(task.collectionId.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE) + ":" + task.uid
        withContext(Dispatchers.IO) { ask("deleteTodo", engine, id, "") }
    }

    /** The first task-store project (Agenda's collection), where a new task goes. */
    private fun firstProject(engine: DataBackendClient): String? = runCatching {
        Json.parseToJsonElement(ask("projects", engine)).jsonArray.firstOrNull()
            ?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /** Every engine call goes through here: one door, held to the engine's method list by CI. */
    private fun ask(method: String, engine: DataBackendClient, vararg args: String): String = engine.call(method, *args)

    private const val CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"

    /**
     * The cal engine, bound; null when it is not installed or serves a contract below the one this
     * build needs. The same handshake as Cloud Tasks': resolve the declared action in the declared
     * package with its meta-data, refuse a contract below the floor, only then build the client.
     * Call off the main thread.
     */
    private fun engine(context: Context): DataBackendClient? {
        client?.let { return it }
        val ctx = context.applicationContext
        val service = ctx.packageManager
            .resolveService(Intent(BuildConfig.CAL_ENGINE_ACTION).setPackage(BuildConfig.CAL_ENGINE_PACKAGE), PackageManager.GET_META_DATA)
            ?.serviceInfo ?: return null
        val needed = BuildConfig.CAL_ENGINE_MIN_CONTRACT
        val found = service.metaData?.getInt(CONTRACT_KEY, 0) ?: 0
        if (found < needed) return null
        return DataBackendClient(ctx, service.packageName, service.name).also { client = it }
    }
}
