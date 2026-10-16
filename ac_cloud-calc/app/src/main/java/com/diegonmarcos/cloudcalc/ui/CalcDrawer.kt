package com.diegonmarcos.cloudcalc.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.R
import com.diegonmarcos.cloudcalc.sheetui.SheetHub

/**
 * What Cloud Calc puts in the top bar's drawer ([com.diegonmarcos.superapp.bottomnav.TopBarDrawer], called once by
 * CalcShell). It is the open page's: the Sheet page lists its sheets and the saved workbooks; every other page lists
 * the sections of the island and, when the open section has a strip, its pages. All of it is read from the
 * NavDecl (build.json::ui) or the sheet state, nothing is typed here. A tap navigates and closes the drawer.
 * Configs is not listed: it is the top bar's Settings > Configs, once.
 */
@Composable
internal fun ColumnScope.CalcDrawer(state: CalcState, close: () -> Unit) {
    if (Declarations.modesOf(state.tab).any { it.kind == "sheet" }) SheetDrawer(close) else NavDrawer(state, close)
}

@Composable
private fun ColumnScope.NavDrawer(state: CalcState, close: () -> Unit) {
    val section = Declarations.nav.section(state.section)
    if (section != null && section.pages.size > 1) {
        Eyebrow(section.label)
        section.pages.forEach { page ->
            Item(page.label, page.id == state.tab, DrawerTags.page(page.id)) { state.tab = page.id; close() }
        }
    }
    Eyebrow(stringResource(R.string.drawer_sections))
    Declarations.sections.forEach { s ->
        Item(s.label, s.id == state.section, DrawerTags.section(s.id)) { state.showSection(s.id); close() }
    }
}

/** The Sheet page's say: its sheets (the tabs under the grid) and the workbooks saved in app storage. */
@Composable
private fun ColumnScope.SheetDrawer(close: () -> Unit) {
    val st = SheetHub.get(LocalContext.current)
    st.rev // subscribe: a rename, an add or an open redraws the list
    Eyebrow(stringResource(R.string.drawer_sheets))
    st.wb.sheetNames().forEachIndexed { i, n ->
        Item(n, n == st.sheet, DrawerTags.sheet(n)) { st.switchTo(i); close() }
    }
    Item(stringResource(R.string.drawer_new_sheet), false, DrawerTags.NEW_SHEET) { st.addSheet(); close() }
    val books = st.files.names()
    if (books.isNotEmpty()) {
        Eyebrow(stringResource(R.string.drawer_workbooks))
        books.forEach { n -> Item(n, n == st.name, DrawerTags.workbook(n)) { st.open(n); close() } }
    }
}

@Composable
private fun Item(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label, maxLines = 1) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = CalcMetrics.gap).testTag(tag),
    )
}

@Composable
private fun Eyebrow(text: String) {
    Text(
        text.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(horizontal = CalcMetrics.gap, vertical = CalcMetrics.small),
    )
}

/** The test tags the Robolectric tests drive the drawer by. */
object DrawerTags {
    const val NEW_SHEET = "calc_drawer_new_sheet"
    fun section(id: String) = "calc_drawer_section_$id"
    fun page(id: String) = "calc_drawer_page_$id"
    fun sheet(name: String) = "calc_drawer_sheet_$name"
    fun workbook(name: String) = "calc_drawer_workbook_$name"
}
