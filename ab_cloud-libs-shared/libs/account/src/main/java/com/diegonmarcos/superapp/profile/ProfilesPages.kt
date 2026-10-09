package com.diegonmarcos.superapp.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitConfirmDialog
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Cloud Account redesign task 4: Profiles ▸ devices / working / diff (spec 4.3-4.5). The Store's
 * dense row language (a state line + one button each, [StorePhoneFragment]) — a row never hides
 * its own state behind a tap, and Delete never fires without [KitConfirmDialog].
 */
object ProfilesPageTags {
    const val DEVICES = "profiles:devices"
    const val WORKING = "profiles:working"
    const val DIFF = "profiles:diff"
    const val FETCH = "profiles:devices:fetch"
    const val BACKUP_NOW = "profiles:devices:backup_now"
    fun load(id: String) = "profiles:devices:load:$id"
    fun backupHere(id: String) = "profiles:devices:backup_here:$id"
    fun setDefault(id: String) = "profiles:devices:set_default:$id"
    fun delete(id: String) = "profiles:devices:delete:$id"
    const val SAVE = "profiles:working:save"
    fun edit(path: String) = "profiles:working:edit:$path"
    const val APPLY_ALL = "profiles:diff:apply_all"
    const val CAPTURE_ALL = "profiles:diff:capture_all"
    fun appApply(app: String) = "profiles:diff:apply:$app"
    fun appCapture(app: String) = "profiles:diff:capture:$app"
    fun rowApply(path: String) = "profiles:diff:apply:item:$path"
    fun rowCapture(path: String) = "profiles:diff:capture:item:$path"
}

@Composable
private fun Dense(text: String, secondary: Boolean = false, tag: String? = null) {
    val p = LocalKitPalette.current
    Text(text, color = if (secondary) p.textSecondary else p.textPrimary, fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
}

@Composable
private fun RowButton(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) { Text(label) }
}

// ── Profiles ▸ devices (spec 4.3) ───────────────────────────────────────

@Composable
fun ProfilesDevicesPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val vault = remember { DeviceVault(ctx) }
    val thisPhone = remember(tick) { AccountDevice.id(ctx) }
    val listing = remember(tick) { vault.devices() }
    val declared = remember(tick) { AccountDevice.declared(ctx).map { it.id } }

    fun io(block: () -> String) {
        if (busy) return
        busy = true; result = "…"
        scope.launch { result = withContext(Dispatchers.IO) { block() }; busy = false; tick++ }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(ProfilesPageTags.DEVICES),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KitSectionHeader("Profiles ▸ devices", "each file under devices/ in the vault, and DEFAULT")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RowButton(if (busy) "…" else "Fetch", ProfilesPageTags.FETCH, !busy) { io { vault.devices().optString("result").ifBlank { "✓ fetched" } } }
            RowButton(if (busy) "…" else "Backup now", ProfilesPageTags.BACKUP_NOW, !busy) { io { vault.backup(thisPhone, false).optString("result") } }
        }
        if (result.isNotBlank()) Dense(result)
        if (!listing.optBoolean("ok", false)) Dense(listing.optString("result", "✗ no listing"), true)
        val files = listing.optJSONArray("devices")
        val byId = mutableMapOf<String, JSONObject>()
        if (files != null) for (i in 0 until files.length()) files.optJSONObject(i)?.let { byId[it.optString("id")] = it }

        // Every declared device, file or not, then DEFAULT.
        for (id in declared) {
            val row = byId[id]
            KitCard {
                if (row == null) {
                    Dense("$id · not captured", true)
                    RowButton("Backup here", ProfilesPageTags.backupHere(id), !busy) { io { vault.backup(id, false).optString("result") } }
                } else {
                    val apps = row.optJSONObject("apps")?.optInt("apps") ?: 0
                    val settings = row.optInt("settings", -1)
                    val mine = id == thisPhone
                    Dense("$id${if (mine) " · this phone" else ""} · $apps apps · captured ${row.optString("captured_at").ifBlank { "—" }}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RowButton("Load", ProfilesPageTags.load(id), !busy) { io { vault.load(id).optString("result") } }
                        if (mine) RowButton("Backup here", ProfilesPageTags.backupHere(id), !busy) { io { vault.backup(id, false).optString("result") } }
                        RowButton("Set as DEFAULT", ProfilesPageTags.setDefault(id), !busy) { io { vault.setDefault(id).optString("result") } }
                        RowButton("Delete", ProfilesPageTags.delete(id), !busy) { confirmDelete = id }
                    }
                }
            }
        }
        KitCard {
            Dense("DEFAULT · ${byId["DEFAULT"]?.optString("captured_at")?.ifBlank { null } ?: "not captured"}")
            RowButton("Load", ProfilesPageTags.load("DEFAULT"), !busy) { io { vault.load("DEFAULT").optString("result") } }
        }
    }

    confirmDelete?.let { id ->
        KitConfirmDialog(
            title = "Delete $id?",
            text = "devices/$id.json is removed from the vault. This cannot be undone from this page.",
            confirmLabel = "Delete",
            dismissLabel = "Cancel",
            onConfirm = { confirmDelete = null; io { vault.delete(id).optString("result") } },
            onDismiss = { confirmDelete = null },
        )
    }
}

