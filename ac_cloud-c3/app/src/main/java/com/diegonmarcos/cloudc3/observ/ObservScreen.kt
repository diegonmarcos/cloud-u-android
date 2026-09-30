package com.diegonmarcos.cloudc3.observ

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
 * #648 OBSERV — what the fleet is doing: health, workflows, logs, reports.
 *
 * This replaces the SuperApp's `page:c3/observability`, which was a `facet: true` tile grid
 * over the hidden `health` / `workflows` / `logs` / `reports` pages of ui.sections[c3]. Those
 * are now this tab's DECLARED sub-pages (build.json::ui.observ.pages), drawn by the SAME
 * shared [PageStrip] the Topology tab uses — one strip mechanism, not two.
 *
 * Health was the one C3 page with a real fragment behind it in the SuperApp
 * (cloud/C3HealthFragment.kt, a View/Fragment surface reading the c3-infra-api). It is
 * re-implemented here in Compose against the SAME ui.c3_ops.base_url rather than moved,
 * because that fragment's neighbours in the SuperApp's cloud/ package (OpsClient,
 * ContainerSheet, CloudData, MeshView) are load-bearing for FOUR other superapp surfaces —
 * WireGuard, the launcher aggregator stacks, superapp search and the RSS feed — so lifting
 * the package wholesale would break all four. See the ticket report.
 *
 * The bodies are not built in this commit and SAY so rather than rendering an empty box: a
 * page that claims a status it has not measured is exactly the defect an ops surface exists
 * to catch, so none of them claims anything yet.
 */
@Composable
fun ObservScreen(reselectTick: Int) {
    val pages = Declarations.observPages
    var selected by rememberSaveable { mutableStateOf(pages.firstOrNull()?.id ?: "") }

    Column(Modifier.fillMaxSize().testTag(TAG_OBSERV)) {
        Spacer(Modifier.height(C3Metrics.gutter))
        PageStrip(pages = pages, selectedId = selected, onSelect = { selected = it })
        Spacer(Modifier.height(C3Metrics.gutter))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val page = pages.firstOrNull { it.id == selected }
            if (page != null) NotBuiltYet(page)
        }
    }
}

internal const val TAG_OBSERV: String = "c3_observ"
