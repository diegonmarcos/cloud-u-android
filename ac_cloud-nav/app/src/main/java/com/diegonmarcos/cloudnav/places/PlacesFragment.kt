package com.diegonmarcos.cloudnav.places

import android.os.Bundle
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudnav.FragmentHost
import com.diegonmarcos.cloudnav.NavChip
import com.diegonmarcos.cloudnav.NavConfig
import com.diegonmarcos.cloudnav.PlaceCategory
import com.diegonmarcos.cloudnav.R
import com.diegonmarcos.cloudnav.maps.MapsMapFragment
import com.diegonmarcos.cloudnav.maps.MapsProviderClient
import com.diegonmarcos.cloudnav.tracker.TrackerInk
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitSearchBar
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * Places tab — the universal "find anything" surface. A live map with the
 * simplest search bar (free text → countries, cities, restaurants, anything)
 * + a row of category "islands" beneath it (Bars, Beaches, Gyms, …) like
 * Google Maps. After a search/category lookup the pins drop on the map AND a
 * results sheet (peeking just above the bottom nav) lists the results;
 * tapping a row recenters the map on that place and opens its detail card.
 *
 * Compose (KitComposeFragment): the map is libs:maps' fragment inside a [FragmentHost].
 */
class PlacesFragment : KitComposeFragment() {

    private var mapFragment: MapsMapFragment? = null
    private var activeRadiusKm by mutableDoubleStateOf(0.0)   // search scope: City/Country/World
    private var query by mutableStateOf("")
    private var currentHits by mutableStateOf<List<MapsProviderClient.SearchHit>>(emptyList())
    private var resultsLabel by mutableStateOf("")
    private var sheet by mutableIntStateOf(SHEET_HIDDEN)
    private var detail by mutableStateOf<MapsProviderClient.SearchHit?>(null)

