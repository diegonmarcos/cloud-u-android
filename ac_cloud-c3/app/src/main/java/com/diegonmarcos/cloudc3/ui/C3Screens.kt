package com.diegonmarcos.cloudc3.ui

import androidx.compose.runtime.Composable
import com.diegonmarcos.cloudc3.apps.AppsScreen
import com.diegonmarcos.cloudc3.configs.ConfigsScreen
import com.diegonmarcos.cloudc3.home.HomeScreen
import com.diegonmarcos.cloudc3.observ.ObservScreen
import com.diegonmarcos.cloudc3.topology.TopologyScreen

/**
 * #648 THE ONE place Kotlin names a tab id. Five branches, one per ui.tabs entry, and the
 * `when` is EXHAUSTIVE over the declaration by test rather than by the compiler: a tab id
 * is a declared string, so the compiler cannot check it and test/test-c3-shell.sh diffs
 * this dispatch against build.json::ui.tabs in BOTH directions instead. A declared tab with
 * no branch here, and a branch here for a tab nobody declared, are each a build failure —
 * which is what makes "no orphaned screen" an asserted property and not a hope.
 *
 * The fall-through deliberately draws nothing: [C3Shell] has already answered an undeclared
 * id with its stated empty state, so a second answer here would be unreachable.
 *
 * There is deliberately no companion list of these ids. A second statement of the same fact
 * is the #405 group_members mistake with a new name: the tester reads the branch labels off
 * this `when`, so the dispatch is the only declaration of what Kotlin answers.
 */
object C3Screens {

    @Composable
    fun Content(tabId: String, reselectTick: Int) {
        when (tabId) {
            "topology" -> TopologyScreen(reselectTick)
            "observ" -> ObservScreen(reselectTick)
            "home" -> HomeScreen(reselectTick)
            "apps" -> AppsScreen(reselectTick)
            "configs" -> ConfigsScreen(reselectTick)
            else -> Unit
        }
    }
}
