package com.diegonmarcos.superapp.network.mesh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.superapp.R

/**
 * #877 Status: the tunnel at a glance. The rows and their ORDER are `ui.mesh_page.pages[status].rows`;
 * [StatusRow] draws one by id, and an id it has no drawing for says so on the page rather than vanishing.
 */
@Composable
fun MeshStatusPage(store: MeshStore, modifier: Modifier = Modifier) {
    val s = store.snapshot
    val rows = store.decl.page("status")?.rows.orEmpty()
    Column(
        modifier.fillMaxSize().testTag(MeshTags.page("status")).verticalScroll(rememberScrollState())
            .padding(MeshDensity.dp(MeshDensity.S8)),
    ) {
        if (s == null) MText(stringResource(R.string.mesh_reading), muted = true)
        else for (id in rows) StatusRow(id, store, s)
    }
}

@Composable
private fun StatusRow(id: String, store: MeshStore, s: MeshSnapshot) {
    val host = store.host
    val cfg = s.cfg
    val tag = MeshTags.row(id)
    when (id) {
        "state" -> {
            val word = when {
                !s.engineInstalled -> stringResource(R.string.mesh_state_unknown)
                !s.up -> stringResource(R.string.mesh_state_down)
                s.light == Light.UP -> stringResource(R.string.mesh_state_up)
                else -> stringResource(R.string.mesh_state_up_nohs)
            }
            Row(
                Modifier.testTag(tag).padding(vertical = MeshDensity.dp(MeshDensity.S4)),
                horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S8)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Light(lightOf(s.light), word)
                MText(word, size = MeshDensity.T_TITLE, bold = true)
                MText(cfg.tunnelName, size = MeshDensity.T_META, mono = true, muted = true)
            }
            if (!s.engineInstalled) Reason(stringResource(R.string.mesh_status_limits))
            if (store.pending) {
                Row(
                    Modifier.padding(vertical = MeshDensity.dp(MeshDensity.S4)),
                    horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S8)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MText(stringResource(R.string.mesh_pending), Modifier.weight(1f), size = MeshDensity.T_META)
                    MButton(stringResource(R.string.mesh_reconnect), { store.run("state.reconnect") }, Modifier.testTag("mesh:pending:reconnect"))
                }
            }
        }
        "interface" -> {
            MHeader(stringResource(R.string.mesh_row_interface))
            KvRow(stringResource(R.string.mesh_k_address), cfg.address.ifBlank { "-" }, tag = tag,
                onCopy = { host.copy("address", cfg.address) })
            KvRow(stringResource(R.string.mesh_k_mtu), cfg.mtu.ifBlank { stringResource(R.string.mesh_default) })
            if (cfg.listenPort.isNotBlank()) KvRow(stringResource(R.string.mesh_k_listen), cfg.listenPort)
        }
        "pubkey" -> KvRow(
            stringResource(R.string.mesh_k_pubkey),
            cfg.publicKey.ifBlank { stringResource(R.string.mesh_no_key) },
            tag = tag,
            onCopy = if (cfg.publicKey.isBlank()) null else { { host.copy("public key", cfg.publicKey) } },
        )
        "endpoint" -> {
            MHeader(stringResource(R.string.mesh_row_endpoint))
            if (cfg.peers.isEmpty()) MText(stringResource(R.string.mesh_no_peers), Modifier.testTag(tag), size = MeshDensity.T_META, muted = true)
            cfg.peers.forEachIndexed { i, p ->
                KvRow(p.name.ifBlank { stringResource(R.string.mesh_peer_n, i + 1) }, p.endpoint.ifBlank { "-" },
                    tag = if (i == 0) tag else "")
            }
        }
        "transport" -> {
            // Which rung of the fallback ladder carries the tunnel, and the last Test fallbacks, rung by rung.
            MHeader(stringResource(R.string.mesh_row_transport))
            val tv = store.transport
            val path = tv?.path.orEmpty()
            KvRow(stringResource(R.string.mesh_k_path), if (path.isBlank()) stringResource(R.string.mesh_path_unknown) else declLabel("path", path, path), tag = tag)
            KvRow(stringResource(R.string.mesh_k_path_mode), declLabel("opt", tv?.mode.orEmpty(), tv?.mode.orEmpty()))
            if (!tv?.detail.isNullOrBlank()) Reason(tv?.detail.orEmpty())
            if (!tv?.relay.isNullOrBlank()) KvRow(stringResource(R.string.mesh_k_relay), tv?.relay.orEmpty())
            for (pr in store.probes) KvRow(declLabel("probe", pr.id, pr.id),
                stringResource(probeVerdict(pr.ok)) + " · " + pr.detail, tag = "mesh:probe:${pr.id}")
            MButton(stringResource(R.string.mesh_ctl_test_fallbacks), { store.run("transport.test") },
                Modifier.testTag("mesh:transport:test").padding(vertical = MeshDensity.dp(MeshDensity.S4)), enabled = !store.busy)
        }
        "handshake" -> {
            MHeader(stringResource(R.string.mesh_row_handshake))
            KvRow(stringResource(R.string.mesh_k_newest), handshakeText(s.newestHandshakeAgeS, s.up, s.engineInstalled), tag = tag)
            if (s.peers.size > 1) s.peers.forEach { p ->
                KvRow(p.cfg.name.ifBlank { MeshFormat.shortKey(p.cfg.publicKey) }, handshakeText(p.handshakeAgeS, s.up, s.engineInstalled) + " · " + healthWord(p.health))
            }
        }
        "traffic" -> {
            MHeader(stringResource(R.string.mesh_row_traffic))
            KvRow(stringResource(R.string.mesh_k_down), MeshFormat.bytes(s.rx) + "  " + MeshFormat.rate(s.rxRate), tag = tag)
            KvRow(stringResource(R.string.mesh_k_up), MeshFormat.bytes(s.tx) + "  " + MeshFormat.rate(s.txRate))
            if (s.peers.size > 1) s.peers.forEach { p ->
                KvRow(p.cfg.name.ifBlank { MeshFormat.shortKey(p.cfg.publicKey) },
                    "↓ " + MeshFormat.rate(p.rxRate) + "  ↑ " + MeshFormat.rate(p.txRate))
            }
        }
        "latency" -> {
            MHeader(stringResource(R.string.mesh_row_latency))
            KvRow(stringResource(R.string.mesh_k_hub), MeshFormat.latency(s.hubLatencyMs), tag = tag)
            s.peers.forEach { p ->
                KvRow(p.cfg.name.ifBlank { MeshFormat.shortKey(p.cfg.publicKey) }, MeshFormat.latency(p.latencyMs))
            }
            Reason(stringResource(R.string.mesh_latency_how))
        }
        "dns" -> {
            MHeader(stringResource(R.string.mesh_row_dns))
            KvRow(stringResource(R.string.mesh_k_dns), cfg.dnsInEffect.ifBlank { "-" }, tag = tag)
            KvRow(stringResource(R.string.mesh_k_fleet_dns), cfg.dns.ifBlank { "-" })
        }
        "profile" -> {
            MHeader(stringResource(R.string.mesh_row_profile))
            KvRow(stringResource(R.string.mesh_k_profile), cfg.activeProfile.substringAfterLast('/').ifBlank { stringResource(R.string.mesh_profile_none) }, tag = tag)
            KvRow(stringResource(R.string.mesh_k_provider), store.provider.ifBlank { "-" })
        }
        else -> MText(stringResource(R.string.mesh_row_unknown, id), Modifier.testTag(tag), size = MeshDensity.T_META, muted = true)
    }
}

/** pass / fail / not testable now, for one Test fallbacks rung. */
internal fun probeVerdict(ok: Boolean?): Int = when (ok) {
    true -> R.string.mesh_probe_pass
    false -> R.string.mesh_probe_fail
    null -> R.string.mesh_probe_skip
}

@Composable
internal fun handshakeText(ageS: Long?, up: Boolean, engine: Boolean): String = when {
    !engine -> stringResource(R.string.mesh_unknown)
    !up -> "-"
    ageS == null -> stringResource(R.string.mesh_hs_none)
    else -> stringResource(R.string.mesh_hs_ago, MeshFormat.age(ageS))
}

@Composable
internal fun healthWord(h: Health): String = stringResource(
    when (h) {
        Health.FRESH -> R.string.mesh_h_fresh
        Health.AGING -> R.string.mesh_h_aging
        Health.STALE -> R.string.mesh_h_stale
        Health.NONE -> R.string.mesh_h_none
        Health.DOWN -> R.string.mesh_h_down
        Health.UNKNOWN -> R.string.mesh_unknown
    },
)
