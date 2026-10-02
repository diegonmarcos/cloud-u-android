package com.diegonmarcos.superapp.uikit

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
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

    /** The palette to draw with, read once per view creation (a theme switch recreates the view). */
    abstract fun palette(): KitPalette

    @Composable
    abstract fun Content()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        requireContext().kitComposeView(palette()) { Content() }
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
