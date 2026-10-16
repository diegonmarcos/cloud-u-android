package com.diegonmarcos.superapp.uikit

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow

/**
 * A Fragment inside a Compose screen, for a host whose page is still a Fragment of a shared
 * library (the wallet, the browser). The activity becomes a Compose shell; this is the one place
 * it still touches the FragmentManager.
 *
 * The container exists only once Compose has composed, which is after the activity restored its
 * fragments, so a restored fragment would have nowhere to attach: the host activity passes its
 * saved state through [withoutFragments] in onCreate and this adds a fresh fragment into the
 * container as soon as it is on screen. A host whose fragment keeps its own state (the browser's
 * tabs live in its store) loses nothing by that.
 */
@Composable
fun KitFragmentHost(containerId: Int, modifier: Modifier = Modifier, tag: String? = null, create: () -> Fragment) {
    val activity = LocalContext.current.findFragmentActivity()
    AndroidView(
        modifier = modifier,
        factory = { ctx -> FragmentContainerView(ctx).apply { id = containerId } },
        update = { container ->
            // Added to the container VIEW, not looked up by id: this runs before the container is attached to the window.
            val fm = activity.supportFragmentManager
            if (fm.findFragmentById(containerId) == null) fm.commitNow { add(container, create(), tag) }
        },
    )
}

/** The saved state to hand FragmentActivity.onCreate when the page is a [KitFragmentHost]. */
object KitFragmentHostState {
    private const val FRAGMENTS_TAG = "android:support:fragments"

    fun withoutFragments(saved: Bundle?): Bundle? = saved?.also { it.remove(FRAGMENTS_TAG) }
}

private fun Context.findFragmentActivity(): FragmentActivity {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    error("KitFragmentHost needs a FragmentActivity host")
}
