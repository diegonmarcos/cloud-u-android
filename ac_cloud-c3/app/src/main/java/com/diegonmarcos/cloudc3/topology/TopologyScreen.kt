package com.diegonmarcos.cloudc3.topology

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.ui.C3Metrics
import com.diegonmarcos.cloudc3.ui.NotBuiltYet
import com.diegonmarcos.cloudc3.ui.PageStrip

/**
 * #648 TOPOLOGY — the fleet's shape: the stack and the machines behind it.
 *
 * This replaces the SuperApp's `page:c3/topology`, which was a `facet: true` tile grid over
 * the hidden `stack` / `vms` pages of ui.sections[c3]. Those two are now this tab's DECLARED
 * sub-pages (build.json::ui.topology.pages) and the strip is the shared [PageStrip], so
 * adding a third is a build.json edit and not a new strip.
 *
 * The page bodies are not built in this commit and SAY so through [NotBuiltYet] rather than
 * rendering an empty box: the ops data comes from ui.c3_ops.base_url, the SAME c3-infra-api
 * the SuperApp reads, and wiring it is the next push. A page that claims a status it has not
 * measured is the defect this whole surface exists to catch, so it claims nothing yet.
 */
@Composable
fun TopologyScreen(reselectTick: Int) {
    val pages = Declarations.topologyPages
    var selected by rememberSaveable { mutableStateOf(pages.firstOrNull()?.id ?: "") }

    Column(Modifier.fillMaxSize().testTag(TAG_TOPOLOGY)) {
        Spacer(Modifier.height(C3Metrics.gutter))
        PageStrip(pages = pages, selectedId = selected, onSelect = { selected = it })
        Spacer(Modifier.height(C3Metrics.gutter))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val page = pages.firstOrNull { it.id == selected }
            if (page != null) NotBuiltYet(page)
        }
    }
}

internal const val TAG_TOPOLOGY: String = "c3_topology"
