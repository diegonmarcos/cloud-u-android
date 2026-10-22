package com.diegonmarcos.superapp.notificationcenter

import com.diegonmarcos.superapp.boards.HealthAgg
import com.diegonmarcos.superapp.boards.HealthBoard
import com.diegonmarcos.superapp.boards.Read
import com.diegonmarcos.superapp.boards.Why
import com.diegonmarcos.superapp.health.HealthBoardReader
import org.json.JSONObject

/**
 * The MyHealth card's face, as pure text. It is built from the SAME read the Boards > MyHealth page uses
 * (`readHealthBoard` = HealthBoardReader + HealthAgg), so the two can not disagree. A missing grant or a failed read
 * says why; it never reads as 0 and it never stays "reading" - a read that does not answer in time is [State.TIMEOUT].
 */
object HealthBadgeModel {
    enum class State { DATA, NO_PERMISSION, ERROR, TIMEOUT }

    /** [title] is the headline (km with the step count), [text] the second line, [lines] the long-press detail. */
    data class Face(val state: State, val title: String, val text: String, val lines: List<String>, val grant: Boolean) {
        fun toJson(): String = JSONObject().put("state", state.name).put("title", title).put("text", text)
            .put("lines", org.json.JSONArray(lines)).put("grant", grant).toString()
    }

    fun faceFromJson(s: String?): Face? = runCatching {
        val o = JSONObject(s ?: return null)
        Face(State.valueOf(o.getString("state")), o.getString("title"), o.getString("text"),
            o.getJSONArray("lines").let { a -> (0 until a.length()).map { a.getString(it) } }, o.getBoolean("grant"))
    }.getOrNull()

    const val TITLE = "MyHealth"
    const val TIMEOUT_S = 20

    private fun permName(p: String) = when (p) {
        HealthBoardReader.STEPS -> "steps"; HealthBoardReader.DISTANCE -> "distance"
        HealthBoardReader.RESTING_HR -> "resting heart rate"; else -> "sleep"
    }

    /** [read] null = the read did not answer within [TIMEOUT_S] seconds. */
    fun face(read: Read<HealthBoard>?): Face = when (read) {
        null -> Face(State.TIMEOUT, TITLE, "Health Connect did not answer in $TIMEOUT_S s",
            listOf("The read timed out. It is retried on the next refresh; open Health Connect to check it is running."), false)
        is Read.Unavailable -> Face(if (read.why == Why.NO_PERMISSION) State.NO_PERMISSION else State.ERROR, TITLE,
            read.detail, emptyList(), read.why == Why.NO_PERMISSION)
        is Read.Ok -> ok(read.value)
    }

    private fun ok(b: HealthBoard): Face {
        val missing = b.missing.map(::permName)
        if (b.status == HealthBoardReader.Status.NEEDS_PERMISSION)
            return Face(State.NO_PERMISSION, TITLE, "Needs read access in Health Connect: " + missing.joinToString(", "),
                emptyList(), true)
        val t = b.steps.today
        val rhr = HealthAgg.bpmText(b.rhr.today).let { if (it == "--") it else "$it bpm" }
        val sleep = HealthAgg.hm(b.sleep.lastNight?.asleepMin?.toDouble())
        val detail = buildList {
            if (missing.isNotEmpty()) add("Not allowed: " + missing.joinToString(", "))
            b.error?.let { add("Partial read: $it") }
        }
        return Face(State.DATA, HealthAgg.kmStepsText(t.km, t.steps, t.estimated), "Resting HR $rhr · Sleep $sleep",
            detail, missing.isNotEmpty())
    }

    /** "updated 4m ago" for a cached face. */
    fun updated(ageMs: Long): String {
        val m = ageMs / 60_000L
        return "updated " + when { m < 1 -> "just now"; m < 60 -> "${m}m ago"; m < 1_440 -> "${m / 60}h ago"; else -> "${m / 1_440}d ago" }
    }
}
