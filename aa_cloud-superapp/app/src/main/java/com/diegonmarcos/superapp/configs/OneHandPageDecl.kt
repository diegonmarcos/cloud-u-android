package com.diegonmarcos.superapp.configs

import android.util.Base64
import com.diegonmarcos.superapp.BuildConfig
import org.json.JSONObject

/**
 * The sections of Configs > Launcher > One-Hand, and so its index, as DATA (build.json::onehand_page). OneHandFragment draws the
 * index row and the section headings from this one list, in this order, so an anchor cannot point at a section the page lacks.
 */
object OneHandPageDecl {
    data class Section(val id: String, val label: String, val heading: String, val icon: String)

    const val STARS = "stars"; const val EDGE_MENU = "edge_menu"; const val FLOATING_MENU = "floating_menu"
    const val HOME_GESTURES = "home_gestures"; const val STATUS_BAR = "status_bar"

    fun parse(json: String): List<Section> {
        val arr = JSONObject(json).getJSONArray("sections")
        val out = (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { Section(it.getString("id"), it.getString("label"), it.getString("heading"), it.getString("icon")) }
        }
        require(out.map { it.id }.toSet().size == out.size) { "onehand_page: duplicate section id" }
        return out
    }

    val sections: List<Section> by lazy {
        parse(String(Base64.decode(BuildConfig.ONEHAND_PAGE_B64, Base64.DEFAULT), Charsets.UTF_8))
    }

    fun section(id: String): Section = sections.first { it.id == id }
}
