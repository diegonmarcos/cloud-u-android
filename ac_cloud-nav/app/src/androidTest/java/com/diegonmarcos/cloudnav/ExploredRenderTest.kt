package com.diegonmarcos.cloudnav

import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diegonmarcos.cloudnav.maps.MapsDb
import com.diegonmarcos.cloudnav.maps.MapsDemo
import com.diegonmarcos.cloudnav.maps.MapsExploredFragment
import com.diegonmarcos.cloudnav.maps.MapsGeoLayers
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE Explored regression test. Explored now renders pins as a NATIVE MapLibre
 * CircleLayer (typed FeatureCollection — proven to register on this stack once
 * the string-geojson bug was fixed), and paints visited areas with FillLayers.
 * Both are GL layers, so this test observes them via the live SOURCE feature
 * counts (querySourceFeatures), the exact signal that string-geojson pins failed
 * (0 features):
 *
 *   seed 228-city demo → Timeline → Explored → poll until:
 *     • the native pin SOURCE reports > 0 features (pins render, no overlay), and
 *     • the Countries fill SOURCE reports > 0 features (choropleth renders), and
 *     • switching to PLACES yields far MORE pins than CITY (684 > 228).
 */
@RunWith(AndroidJUnit4::class)
class ExploredRenderTest {

    // The island and the strip are Compose (#868): Espresso's withText cannot see them. They are
    // driven by the lib's own tags, which also proves both render (a node that is not composed or
    // not laid out fails the click).
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun explored_renders_native_pins_and_visited_fills() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext

        // The Route map auto-locates when it becomes ready (MapsMapFragment autoLocate ->
        // recenterOnUser), which pops Android's location dialog when the permission is not
        // held. Whether that lands before the clicks below is a race on how fast the style
        // loads: when it won, MainActivity was PAUSED under GrantPermissionsActivity and the
        // test failed as NoActivityResumedException or a missing "Explored" view (run
        // 36945007932, both attempts). Granted up front, map-ready centres instead of asking.
        if (android.os.Build.VERSION.SDK_INT >= 28) // grantRuntimePermission is API 28; the CI emulator is 34
            for (p in listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                inst.uiAutomation.grantRuntimePermission(ctx.packageName, p)

        MapsDb.get(ctx).clearAll()
        MapsDemo.resetSeedFlag(ctx)
        assertTrue("demo seed must insert rows", MapsDemo.seed(ctx) > 0)
        val expectedCities = MapsDemo.cityTemplates.size

        com.diegonmarcos.cloudnav.maps.MapsTrackerPrefs(ctx).apply {
            lastFixLat = 52.5200; lastFixLon = 13.4050; lastFixTs = System.currentTimeMillis()
        }

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("bottomnav_item_timeline").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("bottomnav_item_timeline").performClick()
                compose.waitUntil(30_000) { compose.onAllNodesWithTag(PageTabsTags.tab("explored")).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag(PageTabsTags.tab("explored")).performClick()

                fun explored(): MapsExploredFragment? = run {
                    var found: MapsExploredFragment? = null
                    scenario.onActivity { act ->
                        found = act.supportFragmentManager.fragments
                            .flatMap { it.childFragmentManager.fragments }
                            .flatMap { listOf(it) + it.childFragmentManager.fragments }
                            .filterIsInstance<MapsExploredFragment>().firstOrNull()
                    }
                    found
                }

                // ── native pins + Countries fill both register on the GL stack ──
                var pinSource = -1
                var fillSource = -1
                var deadline = System.currentTimeMillis() + 90_000
                while (System.currentTimeMillis() < deadline) {
                    scenario.onActivity {
                        explored()?.let { f ->
                            pinSource = f.debugPinSourceCount()
                            fillSource = f.debugFillSourceCount(MapsGeoLayers.Layer.COUNTRIES)
                        }
                    }
                    android.util.Log.i("MapPins", "probe pinSource=$pinSource fillSource=$fillSource")
                    if (pinSource > 0 && fillSource > 0) break
                    Thread.sleep(1000)
                }
                assertTrue("native pin SOURCE must report >0 features (got $pinSource)", pinSource > 0)
                assertTrue("Countries fill SOURCE must report >0 features (got $fillSource)", fillSource > 0)

                // ── PLACES mode must render FAR more pins than CITY (684 > 228) ──
                var cityCount = -1
                var placeCount = -1
                var placePinSource = -1
                scenario.onActivity {
                    explored()?.let { f ->
                        f.debugSetMode(MapsExploredFragment.Mode.CITY); cityCount = f.debugPinModelCount()
                        f.debugSetMode(MapsExploredFragment.Mode.PLACES); placeCount = f.debugPinModelCount()
                    }
                }
                deadline = System.currentTimeMillis() + 30_000
                while (System.currentTimeMillis() < deadline) {
                    scenario.onActivity { explored()?.let { placePinSource = it.debugPinSourceCount() } }
                    android.util.Log.i("MapPins", "placesProbe city=$cityCount places=$placeCount pinSource=$placePinSource")
                    if (placePinSource > cityCount) break
                    Thread.sleep(1000)
                }
                assertTrue("PLACES ($placeCount) must be many more than CITY ($cityCount)", placeCount > cityCount * 2)
                assertTrue("native pin source in PLACES must exceed city count (got $placePinSource)", placePinSource > cityCount)

                // Timeline map defaults to vector-light (style "light").
                var mapStyle: String? = null
                scenario.onActivity {
                    mapStyle = explored()?.childFragmentManager?.fragments
                        ?.filterIsInstance<com.diegonmarcos.cloudnav.maps.MapsMapFragment>()
                        ?.firstOrNull()?.arguments?.getString("style")
                }
                assertTrue("Timeline map must default to light (got $mapStyle)", mapStyle == "light")
            }
        } finally {
            MapsDb.get(ctx).clearAll()
            MapsDemo.resetSeedFlag(ctx)
        }
    }
}
