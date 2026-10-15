package com.diegonmarcos.cloudc3.pages

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.R
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #648 THE AGENTS PAGE (Apps tab, first page) - the C3 sibling APKs, and the local webserver.
 *
 * All three are already standalone fleet members with their own directory, build.json, ship
 * workflow and applicationId, so this tab LAUNCHES them and declares no page of its own.
 * Nothing here knows what any of the three DOES.
 *
 * Each row RESOLVES its package against the device rather than assuming it is installed —
 * AndroidManifest declares all three under <queries>, without which API 30+ reports an
 * uninstalled-looking null for an app that is present, and the row's "Not installed" would
 * be a lie. Resolution happens on every resume (the [resumes] key), because the usual reason
 * to come back to this tab is having just installed one.
 *
 * The printed name is DERIVED from the fleet name ([Declarations.ExternalAppDecl.display]):
 * c3-watchdog reads "Watchdog". No display label is stored anywhere (#351/#224).
 *
 * 2026-10-15 owner decision: all apps must be Compose — this page is a ComposeView host.
 */
class AgentsFragment : Fragment() {

    private var resumes by mutableIntStateOf(0)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { CloudKitTheme(c3KitPalette(context)) { Agents(resumes) } }
    }

    override fun onResume() {
        super.onResume()
        resumes++
    }

    @Composable
    private fun Agents(resumeKey: Int) {
        val ctx = LocalContext.current
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(KitDensity.medium),
        ) {
            for (tile in Declarations.externalApps) {
                val installed = remember(resumeKey, tile.packageName) { isInstalled(ctx, tile.packageName) }
                AgentRow(tile, installed, ctx)
            }
        }
    }

    @Composable
    private fun AgentRow(tile: Declarations.ExternalAppDecl, installed: Boolean, ctx: Context) {
        val p = LocalKitPalette.current
        @Suppress("DiscouragedApi")
        val icon = remember(tile.icon) { ctx.resources.getIdentifier(tile.icon, "drawable", ctx.packageName) }
        C3Card(onClick = if (installed) ({ launch(ctx, tile.packageName, tile.display) }) else null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KitDensity.small),
            ) {
                if (icon != 0) Image(painterResource(icon), null, Modifier.size(KitDensity.glyph))
                Column {
                    Text(tile.display, color = p.textPrimary, fontSize = KitDensity.title)
                    Text(
                        stringOf(ctx, if (installed) R.string.apps_open else R.string.apps_not_installed),
                        color = p.textSecondary,
                        fontSize = KitDensity.caption,
                    )
                }
            }
        }
    }

    private fun stringOf(ctx: Context, id: Int): String = ctx.getString(id)

    /**
     * "Installed" here means precisely "openable": resolved through the launch intent rather
     * than getPackageInfo, because an app with no launcher activity could not be opened by
     * this row even if it were present. Never throws — an invisible or absent package is
     * false, which is what the row then says.
     */
    private fun isInstalled(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg) != null }
            .getOrDefault(false)

    /** Opens the sibling, and says so when it cannot rather than failing silently. */
    private fun launch(ctx: Context, pkg: String, label: String) {
        val intent = runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
        if (intent == null) {
            Toast.makeText(ctx, ctx.getString(R.string.apps_launch_failed, label), Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(ctx, ctx.getString(R.string.apps_launch_failed, label), Toast.LENGTH_SHORT).show()
        }
    }
}
