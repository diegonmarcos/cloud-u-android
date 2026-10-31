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

/** The two shared Configs pages. */
public enum class FleetConfigsPage(public val id: String, public val label: String) {
    General("general", "General"),
    About("about", "About"),
}

/** Test tags (also UiAutomator resource ids: the runtime nav walk opens and photographs both pages by them). */
public object FleetConfigsTags {
    public const val GENERAL: String = "fleetcfg_general"
    public const val ABOUT: String = "fleetcfg_about"
    public const val BACK: String = "fleetcfg_back"
    public const val ANCHORS: String = "fleetcfg_anchors"
    public const val OPEN_STORE: String = "fleetcfg_open_store"
    public const val OPEN_SUPERAPP: String = "fleetcfg_open_superapp"
    public const val OPEN_APP_CONFIGS: String = "fleetcfg_open_app_configs"
    public fun anchor(id: String): String = "fleetcfg_anchor_$id"
    public fun section(id: String): String = "fleetcfg_section_$id"
    public fun about(field: String): String = "fleetcfg_about_$field"
    public fun page(p: FleetConfigsPage): String = if (p == FleetConfigsPage.General) GENERAL else ABOUT
}

/** The label of the first row's and the Clean drawer's entry for [p]. */
public fun fleetConfigsEntryLabel(p: FleetConfigsPage): String = "Configs ▸ " + p.label

internal fun sectionIcon(id: String): ImageVector = when (id) {
    "perms" -> Icons.Filled.Security
    "battery" -> Icons.Filled.BatteryChargingFull
    "storage" -> Icons.Filled.Storage
    "notifications" -> Icons.Filled.Notifications
    "ui" -> Icons.Filled.Dashboard
    "language" -> Icons.Filled.Language
    else -> Icons.Filled.Tune
}

/**
 * Configs > General or Configs > About, full screen over the app (a dialog window, so every app on the shared scaffold gets them
 * with no route of its own). [onAppConfigs] opens the app's own Configs (General's "App" section).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
public fun FleetConfigsScreen(
    page: FleetConfigsPage,
    dark: Boolean,
    onDismiss: () -> Unit,
    onAppConfigs: () -> Unit,
    decl: FleetConfigsDecl = FleetConfigs.current,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val c = ThemeBackground.colors(dark)
        Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.top, c.bottom)))
                .semantics { testTagsAsResourceId = true }.testTag(FleetConfigsTags.page(page))
                .statusBarsPadding().navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onDismiss)
                        .semantics { contentDescription = "Back" }.testTag(FleetConfigsTags.BACK),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = c.ink) }
                Text(
                    fleetConfigsEntryLabel(page), Modifier.padding(start = 4.dp).semantics { heading() },
                    color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            when (page) {
                FleetConfigsPage.General -> FleetGeneralPage(decl, dark, c, onAppConfigs = { onDismiss(); onAppConfigs() })
                FleetConfigsPage.About -> FleetAboutPage(decl, c)
            }
        }
    }
}

/**
 * Configs > General: ONE page. A row of anchor icons at the top (one per declared section) scrolls to that section; the sections are
 * the app's `configs_pages.general.sections` (Perms, Battery, Storage/backup, Notifications, UI, Language, and App: the app's own
 * Configs pages). Each section reads the device's live state for THIS app and opens the system screen that changes it.
 */
