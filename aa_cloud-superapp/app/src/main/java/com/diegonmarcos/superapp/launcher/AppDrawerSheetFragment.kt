package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.App
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.apps.SuitePhoneAppsFragment
import com.diegonmarcos.superapp.search.InlineSearch
import com.diegonmarcos.superapp.search.SearchEntry

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.launcher.themes.cloud.Home3DFragment
// libs:browser moved to ac_cloud-browser — no browser imports here.

/**
 * Android-launcher-style "all apps" drawer revealed by pulling up from
 * [Home3DFragment]. Content, top to bottom:
 *   • the Cloud | Phone pills (build.json::ui.home_apps_tabs);
 *   • the SuperApp's one search ([InlineSearch], entry [SearchEntry.HOME_SHEET]);
 *   • the body: Cloud ▸ Apps' own page (GroupedTilesFragment "cloud") or Phone ▸ Apps'
 *     (SuitePhoneAppsFragment) — the same two pages the Cloud and Phone sections show.
 * Pull-down (or back press) closes the sheet and restores the 3D cube.
 */
class AppDrawerSheetFragment : Fragment(), BackHandler {

    private var search: InlineSearch? = null

    /** Back with a query clears it before Back closes the sheet. */
    override fun tryHandleBack(): Boolean = search?.handleBack() == true

    override fun onDestroyView() {
        super.onDestroyView()
        search = null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = androidx.core.content.ContextCompat.getDrawable(
                ctx, R.drawable.bg_gradient_black_purple)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // ── Search — the SuperApp's one search (search/SearchPanel): the same slim bar, chips,
        //    per-type sections and engine as Cloud ▸ Apps and the Home star, placed between the
        //    Cloud | Phone pills and the body so it serves whichever tab is showing. Its results
        //    drop over the body on the opaque theme surface. Mounted below, around the body host.

        // Browser-tab chip strip removed — tabs live in Cloud-Browser (ac_cloud-browser).

        // ── Body tabs (Cloud | Phone) — data-driven from build.json::ui.home_apps_tabs.
        // Cloud = GroupedTilesFragment("cloud") and Phone =
        // SuitePhoneAppsFragment — the same merged Quickmarks + All Apps +
        // Smart Folders pages the Suite section uses, so the swipe-up
        // drawer and Suite tab show identical content instead of the
        // Suite tab being "the new one page" and the drawer staying on
        // the old bare Home/Phone grids.
        // Re-opening the sheet always lands on the first tab (Cloud),
        // matching the user's One UI muscle memory.
        val tabs = HomeAppsTabs.loadFromBuildConfig()
        // The shared pill strip (libs:bottomnav). Not under the toolbar island — this strip lives
        // in a sheet — so it takes the base geometry without the live top inset.
        val bodyTabs = com.diegonmarcos.superapp.bottomnav.PageTabsView(ctx).apply {
            pages = tabs.map { com.diegonmarcos.superapp.bottomnav.NavPage(it.id, it.label) }
            underTopChrome = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val host = FrameLayout(ctx).apply {
            id = R.id.app_drawer_grid_host
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        // ── Final mount order (top → bottom):
        //   1. Cloud | Phone PageTabsView — pick surface first.
        //   2. Search bar — the shared one, its dropdown layered over 3.
        //   3. Body host — GroupedTilesFragment("cloud") or SuitePhoneAppsFragment.
        root.addView(bodyTabs)
        val inline = InlineSearch(this, SearchEntry.HOME_SHEET, "Search")
        search = inline
        root.addView(GroupedTilesFragment.mountSearch(inline, host), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        fun showTab(id: String) {
            val frag: androidx.fragment.app.Fragment = when (id) {
                "phone" -> SuitePhoneAppsFragment.newInstance()
                else    -> GroupedTilesFragment.newInstance("cloud")  // "cloud" + default
            }
            childFragmentManager.beginTransaction()
                .replace(host.id, frag)
                .commit()
        }

        bodyTabs.onSelect = { page ->
            bodyTabs.selectedId = page.id
            Haptics.tap(bodyTabs)
            showTab(page.id)
        }
        bodyTabs.selectedId = tabs.firstOrNull()?.id   // a restored sheet keeps its body, so the pill starts on the first tab

        if (s == null && childFragmentManager.findFragmentById(host.id) == null) {
            // Land on the requested tab (default = first / Cloud). Set the body directly and
            // move the pill with it: selectedId never calls back, so the two cannot disagree.
            val requested = arguments?.getString(ARG_INITIAL_TAB)?.takeIf { it.isNotBlank() }
                ?: tabs.firstOrNull()?.id ?: "cloud"
            val idx = tabs.indexOfFirst { it.id == requested }.coerceAtLeast(0)
            showTab(tabs.getOrNull(idx)?.id ?: "cloud")
            bodyTabs.selectedId = tabs.getOrNull(idx)?.id
        }
        return root
    }


    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Tag used both for the back-stack entry name and for activity-side
         *  presence detection. Don't rename without updating MainActivity. */
        const val BACK_STACK_TAG = "app_drawer"
        private const val ARG_INITIAL_TAB = "initial_tab"

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
