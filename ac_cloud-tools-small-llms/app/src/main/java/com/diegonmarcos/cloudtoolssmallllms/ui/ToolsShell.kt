package com.diegonmarcos.cloudtoolssmallllms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.cloudtoolssmallllms.Decl
import com.diegonmarcos.superapp.bottomnav.BottomNavHost
import com.diegonmarcos.superapp.bottomnav.islandEntries
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width

/**
 * The shell (#868): the fleet's bottom island carries the four sections build.json::ui.bottom_nav
 * declares - Chat, Tasks, Costs, Settings - and each draws one page above it.
 */
@Composable
fun ToolsShell(model: ToolsModel) {
    val entries = Decl.nav.islandEntries { rememberVectorPainter(IconCatalog.vector(it)) }
    BottomNavHost(
        entries = entries,
        selectedId = model.section,
        onSelect = { model.section = it.id },
        modifier = Modifier.testTag(Tags.SHELL),
    ) {
        Box(Modifier.fillMaxSize()) {
            when (model.section) {
                "tasks" -> TasksPage(model)
                "costs" -> CostsPage(model)
                "settings" -> SettingsPage(model)
                else -> ChatPage(model)
            }
        }
    }
}

/** A small rounded chip: the data-dense control every page uses (task, model, cost, choices). */
@Composable
fun Chip(text: String, selected: Boolean = false, icon: String? = null, tag: String? = null, onClick: (() -> Unit)? = null) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    var m = Modifier.height(ToolsMetrics.chipHeight).clip(RoundedCornerShape(ToolsMetrics.corner)).background(bg)
        .border(ToolsMetrics.hairline, if (selected) Color.Transparent else MaterialTheme.colorScheme.outline, RoundedCornerShape(ToolsMetrics.corner))
    if (onClick != null) m = m.clickable(onClick = onClick)
    if (tag != null) m = m.testTag(tag)
    Row(m.padding(horizontal = ToolsMetrics.gap), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) {
            Icon(IconCatalog.vector(icon), contentDescription = null, tint = fg, modifier = Modifier.size(ToolsMetrics.icon))
            Spacer(Modifier.width(ToolsMetrics.small))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

/** The test tags the pages are driven by. */
object Tags {
    const val SHELL = "tools_shell"
    const val INPUT = "tools_input"
    const val SEND = "tools_send"
    const val TOKEN = "tools_token"
    const val TOKEN_SAVE = "tools_token_save"
    const val TOKEN_TEST = "tools_token_test"
    fun task(id: String) = "tools_task_$id"
    fun answer(i: Int) = "tools_answer_$i"
    fun router(id: String) = "tools_router_$id"
}
