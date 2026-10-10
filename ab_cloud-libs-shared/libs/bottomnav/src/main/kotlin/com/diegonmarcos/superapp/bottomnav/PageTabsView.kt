package com.diegonmarcos.superapp.bottomnav

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 *  - there is no colour, size or inset to configure: the strip is the fleet's, whatever theme the
 *    host app wears. [surface] only picks which of the lib's two palettes (dark page / light page).
 */
public class PageTabsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AbstractComposeView(context, attrs) {

    public var pages: List<NavPage> by mutableStateOf(emptyList())
    public var selectedId: String? by mutableStateOf(null)
    public var underTopChrome: Boolean by mutableStateOf(true)
    /** What the strip is drawn over (see [PageTabs]); one of the lib's two palettes, never a colour. */
    public var surface: TabSurface by mutableStateOf(TabSurface.Dark)
    /** Tests inject an inset; null = the live window's. Not part of the public contract. */
    internal var insets: WindowInsets? by mutableStateOf(null)
    public var onSelect: (NavPage) -> Unit = {}
    public var onReselect: (NavPage) -> Unit = {}

    @Composable
    override fun Content() {
        PageTabsImpl(
            pages = pages,
            selectedId = selectedId,
            onSelect = { onSelect(it) },
            modifier = Modifier,
            onReselect = { onReselect(it) },
            underTopChrome = underTopChrome,
            insets = insets,
            surface = surface,
        )
    }
}
