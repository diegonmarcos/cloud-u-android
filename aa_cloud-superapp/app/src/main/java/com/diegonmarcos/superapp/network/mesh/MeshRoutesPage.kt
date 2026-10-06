package com.diegonmarcos.superapp.network.mesh

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.diegonmarcos.superapp.R

/**
 * #877 Routes: what the tunnel carries. The allowed-IP table per peer (derived by [Routes.derive], so the
 * split-or-full verdict and the NAT64 prefix are computed from the configuration, never typed), and the
 * apps kept OUTSIDE the tunnel (Interface.ExcludedApplications, which the engine's VpnService honours).
 */
@Composable
fun MeshRoutesPage(store: MeshStore, modifier: Modifier = Modifier) {
    val page = store.decl.page("routes")
    val peers = store.snapshot?.cfg?.peers.orEmpty()
    val table = remember(peers, store.decl.nat64Marker) { Routes.derive(peers, store.decl.nat64Marker) }
    Column(
        modifier.fillMaxSize().testTag(MeshTags.page("routes")).verticalScroll(rememberScrollState())
            .padding(MeshDensity.dp(MeshDensity.S8)),
    ) {
        MHeader(stringResource(R.string.mesh_routes_summary))
        KvRow(stringResource(R.string.mesh_k_mode), stringResource(
            when (table.mode) { RouteMode.NONE -> R.string.mesh_mode_none; RouteMode.SPLIT -> R.string.mesh_mode_split; RouteMode.FULL -> R.string.mesh_mode_full }),
            tag = MeshTags.row("route_mode"))
        KvRow(stringResource(R.string.mesh_k_v4), "%.1f %%".format(java.util.Locale.ROOT, table.v4Coverage * 100))
        KvRow(stringResource(R.string.mesh_k_nat64), table.nat64.joinToString(", ").ifBlank { stringResource(R.string.mesh_none) }, tag = MeshTags.row("route_nat64"))

        MHeader(stringResource(R.string.mesh_routes_table))
        page?.control("route_filter")?.let { c ->
            ChoiceLine(
                declLabel("ctl", c.id, c.label), c.choices.map { it to declLabel("opt", it, it) },
                store.view[c.id] ?: c.default, { store.run(c.engine, it) }, Modifier.testTag(MeshTags.control(c.id)),
            )
        }
        val shown = Routes.filter(table.rows, store.view["route_filter"] ?: "all")
        if (shown.isEmpty()) MText(stringResource(R.string.mesh_routes_none), size = MeshDensity.T_META, muted = true)
        shown.groupBy { it.peer }.forEach { (peer, rows) ->
            MText(peer, Modifier.padding(top = MeshDensity.dp(MeshDensity.S4)), size = MeshDensity.T_META, bold = true)
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = MeshDensity.dp(MeshDensity.S1)), horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S8))) {
                    MText(r.cidr, Modifier.weight(1f), size = MeshDensity.T_META, mono = true, maxLines = 1)
                    MText("v${r.family}", size = MeshDensity.T_CAPTION, muted = true, mono = true)
                    MText(kindWord(r.kind), size = MeshDensity.T_CAPTION, muted = true)
                }
            }
        }

        page?.control("excluded_apps")?.let { c ->
            MHeader(declLabel("ctl", c.id, c.label))
            Reason(stringResource(R.string.mesh_excluded_text))
            ExcludedApps(store, c)
        }
    }
}

@Composable
private fun kindWord(k: RouteKind): String = stringResource(when (k) {
    RouteKind.DEFAULT -> R.string.mesh_kind_default
    RouteKind.NAT64 -> R.string.mesh_kind_nat64
    RouteKind.HOST -> R.string.mesh_kind_host
    RouteKind.SUBNET -> R.string.mesh_kind_subnet
})

@Composable
private fun ExcludedApps(store: MeshStore, c: MeshDecl.Control) {
    var typed by remember { mutableStateOf("") }
    fun write(list: List<String>) = store.run(c.engine, list.joinToString(","))
    if (store.excluded.isEmpty()) MText(stringResource(R.string.mesh_excluded_none), size = MeshDensity.T_META, muted = true, modifier = Modifier.testTag("mesh:excluded:none"))
    store.excluded.forEach { pkg ->
        Row(Modifier.fillMaxWidth().padding(vertical = MeshDensity.dp(MeshDensity.S2)), verticalAlignment = Alignment.CenterVertically) {
            MText(pkg, Modifier.weight(1f), size = MeshDensity.T_META, mono = true, maxLines = 1)
            MButton("✕", { write(store.excluded - pkg) }, Modifier.testTag("mesh:excluded:remove:$pkg"))
        }
    }
    FieldLine(stringResource(R.string.mesh_excluded_add)) {
        MField("", { v -> typed = ""; write(store.excluded + v.trim()) }, hint = "com.example.app", onChange = { typed = it })
    }
    // Suggestions come from the installed launchable apps; the list is read once per page.
    LaunchedLoadApps(store)
    val q = typed.lowercase()
    if (q.isNotBlank()) store.apps.filter { (pkg, label) -> (pkg.contains(q) || label.lowercase().contains(q)) && pkg !in store.excluded }.take(6).forEach { (pkg, label) ->
        MText("$label  $pkg", Modifier.fillMaxWidth().clickable(role = Role.Button) { typed = ""; write(store.excluded + pkg) }.padding(vertical = MeshDensity.dp(MeshDensity.S4)), size = MeshDensity.T_META, mono = true)
    }
}

@Composable
private fun LaunchedLoadApps(store: MeshStore) {
    androidx.compose.runtime.LaunchedEffect(store) {
        if (store.apps.isEmpty()) store.apps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { store.port.installedApps() }.getOrDefault(emptyList()) }
    }
}
