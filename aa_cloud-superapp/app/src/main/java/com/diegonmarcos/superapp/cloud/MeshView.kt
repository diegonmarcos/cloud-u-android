package com.diegonmarcos.superapp.cloud
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.R

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * Shared WG-mesh renderer — the canonical wg-mesh/v1 snapshot
 * (data/mesh.json via [Sections.mesh]) grouped by **transport**
 * (wg0 UDP direct vs wg0-tcp wstunnel over WSS).
 *
 * Extracted out of [C3MeshFragment] so the SAME rendering can be folded
 * into any page. Compose since #773 (was item_c3_mesh_block.xml + item_c3_mesh_row.xml):
 * [Content] is a Column the caller nests in its own scroll container, painted by the
 * kit palette roles (hub = ok, client = warn, spoke = accent).
 */
object MeshView {

    const val TAG_STATUS = "mesh:status"
    fun blockTag(transport: String) = "mesh:block:$transport"
    fun nodeTag(name: String) = "mesh:node:$name"
    fun peerTag(from: String, to: String) = "mesh:peer:$from>$to"

    /** One-line summary for the page header (nodes · peers · transports). */
    fun statusText(ctx: Context): String {
        val mesh = Sections.mesh()
        return ctx.getString(
            R.string.mesh_status_v1,
            mesh.nodes.size, mesh.peers.size, mesh.transports.size,
        )
    }

    /** The per-transport cards (nodes + peerings). */
    @Composable
    fun Content() {
        val mesh = Sections.mesh()

        // wstunnel users = clients that go via wg0-tcp (laptop/phone).
        // Everyone else uses wg0 direct UDP.
        val tcpNodeNames = mesh.nodes.filter { it.wstunnelClient }.map { it.name }.toSet()
        val tcpNodes     = mesh.nodes.filter { it.wstunnelClient }
        val udpNodes     = mesh.nodes.filter { !it.wstunnelClient }

        val tcpPeers = mesh.peers.filter { it.from in tcpNodeNames || it.to in tcpNodeNames }
        val udpPeers = mesh.peers.filterNot { it.from in tcpNodeNames || it.to in tcpNodeNames }

        Column {
            for (transport in mesh.transports) {
                val (nodesForT, peersForT) = when (transport.name) {
                    "wg0"     -> udpNodes to udpPeers
                    "wg0-tcp" -> tcpNodes to tcpPeers
                    else      -> mesh.nodes to mesh.peers
                }
                TransportCard(transport, nodesForT, peersForT)
            }
        }
    }

    @Composable
    private fun TransportCard(t: Sections.MeshTransport, nodes: List<Sections.MeshNode>, peers: List<Sections.MeshPeer>) {
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxWidth().padding(bottom = 14.dp).testTag(blockTag(t.name))) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surfaceSelected).padding(14.dp)) {
                Text("${t.name} · ${t.label}", color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
                val role = when {
                    t.primary  -> "primary"
                    t.fallback -> "fallback"
                    else       -> "—"
                }
                Text(buildString {
                    append(t.protocol.uppercase()).append('/').append(t.port)
                    append("  →  ").append(t.endpoint).append('\n')
                    append(role).append(" · ").append(t.activePeers).append(" active peers · ")
                    append(nodes.size).append(" nodes · ").append(peers.size).append(" peerings\n")
                    if (t.useCase.isNotBlank()) append(t.useCase)
                }, color = p.textPrimary, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp).alpha(0.85f))
            }
            Column(Modifier.padding(top = 4.dp)) {
                // ── nodes ────────────────────────────────────────────
                if (nodes.isNotEmpty()) {
                    Subheader(stringResource(R.string.mesh_section_nodes) + " · ${nodes.size}")
                    for (n in nodes) NodeRow(n)
                }
                // ── peers ────────────────────────────────────────────
                if (peers.isNotEmpty()) {
                    Subheader(stringResource(R.string.mesh_section_peers) + " · ${peers.size}")
                    for (pe in peers) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag(peerTag(pe.from, pe.to))) {
                            Line("${pe.from}  →  ${pe.to}", titleSize = true)
                            Line("AllowedIPs: ${pe.allowedIps.joinToString(", ")}")
                            Line("keepalive ${pe.keepalive}s", dim = true)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun NodeRow(n: Sections.MeshNode) {
        val p = LocalKitPalette.current
        val dot = when (n.role) {
            "hub"    -> p.ok
            "client" -> p.warn
            else     -> p.accent
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).testTag(nodeTag(n.name)),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Column(Modifier.weight(3f).padding(start = 10.dp)) {
                Text("${n.name} · ${n.role}", color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                Text("${n.alias} · ${n.provider}/${n.region}", color = p.textSecondary, maxLines = 1,
                    style = MaterialTheme.typography.labelSmall)
            }
            Text(n.wgIp, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(2f))
            Column(Modifier.weight(3f)) {
                Text(n.publicIp, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                Text(buildString {
                    if (n.portsPublic.isNotEmpty()) append(n.portsPublic.joinToString(" "))
                    if (n.wstunnelServer) append(" · wstunnel↑")
                    if (n.wstunnelClient) append(" · wstunnel↓")
                    if (n.os.isNotBlank()) append(" · ").append(n.os)
                }, color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    @Composable
    private fun Subheader(text: String) {
        Text(text, color = LocalKitPalette.current.accent, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 5.dp))
    }

    @Composable
    private fun Line(text: String, titleSize: Boolean = false, dim: Boolean = false) {
        Text(text, color = LocalKitPalette.current.textPrimary, fontFamily = FontFamily.Monospace,
            style = if (titleSize) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            modifier = if (dim) Modifier.alpha(0.6f) else Modifier)
    }
}
