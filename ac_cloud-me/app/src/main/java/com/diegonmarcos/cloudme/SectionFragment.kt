package com.diegonmarcos.cloudme

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.diegonmarcos.superapp.fin.MyFinDashboardFragment
import com.diegonmarcos.superapp.bottomnav.PageTabsView
import com.diegonmarcos.superapp.health.HealthFragment
import org.json.JSONObject

/**
 * One section: its tab strip and whichever page is showing.
 *
 * A tab that declares `pages` of its own is a container — it draws another,
 * quieter strip underneath and shows one of its children, and a child may be
 * a container in turn. Buro > Fin was the first: Acct, Budget and Portfolio
 * are three views of one question, and three top-level tabs would have said
 * they were three questions. Projects > Health > Workout > Gym is the deepest,
 * at three strips. The strips are built from the page chain rather than from
 * two named fields, so the depth build.json may declare is not capped here.
 *
 * A page's content is decided by its `stack_<id>` list. When that list is a
 * single `fragment` block the library fragment is hosted directly — Health,
 * Accounting, Agenda and the Wallet file browser are whole surfaces owned by
 * their modules, and wrapping them in a renderer would only add a scroll
 * container inside a scroll container. Everything else goes to [StackFragment].
 */
class SectionFragment : Fragment() {

    private val sectionId: String get() = arguments?.getString(ARG_SECTION).orEmpty()

    /** The LEAF page on screen — a sub-page id when the tab is a container. */
    private var pageId: String? = null

    /** One PageTabsView per level of the chain to [pageId], outermost first.
     *  Held as a column rather than as named fields so that adding a level in
     *  build.json needs no field here. */
    private var strips: LinearLayout? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val section = Sections.byId(sectionId)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.me_bg))
        }

        pageId = (s?.getString(STATE_PAGE) ?: arguments?.getString(ARG_PAGE))
            .let { section?.page(it)?.leaf()?.id }

        // The strips themselves are filled by showPage, which is the only
        // place that knows which page — and therefore how many levels — is on
        // screen. Empty here means a section reached before its first page.
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        strips = column
        root.addView(column, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(FrameLayout(ctx).apply { id = HOST_ID }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        super.onViewCreated(view, s)
        showPage(pageId, replaceContent = s == null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGE, pageId)
    }

    /** An empty strip: libs:bottomnav's pill strip (#868), the same one every app draws. The
     *  section's strip sits under the toolbar and the DrawerLayout already clears the system
     *  bars for it, so none adds its own inset. */
    private fun newStrip(ctx: android.content.Context): PageTabsView =
        PageTabsView(ctx).apply {
            underTopChrome = false
            onSelect = { openTab(it.id) }
        }

    /** A container tab has no content of its own; tapping one opens the child
     *  you were last on — which is what staying on the chain means — or its
     *  first child. Tapping a leaf opens the leaf. */
    private fun openTab(id: String) {
        val section = Sections.byId(sectionId) ?: return
        val stillBelow = section.path(pageId).any { it.id == id }
        showPage(if (stillBelow) pageId else section.page(id)?.leaf()?.id)
    }

    /** Shows [id] and brings every strip above it in line. [replaceContent] is
     *  false on a config change, where the child fragment is restored already
     *  and replacing it would drop its own state. */
    private fun showPage(id: String?, replaceContent: Boolean = true) {
        val section = Sections.byId(sectionId) ?: return
        val page = section.page(id)?.leaf() ?: return
        val changed = pageId != page.id
        pageId = page.id

        syncStrips(section, section.path(page.id))

        if (replaceContent || changed) {
            childFragmentManager.commit { replace(HOST_ID, contentFor(section, page)) }
        }
    }

    /** Draws the strips [chain] implies: level 0 offers the section's own
     *  tabs, level n the children of the tab chosen at level n-1, and the
     *  chain says which tab is selected at each. Strips past the chain are
     *  removed, so stepping out of a container takes its strip with it.
     *  Setting a strip's selection only moves the pill, so no re-entry guard. */
    private fun syncStrips(section: Section, chain: List<Page>) {
        val host = strips ?: return
        val levels = listOf(section.pages) + chain.dropLast(1).map { it.pages }
        while (host.childCount > levels.size) host.removeViewAt(host.childCount - 1)
        levels.forEachIndexed { level, siblings ->
            val strip = host.getChildAt(level) as? PageTabsView
                ?: newStrip(host.context).also {
                    host.addView(it, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            // One tab is not a choice — a single-tab strip reads as a broken
            // control rather than as a navigation aid. Configs is shaped that
            // way on purpose: a settings screen is a list you scroll.
            strip.visibility = if (siblings.size > 1) View.VISIBLE else View.GONE
            strip.pages = siblings
            strip.selectedId = chain.getOrNull(level)?.id
        }
    }

    private fun contentFor(section: Section, page: Page): Fragment {
        val stack = Sections.stack(requireContext(), section.id, page.id)
        val single = if (stack.length() == 1) stack.optJSONObject(0) else null
        if (single != null && single.optString("kind") == "fragment") {
            libraryFragment(single)?.let { return it }
        }
        return StackFragment.newInstance(section.id, page.id)
    }

    /** `{"kind":"fragment","id":"…"}` → the module that owns that surface.
     *  An unknown id falls through to the stack renderer, which shows the
     *  block's title rather than a blank screen, so a typo in JSON is
     *  visible on the phone instead of silent. */
    private fun libraryFragment(o: JSONObject): Fragment? = when (o.optString("id")) {
        // `metric` and `records` are only read by the metric page, which is
        // one metric of the taxonomy on a page of its own — Workout > Steps
        // is the Activity metric narrowed to steps, distance and calories.
        // Both are declared here rather than in Kotlin so that narrowing a
        // page, or adding another one, stays a JSON edit.
        "health" -> HealthFragment.newInstance(
            o.optString("page", HealthFragment.PAGE_SUMMARY),
            o.optString("metric"),
            (o.optJSONArray("records") ?: org.json.JSONArray()).let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            },
        )
        "fin"    -> MyFinDashboardFragment()
        "agenda" -> AgendaFragment.newInstance(AgendaFragment.MODE_EVENTS)
        "todo"   -> AgendaFragment.newInstance(AgendaFragment.MODE_TODOS)
        "files"  -> FilesFragment.newInstance(o)
        "web"    -> WebFragment.newInstance(o)
        else     -> null
    }

    companion object {
        private const val ARG_SECTION = "section"
        private const val ARG_PAGE = "page"
        private const val STATE_PAGE = "state_page"
        private const val HOST_ID = 0x00ED0001

        fun newInstance(sectionId: String, pageId: String?): SectionFragment =
            SectionFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SECTION, sectionId)
                    putString(ARG_PAGE, pageId)
                }
            }
    }
}
