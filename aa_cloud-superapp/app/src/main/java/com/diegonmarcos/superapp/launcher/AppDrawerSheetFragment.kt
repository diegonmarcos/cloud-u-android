package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.App
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.ui.ShimmerBorderView
import com.diegonmarcos.superapp.apps.SuitePhoneAppsFragment
import com.diegonmarcos.superapp.search.SearchOpener

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.launcher.themes.cloud.Home3DFragment
// libs:browser moved to ac_cloud-browser — no browser imports here.

/**
 * Android-launcher-style "all apps" drawer revealed by pulling up from
 * [Home3DFragment]. Content:
 *   • Top spacer — double the gap to the activity-level search-bar island
 *     above (user feedback: needed breathing room).
 *   • TileGrid of every section + home actions below.
 * Pull-down (or back press) closes the sheet and restores the 3D cube.
 */
class AppDrawerSheetFragment : Fragment() {

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

        // ── Search bar — glassmorphism + shimmer chrome reused from the
        //    old activity toolbar. Same fixed-height pattern the original
        //    toolbar used (?attr/actionBarSize on both children) so the
        //    wrap_content FrameLayout doesn't enter a measure cycle and
        //    blow up to fill the whole screen.
        val barHeight = dp(56)
        val searchIsland = FrameLayout(ctx).apply {
            background = androidx.core.content.ContextCompat.getDrawable(
                ctx, R.drawable.bg_liquid_glass)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                barHeight,
            ).apply { setMargins(dp(12), dp(6), dp(12), dp(10)) }
            isClickable = true; isFocusable = true
        }
        val searchInner = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val hpad = dp(16)
            setPadding(hpad, 0, hpad, 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                barHeight,
            )
        }
        // App icon — LEFT. Uses ic_launcher_foreground (the unified icon reference)
        // with an explicit black oval background so it matches the launcher icon exactly.
        searchInner.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF0A0A0A.toInt())
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val sz = dp(24)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = dp(12) }
        })
        // Search placeholder — CENTRE (weighted so it takes the gap).
        searchInner.addView(TextView(ctx).apply {
            text = "Search"
            setTextColor(0x99FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
        })
        // AI sparkle (Gemini-style 4-point star + accent) — RIGHT.
        searchInner.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_ai_sparkle)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFE9D8FD.toInt())
            val sz = dp(20)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginStart = dp(12) }
        })
        searchIsland.addView(searchInner)
        searchIsland.addView(ShimmerBorderView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                barHeight,
            )
        })
        searchIsland.setOnClickListener {
            Haptics.tap(it)
            (activity as? SearchOpener)?.openSearchSheet()
        }
        // addView deferred — bodyTabs (Cloud | Phone) goes ABOVE this so
        // the user picks the surface first, then the shared search +
        // chip strip apply to whichever tab is active.

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
        //   2. Search island — shared chrome.
        //   3. Body host — HomeGroupedFragment or PhoneAppsFragment.
        root.addView(bodyTabs)
        root.addView(searchIsland)
        root.addView(host)

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
