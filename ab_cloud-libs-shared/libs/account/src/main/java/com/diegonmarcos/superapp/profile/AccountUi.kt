package com.diegonmarcos.superapp.profile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitState

/**
 * The two building blocks every Cloud Account section page is drawn from (#1081): a [GroupTitle] (libs:ui-kit) over a group,
 * a [Tile] per thing in it. Thin wrappers over the ui-kit's dense eyebrow header and list row, so a page names
 * no size, colour or padding of its own and the sections cannot drift apart.
 */
@Composable
fun Tile(
    label: String,
    modifier: Modifier = Modifier,
    secondary: String = "",
    pill: Pair<String, KitState>? = null,
    tag: String = label,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) = KitListRow(label, modifier, secondary = secondary, pill = pill, tag = tag, onClick = onClick, trailing = trailing)
