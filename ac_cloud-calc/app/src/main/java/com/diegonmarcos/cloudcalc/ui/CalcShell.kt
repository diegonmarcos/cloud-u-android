@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diegonmarcos.cloudcalc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.R
import com.diegonmarcos.cloudcalc.engine.CalcApi
import com.diegonmarcos.superapp.bottomnav.BottomNavHost
import com.diegonmarcos.superapp.bottomnav.PageTabs
import com.diegonmarcos.superapp.bottomnav.islandEntries

/**
 * The shell (#868, INVERTED from #770): the fleet's bottom-nav island carries the three sections
 * (Calculator, Measure, Jev - build.json::ui.bottom_nav), the section's PAGES (what #770 called
 * tabs, ui.sections[].pages) are the top PageTabs strip, and a page shows its modes (ui.modes
 * whose `tab` is its id) as the sub-strip under it when it has more than one.
 */
@Composable
fun CalcShell(api: CalcApi, state: CalcState) {
    CompositionLocalProvider(LocalCalcApi provides api, LocalCalcState provides state) {
        val entries = Declarations.nav.islandEntries { rememberVectorPainter(IconCatalog.vector(it)) }
        // The fleet's own host: the page above the island, the scroll collapse, the inset handling.
        BottomNavHost(
            entries = entries,
            selectedId = state.section,
            onSelect = { state.showSection(it.id) },
            modifier = Modifier.testTag(CalcTags.SHELL),
        ) {
            Column(Modifier.fillMaxSize()) {
                PageStrip(state)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    key(state.tab) { TabContent(state.tab) }
                }
            }
        }
    }
}

/** The selected section's pages (build.json::ui.sections[].pages) as the top pill strip. */
@Composable
private fun PageStrip(state: CalcState) {
    val pages = Declarations.nav.section(state.section)?.pages.orEmpty()
    PageTabs(
        pages = pages,
        selectedId = state.tab,
        onSelect = { state.tab = it.id },
        modifier = Modifier.testTag(CalcTags.PAGES),
    )
}

@Composable
private fun TabContent(tabId: String) {
    val state = LocalCalcState.current
    val modes = Declarations.modesOf(tabId)
    if (modes.isEmpty()) {
        Text(stringResource(R.string.no_modes, tabId), Modifier.padding(CalcMetrics.gutter))
        return
    }
    val selected = state.modeByTab[tabId]?.takeIf { id -> modes.any { it.id == id } } ?: modes.first().id
    Column(Modifier.fillMaxSize().testTag(CalcTags.tab(tabId))) {
        if (modes.size > 1) {
            // The page's modes are a dense icon row: icon over a short label, no minimum height.
            ModeIcons(modes, selected, Declarations.tabs.firstOrNull { it.id == tabId }?.icon.orEmpty()) { state.modeByTab[tabId] = it }
        }
        val mode = modes.first { it.id == selected }
        key(mode.id) { ModeScreen(mode) }
    }
}

/** One mode per cell: [Declarations.Mode.icon] (else the page's [pageIcon]) over its short label; scrolls when it must. */
@Composable
private fun ModeIcons(modes: List<Declarations.Mode>, selectedId: String, pageIcon: String, onSelect: (String) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().testTag(CalcTags.STRIP),
        contentPadding = PaddingValues(horizontal = CalcMetrics.small),
        horizontalArrangement = Arrangement.spacedBy(CalcMetrics.hairline),
    ) {
        items(modes, key = { it.id }) { m ->
            val on = m.id == selectedId
            val tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Column(
                Modifier.clip(RoundedCornerShape(CalcMetrics.small))
                    .background(if (on) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                    .clickable { onSelect(m.id) }
                    .padding(horizontal = CalcMetrics.gap, vertical = CalcMetrics.small)
                    .testTag(PageTabsTags.tab(m.id)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(IconCatalog.vector(m.icon.ifBlank { pageIcon }), contentDescription = m.label, tint = tint, modifier = Modifier.size(CalcMetrics.modeIcon))
                Text(m.short.ifBlank { m.label }, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
            }
        }
    }
}

/** The test tags the Robolectric smoke test drives the shell by. */
object CalcTags {
    const val SHELL = "calc_shell"
    const val STRIP = "calc_mode_strip"
    const val PAGES = "calc_page_strip"
    const val INPUT = "calc_input"
    const val RESULT = "calc_result"
    const val DISPLAY = "calc_display"
    const val CONVERT_EQ = "calc_convert_eq"
    const val CONVERT_SWAP = "calc_convert_swap"
    const val MATRIX = "calc_rates_matrix"
    fun matrixCell(row: String, col: String) = "calc_rates_cell_${row}_$col"
    fun pickerItem(name: String) = "calc_picker_$name"
    const val HISTORY_LIST = "calc_history_list"
    fun historyExpr(i: Int) = "calc_history_expr_$i"
    fun historyResult(i: Int) = "calc_history_result_$i"
    fun historyDelete(i: Int) = "calc_history_delete_$i"
    const val HISTORY_CLEAR = "calc_history_clear"
    fun tab(id: String) = "calc_tab_$id"
    fun mode(id: String) = "calc_mode_$id"
    fun key(label: String) = "calc_key_$label"

    // #770 the Jev section and the follow-up question box.
    const val JEV_INPUT = "jev_input"
    const val JEV_ASK = "jev_ask"
    const val JEV_VERDICT = "jev_verdict"
    const val JEV_TOKEN = "jev_token"
    const val JEV_TEST_TOKEN = "jev_test_token"
    const val JEV_NOTE = "jev_note"
    const val JEV_ROUTING_JSON = "jev_routing_json"
    const val JEV_ROUTING_SAVE = "jev_routing_save"
    const val JEV_TEST_INPUT = "jev_test_input"
    const val JEV_TEST_RUN = "jev_test_run"
    const val ASK_TOGGLE = "ask_toggle"
    const val ASK_BOX = "ask_box"
    fun jevCandidate(id: String) = "jev_candidate_$id"
    fun askPreset(id: String) = "ask_preset_$id"
    fun jevModel(slug: String) = "jev_model_$slug"
    fun jevPicker(use: String) = "jev_picker_$use"
    fun jevTestColumn(model: String) = "jev_test_column_$model"
}
