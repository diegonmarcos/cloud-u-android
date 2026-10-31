package com.diegonmarcos.superapp.navbartop1

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.bottomnav.AppTopBarLabels
import com.diegonmarcos.superapp.bottomnav.CleanDrawerEntry
import com.diegonmarcos.superapp.bottomnav.CleanDrawerModel
import com.diegonmarcos.superapp.bottomnav.DrawerRow
import com.diegonmarcos.superapp.bottomnav.NavQuickAction
import com.diegonmarcos.superapp.bottomnav.NavTokens
import com.diegonmarcos.superapp.bottomnav.NavUiDecl
import com.diegonmarcos.superapp.bottomnav.TopBarTags
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPage
import com.diegonmarcos.superapp.fleetconfigs.fleetConfigsEntryLabel

/** The Clean drawer's test tags. The hamburger keeps [TopBarTags.MENU] so an app's tests that open the drawer still do. */
public object CleanDrawerTags {
    public const val MENU: String = TopBarTags.MENU
    public const val PAGE_DROPDOWN: String = "navclean_page"
    public const val ACTIONS: String = "navclean_actions"
    public const val SUB_DROPDOWN: String = "navclean_sub"
    public const val CONFIGS_GENERAL: String = "navclean_configs_general"
    public const val CONFIGS_ABOUT: String = "navclean_configs_about"
    /** The former "Configs > UI" entry: the card is a section of Configs > General now. */
    public const val CONFIGS_UI: String = CONFIGS_GENERAL
    public fun pageEntry(id: String): String = "navclean_page_$id"
    public fun subEntry(id: String): String = "navclean_sub_$id"
    public fun action(a: NavQuickAction): String = "navclean_action_${a.declared}"
    public fun row(r: DrawerRow): String = "navclean_row_${r.declared}"
}

/**
 * The Clean mode's left drawer: what the Classic first and second rows held, in the order [DrawerRow] declares. Row 1, the page's
 * name as a dropdown like a URL bar (picking a page switches to it). Row 2, the AI / Profile / Settings icons. Row 3, a dropdown of the
 * child pages. The rows come from [model] ([com.diegonmarcos.superapp.bottomnav.cleanDrawerModel]), never from a literal here.
 */
@Composable
public fun ColumnScope.CleanDrawerContent(
    model: CleanDrawerModel,
    labels: AppTopBarLabels,
    onPage: (CleanDrawerEntry) -> Unit,
    onSubPage: (CleanDrawerEntry) -> Unit,
    onAi: () -> Unit,
    onProfile: () -> Unit,
    onConfigs: () -> Unit,
    close: () -> Unit,
    /** Non-null: an entry "Configs > General" opens the shared General page (its UI section switches back to Classic, scroll-hide). */
    onConfigsUi: (() -> Unit)? = null,
    /** Non-null: an entry "Configs > About" follows it. */
    onConfigsAbout: (() -> Unit)? = null,
) {
    val rowHeight = NavTokens().cleanDrawerRowHeight
    for (row in model.rows) when (row) {
        DrawerRow.PageDropdown -> UrlBarDropdown(
            face = model.pageName, entries = model.pages, tag = CleanDrawerTags.PAGE_DROPDOWN, entryTag = CleanDrawerTags::pageEntry,
            onPick = { close(); onPage(it) }, modifier = Modifier.testTag(CleanDrawerTags.row(row)),
        )
        DrawerRow.Actions -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(CleanDrawerTags.ACTIONS),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (a in model.actions) when (a) {
                NavQuickAction.Ai -> ActionIcon(Icons.Filled.AutoAwesome, labels.ai, CleanDrawerTags.action(a), rowHeight) { close(); onAi() }
                NavQuickAction.Profile -> ActionIcon(Icons.Filled.AccountCircle, labels.account, CleanDrawerTags.action(a), rowHeight) { close(); onProfile() }
                NavQuickAction.Settings -> ActionIcon(Icons.Filled.Settings, labels.configs, CleanDrawerTags.action(a), rowHeight) { close(); onConfigs() }
            }
        }
        DrawerRow.SubPageDropdown -> UrlBarDropdown(
            face = model.subPageName, entries = model.subPages, tag = CleanDrawerTags.SUB_DROPDOWN, entryTag = CleanDrawerTags::subEntry,
            onPick = { close(); onSubPage(it) }, modifier = Modifier.testTag(CleanDrawerTags.row(row)),
        )
    }
    if (onConfigsUi != null) Text(
        fleetConfigsEntryLabel(FleetConfigsPage.General),
        Modifier.fillMaxWidth().heightIn(min = rowHeight).clickable(role = Role.Button) { close(); onConfigsUi() }
            .padding(horizontal = 30.dp, vertical = 12.dp).testTag(CleanDrawerTags.CONFIGS_GENERAL),
    )
    if (onConfigsAbout != null) Text(
        fleetConfigsEntryLabel(FleetConfigsPage.About),
        Modifier.fillMaxWidth().heightIn(min = rowHeight).clickable(role = Role.Button) { close(); onConfigsAbout() }
            .padding(horizontal = 30.dp, vertical = 12.dp).testTag(CleanDrawerTags.CONFIGS_ABOUT),
    )
}

@Composable
private fun ActionIcon(icon: ImageVector, label: String, tag: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp)).testTag(tag)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp)) }
}

/** A rounded box showing [face] with a chevron, like a browser's URL bar; a tap opens [entries] and a pick calls [onPick]. */
@Composable
private fun UrlBarDropdown(
    face: String,
    entries: List<CleanDrawerEntry>,
    tag: String,
    entryTag: (String) -> String,
    onPick: (CleanDrawerEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = NavTokens().cleanDrawerRowHeight).clip(shape)
                .border(1.dp, LocalContentColor.current.copy(alpha = 0.35f), shape)
                .clickable(role = Role.Button) { open = true }
                .padding(horizontal = 14.dp).testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(face, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (e in entries) DropdownMenuItem(
                text = {
                    Text(
                        e.label, Modifier.padding(start = 16.dp * e.depth),
                        fontWeight = if (e.selected) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                onClick = { open = false; onPick(e) },
                modifier = Modifier.testTag(entryTag(e.id)),
            )
        }
    }
}
