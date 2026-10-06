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
import com.diegonmarcos.superapp.shell.Collapsible
import com.diegonmarcos.superapp.system.ModePrefs
import com.diegonmarcos.superapp.bottomnav.PageTabsView

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
 * the shared pill strip (libs:bottomnav's [PageTabsView]) gives every tab
 * 1/N of the width against N equal-weight panes, tab *i* sits directly above the
 * pane it names — the strip doubles as each pane's header. Selection then
 * only marks the ACTIVE pane (the one [Collapsible] and the Apps/Admin mode
 * sync follow), since nothing needs swapping.
 *
 * Sections with more pages than [MAX_PANES] never reach here — the nav
 * controller routes them to the icon-rail + detail-pane layout instead.
 *
 * A PAGE can wear the same strip ([forPage]): Configs ▸ Launcher is one page
 * over the Theme and One-Hand tabs. This app gets ONE tab mechanism that way
 * instead of a second widget that has to be restyled and re-fixed in parallel
 * — the strip already knows how to list pages of a section, and a page's tabs
 * ARE pages of its section. Only three things differ, all off [ownerPageId]:
 * the strip lists the OWNER'S tabs instead of the section's pages, it records
 * its selection under its own key so it cannot overwrite the section's, and it
 * stays single-pane on a tablet because it is already living inside the
 * activity's 60% detail column rather than splitting the window itself.
 */
class SectionTabsFragment : Fragment(), Collapsible {

    private val sectionId: String get() = arguments?.getString(ARG_SECTION_ID).orEmpty()

    /** The page this strip belongs to, or "" when the strip IS the section. */
    private val ownerPageId: String get() = arguments?.getString(ARG_OWNER_PAGE).orEmpty()

    /** Where this strip's active tab is remembered on the controller. A
     *  section strip keeps using the bare section id — byte-identical to the
     *  behaviour before pages could have tabs — and a page strip gets its own
     *  key, so Configs ▸ Launcher sitting on One-Hand can never be mistaken
     *  for the Configs SECTION sitting on a tab. */
    private val tabKey: String get() =
        if (ownerPageId.isBlank()) sectionId else pageTabKey(sectionId, ownerPageId)

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

    /**
     * The page id this strip is showing, saved into THIS FRAGMENT'S OWN state
     * so it survives an Activity recreate.
     *
     * [LauncherNavController.activeTabFor] cannot answer across one.
     * `activeTabBySection` is a plain field of the controller, and the
     * controller is `ShellActivity.nav` — a field of the Activity. `recreate()`
     * takes both with it, [startIndex] then reads "", falls through to the
     * first content page, and the strip comes back on tab 0. The
     * FragmentManager, by contrast, saves and restores THIS fragment across the
     * recreate, so its saved state is the one channel that travels with it.
     *
     * #349 is what made that visible: the Modes page asked for a recreate on
     * every toggle, so every flip inside Configs ▸ Launcher ▸ Modes landed back
     * on Configs ▸ Launcher ▸ Profiles. The recreate is gone, but a mode change
     * still legitimately recreates — so this is the half that has to hold on
     * the next honest reload.
     */
    private var selectedPageId = ""

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
        // The section's own pages, or — when a PAGE owns this strip — that
        // page's declared tabs. Both are pages of the same section, so
        // everything below this line is unaware of the difference.
        val owner = Sections.byId(sectionId)?.allPages?.firstOrNull { it.id == ownerPageId }
        val pages = if (owner != null) Sections.tabPagesOf(sectionId, owner)
                    else Sections.byId(sectionId)?.pages.orEmpty()
        // A page that declares an `action` is a LAUNCH tab, not a destination:
        // C3's Watchdog and Morpheus fire `extapp:` and leave the app. It wears
        // a tab so the strip reads Observability | Topology | Watchdog |
        // Morpheus, but it has no fragment, so it never claims a pane.
        val panePages = pages.filter { it.action.isBlank() }
        val twoPane = (activity as? ShellActivity)?.isTwoPane() == true && ownerPageId.isBlank()
        // One pane per content page on a tablet; phones keep the single
        // swapping pane. A PAGE strip is single-pane on every device: it is
        // opened INTO the tablet's 60% detail column, so fanning its tabs out
        // side by side would divide that column, not the window.
        val paneCount = if (twoPane) panePages.size.coerceIn(1, MAX_PANES) else 1

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // #868 the strip is libs:bottomnav's PageTabsView: the SAME pill strip every app draws
        // (PageTabs is the pixel port of the AppTabsStyle this file used to call). It owns the
        // top clearance (base + live status/cutout inset), the gap to the pane below, the equal
        // pill widths and the "|" between the destination tabs and the launch tabs (Observability
        // · Topology │ Watchdog · Morpheus): the divider is derived from the same blank/non-blank
        // `action` split this file turns on, and a launch pill is a button wearing a tab, so it
        // is never the strip's selection.
        val tabs = PageTabsView(ctx).apply {
            this.pages = pages.map(Sections::navPage)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

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

        val start = startIndex(pages, s)
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
        pages.getOrNull(start)?.let { tabs.selectedId = it.id }
        // Sync the mode for the landing tab explicitly: setting selectedId only moves the pill
        // and never calls back, so nothing else would fire it for the page we start on.
        pages.getOrNull(start)?.let {
            selectedPageId = it.id
            (activity as? ShellActivity)?.nav?.syncModeForPage(it.id)
            (activity as? ShellActivity)?.nav?.recordActiveTab(tabKey, it.id)
        }

        tabs.onSelect = select@{ tapped ->
            val position = pages.indexOfFirst { it.id == tapped.id }
            val page = pages.getOrNull(position) ?: return@select
            // Launch tab: dispatch its target through the same grammar a tile uses (so `extapp:`
            // gets the existing installed→open, absent→offer-the-APK behaviour for free) and
            // leave the strip where the user was reading — the pill never moves, so the strip is
            // never parked on a tab with no pane behind it.
            if (page.action.isNotBlank()) {
                (activity as? ShellActivity)?.dispatchTarget(page.action)
                return@select
            }
            tabs.selectedId = page.id
            lastContentTab = position
            selectedPageId = page.id
            activePane = if (paneCount > 1) position.coerceAtMost(paneCount - 1) else 0
            // An Apps/Admin tab also SETS the global mode, so the Home grid, drawer and
            // bottom-nav icon variants follow it. Fired on SELECTION, not at render time: with
            // every pane on screen rendering both would call it twice and the last would win.
            (activity as? ShellActivity)?.nav?.syncModeForPage(page.id)
            (activity as? ShellActivity)?.nav?.recordActiveTab(tabKey, page.id)
            if (paneCount == 1) render(0, page.id)
        }

        return root
    }

    /** Which tab starts selected: where THIS strip was when the Activity was
     *  torn down under it, else the page a deep link or walk stop asked for,
     *  else the persisted Apps/Admin mode when this section has a page named
     *  for it, else the first page. Never a launch tab — landing on one would
     *  fire its external app on arrival, so those are skipped here and only
     *  reachable by an explicit tap.
     *
     *  [selectedPageId] leads, and it has to lead over `initial_page`
     *  specifically: `arguments` are restored across a recreate too, so a strip
     *  that was opened by a deep link would otherwise ANSWER THAT DEEP LINK
     *  AGAIN on every recreate and undo every tab the user picked after it.
     *  Restored state is the only source here that knows where the user was, as
     *  against where they arrived.
     *
     *  A PAGE strip is not built by goSection and so is never handed an
     *  initial page; it asks the controller which tab it was left on instead.
     *  That is also the channel a deep link to a tab arrives on — see
     *  [LauncherNavController.openSectionPage] — and the Apps/Admin mode is
     *  not a fallback it can use, since a page's tabs are not modes. */
    private fun startIndex(pages: List<Sections.Page>, saved: Bundle?): Int {
        val remembered = if (ownerPageId.isBlank()) ModePrefs(requireContext()).mode
                         else (activity as? ShellActivity)?.nav?.activeTabFor(tabKey).orEmpty()
        val wanted = saved?.getString(STATE_SELECTED_PAGE).orEmpty()
            .ifBlank { arguments?.getString(ARG_INITIAL_PAGE).orEmpty() }
            .ifBlank { remembered }
        return pages.indexOfFirst { it.id == wanted && it.action.isBlank() }
            .takeIf { it >= 0 }
            ?: pages.indexOfFirst { it.action.isBlank() }.coerceAtLeast(0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SELECTED_PAGE, selectedPageId)
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
        /** Panes we have stable host ids for — see `values/ids.xml`. */
        const val MAX_PANES = 4

        private const val ARG_SECTION_ID = "section_id"
        private const val ARG_INITIAL_PAGE = "initial_page"
        private const val ARG_OWNER_PAGE = "owner_page"

        /** Saved-instance-state key for [selectedPageId]. Not an ARG_: the
         *  arguments say how this strip was OPENED and never change, and this
         *  says where the user moved it to since. */
        private const val STATE_SELECTED_PAGE = "selected_page"

        /** Where a PAGE strip's active tab is remembered on the controller.
         *  Shared with [LauncherNavController.openSectionPage], which writes
         *  the tab a deep link asked for before opening the owner page — so
         *  the two must agree on the key, and there is one definition of it. */
        fun pageTabKey(sectionId: String, pageId: String): String = "$sectionId/$pageId"

        fun newInstance(sectionId: String, initialPage: String = ""): SectionTabsFragment =
            SectionTabsFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SECTION_ID, sectionId)
                    putString(ARG_INITIAL_PAGE, initialPage)
                }
            }

        /** The strip for a PAGE that declares `tabs` — Configs ▸ Launcher.
         *  Which pages it lists comes from that page's declaration, so this
         *  takes no list of its own. */
        fun forPage(sectionId: String, pageId: String): SectionTabsFragment =
            SectionTabsFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SECTION_ID, sectionId)
                    putString(ARG_OWNER_PAGE, pageId)
                }
            }
    }
}
