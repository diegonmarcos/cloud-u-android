package com.diegonmarcos.superapp.fleetconfigs

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import com.diegonmarcos.superapp.navbartop2.NavUiConfigCard
import com.diegonmarcos.superapp.themebg.ThemeBackground
import com.diegonmarcos.superapp.themebg.ThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ── building blocks ─────────────────────────────────────────────────────────────────────────────────────────────

@Composable
internal fun SectionCard(title: String, icon: ImageVector, c: ThemeColors, modifier: Modifier = Modifier, body: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.tabStrip, shape).border(1.dp, c.islandBorder, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
            Text(title, Modifier.padding(start = 8.dp).semantics { heading() }, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        body()
    }
}

@Composable
internal fun Body(text: String, c: ThemeColors) {
    Text(text.ifBlank { "Not declared." }, color = c.ink, fontSize = 13.sp, lineHeight = 18.sp)
}

@Composable
internal fun KeyValue(k: String, v: String, c: ThemeColors) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, Modifier.weight(1f), color = c.inkDim, fontSize = 13.sp)
        if (v.isNotEmpty()) Text(v, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun StatusRow(label: String, ok: Boolean, c: ThemeColors) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) c.accent else c.inkDim))
        Text(label, Modifier.padding(start = 10.dp).weight(1f), color = c.ink, fontSize = 13.sp)
        Text(if (ok) "On" else "Off", color = if (ok) c.accent else c.inkDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun Link(url: String, c: ThemeColors, onClick: () -> Unit) {
    Text(url.ifBlank { "Not declared." }, Modifier.clickable(enabled = url.isNotBlank(), role = Role.Button, onClick = onClick),
        color = c.accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
}

@Composable
internal fun ActionButton(label: String, c: ThemeColors, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        label,
        Modifier.clip(shape).background(c.tabSelected, shape).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp).testTag(tag),
        color = c.onTabSelected, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
    )
}

// ── device state ────────────────────────────────────────────────────────────────────────────────────────────────

private data class PermRow(val label: String, val granted: Boolean, val dangerous: Boolean)

private fun packageInfo(ctx: Context): PackageInfo? =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS) }.getOrNull()

private fun requested(ctx: Context): List<String> = packageInfo(ctx)?.requestedPermissions?.toList().orEmpty()

private fun permissionRows(ctx: Context): List<PermRow> {
    val info = packageInfo(ctx) ?: return emptyList()
    val names = info.requestedPermissions ?: return emptyList()
    val flags = info.requestedPermissionsFlags
    return names.mapIndexed { i, name ->
        val pi = runCatching { ctx.packageManager.getPermissionInfo(name, 0) }.getOrNull()
        @Suppress("DEPRECATION") val base = (pi?.protectionLevel ?: 0) and PermissionInfo.PROTECTION_MASK_BASE
        val granted = flags != null && i < flags.size && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
        val label = pi?.loadLabel(ctx.packageManager)?.toString()?.replaceFirstChar { it.uppercase() } ?: name.substringAfterLast('.')
        PermRow(label, granted, base == PermissionInfo.PROTECTION_DANGEROUS)
    }.distinctBy { it.label }.sortedBy { it.label }
}

/** "1.4.0 (12)": the declared version, or for a fork without android.* the installed package's. */
internal fun installedVersion(ctx: Context, decl: FleetConfigsDecl): String {
    val pi = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
    val name = decl.version.ifBlank { pi?.versionName.orEmpty() }.ifBlank { "?" }
    @Suppress("DEPRECATION") val code = if (decl.versionCode > 0) decl.versionCode.toLong()
        else if (pi == null) 0L else if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    return if (code > 0) "$name ($code)" else name
}

/** Release notes: date, short sha and the first line of each commit that changed the app. */
@Composable
internal fun ReleaseNotes(notes: List<ReleaseNote>, c: ThemeColors) {
    notes.forEach { n ->
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(n.title, color = c.ink, fontSize = 13.sp, lineHeight = 17.sp)
            Text("${n.date}  ·  ${n.sha.take(10)}", color = c.inkDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

internal fun open(ctx: Context, url: String) {
    if (url.isNotBlank()) open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

internal fun open(ctx: Context, intent: Intent) {
    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Opens a fleet app; when it is not installed, the fleet's release page (where Cloud Store itself installs from). */
internal fun launchApp(ctx: Context, pkg: String) {
    val launch = if (pkg.isBlank()) null else ctx.packageManager.getLaunchIntentForPackage(pkg)
    if (launch != null) open(ctx, launch) else open(ctx, "https://github.com/diegonmarcos/cloudy-u-android/releases/latest")
}

private fun dirSize(f: File?): Long {
    if (f == null || !f.exists()) return 0L
    var total = 0L
    var seen = 0
    f.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }.forEach {
        if (it.isFile) total += it.length()
        if (++seen > 200_000) return total
    }
    return total
}

private fun human(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble()
    var u = 0
    while (v >= 1024 && u < units.lastIndex) { v /= 1024; u++ }
    return if (u == 0) "$bytes B" else String.format(java.util.Locale.ROOT, "%.1f %s", v, units[u])
}
