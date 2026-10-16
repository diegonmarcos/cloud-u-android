package com.diegonmarcos.cloudc3.cloud

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudc3.R
import com.diegonmarcos.cloudc3.pages.c3KitPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * C3 · Health — fed from data/services_*.json snapshots (the SuperApp's data/regen.sh
 * derives them from cloud-data's _cloud-data-consolidated.json).
 *
 * MOVED from aa_cloud-superapp (#648). Three things had to change and
 * nothing else did: the package, the R it resolves against, and the two collaborators it
 * reached for as SuperApp singletons — Sections.publicServices()/privateServices() is now
 * [Services], and the row tap went through the launcher's TileGridFragment.TileClickListener,
 * which does not exist in an app with no tile grid, so it is [UrlClickListener] here. The
 * rendering, the colours, the emoji, the scope rules and the row layout are the ones that
 * shipped, because this is a spin-off: the pages were already designed.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The page is a [KitComposeFragment]; the
 * Topology / Observ stacks embed the SAME tables as the composable [Tables], with no child fragment.
 *
 * `scope` controls which tables render:
 *   - "all"     -> both Public and Private (the Observ tab's Health page)
 *   - "public"  -> only Public  (the Topology tab's Public addresses table)
 *   - "private" -> only Private (the Topology tab's Private addresses table)
 */
class C3HealthFragment : KitComposeFragment() {

    /** What a row tap does. The host decides; this fragment only reports the URL. */
    interface UrlClickListener {
        fun onUrlClicked(url: String)
    }

    private val scope: String
        get() = arguments?.getString(ARG_SCOPE) ?: SCOPE_ALL

    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Text(stringResource(R.string.c3_health_title), color = p.textPrimary,
                style = MaterialTheme.typography.headlineSmall)
            Tables(scope) { url -> (activity as? UrlClickListener)?.onUrlClicked(url) }
        }
    }

    companion object {
        const val SCOPE_ALL = "all"
        const val SCOPE_PUBLIC = "public"
        const val SCOPE_PRIVATE = "private"
        private const val ARG_SCOPE = "scope"

        /** green = public, blue = private: the probe table's two dot colours. */
        private val DOT_PUBLIC = Color(0xFF2E7D32)
        private val DOT_PRIVATE = Color(0xFF1565C0)

        fun newInstance(scope: String = SCOPE_ALL): C3HealthFragment =
            C3HealthFragment().apply {
                arguments = Bundle().apply { putString(ARG_SCOPE, scope) }
            }

        /** The status line + the public / private tables for [scope]; [onUrl] reports a row tap. */
        @Composable
        fun Tables(scope: String, onUrl: (String) -> Unit) {
            val p = LocalKitPalette.current
            val showPub = scope == SCOPE_ALL || scope == SCOPE_PUBLIC
            val showPriv = scope == SCOPE_ALL || scope == SCOPE_PRIVATE

            val pub = if (showPub) Services.publicServices() else emptyList()
            val priv = if (showPriv) Services.privateServices() else emptyList()

            // When embedded as a single-scope card the bare status line above a section
            // header is redundant — it only shows for the whole page.
            if (scope == SCOPE_ALL) {
                Text(stringResource(R.string.c3_health_status_split, pub.size, priv.size),
                    color = p.textSecondary, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 2.dp))
            }

            if (pub.isNotEmpty()) {
                if (scope == SCOPE_ALL) SectionHeader(stringResource(R.string.c3_health_section_public, pub.size))
                for (svc in pub) {
                    Row3(
                        dot = DOT_PUBLIC,
                        name = svc.name,
                        sub = "auth: ${svc.auth.ifBlank { "—" }}",
                        top = "🌐 https://${svc.publicUrl}",
                        bottom = if (svc.privateDns.isNotBlank()) "🔒 ${svc.privateDns}" else "—",
                        vm = svc.vm,
                        onTap = { onUrl("https://${svc.publicUrl}") },
                    )
                }
            }

            if (priv.isNotEmpty()) {
                if (scope == SCOPE_ALL) SectionHeader(stringResource(R.string.c3_health_section_private, priv.size))
                for (svc in priv) {
                    Row3(
                        dot = DOT_PRIVATE,
                        name = svc.name,
                        sub = buildString {
                            append(svc.protocol)
                            if (svc.dbEngine.isNotBlank()) { append(" · "); append(svc.dbEngine) }
                        },
                        top = svc.service.ifBlank { "—" },
                        bottom = "🔒 ${svc.privateDns}",
                        vm = svc.vm,
                        onTap = null,
                    )
                }
            }
        }

        @Composable
        private fun SectionHeader(label: String) {
            val p = LocalKitPalette.current
            Text(label, color = p.accent, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 10.dp, top = 20.dp, end = 10.dp, bottom = 5.dp))
        }

        /** One row of the probe table. Status dot · service name (with auth subtitle) · 2-line URL
         *  column (public on top, private DNS below in monospace) · VM. */
        @Composable
        private fun Row3(dot: Color, name: String, sub: String, top: String, bottom: String, vm: String, onTap: (() -> Unit)?) {
            val p = LocalKitPalette.current
            val tap = if (onTap == null) Modifier else Modifier.clickable { onTap() }
            Row(
                Modifier.fillMaxWidth().testTag("c3:health:$name").then(tap).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).background(dot, CircleShape))
                Column(Modifier.weight(3f).padding(start = 10.dp)) {
                    Text(name, color = p.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(sub, color = p.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                Column(Modifier.weight(5f)) {
                    Text(top, color = p.textPrimary, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(bottom, color = p.accent, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp))
                }
                Text(vm, color = p.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2f))
            }
        }
    }
}
