package com.diegonmarcos.cloudnav.routes

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudnav.FragmentHost
import com.diegonmarcos.cloudnav.NavChip
import com.diegonmarcos.cloudnav.R
import com.diegonmarcos.cloudnav.ResultPicker
import com.diegonmarcos.cloudnav.maps.MapsMapFragment
import com.diegonmarcos.cloudnav.maps.MapsProviderClient
import com.diegonmarcos.cloudnav.maps.MapsRouting
import com.diegonmarcos.cloudnav.tracker.TrackerInk
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.KitSearchBar
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * Routes tab — multi-stop routing with a drawn route line, a travel-mode
 * switcher (car/walk/bike/bus/boat/flight/swim) and an all-modes stats sheet.
 *
 *   • Start defaults to "Your location"; each searched stop offers the top-3
 *     candidates to choose from (no silent first-hit).
 *   • Tap a stop to edit it (remove / move / rename).
 *   • The mode chips redraw the route (real road geometry via Valhalla for
 *     car/walk/bike/bus; great-circle estimate for boat/flight/swim).
 *   • The ⤢ expand icon opens a sheet comparing distance + time across ALL
 *     modes at once.
 *
 * Compose (KitComposeFragment): the map is libs:maps' fragment inside a [FragmentHost].
 */
class RoutesFragment : KitComposeFragment() {

    private var mapFragment: MapsMapFragment? = null

    private val stops = mutableStateListOf(Stop(RouteStops.YOUR_LOCATION, null))

    private val modes by lazy { MapsRouting.modes() }
    private var activeModeId by mutableStateOf(MapsRouting.defaultModeId())

    private var query by mutableStateOf("")
    private var summary by mutableStateOf("Add a destination to see the route")
    private var statsOpen by mutableStateOf(false)
    private var statsNote by mutableStateOf<String?>(null)
    private val stats = mutableStateMapOf<String, String>()   // mode id -> "distance · time"
    private var editing by mutableStateOf<Int?>(null)         // the stop whose action menu is open
    private var renaming by mutableStateOf<Int?>(null)        // the stop being re-searched
    private val picker = ResultPicker()

    override fun palette(): KitPalette = TrackerInk.palette()

    /** A fresh map page, wired to this tab. Called by the host each time its container is new. */
    private fun newMap(): MapsMapFragment = MapsMapFragment.newInstance(fab = true, autoLocate = true).also { map ->
        mapFragment = map
        // Recompute once the user locates (the "Your location" origin resolves).
        // onUserLocation uses android.location.Location — no MapLibre type leaks
        // into the app module (unlike onMapReady).
        map.onUserLocation = { recomputeRoute() }
        // Long-press the map → mark that spot as a stop and (re)trace the route.
        map.onMapLongClick = { lat, lon -> addStopFromMap(lat, lon) }
    }

    @Composable
    override fun Content() {
        val p = LocalKitPalette.current
        val keyboard = LocalSoftwareKeyboardController.current
        Box(Modifier.fillMaxSize()) {
            FragmentHost(childFragmentManager, R.id.nav_host_routes, 0, Modifier.fillMaxSize()) { newMap() }

            // Top routing card.
            KitCard(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp).testTag(RoutesTags.CARD)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Route", Modifier.weight(1f), color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
                    Text("⤢", Modifier.testTag(RoutesTags.EXPAND).clickable { openStats() }.padding(horizontal = 8.dp),
                        color = p.accent, style = MaterialTheme.typography.titleLarge)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KitSearchBar(query, { query = it }, { addDestination(query); keyboard?.hide() }, Modifier.weight(1f),
                        placeholder = "Add a stop — place, address, city…")
                    Text("+", Modifier.testTag(RoutesTags.ADD).clip(RoundedCornerShape(50)).background(p.accent)
                        .clickable { addDestination(query); keyboard?.hide() }.padding(horizontal = 14.dp, vertical = 4.dp),
                        color = p.tileInk, style = MaterialTheme.typography.titleLarge)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    modes.forEach { m ->
                        NavChip("${m.emoji} ${m.label}", m.id == activeModeId, { activeModeId = m.id; recomputeRoute() }, tag = "mode:${m.id}")
                    }
                }
                stops.forEachIndexed { i, s ->
                    Text("${i + 1}.  ${s.label}", Modifier.fillMaxWidth().testTag(RoutesTags.stop(i)).clickable { editing = i }.padding(vertical = 4.dp),
                        color = if (s.latLon == null) p.accent else p.textPrimary, style = MaterialTheme.typography.bodyMedium)
                }
                Text(summary, Modifier.testTag(RoutesTags.SUMMARY), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            }

            if (statsOpen) StatsSheet(Modifier.align(Alignment.BottomCenter), modes, stats, statsNote) { statsOpen = false }
        }

