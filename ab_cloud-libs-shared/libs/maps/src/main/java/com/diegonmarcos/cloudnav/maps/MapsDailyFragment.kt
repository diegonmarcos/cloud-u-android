package com.diegonmarcos.cloudnav.maps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** One row of the Daily list: the calendar day + its LONGEST-dwell place.
 *  Also [MapsExploredFragment]'s data source, grouped by city. Top-level
 *  (not nested in [MapsDailyFragment]) so both fragments share it plainly. */
data class DailyEntry(
    val dayMs: Long,
    val placeName: String?,
    val neighborhood: String?,
    val city: String?,
    val country: String?,
    val lat: Double,
    val lon: Double,
)

/** For each calendar day touched by any Stop, pick the Stop with the LONGEST
 *  overlap into that day. Reverse-chronological. Pure — no DB/Android
 *  dependency beyond the passed-in stops, so it's directly testable and
 *  reusable by both [MapsDailyFragment] and [MapsExploredFragment]. */
fun computeDailyLocations(stops: List<MapsDb.RichStop>): List<DailyEntry> {
    if (stops.isEmpty()) return emptyList()
    val now = System.currentTimeMillis()
    val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    data class Best(val anchorMs: Long, val stop: MapsDb.RichStop, val overlapMs: Long)
    val perDay = mutableMapOf<String, Best>()
    val cal = Calendar.getInstance()
    for (stop in stops) {
        val s = stop.startedAt
        val e = stop.endedAt ?: now
        if (e <= s) continue
        cal.timeInMillis = MapsDailyFragment.localMidnight(s)
        while (cal.timeInMillis <= e) {
            val dayStart = cal.timeInMillis
            val dayEnd   = dayStart + 24L * 3600_000L - 1L
            val overlap  = (minOf(dayEnd, e) - maxOf(dayStart, s)).coerceAtLeast(0)
            if (overlap > 0) {
                val key = dayFmt.format(Date(dayStart))
                val cur = perDay[key]
                if (cur == null || overlap > cur.overlapMs) {
                    perDay[key] = Best(dayStart, stop, overlap)
                }
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
    }
    return perDay.entries
        .sortedByDescending { it.key }
        .map { (_, b) ->
            DailyEntry(
                dayMs        = b.anchorMs,
                placeName    = b.stop.placeName,
                neighborhood = b.stop.neighborhood,
                city         = b.stop.city,
                country      = b.stop.country,
                lat          = b.stop.lat,
                lon          = b.stop.lon,
            )
        }
}

/**
 * "Daily" tab — one row per calendar day showing the user's primary
 * place that day (the LONGEST-dwell Stop overlapping that day). Tapping a
 * day switches Timeline to the Stops tab, filtered to that one day (via the
 * host [MapsTimelineTabsFragment]) — tapping a stop row there is what opens
 * the full-screen day map ([MapsDayMapFragment]).
 *
 * [computeDailyLocations] is also the data source for the Explored tab
 * ([MapsExploredFragment]) — Explored groups these same per-day city picks
 * by city, so it never shows raw per-stop noise.
 *
 * LazyColumn-backed — the full 1987-1992 demo trip is ~2192 rows; only the rows on screen are
 * composed (slow to open, janky scroll otherwise).
 */
class MapsDailyFragment : KitComposeFragment() {

    override fun palette(): KitPalette = MapsInk.palette()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val p = LocalKitPalette.current
        // All-time — not a rolling "last N years" window. The demo dataset is a
        // deliberately historical (1987-1992) block precisely so it never
        // collides with real tracked data; a bounded "recent" window would hide
        // it (the bug this avoids — see MapsDemo's doc comment).
        val daily = remember { computeDailyLocations(MapsDb.get(ctx).stopsBetween(0L, System.currentTimeMillis())) }

        if (daily.isEmpty()) {
            Text(
                "No daily locations yet. Daily picks the LONGEST-dwell Stop per day — needs at least one Stop in the DB. Start the tracker on the Configs page, dwell ≥ 5 min in one spot, and refresh.",
                color = p.textSecondary, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxSize().background(p.surface).padding(16.dp),
            )
            return
        }

        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        var picking by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(p.surface)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().testTag("maps:daily:list"),
            ) {
                items(daily, key = { it.dayMs }) { entry ->
                    DailyRow(entry) { dayMs -> TimelineHost.openStops(parentFragment, dayMs) }
                }
            }
            // Calendar jump — pick a date, scroll to the nearest day in the list
            // (2192 days of demo history is a lot to scroll by hand).
            FloatingActionButton(
                onClick = { picking = true },
                containerColor = p.accent,
                contentColor = p.tileInk,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("maps:daily:jump"),
            ) {
                Icon(painterResource(android.R.drawable.ic_menu_my_calendar), contentDescription = "Jump to date")
            }
        }
        if (picking) {
            JumpDialog(daily, onDismiss = { picking = false }) { target ->
                scope.launch { listState.scrollToItem(nearestIndex(daily, target)) }
            }
        }
    }

    /** Date picker → scroll to the entry whose day is nearest the chosen date (the list is descending by day). */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun JumpDialog(daily: List<DailyEntry>, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
        // Clamp the picker to the actual data range (oldest..newest).
        val minUtc = utcOfLocalDay(daily.minOf { it.dayMs })
        val maxUtc = utcOfLocalDay(daily.maxOf { it.dayMs })
        val state = rememberDatePickerState(
            initialSelectedDateMillis = utcOfLocalDay(daily.first().dayMs),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis in minUtc..maxUtc
            },
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(localMidnightOfUtcDay(it)) }
                    onDismiss()
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }

    companion object {
        fun newInstance() = MapsDailyFragment()

        /** Device-local midnight (epoch ms) of the calendar day [ts] falls in —
         *  the day-bucketing convention shared with [MapsDayMapFragment] and
         *  [MapsStopsFragment]'s "open this stop's day" tap. */
        fun localMidnight(ts: Long): Long {
            val cal = Calendar.getInstance()
            cal.timeInMillis = ts
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        /** The index of the entry whose day is nearest [targetMs] (0 for an empty list). */
        fun nearestIndex(daily: List<DailyEntry>, targetMs: Long): Int =
            daily.indices.minByOrNull { abs(daily[it].dayMs - targetMs) } ?: 0

        /** The Material date picker speaks UTC midnights; the list speaks local ones. Same calendar day, other clock. */
        fun utcOfLocalDay(localMs: Long): Long {
            val cal = Calendar.getInstance().apply { timeInMillis = localMs }
            return LocalDate.of(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }

        /** The inverse of [utcOfLocalDay]: the local midnight of the calendar day a UTC-midnight stamp names. */
        fun localMidnightOfUtcDay(utcMs: Long): Long {
            val d = Instant.ofEpochMilli(utcMs).atZone(ZoneOffset.UTC).toLocalDate()
            return Calendar.getInstance().apply {
                set(d.year, d.monthValue - 1, d.dayOfMonth, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
    }
}

/** What one Daily row says: the day, its longest-dwell place, and the line under it. */
data class DailyRowText(val title: String, val place: String, val sub: String)

private val dailyDayFmt = SimpleDateFormat("EEE  ·  dd MMM yyyy", Locale.US)

/** Pure: the three lines of a Daily row for [entry] (an unresolved place reads "Resolving…"). */
fun dailyRowText(entry: DailyEntry): DailyRowText {
    val parts = listOfNotNull(entry.neighborhood, entry.city, entry.country)
    return DailyRowText(
        title = synchronized(dailyDayFmt) { dailyDayFmt.format(Date(entry.dayMs)) },
        place = entry.placeName ?: "Resolving…",
        sub = if (parts.isNotEmpty()) parts.joinToString("  ·  ") + "    ·  tap for this day's stops ▸"
              else "tap for this day's stops ▸",
    )
}

/** One row of the Daily list — a tap opens that day's stops. */
@Composable
private fun DailyRow(entry: DailyEntry, onTap: (Long) -> Unit) {
    val p = LocalKitPalette.current
    val text = dailyRowText(entry)
    MapsTile(Modifier.testTag("maps:daily:row:${entry.dayMs}"), onClick = { onTap(entry.dayMs) }) {
        Text(text.title, color = p.accent, style = MaterialTheme.typography.titleMedium)
        Text(text.place, color = p.textPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(text.sub, color = p.textSecondary, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp))
    }
}
