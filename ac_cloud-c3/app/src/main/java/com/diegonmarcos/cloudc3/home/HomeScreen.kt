package com.diegonmarcos.cloudc3.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.ui.C3Card
import com.diegonmarcos.cloudc3.ui.C3Metrics
import com.diegonmarcos.cloudc3.ui.C3Row

/**
 * #648 HOME — the centre tab and the app's overview, the position every other fleet
 * five-tab shell gives Home.
 *
 * It is deliberately a SUMMARY OF THE OTHER TABS' declarations and holds no state of its
 * own: one card per content tab, listing that tab's declared sub-pages, plus the Apps card
 * listing the three sibling APKs. So the overview cannot drift from what the app actually
 * offers — adding a page in build.json adds it here too, with no edit.
 *
 * The live numbers (how many VMs are up, how many workflows failed) belong to the tabs that
 * measure them and arrive when those tabs are wired; Home will read the SAME state they
 * render rather than fetching its own, which is the rule that keeps two screens from
 * disagreeing about one machine.
 */
@Composable
fun HomeScreen(reselectTick: Int) {
    LazyColumn(
        Modifier.fillMaxSize().testTag(TAG_HOME),
        contentPadding = PaddingValues(C3Metrics.gutter),
        verticalArrangement = Arrangement.spacedBy(C3Metrics.gap),
    ) {
        item {
            C3Card(title = TITLE_TOPOLOGY) {
                Column {
                    Declarations.topologyPages.forEach { C3Row(it.label, "") }
                }
            }
        }
        item {
            C3Card(title = TITLE_OBSERV) {
                Column {
                    Declarations.observPages.forEach { C3Row(it.label, "") }
                }
            }
        }
        item {
            C3Card(title = TITLE_APPS) {
                Column {
                    Declarations.externalApps.forEach { C3Row(it.label, it.packageName) }
                }
            }
        }
    }
}

// The three card titles. These name the TABS, so they are read from the tab declaration
// rather than spelled here — a title that disagreed with its tab's label would be the
// #380/#381 defect shape (one thing named twice, drifting).
private val TITLE_TOPOLOGY: String get() = labelOf("topology")
private val TITLE_OBSERV: String get() = labelOf("observ")
private val TITLE_APPS: String get() = labelOf("apps")

private fun labelOf(tabId: String): String =
    Declarations.tabs.firstOrNull { it.id == tabId }?.label ?: tabId

internal const val TAG_HOME: String = "c3_home"