        picker.Host()
        editing?.let { i -> stops.getOrNull(i)?.let { s -> StopActions(s.label, { editing = null }) { action ->
            editing = null
            when (action) {
                StopAction.RENAME -> renaming = i
                StopAction.UP -> { moveStop(i, -1) }
                StopAction.DOWN -> { moveStop(i, 1) }
                StopAction.REMOVE -> { stops.removeAt(i); recomputeRoute() }
            }
        } } }
        renaming?.let { i -> stops.getOrNull(i)?.let { s -> RenameDialog(s.label, { renaming = null }) { q ->
            renaming = null
            renameStop(i, q)
        } } }
    }

    // ── stops ────────────────────────────────────────────────────────
    private fun addDestination(q: String) {
        if (q.isBlank()) return
        val ctx = context ?: return
        val center = mapFragment?.centerTarget()
        Thread {
            val hits = MapsProviderClient.forwardSearch(ctx, q, center?.first ?: 0.0, center?.second ?: 0.0)
            ui {
                if (hits.isEmpty()) { Toast.makeText(ctx, "No match for \"$q\"", Toast.LENGTH_SHORT).show(); return@ui }
                picker.offer(hits) { hit ->
                    stops.add(Stop(hit.title, hit.lat to hit.lon))
                    query = ""
                    recomputeRoute()
                }
            }
        }.start()
    }

    /** Long-press handler: add the tapped coordinate as a stop immediately (so
     *  the route traces right away), then reverse-geocode a nicer label. */
    private fun addStopFromMap(lat: Double, lon: Double) {
        val ctx = context ?: return
        val stop = Stop(RouteStops.droppedPinLabel(lat, lon), lat to lon)
        stops.add(stop)
        recomputeRoute()
        Toast.makeText(ctx, "Marked — tracing route…", Toast.LENGTH_SHORT).show()
        Thread {
            val r = MapsProviderClient.reverseGeocode(ctx, lat, lon)
            val name = r?.placeName?.substringBefore(",")?.trim()?.ifEmpty { null }
                ?: r?.city ?: return@Thread
            ui {
                val idx = stops.indexOf(stop)
                if (idx >= 0) stops[idx] = Stop(name, lat to lon)
            }
        }.start()
    }

    private fun moveStop(index: Int, delta: Int) {
        val moved = RouteStops.moved(stops.toList(), index, delta)
        stops.clear(); stops.addAll(moved)
        recomputeRoute()
    }

    private fun renameStop(index: Int, q: String) {
        val ctx = context ?: return
        if (q.isBlank()) return
        val center = mapFragment?.centerTarget()
        Thread {
            val hits = MapsProviderClient.forwardSearch(ctx, q, center?.first ?: 0.0, center?.second ?: 0.0)
            ui {
                if (hits.isEmpty()) { Toast.makeText(ctx, "No match", Toast.LENGTH_SHORT).show(); return@ui }
                picker.offer(hits) { hit ->
                    if (index in stops.indices) stops[index] = Stop(hit.title, hit.lat to hit.lon)
                    recomputeRoute()
                }
            }
        }.start()
    }

    // ── routing ──────────────────────────────────────────────────────
    private fun coordsForRoute(): List<DoubleArray> = RouteStops.coords(stops.toList(), mapFragment?.myLatLon())

    private fun recomputeRoute() {
        if (!isAdded) return
        val map = mapFragment ?: return
        val pins = stops.mapNotNull { it.latLon }.map { MapsMapFragment.Pin(it.first, it.second, MapsMapFragment.COLOR_RESULT) }
        map.setPins(pins)
        val coords = coordsForRoute()
        if (coords.size < 2) {
            map.clearRoute()
            summary = RouteStops.idleSummary(stops.toList())
            return
        }
        val mode = modes.firstOrNull { it.id == activeModeId } ?: return
        summary = "Routing…"
        Thread {
            val r = MapsRouting.route(mode, coords)
            ui {
                if (r == null) { summary = "No ${mode.label.lowercase()} route found"; map.clearRoute(); return@ui }
                map.setRoute(r.geometry)
                summary = "${mode.emoji} ${MapsRouting.fmtDistance(r.distanceKm)} · ${MapsRouting.fmtDuration(r.durationSec)}" +
                    if (r.estimated) "  (est.)" else ""
                if (pins.isNotEmpty()) map.fitTo(pins)
            }
        }.start()
    }

    private fun openStats() {
        val coords = coordsForRoute()
        stats.clear()
        statsOpen = true
        if (coords.size < 2) {
            statsNote = "Add at least one destination (and your location) to compare travel options."
            return
        }
        statsNote = null
        modes.forEach { mode ->
            stats[mode.id] = "…"
            Thread {
                val r = MapsRouting.route(mode, coords)
                ui {
                    stats[mode.id] = if (r == null) "—" else
                        "${MapsRouting.fmtDistance(r.distanceKm)} · ${MapsRouting.fmtDuration(r.durationSec)}" +
                            if (r.estimated) " (est.)" else ""
                }
            }.start()
        }
    }

    private fun ui(block: () -> Unit) { if (!isAdded) return; requireActivity().runOnUiThread { if (isAdded) block() } }
}

