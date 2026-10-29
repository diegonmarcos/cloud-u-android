package com.diegonmarcos.cloudnav.maps

import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlin.math.roundToInt

/**
 * Configs sub-page, rendered in one of two SECTIONS (data-driven host =
 * ConfigsFragment's page strip):
 *
 *   • [SECTION_TRACKER] — tracker controls (status, Start/Stop, reset),
 *     calibration sliders, and the export panel.
 *   • [SECTION_APIS]    — provider pickers for Search, Reverse geocoder and
 *     POI/Places + inline API-key editors.
 *
 * Provider lists are data-driven from build.json::ui.maps_providers via
 * [MapsProviders.loadFromBuildConfig]; selections persist in
 * [MapsProviderPrefs]. Dark palette shared with the other Maps pages ([MapsInk]).
 */
class MapsConfigFragment : KitComposeFragment() {

    private val section: String get() = arguments?.getString(ARG_SECTION) ?: SECTION_TRACKER

    /** Bumped to make the page read its prefs and the DB again (a resume, a pick, a finished action). */
    private var tick by mutableIntStateOf(0)

    /** SAF file-picker for "Import JSON" — parses the picked file off-thread
     *  and inserts every trip as a Stop (see [MapsImport]). */
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val ctx = context ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            Toast.makeText(ctx, "Importing…", Toast.LENGTH_SHORT).show()
            Thread {
                val r = MapsImport.importFrom(ctx, uri)
                activity?.runOnUiThread {
                    Toast.makeText(
                        ctx,
                        r.error ?: "Imported ${r.inserted} places — open Timeline",
                        Toast.LENGTH_LONG,
                    ).show()
                    tick++
                }
            }.start()
        }

    override fun onResume() {
        super.onResume()
        if (section == SECTION_TRACKER) tick++
    }

    override fun palette(): KitPalette = MapsInk.palette()

    @Composable
    override fun Content() {
        check(tick >= 0)   // a read of the counter: a bump recomposes the whole page
        MapsPage {
            if (section == SECTION_TRACKER) Tracker() else Apis()
        }
    }

    // ── TRACKER section ──────────────────────────────────────────────
    @Composable
    private fun Tracker() {
        val ctx = LocalContext.current
        val p = LocalKitPalette.current
        val trackingPrefs = MapsTrackingPrefs(ctx)
        val trackerPrefs = MapsTrackerPrefs(ctx)
        val db = MapsDb.get(ctx)
        val missing = MapsPermissions.missing(ctx)
        val running = trackerPrefs.enabled

        MapsHeading("Tracker")
        Text(
            when {
                missing.isNotEmpty() -> "● Permissions missing"
                running -> "● Tracker running"
                else -> "● Tracker idle"
            },
            color = when {
                missing.isNotEmpty() -> p.bad
                running -> p.ok
                else -> p.warn
            },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("maps:tracker:status"),
        )
        Gap(8)
        val lastTs = trackerPrefs.lastFixTs
        val lastFixStr = if (lastTs <= 0L) "no fix yet" else
            "last fix " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(lastTs))
        Text("Points: ${db.pointCount()}\nStops:  ${db.stopCount()}\n$lastFixStr",
            color = p.textSecondary, style = MaterialTheme.typography.bodyMedium)
        Gap(12)
        if (missing.isNotEmpty()) {
            // #1274 the banner: the host's Permissions page asks (onResume re-reads on the way back).
            Row(Modifier.fillMaxWidth().testTag("maps:perms-banner"), verticalAlignment = Alignment.CenterVertically) {
                Text("Permissions needed: " + missing.joinToString(", ") {
                    it.substringAfterLast('.').replace('_', ' ').lowercase()
                }, color = p.warn, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                MapsButton(com.diegonmarcos.superapp.perms.PermsBanner.BUTTON, onClick = { MapsPermissions.openPermissions(ctx, missing) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MapsButton("Start", modifier = Modifier.weight(1f), enabled = !running, onClick = {
                if (missing.isNotEmpty()) {
                    MapsPermissions.openPermissions(ctx, missing)
                } else {
                    LocationTrackerService.startTracking(ctx)
                    tick++
                }
            })
            MapsButton("Stop", modifier = Modifier.weight(1f), enabled = running, onClick = {
                LocationTrackerService.stopTracking(ctx)
                tick++
            })
        }

        Gap(12)
        MapsCaption("Data reset wipes every collected point + stop + cached place. Irreversible — Export first if you want a backup.")
        var resetArmed by remember { mutableStateOf(false) }
        MapsButton(if (resetArmed) "Tap again to confirm wipe" else "Reset all tracker data", onClick = {
            if (!resetArmed) {
                resetArmed = true
            } else {
                MapsDb.get(ctx).clearAll()
                // Also clear the demo-seed flag — otherwise a wiped DB stays
                // permanently empty, since Load-demo-data would see "already
                // seeded" and silently no-op forever after this reset.
                MapsDemo.resetSeedFlag(ctx)
                resetArmed = false
                tick++
            }
        })

        // Tester seed — FULL daily coverage 1987-1992 (~2192 days) so Timeline/
        // Daily/Stops/Explored/MyTrips render real content without a live
        // tracker. Idempotent. Generates thousands of rows — off-thread.
        Gap(12)
        MapsCaption("No tracker history yet? Load a full 5-year demo trip (1987-1992, one day at a time — deliberately historical so it never conflicts with your real tracked data) to explore Timeline, Daily, Stops, Explored and MyTrips — every single day populated, cycling through 8 cities across 4 continents.")
        var loading by remember { mutableStateOf(false) }
        MapsButton(if (loading) "Loading…" else "Load demo data (1987-1992, full)", enabled = !loading, onClick = {
            loading = true
            Thread {
                val n = MapsDemo.seed(ctx)
                activity?.runOnUiThread {
                    Toast.makeText(
                        ctx,
                        if (n > 0) "Loaded $n demo stops across ${MapsDemo.totalDays} days — open Timeline" else "Demo trip already loaded",
                        Toast.LENGTH_LONG,
                    ).show()
                    loading = false
                    tick++
                }
            }.start()
        })

        // Import the user's OWN location history from a JSON file (e.g. the
        // travel-data.json export) → inserted as Stops via MapsImport.
        Gap(12)
        MapsCaption("Already have your own location history? Import a JSON file (travel-data.json / trips export) — each trip becomes a place in Timeline, Daily, Stops and Explored.")
        MapsButton("Import location data (JSON)", onClick = {
            // application/json first; some pickers only expose text/* or */*.
            importLauncher.launch(arrayOf("application/json", "text/json", "text/plain", "*/*"))
        })

        Gap(24)
        MapsHeading("Tracker calibration")
        MapsCaption("Tune the foreground-service battery sip. Tighter intervals + smaller radius = more accurate, more battery.")
        MapsSlider("GPS interval while MOVING", trackingPrefs.intervalMovingMs / 1000, 5, 120, "s") {
            trackingPrefs.intervalMovingMs = it * 1000
        }
        MapsSlider("GPS interval while STOPPED", trackingPrefs.intervalStoppedMs / 60_000, 1, 30, "min") {
            trackingPrefs.intervalStoppedMs = it * 60_000
        }
        MapsSlider("Moving threshold", trackingPrefs.movingThresholdMps.toInt(), 1, 10, "m/s") {
            trackingPrefs.movingThresholdMps = it.toFloat()
        }
        MapsSlider("Stop radius", trackingPrefs.stopsRadiusM, 10, 200, "m") { trackingPrefs.stopsRadiusM = it }
        MapsSlider("Stop dwell time", trackingPrefs.stopsDwellMin, 1, 30, "min") { trackingPrefs.stopsDwellMin = it }

        // Export panel (child fragment).
        Gap(24)
        MapsHeading("Export")
        ChildFragmentHost(
            fm = childFragmentManager,
            containerId = R.id.maps_host_export,
            key = "export",
            modifier = Modifier.fillMaxWidth(),
        ) { MapsExportFragment.newInstance() }
    }

    // ── APIs section ─────────────────────────────────────────────────
    @Composable
    private fun Apis() {
        val ctx = LocalContext.current
        val providerPrefs = MapsProviderPrefs(ctx)
        val apiKeyPrefs = MapsApiKeyPrefs(ctx)
        val all = MapsProviders.loadFromBuildConfig()

        // Basemap: "Auto" lets each screen keep its own smart default (Navigation
        // dark, Places satellite, Routes light, …) resolved to vector/hybrid or
        // raster by the family choice; picking a concrete style below PINS it
        // everywhere, overriding every screen's default. Vector/hybrid styles
        // fall back to raster automatically if their fetch fails at runtime.
        val basemapPrefs = MapsBasemapPrefs(ctx)
        MapsHeading("Basemap")
        MapsCaption("Auto keeps each screen's own look (Navigation dark, Places satellite, Routes light…), just swapping vector/raster/hybrid rendering. Pinning a specific map below overrides that everywhere.")
        PickRow("Auto — Vector (recommended)", "Each screen's default, rendered as English-labelled vector/hybrid",
            selected = basemapPrefs.explicitStyleKey == null && basemapPrefs.preferVectorFamily) {
            basemapPrefs.explicitStyleKey = null; basemapPrefs.preferVectorFamily = true; tick++
        }
        Gap(6)
        PickRow("Auto — Raster", "Each screen's default, rendered as fixed local-language tile images",
            selected = basemapPrefs.explicitStyleKey == null && !basemapPrefs.preferVectorFamily) {
            basemapPrefs.explicitStyleKey = null; basemapPrefs.preferVectorFamily = false; tick++
        }
        Gap(14)
        MapStyles.order.forEach { key ->
            val s = MapStyles.get(key)
            val kind = when { s.hybrid -> "Hybrid — imagery + English vector overlay"; s.vectorStyleUrl != null -> "Vector — English labels, on-device"; else -> "Raster — fixed tile images" }
            PickRow(s.label, kind, selected = basemapPrefs.explicitStyleKey == key) {
                basemapPrefs.explicitStyleKey = key; tick++
            }
            Gap(6)
        }
        Gap(20)

        // Search (forward geocode) — the universal search bar's backend.
        MapsHeading("Search (geocoder)")
        MapsCaption("Backs the search bar across Routes / Navigation / Places — turns 'Berlin', a street, a restaurant, anything into coordinates.")
        for (provider in MapsProviders.forKind(all, "search")) {
            ProviderRow(provider, apiKeyPrefs, selected = providerPrefs.activeSearch == provider.id) {
                providerPrefs.activeSearch = provider.id; tick++
            }
            Gap(6)
        }

        // Reverse geocoder.
        Gap(20)
        MapsHeading("Reverse geocoder")
        MapsCaption("Resolves a GPS point into city + neighborhood. Fires once per Stop event.")
        for (provider in MapsProviders.forKind(all, "reverse")) {
            ProviderRow(provider, apiKeyPrefs, selected = providerPrefs.activeReverse == provider.id) {
                providerPrefs.activeReverse = provider.id; tick++
            }
            Gap(6)
        }

        // POI / Places.
        Gap(20)
        MapsHeading("POI / Places")
        MapsCaption("Resolves an amenity (restaurant, shop, café) and backs the Places category islands (Overpass).")
        for (provider in MapsProviders.forKind(all, "poi")) {
            ProviderRow(provider, apiKeyPrefs, selected = providerPrefs.activePoi == provider.id) {
                providerPrefs.activePoi = provider.id; tick++
            }
            Gap(6)
        }
    }

    @Composable
    private fun Gap(size: Int) { Spacer(Modifier.height(size.dp)) }

    @Composable
    private fun PickRow(label: String, sub: String, selected: Boolean, onPick: () -> Unit) {
        val p = LocalKitPalette.current
        MapsTile(Modifier.testTag("maps:config:pick:$label"), selected = selected, onClick = onPick) {
            Text((if (selected) "● " else "○ ") + label, color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
            Text(sub, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun ProviderRow(
        provider: MapsProviders.Provider,
        apiKeyPrefs: MapsApiKeyPrefs,
        selected: Boolean,
        onPick: () -> Unit,
    ) {
        val p = LocalKitPalette.current
        MapsTile(Modifier.testTag("maps:config:provider:${provider.id}"), selected = selected, onClick = onPick) {
            Text(
                (if (selected) "● " else "○ ") + provider.label +
                    "   [" + hostShort(provider.host) + "]" +
                    "   " + provider.freeTier,
                color = p.textPrimary, style = MaterialTheme.typography.titleMedium,
            )
            if (provider.requiresApiKey) {
                var key by remember(provider.id) { mutableStateOf(apiKeyPrefs.get(provider.id)) }
                val has = key.isNotEmpty()
                Text(if (has) "API key ✓" else "Needs API key ↓",
                    color = if (has) p.accent else p.warn, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; apiKeyPrefs.set(provider.id, it) },
                    placeholder = { Text("API key for ${provider.label}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("maps:config:key:${provider.id}"),
                )
            }
        }
    }

    private fun hostShort(host: String): String = when (host) {
        "free-noauth"  -> "FREE · no account"
        "free-account" -> "FREE · account"
        "paid"         -> "PAID"
        "self"         -> "SELF-HOSTED"
        else           -> host.uppercase()
    }

    /** A labelled integer slider: shows the live value while dragging, commits (and ticks) on release. */
    @Composable
    private fun MapsSlider(
        title: String,
        currentValue: Int,
        min: Int,
        max: Int,
        unit: String,
        onChange: (Int) -> Unit,
    ) {
        val p = LocalKitPalette.current
        val view = LocalView.current
        var value by remember(title) { mutableFloatStateOf(currentValue.coerceIn(min, max).toFloat()) }
        Text("$title = ${value.roundToInt()}$unit", color = p.textPrimary, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 4.dp))
        Slider(
            value = value,
            onValueChange = { value = it.roundToInt().toFloat() },
            valueRange = min.toFloat()..max.toFloat(),
            onValueChangeFinished = { MapsHaptics.tap(view); onChange(value.roundToInt()) },
            modifier = Modifier.padding(bottom = 8.dp).testTag("maps:config:slider:$title"),
        )
    }

    companion object {
        const val SECTION_TRACKER = "tracker"
        const val SECTION_APIS    = "apis"
        private const val ARG_SECTION = "section"

        fun newInstance(section: String = SECTION_TRACKER) = MapsConfigFragment().apply {
            arguments = android.os.Bundle().apply { putString(ARG_SECTION, section) }
        }
    }
}
