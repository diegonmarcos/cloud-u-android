package com.diegonmarcos.superapp.bottomnav

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.painterResource

/** One item as a View-based host declares it: a stable id, its label, and a drawable resource. */
public data class BottomNavViewItem(val id: String, val label: String, @DrawableRes val icon: Int)

/**
 * [BottomNavIsland] for an app whose shell is still an XML layout (superapp, cloud-me,
 * cloud-wallet — #531). It is a Compose HOST, not a View port: everything it draws is the
 * island, so the geometry every app shows is the one this module measures. What it adds is the
 * View-side contract those shells already speak:
 *
 *  - [items] / [selectedId] are plain properties. Setting [selectedId] only MOVES the pill; it
 *    never calls back, so a shell syncing the bar to where it navigated needs no re-entry guard.
 *  - a tap on an unselected item calls [onSelect], a tap on the selected one [onReselect]. The
 *    host decides whether the pill moves, by setting [selectedId].
 *  - [insets] null = the live window's (#477). A shell that already pads its own root for the
 *    system bars passes zero, or the island would clear the bar twice.
 *  - [colorScheme] carries the host's View theme into the island's pill and ink, so a themed
 *    launcher keeps its own light capsule. null = whatever MaterialTheme is around it.
 */
public class BottomNavIslandView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AbstractComposeView(context, attrs) {

    public var items: List<BottomNavViewItem> by mutableStateOf(emptyList())
    public var selectedId: String? by mutableStateOf(null)
    public var collapsed: Boolean by mutableStateOf(false)
    public var insets: WindowInsets? by mutableStateOf(null)
    public var colorScheme: ColorScheme? by mutableStateOf(null)
    public var itemModifier: (String) -> Modifier by mutableStateOf<(String) -> Modifier>({ Modifier })
    public var onSelect: (String) -> Unit = {}
    public var onReselect: (String) -> Unit = {}

    @Composable
    override fun Content() {
        val scheme = colorScheme
        if (scheme == null) Island() else MaterialTheme(colorScheme = scheme) { Island() }
    }

    @Composable
    private fun Island() {
        val entries = items.map { BottomNavEntry(it.id, it.label, painterResource(it.icon)) }
        val tap: (BottomNavEntry) -> Unit = { if (it.id == selectedId) onReselect(it.id) else onSelect(it.id) }
        val modify = itemModifier
        val fixed = insets
        if (fixed == null) {
            BottomNavIsland(entries, selectedId, tap, collapsed = collapsed, itemModifier = { modify(it.id) })
        } else {
            BottomNavIsland(entries, selectedId, tap, collapsed = collapsed, insets = fixed, itemModifier = { modify(it.id) })
        }
    }
}

/** The island's test tags, for a HOST's tests that measure the island it configured. */
public object BottomNavTags {
    public const val ISLAND: String = TAG_ISLAND
    public fun item(id: String): String = itemTag(id)
    public fun icon(id: String): String = iconTag(id)
    public fun label(id: String): String = labelTag(id)
}
