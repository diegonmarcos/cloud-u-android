package com.diegonmarcos.superapp.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.appstore.AppInventory
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AppsPageTags {
    const val PAGE = "setup:apps"
    const val SUMMARY = "apps:summary"
    const val INSTALL = "apps:install-missing"
    const val CAPTURE = "apps:capture"
    fun row(pkg: String) = "apps:row:$pkg"
}

/**
 * Setup ▸ apps (spec 4.7): the working profile's inventory against this phone, classified by the
 * Store's own [AppInventory.plan] (installed / fleet / vendor or F-Droid rung / no source). Rows are
 * read-only. Two actions: **Install missing via Store** (the [SetupRunbook.handToStore] hand-off over
 * EXTRA_IMPORT; the Store shows its plan sheet before it installs) and **Capture installed → profile**
 * ([DeviceVault.backup] of this phone, one commit). An app with no vendor or F-Droid rung says
 * "no source declared" (the fix is the source map), never a store page.
 */
@Composable
fun AppsPage() {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val plan by produceState<AppInventory.Plan?>(null, tick) {
        value = withContext(Dispatchers.IO) { runCatching { SetupRunbook(ctx).appsPlan() }.getOrNull() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(AppsPageTags.PAGE),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KitSectionHeader("Apps", "the working profile's inventory vs this phone, in the Store's classes")
        val pl = plan
        Text(pl?.let { SetupRunbook.appsLine(it) } ?: "no working profile: load one on Setup ▸ runbook (step profile)",
            color = p.textSecondary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(AppsPageTags.SUMMARY))
        OutlinedButton(enabled = !busy && pl != null && (pl.ours.size + pl.direct.size) > 0,
            modifier = Modifier.fillMaxWidth().testTag(AppsPageTags.INSTALL), onClick = {
                status = if (SetupRunbook(ctx).handToStore()) "handed to Cloud Store: confirm its plan sheet"
                         else "✗ Cloud Store is not installed (Setup ▸ runbook, step store)"
            }) { Text("Install missing via Store (${(pl?.ours?.size ?: 0) + (pl?.direct?.size ?: 0)})") }
        OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth().testTag(AppsPageTags.CAPTURE), onClick = {
            busy = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { DeviceVault(ctx).backup(AccountDevice.id(ctx), dry = false) }.getOrNull() }
                status = r?.optString("result") ?: "✗ capture failed"
                busy = false; tick++
            }
        }) { Text("Capture installed → profile") }
        if (status.isNotBlank()) Text(status, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        if (pl != null) {
            Section("Not installed · fleet (${pl.ours.size})", pl.ours.map { it.pkg to "fleet release" })
            Section("Not installed · vendor / F-Droid (${pl.direct.size})", pl.direct.map { it.pkg to "declared rung" })
            Section("Not installed · no source declared (${pl.store.size + pl.manual.size})",
                (pl.store.map { it.entry } + pl.manual).map { it.pkg to "no source declared: add it to the source map" })
            Section("Installed (${pl.installed.size})", pl.installed.map { it.pkg to (if (it.ours) "fleet" else "installed") })
        }
    }
}

@Composable
private fun Section(title: String, rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    val p = LocalKitPalette.current
    KitCard {
        Text(title, color = p.textPrimary, style = MaterialTheme.typography.labelMedium)
        for ((pkg, why) in rows) Text("$pkg  ·  $why", color = p.textSecondary, fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(AppsPageTags.row(pkg)))
    }
}
