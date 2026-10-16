package com.diegonmarcos.superapp.uikit

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.AbstractComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * A kit screen that lives INSIDE an input-method window (the keyboard's voice, translate and media
 * surfaces). An InputMethodService's window carries no lifecycle, saved-state or view-model owner,
 * and a Compose view refuses to attach without them, so this base installs a minimal always-resumed
 * set on the window's root the first time any such view attaches; every later one finds it.
 *
 * Subclasses keep the public surface of the View they replace (the keyboard's Java code constructs
 * and drives them) and only supply the palette and the body.
 */
abstract class KitImeComposeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AbstractComposeView(context, attrs) {

    /** The palette to draw with (the keyboard's, resolved from its theme). */
    abstract fun palette(): KitPalette

    @Composable
    abstract fun Body()

    @Composable
    final override fun Content() {
        CloudKitTheme(palette()) { Body() }
    }

    override fun onAttachedToWindow() {
        ensureOwners(rootView)
        super.onAttachedToWindow()
    }

    private fun ensureOwners(root: View) {
        if (root.findViewTreeLifecycleOwner() != null && root.findViewTreeSavedStateRegistryOwner() != null) return
        val owner = ImeWindowOwner()
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
        root.setViewTreeViewModelStoreOwner(owner)
    }

    /** Created, started and resumed once; an IME window has no finer lifecycle than "exists". */
    private class ImeWindowOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val viewModelStore: ViewModelStore = ViewModelStore()
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

        init {
            controller.performAttach()
            controller.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }
    }
}