// ── Profiles ▸ working (spec 4.4) ───────────────────────────────────────

/** The working profile's settings, grouped as the InfoMask schema's topics where they match an
 *  app id under `settings`, then every other app under `settings` (#4.4 "settings (per app)"). */
private fun workingTopics(profile: JSONObject?): List<Pair<String, JSONObject?>> {
    val settings = profile?.optJSONObject("settings")
    val schemaIds = InfoMask.schema.map { it.id }.filter { settings?.has(it) == true }
    val rest = settings?.keys()?.asSequence()?.filter { !it.startsWith("_") && it !in schemaIds }.orEmpty().toList()
    val out = mutableListOf<Pair<String, JSONObject?>>()
    for (id in schemaIds) out += id to settings?.optJSONObject(id)
    for (id in rest) out += id to settings?.optJSONObject(id)
    out += "apps" to profile?.optJSONObject("apps")
    out += "perms" to profile?.optJSONObject("perms")
    out += "system" to profile?.optJSONObject("system")
    return out
}

@Composable
fun ProfilesWorkingPage() {
    val ctx = LocalContext.current
    val vault = remember { DeviceVault(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf("") }
    val working = remember(tick) { vault.working() }
    val device = working?.optString("device").orEmpty()
    val profile = working?.optJSONObject("profile")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(ProfilesPageTags.WORKING),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KitSectionHeader("Profiles ▸ working", if (device.isBlank()) "nothing loaded — Profiles ▸ devices ▸ Load" else "$device · every declared field, masked where InfoMask says so")
        if (result.isNotBlank()) Dense(result)
        if (profile != null) {
            for ((topic, body) in workingTopics(profile)) {
                KitCard {
                    Text(topic, color = LocalKitPalette.current.textPrimary, style = MaterialTheme.typography.titleMedium)
                    val leaves = AccountDrift.leaves(body)
                    if (leaves.isEmpty()) Dense("empty", true)
                    for ((key, value) in leaves) {
                        val full = "settings.$topic.$key"
                        val text = AccountDrift.text(value)
                        val masked = InfoMask.declared.hides(full, text)
                        val editable = !masked
                        Column(Modifier.fillMaxWidth()
                            .then(if (editable) Modifier.clickable { editing = full }.testTag(ProfilesPageTags.edit(full)) else Modifier)) {
                            Dense(key, true)
                            Dense(if (masked) "•••• (${text.length})" else text)
                        }
                    }
                }
            }
        }
    }

    editing?.let { path ->
        var value by remember(path) { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(path) },
            text = { OutlinedTextField(value, { value = it }) },
            confirmButton = {
                androidx.compose.material3.TextButton(enabled = !InfoMask.declared.hides(path, value), modifier = Modifier.testTag(ProfilesPageTags.SAVE), onClick = {
                    val w = vault.working()
                    val p = w?.optJSONObject("profile")
                    if (p != null && device.isNotBlank()) {
                        AccountDrift.put(p, path, value)
                        val (apps, keys) = DeviceProfile.counts(p)
                        val target = vault.decl.devicePath(device)
                        val c = vault.client()
                        val topic = path.removePrefix("settings.").substringBefore(".")
                        if (c == null) result = "✗ no usable forge declared"
                        else {
                            val cur = c.get(target, vault.decl.branch)
                            val sha = (cur as? ForgeClient.Result.Ok)?.value?.sha
                            when (val put = c.put(target, DeviceProfile.text(p), sha, "account($device): working ▸ $topic — 1 key changed", vault.decl.branch)) {
                                is ForgeClient.Result.Ok -> {
                                    com.diegonmarcos.superapp.settings.ConfigsPrefs(ctx).putText(DeviceVault.K_WORKING,
                                        JSONObject().put("device", device).put("sha", put.value).put("profile", p).toString())
                                    result = "✓ $device · $topic · 1 key changed"
                                }
                                is ForgeClient.Result.Failed -> result = "✗ ${put.reason}"
                            }
                        }
                    }
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

// ── Profiles ▸ diff (spec 4.5) ───────────────────────────────────────────

@Composable
fun ProfilesDiffPage(model: AccountModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    val vault = remember { DeviceVault(ctx) }
    val working = remember(tick) { vault.working() }
    val fileLeaves = remember(tick) { AccountDrift.leaves(working?.optJSONObject("profile")?.optJSONObject("settings")) }
    model.version.intValue
    val phoneLeaves = remember(model.version.intValue, tick) { AccountDrift.leaves(model.runtime()?.body) }
    val fields = remember(fileLeaves, phoneLeaves) { AccountDrift.diff(fileLeaves, phoneLeaves, model.apps) }
    val labels = model.apps.associate { it.id to it.label }

    fun io(block: () -> String) {
        if (busy) return
        busy = true; result = "…"
        scope.launch { result = withContext(Dispatchers.IO) { block() }; busy = false; tick++ }
    }

    /** → phone: push one file value onto the runtime via the app that owns it. */
    fun applyToPhone(paths: Collection<String>): String {
        val plan = AccountDrift.pushPlan(fileLeaves, phoneLeaves.keys, paths, model.apps)
        if (plan.isEmpty()) return "✗ nothing to apply (not observed, or the file holds nothing)"
        return model.pushServerToRuntime(paths.toList())
    }

    /** ← file: capture the runtime's value back into the working file. */
    fun captureFromPhone(paths: Collection<String>): String {
        val w = vault.working() ?: return "✗ nothing loaded — Profiles ▸ devices ▸ Load"
        val device = w.optString("device")
        val p = w.optJSONObject("profile") ?: return "✗ malformed working profile"
        val settings = p.optJSONObject("settings") ?: JSONObject().also { p.put("settings", it) }
        val written = AccountDrift.runtimeToDeclared(settings, phoneLeaves, phoneLeaves.keys, emptySet(), paths)
        p.put("settings", written.body)
        val target = vault.decl.devicePath(device)
        val c = vault.client() ?: return "✗ no usable forge declared"
        val cur = c.get(target, vault.decl.branch)
        val sha = (cur as? ForgeClient.Result.Ok)?.value?.sha
        return when (val put = c.put(target, DeviceProfile.text(p), sha, "account($device): captured ${written.written.size} key(s) from this phone", vault.decl.branch)) {
            is ForgeClient.Result.Ok -> {
                com.diegonmarcos.superapp.settings.ConfigsPrefs(ctx).putText(DeviceVault.K_WORKING,
                    JSONObject().put("device", device).put("sha", put.value).put("profile", p).toString())
                "✓ $device · ${written.written.size} key(s) captured"
            }
            is ForgeClient.Result.Failed -> "✗ ${put.reason}"
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(ProfilesPageTags.DIFF),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KitSectionHeader("Profiles ▸ diff", "working file ↔ this phone, per app per key")
        val drift = AccountDrift.counts(fields)
        Dense("same ${drift.same} · drift ${drift.drift} (changed ${drift.changed}, file-only ${drift.onlyA}, phone-only ${drift.onlyB})", true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RowButton("Apply all → phone", ProfilesPageTags.APPLY_ALL, !busy) { io { applyToPhone(AccountDrift.drifted(fields)) } }
            RowButton("Capture all ← file", ProfilesPageTags.CAPTURE_ALL, !busy) { io { captureFromPhone(AccountDrift.drifted(fields)) } }
        }
        if (result.isNotBlank()) Dense(result)
        for ((app, c) in AccountDrift.byApp(fields, model.apps)) {
            val drifted = fields.filter { it.app == app && it.kind != AccountDrift.Kind.SAME }
            if (drifted.isEmpty()) continue
            KitCard {
                Text(labels[app] ?: "(unowned)", color = LocalKitPalette.current.textPrimary, style = MaterialTheme.typography.titleMedium)
                Dense("${c.drift} drifted of ${c.same + c.drift}", true)
                if (app.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RowButton("Apply all → phone", ProfilesPageTags.appApply(app), !busy) { io { applyToPhone(drifted.map { it.path }) } }
                    RowButton("Capture all ← file", ProfilesPageTags.appCapture(app), !busy) { io { captureFromPhone(drifted.map { it.path }) } }
                }
                for (f in drifted) {
                    val secret = InfoMask.declared.hides("settings.${f.path}", f.a ?: f.b ?: "")
                    Dense(f.path, true)
                    Dense(if (secret) "file: •••• (presence, fingerprint) · phone: •••• (presence, fingerprint)"
                          else "file: ${f.a ?: "—"} · phone: ${f.b ?: "—"}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RowButton("→ phone", ProfilesPageTags.rowApply(f.path), !busy) { io { applyToPhone(listOf(f.path)) } }
                        RowButton("← file", ProfilesPageTags.rowCapture(f.path), !busy) { io { captureFromPhone(listOf(f.path)) } }
                    }
                }
            }
        }
    }
}

/** Drift count the Account ▸ profile tile draws (spec 4.5's pair: working file vs runtime). */
fun profilesDriftCount(ctx: android.content.Context, model: AccountModel): Int {
    val working = DeviceVault(ctx).working()?.optJSONObject("profile")?.optJSONObject("settings")
    val fileLeaves = AccountDrift.leaves(working)
    val phoneLeaves = AccountDrift.leaves(model.runtime()?.body)
    return AccountDrift.counts(AccountDrift.diff(fileLeaves, phoneLeaves, model.apps)).drift
}
