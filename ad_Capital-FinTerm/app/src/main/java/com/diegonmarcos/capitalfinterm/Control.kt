package com.diegonmarcos.capitalfinterm

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

enum class CState { RUNNING, STOPPED, UNHEALTHY, UNKNOWN }

/** One action of the container control (data/control.json): its id, label, whether it asks first, and the states it is available in. */
data class ControlAction(val id: String, val label: String, val confirm: Boolean, val enabledWhen: Set<String>)

/** The container control declaration (data/control.json). */
data class ControlDecl(
    val vm: String,
    val name: String,
    val label: String,
    val source: String,
    val fleetEndpoint: String,
    val healthPath: String,
    val statusPath: String,
    val actionPath: String,
    val runningPrefix: String,
    val unhealthyMarker: String,
    val actions: List<ControlAction>,
)

/** Start / Stop / Restart of a container through c3-infra-api over the private mesh only. Pure apart from the [Http] it is given. */
object Control {
    fun parse(json: String): ControlDecl? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val c = o.optJSONObject("container") ?: return null
        val api = o.optJSONObject("api") ?: return null
        val st = o.optJSONObject("states") ?: return null
        val acts = o.optJSONArray("actions") ?: JSONArray()
        return ControlDecl(
            vm = Catalog.str(c, "vm"), name = Catalog.str(c, "name"), label = Catalog.str(c, "label"), source = Catalog.str(c, "source"),
            fleetEndpoint = Catalog.str(api, "fleet_endpoint"), healthPath = Catalog.str(api, "health_path"),
            statusPath = Catalog.str(api, "status_path"), actionPath = Catalog.str(api, "action_path"),
            runningPrefix = Catalog.str(st, "running_prefix"), unhealthyMarker = Catalog.str(st, "unhealthy_marker"),
            actions = (0 until acts.length()).mapNotNull { acts.optJSONObject(it) }.map { a ->
                val w = a.optJSONArray("enabled_when")
                ControlAction(Catalog.str(a, "id"), Catalog.str(a, "label"), a.optBoolean("confirm", true),
                    if (w == null) emptySet() else (0 until w.length()).map { w.optString(it) }.toSet())
            },
        )
    }

    /** The container's state from one status body: [{vm, containers:[{name, status}], error?}] (docker ps's status text). */
    fun state(d: ControlDecl, body: String): CState {
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return CState.UNKNOWN
        val entry = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { Catalog.str(it, "vm") == d.vm } ?: return CState.UNKNOWN
        if (Catalog.str(entry, "error").isNotBlank()) return CState.UNKNOWN
        val cs = entry.optJSONArray("containers") ?: return CState.UNKNOWN
        val me = (0 until cs.length()).mapNotNull { cs.optJSONObject(it) }.firstOrNull { Catalog.str(it, "name") == d.name } ?: return CState.UNKNOWN
        val text = Catalog.str(me, "status")
        return when {
            text.startsWith(d.runningPrefix) -> if (d.unhealthyMarker.isNotEmpty() && d.unhealthyMarker in text) CState.UNHEALTHY else CState.RUNNING
            text.startsWith("Restarting") -> CState.UNHEALTHY
            text.isBlank() -> CState.UNKNOWN
            else -> CState.STOPPED
        }
    }

    fun action(d: ControlDecl, id: String): ControlAction? = d.actions.firstOrNull { it.id == id }

    /** MESH ONLY: an action is available only on the mesh, and only in a state it is declared for. */
    fun enabled(d: ControlDecl, actionId: String, state: CState, onMesh: Boolean): Boolean {
        if (!onMesh) return false
        val a = action(d, actionId) ?: return false
        return state.name.lowercase() in a.enabledWhen
    }

    /** Whether the action asks before it runs (data/control.json: Stop and Restart do). An undeclared action always asks. */
    fun needsConfirm(d: ControlDecl, actionId: String): Boolean = action(d, actionId)?.confirm ?: true

    fun statusUrl(d: ControlDecl, base: String): String = Requests.url(base, d.statusPath, emptyList(), mapOf("vm" to d.vm))

    fun actionUrl(d: ControlDecl, base: String, actionId: String): String =
        Requests.url(base, d.actionPath, emptyList(), mapOf("vm" to d.vm, "name" to d.name, "action" to actionId))

    fun readState(d: ControlDecl, base: String, http: Http): CState = try {
        state(d, http.get(statusUrl(d, base)))
    } catch (e: IOException) {
        CState.UNKNOWN
    }

    /** Runs [actionId] and reads the state back; the call's failure is reported, never assumed done. */
    fun run(d: ControlDecl, base: String, http: Http, actionId: String): Pair<Boolean, CState> {
        val ok = try {
            http.post(actionUrl(d, base, actionId))
            true
        } catch (e: IOException) {
            false
        }
        return ok to readState(d, base, http)
    }
}
