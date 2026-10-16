package com.diegonmarcos.cloudnav.maps

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 * MyTrips → Stats page. All-time rollups grouped by year. Each year row
 * shows: # Stops, # Cities, # Countries, top 3 cities. Cheap to
 * compute from the existing Stop table — no extra DB schema.
 *
 * Push 3.5 can layer in a stacked-bar chart (year × top-N cities) and
 * a country heatmap. For Push 3 the goal is a credible text table.
 */
class MytripsStatsFragment : KitComposeFragment() {

    override fun palette(): KitPalette = MapsInk.palette()

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        // All-time — see MapsDailyFragment's comment: a "last N years" bound
        // would hide the deliberately historical (1987-1992) demo dataset.
        val stops = remember { MapsDb.get(ctx).stopsBetween(0L, System.currentTimeMillis()) }
        MapsPage {
            MapsHeading("Stats — all time")
            if (stops.isEmpty()) {
                MapsCaption("No Stops collected yet.")
            } else {
                // Group by yyyy via SimpleDateFormat (local TZ).
                val yearFmt = SimpleDateFormat("yyyy", Locale.US)
                val byYear = stops.groupBy { yearFmt.format(Date(it.startedAt)) }
                for ((year, yearStops) in byYear.entries.sortedByDescending { it.key }) YearTile(year, yearStops)
            }
        }
    }

    @Composable
    private fun YearTile(year: String, stops: List<MapsDb.RichStop>) {
        val p = LocalKitPalette.current
        val cities = stops.mapNotNull { it.city }
        val countries = stops.mapNotNull { it.country }.toSet()
        val topCities = cities.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(3)
            .joinToString("  ·  ") { "${it.key} (${it.value})" }
        MapsTile(Modifier.padding(vertical = 6.dp)) {
            Text(year, color = p.accent, style = MaterialTheme.typography.headlineSmall)
            Text("${stops.size} stops  ·  ${cities.toSet().size} cities  ·  ${countries.size} countries",
                color = p.textPrimary, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            if (topCities.isNotEmpty()) {
                Text("Top: $topCities", color = p.textSecondary, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    companion object { fun newInstance() = MytripsStatsFragment() }
}
