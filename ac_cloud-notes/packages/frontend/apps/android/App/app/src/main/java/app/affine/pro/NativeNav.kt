package app.affine.pro

import android.view.Gravity
import android.view.View
import android.webkit.WebView
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

    fun dispatch(section: String, page: String?): String =
        "window.dispatchEvent(new CustomEvent('$EVENT',{detail:{section:${quote(section)},page:${quote(page)}}}))"
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
    private var sectionId: String = ""

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
            ).apply { gravity = Gravity.TOP },
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
            val h = if (v.visibility == View.VISIBLE) bottom - top else 0
            (webView.layoutParams as? CoordinatorLayout.LayoutParams)?.let {
                if (it.topMargin != h) {
                    it.topMargin = h
                    webView.layoutParams = it
                }
            }
        }
        decl.default()?.let { showSection(it.id) }
    }

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
    }

    private fun openPage(page: NavPage) {
        // A LAUNCH page (non-blank action) creates something and leaves the strip where it was.
        if (page.action.isBlank()) strip.selectedId = page.id
        run(NativeNavScript.dispatch(sectionId, page.id))
    }
}
