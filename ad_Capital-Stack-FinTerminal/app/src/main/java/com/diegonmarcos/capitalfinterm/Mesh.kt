package com.diegonmarcos.capitalfinterm

import org.json.JSONObject
import java.io.IOException

/** What a panel's preferred route is: the primary source, its mesh base address, the topic template and the peek path. */
data class PrimaryRoute(val source: Source, val base: String, val topic: String, val peekPath: String)

/** The private mesh: the fleet endpoints' base addresses baked at build time, and who is reachable now. */
object Mesh {
    /** FLEET_MESH_B64 decoded: fleet endpoint id to its mesh base address (empty map when it does not parse). */
    fun parse(json: String): Map<String, String> {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        val m = LinkedHashMap<String, String>()
        o.keys().forEach { m[it] = Catalog.str(o, it) }
        return m
    }

    fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()

    /** True when [healthPath] under [base] answers; any failure (the name does not resolve off the mesh, a timeout, an error) is false. */
    fun reachable(http: Http, base: String, healthPath: String): Boolean =
        base.isNotEmpty() && try {
            http.get(base.trimEnd('/') + healthPath)
            true
        } catch (e: IOException) {
            false
        }
}

/** The order of sources for a panel: the own-fleet primary first when it is declared, feeds the entry, outranks the panel's source and answers. */
object Priority {
    fun route(panel: Panel, dash: Dashboard, sources: List<Source>, mesh: Map<String, String>, reachable: (String) -> Boolean): PrimaryRoute? {
        val p = panel.fetch?.optJSONObject("primary") ?: return null
        val src = Catalog.byId(sources, Catalog.str(p, "source")) ?: return null
        val own = Catalog.byId(sources, panel.source) ?: return null
        if (!src.feeds(dash.tab, dash.entry) || src.priority >= own.priority) return null
        val base = mesh[src.fleetEndpoint].orEmpty()
        val peek = src.paths["peek"].orEmpty()
        if (base.isEmpty() || peek.isEmpty() || !reachable(src.paths["health"].orEmpty().let { base.trimEnd('/') + it })) return null
        return PrimaryRoute(src, base, Catalog.str(p, "topic"), peek)
    }
}
