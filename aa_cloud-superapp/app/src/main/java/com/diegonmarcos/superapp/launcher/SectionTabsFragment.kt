package com.diegonmarcos.superapp.launcher

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.ShellActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.core.Collapsible
import com.diegonmarcos.superapp.system.ModePrefs
import com.google.android.material.tabs.TabLayout

/**
 * A section's pages behind ONE tab strip — the launcher's tabbed sections
 * (Suite = Cloud|Phone, Comms/Infos = Apps|Admin). Replaces the hand-written
 * `TabbedSectionFragment` + `SuiteCloudPhoneTabsFragment` pair: the tab list
 * is `build.json::ui.sections[].pages[]`, so no section or page id is spelled
 * out here.
 *
 * Phone — one pane, the selected tab renders into it. Identical to the old
 * two-fragment behaviour.
 *
 * Tablet ([MainActivity.isTwoPane]) — ONE PANE PER PAGE, all rendered at
 * once and side by side: Suite shows Cloud *and* Phone simultaneously. The
 * strip stays put so the chrome reads the same as the phone, and because
 * [TabLayout.MODE_FIXED] + [TabLayout.GRAVITY_FILL] give every tab 1/N of
 * the width against N equal-weight panes, tab *i* sits directly above the
 * pane it names — the strip doubles as each pane's header. Selection then
 * only marks the ACTIVE pane (the one [Collapsible] and the Apps/Admin mode
 * sync follow), since nothing needs swapping.
 *
 * Sections with more pages than [MAX_PANES] never reach here — the nav
 * controller routes them to the icon-rail + detail-pane layout instead.
 */
class SectionTabsFragment : Fragment(), Collapsible {

    private val sectionId: String get() = arguments?.getString(ARG_SECTION_ID).orEmpty()

    /** Stable hosts, one per rendered pane — see `values/ids.xml`. */
    private val paneIds = intArrayOf(
        R.id.section_pane_0, R.id.section_pane_1, R.id.section_pane_2, R.id.section_pane_3,
    )

    /** Index of the tab the user last selected; the pane [Collapsible] talks
     *  to when several are on screen. */
    private var activePane = 0

    /** Position of the last tab that actually HAS content. A launch tab hands
     *  selection back to this one the moment it has fired, so the strip is
     *  never left sitting on a tab with no pane behind it. */
    private var lastContentTab = 0

    /** The "|" between the tab groups, if this strip has both kinds of tab.
     *  Held so the width arithmetic can pay for it — it sits inside the strip
     *  but is not a tab, so its width is not the tabs' to divide. */
    private var groupDivider: View? = null

    /**
     * [Collapsible] — a bottom-nav re-tap lands on this wrapper (it is the
     * visible top fragment), so forward it to the ACTIVE pane's child. Panes
     * the user isn't pointing at keep their own collapse state. Returns the
     * child's consumed-flag (false when it isn't [Collapsible]) so the
     * activity's re-tap handler can still fall back to its default.
     */
    override fun toggleAllCollapsed(): Boolean {
        val host = paneIds.getOrNull(activePane) ?: return false
        return (childFragmentManager.findFragmentById(host) as? Collapsible)
            ?.toggleAllCollapsed() ?: false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val pages = Sections.byId(sectionId)?.pages.orEmpty()
        // A page that declares an `action` is a LAUNCH tab, not a destination:
        // C3's Watchdog and Morpheus fire `extapp:` and leave the app. It wears
        // a tab so the strip reads Observability | Topology | Watchdog |
        // Morpheus, but it has no fragment, so it never claims a pane.
        val panePages = pages.filter { it.action.isBlank() }
        val twoPane = (activity as? ShellActivity)?.isTwoPane() == true
        // One pane per content page on a tablet; phones keep the single
        // swapping pane.
        val paneCount = if (twoPane) panePages.size.coerceIn(1, MAX_PANES) else 1

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val tabs = TabLayout(ctx).apply {
            pages.forEach { addTab(newTab().setText(it.label)) }
            tabMode = TabLayout.MODE_FIXED
            tabGravity = TabLayout.GRAVITY_FILL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            // Liquid-glass pill chrome — the same helper the strip used before,
            // so Suite / Infos / Labs still read as one consistent surface.
            AppTabsStyle.apply(this)
            // Sizing is the other half of the chrome; the divider is read
            // lazily because addGroupDivider() runs after this block.
            AppTabsStyle.equalise(this) { groupDivider }
        }

        // The `|` the strip reads with: Observability · Topology │ Watchdog ·
        // Morpheus. It marks a real split — the left group NAVIGATES inside the
        // app, the right group LEAVES it — which is worth seeing at a glance
        // rather than inferring from four undifferentiated pills.
        //
        // Its position is DERIVED from the same blank/non-blank `action` split
        // this file and [LauncherNavController.isTabbed] already turn on, so it
        // tracks the strip instead of pinning an index that would rot the
        // moment a fifth tab appeared.
        //
        // And it is inserted into TabLayout's own strip rather than added as a
        // tab whose label happens to be "|": tabCount stays 4, so nothing
        // selectable, focusable, reachable by [startIndex], recordable by
        // recordActiveTab, or counted towards [MAX_PANES] is brought into
        // being by drawing it.
        pages.indexOfFirst { it.action.isNotBlank() }
            .takeIf { it > 0 }
            ?.let { addGroupDivider(tabs, it) }

        val panes = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        repeat(paneCount) { i ->
            panes.addView(FrameLayout(ctx).apply {
                id = paneIds[i]
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            })
        }

        root.addView(tabs)
        root.addView(panes)

        val start = startIndex(pages)
        activePane = start
        lastContentTab = start
        if (paneCount > 1) {
            // Every content page is on screen at once — fill each pane from its
            // own page and leave them alone; the tabs only move [activePane].
            panePages.take(paneCount).forEachIndexed { i, p -> render(i, p.id) }
        } else {
            // Phone: one pane, so the selected tab is also the rendered page.
            pages.getOrNull(start)?.let { render(0, it.id) }
        }
        tabs.getTabAt(start)?.select()
        // Sync the mode for the landing tab explicitly. The listener below is
        // attached AFTER this, and selecting tab 0 is a no-op anyway, so
        // neither would fire onTabSelected for the page we start on.
        pages.getOrNull(start)?.let {
            (activity as? ShellActivity)?.nav?.syncModeForPage(it.id)
            (activity as? ShellActivity)?.nav?.recordActiveTab(sectionId, it.id)
        }

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val page = pages.getOrNull(tab.position) ?: return
                // Launch tab — a BUTTON wearing a tab. Dispatch its target
                // through the same grammar a tile uses (so `extapp:` gets the
                // existing installed→open, absent→offer-the-APK behaviour for
                // free) and give the selection straight back to the tab the
                // user was reading: leaving the app must not also leave the
                // strip parked on an empty tab, which is what the user would
                // come back to.
                if (page.action.isNotBlank()) {
                    (activity as? ShellActivity)?.dispatchTarget(page.action)
                    tabs.getTabAt(lastContentTab)
                        ?.takeIf { it != tab }
                        ?.let { back -> tabs.post { back.select() } }
                    return
                }
                lastContentTab = tab.position
                activePane = if (paneCount > 1) tab.position.coerceAtMost(paneCount - 1) else 0
                // An Apps/Admin tab also SETS the global mode, so the Home
                // grid, drawer and bottom-nav icon variants follow it. Fired
                // on SELECTION, not at render time: with every pane on screen
                // rendering both would call it twice and the last would win.
                (activity as? ShellActivity)?.nav?.syncModeForPage(page.id)
                (activity as? ShellActivity)?.nav?.recordActiveTab(sectionId, page.id)
                if (paneCount == 1) render(0, page.id)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        return root
    }

