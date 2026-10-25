package com.diegonmarcos.clouddrive.sync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.storage.RcloneProviders
import com.diegonmarcos.clouddrive.storage.StorageCoordinator
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.cloudlib.rclone.RcloneRunner
import org.json.JSONObject

/**
 * Mounts > Rclone: the in-app `rclone config`. The backend list and every question come from `rclone config providers`
 * ([RcloneProviders]); the answers are applied with the real `rclone config create` / `update`, the browser sign-in is rclone's own
 * `rclone authorize`, the shared-drive list is `rclone backend drives`, and the last step runs `rclone lsd` on the new remote. The
 * screens only walk the stages in the order rclone's terminal flow asks: backend, name, questions (one at a time), sign-in for
 * the OAuth backends (browser or service account), review, create, shared drive, test. Secrets typed here are hidden, handed to
 * rclone and to Cloud Account, and never logged.
 *
 * [editing] = the name of an existing remote: its type and current non-secret answers are read back with `rclone config show`,
 * a secret left empty keeps the stored value.
 */
private enum class Stage { PICK, NAME, ASK, AUTH, REVIEW, SHARED, DONE }

@Composable
fun RemoteWizard(storage: StorageCoordinator, editing: String?, onDismiss: () -> Unit) {
    val providers by storage.providers.collectAsState()
    val loading by storage.providersLoading.collectAsState()
    val loadError by storage.providersError.collectAsState()
    val notes by storage.remoteNotes.collectAsState()
    LaunchedEffect(Unit) { storage.loadProviders() }

    var stage by remember { mutableStateOf(if (editing == null) Stage.PICK else Stage.ASK) }
    var provider by remember { mutableStateOf<RcloneProviders.Provider?>(null) }
    var name by remember { mutableStateOf(editing.orEmpty()) }
    val values = remember { mutableStateMapOf<String, String>() }
    val stored = remember { mutableStateMapOf<String, String>() }     // what `config show` printed (edit only); secrets are never drawn
    var advanced by remember { mutableStateOf(false) }
    var index by remember { mutableStateOf(0) }
    var search by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var token by remember { mutableStateOf<String?>(null) }
    var authMode by remember { mutableStateOf("browser") }
    var saJson by remember { mutableStateOf("") }
    var authUrl by remember { mutableStateOf<String?>(null) }
    var authWaiting by remember { mutableStateOf(false) }
    var handle by remember { mutableStateOf<RcloneRunner.Handle?>(null) }
    val drives = remember { mutableStateListOf<RcloneProviders.SharedDrive>() }
    var drivesLoaded by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current

    DisposableEffect(Unit) { onDispose { handle?.cancel() } }

    // edit: read the remote back from rclone, then choose its provider schema
    LaunchedEffect(editing, providers.size) {
        if (editing != null && provider == null && providers.isNotEmpty()) {
            busy = true
            storage.showRemote(editing) { r ->
                r.onSuccess { (type, options) ->
                    provider = providers.firstOrNull { it.name == type }
                    options.forEach { (k, v) -> stored[k] = v; if (!RcloneProviders.masked(k)) values[k] = v }
                    if (provider == null) failure = "rclone has no backend named $type"
                }.onFailure { failure = it.message }
                busy = false
            }
        }
    }

    val p = provider
    val steps = if (p == null) emptyList() else RcloneProviders.steps(p, values.toMap(), advanced)
    val oauth = p?.oauth == true

    fun save() {
        val prov = p ?: return
        busy = true; failure = null
        val send = LinkedHashMap<String, String>()
        for (o in steps) values[o.name]?.takeIf { it.isNotBlank() }?.let { if (editing == null || stored[o.name] != it) send[o.name] = it }
        if (oauth) {
            if (authMode == "sa" && saJson.isNotBlank()) send["service_account_credentials"] = saJson
            token?.let { send["token"] = it }
        }
        storage.saveRemote(name, prov.name, send, editing != null) { why ->
            busy = false
            if (why != null) failure = why
            else { created = true; stage = if (prov.hasSharedDrives && oauth) Stage.SHARED else Stage.DONE; if (stage == Stage.DONE) storage.testRemote(name) }
        }
    }

    val title = if (editing == null) stringResource(R.string.wiz_new) else stringResource(R.string.wiz_edit, editing)
    AlertDialog(
        onDismissRequest = { handle?.cancel(); onDismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = DriveMetrics.gap))
                failure?.let { NoteText(stringResource(R.string.wiz_failed, it), error = true, maxLines = 6) }
                when {
                    stage == Stage.PICK -> {
                        when {
                            providers.isEmpty() && loading -> NoteText(stringResource(R.string.providers_loading))
                            providers.isEmpty() -> {
                                NoteText(stringResource(R.string.providers_failed, loadError ?: ""), error = true)
                                Pill(stringResource(R.string.storage_retry), { storage.loadProviders(force = true) })
                            }
                            else -> {
                                FormField(stringResource(R.string.wiz_search), search, { search = it })
                                val shown = providers.filter { search.isBlank() || it.name.contains(search, true) || it.description.contains(search, true) }
                                Box(Modifier.heightIn(max = DriveMetrics.dialogList)) {
                                    LazyColumn {
                                        items(shown, key = { it.name }) { pr ->
                                            Column(Modifier.fillMaxWidth().clickable { provider = pr; stage = Stage.NAME }.padding(vertical = DriveMetrics.pad)) {
                                                Text(pr.name, style = MaterialTheme.typography.titleSmall)
                                                Text(pr.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    stage == Stage.NAME -> Column {
                        NoteText("${p?.name}: ${p?.description}")
                        FormField(stringResource(R.string.wiz_name), name, { name = it }, supporting = if (name.isNotEmpty() && !RcloneProviders.validName(name)) stringResource(R.string.wiz_name_bad) else null)
                        SwitchRow(stringResource(R.string.wiz_advanced), advanced, { advanced = it })
                    }
                    stage == Stage.ASK && p != null -> {
                        val o = steps.getOrNull(index.coerceIn(0, (steps.size - 1).coerceAtLeast(0)))
                        if (o == null) NoteText(stringResource(R.string.wiz_review))
                        else Column(Modifier.verticalScroll(rememberScrollState())) {
                            NoteText(stringResource(R.string.wiz_step, index + 1, steps.size))
                            Text(o.name, style = MaterialTheme.typography.titleSmall)
                            Text(o.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 12, overflow = TextOverflow.Ellipsis)
                            val shownDefault = o.default.takeIf { it.isNotBlank() }
                            NoteText(if (o.required && shownDefault == null) stringResource(R.string.wiz_required) else stringResource(R.string.wiz_optional) + (shownDefault?.let { " · " + stringResource(R.string.wiz_default, it) } ?: ""))
                            val examples = RcloneProviders.examples(o, values.toMap())
                            if (examples.isNotEmpty()) ChoiceRow(examples.map { it.value to it.value.ifBlank { "·" } }, values[o.name].orEmpty(), { values[o.name] = it })
                            if (o.isBool) SwitchRow(o.name, values[o.name] == "true" || (values[o.name] == null && o.default == "true"), { values[o.name] = it.toString() })
                            else if (!o.exclusive || examples.isEmpty()) FormField(o.name, values[o.name].orEmpty(), { values[o.name] = it }, secret = o.password || (o.sensitive && RcloneProviders.masked(o.name)),
                                supporting = if (editing != null && stored.containsKey(o.name) && RcloneProviders.masked(o.name)) stringResource(R.string.wiz_secret_kept) else null)
                        }
                    }
                    stage == Stage.AUTH && p != null -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        NoteText(stringResource(R.string.wiz_auth_title))
                        ChoiceRow(listOfNotNull("browser" to stringResource(R.string.wiz_auth_browser), ("sa" to stringResource(R.string.wiz_auth_sa)).takeIf { p.hasServiceAccount }, ("keep" to stringResource(R.string.wiz_auth_keep)).takeIf { editing != null }), authMode, { authMode = it })
                        when (authMode) {
                            "browser" -> {
                                if (token != null) NoteText(stringResource(R.string.wiz_auth_done))
                                else {
                                    authUrl?.let { u -> Pill(stringResource(R.string.wiz_auth_open), { uri.openUri(u) }, filled = true) }
                                    if (authWaiting) NoteText(stringResource(R.string.wiz_auth_waiting))
                                    else Pill(stringResource(R.string.wiz_auth_start), {
                                        authWaiting = true; authUrl = null; failure = null
                                        handle = storage.ops.authorize(p.name, values["client_id"].orEmpty(), values["client_secret"].orEmpty(),
                                            onUrl = { u -> authUrl = u; runCatching { uri.openUri(u) } },
                                            onDone = { r -> authWaiting = false; r.onSuccess { token = it }.onFailure { failure = it.message } })
                                    }, filled = true)
                                }
                            }
                            "sa" -> FormField(stringResource(R.string.wiz_sa_paste), saJson, { saJson = it }, secret = true, singleLine = false)
                            else -> NoteText(stringResource(R.string.wiz_auth_keep))
                        }
                    }
                    stage == Stage.REVIEW && p != null -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("${name}: ${p.name}", style = MaterialTheme.typography.titleSmall)
                        for (o in steps) {
                            val v = values[o.name].orEmpty()
                            if (v.isNotBlank()) NoteText("${o.name} = ${if (RcloneProviders.masked(o.name) || o.password) "••••" else v}", maxLines = 2)
                        }
                        if (oauth) NoteText(if (authMode == "sa" && saJson.isNotBlank()) stringResource(R.string.wiz_auth_sa) else if (token != null) stringResource(R.string.wiz_auth_done) else stringResource(R.string.wiz_auth_keep))
                    }
                    stage == Stage.SHARED -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        NoteText(stringResource(R.string.wiz_shared_title))
                        if (!drivesLoaded) Pill(stringResource(R.string.wiz_shared_load), {
                            busy = true
                            storage.shared(name) { r -> busy = false; drivesLoaded = true; r.onSuccess { drives.clear(); drives.addAll(it) }.onFailure { failure = it.message } }
                        }, filled = true, enabled = !busy)
                        else {
                            Pill(stringResource(R.string.wiz_shared_mine), { stage = Stage.DONE; storage.testRemote(name) })
                            drives.forEach { d -> Pill(d.name.ifBlank { d.id }, {
                                busy = true
                                storage.saveRemote(name, p?.name.orEmpty(), mapOf("team_drive" to d.id), true) { why -> busy = false; if (why != null) failure = why else { stage = Stage.DONE; storage.testRemote(name) } }
                            }) }
                        }
                    }
                    stage == Stage.DONE -> {
                        NoteText(stringResource(R.string.wiz_test_ok, notes[name] ?: stringResource(R.string.wiz_working)))
                    }
                }
            }
        },
        confirmButton = {
            when (stage) {
                Stage.PICK -> {}
                Stage.NAME -> TextButton(onClick = { stage = Stage.ASK; index = 0 }, enabled = RcloneProviders.validName(name)) { Text(stringResource(R.string.storage_next)) }
                Stage.ASK -> TextButton(onClick = {
                    val last = index >= steps.size - 1
                    if (!last) index += 1 else stage = if (oauth) Stage.AUTH else Stage.REVIEW
                }, enabled = p != null && !busy && steps.getOrNull(index).let { o -> o == null || !o.required || o.default.isNotBlank() || values[o.name].orEmpty().isNotBlank() || stored.containsKey(o.name) }) {
                    Text(stringResource(R.string.storage_next))
                }
                Stage.AUTH -> TextButton(onClick = { stage = Stage.REVIEW }, enabled = authMode == "keep" || (authMode == "browser" && token != null) || (authMode == "sa" && runCatching { JSONObject(saJson).has("private_key") }.getOrDefault(false))) { Text(stringResource(R.string.storage_next)) }
                Stage.REVIEW -> TextButton(onClick = { if (authMode == "sa") saJson = runCatching { JSONObject(saJson).toString() }.getOrDefault(saJson); save() }, enabled = !busy) {
                    Text(stringResource(if (editing == null) R.string.wiz_create else R.string.wiz_update))
                }
                Stage.SHARED -> TextButton(onClick = { stage = Stage.DONE; storage.testRemote(name) }) { Text(stringResource(R.string.storage_next)) }
                Stage.DONE -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.storage_close)) }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                when {
                    created || stage == Stage.DONE -> onDismiss()
                    stage == Stage.NAME -> stage = Stage.PICK
                    stage == Stage.ASK && index > 0 -> index -= 1
                    stage == Stage.ASK && editing == null -> stage = Stage.NAME
                    stage == Stage.AUTH -> { stage = Stage.ASK; index = (steps.size - 1).coerceAtLeast(0) }
                    stage == Stage.REVIEW -> stage = if (oauth) Stage.AUTH else Stage.ASK
                    else -> { handle?.cancel(); onDismiss() }
                }
            }) { Text(stringResource(if (stage == Stage.PICK || stage == Stage.DONE || (stage == Stage.ASK && editing != null && index == 0)) R.string.storage_cancel else R.string.storage_back)) }
        },
    )
}