    override fun palette(): KitPalette = TrackerInk.palette()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeRadiusKm = NavConfig.searchScopes.firstOrNull { it.id == NavConfig.defaultScope }?.radiusKm ?: 0.0
    }

    /** A fresh map page, wired to this tab. Called by the host each time its container is new. */
    private fun newMap(): MapsMapFragment = MapsMapFragment.newInstance(fab = true, style = "satellite").also { map ->
        mapFragment = map
        // Tap a pin → open its rich detail card.
        map.onPinClick = { id -> currentHits.getOrNull(id.toIntOrNull() ?: -1)?.let { detail = it } }
        // Handed a geo: location by another app (Cloud Nav as a maps app): once
        // the map is ready, drop+centre on the point, or run the address search.
        applyGeoArgs(map)
    }

    @Composable
    override fun Content() {
        val scopes = remember { NavConfig.searchScopes }
        val categories = remember { NavConfig.placeCategories }
        var scopesShown by remember { mutableStateOf(false) }
        var scopeId by remember { mutableStateOf(NavConfig.defaultScope) }
        val keyboard = LocalSoftwareKeyboardController.current

        Box(Modifier.fillMaxSize()) {
            FragmentHost(childFragmentManager, R.id.nav_host_places, 0, Modifier.fillMaxSize()) { newMap() }

            // Top overlay: simple search + radius-scope islands + category islands.
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // The radius scope (City / Country / World) shows once the search bar is focused.
                Box(Modifier.onFocusChanged { if (it.hasFocus) scopesShown = true }) {
                    KitSearchBar(query, { query = it }, { search(query); keyboard?.hide() }, placeholder = "Search places, addresses, cities…")
                }
                if (scopesShown) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    scopes.forEach { sc ->
                        NavChip(if (sc.emoji.isBlank()) sc.label else "${sc.emoji} ${sc.label}", sc.id == scopeId,
                            { scopeId = sc.id; activeRadiusKm = sc.radiusKm }, tag = "scope:${sc.id}")
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { cat ->
                        NavChip(if (cat.emoji.isBlank()) cat.label else "${cat.emoji} ${cat.label}", false,
                            { searchCategory(cat) }, tag = "cat:${cat.id}")
                    }
                }
            }

            if (sheet != SHEET_HIDDEN) ResultsSheet(
                Modifier.align(Alignment.BottomCenter), "${currentHits.size} · $resultsLabel", currentHits, sheet == SHEET_EXPANDED,
                onToggle = { sheet = if (sheet == SHEET_EXPANDED) SHEET_COLLAPSED else SHEET_EXPANDED },
                onClose = { sheet = SHEET_HIDDEN },
                onPick = { hit -> mapFragment?.recenter(hit.lat, hit.lon, 16.0); detail = hit },
            )
        }

        detail?.let { hit ->
            val here = mapFragment?.myLatLon()
            PlaceDetailSheet(hit, here?.first ?: Double.NaN, here?.second ?: Double.NaN) { detail = null }
        }
    }

    /** Act on a geo: hand-off carried in [getArguments]. A point drops a pin
     *  and centres on it; a query runs the forward search. Deferred to
     *  onMapReady so the camera/pin plumbing exists. */
    private fun applyGeoArgs(map: MapsMapFragment) {
        val args = arguments ?: return
        if (args.getBoolean(ARG_HAS_POINT, false)) {
            val lat = args.getDouble(ARG_LAT)
            val lon = args.getDouble(ARG_LON)
            val label = args.getString(ARG_LABEL) ?: "Dropped pin"
            val zoom = if (args.containsKey(ARG_ZOOM)) args.getDouble(ARG_ZOOM) else 16.0
            map.onMapReady = {
                map.setPins(listOf(MapsMapFragment.Pin(lat, lon, MapsMapFragment.COLOR_RESULT, title = label, id = "0")))
                map.recenter(lat, lon, zoom)
            }
        } else {
            val q = args.getString(ARG_QUERY)
            if (!q.isNullOrBlank()) map.onMapReady = { search(q) }
        }
    }

    /** Free-text universal search → red pins + results list. Bounded by the
     *  active radius scope (City/Country/World) around the user (or map). */
    private fun search(q: String) {
        if (q.isBlank()) return
        val ctx = context ?: return
        val map = mapFragment
        val focus = map?.myLatLon() ?: map?.centerTarget()
        val radius = activeRadiusKm
        Thread {
            val hits = MapsProviderClient.forwardSearch(
                ctx, q,
                focusLat = focus?.first ?: 0.0,
                focusLon = focus?.second ?: 0.0,
                radiusKm = radius,
            )
            ui {
                if (hits.isEmpty()) { Toast.makeText(ctx, "No match for \"$q\"", Toast.LENGTH_SHORT).show(); return@ui }
                showResults(hits, "\"$q\"", MapsMapFragment.COLOR_RESULT)
            }
        }.start()
    }

    /** Category island → Overpass POI in the viewport → blue pins + list. */
    private fun searchCategory(cat: PlaceCategory) {
        val ctx = context ?: return
        val bounds = mapFragment?.visibleBounds()
        if (bounds == null) { Toast.makeText(ctx, "Map still loading…", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(ctx, "Searching ${cat.label}…", Toast.LENGTH_SHORT).show()
        Thread {
            val hits = MapsProviderClient.poiSearch(ctx, cat.query, bounds[0], bounds[1], bounds[2], bounds[3])
            ui {
                if (hits.isEmpty()) { Toast.makeText(ctx, "No ${cat.label} in view — zoom/pan and retry", Toast.LENGTH_SHORT).show(); return@ui }
                showResults(hits, cat.label, MapsMapFragment.COLOR_PLACE)
            }
        }.start()
    }

    /** Drop pins, frame them, and populate + peek the results sheet. */
    private fun showResults(hits: List<MapsProviderClient.SearchHit>, label: String, color: String) {
        currentHits = hits
        resultsLabel = label
        val pins = hits.mapIndexed { i, h -> MapsMapFragment.Pin(h.lat, h.lon, color, title = h.title, id = i.toString()) }
        mapFragment?.setPins(pins)
        mapFragment?.fitTo(pins)
        sheet = SHEET_COLLAPSED
    }

    private fun ui(block: () -> Unit) {
        if (!isAdded) return
        requireActivity().runOnUiThread { if (isAdded) block() }
    }

    companion object {
        private const val SHEET_HIDDEN = 0
        private const val SHEET_COLLAPSED = 1
        private const val SHEET_EXPANDED = 2

        private const val ARG_HAS_POINT = "geo_has_point"
        private const val ARG_LAT = "geo_lat"
        private const val ARG_LON = "geo_lon"
        private const val ARG_LABEL = "geo_label"
        private const val ARG_ZOOM = "geo_zoom"
        private const val ARG_QUERY = "geo_query"

        /** Build a Places screen primed to act on an incoming geo: location — a
         *  point drops+centres a pin; a query runs the forward search. */
        fun forGeo(t: com.diegonmarcos.cloudnav.GeoTarget): PlacesFragment = when (t) {
            is com.diegonmarcos.cloudnav.GeoTarget.Point -> PlacesFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_HAS_POINT, true)
                    putDouble(ARG_LAT, t.lat)
                    putDouble(ARG_LON, t.lon)
                    t.label?.let { putString(ARG_LABEL, it) }
                    t.zoom?.let { putDouble(ARG_ZOOM, it) }
                }
            }
            is com.diegonmarcos.cloudnav.GeoTarget.Query -> PlacesFragment().apply {
                arguments = Bundle().apply { putString(ARG_QUERY, t.text) }
            }
        }
    }
}

object PlacesTags {
    const val SHEET = "places:results"
    const val SHEET_TOGGLE = "places:results:toggle"
    const val SHEET_CLOSE = "places:results:close"
    fun row(i: Int) = "places:results:row:$i"
}

/** The results list over the map: a handle and title that fold the list open, and one row per hit. */
@Composable
private fun ResultsSheet(
    modifier: Modifier, title: String, hits: List<MapsProviderClient.SearchHit>, expanded: Boolean,
    onToggle: () -> Unit, onClose: () -> Unit, onPick: (MapsProviderClient.SearchHit) -> Unit,
) {
    val p = LocalKitPalette.current
    Column(modifier.fillMaxWidth().testTag(PlacesTags.SHEET).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
        .background(p.surface).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.hairline))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).testTag(PlacesTags.SHEET_TOGGLE).clickable(onClick = onToggle).padding(vertical = 8.dp),
                color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
            Text("✕", Modifier.testTag(PlacesTags.SHEET_CLOSE).clickable(onClick = onClose).padding(8.dp), color = p.textSecondary,
                style = MaterialTheme.typography.titleMedium)
        }
        if (expanded) LazyColumn(Modifier.heightIn(max = 320.dp)) {
            itemsIndexed(hits) { i, hit ->
                Column(Modifier.fillMaxWidth().testTag(PlacesTags.row(i)).clickable { onPick(hit) }.padding(vertical = 10.dp)) {
                    Text(hit.title, color = p.textPrimary, style = MaterialTheme.typography.bodyLarge)
                    hit.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, color = p.textSecondary, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
