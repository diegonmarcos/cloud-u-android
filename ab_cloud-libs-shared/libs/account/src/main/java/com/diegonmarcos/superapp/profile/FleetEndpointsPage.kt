package com.diegonmarcos.superapp.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.fleetconfig.FleetEndpointsContract
import com.diegonmarcos.superapp.fleetendpoints.EndpointChecker
import com.diegonmarcos.superapp.fleetendpoints.EndpointState
import com.diegonmarcos.superapp.fleetendpoints.FleetTransport
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.uikit.GroupTitle
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FleetEndpointsTags {
    const val PAGE = "endpoints:page"
    const val CHECK = "endpoints:check"
    const val BEARER = "endpoints:bearer"
    const val BANNER = "endpoints:banner"
    fun row(id: String) = "endpoints:row:$id"
}

/** The word and colour of a route, shared by the row pill and the banner count. */
fun endpointStateWord(s: EndpointState): Pair<String, KitState> = when (s) {
    EndpointState.MESH -> "mesh" to KitState.OK
    EndpointState.PUBLIC -> "public" to KitState.WARN
    EndpointState.OFFLINE -> "offline" to KitState.BAD
    EndpointState.UNKNOWN -> "not checked" to KitState.IDLE
}

/** The two endpoint lines of a row. URLs and status words only: the bearer never appears here. */
fun endpointDetail(r: EndpointRow): String {
    val s = r.service
    val mesh = FleetEndpointRows.hostPort(s.meshUrl()) + when (r.meshUp) { true -> " up"; false -> " down"; null -> "" }
    val pub = if (!s.hasPublic) "no public endpoint" else s.publicDomain + when {
        !r.checked -> ""
        r.publicStatus != null -> " HTTP ${r.publicStatus}"
        else -> " unreachable"
    }
    return "mesh $mesh · public $pub · ${s.policy.replace('_', ' ')}"
}

/**
 * #1093 Connect ▸ Status ▸ Endpoints: every fleet service Cloud Account talks to, with the state of BOTH of its
 * endpoints, the route a request takes now, and whether a fleet bearer is stored (masked: it is sent on the public path
 * only). The services come from the one declaration ([AccountHost.fleetEndpointsDecl]); Check asks each endpoint
 * (the mesh by a connection, the public one by its health path) the way real traffic would reach it.
 */
@Composable
fun FleetEndpointsPage(
    /** Blocking; the real checks by default, a fake in a test. */
    check: (List<FleetEndpointsContract.Service>) -> Map<String, EndpointCheck> = { FleetEndpointRows.check(it, EndpointChecker()) },
) {
    val ctx = LocalContext.current
    val services = remember { FleetEndpointsRuntime.services() }
    val routes by FleetTransport.router.status.collectAsState()
    var checks by remember { mutableStateOf(emptyMap<String, EndpointCheck>()) }
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val bearer = remember(tick) { runCatching { FleetEndpointsRuntime.hasBearer(AccountVault(ctx)) }.getOrDefault(false) }
    LaunchedEffect(tick) {
        busy = true
        checks = withContext(Dispatchers.IO) { runCatching { check(services) }.getOrDefault(emptyMap()) }
        busy = false
    }
    val rows = FleetEndpointRows.build(services, routes, checks)
    val counts = rows.groupingBy { it.state }.eachCount()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp).testTag(FleetEndpointsTags.PAGE),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            services.isEmpty() -> KitStatusBanner("No fleet endpoint is declared.", KitState.WARN, tag = FleetEndpointsTags.BANNER)
            busy -> KitStatusBanner("Checking ${services.size} services…", KitState.BUSY, tag = FleetEndpointsTags.BANNER)
            else -> KitStatusBanner(
                "${counts[EndpointState.MESH] ?: 0} on the mesh · ${counts[EndpointState.PUBLIC] ?: 0} on public · ${counts[EndpointState.OFFLINE] ?: 0} offline",
                if ((counts[EndpointState.OFFLINE] ?: 0) > 0) KitState.BAD else KitState.OK, tag = FleetEndpointsTags.BANNER)
        }
        KitActionBar(listOf(KitAction("Check", FleetEndpointsTags.CHECK, !busy) { tick++ }), filledFirst = false)
        GroupTitle("Services · ${rows.size}")
        for (r in rows) Tile("${r.service.id} · ${r.service.kind}", tag = FleetEndpointsTags.row(r.service.id), pill = endpointStateWord(r.state), secondary = endpointDetail(r))
        GroupTitle("Bearer")
        Tile("Fleet bearer", tag = FleetEndpointsTags.BEARER,
            pill = (if (bearer) "set" else "not set") to (if (bearer) KitState.OK else KitState.WARN),
            secondary = (if (bearer) FleetEndpointsContract.MASK + " · " else "") + "sent on the public path only, never to the mesh or any other host")
    }
}