object RoutesTags {
    const val CARD = "routes:card"
    const val EXPAND = "routes:expand"
    const val ADD = "routes:add"
    const val SUMMARY = "routes:summary"
    const val STATS = "routes:stats"
    const val STATS_CLOSE = "routes:stats:close"
    const val RENAME_FIELD = "routes:rename:field"
    const val RENAME_GO = "routes:rename:go"
    fun stop(i: Int) = "routes:stop:$i"
    fun action(a: StopAction) = "routes:action:${a.name.lowercase()}"
    fun stat(modeId: String) = "routes:stat:$modeId"
}

enum class StopAction(val label: String) {
    RENAME("Rename / re-search"), UP("Move up"), DOWN("Move down"), REMOVE("Remove")
}

/** The all-modes comparison over the map. */
@Composable
private fun StatsSheet(modifier: Modifier, modes: List<MapsRouting.Mode>, stats: Map<String, String>, note: String?, onClose: () -> Unit) {
    val p = LocalKitPalette.current
    Column(modifier.fillMaxWidth().testTag(RoutesTags.STATS).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
        .background(p.surface).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.hairline))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Travel options", Modifier.weight(1f), color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
            Text("✕", Modifier.testTag(RoutesTags.STATS_CLOSE).clickable(onClick = onClose).padding(8.dp), color = p.textSecondary,
                style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            if (note != null) Text(note, color = p.textSecondary, style = MaterialTheme.typography.bodyMedium)
            else modes.forEach { m ->
                Text("${m.emoji} ${m.label}     ${stats[m.id] ?: "…"}", Modifier.testTag(RoutesTags.stat(m.id)).padding(vertical = 10.dp),
                    color = p.textPrimary, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun StopActions(title: String, onDismiss: () -> Unit, onPick: (StopAction) -> Unit) {
    val p = LocalKitPalette.current
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = p.surfaceSelected, titleContentColor = p.textPrimary,
        title = { Text(title) },
        text = {
            Column {
                for (a in StopAction.values()) {
                    Text(a.label, Modifier.fillMaxWidth().testTag(RoutesTags.action(a)).clickable { onPick(a) }.padding(vertical = 12.dp),
                        color = p.textPrimary, style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSearch: (String) -> Unit) {
    val p = LocalKitPalette.current
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = p.surfaceSelected, titleContentColor = p.textPrimary,
        title = { Text("Edit stop") },
        text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().testTag(RoutesTags.RENAME_FIELD), singleLine = true) },
        confirmButton = { TextButton(onClick = { onSearch(text) }, modifier = Modifier.testTag(RoutesTags.RENAME_GO)) { Text("Search", color = p.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = p.accent) } },
    )
}
