package app.affine.pro

import android.view.Gravity
import android.view.View
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.diegonmarcos.superapp.bottomnav.BottomNavIslandView
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.PageTabsView

/**
 * The JS the shell sends to the editor. Ids only: the web half
 * (core/src/mobile/components/native-nav-routes.ts) owns the routes, so the
 * native side never learns a URL and a route change is a web-only change.
 */
internal object NativeNavScript {
    const val EVENT = "cloud-notes:nav"

    private fun quote(s: String?): String =
        if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** The top bar's Settings > Configs: the web half opens AFFiNE's own settings dialog. */
    const val OPEN_SETTINGS_EVENT = "cloud-notes:open-settings"

    /** A page picked in the top bar's drawer: the web half opens that doc in the workbench. */
    const val OPEN_DOC_EVENT = "cloud-notes:open-doc"

    fun dispatch(section: String, page: String?): String =
        "window.dispatchEvent(new CustomEvent('$EVENT',{detail:{section:${quote(section)},page:${quote(page)}}}))"

    fun openSettings(): String = "window.dispatchEvent(new Event('$OPEN_SETTINGS_EVENT'))"

    fun openDoc(id: String): String =
        "window.dispatchEvent(new CustomEvent('$OPEN_DOC_EVENT',{detail:{id:${quote(id)}}}))"
}

/**
 * Cloud Notes' navigation (#868): libs:bottomnav's island (Home | Pages | Canvas |
 * Graph | Search) at the bottom and, for the selected section, its page strip on
 * top, both fed by NavDecl from build.json::ui. Taps become `cloud-notes:nav`
 * events for the editor WebView. The look is the lib's alone.
 */
internal class NativeNav(
    private val decl: NavDecl,
    private val webView: WebView,
    private val icon: (String) -> Int,
    private val run: (String) -> Unit,
) {
    private lateinit var island: BottomNavIslandView
    private lateinit var strip: PageTabsView

    /** The open section; the top bar's drawer reads it to mark the current one. */
    var sectionId: String by mutableStateOf("")
        private set

    // The WebView starts below the TOP STACK, the only vertical offset native owns:
    // [status bar + top bar](topInset) then the page strip (stripHeight). The web half
    // adds no status-bar padding of its own once it sees cloudNotesNativeNav (setup.ts).
    private var topInset = 0
    private var stripHeight = 0

    fun attach(parent: CoordinatorLayout) {
        val ctx = parent.context
        island = BottomNavIslandView(ctx).apply {
            items = decl.viewItems(icon)
            onSelect = { id -> openSection(id) }
            onReselect = { id -> openSection(id) }
        }
        strip = PageTabsView(ctx).apply {
            onSelect = { page -> openPage(page) }
            visibility = View.GONE
        }
        parent.addView(
            strip,
            CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
                CoordinatorLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP
                topMargin = topInset
            },
        )
        parent.addView(
            island,
            CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
                CoordinatorLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.BOTTOM },
        )
        // The strip sits above the editor, not over its page header.
        strip.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            stripHeight = if (v.visibility == View.VISIBLE) bottom - top else 0
            applyWebMargin()
        }
        decl.default()?.let { showSection(it.id) }
    }

    /** The top bar reports its height (status bar included); the strip and the WebView sit below it. */
    fun setTopInset(px: Int) {
        if (px == topInset) return
        topInset = px
        (strip.layoutParams as? CoordinatorLayout.LayoutParams)?.let {
            it.topMargin = px
            strip.layoutParams = it
        }
        applyWebMargin()
    }

    private fun applyWebMargin() {
        val h = topInset + stripHeight
        (webView.layoutParams as? CoordinatorLayout.LayoutParams)?.let {
            if (it.topMargin != h) {
                it.topMargin = h
                webView.layoutParams = it
            }
        }
    }

    /** A section chosen outside the island (the top bar's drawer). */
    fun open(id: String) = openSection(id)

    private fun openSection(id: String) {
        showSection(id)
        run(NativeNavScript.dispatch(id, null))
    }

    private fun showSection(id: String) {
        val section = decl.section(id) ?: return
        sectionId = id
        island.selectedId = id
        strip.pages = section.pages
        strip.selectedId = section.pages.firstOrNull()?.id
        strip.surface = decl.stripSurface(id, darkTheme = true)
        strip.visibility = if (section.pages.size > 1) View.VISIBLE else View.GONE
        if (strip.visibility == View.GONE) {
            // A GONE view is never laid out, so its layout listener cannot report the 0.
            stripHeight = 0
            applyWebMargin()
        }
    }

    private fun openPage(page: NavPage) {
        // A LAUNCH page (non-blank action) creates something and leaves the strip where it was.
        if (page.action.isBlank()) strip.selectedId = page.id
        run(NativeNavScript.dispatch(sectionId, page.id))
    }
}
