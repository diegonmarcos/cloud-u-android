package com.diegonmarcos.cloudstore

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.appstore.BuildConfig as StoreBuild
import com.diegonmarcos.superapp.appstore.StoreDensity
import com.diegonmarcos.superapp.appstore.AppsMeshFragment
import androidx.fragment.compose.AndroidFragment
import com.diegonmarcos.superapp.updater.Fleet

/**
 * Store > Access: Apps Mesh, Android Perms and Cloud Perms, one bottom-nav destination whose
 * top tabs (build.json::ui access.pages: mesh, android, cloud) MainActivity draws with PageTabs.
 * Apps Mesh left the Cloud page; the permissions page that was here split in two - the OS's own
 * runtime grants, and the constellation permission - one page each instead of two panes per app.
 * (The logic is the old Perms tab's, drawn with Views before.) The constellation is a trusted
 * environment because every APK is signed with the SAME key, which is what makes signature-level
 * permissions usable between our apps. Each app card carries two panes: Android Perms (the OS's
 * own runtime grants, read-only here: only the system UI may change them) and Cloud Perms
 * (CONSTELLATION_DATA, protectionLevel="signature", granted at install to every APK carrying our
 * key). Neither pane renders a toggle: a switch could only misreport state it does not control.
 */
@Composable
fun AccessPage(page: String) {
    when (page) {
        PAGE_MESH -> AndroidFragment<AppsMeshFragment>(Modifier.fillMaxSize())
        PAGE_ANDROID -> PermsList(cloud = false)
        else -> PermsList(cloud = true)
    }
}

const val PAGE_MESH = "mesh"
const val PAGE_ANDROID = "android"
const val PAGE_CLOUD = "cloud"

@Composable
private fun PermsList(cloud: Boolean) {
    val ctx = LocalContext.current
    val fleet = remember { Fleet.parse(StoreBuild.CONSTELLATION_FLEET_B64) }
    val pad = StoreDensity.dpValue(StoreDensity.S12).dp
    LazyColumn(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.spacedBy(dpOf(StoreDensity.S4))) {
        item {
            Text(if (cloud)
                "Cloud Perms: our constellation permission, granted automatically to every app carrying the Cloud signing key, so they talk freely to each other by default."
            else
                "Android Perms: the OS's own runtime grants, read-only here - the system screen owns them.",
                color = DIM, fontSize = StoreDensity.T_META.sp)
        }
        items(fleet.filter { it.pkg != ctx.packageName }, key = { it.id }) { app -> PermsCard(ctx, app, cloud) }
    }
}

@Composable
private fun PermsCard(ctx: Context, app: Fleet.App, cloud: Boolean) {
    val pkg = Fleet.installedId(ctx, app)
    val h = dpOf(StoreDensity.S12); val v = dpOf(StoreDensity.S6)
    Column(Modifier.fillMaxWidth().background(CARD).padding(h, v)) {
        Text(app.label + (if (app.kind == "lib") "  ·  lib" else ""), color = Color.White,
            fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_TITLE.sp)
        Text(pkg ?: app.pkg, color = DIM, fontFamily = FontFamily.Monospace, fontSize = StoreDensity.T_CAPTION.sp)
        when {
            pkg == null -> Text("◯ not installed", color = MISSING, fontSize = StoreDensity.T_META.sp)
            sameSignature(ctx, pkg) -> Text("🔑 same key  ·  eligible for signature-level data access", color = UP, fontSize = StoreDensity.T_META.sp)
            else -> Text("⚠ different signature  ·  NOT eligible — reinstall from our release", color = BLOCKED, fontSize = StoreDensity.T_META.sp)
        }
        if (pkg == null) return@Column
        if (cloud) CloudPerms(ctx, pkg) else AndroidPerms(ctx, pkg)
    }
}

/** Android's own runtime permissions for [pkg]: what it requests and whether it holds it, then a hand-off to the system screen. */
@Composable
private fun AndroidPerms(ctx: Context, pkg: String) {
    val pm = ctx.packageManager
    val requested = runCatching {
        pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList()
    }.getOrNull().orEmpty()
        // Our constellation permission lives on the Cloud Perms page; here the platform's own.
        .filter { it.startsWith("android.permission.") }.sorted()
    if (requested.isEmpty()) {
        Text("Requests no Android permissions.", color = DIM, fontSize = StoreDensity.T_META.sp)
    } else for (p in requested) {
        val granted = pm.checkPermission(p, pkg) == PackageManager.PERMISSION_GRANTED
        Text((if (granted) "✓  " else "·  ") + p.removePrefix("android.permission."),
            color = if (granted) UP else MISSING, fontSize = StoreDensity.T_CAPTION.sp)
    }
    Text("System settings ↗", Modifier.padding(top = dpOf(StoreDensity.S4))
        .background(IDLE).clickable {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Toast.makeText(ctx, "No settings screen", Toast.LENGTH_SHORT).show() }
        }.padding(dpOf(StoreDensity.S8), dpOf(StoreDensity.S4)),
        color = Color.White, fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_META.sp)
}

/** The constellation's own permission. There is deliberately no switch: CONSTELLATION_DATA is
 *  protectionLevel="signature", so Android grants it at install to every APK carrying our signing key. */
@Composable
private fun CloudPerms(ctx: Context, pkg: String) {
    val pm = ctx.packageManager
    val holds = pm.checkPermission(CONSTELLATION_PERM, pkg) == PackageManager.PERMISSION_GRANTED
    val weHold = pm.checkPermission(CONSTELLATION_PERM, ctx.packageName) == PackageManager.PERMISSION_GRANTED
    Text(if (holds) "✓  Cloud data access — granted" else "✕  Cloud data access — not granted",
        color = if (holds) UP else BLOCKED, fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_BODY.sp)
    Text(CONSTELLATION_PERM, color = DIM, fontFamily = FontFamily.Monospace, fontSize = StoreDensity.T_CAPTION.sp)
    Text(when {
        holds && weHold -> "Two-way: this app and Cloud Store can each read the other's constellation data. " +
            "Granted automatically at install because both carry the Cloud signing key — no prompt, and no outside APK can obtain it."
        holds -> "This app holds it but Cloud Store does not — reinstall Cloud Store from our release."
        sameSignature(ctx, pkg) -> "Same signing key, but this build predates the constellation permission. " +
            "Update it from the Cloud page; the grant lands on reinstall."
        else -> "Signed with a different key, so Android refuses this permission. " +
            "Reinstall from our release to bring it into the constellation."
    }, color = DIM, fontSize = StoreDensity.T_META.sp)
}

/** True when [pkg] is signed with the same key as us: the whole basis of `signature`-level permissions. */
@Suppress("DEPRECATION")
private fun sameSignature(ctx: Context, pkg: String): Boolean = runCatching {
    ctx.packageManager.checkSignatures(ctx.packageName, pkg) == PackageManager.SIGNATURE_MATCH
}.getOrDefault(false)

/** Declared in libs:core's manifest at protectionLevel="signature" and merged into every constellation app. */
private const val CONSTELLATION_PERM = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"

private fun dpOf(step: Int): Dp = StoreDensity.dpValue(step).dp
private val UP = Color(0xFF48BB78)
private val MISSING = Color(0xFF63B3ED)
private val BLOCKED = Color(0xFFF56565)
private val DIM = Color(0x99FFFFFF)
private val CARD = Color(0xFF1C1C24)
private val IDLE = Color(0xFF2A2A33)
