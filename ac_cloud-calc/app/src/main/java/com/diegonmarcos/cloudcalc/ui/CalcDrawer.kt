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
 * What Cloud Calc's Sheet page puts at the end of the first row's title dropdown
 * ([com.diegonmarcos.superapp.bottomnav.TopBarDrawer], called once by CalcShell): its sheets (the tabs under the grid) and the
 * saved workbooks, read from the sheet state. The sections and pages are the dropdown's own hierarchy (the NavDecl), not listed
 * here. A tap acts and closes the dropdown.
 */
@Composable
internal fun ColumnScope.CalcDrawer(close: () -> Unit) = SheetDrawer(close)

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
    fun sheet(name: String) = "calc_drawer_sheet_$name"
    fun workbook(name: String) = "calc_drawer_workbook_$name"
}