    /**
     * Put a literal "|" BETWEEN tab [position]-1 and tab [position].
     *
     * It is a CHARACTER, not a rule. The first attempt drew a 1dp View at
     * [ViewGroup.LayoutParams.MATCH_PARENT] height, and a full-height line
     * across the strip does not read as punctuation between two words — it
     * reads as the row having been cut in half, which is what it looked like
     * on the device. A "|" set in the tabs' own type is the separator the ask
     * described, and it cannot be "too big" because it is exactly as tall as
     * the letters beside it.
     *
     * TabLayout lays its tabs out in one internal LinearLayout — its only
     * child — so a view dropped into that at the right index sits between the
     * two groups and moves with them. Going through [TabLayout.addTab] would
     * have made it a tab, which is precisely what it must not be.
     */
    private fun addGroupDivider(tabs: TabLayout, position: Int) {
        val strip = tabs.getChildAt(0) as? LinearLayout ?: return
        if (position > strip.childCount) return
        val pad = (DIVIDER_PAD_DP * tabs.resources.displayMetrics.density).toInt()
        val bar = android.widget.TextView(tabs.context).apply {
            text = "|"
            // The values [AppTabsStyle.makePill] gives every tab label — that
            // is the Kotlin object in AppTabsStyle.kt, not a res/ style; there
            // is no XML for this strip. They are only the STARTING point, and
            // deliberately so: [AppTabsStyle.equalise] re-reads the type off a real
            // label once the row has been measured and re-applies it here, so
            // the separator follows the labels when they shrink to fit. These
            // are what it looks like for the one frame before that runs.
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            textSize = 12f
            setTextColor(0xAAFFFFFFL.toInt())
            setPadding(pad, 0, pad, 0)
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        }
        strip.addView(bar, position)
        groupDivider = bar
    }

    /** Which tab starts selected: the page a deep link or walk stop asked
     *  for, else the persisted Apps/Admin mode when this section has a page
     *  named for it, else the first page. Never a launch tab — landing on one
     *  would fire its external app on arrival, so those are skipped here and
     *  only reachable by an explicit tap. */
    private fun startIndex(pages: List<Sections.Page>): Int {
        val wanted = arguments?.getString(ARG_INITIAL_PAGE).orEmpty()
            .ifBlank { ModePrefs(requireContext()).mode }
        return pages.indexOfFirst { it.id == wanted && it.action.isBlank() }
            .takeIf { it >= 0 }
            ?: pages.indexOfFirst { it.action.isBlank() }.coerceAtLeast(0)
    }

    /** Commit page [pageId] into pane [index], reusing the nav controller's
     *  page→Fragment routing so a pane shows exactly what opening that page
     *  on a phone would. */
    private fun render(index: Int, pageId: String) {
        val frag = (activity as? ShellActivity)?.nav?.pageFragment(sectionId, pageId) ?: return
        childFragmentManager.beginTransaction()
            .replace(paneIds[index], frag)
            .commitAllowingStateLoss()
    }

    companion object {
        /** Breathing room either side of the "|" — typographic spacing, not
         *  structure. Everything else about the separator is just the glyph. */
        private const val DIVIDER_PAD_DP = 4f

        /** Panes we have stable host ids for — see `values/ids.xml`. */
        const val MAX_PANES = 4

        private const val ARG_SECTION_ID = "section_id"
        private const val ARG_INITIAL_PAGE = "initial_page"

        fun newInstance(sectionId: String, initialPage: String = ""): SectionTabsFragment =
            SectionTabsFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SECTION_ID, sectionId)
                    putString(ARG_INITIAL_PAGE, initialPage)
                }
            }
    }
}
