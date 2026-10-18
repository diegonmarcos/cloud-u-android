package com.diegonmarcos.cloudstore

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.appstore.StoreDensity
import com.diegonmarcos.superapp.bottomnav.NavDecl

/**
 * What the Store puts in the top bar's drawer ([com.diegonmarcos.superapp.bottomnav.TopBarDrawer], called by
 * MainActivity's shell): the Store's own categories, from the same build.json::ui the island and the page strip
 * read, so nothing is typed here. First the OPEN section's pages (Phone: Installed | Declared, Feed:
 * Commits | CI-CD, Access: its four) when it has a strip, then every island section. A tap navigates and closes the
 * drawer. Configs is not listed: it is the top bar's Settings > Configs, once.
 *
 * [currentPage] is the open child page of [selected] (null when that section has no strip).
 */
@Composable
internal fun ColumnScope.StoreDrawer(
    nav: NavDecl,
    selected: String,
    currentPage: String?,
    onSection: (String) -> Unit,
    onPage: (section: String, page: String) -> Unit,
    close: () -> Unit,
) {
    val gutter = StoreDensity.dpValue(StoreDensity.S12).dp
    val section = nav.section(selected)
    if (section != null && currentPage != null) {
        Eyebrow(section.label)
        section.pages.forEach { page ->
            NavigationDrawerItem(
                label = { Text(page.label) },
                selected = page.id == currentPage,
                onClick = { onPage(section.id, page.id); close() },
                modifier = Modifier.padding(horizontal = gutter).testTag("store_drawer_page_${page.id}"),
            )
        }
    }
    Eyebrow("Store")
    nav.bottomSections().forEach { s ->
        NavigationDrawerItem(
            label = { Text(s.label) },
            selected = s.id == selected,
            onClick = { onSection(s.id); close() },
            modifier = Modifier.padding(horizontal = gutter).testTag("store_drawer_section_${s.id}"),
        )
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(
        text.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = StoreDensity.T_CAPTION.sp,
        modifier = Modifier.padding(
            horizontal = StoreDensity.dpValue(StoreDensity.S12).dp,
            vertical = StoreDensity.dpValue(StoreDensity.S8).dp,
        ),
    )
}
