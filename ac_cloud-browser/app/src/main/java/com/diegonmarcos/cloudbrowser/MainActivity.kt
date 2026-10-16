package com.diegonmarcos.cloudbrowser

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.fragment.app.commit
import com.diegonmarcos.superapp.bottomnav.BottomNavEntry
import com.diegonmarcos.superapp.bottomnav.BottomNavIsland
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.PageTabs
import com.diegonmarcos.superapp.browser.BrowserHostFragment
import com.diegonmarcos.superapp.browser.BrowserSearchPageHost
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitFragmentHost
import com.diegonmarcos.superapp.uikit.KitFragmentHostState
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.updater.UpdateOverlayFragment
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.Updater

/**
 * Single-activity shell for Cloud Browser. Hosts [BrowserHostFragment] full-screen.
 * Handles VIEW intents (http/https) so other apps can open links here.
 * Wires the self-updater (Updater) so the app can silently update itself from GHCR.
 *
 * [BrowserHostFragment] has no host interface, but it is NOT self-configuring:
 * libs:browser is shared by reference and deliberately ships no default
 * tabs and no engine list of its own. This activity is where Cloud
 * Browser's own content — the four first-run pinned tabs, the DuckDuckGo
 * default — crosses from build.json::ui.browser into the shared host.
 * Change those four URLs in build.json; no other app is affected.
 *
 * #868 The bottom bar is libs:bottomnav's island, fed by NavDecl from build.json::ui: Browser is
 * the host fragment's page, Tabs its tab switcher (#886), Search and Configs open the Search add-on's
 * page and the browser settings OVER it (the host's own overlays), Configs under a strip of top tabs (#900: libs:bottomnav
 * PageTabs, one per config category, declared in ui.sections[configs].pages), and the pill follows the host: it
 * goes back to Browser (or Tabs, when the switcher is showing) the moment the last overlay closes.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The shell is a Compose screen under CloudKitTheme
 * (libs:ui-kit): the host fragment sits in a KitFragmentHost and the island is libs:bottomnav's
 * BottomNavIsland composable.
 */
class MainActivity : AppCompatActivity() {

    /** The island's selected item (null = none); the host moves it through [BrowserHostFragment.onOverlaysClosed]. */
    private var selectedId by mutableStateOf<String?>(null)
    /** #900 the Configs top tab showing (a ui.sections[configs].pages id), while Configs is the island's item. */
    private var configsPage by mutableStateOf<String?>(null)
    private val decl: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The host fragment is re-added by KitFragmentHost once Compose has a container for it.
        super.onCreate(KitFragmentHostState.withoutFragments(savedInstanceState))
        FleetChrome.apply(this)
        supportFragmentManager.addFragmentOnAttachListener { _, f ->
            // The pill follows the host: Tabs while the tab switcher is what is showing, else Browser.
            if (f is BrowserHostFragment) f.onOverlaysClosed = {
                selectedId = decl.section(if (f.isTabsOpen) "tabs" else "browser")?.id
            }
        }
        selectedId = decl.default()?.id
        // A link that started this activity opens once; a recreation brings the browser back on its own tabs.
        val openUrl = if (savedInstanceState == null) intent?.dataString?.takeIf { it.isNotBlank() } else null
        setContent {
            val palette = KitPalette(
                surface = colorResource(R.color.browser_surface),
                surfaceSelected = colorResource(R.color.browser_selected),
                textPrimary = colorResource(R.color.browser_text),
                textSecondary = colorResource(R.color.browser_text_dim),
                accent = colorResource(R.color.browser_accent),
                hairline = colorResource(R.color.browser_hairline),
                tileInk = colorResource(R.color.browser_ink),
            )
            CloudKitTheme(palette) {
                // While the keyboard is up the island would sit between it and the page, taking the room the
                // address suggestions need; it comes back the moment the keyboard goes.
                val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                Column(
                    Modifier.fillMaxSize().background(colorResource(R.color.browser_bg))
                        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .imePadding(),
                ) {
                    // #900 Configs is a section of top tabs (one per config category), sideways-scrolling when they do not fit.
                    if (selectedId == "configs") {
                        val tabs = remember { decl.section("configs")?.pages.orEmpty() }
                        PageTabs(tabs, configsPage, onSelect = { pg -> openConfigsPage(pg.id) }, underTopChrome = false)
                    }
                    KitFragmentHost(R.id.fragment_container, Modifier.weight(1f).fillMaxWidth()) {
                        BrowserHostFragment.newInstance(openUrl, BuildConfig.UI_BROWSER_CONFIG_B64)
                    }
                    if (!imeVisible) Island()
                }
            }
        }

