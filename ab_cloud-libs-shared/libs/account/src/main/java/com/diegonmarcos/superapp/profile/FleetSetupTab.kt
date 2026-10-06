package com.diegonmarcos.superapp.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.account.R
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object FleetSetupTags {
    const val TAB = "account:fleetsetup"
    const val RUN = "fleetsetup:run"
    const val SUMMARY = "fleetsetup:summary"
    fun app(id: String) = "fleetsetup:app:$id"
    fun result(id: String) = "fleetsetup:result:$id"
    fun retry(id: String) = "fleetsetup:retry:$id"
    fun install(id: String) = "fleetsetup:install:$id"
}

/**
 * #873 Fleet Setup, dense and data first: one row per fleet app the manifest declares (installed or not, how many
 * keys Cloud Account holds for it, the last ✓/✗ with the failing key), a Retry per app, an Install through the Store
 * for an app that is missing, and "Set up fleet" for all of them in order. The plan and the run are
 * [SetupPlan] / [FleetSetup]; this only draws them.
 */
@Composable
fun FleetSetupTab(model: AccountModel) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val outcomes = remember { mutableStateMapOf<String, FleetSetup.AppOutcome>() }
    val plan = remember(tick, model.version.intValue) {
        runCatching { FleetSetup.plan(ctx, ConfigsPrefs(ctx).json, model.shown(), AccountVault(ctx).appConfigs()) }.getOrNull()
    }
    val apps = remember { runCatching { AccountFleet.manifest(ctx).apps.values.toList() }.getOrDefault(emptyList()) }
    val labels = remember { AccountFleet.fleetApps().associate { it.id to it.label } }
    fun go(only: Set<String>?) {
        val pl = plan ?: return
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) {
                FleetSetup.run(pl, { FleetSetup.installed(ctx, it) }, FleetSetup.transport(ctx), only) { o -> outcomes[o.id] = o }
            }
            busy = false
        }
    }
    Column(Modifier.fillMaxWidth().testTag(FleetSetupTags.TAB), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KitSectionHeader(stringResource(R.string.fleetsetup_title), stringResource(R.string.fleetsetup_caption))
        val done = outcomes.values.count { it.state == FleetSetup.State.DONE }
        val bad = outcomes.values.count { it.state == FleetSetup.State.FAILED || it.state == FleetSetup.State.NO_CONTRACT }
        Text(stringResource(R.string.fleetsetup_summary, plan?.itemCount ?: 0, plan?.apps?.size ?: 0, done, bad, plan?.unmapped?.size ?: 0),
            color = p.textSecondary, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            modifier = Modifier.testTag(FleetSetupTags.SUMMARY))
        OutlinedButton(onClick = { go(null) }, enabled = !busy && plan != null && plan.itemCount > 0,
            modifier = Modifier.fillMaxWidth().testTag(FleetSetupTags.RUN)) { Text(stringResource(R.string.fleetsetup_run)) }
        KitCard {
            for (app in apps) {
                val ap = plan?.of(app.id)
                val o = outcomes[app.id]
                val here = FleetSetup.installed(ctx, app.pkg)
                Column(Modifier.fillMaxWidth().testTag(FleetSetupTags.app(app.id))) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${if (here) "●" else "○"} ${labels[app.id] ?: app.id}", color = p.textPrimary, style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.fleetsetup_keys, ap?.items?.size ?: 0), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    if (o != null) Text(o.line(), color = if (o.ok) p.accent else p.textSecondary, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(FleetSetupTags.result(app.id)))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!here) OutlinedButton(enabled = !busy, modifier = Modifier.testTag(FleetSetupTags.install(app.id)), onClick = {
                            busy = true
                            scope.launch { withContext(Dispatchers.IO) { FleetSetup.install(ctx, app.id) }; busy = false; tick++ }
                        }) { Text(stringResource(R.string.fleetsetup_install)) }
                        else if (ap != null && ap.items.isNotEmpty()) OutlinedButton(enabled = !busy, modifier = Modifier.testTag(FleetSetupTags.retry(app.id)),
                            onClick = { go(setOf(app.id)) }) { Text(stringResource(if (o?.ok == false) R.string.fleetsetup_retry else R.string.fleetsetup_apply)) }
                    }
                }
            }
        }
        plan?.unmapped?.takeIf { it.isNotEmpty() }?.let { u ->
            KitCard {
                Text(stringResource(R.string.fleetsetup_unmapped, u.size), color = p.textPrimary, style = MaterialTheme.typography.labelMedium)
                for (x in u.take(40)) Text("${x.source} — ${x.why}", color = p.textSecondary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
