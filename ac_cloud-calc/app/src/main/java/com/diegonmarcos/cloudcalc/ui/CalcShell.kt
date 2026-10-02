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
 * The shell: the selected tab's modes above the fleet's bottom-nav island. Tabs are
 * build.json::ui.tabs in declared order; a tab shows its modes (ui.modes whose `tab` is its id)
 * as a chip strip when it has more than one.
 */
@Composable
fun CalcShell(api: CalcApi, state: CalcState) {
    CompositionLocalProvider(LocalCalcApi provides api, LocalCalcState provides state) {
        val collapse = rememberBottomNavCollapse()
        val insets = bottomNavInsets()
        val entries = Declarations.tabs.map { BottomNavEntry(it.id, it.label, rememberVectorPainter(IconCatalog.vector(it.icon))) }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(CalcTags.SHELL)) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .consumeWindowInsets(insets.only(WindowInsetsSides.Bottom))
                    .windowInsetsPadding(WindowInsets.statusBars)
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
    fun chip(id: String) = "calc_chip_$id"
    fun mode(id: String) = "calc_mode_$id"
    fun key(label: String) = "calc_key_$label"
}
