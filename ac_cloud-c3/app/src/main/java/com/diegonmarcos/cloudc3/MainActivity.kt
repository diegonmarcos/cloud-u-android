package com.diegonmarcos.cloudc3

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.diegonmarcos.cloudc3.cloud.C3HealthFragment
import com.diegonmarcos.cloudc3.cloud.C3MeshFragment
import com.diegonmarcos.cloudc3.cloud.C3StackFragment
import com.diegonmarcos.cloudc3.pages.AppsFragment
import com.diegonmarcos.cloudc3.pages.C3FragmentHost
import com.diegonmarcos.cloudc3.pages.ConfigsFragment
import com.diegonmarcos.cloudc3.pages.HomeFragment
import com.diegonmarcos.cloudc3.pages.ObservFragment
import com.diegonmarcos.cloudc3.pages.TopologyFragment
import com.diegonmarcos.cloudc3.pages.c3KitPalette
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitFragmentHostState

/**
 * #648 the app's one Activity: a FRAGMENT HOST above the fleet's bottom-nav island.
 *
 * The pages are the SuperApp's C3 pages and they are Fragments, so the shell hosts
 * fragments rather than asking them to become something else.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The shell is a Compose screen under
 * CloudKitTheme: the tab's fragment sits in a [C3FragmentHost] and the island is libs:bottomnav's
 * BottomNavIsland composable (through [C3BottomNav]).
 *
 * THE TOP-OVERFLOW FIX (#407/#477). Content was drawing under the status bar and the
 * camera cutout. The fix reads the REAL inset and pads with it:
 *
 *  - [FleetChrome.apply] means this window lays out behind the system bars, which is what
 *    lets the island sit against the bottom edge.
 *  - the content column pads with systemBars UNION displayCutout, because on this device the
 *    cutout is taller than the status bar and either one alone leaves the other clipped
 *    (#407 replaced a tuned margin with exactly this reading).
 *  - it pads the top and the sides ONLY, so the bottom inset is left for the island, which
 *    reads it itself. Consuming it is the #477 bug: a strip that consumed the inset starved
 *    every view below it.
 *  - there is no hardcoded top margin. Nothing here is tuned to a device.
 */
