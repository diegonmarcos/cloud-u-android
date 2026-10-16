package com.diegonmarcos.cloudnav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager

/**
 * A child [Fragment] inside a Compose page. [key] changes -> the previous child is removed and
 * [create]'s fragment takes the container. Added to the container VIEW (not looked up by id) because
 * this runs before the container is attached to the window.
 */
@Composable
fun FragmentHost(fm: FragmentManager, containerId: Int, key: Any, modifier: Modifier = Modifier, create: () -> Fragment) {
    AndroidView(
        modifier = modifier,
        factory = { ctx -> FragmentContainerView(ctx).apply { id = containerId } },
        update = { container ->
            if (container.tag != key) {
                container.tag = key
                val old = fm.findFragmentById(containerId)
                fm.beginTransaction().apply {
                    if (old != null) remove(old)
                    add(container, create(), null)
                }.commitAllowingStateLoss()
            }
        },
    )
}
