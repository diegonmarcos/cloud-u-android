package com.diegonmarcos.superapp.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.diegonmarcos.superapp.uikit.KitDates
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitDeviceCard
import com.diegonmarcos.superapp.uikit.KitFingerprint
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitSaveBar
import com.diegonmarcos.superapp.uikit.KitSettingsRow
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatePill
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.KitConfirmDialog
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Cloud Account redesign task 4: Profiles ▸ devices / working / diff (spec 4.3-4.5), drawn with the
 * ui-kit set of the visual pass (cloud-account-ui spec 1): one KitDeviceCard per device, the working
 * file as labelled rows with a save bar, the diff as one card per app. A row never hides its own
 * state behind a tap, and Delete never fires without [KitConfirmDialog].
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


// ── Profiles ▸ devices (spec 4.3) ───────────────────────────────────────

@Composable
fun ProfilesDevicesPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf(Said(KitState.IDLE, "")) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val vault = remember { DeviceVault(ctx) }
    val thisPhone = remember(tick) { AccountDevice.id(ctx) }
    val listing = remember(tick) { vault.devices() }
    val declared = remember(tick) { AccountDevice.declared(ctx).map { it.id } }

    fun io(block: () -> String) {
        if (busy) return
        busy = true; result = Said(KitState.BUSY, "working")
        scope.launch { result = said(withContext(Dispatchers.IO) { block() }); busy = false; tick++ }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(ProfilesPageTags.DEVICES),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KitActionBar(listOf(
            KitAction(if (busy) "Fetching…" else "Fetch", ProfilesPageTags.FETCH, !busy) { io { vault.devices().optString("result").ifBlank { "fetched" } } },
            KitAction("Backup now", ProfilesPageTags.BACKUP_NOW, !busy) { io { vault.backup(thisPhone, false).optString("result") } },
        ))
        SaidBanner(result, "devices:result")
        if (!listing.optBoolean("ok", false)) KitStatusBanner(said(listing.optString("result", "no listing")).text.ifBlank { "no listing" },
            KitState.BAD, tag = "devices:listing")
        val files = listing.optJSONArray("devices")
        val byId = mutableMapOf<String, JSONObject>()
        if (files != null) for (i in 0 until files.length()) files.optJSONObject(i)?.let { byId[it.optString("id")] = it }

        // DEFAULT first, then every declared device, file or not.
        val def = byId["DEFAULT"]
        KitDeviceCard(
            id = "DEFAULT", glyph = "♛", model = "the profile a new phone starts from",
            state = def?.optString("captured_at")?.ifBlank { null }?.let { "captured ${KitDates.relative(it)}" } ?: "not captured",
            pill = if (def == null) "missing" to KitState.WARN else "file" to KitState.OK,
            primary = KitAction("Load", ProfilesPageTags.load("DEFAULT"), !busy) { io { vault.load("DEFAULT").optString("result") } },
        )
        for (id in declared) {
            val row = byId[id]
            val mine = id == thisPhone
            if (row == null) {
                KitDeviceCard(
                    id = id, model = "", state = "not captured", badge = if (mine) "this phone" else "",
                    pill = "no file" to KitState.IDLE,
                    primary = KitAction("Backup here", ProfilesPageTags.backupHere(id), !busy) { io { vault.backup(id, false).optString("result") } },
                )
            } else {
                val apps = row.optJSONObject("apps")?.optInt("apps") ?: 0
                KitDeviceCard(
                    id = id, model = row.optString("model").ifBlank { "" },
                    state = "$apps apps · captured ${row.optString("captured_at").ifBlank { null }?.let { KitDates.relative(it) } ?: "—"}",
                    badge = if (mine) "this phone" else "",
                    pill = "file" to KitState.OK,
                    primary = KitAction("Load", ProfilesPageTags.load(id), !busy) { io { vault.load(id).optString("result") } },
                    secondary = if (mine) listOf(KitAction("Backup here", ProfilesPageTags.backupHere(id), !busy) { io { vault.backup(id, false).optString("result") } }) else emptyList(),
                    menu = listOf(
                        KitAction("Set as DEFAULT", ProfilesPageTags.setDefault(id), !busy) { io { vault.setDefault(id).optString("result") } },
                        KitAction("Delete", ProfilesPageTags.delete(id), !busy) { confirmDelete = id },
                    ),
                )
            }
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
    val scope = rememberCoroutineScope()
    val vault = remember { DeviceVault(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf(Said(KitState.IDLE, "")) }
    var saving by remember { mutableStateOf(false) }
    /** Unsaved edits, path → value: the save bar commits them as one write of the device file. */
    val pending = remember { mutableStateMapOf<String, String>() }
    val working = remember(tick) { vault.working() }
    val device = working?.optString("device").orEmpty()
    val profile = working?.optJSONObject("profile")

    /** One PUT of the device file with every pending edit; the working copy follows the forge's answer. */
    fun save(edits: Map<String, String>): Said {
        val w = vault.working()
        val p = w?.optJSONObject("profile")
        if (p == null || device.isBlank()) return Said(KitState.BAD, "nothing loaded — Profiles ▸ devices ▸ Load")
        for ((path, value) in edits) AccountDrift.put(p, path, value)
        val target = vault.decl.devicePath(device)
        val c = vault.client() ?: return Said(KitState.BAD, "no usable forge declared")
        val topics = edits.keys.map { it.removePrefix("settings.").substringBefore(".") }.distinct().joinToString(", ")
        val cur = c.get(target, vault.decl.branch)
        val sha = (cur as? ForgeClient.Result.Ok)?.value?.sha
        return when (val put = c.put(target, DeviceProfile.text(p), sha, "account($device): working ▸ $topics — ${edits.size} key(s) changed", vault.decl.branch)) {
            is ForgeClient.Result.Ok -> {
                com.diegonmarcos.superapp.settings.ConfigsPrefs(ctx).putText(DeviceVault.K_WORKING,
                    JSONObject().put("device", device).put("sha", put.value).put("profile", p).toString())
                Said(KitState.OK, "$device · $topics · ${edits.size} key(s) changed")
            }
            is ForgeClient.Result.Failed -> Said(KitState.BAD, put.reason)
        }
    }

    Column(Modifier.fillMaxSize().testTag(ProfilesPageTags.WORKING)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (device.isBlank()) KitStatusBanner("Nothing loaded: Profiles ▸ devices ▸ Load", KitState.WARN, tag = "working")
            else KitStatusBanner("$device · every declared field, masked where InfoMask says so", KitState.OK, tag = "working")
            SaidBanner(result, "working:result")
            if (profile != null) {
                for ((topic, body) in workingTopics(profile)) {
                    KitSectionHeader(InfoMask.schema.firstOrNull { it.id == topic }?.label ?: fieldLabel(topic), "", eyebrow = true)
                    KitCard {
                        val leaves = AccountDrift.leaves(body)
                        if (leaves.isEmpty()) KitSettingsRow("Nothing captured yet")
                        for ((key, value) in leaves) {
                            val full = "settings.$topic.$key"
                            val text = pending[full] ?: AccountDrift.text(value)
                            val masked = InfoMask.declared.hides(full, text)
                            val editable = !masked
                            KitListRow(fieldLabel(key), tag = full,
                                modifier = if (editable) Modifier.testTag(ProfilesPageTags.edit(full)) else Modifier,
                                pill = if (full in pending) "unsaved" to KitState.WARN else null,
                                onClick = if (editable) ({ editing = full }) else null,
                                onLongClick = { copyPath(ctx, full) }) {
                                if (masked) KitFingerprint(fingerprint(text), tag = full)
                                else Text(text, color = LocalKitPalette.current.textSecondary, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                            }
                        }
                    }
                }
            }
        }
        if (pending.isNotEmpty()) KitSaveBar("${pending.size} change${if (pending.size == 1) "" else "s"}", saving = saving,
            onSave = {
                val edits = pending.toMap()
                saving = true
                scope.launch {
                    result = withContext(Dispatchers.IO) { runCatching { save(edits) }.getOrElse { Said(KitState.BAD, it.javaClass.simpleName) } }
                    if (result.state == KitState.OK) pending.clear()
                    saving = false; tick++
                }
            },
            onDiscard = { pending.clear() })
    }

    editing?.let { path ->
        var value by remember(path) { mutableStateOf(pending[path].orEmpty()) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(fieldLabel(path.removePrefix("settings.").substringAfter("."))) },
            text = { OutlinedTextField(value, { value = it }) },
            confirmButton = {
                TextButton(enabled = !InfoMask.declared.hides(path, value), modifier = Modifier.testTag(ProfilesPageTags.SAVE), onClick = {
                    pending[path] = value
                    editing = null
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

// ── Profiles ▸ diff (spec 4.5) ───────────────────────────────────────────

@Composable
fun ProfilesDiffPage(model: AccountModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf(Said(KitState.IDLE, "")) }
    var tick by remember { mutableIntStateOf(0) }
    val vault = remember { DeviceVault(ctx) }
    val working = remember(tick) { vault.working() }
    val fileLeaves = remember(tick) { AccountDrift.leaves(working?.optJSONObject("profile")?.optJSONObject("settings")) }
    model.version.intValue
    val phoneLeaves = remember(model.version.intValue, tick) { AccountDrift.leaves(model.runtime()?.body) }
    val fields = remember(fileLeaves, phoneLeaves) { AccountDrift.diff(fileLeaves, phoneLeaves, model.apps) }
    val labels = model.apps.associate { it.id to it.label }
    val p = LocalKitPalette.current

    fun io(block: () -> Said) {
        if (busy) return
        busy = true; result = Said(KitState.BUSY, "working")
        scope.launch { result = withContext(Dispatchers.IO) { block() }; busy = false; tick++ }
    }

    /** → phone: push one file value onto the runtime via the app that owns it. */
    fun applyToPhone(paths: Collection<String>): Said {
        val plan = AccountDrift.pushPlan(fileLeaves, phoneLeaves.keys, paths, model.apps)
        if (plan.isEmpty()) return Said(KitState.BAD, "nothing to apply (not observed, or the file holds nothing)")
        return said(model.pushServerToRuntime(paths.toList()))
    }

    /** ← file: capture the runtime's value back into the working file. */
    fun captureFromPhone(paths: Collection<String>): Said {
        val w = vault.working() ?: return Said(KitState.BAD, "nothing loaded — Profiles ▸ devices ▸ Load")
        val device = w.optString("device")
        val pr = w.optJSONObject("profile") ?: return Said(KitState.BAD, "malformed working profile")
        val settings = pr.optJSONObject("settings") ?: JSONObject().also { pr.put("settings", it) }
        val written = AccountDrift.runtimeToDeclared(settings, phoneLeaves, phoneLeaves.keys, emptySet(), paths)
        pr.put("settings", written.body)
        val target = vault.decl.devicePath(device)
        val c = vault.client() ?: return Said(KitState.BAD, "no usable forge declared")
        val cur = c.get(target, vault.decl.branch)
        val sha = (cur as? ForgeClient.Result.Ok)?.value?.sha
        return when (val put = c.put(target, DeviceProfile.text(pr), sha, "account($device): captured ${written.written.size} key(s) from this phone", vault.decl.branch)) {
            is ForgeClient.Result.Ok -> {
                com.diegonmarcos.superapp.settings.ConfigsPrefs(ctx).putText(DeviceVault.K_WORKING,
                    JSONObject().put("device", device).put("sha", put.value).put("profile", pr).toString())
                Said(KitState.OK, "$device · ${written.written.size} key(s) captured")
            }
            is ForgeClient.Result.Failed -> Said(KitState.BAD, put.reason)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(ProfilesPageTags.DIFF),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val drift = AccountDrift.counts(fields)
        val byApp = AccountDrift.byApp(fields, model.apps)
        val driftedApps = byApp.count { (_, c) -> c.drift > 0 }
        if (drift.drift == 0) KitStatusBanner("In sync · ${drift.same} keys match", KitState.OK, tag = "diff")
        else KitStatusBanner("${drift.drift} keys differ in $driftedApps app${if (driftedApps == 1) "" else "s"} · changed ${drift.changed}, file only ${drift.onlyA}, phone only ${drift.onlyB}",
            KitState.WARN, tag = "diff")
        KitActionBar(listOf(
            KitAction("Apply all → phone", ProfilesPageTags.APPLY_ALL, !busy) { io { applyToPhone(AccountDrift.drifted(fields)) } },
            KitAction("Capture all ← file", ProfilesPageTags.CAPTURE_ALL, !busy) { io { captureFromPhone(AccountDrift.drifted(fields)) } },
        ))
        SaidBanner(result, "diff:result")
        for ((app, c) in byApp) {
            val drifted = fields.filter { it.app == app && it.kind != AccountDrift.Kind.SAME }
            if (drifted.isEmpty()) continue
            KitCard {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(labels[app] ?: "(unowned)", color = p.textPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    KitStatePill("${c.drift} of ${c.same + c.drift}", KitState.WARN)
                }
                if (app.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, modifier = Modifier.testTag(ProfilesPageTags.appApply(app)), onClick = { io { applyToPhone(drifted.map { it.path }) } }) { Text("Apply all") }
                    TextButton(enabled = !busy, modifier = Modifier.testTag(ProfilesPageTags.appCapture(app)), onClick = { io { captureFromPhone(drifted.map { it.path }) } }) { Text("Capture all") }
                }
                for (f in drifted) {
                    val secret = InfoMask.declared.hides("settings.${f.path}", f.a ?: f.b ?: "")
                    KitListRow(fieldLabel(f.path), tag = f.path,
                        secondary = if (secret) "file and phone hold a secret: fingerprints only" else "file: ${f.a ?: "—"}\nphone: ${f.b ?: "—"}",
                        onLongClick = { copyPath(ctx, "settings.${f.path}") }) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            if (secret) KitFingerprint(f.a?.let(::fingerprint), tag = "file:${f.path}")
                            if (secret) KitFingerprint(f.b?.let(::fingerprint), tag = "phone:${f.path}")
                            TextButton(enabled = !busy, modifier = Modifier.testTag(ProfilesPageTags.rowApply(f.path)), onClick = { io { applyToPhone(listOf(f.path)) } }) { Text("→") }
                            TextButton(enabled = !busy, modifier = Modifier.testTag(ProfilesPageTags.rowCapture(f.path)), onClick = { io { captureFromPhone(listOf(f.path)) } }) { Text("←") }
                        }
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
