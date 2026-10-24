package com.diegonmarcos.cloudnav.tracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Explored > Maps (#1156): the Trips data on an OSM map (the Day page's MapLibre setup, #1145): pins for places and stays, routes,
 * and drawn areas (cities, regions, countries visited). Data: the provider's `tracker/trips` plus the last [DAYS] days of
 * `tracker/segments`, mapped by [ExploredMapData].
 */
class ExploredMapsFragment : KitComposeFragment() {
    private var layers by mutableStateOf(ExploredLayers(emptyList(), emptyList(), emptyList()))
    private var loading by mutableStateOf(true)

    override fun palette(): KitPalette = TrackerInk.palette()
    override fun onResume() { super.onResume(); load() }

    @Composable
    override fun Content() {
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxSize().background(p.surface)) {
            Text(
                if (loading && layers.isEmpty) "Loading..." else "${layers.pins.size} pins  ·  ${layers.routes.size} routes  ·  ${layers.areas.size} areas (approximate: city discs, region and country hulls)",
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(TAG_SUMMARY), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            ExploredMap(layers, Modifier.fillMaxSize().testTag(TAG_MAP))
        }
    }

    private fun load() {
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            layers = withContext(Dispatchers.IO) {
                runCatching {
                    val trips = TrackerDebugApiProvider.trips(app, 90)
                    val today = LocalDate.now()
                    val days = (DAYS - 1 downTo 0).map { TrackerDebugApiProvider.segments(app, today.minusDays(it.toLong()).toString()) }
                    ExploredMapData.layers(trips, days)
                }.getOrDefault(layers)
            }
            loading = false
        }
    }

    companion object {
        const val DAYS = 7
        const val TAG_MAP = "explored:map"; const val TAG_SUMMARY = "explored:summary"
    }
}
