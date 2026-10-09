package com.diegonmarcos.superapp.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.appstore.AppInventory
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner
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
 * Store's own [AppInventory.plan] (installed / fleet / vendor or F-Droid rung / no source), one shelf
 * each with a state pill per row. Rows are read-only. Two actions: **Install missing via Store** (the [SetupRunbook.handToStore] hand-off over
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
    var status by remember { mutableStateOf(Said(KitState.IDLE, "")) }
    var busy by remember { mutableStateOf(false) }
    val plan by produceState<AppInventory.Plan?>(null, tick) {
        value = withContext(Dispatchers.IO) { runCatching { SetupRunbook(ctx).appsPlan() }.getOrNull() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(AppsPageTags.PAGE),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val pl = plan
        val missing = (pl?.ours?.size ?: 0) + (pl?.direct?.size ?: 0)
        KitStatusBanner(pl?.let { SetupRunbook.appsLine(it) } ?: "No working profile: load one on Setup ▸ runbook (step profile)",
            if (pl == null) KitState.WARN else if (missing == 0) KitState.OK else KitState.WARN,
            Modifier.testTag(AppsPageTags.SUMMARY), tag = "apps")
        KitActionBar(listOf(
            KitAction("Install missing via Store ($missing)", AppsPageTags.INSTALL, !busy && pl != null && missing > 0) {
                status = if (SetupRunbook(ctx).handToStore()) Said(KitState.OK, "handed to Cloud Store: confirm its plan sheet")
                         else Said(KitState.BAD, "Cloud Store is not installed (Setup ▸ runbook, step store)")
            },
            KitAction("Capture installed → profile", AppsPageTags.CAPTURE, !busy) {
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { DeviceVault(ctx).backup(AccountDevice.id(ctx), dry = false) }.getOrNull() }
                    status = r?.optString("result")?.let(::said) ?: Said(KitState.BAD, "capture failed")
                    busy = false; tick++
                }
            },
        ))
        SaidBanner(status, "apps:status")
        if (pl != null) {
            Section("Not installed · fleet", pl.ours.map { it.pkg to "fleet release" }, "missing" to KitState.WARN)
            Section("Not installed · vendor / F-Droid", pl.direct.map { it.pkg to "declared rung" }, "missing" to KitState.WARN)
            Section("Not installed · no source declared",
                (pl.store.map { it.entry } + pl.manual).map { it.pkg to "no source declared: add it to the source map" }, "no source" to KitState.BAD)
            Section("Installed", pl.installed.map { it.pkg to (if (it.ours) "fleet" else "installed") }, "installed" to KitState.OK)
        }
    }
}

/** One shelf: an eyebrow with its count, then one dense row per package with its state pill. */
@Composable
private fun Section(title: String, rows: List<Pair<String, String>>, pill: Pair<String, KitState>) {
    if (rows.isEmpty()) return
    KitSectionHeader("$title (${rows.size})", "", eyebrow = true)
    KitCard {
        for ((pkg, why) in rows) KitListRow(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }, secondary = "$pkg · $why",
            leading = pkg.substringAfterLast('.').take(1).uppercase(), pill = pill, tag = pkg,
            modifier = Modifier.testTag(AppsPageTags.row(pkg)))
    }
}
