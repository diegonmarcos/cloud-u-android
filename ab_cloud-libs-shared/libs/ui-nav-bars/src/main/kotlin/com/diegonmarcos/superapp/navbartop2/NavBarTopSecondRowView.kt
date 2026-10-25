package com.diegonmarcos.superapp.navbartop2

import android.content.Context
import android.util.AttributeSet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.TabSurface

/**
 * [NavBarTopSecondRow] for a host that is a Fragment or a View (libs:maps' strip factory), as libs:bottomnav's PageTabsView is for
 * its PageTabs. A Compose host, not a View port: it draws exactly what the composable draws.
 *
 *  - [pages] / [selectedId] are plain properties; setting [selectedId] only MOVES the selection, it never calls back.
 *  - a tap on an unselected tab calls [onSelect], a tap on the selected one [onReselect]; the host decides whether the selection moves.
 *  - no colour, size or inset is configurable: [dark] and [surface] only pick one of the shared palettes.
 */
public class NavBarTopSecondRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AbstractComposeView(context, attrs) {

    public var pages: List<NavPage> by mutableStateOf(emptyList())
    public var selectedId: String? by mutableStateOf(null)
    public var underTopChrome: Boolean by mutableStateOf(true)
    public var dark: Boolean by mutableStateOf(true)
    public var surface: TabSurface? by mutableStateOf(null)
    public var onSelect: (NavPage) -> Unit = {}
    public var onReselect: (NavPage) -> Unit = {}

    @Composable
    override fun Content() {
        NavBarTopSecondRow(
            pages = pages,
            selectedId = selectedId,
            onSelect = { onSelect(it) },
            dark = dark,
            onReselect = { onReselect(it) },
            underTopChrome = underTopChrome,
            surface = surface,
        )
    }
}
