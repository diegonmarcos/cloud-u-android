package com.diegonmarcos.superapp.fleetconfigs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.diegonmarcos.superapp.themebg.ThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Configs > About: the app in full. Hand-written per app in build.json::configs_pages.about (tagline, description,
 * features, screens, guide, privacy, backends, related, credits); everything else is derived at build time by
 * fleet-configs.gradle (permissions from build.json::perms and the libs:perms catalogue, data stores from the fleet-config
 * manifest, crash / analytics libs from the module graph, licence and origin from licenses/curated.json, mesh URLs, repo,
 * crash sink, debug API, build sha and date) or read at runtime (icon, installed version, changelog from the release).
 * configs-pages.json::about_min is the minimum every app's declaration is held to.
 */
@Composable
public fun FleetAboutPage(decl: FleetConfigsDecl, c: ThemeColors) {
    val ctx = LocalContext.current
    val a = decl.about
    var releases by remember { mutableStateOf(FleetReleases.cached()) }
    LaunchedEffect(decl.app) { if (releases == null) releases = withContext(Dispatchers.IO) { FleetReleases.load(decl) } }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val icon = remember(ctx) { runCatching { ctx.packageManager.getApplicationIcon(ctx.packageName).toBitmap(192, 192).asImageBitmap() }.getOrNull() }
        val label = decl.name.ifBlank { ctx.applicationInfo.loadLabel(ctx.packageManager).toString() }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp).testTag(FleetConfigsTags.about("header")), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Image(icon, contentDescription = label, modifier = Modifier.size(76.dp).clip(RoundedCornerShape(20.dp)))
            Column(Modifier.padding(start = 14.dp)) {
                Text(label, color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(FleetConfigsTags.about("name")))
                if (a.tagline.isNotBlank()) Text(a.tagline, color = c.ink, fontSize = 14.sp, lineHeight = 18.sp, modifier = Modifier.testTag(FleetConfigsTags.about("tagline")))
                Text("Version ${installedVersion(ctx, decl)}  ·  ${decl.sha.ifBlank { "local" }}", color = c.inkDim, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.testTag(FleetConfigsTags.about("version")))
            }
        }
        SectionCard("What it does", Icons.Filled.Info, c, Modifier.testTag(FleetConfigsTags.about("what"))) { Body(a.description, c) }
        SectionCard("Features", Icons.Filled.Star, c, Modifier.testTag(FleetConfigsTags.about("features"))) {
            a.features.forEach { f -> Bullet(f, c) }
        }
        SectionCard("Screens", Icons.AutoMirrored.Filled.ViewQuilt, c, Modifier.testTag(FleetConfigsTags.about("screens"))) {
            a.screens.forEach { s -> Term(s.name, s.what, c) }
        }
        SectionCard("How to use it", Icons.AutoMirrored.Filled.MenuBook, c, Modifier.testTag(FleetConfigsTags.about("guide"))) {
            a.guide.forEachIndexed { i, g -> Row { Text("${i + 1}.  ", color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold); Body(g, c) } }
        }
        SectionCard("Data and privacy", Icons.Filled.Lock, c, Modifier.testTag(FleetConfigsTags.about("privacy"))) {
            Body(a.privacy, c)
            if (a.stores.isNotEmpty()) {
                Sub("What it stores on the phone", c)
                a.stores.forEach { st -> Term(st.id + if (st.cls.isNotBlank()) "  ·  ${st.cls}" else "", st.doc + if (st.path.isNotBlank()) "\n${st.path}" else "", c) }
            }
            Sub("Backups", c)
            Body(BACKUP_TEXT, c)
            Sub("Crash and analytics libraries", c)
            if (a.diagnostics.isEmpty()) Body("None linked.", c) else a.diagnostics.forEach { Bullet(it, c) }
        }
        SectionCard("Permissions", Icons.Filled.Security, c, Modifier.testTag(FleetConfigsTags.about("perms"))) {
            if (a.perms.isEmpty()) Body("This app asks for no runtime or special permission.", c)
            a.perms.forEach { p ->
                Term(p.label + (if (p.helper.isNotBlank()) "  ·  held by ${p.helper}" else "") + "  ·  ${p.kind}", p.why.ifBlank { "Declared in build.json::perms." }, c)
            }
            Text("Grant or revoke them in Configs ▸ General ▸ Perms.", color = c.inkDim, fontSize = 11.sp)
        }
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
        SectionCard("Related fleet apps", Icons.Filled.Hub, c, Modifier.testTag(FleetConfigsTags.about("related"))) {
            a.related.forEach { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name.ifBlank { r.app }, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(r.why, color = c.inkDim, fontSize = 12.sp)
                    }
                    if (r.pkg.isNotBlank()) ActionButton("Open", c, "fleetcfg_related_${r.app}") { launchApp(ctx, r.pkg) }
                }
            }
        }
        SectionCard("Build", Icons.Filled.Build, c, Modifier.testTag(FleetConfigsTags.about("build"))) {
            KeyValue("Version", installedVersion(ctx, decl), c)
            KeyValue("Build sha", decl.sha.ifBlank { "local" }, c)
            KeyValue("Build date", decl.buildDate.ifBlank { "unknown" }, c)
            KeyValue("Package", ctx.packageName, c)
            val pi = remember(ctx) { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull() }
            if (pi != null) KeyValue("Installed", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(pi.lastUpdateTime)), c)
        }
        SectionCard("Source and licence", Icons.Filled.Code, c, Modifier.testTag(FleetConfigsTags.about("repo"))) {
            Link(a.repo, c) { open(ctx, a.repo) }
            KeyValue("Licence", FleetConfigs.licenceLabel(a.license), c)
            if (a.origin.isNotBlank()) KeyValue("Origin", a.origin, c)
            if (a.upstream.isNotBlank()) KeyValue("Upstream", a.upstream, c)
            if (a.credits.isNotBlank()) { Sub("Credits", c); Body(a.credits, c) }
        }
        SectionCard("Changelog", Icons.Filled.History, c, Modifier.testTag(FleetConfigsTags.about("changelog"))) {
            val r = releases
            when {
                r == null -> Text("Loading the release notes…", color = c.inkDim, fontSize = 12.sp)
                r.notes.isEmpty() -> Text(r.error.ifBlank { "No release notes found." }, color = c.inkDim, fontSize = 12.sp)
                else -> ReleaseNotes(r.notes.take(8), c)
            }
        }
        SectionCard("Support and debug", Icons.Filled.BugReport, c, Modifier.testTag(FleetConfigsTags.about("debug"))) {
            KeyValue("Report a problem", "", c)
            Link(a.support, c) { open(ctx, a.support) }
            KeyValue("Crash reports (on this phone)", "", c)
            Link(a.crash, c) { open(ctx, a.crash) }
            KeyValue("Debug API (on this phone)", "", c)
            Link(a.debug, c) { open(ctx, a.debug) }
            ActionButton("App info", c, "fleetcfg_about_appinfo") { com.diegonmarcos.superapp.perms.PermsPages.openAppInfo(ctx, ctx.packageName) }
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

private const val BACKUP_TEXT: String =
    "The app's settings and state are part of the phone profile Cloud Account backs up to, and restores from, the owner's " +
        "private vault repository (the fleet-config manifest says which stores move to a new phone). Secrets stay in the " +
        "Android Keystore and are never exported in clear."

@Composable
private fun Bullet(text: String, c: ThemeColors) {
    Row {
        Box(Modifier.padding(top = 7.dp, end = 10.dp).size(5.dp).clip(CircleShape).background(c.accent))
        Text(text, color = c.ink, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun Term(term: String, def: String, c: ThemeColors) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(term, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (def.isNotBlank()) Text(def, color = c.inkDim, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun Sub(text: String, c: ThemeColors) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Spacer(Modifier.width(0.dp))
        Text(text, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
