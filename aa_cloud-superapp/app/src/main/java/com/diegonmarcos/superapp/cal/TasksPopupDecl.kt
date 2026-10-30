package com.diegonmarcos.superapp.cal

import android.util.Base64
import com.diegonmarcos.superapp.BuildConfig
import org.json.JSONObject

/** The Tasks popup as DATA (build.json::tasks_popup): its name, its default view, its months and every size. */
data class TasksPopupDeclaration(
    val label: String,
    val defaultView: String,
    val views: List<Pair<String, String>>,       // id to caption
    val monthsBefore: Int, val monthsAfter: Int, val monthCount: Int,
    val widthDp: Int, val maxHeightDp: Int, val monthCellDp: Int, val tableHeightDp: Int, val tableHeightAgendaViewDp: Int,
    val agendaLabel: String, val agendaWindowDays: Int, val agendaMaxRows: Int,
    val todoLabel: String, val todoMaxRows: Int, val todoComplete: Boolean,
    val appOnRowTap: String,
)

object TasksPopupDecl {
    const val CALENDAR = "calendar"; const val AGENDA = "agenda"

    fun parse(json: String): TasksPopupDeclaration {
        val o = JSONObject(json)
        val m = o.getJSONObject("months")
        val before = m.getInt("before"); val after = m.getInt("after"); val count = m.getInt("count")
        require(count == before + 1 + after) { "tasks_popup.months: count $count != before $before + 1 + after $after" }
        val vs = o.getJSONArray("views")
        val views = (0 until vs.length()).map { vs.getJSONObject(it).let { v -> v.getString("id") to v.getString("label") } }
        val def = o.getString("default_view")
        require(views.any { it.first == def }) { "tasks_popup.default_view '$def' is not one of the views" }
        val l = o.getJSONObject("layout")
        val t = o.getJSONObject("tables"); val a = t.getJSONObject("agenda"); val d = t.getJSONObject("todo")
        return TasksPopupDeclaration(
            label = o.getString("label"), defaultView = def, views = views,
            monthsBefore = before, monthsAfter = after, monthCount = count,
            widthDp = l.getInt("width_dp"), maxHeightDp = l.getInt("max_height_dp"), monthCellDp = l.getInt("month_cell_dp"),
            tableHeightDp = l.getInt("table_height_dp"), tableHeightAgendaViewDp = l.getInt("table_height_agenda_view_dp"),
            agendaLabel = a.getString("label"), agendaWindowDays = a.getInt("window_days"), agendaMaxRows = a.getInt("max_rows"),
            todoLabel = d.getString("label"), todoMaxRows = d.getInt("max_rows"), todoComplete = d.getBoolean("complete"),
            appOnRowTap = o.optString("opens_app_on_row_tap", ""),
        )
    }

    /** The declaration baked into the build. */
    val declared: TasksPopupDeclaration by lazy {
        parse(String(Base64.decode(BuildConfig.TASKS_POPUP_B64, Base64.DEFAULT), Charsets.UTF_8))
    }
}
