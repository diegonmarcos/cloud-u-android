package com.diegonmarcos.superapp.profile

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The pages #1081 added to Cloud Account's five sections (Personal Info ▸ Wallet, Configs ▸ Desktop / C3,
 * Data ▸ Backups / Devices ▸ Mesh, Activity). Each is drawn from [GroupTitle] and [Tile] only. The pure parts
 * ([PublicConfig], [MeshStatus], [ActivityLog]) are in SectionKit.kt.
 */
object SectionPageTags {
    const val WALLET = "section:wallet"
    const val DESKTOP = "section:desktop"
    const val C3 = "section:c3"
    const val BACKUPS = "section:backups"
    const val MESH = "section:mesh"
    const val ACTIVITY = "section:activity"
    const val OPEN_DRIVE = "section:backups:open_drive"
    const val OPEN_C3 = "section:c3:open"
    const val REFRESH = "section:mesh:refresh"
    const val CLEAR = "section:activity:clear"
}

private tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}

@Composable
private fun SectionColumn(tag: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

/** Opens [route] through the host; a host that cannot route it says so in [say]. */
private fun openRoute(ctx: Context, route: String, say: (String) -> Unit) {
    val a = ctx.hostActivity()
    if (a == null || !AccountHost.route(a, route)) say("cannot open ${route.substringAfter(':')} from here")
}

/** Personal Info ▸ Wallet: the way to Cloud Wallet and to Cloud Vault, where payment and identity items live (none is stored here). */
@Composable
fun WalletPage() {
    val ctx = LocalContext.current
    var line by remember { mutableStateOf("") }
    SectionColumn(SectionPageTags.WALLET) {
        if (line.isNotBlank()) KitStatusBanner(line, KitState.WARN, tag = "wallet")
        GroupTitle("Wallet")
        Tile("Cloud Wallet", secondary = "cards, passes and tickets live in their own app; none is kept here",
            pill = "Cloud Wallet" to KitState.IDLE, onClick = { openRoute(ctx, "extapp:cloud-wallet") { line = it } })
        Tile("ID documents", secondary = "held encrypted by Cloud Vault; open it to see them",
            pill = "Cloud Vault" to KitState.IDLE, onClick = { openRoute(ctx, "extapp:cloud-vault") { line = it } })
    }
}

/** Configs ▸ Desktop: the declared desktop-class devices, OS and Home Manager, public fields only. */
@Composable
fun DesktopConfigsPage() {
    val ctx = LocalContext.current
    val model = remember { AccountModel.get(ctx) }
    val entries = remember(model.version.intValue) { PublicConfig.desktops(model.server()?.body) }
    SectionColumn(SectionPageTags.DESKTOP) {
        GroupTitle("Desktop · OS and Home Manager")
        if (entries.isEmpty()) KitStatusBanner("no desktop device declared in the vault yet", KitState.IDLE, tag = "desktop")
        for (e in entries) Tile(e.label, tag = "desktop:${e.id}",
            secondary = e.rows.joinToString(" · ") { (k, v) -> "${k.replace('_', '-')} $v" }.ifBlank { "declared, no public fields" })
    }
}

/** Configs ▸ C3: the declared containers, public fields only, and the way to Cloud C3 itself. */
@Composable
fun C3ConfigsPage() {
    val ctx = LocalContext.current
    val model = remember { AccountModel.get(ctx) }
    val entries = remember(model.version.intValue) { PublicConfig.containers(model.server()?.body) }
    var line by remember { mutableStateOf("") }
    SectionColumn(SectionPageTags.C3) {
        if (line.isNotBlank()) KitStatusBanner(line, KitState.WARN, tag = "c3")
        GroupTitle("C3 · containers")
        if (entries.isEmpty()) KitStatusBanner("no container declared in the vault; Cloud C3 holds the live list", KitState.IDLE, tag = "c3:none")
        for (e in entries) Tile(e.label, tag = "c3:${e.id}",
            secondary = e.rows.joinToString(" · ") { (k, v) -> "$k $v" }.ifBlank { "declared, no public fields" })
        KitActionBar(listOf(KitAction("Open Cloud C3", SectionPageTags.OPEN_C3) { openRoute(ctx, "extapp:cloud-c3") { line = it } }))
    }
}

/** Data ▸ Backups ▸ Databases: this app's runtime databases and stores (names and sizes only) and the way to Cloud Drive's Backups. */
@Composable
fun BackupsPage() {
    val ctx = LocalContext.current
    var line by remember { mutableStateOf("") }
    val stores = remember { runtimeStores(ctx) }
    SectionColumn(SectionPageTags.BACKUPS) {
        if (line.isNotBlank()) KitStatusBanner(line, KitState.WARN, tag = "backups")
        GroupTitle("Auto-backup")
        Tile("Device profile", secondary = "capture, compare, commit only when something changed",
            pill = AutoBackup.mode(ctx).ifBlank { AutoBackup.OFF } to KitState.IDLE)
        GroupTitle("Runtime databases · ${stores.size}")
        if (stores.isEmpty()) KitStatusBanner("no runtime database yet", KitState.IDLE, tag = "backups:none")
        for ((name, size) in stores) Tile(name, secondary = "$size B", tag = "store:$name")
        KitActionBar(listOf(KitAction("Open Cloud Drive › Backups", SectionPageTags.OPEN_DRIVE) {
            openRoute(ctx, "extapp:cloud-drive") { line = it }
        }))
    }
}

/** (name, bytes) of this app's SQLite databases and preference files, without their contents. */
private fun runtimeStores(ctx: Context): List<Pair<String, Long>> {
    val dbs = ctx.databaseList().filterNot { it.endsWith("-journal") || it.endsWith("-wal") || it.endsWith("-shm") }
        .map { it to ctx.getDatabasePath(it).length() }
    val prefsDir = File(ctx.applicationInfo.dataDir, "shared_prefs")
    val prefs = prefsDir.listFiles()?.filter { it.isFile }?.map { it.name.removeSuffix(".xml") to it.length() }.orEmpty()
    return (dbs + prefs).sortedBy { it.first }
}

/** Data ▸ Devices ▸ Mesh: every declared owner device, with this phone's live tunnel and a probe of each other one. */
@Composable
fun DevicesMeshPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val devices = remember(tick) { AccountDevice.declared(ctx) }
    val thisId = remember(tick) { AccountDevice.id(ctx) }
    val tunnel = remember(tick) { AccountHost.mesh?.let { m -> runCatching { m.state(ctx) }.getOrNull() } }
    val up = tunnel != null && (tunnel.name.isNotBlank() || tunnel.address.isNotBlank())
    val probed = remember { mutableStateMapOf<String, Boolean>() }
    var busy by remember { mutableStateOf(false) }
    fun probe() {
        if (busy || !up) return
        busy = true
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                devices.filter { it.id != thisId }.associate { d ->
                    d.id to runCatching { InetAddress.getByName(d.wgIp).isReachable(1500) }.getOrDefault(false)
                }
            }
            probed.putAll(found)
            busy = false
        }
    }
    LaunchedEffect(tick, up) { probe() }
    SectionColumn(SectionPageTags.MESH) {
        KitStatusBanner(
            when {
                AccountHost.mesh == null -> "no mesh tunnel in this app"
                up -> "tunnel ${tunnel!!.name.ifBlank { "up" }} · ${tunnel.address.ifBlank { "no address" }} · ${tunnel.peerKeys.size} peers"
                else -> "mesh tunnel is down"
            }, if (up) KitState.OK else KitState.WARN, tag = "mesh")
        KitActionBar(listOf(KitAction(if (busy) "Checking…" else "Refresh", SectionPageTags.REFRESH, !busy) { tick++ }))
        GroupTitle("Owner devices · ${devices.size}")
        if (devices.isEmpty()) KitStatusBanner("no device declared in the vault yet", KitState.IDLE, tag = "mesh:none")
        for (d in devices) {
            val kind = MeshStatus.classify(d.id == thisId, up, probed[d.id])
            val state = when (kind) {
                MeshStatus.Kind.THIS_PHONE_UP, MeshStatus.Kind.REACHABLE -> KitState.OK
                MeshStatus.Kind.UNREACHABLE, MeshStatus.Kind.THIS_PHONE_DOWN -> KitState.BAD
                MeshStatus.Kind.TUNNEL_DOWN -> KitState.WARN
                MeshStatus.Kind.UNKNOWN -> KitState.IDLE
            }
            val peer = if (d.publicKey.isNotBlank() && tunnel != null && d.publicKey in tunnel.peerKeys) " · peer" else ""
            Tile(d.label, tag = "device:${d.id}", pill = MeshStatus.word(kind) to state,
                secondary = listOf(d.type, d.wgIp).filter { it.isNotBlank() }.joinToString(" · ") + peer)
        }
    }
}

/** Activity: the on-device log of what the account did, newest first. */
@Composable
fun ActivityPage() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val items = remember(tick) { ActivityLog.items(ctx) }
    val fmt = remember { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()) }
    SectionColumn(SectionPageTags.ACTIVITY) {
        GroupTitle("Activity · ${items.size}")
        if (items.isEmpty()) KitStatusBanner("nothing recorded yet", KitState.IDLE, tag = "activity:none")
        for (row in items) Tile(row.text, tag = "activity:${row.at}:${row.kind}", secondary = "${fmt.format(Date(row.at))} · ${row.kind}")
        if (items.isNotEmpty()) KitActionBar(listOf(KitAction("Clear log", SectionPageTags.CLEAR) { ActivityLog.clear(ctx); tick++ }), filledFirst = false)
    }
}
