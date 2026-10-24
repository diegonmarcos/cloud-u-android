package app.cloudnotes.pro

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavPage

/**
 * The JS the shell sends to the editor. Ids only: the web half
 * (core/src/mobile/components/native-nav-routes.ts) owns the routes, so the
 * native side never learns a URL and a route change is a web-only change.
 */
internal object NativeNavScript {
    const val EVENT = "cloud-notes:nav"

    private fun quote(s: String?): String =
        if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** The top bar's Settings > Configs: the web half opens Cloud Notes's own settings dialog. */
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
 * Cloud Notes' navigation state (#868, #1164): the open section, the selected page of its second row and whether the shell
 * draws the Ask page itself. [NotesShell] draws it with the shared bars (first row, second row, bottom nav); taps become
 * `cloud-notes:nav` events for the editor WebView. Ids only, the web half owns the routes.
 */
internal class NativeNav(
    val decl: NavDecl,
    private val run: (String) -> Unit,
) {
    companion object {
        /**
         * `<section>/<page>` ids the shell draws itself instead of sending them to the web half:
         * Search > Ask is libs:ai's chat. Mirrors the `native` rows of native-nav-routes.ts
         * (test-notes-topbar.sh fails when the two sets differ).
         */
        val NATIVE_PAGES: Set<String> = setOf("search/ask")
    }

    /** The open section; the first row, the drawer and the bottom nav read it. */
    var sectionId: String by mutableStateOf(decl.default()?.id.orEmpty())
        private set

    /** The selected page of the second row (null: the section's first). */
    var pageId: String? by mutableStateOf(decl.default()?.pages?.firstOrNull()?.id)
        private set

    /** True while a page the shell draws itself (Search > Ask) is the one shown. */
    var askShown: Boolean by mutableStateOf(false)
        private set

    /** A section chosen from the bottom nav, the first row's dropdown or the drawer. */
    fun open(id: String) {
        val section = decl.section(id) ?: return
        sectionId = id
        pageId = section.pages.firstOrNull()?.id
        route(id, null)
    }

    /** A page of the open section chosen in the second row. A LAUNCH page (non-blank action) leaves the strip where it was. */
    fun openPage(page: NavPage) {
        if (page.action.isBlank()) pageId = page.id
        route(sectionId, page.id)
    }

    /** A sub-page of [section] picked in the first row's dropdown. */
    fun openPage(section: String, page: String) {
        val s = decl.section(section) ?: return
        val p = s.pages.firstOrNull { it.id == page } ?: return
        if (section != sectionId) { sectionId = section }
        openPage(p)
    }

    /** Native pages are drawn by the shell; every other target goes to the web half. */
    private fun route(section: String, page: String?) {
        val target = page ?: decl.section(section)?.pages?.firstOrNull()?.id
        askShown = target != null && "$section/$target" in NATIVE_PAGES
        if (!askShown) run(NativeNavScript.dispatch(section, page))
    }
}
