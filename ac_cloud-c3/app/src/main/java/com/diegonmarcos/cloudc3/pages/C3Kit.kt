package com.diegonmarcos.cloudc3.pages

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.core.content.ContextCompat
import com.diegonmarcos.cloudc3.R
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * 2026-10-15 owner decision: all apps must be Compose. C3's own pages paint with the fleet's
 * kit theme; this maps C3's colour RESOURCES onto the kit's palette roles, so no colour is
 * spelled in Kotlin.
 */
internal fun c3KitPalette(ctx: Context): KitPalette {
    fun c(id: Int) = ContextCompat.getColor(ctx, id)
    return KitPalette.fromArgb(
        surface = c(R.color.c3_surface),
        surfaceSelected = c(R.color.c3_surface_raised),
        textPrimary = c(R.color.c3_text),
        textSecondary = c(R.color.c3_text_secondary),
        accent = c(R.color.c3_accent_on_card),
        hairline = c(R.color.c3_outline),
        tileInk = c(R.color.c3_background),
    )
}

/** The bordered rounded card every C3 list row and block sits in; tappable when [onClick] is set. */
@Composable
internal fun C3Card(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(KitDensity.medium)
    Column(
        Modifier.fillMaxWidth().padding(bottom = KitDensity.small)
            .background(p.surface, shape).border(1.dp, p.hairline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(KitDensity.medium),
        content = content,
    )
}

/**
 * A child [Fragment] inside a Compose page: the one place the pages still touch a FragmentManager
 * (the paged tabs swap their page fragment here, the shell swaps its tab). [key] changes → the
 * previous child is removed and [create]'s fragment takes the container.
 *
 * Added to the container VIEW, not looked up by id: this runs before the container is attached
 * to the window, so a replace-by-id would find nothing to attach to.
 */
@Composable
internal fun C3FragmentHost(
    fm: FragmentManager,
    containerId: Int,
    key: Any?,
    modifier: Modifier = Modifier,
    create: () -> Fragment,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx -> FragmentContainerView(ctx).apply { id = containerId } },
        update = { container ->
            if (container.getTag(R.id.c3_host_key) != key) {
                container.setTag(R.id.c3_host_key, key)
                // Everything in this container goes: a page pushed OVER a tab (add + back stack)
                // is in it too, and a tab switch must not leave it standing on the new tab.
                val old = fm.fragments.filter { it.id == containerId }
                fm.beginTransaction().apply {
                    old.forEach { remove(it) }
                    add(container, create(), null)
                }.commitAllowingStateLoss()
            }
        },
    )
}
