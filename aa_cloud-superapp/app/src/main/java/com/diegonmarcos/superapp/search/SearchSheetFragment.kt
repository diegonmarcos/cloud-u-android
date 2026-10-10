package com.diegonmarcos.superapp.search

import android.os.Bundle
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentManager
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette

/**
 * The search the Home star opens (Polaris, the launcher's Search shortcut, Sirius' Search:
 * action:open_search) — the same bar, chips, sections and engine as Cloud ▸ Apps and the swipe
 * sheet, full screen over the page it was opened from, on the opaque theme surface. It starts on
 * the browser scopes plus the configs ([SearchEntry.HOME_STAR]); an empty query lists everything
 * in the chosen scopes, as this sheet always did. A pick closes it.
 */
class SearchSheetFragment : KitComposeFragment() {

    private val controller by lazy {
        val ctx = requireContext()
        SearchController(SearchEntry.HOME_STAR, SuperappSearchIndex.Source(ctx), SearchScopePrefs(ctx))
    }

    override fun palette(): KitPalette = LauncherPalette.kit(requireContext())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // A layer above the page: it casts a shadow and takes its own touches.
        view.elevation = 8f * resources.displayMetrics.density
        view.isClickable = true
    }

    private fun withActions(block: SearchActions.() -> Unit) {
        val a = activity ?: return
        SearchActions(a).block()
        dismiss()
    }

    private fun dismiss() {
        if (!isAdded) return
        parentFragmentManager.popBackStack(BACK_STACK_TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    @Composable
    override fun Content() {
        val c = controller
        val surface = remember { Color(LauncherPalette.opaqueSurface(requireContext())) }
        val cb = remember {
            SearchCallbacks(
                onHit = { hit -> withActions { open(hit) } },
                onCommand = { cmd -> withActions { run(cmd) } },
                onCloudSearch = { q -> withActions { cloudSearch(q) } },
            )
        }
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        SearchLiveEffect(c)
        Column(
            Modifier.fillMaxSize()
                .background(surface)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(vertical = 12.dp)
                .testTag(SearchPanelTags.SHEET),
        ) {
            SearchBox(c, PLACEHOLDER, onGo = { c.go(cb) },
                modifier = Modifier.padding(horizontal = 12.dp).focusRequester(focus))
            if (!c.commandMode) SearchScopeChips(c, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp))
            SearchResults(c, surface, cb, Modifier.weight(1f))
        }
        LaunchedEffect(Unit) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }

    companion object {
        /** Fragment tag AND back-stack name: the shell uses it to avoid stacking two sheets on a
         *  double tap, and to pop this one. */
        const val BACK_STACK_TAG = "search_sheet"
        const val PLACEHOLDER = "Search   ( : for commands )"
        fun newInstance() = SearchSheetFragment()
    }
}
