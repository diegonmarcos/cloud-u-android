package com.diegonmarcos.cloudnav.maps

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen day-detail map — opened by tapping a row in the Daily
 * timeline. Pins every Stop the user visited that calendar day (green
 * dots) on a [MapsMapFragment] and frames the camera around them. A
 * floating top card shows the date + a back chevron.
 *
 * Overlaid on android.R.id.content with the back stack, so the system
 * back button / the chevron dismisses it back to the timeline.
 */
class MapsDayMapFragment : KitComposeFragment() {

    private val dayMs: Long get() = arguments?.getLong(ARG_DAY_MS, 0L) ?: 0L

    override fun palette(): KitPalette = MapsInk.palette()

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val p = LocalKitPalette.current
        val dayStart = dayMs
        val dayEnd = dayStart + 24L * 3600_000L - 1L
        val stops = remember(dayStart) { MapsDb.get(ctx).stopsBetween(dayStart, dayEnd) }
        val dayFmt = remember { SimpleDateFormat("EEE  ·  dd MMM yyyy", Locale.US) }

        Box(
            Modifier.fillMaxSize().background(p.surface)       // opaque — covers the timeline beneath.
                .pointerInput(Unit) { detectTapGestures { } }, // swallow taps to the layer below.
        ) {
            // Embedded interactive map. Pins set BEFORE commit → baked into the style source at
            // load time (see MapsExploredFragment); onMapReady only frames the camera.
            ChildFragmentHost(
                fm = childFragmentManager,
                containerId = R.id.maps_host_day_map,
                key = dayStart,
                modifier = Modifier.fillMaxSize(),
            ) {
                val pins = stops.map { MapsMapFragment.Pin(it.lat, it.lon, MapsMapFragment.COLOR_DAY) }
                MapsMapFragment.newInstance(nav3d = false, fab = true).apply {
                    setPins(pins)
                    onMapReady = { if (pins.isNotEmpty()) fitTo(pins) }
                }
            }

            // Floating top card: back chevron + date + stop count.
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
                shape = RoundedCornerShape(16.dp),
                color = p.surfaceSelected,
                shadowElevation = 6.dp,
            ) {
                Row(Modifier.padding(start = 12.dp, top = 10.dp, end = 16.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("◂", color = p.textPrimary, style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag("maps:day-map:back").clickable { dismiss() }
                            .padding(end = 12.dp))
                    Column {
                        Text(dayFmt.format(Date(dayStart)), color = p.textPrimary,
                            style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (stops.isEmpty()) "No mapped stops this day"
                            else "${stops.size} place${if (stops.size == 1) "" else "s"} this day",
                            color = p.textSecondary, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    private fun dismiss() {
        requireActivity().supportFragmentManager.popBackStack()
    }

    companion object {
        private const val ARG_DAY_MS = "day_ms"

        fun newInstance(dayMs: Long) = MapsDayMapFragment().apply {
            arguments = Bundle().apply { putLong(ARG_DAY_MS, dayMs) }
        }
    }
}
