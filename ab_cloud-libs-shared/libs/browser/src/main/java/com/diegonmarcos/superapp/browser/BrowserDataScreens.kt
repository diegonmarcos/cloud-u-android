package com.diegonmarcos.superapp.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitConfirmDialog
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** The storage breakdown the screen shows; null [items] = still measuring. [autofill] = category 3, measured apart. */
class StorageState {
    var items by mutableStateOf<List<SiteData.Item>?>(null)
    var autofill by mutableStateOf<BrowserAutofillData.Summary?>(null)
    var host by mutableStateOf("")
}

/** The running site save the panel shows. */
class SaveState {
    var progress by mutableStateOf<SaveProgress?>(null)
    var title by mutableStateOf("")
}

/**
 * Configs ▸ Data & storage ▸ Storage & cookies: every item the browser keeps, its size, a box each,
 * Clear selected and Clear everything — both behind a confirm that names what goes.
 */
@Composable
fun BrowserStorageScreen(state: StorageState, onClear: (Set<String>) -> Unit, onClose: () -> Unit,
                         onForgetAutofill: () -> Unit = {}, onOpenAccount: () -> Unit = {}) {
    val p = LocalKitPalette.current
    val items = state.items
    var picked by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf<Set<String>?>(null) }
    Column(Modifier.fillMaxSize().background(p.surface).verticalScroll(rememberScrollState()).padding(12.dp).testTag("browser:storage")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("← Back") }
            Text("Storage & cookies", color = p.textPrimary, style = MaterialTheme.typography.titleLarge)
        }
        if (items == null) Text("Measuring…", color = p.textSecondary, modifier = Modifier.padding(16.dp))
        items?.forEach { it ->
            Row(Modifier.fillMaxWidth().clickable { picked = if (it.id in picked) picked - it.id else picked + it.id }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(it.id in picked, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp).testTag("browser:storage:${it.id}"))
                Column(Modifier.weight(1f)) {
                    Text(it.label, color = p.textPrimary, style = MaterialTheme.typography.bodyLarge)
                    if (it.detail.isNotBlank()) Text(it.detail, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Text(it.bytes?.let(SiteData::human) ?: "—", color = p.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider(color = p.hairline)
        }
        if (items != null) {
            Text("Total ${SiteData.human(SiteData.total(items))}", color = p.textPrimary, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp).testTag("browser:storage:total"))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton({ confirm = picked }, enabled = picked.isNotEmpty(), modifier = Modifier.testTag("browser:storage:clear")) { Text("Clear selected") }
                TextButton({ confirm = SiteData.everything(items) }, modifier = Modifier.testTag("browser:storage:clear-all")) { Text("Clear everything") }
            }
        }
        BrowserAutofillDataSection(state.autofill, state.host, onForgetAutofill, onOpenAccount)
        confirm?.let { ids ->
            val list = items.orEmpty().filter { it.id in ids }
            val all = items != null && ids == SiteData.everything(items)
            KitConfirmDialog(
                title = if (all) "Clear everything?" else "Clear ${list.size} item(s)?",
                text = list.joinToString("\n") { "• ${it.label} (${it.bytes?.let(SiteData::human) ?: "—"})" } +
                    "\n\nThis cannot be undone." + (if ("cookies" in ids) " You will be signed out of every site." else "") +
                    "\nAutofill data (profiles, site rules, snippets in Cloud Account) is not touched.",
                confirmLabel = "Clear", dismissLabel = "Cancel",
                onConfirm = { confirm = null; onClear(ids); picked = emptySet() },
                onDismiss = { confirm = null },
            )
        }
    }
}

