package com.diegonmarcos.cloudbrowser

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.fragment.app.commit
import com.diegonmarcos.superapp.bottomnav.BottomNavIslandView
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.browser.BrowserHostFragment
import com.diegonmarcos.superapp.browser.BrowserSearchPageHost
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
 * the host fragment, Search and Configs open the Search add-on's page and the browser settings
 * OVER it (the host's own overlays), and the pill follows the host: it goes back to Browser the
 * moment the last overlay closes.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavIslandView
    private val decl: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        setContentView(R.layout.activity_main)
        supportFragmentManager.addFragmentOnAttachListener { _, f ->
            if (f is BrowserHostFragment) f.onOverlaysClosed = { bottomNav.selectedId = decl.section("browser")?.id }
        }
        buildBottomNav()

        if (savedInstanceState == null) {
            val openUrl = intent?.dataString?.takeIf { it.isNotBlank() }
            supportFragmentManager.commit {
                replace(
                    R.id.fragment_container,
                    BrowserHostFragment.newInstance(openUrl, BuildConfig.UI_BROWSER_CONFIG_B64),
                )
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
    private fun buildBottomNav() {
        bottomNav = ActivityCompat.requireViewById(this, R.id.bottom_nav)
        bottomNav.items = decl.viewItems { icon ->
            @Suppress("DiscouragedApi")
            resources.getIdentifier("ic_nav_$icon", "drawable", packageName)
        }
        bottomNav.selectedId = decl.default()?.id
        bottomNav.onSelect = { id -> openSection(id) }
        bottomNav.onReselect = { id -> if (id != decl.default()?.id) openSection(id) }
    }

    private fun openSection(id: String) {
        val host = supportFragmentManager.findFragmentById(R.id.fragment_container) as? BrowserHostFragment ?: return
        host.dismissOverlays()
        when (id) {
            "search" -> {
                host.openSearch()
                // No Search page installed: the host only says so, nothing opened, the pill stays.
                if (BrowserSearchPageHost.page == null) return
            }
            "configs" -> host.openSettings()
        }
        // closeOverlays reset the pill to Browser; an overlay that opened puts it on its own item.
        bottomNav.selectedId = id
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
