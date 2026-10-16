package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.apps.SuitePhoneAppsBody
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.PageTabs
import com.diegonmarcos.superapp.search.SearchEntry
import com.diegonmarcos.superapp.search.SearchFrame
import com.diegonmarcos.superapp.search.SearchSession
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
// libs:browser moved to ac_cloud-browser — no browser imports here.

/**
 * Android-launcher-style "all apps" drawer revealed by pulling up from
 * [com.diegonmarcos.superapp.launcher.themes.cloud.Home3DFragment]. Content, top to bottom:
 *   • the Cloud | Phone pills (build.json::ui.home_apps_tabs);
 *   • the SuperApp's one search ([SearchSession], entry [SearchEntry.HOME_SHEET]);
 *   • the body: Cloud ▸ Apps' own page ([GroupedTilesPage] "cloud") or Phone ▸ Apps' ([SuitePhoneAppsBody]) — the
 *     same two pages the Cloud and Phone sections show.
 * Pull-down (or back press) closes the sheet and restores the 3D cube.
 *
 * Compose since #773: the two bodies are composables swapped in place (no child fragments). Re-opening the sheet
 * always lands on the requested tab (default the first, Cloud), matching the user's One UI muscle memory.
 */
class AppDrawerSheetFragment : KitComposeFragment(), BackHandler {

    private var search: SearchSession? = null

    /** Back with a query clears it before Back closes the sheet. */
    override fun tryHandleBack(): Boolean = search?.handleBack() == true

    override fun onDestroyView() {
        super.onDestroyView()
        search = null
    }

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val view = LocalView.current
        val tabs = remember { HomeAppsTabs.loadFromBuildConfig() }
        val requested = arguments?.getString(ARG_INITIAL_TAB)?.takeIf { it.isNotBlank() } ?: tabs.firstOrNull()?.id ?: "cloud"
        val first = tabs.getOrNull(tabs.indexOfFirst { it.id == requested }.coerceAtLeast(0))?.id ?: "cloud"
        var selected by rememberSaveable { mutableStateOf(first) }
        // The shared pill strip (libs:bottomnav). Not under the toolbar island — this strip lives in a sheet — so
        // it takes the base geometry without the live top inset.
        val click = { id: String -> (activity as? TileGridFragment.TileClickListener)?.onTileClicked(id); Unit }
        val session = remember { SearchSession(this, SearchEntry.HOME_SHEET).also { search = it } }
        Column(Modifier.fillMaxSize().background(LocalKitPalette.current.surface).testTag(TAG_SHEET)) {
            PageTabs(tabs.map { NavPage(it.id, it.label) }, selected,
                onSelect = { page -> selected = page.id; Haptics.tap(view) }, underTopChrome = false)
            // Search — the SuperApp's one search: the same slim bar, chips, per-type sections and engine as Cloud ▸
            // Apps and the Home star, placed between the pills and the body so it serves whichever tab is showing.
            SearchFrame(session, "Search", Modifier.fillMaxSize()) {
                when (selected) {
                    "phone" -> SuitePhoneAppsBody()
                    else    -> GroupedTilesPage("cloud", click)   // "cloud" + default
                }
            }
        }
    }

    companion object {
        /** Tag used both for the back-stack entry name and for activity-side
         *  presence detection. Don't rename without updating MainActivity. */
        const val BACK_STACK_TAG = "app_drawer"
        private const val ARG_INITIAL_TAB = "initial_tab"
        const val TAG_SHEET = "appdrawer:sheet"

        /** [initialTab] = home_apps_tabs id to open on ("cloud" | "phone").
         *  Blank/unknown → first tab (Cloud), preserving the prior default. */
        fun newInstance(initialTab: String = ""): AppDrawerSheetFragment =
            AppDrawerSheetFragment().apply {
                if (initialTab.isNotBlank()) {
                    arguments = Bundle().apply { putString(ARG_INITIAL_TAB, initialTab) }
                }
            }
    }
}
