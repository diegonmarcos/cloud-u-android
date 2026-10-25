package com.diegonmarcos.cloudsearch.core

import org.json.JSONArray

/** What a call to a fleet service came to: the first route that answered, or every route's reason for not answering. */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T, val via: String) : Outcome<T>
    data class Down(val reasons: List<String>) : Outcome<Nothing>
}

/**
 * One fleet service as the build bakes it from ac_cloud-account/build.json::fleet_endpoints (BuildConfig.FLEET_ENDPOINTS_B64):
 * the mesh base, the public base and the policy (mesh_first | mesh_only | public_only). No host is spelled in Kotlin. The fleet
 * bearer goes to the PUBLIC origin only, never to a mesh name (the declaration's own rule).
 */
data class FleetEndpoint(val id: String, val meshUrl: String, val publicUrl: String, val policy: String) {
    data class Route(val label: String, val base: String, val headers: Map<String, String>)

    /** The routes to try in order for [bearer] (null = no fleet sign-in), and why a declared one is skipped. */
    fun plan(bearer: String?): Pair<List<Route>, List<String>> {
        val routes = ArrayList<Route>()
        val skipped = ArrayList<String>()
        if (policy != POLICY_PUBLIC_ONLY) {
            if (meshUrl.isNotBlank()) routes.add(Route("mesh", meshUrl.trimEnd('/'), emptyMap())) else skipped.add("$id has no mesh address")
        }
        if (policy != POLICY_MESH_ONLY) {
            if (publicUrl.isBlank()) skipped.add("$id has no public address")
            else if (bearer == null) skipped.add("public route needs the fleet sign-in of Cloud Account")
            else routes.add(Route("public", publicUrl.trimEnd('/'), mapOf("Authorization" to "Bearer $bearer")))
        }
        return routes to skipped
    }

    /** [call] on each route in turn until one returns; a throw is that route's reason ("mesh: ..."). */
    fun <T> attempt(bearer: String?, call: (Route) -> T): Outcome<T> {
        val (routes, skipped) = plan(bearer)
        val reasons = ArrayList(skipped)
        for (r in routes) {
            try { return Outcome.Ok(call(r), r.label) } catch (e: Exception) { reasons.add(r.label + ": " + (e.message ?: e.javaClass.simpleName)) }
        }
        return Outcome.Down(reasons)
    }

    companion object {
        const val POLICY_MESH_ONLY = "mesh_only"
        const val POLICY_PUBLIC_ONLY = "public_only"

        /** The baked array [{id, mesh_url, public_url, policy}]; unreadable = none. */
        fun parse(json: String): Map<String, FleetEndpoint> = runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map { a.getJSONObject(it) }.associate { o ->
                o.getString("id") to FleetEndpoint(o.getString("id"), o.optString("mesh_url"), o.optString("public_url"), o.optString("policy", "mesh_first"))
            }
        }.getOrDefault(emptyMap())
    }
}
