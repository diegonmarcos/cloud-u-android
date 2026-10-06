package com.diegonmarcos.superapp.network.mesh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
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
import com.diegonmarcos.superapp.launcher.Sections

/**
 * #877 Peers: one dense row per peer (light, name, endpoint, handshake age, rx/tx); a tap opens the full
 * peer config as editable fields with Copy and Remove. The fleet topology (data/mesh.json) is the optional
 * block under the list - the apps-mesh page's visuals, drawn here without merging the two pages.
 */
@Composable
fun MeshPeersPage(store: MeshStore, modifier: Modifier = Modifier) {
    val s = store.snapshot
    val page = store.decl.page("peers")
    var open by remember { mutableStateOf(-1) }
    Column(
        modifier.fillMaxSize().testTag(MeshTags.page("peers")).verticalScroll(rememberScrollState())
            .padding(MeshDensity.dp(MeshDensity.S8)),
    ) {
        page?.control("peer_sort")?.let { c ->
            ChoiceLine(
                declLabel("ctl", c.id, c.label),
                c.choices.map { it to declLabel("opt", it, it) },
                store.view[c.id] ?: c.default,
                { store.run(c.engine, it) },
                Modifier.testTag(MeshTags.control(c.id)),
            )
        }
        if (s == null) { MText(stringResource(R.string.mesh_reading), muted = true); return@Column }
        val order = PeerOrder.sort(s.peers, store.view["peer_sort"] ?: "config")
        if (order.isEmpty()) MText(stringResource(R.string.mesh_no_peers), muted = true)
        order.forEach { row ->
            val index = s.peers.indexOfFirst { it === row }
            PeerRow(store, s, row, index, open == index) { open = if (open == index) -1 else index }
        }
        page?.control("peer_add")?.let { c ->
            MButton(declLabel("ctl", c.id, c.label), { store.run(c.engine) },
                Modifier.padding(top = MeshDensity.dp(MeshDensity.S8)).testTag(MeshTags.control(c.id)))
        }
        page?.control("topology")?.let { c ->
            SwitchLine(
                declLabel("ctl", c.id, c.label), store.view[c.id] == "true",
                { store.run(c.engine, it.toString()) },
                Modifier.padding(top = MeshDensity.dp(MeshDensity.S8)).testTag(MeshTags.control(c.id)),
            )
            if (store.view[c.id] == "true") MeshTopology()
        }
    }
}

@Composable
private fun PeerRow(store: MeshStore, s: MeshSnapshot, p: PeerLive, index: Int, expanded: Boolean, toggle: () -> Unit) {
    val label = p.cfg.name.ifBlank { MeshFormat.shortKey(p.cfg.publicKey) }
    val word = healthWord(p.health)
    Column(Modifier.fillMaxWidth().testTag(MeshTags.peer(index))) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = toggle)
                .padding(vertical = MeshDensity.dp(MeshDensity.S4)),
            horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S8)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Light(lightOf(p.health), word)
            Column(Modifier.weight(1f)) {
                MText(label, size = MeshDensity.T_BODY, bold = true, maxLines = 1)
                MText(p.cfg.endpoint.ifBlank { "-" }, size = MeshDensity.T_CAPTION, mono = true, muted = true, maxLines = 1)
                MText(p.cfg.allowedIps.ifBlank { "-" }, size = MeshDensity.T_CAPTION, mono = true, muted = true, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                MText(handshakeText(p.handshakeAgeS, s.up, s.engineInstalled), size = MeshDensity.T_META, mono = true)
                MText("↓" + MeshFormat.bytes(p.rx), size = MeshDensity.T_CAPTION, mono = true, muted = true)
                MText("↑" + MeshFormat.bytes(p.tx), size = MeshDensity.T_CAPTION, mono = true, muted = true)
            }
        }
        if (expanded) PeerDetail(store, p, index)
    }
}

