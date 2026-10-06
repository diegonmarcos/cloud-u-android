package com.diegonmarcos.cloudc3

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.diegonmarcos.cloudc3.cloud.C3HealthFragment
import com.diegonmarcos.cloudc3.cloud.C3MeshFragment
import com.diegonmarcos.cloudc3.cloud.C3StackFragment
import com.diegonmarcos.cloudc3.pages.AppsFragment
import com.diegonmarcos.cloudc3.pages.ConfigsFragment
import com.diegonmarcos.cloudc3.pages.HomeFragment
import com.diegonmarcos.cloudc3.pages.ObservFragment
import com.diegonmarcos.cloudc3.pages.TopologyFragment
import com.diegonmarcos.superapp.bottomnav.BottomNavIslandView

/**
 * #648 the app's one Activity: a FRAGMENT HOST above the fleet's bottom-nav island.
 *
 * The pages are the SuperApp's C3 pages and they are Fragments, so the shell hosts
 * fragments rather than asking them to become something else.
 *
 * THE TOP-OVERFLOW FIX (#407/#477). Content was drawing under the status bar and the
 * camera cutout. The fix reads the REAL inset and pads with it:
 *
 *  - [enableEdgeToEdge] means this window lays out behind the system bars, which is what
 *    lets the island sit against the bottom edge.
 *  - the listener below takes systemBars UNION displayCutout, because on this device the
 *    cutout is taller than the status bar and either one alone leaves the other clipped
 *    (#407 replaced a tuned margin with exactly this reading).
 *  - it pads the CONTENT container's top, never the root, and **returns the insets
 *    unconsumed**. Consuming them is the #477 bug: a strip that consumed the inset
 *    starved every view below it, and here it would starve the island of the bottom
 *    inset it reads for itself.
 *  - there is no hardcoded top margin. Nothing here is tuned to a device.
 */
class MainActivity : AppCompatActivity(),
    C3HealthFragment.UrlClickListener,
    C3StackFragment.TargetListener {

    private lateinit var nav: BottomNavIslandView
    private lateinit var content: FrameLayout
    private var currentTab: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        content = findViewById(R.id.fragment_container)
        nav = findViewById(R.id.bottom_nav)

        // READ, never consume (#477).
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.setPadding(
                maxOf(bars.left, cutout.left),
                maxOf(bars.top, cutout.top),
                maxOf(bars.right, cutout.right),
                0,
            )
            insets
        }

        C3BottomNav.configure(nav) { tabId -> show(tabId) }

        val restored = savedInstanceState?.getString(STATE_TAB)
        val initial = restored?.takeIf { id -> Declarations.tabs.any { it.id == id } }
            ?: Declarations.defaultTab.takeIf { d -> Declarations.tabs.any { it.id == d } }
            ?: Declarations.tabs.firstOrNull()?.id
        initial?.let { show(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, currentTab)
    }

    /**
     * Swap the one container to a tab's page. THE ONE PLACE Kotlin names a tab id — the
     * tester diffs this dispatch against build.json::ui.bottom_nav in BOTH directions, so a
     * declared tab with no page and a page no declaration reaches are each a build
     * failure. There is deliberately no companion list of these ids.
     */
    private fun show(tabId: String) {
        if (tabId == currentTab) return
        val fragment = when (tabId) {
            "topology" -> TopologyFragment()
            "observ" -> ObservFragment()
            "home" -> HomeFragment()
            "apps" -> AppsFragment()
            "configs" -> ConfigsFragment()
            else -> return
        }
        currentTab = tabId
        C3BottomNav.sync(nav, tabId)
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, fragment, tabId)
        }
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
    }
}
