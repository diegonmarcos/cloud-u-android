package com.diegonmarcos.superapp.bottomnav

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView

/**
 * [PageTabs] for an app whose shell is still an XML layout or a Fragment (#868) - the same dual
 * pattern as [BottomNavIslandView]. A Compose HOST, not a View port: everything it draws is the
 * strip [PageTabs] draws, so the geometry every app shows is the one PageTabsTest measures.
 *
 *  - [pages] / [selectedId] are plain properties. Setting [selectedId] only MOVES the pill; it
 *    never calls back, so a shell syncing the strip to where it navigated needs no re-entry guard.
 *  - a tap on an unselected pill calls [onSelect], a tap on the selected one [onReselect]. The
 *    host decides whether the pill moves, by setting [selectedId]. A LAUNCH page
 *    ([NavPage.action] non-blank) is handed to [onSelect] like any other; the host dispatches
 *    the action and leaves [selectedId] where it was.
 *  - [underTopChrome] adds the live status-bar / cutout inset above the strip (a strip under the
 *    toolbar island); false for a strip in a sheet. [insets] null = the live window's.
 *  - [colorScheme] carries the host's View theme into the strip's text; null = the MaterialTheme
 *    around it.
 */
public class PageTabsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AbstractComposeView(context, attrs) {

    public var pages: List<NavPage> by mutableStateOf(emptyList())
    public var selectedId: String? by mutableStateOf(null)
    public var underTopChrome: Boolean by mutableStateOf(true)
    public var insets: WindowInsets? by mutableStateOf(null)
    public var colorScheme: ColorScheme? by mutableStateOf(null)
    public var onSelect: (NavPage) -> Unit = {}
    public var onReselect: (NavPage) -> Unit = {}

    @Composable
    override fun Content() {
        val scheme = colorScheme
        if (scheme == null) Strip() else MaterialTheme(colorScheme = scheme) { Strip() }
    }

    @Composable
    private fun Strip() {
        PageTabs(
            pages = pages,
            selectedId = selectedId,
            onSelect = { onSelect(it) },
            onReselect = { onReselect(it) },
            underTopChrome = underTopChrome,
            insets = insets,
        )
    }
}
