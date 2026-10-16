package com.diegonmarcos.superapp.cloud
import com.diegonmarcos.superapp.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * C3 · WG Mesh — renders the canonical wg-mesh/v1 snapshot
 * (data/mesh.json) grouped by **transport**, matching the user's
 * mental model of "WG0 vs WG-PUBLIC":
 *
 *   wg0       UDP/51820 direct                  → VMs (hub + spokes)
 *   wg0-tcp   wstunnel TCP/443 over WSS         → roaming clients
 *
 * Each transport card shows:
 *   • Header  protocol/port + endpoint + primary/fallback + use case
 *   • Nodes   that use THIS transport (status dot · name · region
 *              · WG-IP · public IP · public ports · OS)
 *   • Peers   declared from→to relationships involving any node above
 *
 * Per the canonical spec, "wg-public" is NOT a separate WG network —
 * it's the wstunnel TCP/443 transport of the same wg0 network. The
 * UI groups by transport so it reads as two networks (which is how
 * the user perceives them).
 *
 * Compose since #773 (was fragment_c3_mesh.xml); all rendering lives in [MeshView].
 */
class C3MeshFragment : KitComposeFragment() {

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val p = LocalKitPalette.current
        Column(pageModifier().padding(12.dp)) {
            Text(stringResource(R.string.mesh_title), color = p.textPrimary,
                style = MaterialTheme.typography.headlineSmall)
            Text(MeshView.statusText(LocalContext.current), color = p.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp).alpha(0.9f).testTag(MeshView.TAG_STATUS))
            MeshView.Content()
        }
    }

    /**
     * Standalone the page scrolls itself; embedded in the Infos stack (a child fragment) it sits inside the
     * stack's scrolling body, and a Compose scroll under an unbounded height throws at measure time.
     */
    @Composable
    private fun pageModifier(): Modifier =
        if (parentFragment != null) Modifier.fillMaxWidth()
        else Modifier.fillMaxSize().verticalScroll(rememberScrollState())

    companion object { fun newInstance() = C3MeshFragment() }
}
