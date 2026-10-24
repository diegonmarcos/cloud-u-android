package com.diegonmarcos.cloudnav.configs

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudnav.FragmentHost
import com.diegonmarcos.cloudnav.NavConfig
import com.diegonmarcos.cloudnav.R
import com.diegonmarcos.cloudnav.maps.MapsConfigFragment
import com.diegonmarcos.cloudnav.maps.MapsPageStrip
import com.diegonmarcos.cloudnav.tracker.TrackerEngineFragment
import com.diegonmarcos.cloudnav.tracker.TrackerInk
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRowView
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette

/**
 * Configs tab. Six sub-pages on a NavBarTopSecondRowView strip (#868: build.json ui.sections[configs].pages):
 *   • Tracker — the tracking engine ([TrackerEngineFragment]); its "Foreground tracker, calibration and
 *               export" button opens libs:maps' tracker controls ([MapsConfigFragment] section=tracker).
 *   • APIs    — Search / Reverse-geocoder / POI provider pickers + API keys
 *               ([MapsConfigFragment] section=apis).
 *   • Update  — the in-app GHCR self-updater ([UpdateConfigFragment]).
 *   • Cache   — real per-mechanism cache size + clear ([CacheConfigFragment]).
 *   • Layers  — per-layer visual settings, e.g. Terrain exaggeration ([LayersConfigFragment]).
 *   • About   — the device/app/permissions/battery/memory/network page ([DevControlFragment]).
 *
 * Compose (the strip is NavBarTopSecondRowView through AndroidView; the pages are child fragments). A settings
 * page, not a search surface — no search bar.
 */
class ConfigsFragment : KitComposeFragment() {
    private var current by mutableStateOf("")
    private var nonce by mutableIntStateOf(0)
    private var legacyTracker by mutableStateOf(false)

    override fun palette(): KitPalette = TrackerInk.palette()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = NavConfig.decl.section("configs")?.pages.orEmpty().firstOrNull()?.id ?: PAGE_TRACKER
    }

    /** The engine page's button: libs:maps' foreground tracker controls in place of the engine page. */
    fun showLegacyTracker() { legacyTracker = true; nonce++ }

    @Composable
    override fun Content() {
        val pages = remember { NavConfig.decl.section("configs")?.pages.orEmpty() }
        Column(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { c ->
                    MapsPageStrip.create(c, pages, current) { page -> legacyTracker = false; current = page.id; nonce++ }
                },
                update = { tabs: NavBarTopSecondRowView -> if (tabs.selectedId != current) tabs.selectedId = current },
            )
            FragmentHost(childFragmentManager, R.id.nav_host_configs, Triple(current, nonce, legacyTracker), Modifier.weight(1f)) { pageFragment(current) }
        }
    }

    private fun pageFragment(id: String): Fragment = when (id) {
        PAGE_TRACKER -> if (legacyTracker) MapsConfigFragment.newInstance(MapsConfigFragment.SECTION_TRACKER) else TrackerEngineFragment()
        "apis" -> MapsConfigFragment.newInstance(MapsConfigFragment.SECTION_APIS)
        "update" -> UpdateConfigFragment()
        "cache" -> CacheConfigFragment()
        "layers" -> LayersConfigFragment()
        else -> DevControlFragment()
    }

    private companion object {
        const val PAGE_TRACKER = "tracker"
    }
}
