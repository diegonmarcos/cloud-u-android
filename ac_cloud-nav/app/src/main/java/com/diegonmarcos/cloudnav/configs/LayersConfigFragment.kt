package com.diegonmarcos.cloudnav.configs

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.cloudnav.maps.MapsLayerPrefs
import com.diegonmarcos.cloudnav.tracker.TrackerInk
import com.diegonmarcos.cloudnav.tracker.TrackerPage
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlin.math.roundToInt

/**
 * Layers tab: per-layer visual settings, one section per layer. Currently
 * just Terrain's exaggeration, since that's the only layer with a tunable
 * knob today — [MapsLayerPrefs] is the native, persisted source of truth,
 * and the Terrain view pushes/reads this same value into its WebView page
 * (see terrain_map.html's `AndroidBridge` wiring) so this tab and the
 * in-view slider never drift out of sync.
 */
class LayersConfigFragment : KitComposeFragment() {
    override fun palette(): KitPalette = TrackerInk.palette()

    @Composable
    override fun Content() {
        val prefs = MapsLayerPrefs(LocalContext.current)
        TrackerPage {
            KitSectionHeader("Layers", "Per-layer visual settings. Changes apply immediately, including live inside an already-open view.")
            KitSectionHeader("Terrain", "3D mountain relief exaggeration in the Terrain view (Map style menu > Terrain).")
            KitCard {
                LabelledSlider(LayersTags.TERRAIN, "Exaggeration", prefs.terrainExaggeration, 1, 100, "x") { prefs.terrainExaggeration = it }
            }
        }
    }
}

object LayersTags {
    const val TERRAIN = "layers:terrain"
    fun label(tag: String) = "$tag:label"
}

/** "title = 12x" over a slider; [onChange] fires when the finger lifts, the label follows the drag. */
@Composable
internal fun LabelledSlider(tag: String, title: String, current: Int, min: Int, max: Int, unit: String, onChange: (Int) -> Unit) {
    val p = LocalKitPalette.current
    var v by remember { mutableFloatStateOf(current.coerceIn(min, max).toFloat()) }
    Text("$title = ${v.roundToInt()}$unit", Modifier.testTag(LayersTags.label(tag)), color = p.textPrimary, style = MaterialTheme.typography.bodyLarge)
    Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { onChange(v.roundToInt()) },
        valueRange = min.toFloat()..max.toFloat(), modifier = Modifier.testTag(tag))
}
