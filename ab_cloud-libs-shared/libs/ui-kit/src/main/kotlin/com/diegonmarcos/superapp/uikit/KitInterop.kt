package com.diegonmarcos.superapp.uikit

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment

/**
 * A screen whose whole body is a composable, for a host that still navigates by
 * FragmentManager. A View app migrates one page at a time by swapping its Fragment's base class
 * for this one: the shell, its back stack and its page factory stay untouched.
 *
 * The composition is disposed with the fragment's VIEW lifecycle, not the fragment's, so a page
 * popped to the back stack releases its composition and a re-attach builds a fresh one — the
 * same lifetime the View tree it replaces had.
 */
abstract class KitComposeFragment : Fragment() {

    /**
     * Whether this page's Compose scrolling is reported to the host's [KitScroll] sink. A page that hosts a View which scrolls by
     * itself (a WebView) says false: the host's View driver already sees that scroll, and a wrapper around a WebView is a layer it
     * does not need.
     */
    protected open val reportsScroll: Boolean get() = true

    /** The page body, under the shell's scroll sink when the host installed one and the page wants it. */
    @Composable
    private fun Page() { if (reportsScroll) KitPage { Content() } else Content() }


    /** The palette to draw with, read once per view creation (a theme switch recreates the view). */
    abstract fun palette(): KitPalette

    @Composable
    abstract fun Content()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        requireContext().kitComposeView(palette()) { Page() }
}

/**
 * Kit composables inside a page that is still Views: add the returned view where the hand-built
 * widgets used to go. Disposed when the host view tree's lifecycle ends.
 */
fun Context.kitComposeView(palette: KitPalette, content: @Composable () -> Unit): ComposeView =
    ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { CloudKitTheme(palette, content) }
    }

/**
 * A host (the SuperApp shell) that wants to know when a page scrolls - to collapse its bottom nav - sets [connection] once;
 * every [KitComposeFragment] page then reports its nested scrolls to it. Null (the default, and every other app) = no sink.
 * Compose pages move no View's scrollY, so a host that only watches View scroll offsets never sees them.
 */
object KitScroll {
    @Volatile
    var connection: androidx.compose.ui.input.nestedscroll.NestedScrollConnection? = null
}

/** [content] with the host's scroll sink around it, if there is one. */
@Composable
fun KitPage(content: @Composable () -> Unit) {
    val sink = KitScroll.connection
    if (sink == null) content()
    else androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.nestedScroll(sink),
    ) { content() }
}
