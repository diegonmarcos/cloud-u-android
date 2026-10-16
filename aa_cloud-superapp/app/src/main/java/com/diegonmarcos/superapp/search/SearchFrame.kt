package com.diegonmarcos.superapp.search

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.ui.LauncherPalette

/**
 * A page's one inline search, in Compose (what [InlineSearch] is for a View host): the SuperApp's single
 * search (SearchPanel) with its [controller], its pick callbacks and its Back/reset behaviour. The page
 * draws it with [SearchFrame]: the bar FIRST (so on Cloud ▸ Apps it sits directly under the page's tab
 * icons), then the page content with the results dropdown layered over it.
 */
class SearchSession(private val fragment: Fragment, entry: SearchEntry) {
    private val ctx = fragment.requireContext()
    val controller = SearchController(entry, SuperappSearchIndex.Source(ctx), SearchScopePrefs(ctx))

    private fun withActions(block: SearchActions.() -> Unit) {
        val a = fragment.activity ?: return
        reset()
        SearchActions(a).block()
    }

    val callbacks = SearchCallbacks(
        onHit = { hit -> withActions { open(hit) } },
        onCommand = { cmd -> withActions { run(cmd) } },
        onCloudSearch = { q -> withActions { cloudSearch(q) } },
    )

    /** Back with a query clears it (the full page returns) before Back leaves the page. */
    fun handleBack(): Boolean {
        if (controller.query.isBlank()) return false
        reset()
        return true
    }

    fun reset() {
        controller.query = ""
        controller.blur()
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(fragment.view?.windowToken, 0)
    }

    internal val surface: Color get() = Color(LauncherPalette.opaqueSurface(ctx))
}

@Composable
fun SearchFrame(session: SearchSession, placeholder: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        SearchBox(session.controller, placeholder, onGo = { session.controller.go(session.callbacks) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        Box(Modifier.weight(1f)) {
            content()
            Box(Modifier.fillMaxSize().shadow(0.dp)) { SearchDropdown(session.controller, session.surface, session.callbacks) }
        }
    }
}
