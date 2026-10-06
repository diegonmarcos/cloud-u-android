package com.diegonmarcos.cloudnav

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudnav.configs.ConfigsFragment
import com.diegonmarcos.cloudnav.maps.MapsTimelineTabsFragment
import com.diegonmarcos.superapp.updater.Updater
import com.diegonmarcos.cloudnav.places.PlacesFragment
import com.diegonmarcos.cloudnav.routes.NavigationFragment
import com.diegonmarcos.cloudnav.routes.RoutesFragment
import com.diegonmarcos.superapp.bottomnav.BottomNavIslandView
import com.diegonmarcos.superapp.bottomnav.FleetChrome

/**
 * Cloud Nav shell — minimal Google-Maps-style chrome:
 *   • a content fragment container (each page draws its own search UI)
 *   • bottom nav island (libs:bottomnav): Routes · Navigation · Places · Timeline · Configs
 *
 * Sections + default section are data-driven from build.json::ui.* (decoded
 * by [NavConfig.decl], libs:bottomnav's NavDecl). The shell deliberately owns NO search bar — Routes does
 * multi-stop routing, Navigation a destination field, Places a simple POI
 * lookup with category islands. Each fragment renders the search UI it needs.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var content: View
    private lateinit var bottomNav: BottomNavIslandView
    private var currentTab: String = ""
    /** A geo: location handed in by another app, consumed by the Places tab on
     *  its next creation (see [fragmentForSection]). */
    private var pendingGeo: GeoTarget? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        setContentView(R.layout.activity_main)

        content = findViewById(R.id.content)
        bottomNav = findViewById(R.id.bottom_nav)

        applyInsets()
        buildBottomNav()

        // Schedule the periodic GHCR self-update check (data-driven cadence).
        // The Update tab in Configs offers a manual check-now button.
        Updater.start(this)

        if (savedInstanceState == null) {
            // A geo: launch (Cloud Nav declared as a maps app) opens straight on
            // the Places map at the requested location; otherwise the default tab.
            val geo = geoTargetOf(intent)
            if (geo != null) { pendingGeo = geo; switchTo(TAB_PLACES) }
            else switchTo(defaultSection())
        } else {
            currentTab = savedInstanceState.getString(KEY_TAB, defaultSection())
            syncBottomNav(currentTab)
        }
    }

    /** A geo: intent arriving while the app is already running (singleTop) —
     *  re-route to the Places map at the new location. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val geo = geoTargetOf(intent) ?: return
        pendingGeo = geo
        switchTo(TAB_PLACES)
    }

    private fun geoTargetOf(intent: Intent?): GeoTarget? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        return GeoUri.parse(intent.data?.toString())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, currentTab)
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.updatePadding(top = bars.top)
            insets
        }
    }

    /** The island is libs:bottomnav's, fed by NavDecl (#868). The app only says which fragment a
     *  section opens; the island's look is the lib's, the same in every app. */
    private fun buildBottomNav() {
        bottomNav.items = NavConfig.decl.viewItems { Icons.nav(this, it) }
        bottomNav.collapseOnScrollIn(content as ViewGroup)
        bottomNav.onSelect = { id -> if (id != currentTab) switchTo(id) }
    }

    private fun defaultSection(): String = NavConfig.decl.default()?.id ?: "routes"

    private fun switchTo(tabId: String) {
        currentTab = tabId
        supportFragmentManager.beginTransaction()
            .replace(R.id.content, fragmentForSection(tabId))
            .commit()
        syncBottomNav(tabId)
    }

    private fun fragmentForSection(id: String): Fragment = when (id) {
        "routes"     -> RoutesFragment()
        "navigation" -> NavigationFragment()
        // Consume a pending geo: location once, so a hand-off from another app
        // lands on the map at that point / runs that search.
        "places"     -> pendingGeo?.let { g -> pendingGeo = null; PlacesFragment.forGeo(g) } ?: PlacesFragment()
        "timeline"   -> MapsTimelineTabsFragment.newInstance(NavConfig.decl.section("timeline")?.pages.orEmpty())
        "configs"    -> ConfigsFragment()
        else         -> RoutesFragment()
    }

    private fun syncBottomNav(sectionId: String) {
        bottomNav.selectedId = sectionId
    }

    private companion object {
        const val KEY_TAB = "cloud_nav_tab"
        const val TAB_PLACES = "places"
    }
}
