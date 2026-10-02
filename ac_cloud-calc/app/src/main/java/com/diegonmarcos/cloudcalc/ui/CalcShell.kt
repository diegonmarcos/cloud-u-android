@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diegonmarcos.cloudcalc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.R
import com.diegonmarcos.cloudcalc.engine.CalcApi
import com.diegonmarcos.superapp.bottomnav.BottomNavEntry
import com.diegonmarcos.superapp.bottomnav.BottomNavIsland
import com.diegonmarcos.superapp.bottomnav.bottomNavInsets
import com.diegonmarcos.superapp.bottomnav.rememberBottomNavCollapse

/**
 * The shell: the section row (#770: Calculator, Measure, Jev — build.json::ui.sections), the
 * selected tab's modes, and the fleet's bottom-nav island showing that section's tabs. Tabs are
 * build.json::ui.tabs in declared order; a tab shows its modes (ui.modes whose `tab` is its id)
 * as a chip strip when it has more than one.
 */
@Composable
fun CalcShell(api: CalcApi, state: CalcState) {
    CompositionLocalProvider(LocalCalcApi provides api, LocalCalcState provides state) {
        val collapse = rememberBottomNavCollapse()
        val insets = bottomNavInsets()
        val entries = Declarations.tabsOf(state.section).map { BottomNavEntry(it.id, it.label, rememberVectorPainter(IconCatalog.vector(it.icon))) }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(CalcTags.SHELL)) {
            SectionRow(state)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .consumeWindowInsets(insets.only(WindowInsetsSides.Bottom))
                    .nestedScroll(collapse),
            ) {
                key(state.tab) { TabContent(state.tab) }
            }
            BottomNavIsland(
                entries = entries,
                selectedId = state.tab,
                onSelect = { state.tab = it.id },
                collapsed = collapse.collapsed,
                insets = insets,
            )
        }
    }
}

/** #770 the three top-level sections (build.json::ui.sections) as one segmented row. */
@Composable
private fun SectionRow(state: CalcState) {
    val sections = Declarations.sections
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = CalcMetrics.gutter, vertical = CalcMetrics.small),
    ) {
        sections.forEachIndexed { i, sec ->
            SegmentedButton(
                selected = sec.id == state.section,
                onClick = { state.showSection(sec.id) },
                shape = SegmentedButtonDefaults.itemShape(i, sections.size),
                icon = { Icon(IconCatalog.vector(sec.icon), contentDescription = null) },
                label = { Text(sec.label) },
                modifier = Modifier.testTag(CalcTags.section(sec.id)),
            )
        }
    }
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
            LazyRow(
                Modifier.fillMaxWidth().testTag(CalcTags.STRIP),
                contentPadding = PaddingValues(horizontal = CalcMetrics.gutter),
                horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap),
            ) {
                items(modes, key = { it.id }) { m ->
                    FilterChip(
                        selected = m.id == selected,
                        onClick = { state.modeByTab[tabId] = m.id },
                        label = { Text(m.label) },
                        modifier = Modifier.testTag(CalcTags.chip(m.id)),
                    )
                }
            }
        }
        val mode = modes.first { it.id == selected }
        key(mode.id) { ModeScreen(mode) }
    }
}

/** The test tags the Robolectric smoke test drives the shell by. */
object CalcTags {
    const val SHELL = "calc_shell"
    const val STRIP = "calc_mode_strip"
    const val INPUT = "calc_input"
    const val RESULT = "calc_result"
    fun tab(id: String) = "calc_tab_$id"
    fun section(id: String) = "calc_section_$id"
    fun chip(id: String) = "calc_chip_$id"
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
