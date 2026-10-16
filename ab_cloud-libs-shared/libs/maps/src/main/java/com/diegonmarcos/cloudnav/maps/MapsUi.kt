package com.diegonmarcos.cloudnav.maps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * The look every Maps / Cloud-Nav page shares: one dark palette (the old COL_* constants, now the
 * roles of a [KitPalette]) and the few parts the pages draw with. A page wraps itself in
 * CloudKitTheme through KitComposeFragment, so these read [LocalKitPalette] and hold no colour.
 */
internal object MapsInk {
    const val SURFACE   = 0xFF0B0F17.toInt()   // deep blue-black base
    const val CARD      = 0xFF141A25.toInt()   // the sheets' and the island's dark card
    const val PRIMARY   = 0xFFECEFF4.toInt()   // near-white body
    const val SECONDARY = 0xFF9AA3B2.toInt()   // muted grey caption
    const val ACCENT    = 0xFF4ADE80.toInt()   // vivid green subhead
    const val HAIRLINE  = 0xFF2A3342.toInt()
    const val WARN      = 0xFFFBBF24.toInt()   // bright amber (readable on dark)
    const val BAD       = 0xFFEF4444.toInt()
    const val OK        = 0xFF16A34A.toInt()

    fun palette(): KitPalette = KitPalette.fromArgb(
        surface = SURFACE, surfaceSelected = CARD, textPrimary = PRIMARY, textSecondary = SECONDARY,
        accent = ACCENT, hairline = HAIRLINE, tileInk = SURFACE, ok = OK, warn = WARN, bad = BAD,
    )
}

/** The scrolling body of a settings-like page: surface, 16 dp gutter, children stacked. */
@Composable
internal fun MapsPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalKitPalette.current
    Column(
        modifier.fillMaxSize().background(p.surface).verticalScroll(rememberScrollState()).padding(16.dp),
        content = content,
    )
}

@Composable
internal fun MapsHeading(text: String, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    Text(text, color = p.textPrimary, style = MaterialTheme.typography.headlineSmall,
        modifier = modifier.padding(bottom = 8.dp))
}

@Composable
internal fun MapsSubhead(text: String, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    Text(text, color = p.accent, style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(bottom = 4.dp))
}

@Composable
internal fun MapsCaption(text: String, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    Text(text, color = p.textSecondary, style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.padding(bottom = 12.dp))
}

/** A raised row-sized block; tappable (with the haptic tick) when [onClick] is set. */
@Composable
internal fun MapsTile(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalKitPalette.current
    val view = LocalView.current
    val tap = if (onClick == null) Modifier else Modifier.clickable { MapsHaptics.tap(view); onClick() }
    Column(
        modifier.fillMaxWidth().background(if (selected) p.surfaceSelected else p.surface).then(tap).padding(12.dp),
        content = content,
    )
}

/** "label ........ value" line of a read-only telemetry panel. */
@Composable
internal fun MapsKeyValue(key: String, value: String, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, color = p.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, color = p.textSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A page button that ticks the haptic motor like every control of the old pages did. */
@Composable
internal fun MapsButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val view = LocalView.current
    Button(onClick = { MapsHaptics.tap(view); onClick() }, enabled = enabled,
        modifier = modifier.testTag("maps:button:$label")) { Text(label) }
}

/** The dark bottom sheet every list / detail / picker of the Maps pages opens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapsSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalKitPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.surfaceSelected,
        contentColor = p.textPrimary,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            content = content)
    }
}

/**
 * A child [Fragment] inside a Compose page: the one place the pages still touch the child
 * FragmentManager (the map, the Export panel, the Timeline's three tabs). [key] changes → the
 * previous child is removed and [create]'s fragment takes the container.
 *
 * Added to the container VIEW, not looked up by id: this runs before the container is attached
 * to the window, which is also why the fragment is committed rather than replaced by id.
 */
@Composable
internal fun ChildFragmentHost(
    fm: FragmentManager,
    containerId: Int,
    key: Any,
    modifier: Modifier = Modifier,
    create: () -> Fragment,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx -> FragmentContainerView(ctx).apply { id = containerId } },
        update = { container ->
            if (container.getTag(R.id.maps_host_key) != key) {
                container.setTag(R.id.maps_host_key, key)
                val old = fm.findFragmentById(containerId)
                fm.beginTransaction().apply {
                    if (old != null) remove(old)
                    add(container, create(), null)
                }.commitAllowingStateLoss()
            }
        },
    )
}
