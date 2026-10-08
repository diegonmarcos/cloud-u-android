package com.diegonmarcos.cloudsearch.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * #913 the Cloud Search section (build.json::search.cloud): Apps, Messages and Code. Apps searches the fleet's own
 * manifest, Messages asks Cloud Mail through the read-only agent door, Code opens Cloud Code and searches the owner's
 * repositories on GitHub in Cloud Browser. Nothing here fetches or writes.
 */
data class CloudConfig(val messagesLimit: Int, val messagesLookbackDays: Int, val codeOpenFleet: String, val codeOwner: String, val codeSearchUrl: String) {
    data class FleetApp(val id: String, val label: String, val pkg: String)

    /** The fleet apps whose id or label contains [q] (case-insensitive); all of them, label-sorted, for a blank query. */
    fun apps(all: List<FleetApp>, q: String): List<FleetApp> {
        val t = q.trim().lowercase()
        return all.filter { t.isEmpty() || it.id.lowercase().contains(t) || it.label.lowercase().contains(t) }.sortedBy { it.label.lowercase() }
    }

    /** The GitHub code search of the owner's repositories for [q], or null when there is nothing to search for. */
    fun codeUrl(q: String): String? {
        val t = q.trim()
        if (t.isEmpty()) return null
        return codeSearchUrl.replace("{q}", enc(t)).replace("{owner}", enc(codeOwner))
    }

    /** Messages since this many days ago. */
    fun messagesSince(nowMs: Long): Long = nowMs - messagesLookbackDays * 86_400_000L

    companion object {
        fun parse(o: JSONObject): CloudConfig {
            val m = o.getJSONObject("messages")
            val c = o.getJSONObject("code")
            val cfg = CloudConfig(m.getInt("limit"), m.getInt("lookback_days"), c.getString("open_fleet"), c.getString("owner"), c.getString("search_url"))
            require(cfg.messagesLimit in 1..200) { "search.cloud.messages.limit must be 1..200" }
            require(cfg.messagesLookbackDays >= 1) { "search.cloud.messages.lookback_days must be at least 1" }
            require(cfg.codeSearchUrl.startsWith("https://") && cfg.codeSearchUrl.contains("{q}")) { "search.cloud.code.search_url must be https and carry {q}" }
            return cfg
        }

        /** The baked fleet rows (id, label, package), in the order given. */
        fun fleetApps(json: String): List<FleetApp> = runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map { a.getJSONObject(it) }.map { FleetApp(it.getString("id"), it.getString("label"), it.getString("package")) }
        }.getOrDefault(emptyList())

        private fun enc(s: String) = Templates.enc(s)
    }
}
