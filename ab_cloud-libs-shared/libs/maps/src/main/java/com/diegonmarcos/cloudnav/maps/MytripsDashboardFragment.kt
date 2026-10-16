package com.diegonmarcos.cloudnav.maps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MyTrips → Dashboard page. Headline rollups from the local MyMaps
 * tracker DB. Read-only; refreshes on every onResume.
 *
 * Sections:
 *   • Headline counters — Stops, Cities, Countries, Days tracked.
 *   • Today      — list of cities visited today (de-duped).
 *   • This week  — same for last 7 days.
 *   • Last month — same for last 30 days.
 *   • Most-visited cities (all time) — top 10.
 *
 * Push 3.5 can add charts (chart.js / MPAndroidChart) — for now the
 * text-only dashboard exposes the data and lets the user feel the
 * shape of the dataset.
 */
class MytripsDashboardFragment : KitComposeFragment() {

    /** Bumped by onResume: the page reads the DB again whenever it comes back. */
    private var refresh by mutableIntStateOf(0)

    override fun palette(): KitPalette = MapsInk.palette()

    override fun onResume() {
        super.onResume()
        refresh++
    }

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        check(refresh >= 0)   // a read of the resume counter: a resume recomposes the whole page
        val now = System.currentTimeMillis()
        // All-time — see MapsDailyFragment's comment: a "last N years" bound
        // would hide the deliberately historical (1987-1992) demo dataset.
        val from30d = now - 30L * 24L * 3600L * 1000L
        val from7d = now - 7L * 24L * 3600L * 1000L
        val fromToday = startOfDayMs(now)

        val db = MapsDb.get(ctx)
        val all = db.stopsBetween(0L, now)
        // ── Headline counters (always rendered — useful even at 0
        //    because the user can SEE the wiring is live before any
        //    Stops accumulate) ──────────────────────────────────────
        val cities = all.mapNotNull { it.city }.toSet()
        val countries = all.mapNotNull { it.country }.toSet()
        val days = all.map { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it.startedAt)) }.toSet()

        MapsPage {
            MapsHeading("MyTrips · beta")
            Grid4(
                "Stops" to all.size.toString(),
                "Cities" to cities.size.toString(),
                "Countries" to countries.size.toString(),
                "Days" to days.size.toString(),
            )
            MapsCaption("All time")

            // ── Raw DB telemetry — proves the tracker → DB → dashboard
            //    wiring is live even when Stops detection hasn't yet
            //    triggered (≥ 5-min dwell required). ─
            Gap()
            MapsSubhead("Tracker · live telemetry")
            MapsKeyValue("GPS points logged", db.pointCount().toString())
            MapsKeyValue("Stops detected", db.stopCount().toString())
            MapsKeyValue("Stops with city", all.count { it.city != null }.toString())
            MapsKeyValue("Stops with country", all.count { it.country != null }.toString())

            // ── Detector state — explains WHY a Stop has / hasn't emitted
            //    in one panel. Reads MapsTrackerPrefs.last* telemetry that
            //    LocationTrackerService writes on every tick. ────────────
            Gap()
            MapsSubhead("Detector state")
            val tp = MapsTrackerPrefs(ctx)
            val cal = MapsTrackingPrefs(ctx)
            val decisionStr = tp.lastDecision.ifEmpty { "(no ticks yet)" }
            val decisionAge = if (tp.lastDecisionTs > 0) humanAge(now - tp.lastDecisionTs) else "—"
            MapsKeyValue("Last decision", "$decisionStr · $decisionAge")
            MapsKeyValue("Buffer fill", "${tp.lastBufFill} / ${tp.lastBufSize}")
            MapsKeyValue("Span (min) / dwell", "%.1f / %d".format(tp.lastSpanMin, cal.stopsDwellMin))
            MapsKeyValue("p90 dist / radius_eff", "%.0fm / %.0fm".format(tp.lastP90DistM, tp.lastRadiusEffM))
            MapsKeyValue("Avg accuracy", "%.0fm".format(tp.lastAvgAccM))
            MapsKeyValue("Active stop id", if (tp.activeStopId > 0) tp.activeStopId.toString() else "—")

            // ── Last 5 GPS fixes — shows fixes are landing even
            //    pre-Stop. Reverse-chronological. ───────────────────────
            Gap()
            MapsSubhead("Last 5 GPS fixes")
            val recent = db.recentPoints(5).asReversed()
            if (recent.isEmpty()) {
                MapsCaption("No fixes yet — start the tracker on MyMaps → Configs.")
            } else {
                val tsFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
                for (pt in recent) MapsKeyValue(tsFmt.format(Date(pt.ts)), "%.5f, %.5f".format(pt.lat, pt.lon))
            }

            // ── Today / Week / Month windows + top cities ───────────
            if (all.isNotEmpty()) {
                Section("Today", all.filter { it.startedAt >= fromToday })
                Section("Last 7 days", all.filter { it.startedAt >= from7d })
                Section("Last 30 days", all.filter { it.startedAt >= from30d })

                Gap()
                MapsSubhead("Most-visited cities — all time")
                val topCities = all.mapNotNull { it.city }
                    .groupingBy { it }.eachCount()
                    .entries.sortedByDescending { it.value }
                    .take(10)
                if (topCities.isEmpty()) {
                    MapsCaption("No cities resolved yet — enrichment runs as Stops accumulate.")
                } else {
                    for ((city, n) in topCities) MapsKeyValue(city, "$n stops")
                }
            } else {
                Gap()
                MapsCaption("Top cities + day-window rollups will populate once Stops start landing (≥ 5-min dwell).")
            }
        }
    }

    @Composable
    private fun Gap() { Spacer(Modifier.height(16.dp)) }

    /** Render a sub-section listing the de-duped city names in [stops]. */
    @Composable
    private fun Section(title: String, stops: List<MapsDb.RichStop>) {
        val p = LocalKitPalette.current
        Gap()
        MapsSubhead(title)
        if (stops.isEmpty()) {
            MapsCaption("—")
            return
        }
        // De-dupe cities, preserve order seen (DESC time).
        val cities = LinkedHashSet<String>()
        for (s in stops) s.city?.let { cities.add(it) }
        if (cities.isEmpty()) {
            MapsCaption("${stops.size} stops — cities still resolving")
            return
        }
        Text(cities.joinToString("  ·  "), color = p.textPrimary, style = MaterialTheme.typography.bodyLarge)
        MapsCaption("${stops.size} stops")
    }

    /** 2x2 grid of headline counter tiles. */
    @Composable
    private fun Grid4(vararg cells: Pair<String, String>) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in cells.toList().chunked(2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((k, v) in row) CounterTile(k, v, Modifier.weight(1f))
                }
            }
        }
    }

    @Composable
    private fun CounterTile(key: String, value: String, modifier: Modifier = Modifier) {
        val p = LocalKitPalette.current
        MapsTile(modifier) {
            Text(value, color = p.accent, style = MaterialTheme.typography.headlineSmall)
            Text(key, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }

    private fun startOfDayMs(nowMs: Long): Long {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    /** "23s ago" / "4m ago" / "1h ago" — used by detector telemetry. */
    private fun humanAge(deltaMs: Long): String {
        val s = (deltaMs / 1000L).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60}m ago"
            s < 86400 -> "${s / 3600}h ago"
            else -> "${s / 86400}d ago"
        }
    }

    companion object { fun newInstance() = MytripsDashboardFragment() }
}
