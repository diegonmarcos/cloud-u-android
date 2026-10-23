package com.diegonmarcos.cloudnav.maps

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Maps → Stops page. Flat reverse-chronological list of Stops — no day
 * grouping (that's the Timeline/Daily page). Each row shows full timestamp +
 * lat/lon (debug-grade detail) + the resolved address line.
 *
 * All-time by default; when opened via a Daily row's tap (see
 * [MapsTimelineTabsFragment.openStopsForDay]) it's scoped to just that one
 * calendar day instead ([ARG_DAY_MS]).
 *
 * LazyColumn-backed — the full 1987-1992 demo trip is several thousand
 * Stops; only the rows on screen are composed.
 */
class MapsStopsFragment : KitComposeFragment() {

    private val dayFilterMs: Long? get() = arguments?.getLong(ARG_DAY_MS, -1L)?.takeIf { it >= 0L }

    override fun palette(): KitPalette = MapsInk.palette()

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val p = LocalKitPalette.current
        val dayMs = dayFilterMs
        val (fromMs, toMs) = if (dayMs != null) dayMs to (dayMs + 24L * 3600_000L - 1L)
                             else 0L to System.currentTimeMillis()
        // All-time when unfiltered — see MapsDailyFragment's comment: a
        // "last N years" bound would hide the deliberately historical
        // (1987-1992) demo dataset.
        val stops = remember(dayMs) { TimelineViews.richStops(TimelineSync.view(ctx, fromMs, toMs)).filter { it.startedAt in fromMs..toMs }.sortedByDescending { it.startedAt } }
        val dayFmt = remember { SimpleDateFormat("EEE, dd MMM yyyy", Locale.US) }

        Column(Modifier.fillMaxSize().background(p.surface)) {
            Text(
                if (dayMs != null) "Stops on ${dayFmt.format(Date(dayMs))} (${stops.size})" else "Stops (${stops.size})",
                color = p.textPrimary, style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 12.dp),
            )
            if (stops.isEmpty()) {
                Text("No Stops collected yet.", color = p.textSecondary, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("maps:stops:list"),
                ) {
                    items(stops, key = { it.id }) { stop -> StopRow(stop) { openDayMap(it) } }
                }
            }
        }
    }

    private fun openDayMap(dayMs: Long) {
        requireActivity().supportFragmentManager.beginTransaction()
            .add(android.R.id.content, MapsDayMapFragment.newInstance(dayMs), "day-map")
            .addToBackStack("day-map")
            .commit()
    }

    companion object {
        // DARK palette (shared across all Maps/Timeline/Configs surfaces).
        // Matches app theme colors.xml + the cockpit HUD. The nav app's own screens still read these.
        const val COL_SURFACE   = MapsInk.SURFACE
        const val COL_TILE      = 0x14FFFFFF           // ~8% white — subtle raised card
        const val COL_PRIMARY   = MapsInk.PRIMARY
        const val COL_SECONDARY = MapsInk.SECONDARY
        const val COL_ACCENT    = MapsInk.ACCENT
        private const val ARG_DAY_MS = "day_ms"

        /** [dayMs] non-null → scope to just that calendar day (local midnight,
         *  see [MapsDailyFragment.localMidnight]); null → all-time (default). */
        fun newInstance(dayMs: Long? = null) = MapsStopsFragment().apply {
            arguments = Bundle().apply { if (dayMs != null) putLong(ARG_DAY_MS, dayMs) }
        }
    }
}

/** What one Stops row says: the time, the coordinates, and the place line. */
data class StopRowText(val ts: String, val coords: String, val place: String)

private val stopTsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

/** Pure: the three lines of a Stops row for [stop] (an unresolved place reads "Resolving…"). */
fun stopRowText(stop: MapsDb.RichStop): StopRowText = StopRowText(
    ts = synchronized(stopTsFmt) { stopTsFmt.format(Date(stop.startedAt)) },
    coords = "%.5f, %.5f".format(stop.lat, stop.lon),
    place = (stop.placeName ?: "Resolving…") + "   ·  tap for this day's map ▸",
)

/** One row of the Stops list — a tap hands the stop's local calendar-day midnight to [onTap]. */
@Composable
private fun StopRow(stop: MapsDb.RichStop, onTap: (Long) -> Unit) {
    val p = LocalKitPalette.current
    val text = stopRowText(stop)
    MapsTile(Modifier.testTag("maps:stops:row:${stop.id}"),
        onClick = { onTap(MapsDailyFragment.localMidnight(stop.startedAt)) }) {
        Text(text.ts, color = p.accent, style = MaterialTheme.typography.titleMedium)
        Text(text.coords, color = p.textSecondary, style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace)
        Text(text.place, color = p.textPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}