class MainActivity : AppCompatActivity(),
    C3HealthFragment.UrlClickListener,
    C3StackFragment.TargetListener {

    private var currentTab by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The tab's fragment is re-added by the Compose host once it has a container for it.
        super.onCreate(KitFragmentHostState.withoutFragments(savedInstanceState))
        FleetChrome.apply(this)

        val restored = savedInstanceState?.getString(STATE_TAB)
        val initial = restored?.takeIf { id -> Declarations.tabs.any { it.id == id } }
            ?: Declarations.defaultTab.takeIf { d -> Declarations.tabs.any { it.id == d } }
            ?: Declarations.tabs.firstOrNull()?.id
        initial?.let { show(it) }
        setContent { CloudKitTheme(c3KitPalette(this)) { Shell() } }
        if (savedInstanceState == null) handleDeepLink(intent)
    }

    @Composable
    private fun Shell() {
        Column(
            Modifier.fillMaxSize().background(colorResource(R.color.c3_background))
                // READ, never consume (#477): top and sides only; the island takes the bottom.
                .windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ),
        ) {
            // The page. Every tab replaces this one container, the way the SuperApp's shell
            // does, so a page arrives as the Fragment it already is.
            val tab = currentTab
            if (tab != null) {
                C3FragmentHost(
                    fm = supportFragmentManager,
                    containerId = R.id.fragment_container,
                    key = tab,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { fragmentFor(tab) ?: Fragment() }
            }
            // The fleet's ONE bottom nav (#531/#565): the same Compose island the SuperApp,
            // mail, wallet, me and drive draw. Below the content so nothing a page draws can
            // hide under it. C3BottomNav feeds it build.json::ui.bottom_nav.
            C3BottomNav.Island(selectedId = tab, onOpen = { show(it) })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * The fleet's launcher deep-link contract (the string extra [EXTRA_TARGET], same name the
     * SuperApp's shortcuts use): a sibling app opens a page here by sending its `page:` id, e.g.
     * Cloud Mail's Home "Workflows" sends `page:c3/dagu`. The target goes through the same seam
     * a stack tile uses, so an id this app does not ship is stated, not ignored.
     */
    private fun handleDeepLink(intent: Intent?) {
        val target = intent?.getStringExtra(EXTRA_TARGET)?.takeIf { it.startsWith("page:") } ?: return
        intent.removeExtra(EXTRA_TARGET)
        onTargetClicked(target)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, currentTab)
    }

    /** Open a tab: the island's pill moves with [currentTab] and the host swaps the page in. */
    private fun show(tabId: String) {
        if (tabId == currentTab) return
        currentTab = tabId
    }

    /**
     * The tab's page. THE ONE PLACE Kotlin names a tab id — the
     * tester diffs this dispatch against build.json::ui.bottom_nav in BOTH directions, so a
     * declared tab with no page and a page no declaration reaches are each a build
     * failure. There is deliberately no companion list of these ids.
     */
    private fun fragmentFor(tabId: String): Fragment? {
        val fragment = when (tabId) {
            "topology" -> TopologyFragment()
            "observ" -> ObservFragment()
            "home" -> HomeFragment()
            "apps" -> AppsFragment()
            "configs" -> ConfigsFragment()
            else -> return null
        }
        return fragment
    }

    /** A service row's own web UI. The estate tables are the one place this happens. */
    override fun onUrlClicked(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    /**
     * #648 the stack pages' navigation seam — the role the SuperApp's tile dispatcher
     * played, sized to the targets the carried c3 stacks actually declare:
     *
     *   http(s)://…       → the system browser, same as a health row's tap.
     *   extapp:<id>       → launch the sibling APK ui.external_apps names; a missing app
     *                       is stated, never a tap that does nothing.
     *   page:<sec>/<id>   → the page's own fragment where one exists in this app, pushed
     *                       full-screen with Back returning to the stack that sent it.
     *                       page:c3/health, page:c3/dagu and page:wg/status ARE here; the
     *                       SuperApp's sample-stub pages (reports, stack, workflows, vms,
     *                       logs, gha) are not shipped in this app and the tap SAYS SO —
     *                       the same honest verdict everywhere else on these pages.
     */
    override fun onTargetClicked(target: String) {
        when {
            target.isBlank() -> Unit
            target.startsWith("http") -> onUrlClicked(target)
            target.startsWith("extapp:") -> {
                val id = target.removePrefix("extapp:").substringBefore('#').substringBefore('/')
                val app = Declarations.externalApps.firstOrNull { it.id == id }
                val intent = app?.let {
                    runCatching { packageManager.getLaunchIntentForPackage(it.packageName) }.getOrNull()
                }
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { startActivity(intent) }
                } else {
                    Toast.makeText(this,
                        getString(R.string.apps_launch_failed, app?.display ?: id),
                        Toast.LENGTH_SHORT).show()
                }
            }
            target.startsWith("page:") -> {
                // The page id, its `#anchor` suffix dropped: the three pages this app
                // ships carry no in-page anchors of their own.
                val page = target.removePrefix("page:").substringBefore('#')
                val fragment: Fragment? = when (page) {
                    "c3/health", "observ/health" -> C3HealthFragment.newInstance(C3HealthFragment.SCOPE_ALL)
                    "c3/dagu", "observ/dagu" -> com.diegonmarcos.superapp.ops.dagu.DaguFragment.newInstance()
                    "wg/status", "observ/mesh" -> C3MeshFragment.newInstance()
                    else        -> null
                }
                if (fragment != null) {
                    supportFragmentManager.commit {
                        setReorderingAllowed(true)
                        add(R.id.fragment_container, fragment)
                        addToBackStack(target)
                    }
                } else {
                    Toast.makeText(this,
                        getString(R.string.stack_page_not_here, target),
                        Toast.LENGTH_SHORT).show()
                }
            }
            else -> Toast.makeText(this,
                getString(R.string.stack_page_not_here, target),
                Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val STATE_TAB = "c3_selected_tab"
        const val EXTRA_TARGET = "shortcut_action"
    }
}