@Composable
public fun FleetGeneralPage(decl: FleetConfigsDecl, dark: Boolean, c: ThemeColors, onAppConfigs: () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val tops = remember { mutableStateMapOf<String, Int>() }
    // The system screens these sections open change state outside the app: re-read it while the page is up (cheap getters only).
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    val sections = decl.general
    val active = sections.lastOrNull { (tops[it.id] ?: Int.MAX_VALUE) <= scroll.value + 24 }?.id ?: sections.firstOrNull()?.id
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp).testTag(FleetConfigsTags.ANCHORS),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sections.forEach { s ->
            val on = s.id == active
            val shape = RoundedCornerShape(14.dp)
            Column(
                Modifier.width(64.dp).clip(shape).background(if (on) c.tabSelected else c.island, shape)
                    .clickable(role = Role.Tab) { scope.launch { scroll.animateScrollTo(tops[s.id] ?: 0) } }
                    .semantics { selected = on; contentDescription = s.label }
                    .padding(vertical = 8.dp).testTag(FleetConfigsTags.anchor(s.id)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(sectionIcon(s.id), contentDescription = null, tint = if (on) c.onTabSelected else c.onIsland, modifier = Modifier.size(22.dp))
                Text(s.label, color = if (on) c.onTabSelected else c.onIsland, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(2.dp))
        sections.forEach { s ->
            SectionCard(s.label, sectionIcon(s.id), c, Modifier.testTag(FleetConfigsTags.section(s.id)).onGloballyPositioned { tops[s.id] = it.positionInParent().y.toInt() }) {
                if (s.note.isNotBlank()) Body(s.note, c)
                when (s.id) {
                    "perms" -> PermsSection(c, tick)
                    "battery" -> BatterySection(c, tick)
                    "storage" -> StorageSection(c, tick)
                    "notifications" -> NotificationsSection(c, tick)
                    "ui" -> NavUiConfigCard(dark = dark)
                    "language" -> LanguageSection(c, tick)
                    FleetConfigs.APP_SECTION -> ActionButton("Open ${decl.name.ifBlank { "app" }} configs", c, FleetConfigsTags.OPEN_APP_CONFIGS, onAppConfigs)
                }
            }
        }
        // room so the last section can scroll to the top under its anchor
        Spacer(Modifier.height(320.dp))
    }
}

/** Configs > About: the app in full, from its declaration plus what the package manager knows. */
@Composable
public fun FleetAboutPage(decl: FleetConfigsDecl, c: ThemeColors) {
    val ctx = LocalContext.current
    val a = decl.about
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val icon = remember(ctx) { runCatching { ctx.packageManager.getApplicationIcon(ctx.packageName).toBitmap(192, 192).asImageBitmap() }.getOrNull() }
        val label = decl.name.ifBlank { ctx.applicationInfo.loadLabel(ctx.packageManager).toString() }
        val version = decl.version.ifBlank { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp).testTag(FleetConfigsTags.about("header")), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Image(icon, contentDescription = label, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)))
            Column(Modifier.padding(start = 14.dp)) {
                Text(label, color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(FleetConfigsTags.about("name")))
                Text("Version $version" + if (decl.versionCode > 0) " (${decl.versionCode})" else "", color = c.inkDim, fontSize = 13.sp,
                    modifier = Modifier.testTag(FleetConfigsTags.about("version")))
                Text("Build ${decl.sha.ifBlank { "local" }}", color = c.inkDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag(FleetConfigsTags.about("sha")))
                Text(ctx.packageName, color = c.inkDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
        SectionCard("What it does", Icons.Filled.Info, c, Modifier.testTag(FleetConfigsTags.about("what"))) { Body(a.what, c) }
        SectionCard("Features", Icons.Filled.Star, c, Modifier.testTag(FleetConfigsTags.about("features"))) {
            a.features.forEach { f -> Row { Text("•  ", color = c.accent, fontSize = 13.sp); Body(f, c) } }
        }
        SectionCard("Source and licence", Icons.Filled.Code, c, Modifier.testTag(FleetConfigsTags.about("repo"))) {
            Link(a.repo, c) { open(ctx, a.repo) }
            KeyValue("Licence", FleetConfigs.licenceLabel(a.license), c)
        }
        SectionCard("Privacy", Icons.Filled.Lock, c, Modifier.testTag(FleetConfigsTags.about("privacy"))) { Body(a.privacy, c) }
        SectionCard("Backend services", Icons.Filled.Cloud, c, Modifier.testTag(FleetConfigsTags.about("backends"))) {
            if (a.backends.isEmpty()) Body("None. This app talks to no fleet service; everything it shows lives on the phone.", c)
            a.backends.forEach { b ->
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(b.id + if (b.kind.isNotBlank()) "  ·  ${b.kind}" else "", color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (b.why.isNotBlank()) Text(b.why, color = c.inkDim, fontSize = 12.sp)
                    Text(b.mesh.ifBlank { "mesh URL not resolved in this build" }, color = c.accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Text("Mesh URLs answer only inside the private mesh (WireGuard); off the mesh the app falls back to the public, signed-in route.",
                color = c.inkDim, fontSize = 11.sp)
        }
        SectionCard("Crash and debug", Icons.Filled.BugReport, c, Modifier.testTag(FleetConfigsTags.about("debug"))) {
            KeyValue("Crash reports", "", c)
            Link(a.crash, c) { open(ctx, a.crash) }
            KeyValue("Debug API", "", c)
            Link(a.debug, c) { open(ctx, a.debug) }
        }
        SectionCard("Cloud Store", Icons.Filled.Storefront, c, Modifier.testTag(FleetConfigsTags.about("store"))) {
            Body(FleetConfigs.STORE_TEXT, c)
            ActionButton("Open Cloud Store", c, FleetConfigsTags.OPEN_STORE) { launchApp(ctx, decl.storePkg) }
        }
        SectionCard("Cloud SuperApp", Icons.Filled.Apps, c, Modifier.testTag(FleetConfigsTags.about("superapp"))) {
            Body(FleetConfigs.SUPERAPP_TEXT, c)
            ActionButton("Open Cloud SuperApp", c, FleetConfigsTags.OPEN_SUPERAPP) { launchApp(ctx, decl.superappPkg) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── sections of General ─────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun PermsSection(c: ThemeColors, tick: Int) {
    val ctx = LocalContext.current
    val rows = remember(tick) { permissionRows(ctx) }
    val runtime = rows.filter { it.dangerous }
    if (runtime.isEmpty()) Body("This app asks for no runtime permission.", c)
    runtime.forEach { r -> StatusRow(r.label, r.granted, c) }
    val special = remember(tick) { specialAccess(ctx) }
    special.forEach { (label, on) -> StatusRow(label, on, c) }
    val normal = rows.count { !it.dangerous }
    if (normal > 0) Text("$normal install-time permissions granted with the app.", color = c.inkDim, fontSize = 11.sp)
    ActionButton("Open app permissions", c, "fleetcfg_open_perms") { open(ctx, appDetails(ctx)) }
}

@Composable
private fun BatterySection(c: ThemeColors, tick: Int) {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    val unrestricted = remember(tick) { pm.isIgnoringBatteryOptimizations(ctx.packageName) }
    StatusRow(if (unrestricted) "Unrestricted: not battery optimised" else "Battery optimised: Android may stop background work", unrestricted, c)
    if (Build.VERSION.SDK_INT >= 28) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val restricted = remember(tick) { am.isBackgroundRestricted }
        StatusRow(if (restricted) "Background use restricted" else "Background use allowed", !restricted, c)
    }
    val canAsk = remember { requested(ctx).contains(android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) }
    ActionButton(if (unrestricted) "Battery settings" else "Allow unrestricted battery", c, "fleetcfg_open_battery") {
        open(
            ctx,
            if (!unrestricted && canAsk) Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
            else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
    }
}

@Composable
private fun StorageSection(c: ThemeColors, tick: Int) {
    val ctx = LocalContext.current
    var cleared by remember { mutableIntStateOf(0) }
    val sizes by produceState(Pair(-1L, -1L), cleared) {
        value = withContext(Dispatchers.IO) { Pair(dirSize(File(ctx.applicationInfo.dataDir)), dirSize(ctx.cacheDir) + dirSize(ctx.externalCacheDir)) }
    }
    KeyValue("App data", if (sizes.first < 0) "…" else human(sizes.first), c)
    KeyValue("Cache", if (sizes.second < 0) "…" else human(sizes.second), c)
    val backup = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0
    StatusRow(if (backup) "Android backup allowed" else "Android backup off for this app", backup, c)
    Text("Settings and app state are part of the phone profile Cloud Account backs up to, and restores from, the private vault repository.",
        color = c.inkDim, fontSize = 11.sp)
    if (requested(ctx).contains("android.permission.MANAGE_EXTERNAL_STORAGE") && Build.VERSION.SDK_INT >= 30) {
        val all = remember(tick) { Environment.isExternalStorageManager() }
        StatusRow("All-files access", all, c)
        if (!all) ActionButton("Allow all-files access", c, "fleetcfg_open_allfiles") {
            open(ctx, Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName)))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton("Clear cache", c, "fleetcfg_clear_cache") {
            ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            ctx.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
            cleared++
        }
        ActionButton("Storage settings", c, "fleetcfg_open_storage") { open(ctx, appDetails(ctx)) }
    }
}

@Composable
private fun NotificationsSection(c: ThemeColors, tick: Int) {
    val ctx = LocalContext.current
    val on = remember(tick) { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    val channels = remember(tick) { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notificationChannels }
    StatusRow(if (on) "Notifications allowed" else "Notifications blocked", on, c)
    if (channels.isEmpty()) Text("No notification channel created yet.", color = c.inkDim, fontSize = 11.sp)
    channels.forEach { ch -> StatusRow(ch.name.toString(), ch.importance != NotificationManager.IMPORTANCE_NONE, c) }
    ActionButton("Notification settings", c, "fleetcfg_open_notifications") {
        open(ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
    }
}

@Composable
private fun LanguageSection(c: ThemeColors, tick: Int) {
    val ctx = LocalContext.current
    val current = remember(tick) {
        val app = if (Build.VERSION.SDK_INT >= 33) ctx.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales?.toLanguageTags().orEmpty() else ""
        if (app.isNotBlank()) app else "System default (" + ctx.resources.configuration.locales[0].displayName + ")"
    }
    KeyValue("App language", current, c)
    ActionButton("Change language", c, "fleetcfg_open_language") {
        open(
            ctx,
            if (Build.VERSION.SDK_INT >= 33) Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
            else Intent(Settings.ACTION_LOCALE_SETTINGS),
        )
    }
}

// ── building blocks ─────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, icon: ImageVector, c: ThemeColors, modifier: Modifier = Modifier, body: @Composable ColumnScope.() -> Unit) {
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
private fun Body(text: String, c: ThemeColors) {
    Text(text.ifBlank { "Not declared." }, color = c.ink, fontSize = 13.sp, lineHeight = 18.sp)
}

@Composable
private fun KeyValue(k: String, v: String, c: ThemeColors) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, Modifier.weight(1f), color = c.inkDim, fontSize = 13.sp)
        if (v.isNotEmpty()) Text(v, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, c: ThemeColors) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) c.accent else c.inkDim))
        Text(label, Modifier.padding(start = 10.dp).weight(1f), color = c.ink, fontSize = 13.sp)
        Text(if (ok) "On" else "Off", color = if (ok) c.accent else c.inkDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Link(url: String, c: ThemeColors, onClick: () -> Unit) {
    Text(url.ifBlank { "Not declared." }, Modifier.clickable(enabled = url.isNotBlank(), role = Role.Button, onClick = onClick),
        color = c.accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
}

@Composable
private fun ActionButton(label: String, c: ThemeColors, tag: String, onClick: () -> Unit) {
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

private fun specialAccess(ctx: Context): List<Pair<String, Boolean>> {
    val req = requested(ctx)
    val out = mutableListOf<Pair<String, Boolean>>()
    if ("android.permission.SYSTEM_ALERT_WINDOW" in req) out += "Display over other apps" to Settings.canDrawOverlays(ctx)
    if ("android.permission.MANAGE_EXTERNAL_STORAGE" in req && Build.VERSION.SDK_INT >= 30) out += "All-files access" to Environment.isExternalStorageManager()
    if ("android.permission.REQUEST_INSTALL_PACKAGES" in req) out += "Install other apps" to ctx.packageManager.canRequestPackageInstalls()
    return out
}

private fun appDetails(ctx: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))

private fun open(ctx: Context, url: String) {
    if (url.isNotBlank()) open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private fun open(ctx: Context, intent: Intent) {
    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Opens a fleet app; when it is not installed, the fleet's release page (where Cloud Store itself installs from). */
private fun launchApp(ctx: Context, pkg: String) {
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
