package com.diegonmarcos.cloudnav.configs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudnav.maps.MapsConfigFragment
import com.diegonmarcos.cloudnav.NavConfig
import com.diegonmarcos.cloudnav.maps.MapsPageStrip
import com.diegonmarcos.superapp.bottomnav.PageTabsView

/**
 * Configs tab. Six sub-pages on a PageTabsView strip (#868: build.json ui.sections[configs].pages):
 *   • Tracker — GPS tracker controls + calibration + export
 *               ([MapsConfigFragment] section=tracker).
 *   • APIs    — Search / Reverse-geocoder / POI provider pickers + API keys
 *               ([MapsConfigFragment] section=apis).
 *   • Update  — the in-app GHCR self-updater ([UpdateConfigFragment]).
 *   • Cache   — real per-mechanism cache size + clear
 *               ([CacheConfigFragment]).
 *   • Layers  — per-layer visual settings, e.g. Terrain exaggeration
 *               ([LayersConfigFragment]).
 *   • About   — the extensive device/app/permissions/battery/memory/network
 *               page ([DevControlFragment], ported from Cloud SuperApp).
 *
 * A settings page, not a search surface — no search bar.
 */
class ConfigsFragment : Fragment() {

    private lateinit var container: FrameLayout

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val col = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val pages = NavConfig.decl.section("configs")?.pages.orEmpty()
        val tabs: PageTabsView = MapsPageStrip.create(requireContext(), pages, pages.firstOrNull()?.id) { show(it.id) }
        this.container = FrameLayout(requireContext()).apply { id = View.generateViewId() }
        col.addView(tabs, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(this.container, LinearLayout.LayoutParams(MATCH, MATCH))

        if (savedInstanceState == null) show(pages.firstOrNull()?.id ?: PAGE_TRACKER)
        return col
    }

    private fun show(id: String) {
        val frag: Fragment = when (id) {
            PAGE_TRACKER -> MapsConfigFragment.newInstance(MapsConfigFragment.SECTION_TRACKER)
            "apis" -> MapsConfigFragment.newInstance(MapsConfigFragment.SECTION_APIS)
            "update" -> UpdateConfigFragment()
            "cache" -> CacheConfigFragment()
            "layers" -> LayersConfigFragment()
            else -> DevControlFragment()
        }
        childFragmentManager.beginTransaction()
            .replace(container.id, frag)
            .commit()
    }

    private companion object {
        const val PAGE_TRACKER = "tracker"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
