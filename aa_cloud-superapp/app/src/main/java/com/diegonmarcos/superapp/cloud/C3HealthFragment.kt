package com.diegonmarcos.superapp.cloud
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.launcher.TileGridFragment
import com.diegonmarcos.superapp.launcher.AggregatorStackFragment
import com.diegonmarcos.superapp.R

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * C3 · Health — fed from data/services_*.json snapshots
 * (data/regen.sh derives them from cloud-data's
 * _cloud-data-consolidated.json).
 *
 * `scope` controls which tables render:
 *   - "all"     → both Public and Private (default — standalone page)
 *   - "public"  → only Public
 *   - "private" → only Private
 *
 * Used standalone (drawer / page:c3/health) AND embedded inside
 * [AggregatorStackFragment] cards where each card is a single-scope view.
 *
 * Compose since #773 (was fragment_c3_health.xml + item_c3_health_row.xml): a KitComposeFragment
 * under CloudKitTheme; the dots are the palette's ok (public) and accent (private) roles.
 */
class C3HealthFragment : KitComposeFragment() {

    private val scope: String
        get() = arguments?.getString(ARG_SCOPE) ?: SCOPE_ALL

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val showPub  = scope == SCOPE_ALL || scope == SCOPE_PUBLIC
        val showPriv = scope == SCOPE_ALL || scope == SCOPE_PRIVATE
        val pub  = if (showPub)  Sections.publicServices()  else emptyList()
        val priv = if (showPriv) Sections.privateServices() else emptyList()
        val p = LocalKitPalette.current

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Text(stringResource(R.string.c3_health_title), color = p.textPrimary,
                style = MaterialTheme.typography.headlineSmall)
            // When embedded as a single-scope card the bare status line above
            // a section header is redundant — only the standalone page draws it.
            if (scope == SCOPE_ALL) {
                Text(stringResource(R.string.c3_health_status_split, pub.size, priv.size),
                    color = p.textSecondary, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 2.dp).testTag(TAG_STATUS))
            }
            if (pub.isNotEmpty()) {
                if (scope == SCOPE_ALL) Header(stringResource(R.string.c3_health_section_public, pub.size), TAG_PUBLIC)
                for (svc in pub) {
                    // A public service's own web UI is exactly the content this
                    // app can render, and the estate dashboard is a place you
                    // tap through repeatedly — bouncing to an external browser
                    // per row meant a task switch and no way back to the list.
                    val act = LocalContext.current as? FragmentActivity
                    ProbeRow(svc.name, "auth: ${svc.auth.ifBlank { "—" }}", "🌐 https://${svc.publicUrl}",
                        if (svc.privateDns.isNotBlank()) "🔒 ${svc.privateDns}" else "—", svc.vm,
                        p.ok, Modifier.testTag(rowTag(svc.name)).clickable {
                            (act as? TileGridFragment.TileClickListener)?.onTileClicked("https://${svc.publicUrl}")
                        })
                }
            }
            if (priv.isNotEmpty()) {
                if (scope == SCOPE_ALL) Header(stringResource(R.string.c3_health_section_private, priv.size), TAG_PRIVATE)
                for (svc in priv) {
                    ProbeRow(svc.name,
                        buildString {
                            append(svc.protocol)
                            if (svc.dbEngine.isNotBlank()) { append(" · "); append(svc.dbEngine) }
                        },
                        svc.service.ifBlank { "—" }, "🔒 ${svc.privateDns}", svc.vm,
                        p.accent, Modifier.testTag(rowTag(svc.name)))
                }
            }
        }
    }

    @Composable
    private fun Header(label: String, tag: String) {
        Text(label, color = LocalKitPalette.current.accent, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 20.dp, bottom = 5.dp).testTag(tag))
    }

    /** Status dot · name + auth · URL + private DNS (monospace) · VM — one probe-table row. */
    @Composable
    private fun ProbeRow(name: String, auth: String, domain: String, priv: String, vm: String,
                    dot: androidx.compose.ui.graphics.Color, modifier: Modifier) {
        val p = LocalKitPalette.current
        Row(
            modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Column(Modifier.weight(3f).padding(start = 10.dp)) {
                Text(name, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                Text(auth, color = p.textSecondary, maxLines = 1, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.alpha(0.8f))
            }
            Column(Modifier.weight(5f)) {
                Text(domain, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
                Text(priv, color = p.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp))
            }
            Text(vm, color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(2f))
        }
    }

    companion object {
        const val SCOPE_ALL     = "all"
        const val SCOPE_PUBLIC  = "public"
        const val SCOPE_PRIVATE = "private"
        private const val ARG_SCOPE = "scope"

        const val TAG_STATUS  = "c3health:status"
        const val TAG_PUBLIC  = "c3health:header:public"
        const val TAG_PRIVATE = "c3health:header:private"
        fun rowTag(name: String) = "c3health:row:$name"

        fun newInstance(scope: String = SCOPE_ALL): C3HealthFragment =
            C3HealthFragment().apply {
                arguments = Bundle().apply { putString(ARG_SCOPE, scope) }
            }
    }
}