@Composable
private fun PeerDetail(store: MeshStore, p: PeerLive, index: Int) {
    val c = p.cfg
    fun save(n: PeerCfg) = store.perform("peer") { store.port.updatePeer(index, n) }
    var armed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = MeshDensity.dp(MeshDensity.S8))) {
        FieldLine(stringResource(R.string.mesh_f_name)) { MField(c.name, { save(c.copy(name = it.trim())) }, mono = false) }
        FieldLine(stringResource(R.string.mesh_f_pubkey)) { MField(c.publicKey, { save(c.copy(publicKey = it.trim())) }) }
        FieldLine(stringResource(R.string.mesh_f_psk)) { MField(c.presharedKey, { save(c.copy(presharedKey = it.trim())) }, secret = true, hint = if (c.presharedKey.isBlank()) "" else stringResource(R.string.mesh_stored)) }
        FieldLine(stringResource(R.string.mesh_f_endpoint)) { MField(c.endpoint, { save(c.copy(endpoint = it.trim())) }) }
        FieldLine(stringResource(R.string.mesh_f_allowed)) { MField(c.allowedIps, { save(c.copy(allowedIps = it.trim())) }) }
        FieldLine(stringResource(R.string.mesh_f_keepalive)) { MField(c.keepalive, { save(c.copy(keepalive = it.trim())) }, numeric = true) }
        Row(Modifier.padding(top = MeshDensity.dp(MeshDensity.S4)), horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S8))) {
            store.decl.page("peers")?.control("peer_copy")?.let { cc ->
                MButton(declLabel("ctl", cc.id, cc.label), { store.run(cc.engine, index = index) }, Modifier.testTag(MeshTags.control(cc.id)))
            }
            store.decl.page("peers")?.control("peer_remove")?.let { rc ->
                MButton(
                    if (armed) stringResource(R.string.mesh_confirm_remove) else declLabel("ctl", rc.id, rc.label),
                    { if (armed) { armed = false; store.run(rc.engine, index = index) } else armed = true },
                    Modifier.testTag(MeshTags.control(rc.id)), emphasised = armed,
                )
            }
        }
    }
}

/** The fleet's declared topology (data/mesh.json via Sections.mesh): transports, their nodes and peerings. */
@Composable
fun MeshTopology() {
    val mesh = remember { runCatching { Sections.mesh() }.getOrNull() }
    Column(Modifier.fillMaxWidth().testTag("mesh:topology")) {
        if (mesh == null) { MText(stringResource(R.string.mesh_topology_missing), size = MeshDensity.T_META, muted = true); return@Column }
        MHeader(stringResource(R.string.mesh_topology) + " · " + stringResource(R.string.mesh_topology_counts, mesh.nodes.size, mesh.peers.size, mesh.transports.size))
        val tcp = mesh.nodes.filter { it.wstunnelClient }.map { it.name }.toSet()
        for (t in mesh.transports) {
            val nodes = when (t.name) { "wg0" -> mesh.nodes.filter { !it.wstunnelClient }; "wg0-tcp" -> mesh.nodes.filter { it.wstunnelClient }; else -> mesh.nodes }
            val peers = when (t.name) {
                "wg0" -> mesh.peers.filterNot { it.from in tcp || it.to in tcp }
                "wg0-tcp" -> mesh.peers.filter { it.from in tcp || it.to in tcp }
                else -> mesh.peers
            }
            MText("${t.name} · ${t.label}", size = MeshDensity.T_BODY, bold = true, modifier = Modifier.padding(top = MeshDensity.dp(MeshDensity.S6)))
            MText("${t.protocol.uppercase()}/${t.port} → ${t.endpoint}", size = MeshDensity.T_CAPTION, mono = true, muted = true)
            for (n in nodes) {
                Row(Modifier.padding(vertical = MeshDensity.dp(MeshDensity.S1)), horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S6)), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(MeshDensity.dp(MeshDensity.GLYPH)).clip(CircleShape).background(MeshRoles.of(n.role)),
                    )
                    MText("${n.name} · ${n.role}", Modifier.weight(1f), size = MeshDensity.T_META, maxLines = 1)
                    MText(n.wgIp, size = MeshDensity.T_CAPTION, mono = true, muted = true, maxLines = 1)
                }
            }
            for (p in peers) MText("${p.from} → ${p.to}  ${p.allowedIps.joinToString(", ")}  ${p.keepalive}s", size = MeshDensity.T_CAPTION, mono = true, muted = true)
        }
    }
}