/** Configs ▸ Data & storage ▸ Offline copies: each copy with its size and date, open, delete one, delete all. */
@Composable
fun BrowserOfflineScreen(sites: List<OfflineSite>, onOpen: (OfflineSite) -> Unit, onDelete: (OfflineSite) -> Unit, onDeleteAll: () -> Unit, onClose: () -> Unit) {
    val p = LocalKitPalette.current
    var confirmAll by remember { mutableStateOf(false) }
    val date = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
    Column(Modifier.fillMaxSize().background(p.surface).verticalScroll(rememberScrollState()).padding(12.dp).testTag("browser:offline")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("← Back") }
            Text("Offline copies", color = p.textPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (sites.isNotEmpty()) TextButton({ confirmAll = true }) { Text("Delete all") }
        }
        if (sites.isEmpty()) Text("Nothing saved yet. Page menu ▸ Save this page / Save this site offline.", color = p.textSecondary, modifier = Modifier.padding(16.dp))
        sites.forEach { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { onOpen(s) }.padding(vertical = 8.dp).testTag("browser:offline:open")) {
                    Text(s.title.ifBlank { s.origin }, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text("${s.origin} · ${s.pages.size} page(s) · ${SiteData.human(s.bytes)} · ${date.format(java.util.Date(s.ts))}",
                        color = p.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    if (s.stopped.isNotBlank()) Text("Stopped: ${s.stopped}", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                TextButton({ onDelete(s) }, modifier = Modifier.testTag("browser:offline:delete")) { Text("✕") }
            }
            HorizontalDivider(color = p.hairline)
        }
        if (sites.isNotEmpty()) Text("Total ${SiteData.human(sites.sumOf { it.bytes })}", color = p.textSecondary, modifier = Modifier.padding(top = 8.dp))
        if (confirmAll) KitConfirmDialog("Delete every offline copy?", "${sites.size} copies, ${SiteData.human(sites.sumOf { it.bytes })}. This cannot be undone.",
            "Delete all", "Cancel", onConfirm = { confirmAll = false; onDeleteAll() }, onDismiss = { confirmAll = false })
    }
}

/** The panel over the page while a site is being saved: progress against the limits, and Stop. */
@Composable
fun BrowserSavePanel(state: SaveState, onStop: () -> Unit, onClose: () -> Unit) {
    val p = LocalKitPalette.current
    val pr = state.progress
    Column(Modifier.fillMaxWidth().background(p.surface).padding(16.dp).testTag("browser:save")) {
        Text(if (pr?.done == true) "Saved" else "Saving ${state.title}…", color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
        if (pr != null) {
            Text("${pr.pages} of at most ${pr.limits.maxPages} pages · ${SiteData.human(pr.bytes)} of ${SiteData.human(pr.limits.maxBytes)} · ${pr.pending} waiting",
                color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            if (pr.current.isNotBlank()) Text(pr.current, color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            if (pr.done && pr.stopped.isNotBlank()) Text("Stopped: ${pr.stopped}", color = p.accent, style = MaterialTheme.typography.bodySmall)
            if (pr.error.isNotBlank()) Text(pr.error, color = p.accent)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (pr?.done == true) TextButton(onClose) { Text("Close") } else TextButton(onStop) { Text("Stop") }
        }
    }
}

/**
 * Category 3, its OWN section (a0_docs/eng-specs/autofill-3-tier.md §3.1): autofill data is not cookies and
 * not site storage, so no box above can clear it. The SOT lives in Cloud Account — edited there, kept by
 * Android's "Clear storage" of this app; only the browser-local leftovers can be forgotten here, behind
 * their own confirm.
 */
@Composable
fun BrowserAutofillDataSection(s: BrowserAutofillData.Summary?, host: String, onForget: () -> Unit, onOpenAccount: () -> Unit) {
    val p = LocalKitPalette.current
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("browser:storage:autofill")) {
        Text("Autofill data (this site / all sites)", color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
        Text("Not cookies, not site storage: nothing above clears it. Profiles, addresses, site rules and snippets live in Cloud Account.",
            color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        if (s == null) Text("Measuring…", color = p.textSecondary)
        else {
            if (!s.accountInstalled) Text("Cloud Account is not installed: no autofill profiles to fill from.", color = p.textSecondary, style = MaterialTheme.typography.bodyMedium)
            else {
                if (host.isNotBlank()) Text("This site ($host): ${s.siteRules} rule(s)", color = p.textPrimary, style = MaterialTheme.typography.bodyMedium)
                Text("All sites: ${s.profiles} profile(s), ${s.addresses} address(es), ${s.rules} rule(s), ${s.snippets} snippet(s) — in Cloud Account, kept when this browser's storage is cleared",
                    color = p.textPrimary, style = MaterialTheme.typography.bodyMedium)
            }
            Text(if (s.localBytes > 0) "Browser-local: an older imported profile (${SiteData.human(s.localBytes)}). It stays on this phone only, and Android's \"Clear storage\" of Cloud Browser deletes it."
                else "Browser-local: nothing (only a short in-memory copy while a page is open).", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                if (s.accountInstalled) TextButton(onOpenAccount, modifier = Modifier.testTag("browser:storage:autofill:account")) { Text("Edit in Cloud Account") }
                TextButton({ confirm = true }, modifier = Modifier.testTag("browser:storage:autofill:forget")) { Text("Forget browser-local") }
            }
        }
        if (confirm) KitConfirmDialog("Forget browser-local autofill data?",
            "The older imported profile on this phone and the in-memory copy go. Cookies, site storage and everything in Cloud Account stay.",
            "Forget", "Cancel", onConfirm = { confirm = false; onForget() }, onDismiss = { confirm = false })
    }
}

/** Menu ▸ Clear site data: this site's cookies and storage, each its own box; autofill data is not one of them. */
@Composable
fun BrowserClearSiteScreen(host: String, onClear: (Set<String>) -> Unit, onClose: () -> Unit) {
    val p = LocalKitPalette.current
    var picked by remember { mutableStateOf(BrowserClearCategories.defaults(BrowserClearCategories.SITE_BOXES.map { it.first })) }
    Column(Modifier.fillMaxWidth().background(p.surface).padding(12.dp).testTag("browser:clear-site")) {
        Text("Clear site data — $host", color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
        BrowserClearCategories.SITE_BOXES.forEach { (id, label) ->
            Row(Modifier.fillMaxWidth().clickable { picked = if (id in picked) picked - id else picked + id }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(id in picked, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp).testTag("browser:clear-site:$id"))
                Text(label, color = p.textPrimary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text("Other sites keep everything. Autofill data for this site (Cloud Account rules and profiles) is not cleared here: Configs ▸ Data & storage ▸ Autofill data.",
            color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClose) { Text("Cancel") }
            TextButton({ onClear(picked) }, enabled = picked.isNotEmpty(), modifier = Modifier.testTag("browser:clear-site:go")) { Text("Clear") }
        }
    }
}