        Updater.start(this)
        UpdateProgress.setListener { state ->
            runOnUiThread { handleUpdateState(state) }
        }
    }

    /**
     * The island: ui.bottom_nav through [NavDecl], the icon of a section its `ic_nav_<icon>`
     * drawable. A tap on Search / Configs asks the live host to draw that page over itself; a tap
     * on Browser closes whatever is open. The island's look is the lib's.
     */
    @androidx.compose.runtime.Composable
    private fun Island() {
        val items = remember {
            decl.viewItems { icon ->
                @Suppress("DiscouragedApi")
                resources.getIdentifier("ic_nav_$icon", "drawable", packageName)
            }
        }
        val entries = items.map { BottomNavEntry(it.id, it.label, painterResource(it.icon)) }
        BottomNavIsland(
            entries = entries,
            selectedId = selectedId,
            onSelect = { e -> if (e.id != selectedId || e.id != decl.default()?.id) openSection(e.id) },
        )
    }

    private fun openSection(id: String) {
        val host = supportFragmentManager.findFragmentById(R.id.fragment_container) as? BrowserHostFragment ?: return
        host.dismissOverlays()
        when (id) {
            // The page (or a new tab when none is open) / the tab switcher: the host's own screens.
            "browser" -> host.openBrowser()
            "tabs" -> host.openTabs()
            // Fav: the bookmarks (list or icon grid) over the page.
            "favourites" -> host.openFavourites()
            "search" -> {
                host.openSearch()
                // No Search page installed: the host only says so, nothing opened, the pill stays.
                if (BrowserSearchPageHost.page == null) return
            }
            "configs" -> {
                configsPage = decl.section("configs")?.pages?.firstOrNull()?.id
                host.openSettings()
            }
        }
        // closeOverlays reset the pill to Browser; an overlay that opened puts it on its own item.
        selectedId = id
    }

    /** A Configs top tab was picked: the host swaps the page under it. */
    private fun openConfigsPage(id: String) {
        val host = supportFragmentManager.findFragmentById(R.id.fragment_container) as? BrowserHostFragment ?: return
        configsPage = id
        host.openConfigsPage(id)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // VIEW intent from another app: replace fragment with the new URL.
        // BrowserHostFragment takes its URL via newInstance args only.
        val url = intent.dataString?.takeIf { it.isNotBlank() } ?: return
        supportFragmentManager.commit {
            replace(
                R.id.fragment_container,
                BrowserHostFragment.newInstance(url, BuildConfig.UI_BROWSER_CONFIG_B64),
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        UpdateProgress.setListener(null)
    }

    private fun handleUpdateState(state: UpdateProgress.State) {
        val tag = "update_overlay"
        val frag = supportFragmentManager.findFragmentByTag(tag)
        // An unattended pass, or a manual one the user minimized, draws
        // nothing. Failed states are never suppressed.
        if (UpdateProgress.suppressed(this, state)) {
            frag?.let { supportFragmentManager.commit(allowStateLoss = true) { remove(it) } }
            return
        }
        when (state) {
            is UpdateProgress.State.Idle -> {
                frag?.let { supportFragmentManager.commit { remove(it) } }
            }
            else -> {
                if (frag == null) {
                    supportFragmentManager.commit {
                        add(android.R.id.content, UpdateOverlayFragment.newInstance(), tag)
                    }
                } else {
                    (frag as? UpdateOverlayFragment)?.applyState(state)
                }
            }
        }
    }
}
