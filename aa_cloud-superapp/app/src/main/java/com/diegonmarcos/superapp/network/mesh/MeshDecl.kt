package com.diegonmarcos.superapp.network.mesh

import android.util.Base64
import com.diegonmarcos.superapp.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * #877 `build.json::ui.mesh_page`, parsed: the Cloud Mesh page's tabs, each tab's controls with
 * their defaults, and the numbers the live readout uses. Nothing about the page's shape is written
 * in Kotlin: the strip draws [pages], a tab draws its [Page.controls], and test-mesh-page.sh holds
 * this declaration equal to the code in both directions (a declared page with no composable, a
 * composable with no declaration, a control naming an engine call that does not exist, a control
 * the engine cannot honour that is not shown disabled with its reason).
 *
 * [parse] is pure; [baked] is the declaration THIS build shipped (BuildConfig.UI_MESH_PAGE_B64).
 */
object MeshDecl {

    /** switch | choice | number | text | secret | list | action. */
    data class Control(
        val id: String,
        val kind: String,
        /** The heading this control sits under on its page; blank = none. */
        val group: String,
        val label: String,
        /** The declared default, as text; "" when none ([defaultFrom] names where it lives instead). */
        val default: String,
        val defaultFrom: String,
        val choices: List<String>,
        val choicesFrom: String,
        val min: Long?,
        val max: Long?,
        /** The engine call this control dispatches to (a [MeshActions] id); "" when it has none. */
        val engine: String,
        /** Non-blank: the engine cannot honour this control; the page shows it disabled with this reason. */
        val unsupported: String,
    ) {
        val honoured: Boolean get() = unsupported.isBlank()
    }

    data class Page(
        val id: String,
        val label: String,
        val icon: String,
        /** The ordered readout rows a Status-style page draws; empty for the others. */
        val rows: List<String>,
        val controls: List<Control>,
    ) {
        fun control(id: String): Control? = controls.firstOrNull { it.id == id }
    }

    data class Decl(
        val defaultPage: String,
        val pollMs: Long,
        val latencyEveryTicks: Int,
        val latencyTimeoutMs: Int,
        val logCap: Int,
        val handshakeFreshS: Long,
        val handshakeStaleS: Long,
        val nat64Marker: String,
        val pages: List<Page>,
    ) {
        fun page(id: String?): Page? = pages.firstOrNull { it.id == id }
        fun control(id: String): Control? = pages.firstNotNullOfOrNull { it.control(id) }
        /** The page the strip opens on: the declared default, else the first. */
        val startPage: Page get() = page(defaultPage) ?: pages.first()
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }

    fun parse(json: String): Decl {
        val o = JSONObject(json)
        val hs = o.optJSONObject("handshake") ?: JSONObject()
        val ps = o.getJSONArray("pages")
        return Decl(
            defaultPage = o.optString("default_page"),
            pollMs = o.optLong("poll_ms", 1000L).coerceAtLeast(250L),
            latencyEveryTicks = o.optInt("latency_every_ticks", 5).coerceAtLeast(1),
            latencyTimeoutMs = o.optInt("latency_timeout_ms", 1200).coerceAtLeast(100),
            logCap = o.optInt("log_cap", 200).coerceAtLeast(10),
            handshakeFreshS = hs.optLong("fresh_s", 150L),
            handshakeStaleS = hs.optLong("stale_s", 300L),
            nat64Marker = o.optString("nat64_marker"),
            pages = (0 until ps.length()).map { i ->
                val p = ps.getJSONObject(i)
                val cs = p.optJSONArray("controls") ?: JSONArray()
                Page(
                    id = p.getString("id"),
                    label = p.optString("label", p.getString("id")),
                    icon = p.optString("icon"),
                    rows = p.optJSONArray("rows").strings(),
                    controls = (0 until cs.length()).map { j ->
                        val c = cs.getJSONObject(j)
                        Control(
                            id = c.getString("id"),
                            kind = c.getString("kind"),
                            group = c.optString("group"),
                            label = c.optString("label", c.getString("id")),
                            default = if (c.has("default")) c.get("default").toString() else "",
                            defaultFrom = c.optString("default_from"),
                            choices = c.optJSONArray("choices").strings(),
                            choicesFrom = c.optString("choices_from"),
                            min = if (c.has("min")) c.getLong("min") else null,
                            max = if (c.has("max")) c.getLong("max") else null,
                            engine = c.optString("engine"),
                            unsupported = c.optString("unsupported"),
                        )
                    },
                )
            },
        )
    }

    /** The seeds `default_from` names: ui.wireguard_default's fields (baked) and ui.dns's default preset. */
    fun seeds(): Map<String, String> = mapOf(
        "interface_mtu" to BuildConfig.UI_WG_INTERFACE_MTU,
        "tunnel_name" to BuildConfig.UI_WG_TUNNEL_NAME,
        "interface_address" to BuildConfig.UI_WG_INTERFACE_ADDRESS,
        "dns.default_preset" to com.diegonmarcos.superapp.network.FleetDns.decl.defaultPreset,
    )

    /** The declaration this build shipped. */
    val baked: Decl by lazy { parse(String(Base64.decode(BuildConfig.UI_MESH_PAGE_B64, Base64.DEFAULT))) }

    /**
     * A control's default, resolved: its own `default`, else the seed `default_from` names in
     * [seeds] (ui.wireguard_default's fields, plus `dns.default_preset`). "" when neither exists.
     */
    fun defaultOf(c: Control, seeds: Map<String, String>): String =
        if (c.defaultFrom.isNotBlank()) seeds[c.defaultFrom].orEmpty() else c.default
}
