package com.diegonmarcos.superapp.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * A Compose view for a window a Service adds through WindowManager (the floating nav bubble and menu, the screensaver cover).
 * Such a window has no Activity, so nothing supplies the lifecycle, saved-state and view-model owners a Compose view refuses
 * to attach without; this carries a minimal set on the view itself, resumed from the moment it exists, and [destroy]
 * disposes the composition and ends the lifecycle when the window is removed.
 */
class OverlayCompose(ctx: Context, content: @Composable () -> Unit) {

    private val owner = Owner()

    /** The view to hand to WindowManager.addView. */
    val view: ComposeView = ComposeView(ctx).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setContent(content)
    }

    /** Call after the window is removed. */
    fun destroy() {
        view.disposeComposition()
        owner.finish()
    }

    private class Owner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
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

        fun finish() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}
