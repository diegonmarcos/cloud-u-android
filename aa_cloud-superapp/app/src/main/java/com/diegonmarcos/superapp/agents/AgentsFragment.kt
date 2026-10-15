package com.diegonmarcos.superapp.agents

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.diegonmarcos.superapp.ShellActivity
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitEmptyState
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Configs ▸ Fleet ▸ Agents ▸ SuperApp agents | Fleet agents — one fragment, two scopes, hosted by the
 * Agents page's PageTabs strip (build.json `tabs`, SectionTabsFragment).
 *
 * STATUS FIRST: a banner with the counts, then one row per agent whose pill is its live state.
 * A tap opens the details (what it does, its trigger and kind, every live value, where it is
 * declared) and the Open action, which dispatches the row's declared target — a page: of this
 * app, or extapp:<app>#page:... for another app (installed: opened; absent: the install offer).
 *
 * Three states besides the list: loading (the declaration is read, then each live status off the
 * main thread, each row saying "checking" until its answer lands), empty (nothing declared for
 * this scope) and error (the baked registry does not parse).
 */
class AgentsFragment : KitComposeFragment() {

    companion object {
        const val SCOPE_SUPERAPP = "superapp"
        const val SCOPE_FLEET = "fleet"
        private const val ARG_SCOPE = "scope"
        const val CHECKING = "checking"

        fun newInstance(scope: String) = AgentsFragment().apply {
            arguments = Bundle().apply { putString(ARG_SCOPE, scope) }
        }
    }

    /** What the page is showing. */
    internal sealed class Ui {
        object Loading : Ui()
        data class Error(val message: String) : Ui()
        data class Loaded(val agents: List<FleetAgents.Agent>) : Ui()
    }

    private val scope: String get() = arguments?.getString(ARG_SCOPE) ?: SCOPE_FLEET

    internal var ui by mutableStateOf<Ui>(Ui.Loading)
        private set
    internal val live = mutableStateMapOf<String, AgentStatusReader.Live>()
    private var expanded by mutableStateOf<String?>(null)

    /** Where the declared agents come from; a test swaps it. */
    internal var source: () -> List<FleetAgents.Agent> = { FleetAgents.all() }

    override fun palette() = LauncherPalette.kit(requireContext())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        load()
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    internal fun load() {
        ui = Ui.Loading
        val agents = runCatching { FleetAgents.forScope(source(), scope) }
            .getOrElse { ui = Ui.Error(it.message ?: it.javaClass.simpleName); return }
        ui = Ui.Loaded(agents)
        val ctx = requireContext().applicationContext
        lifecycleScope.launch {
            for (a in agents) {
                val l = withContext(Dispatchers.IO) { AgentStatusReader.read(ctx, a) }
                live[a.id] = l
            }
        }
    }

    @Composable
    override fun Content() {
        when (val u = ui) {
            Ui.Loading -> KitEmptyState(null, "Reading the agent registry…", Modifier.testTag("agents:loading"))
            is Ui.Error -> Column(Modifier.fillMaxSize().padding(16.dp).testTag("agents:error")) {
                KitStatusBanner("The agent registry could not be read: ${u.message}", KitState.BAD, tag = "agents",
                    action = KitAction("Retry", "agents:retry") { load() })
            }
            is Ui.Loaded ->
                if (u.agents.isEmpty()) KitEmptyState("No agents",
                    if (scope == SCOPE_SUPERAPP) "Cloud SuperApp declares no agents in its build.json fleet_agents."
                    else "No app in the fleet declares fleet_agents in its build.json.",
                    Modifier.testTag("agents:empty"))
                else Loaded(u.agents)
        }
    }

    @Composable
    private fun Loaded(agents: List<FleetAgents.Agent>) {
        val p = LocalKitPalette.current
        val known = agents.mapNotNull { live[it.id] }
        val busy = known.count { it.state == KitState.BUSY }
        val ok = known.count { it.state == KitState.OK }
        val attention = known.count { it.state == KitState.WARN || it.state == KitState.BAD }
        val summary = buildString {
            append("${agents.size} agents")
            if (scope == SCOPE_FLEET) append(" in ${agents.map { it.app }.distinct().size} apps")
            if (known.size < agents.size) append(" · checking ${agents.size - known.size}")
            else append(" · $ok ok · $busy working · $attention need attention")
        }
        val bannerState = when {
            known.size < agents.size -> KitState.BUSY
            attention > 0 -> KitState.WARN
            else -> KitState.OK
        }
        val groups = agents.groupBy { it.appLabel }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("agents:list"),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                Spacer(Modifier.height(12.dp))
                KitStatusBanner(summary, bannerState, tag = "agents",
                    action = KitAction("Refresh", "agents:refresh") { live.clear(); load() })
                Spacer(Modifier.height(8.dp))
            }
            for ((app, rows) in groups) {
                if (scope == SCOPE_FLEET) item(key = "h:$app") {
                    KitSectionHeader(app, "", Modifier.padding(top = 8.dp), eyebrow = true)
                }
                items(rows, key = { it.id }) { a ->
                    val l = live[a.id]
                    Column {
                        KitListRow(
                            label = a.name,
                            secondary = "${a.kind} · ${a.trigger}",
                            pill = (l?.word ?: CHECKING) to (l?.state ?: KitState.IDLE),
                            tag = "agent:${a.id}",
                            onClick = { expanded = if (expanded == a.id) null else a.id },
                        )
                        if (expanded == a.id) Details(a, l)
                    }
                }
            }
            item {
                Text("Declared in each app's build.json fleet_agents; live status only through this phone's own channels.",
                    color = p.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.padding(vertical = 16.dp))
            }
        }
    }

    @Composable
    private fun Details(a: FleetAgents.Agent, l: AgentStatusReader.Live?) {
        val p = LocalKitPalette.current
        KitCard(Modifier.testTag("agent:details:${a.id}")) {
            if (a.summary.isNotBlank()) Text(a.summary, color = p.textPrimary, fontSize = KitDensity.body)
            val lines = buildList {
                add("App" to a.appLabel)
                add("Kind" to a.kind)
                add("Trigger" to a.trigger)
                add("Status" to (l?.word ?: CHECKING))
                l?.details?.let { addAll(it) }
                if (a.service.isNotBlank()) add("Service" to a.service)
                if (a.usedBy.isNotEmpty()) add("Used by" to a.usedBy.joinToString(", "))
                add("Declared in" to a.declaredIn)
            }
            for ((k, v) in lines) Text("$k: $v", color = p.textSecondary, fontSize = KitDensity.caption)
            KitActionBar(listOf(KitAction(if (a.isSelf) "Open" else "Open in ${a.appLabel}", "agent:open:${a.id}") {
                (activity as? ShellActivity)?.dispatchTarget(a.open)
            }))
        }
    }
}
