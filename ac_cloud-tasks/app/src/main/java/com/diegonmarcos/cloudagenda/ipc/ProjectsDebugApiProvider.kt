package com.diegonmarcos.cloudagenda.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.diegonmarcos.cloudagenda.data.ProjectSummaries
import com.diegonmarcos.cloudagenda.data.TasksDb
import com.diegonmarcos.cloudagenda.data.TasksRepository
import com.diegonmarcos.superapp.devtools.AppDebugServer
import org.json.JSONArray
import org.json.JSONObject

/**
 * `projects/list` on the fleet IPC door (<pkg>.fleet, FleetTokenProvider — signature permission CONSTELLATION_DATA):
 * every project declared in this app with its open / done counts and next due task, for the SuperApp's Boards ▸ Tasks.
 * Read-only, straight from the app's own database. Registered from a provider's onCreate so the route exists before
 * any Activity; not a real provider and not exported.
 */
class ProjectsDebugApiProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return false
        runCatching {
            AppDebugServer.route("projects", listOf(
                AppDebugServer.Op("list", "", "every project: id, name, open, done, next_due_name, next_due (yyyy-MM-dd)"),
            )) { op, _ ->
                when (op) {
                    "", "list" -> list(TasksRepository(TasksDb.shared(app))).toString()
                    else -> null
                }
            }
        }
        return true
    }

    companion object {
        fun list(repo: TasksRepository): JSONObject {
            val arr = JSONArray()
            for (p in ProjectSummaries.of(repo.snapshot())) {
                arr.put(JSONObject().put("id", p.id).put("name", p.name).put("open", p.open).put("done", p.done)
                    .put("next_due_name", p.nextDueName ?: "").put("next_due", p.nextDue ?: ""))
            }
            return JSONObject().put("projects", arr)
        }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
