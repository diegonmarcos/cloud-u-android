package com.diegonmarcos.cloudkgstore.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.cloudkgstore.PhoneApp
import com.diegonmarcos.cloudkgstore.PhoneApps
import com.diegonmarcos.cloudkgstore.PhoneDetail
import com.diegonmarcos.cloudkgstore.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phone > Apps: the installed apps index read from PackageManager on this phone (package, version, permissions, components).
 * The list is filtered here; a tap opens one app's permissions (granted first) and its activities, services, receivers and
 * providers. Nothing is stored or sent.
 */
@Composable
fun PhoneAppsPage() {
    val ctx = LocalContext.current.applicationContext
    var all by remember { mutableStateOf<List<PhoneApp>?>(null) }
    var q by rememberSaveable { mutableStateOf("") }
    var system by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<PhoneDetail?>(null) }
    LaunchedEffect(Unit) { all = withContext(Dispatchers.IO) { PhoneApps.load(ctx.packageManager) } }
    LaunchedEffect(open) { detail = open?.let { p -> withContext(Dispatchers.IO) { PhoneApps.detail(ctx.packageManager, p) } } }
    BackHandler(enabled = open != null) { open = null }

    val d = detail
    if (open != null) {
        if (d == null) KgNote(stringResource(R.string.status_busy)) else AppDetail(d)
        return
    }
    val rows = PhoneApps.filter(all.orEmpty(), q, system)
    Column(Modifier.fillMaxSize()) {
        KgQueryBar(q, { q = it }, stringResource(R.string.phone_hint), false, {}, null)
        KgChips(listOf("user" to stringResource(R.string.phone_user), "all" to stringResource(R.string.phone_all)), if (system) "all" else "user") { system = it == "all" }
        KgNote(if (all == null) stringResource(R.string.status_busy) else stringResource(R.string.phone_count, rows.size, all.orEmpty().size))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(rows, key = { it.pkg }) { a ->
                KgRow(a.label, a.pkg, a.version + " (" + a.code + ")" + if (a.system) " · " + stringResource(R.string.phone_system) else "", onClick = { open = a.pkg })
            }
        }
    }
}

@Composable
private fun AppDetail(d: PhoneDetail) {
    val perms = PhoneApps.orderedPermissions(d.permissions)
    LazyColumn(Modifier.fillMaxSize()) {
        item { KgRow(d.app.label, d.app.pkg, stringResource(R.string.phone_version, d.app.version, d.app.code) + if (d.app.installer.isBlank()) "" else " · " + stringResource(R.string.phone_installer, d.app.installer)) }
        item { KgHeading(stringResource(R.string.phone_permissions, perms.size)) }
        items(perms) { p -> KgRow(PhoneApps.shortName(p.name), if (p.granted) stringResource(R.string.phone_granted) else stringResource(R.string.phone_not_granted)) }
        item { KgHeading(stringResource(R.string.phone_activities, d.activities.size)) }
        items(d.activities) { KgRow(it) }
        item { KgHeading(stringResource(R.string.phone_services, d.services.size)) }
        items(d.services) { KgRow(it) }
        item { KgHeading(stringResource(R.string.phone_receivers, d.receivers.size)) }
        items(d.receivers) { KgRow(it) }
        item { KgHeading(stringResource(R.string.phone_providers, d.providers.size)) }
        items(d.providers) { KgRow(it) }
    }
}
