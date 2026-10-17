package com.diegonmarcos.cloudnav.tracker

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.diegonmarcos.superapp.devtools.AppDebugServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * The tracker's data for the fleet: `tracker/steps` and `tracker/places` on the fleet IPC door
 * (<pkg>.fleet, FleetTokenProvider — signature permission CONSTELLATION_DATA, so only a constellation-signed app such
 * as the SuperApp's Boards ▸ My-PM can ask). Read-only: both ops read the stores the Steps page reads
 * ([StepsRepository]: Health Connect or the engine's own samples; the engine's fixes plus the foreground tracker's GPS
 * points) and change nothing. Registered from a provider's onCreate, the ImageDebugApiProvider way, so the route exists
 * before Application code runs. Not a real provider and not exported.
 */
class TrackerDebugApiProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return false
        runCatching {
            AppDebugServer.route(GROUP, listOf(
                AppDebugServer.Op("steps", "days=<1..31, default 7>", "steps today and per day (Health Connect, else this phone's counter) with the source status the Steps page shows"),
                AppDebugServer.Op("places", "days=<1..31, default 7>", "the places the tracker recorded, per day: stays (centroid, time span, label)"),
            )) { op, q ->
                val days = (q["days"]?.toIntOrNull() ?: 7).coerceIn(1, 31)
                when (op) {
                    "steps" -> steps(app, days).toString()
                    "places" -> places(app, days).toString()
                    else -> null
                }
            }
        }
        return true
    }

    companion object {
        const val GROUP = "tracker"

        fun steps(ctx: Context, days: Int, zone: ZoneId = ZoneId.systemDefault()): JSONObject {
            val ui = runBlocking { StepsRepository(ctx).load(zone) }
            val arr = JSONArray()
            for (i in days - 1 downTo 0) {
                val d = ui.today.minusDays(i.toLong())
                arr.put(JSONObject().put("date", d.toString()).put("steps", ui.daily[d] ?: 0L))
            }
            return JSONObject()
                .put("today", ui.daily[ui.today] ?: ui.totals.day)
                .put("days", arr)
                .put("source", ui.source.name)
                .put("status", ui.status.name)
                .put("status_line", ui.diag.statusLine)
                .put("hint", ui.diag.hint ?: "")
        }

        fun places(ctx: Context, days: Int, zone: ZoneId = ZoneId.systemDefault()): JSONObject {
            val repo = StepsRepository(ctx)
            val today = LocalDate.now(zone)
            val from = today.minusDays(days - 1L).atStartOfDay(zone).toInstant().toEpochMilli()
            val fixes = repo.fixes(from, System.currentTimeMillis())
            val out = JSONArray()
            for ((d, stays) in PlacesDigest.byDay(fixes, zone, today, days)) {
                val v = JSONArray()
                for (s in stays) {
                    v.put(JSONObject()
                        .put("part", PlacesDigest.span(s, zone))
                        .put("label", repo.name(PlaceFix(s.startMs, s.lat, s.lon, s.predicted)))
                        .put("lat", s.lat).put("lon", s.lon)
                        .put("predicted", s.predicted))
                }
                out.put(JSONObject().put("date", d.toString()).put("visits", v))
            }
            return JSONObject().put("days", out).put("fixes", fixes.size)
        }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
