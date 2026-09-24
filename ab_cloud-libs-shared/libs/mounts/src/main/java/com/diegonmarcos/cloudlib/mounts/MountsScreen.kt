package com.diegonmarcos.cloudlib.mounts

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one entry point libs:mounts exports: the mount list (declared by the
 * host plus user-added; add / edit / test / delete), and per mount a
 * browse-through — navigate, download-and-open through the host, upload from
 * the system picker, new folder, rename, delete, and on SSH mounts a remote
 * command box. Hosted by the app that links this module (cloud-drive, push 5
 * of #567); this library never names that app.
 *
 * @param target a mount id to open its browser directly, a `scheme://…` URI
 *   to prefill the add dialog, or null for the list.
 * @param onOpenFile the host's file hand-off for a downloaded file.
 * @param onClose the host's way out of this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MountsScreen(
    target: String?,
    onOpenFile: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { MountStore(File(context.filesDir, "mounts/mounts.json")) }
    val credentials = remember { MountCredentialStore(context) }
    val downloadDir = remember { File(context.getExternalFilesDir(null) ?: context.filesDir, "mount-downloads") }
    var mounts by remember { mutableStateOf(store.load()) }
    var open by remember { mutableStateOf<MountSpec?>(null) }
    var editing by remember { mutableStateOf<MountSpec?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }   // prefilled uri or ""
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(target) {
        val t = target?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        mounts.firstOrNull { it.id == t }?.let { open = it; return@LaunchedEffect }
        if (t.contains("://")) adding = t
    }

    val current = open
    if (current != null) {
        BrowserScreen(current, credentials, downloadDir, onOpenFile, onBack = { open = null })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.cloudlib_mounts_title)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") } })
        },
        floatingActionButton = { FloatingActionButton(onClick = { adding = "" }) { Icon(Icons.Default.Add, contentDescription = "Add mount") } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (mounts.isEmpty()) item { Text("No mounts yet. Add an sftp / ssh / ftp / webdav connection; its password stays encrypted in this app.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            items(mounts, key = { it.id }) { m ->
                var testing by remember { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clickable { open = m }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name + if (m.declared) "  (declared)" else "", style = MaterialTheme.typography.titleMedium)
                            Text(m.uri, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Text(m.type.label + if (m.keyPath.isNotBlank()) " · key auth" else if (credentials.has(m.id)) " · password stored" else " · no credential", style = MaterialTheme.typography.bodySmall)
                        }
                        if (testing) CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
                        else TextButton(onClick = {
                            testing = true
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runCatching { MountFsFactory.open(m, credentials.secretFor(m), credentials.knownHosts).use { it.list(m.path).size } } }
                                testing = false
                                snackbar.showSnackbar(r.fold({ "${m.name}: reachable, $it entries" }, { "${m.name}: ${it.message ?: it}" }))
                            }
                        }) { Text("Test") }
                        IconButton(onClick = { editing = m }) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                    }
                }
            }
        }
    }

    if (adding != null || editing != null) {
        MountDialog(
            existing = editing, prefill = adding ?: "", credentials = credentials,
            onDismiss = { adding = null; editing = null },
            onSave = { m, secret ->
                if (secret.isNotBlank()) credentials.set(m.id, secret)
                mounts = store.upsert(m); adding = null; editing = null
            },
            onDelete = { m -> credentials.clear(m.id); mounts = store.remove(m.id); editing = null },
        )
    }
}

@Composable
private fun MountDialog(existing: MountSpec?, prefill: String, credentials: MountCredentialStore, onDismiss: () -> Unit, onSave: (MountSpec, String) -> Unit, onDelete: (MountSpec) -> Unit) {
    val parsed = remember(prefill) { if (prefill.isNotBlank()) MountUri.parse(prefill) else null }
    val seed = existing ?: parsed
    var name by remember { mutableStateOf(seed?.name ?: "") }
    var type by remember { mutableStateOf(seed?.type ?: MountType.SFTP) }
    var host by remember { mutableStateOf(seed?.host ?: "") }
    var port by remember { mutableStateOf((seed?.port ?: type.defaultPort).toString()) }
    var username by remember { mutableStateOf(seed?.username ?: "") }
    var path by remember { mutableStateOf(seed?.path ?: "/") }
    var keyPath by remember { mutableStateOf(seed?.keyPath ?: "") }
    var secret by remember { mutableStateOf("") }
    val hasSecret = existing != null && credentials.has(existing.id)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add mount" else "Edit ${existing.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                MountType.values().forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable { if (port == type.defaultPort.toString()) port = t.defaultPort.toString(); type = t }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = type == t, onClick = { if (port == type.defaultPort.toString()) port = t.defaultPort.toString(); type = t }); Text(t.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(host, { host = it }, label = { Text("Host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it.filter { c -> c.isDigit() } }, label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(username, { username = it }, label = { Text("User") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(path, { path = it }, label = { Text("Start path") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (type == MountType.SFTP || type == MountType.SSH) OutlinedTextField(keyPath, { keyPath = it }, label = { Text("Private key file (empty = password)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it }, label = { Text((if (keyPath.isNotBlank()) "Key passphrase" else "Password") + if (hasSecret) " (stored — leave empty to keep)" else "") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (type == MountType.SFTP || type == MountType.SSH) Text("Host keys are trusted on first use into the app's own known_hosts and refused if they change.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = host.isNotBlank() && port.toIntOrNull() != null, onClick = {
                val p = port.toInt()
                val id = existing?.id ?: MountUri.idFor(type, host.trim(), p, username.trim(), path.trim())
                onSave(MountSpec(id, name.trim().ifBlank { "${username.trim()}@${host.trim()}".trimStart('@') }, type, host.trim(), p, username.trim(), path.trim().ifBlank { "/" }, keyPath.trim(), existing?.declared ?: false), secret)
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null && !existing.declared) TextButton(onClick = { onDelete(existing) }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserScreen(mount: MountSpec, credentials: MountCredentialStore, downloadDir: File, onOpenFile: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var fs by remember { mutableStateOf<RemoteFs?>(null) }
    var path by remember { mutableStateOf(mount.path.ifBlank { "/" }) }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<RemoteEntry?>(null) }
    var deleting by remember { mutableStateOf<RemoteEntry?>(null) }
    var command by remember { mutableStateOf<String?>(null) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    fun notify(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    fun refresh(p: String = path) {
        busy = true; error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val f = fs ?: MountFsFactory.open(mount, credentials.secretFor(mount), credentials.knownHosts).also { fs = it }
                    f.list(p)
                }
            }
            busy = false
            r.onSuccess { entries = it; path = p }.onFailure { error = it.message ?: it.toString() }
        }
    }
    LaunchedEffect(mount.id) { refresh(mount.path.ifBlank { "/" }) }
    androidx.compose.runtime.DisposableEffect(mount.id) { onDispose { fs?.let { f -> Thread { runCatching { f.close() } }.start() } } }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val u = uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val name = context.contentResolver.query(u, null, null, null, null)?.use { c -> val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && i >= 0) c.getString(i) else null } ?: "upload"
                    val tmp = File(context.cacheDir, "mounts-upload/$name").apply { parentFile?.mkdirs() }
                    context.contentResolver.openInputStream(u)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    fs!!.upload(tmp, RemotePaths.join(path, name)); tmp.delete(); name
                }
            }
            r.onSuccess { notify("uploaded $it"); refresh() }.onFailure { notify("upload failed: ${it.message}") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text(mount.name, style = MaterialTheme.typography.titleMedium); Text(path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(enabled = fs != null, onClick = { uploadPicker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Upload, contentDescription = "Upload") }
                    IconButton(enabled = fs != null, onClick = { newFolder = true }) { Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder") }
                    if (mount.type == MountType.SSH) IconButton(enabled = fs != null, onClick = { command = "" }) { Icon(Icons.Default.Terminal, contentDescription = "Run command") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) CircularProgressIndicator(Modifier.padding(12.dp))
            error?.let { Text("cannot list: $it", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            LazyColumn(Modifier.fillMaxSize()) {
                if (path != "/" && path.isNotEmpty()) item {
                    ListItem(headlineContent = { Text("..") }, leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) }, modifier = Modifier.clickable { refresh(RemotePaths.parent(path)) })
                    HorizontalDivider()
                }
                if (entries.isEmpty() && !busy && error == null) item { Text("(empty)", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                items(entries, key = { it.path }) { e ->
                    ListItem(
                        headlineContent = { Text(e.name) },
                        supportingContent = { Text(if (e.isDir) "folder" else humanBytes(e.size) + if (e.modifiedEpochMillis > 0) "  " + fmt.format(Date(e.modifiedEpochMillis)) else "", style = MaterialTheme.typography.bodySmall) },
                        leadingContent = { Icon(if (e.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile, contentDescription = null) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { renaming = e }) { Icon(Icons.Default.Edit, contentDescription = "Rename") }
                                IconButton(onClick = { deleting = e }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                            }
                        },
                        modifier = Modifier.clickable {
                            if (e.isDir) refresh(e.path)
                            else scope.launch {
                                notify("downloading ${e.name}…")
                                val into = File(downloadDir, mount.id + e.path)
                                val r = withContext(Dispatchers.IO) { runCatching { fs!!.download(e.path, into); into } }
                                r.onSuccess { onOpenFile(it.absolutePath) }.onFailure { notify("download failed: ${it.message}") }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newFolder = false }, title = { Text("New folder in $path") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { newFolder = false; scope.launch { withContext(Dispatchers.IO) { runCatching { fs!!.mkdir(RemotePaths.join(path, name.trim())) } }.onSuccess { refresh() }.onFailure { notify("mkdir failed: ${it.message}") } } }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text("Cancel") } })
    }
    renaming?.let { e ->
        var name by remember(e.path) { mutableStateOf(e.name) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename ${e.name}") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("New name") }) },
            confirmButton = { TextButton(enabled = name.isNotBlank() && name != e.name, onClick = { renaming = null; scope.launch { withContext(Dispatchers.IO) { runCatching { fs!!.rename(e.path, RemotePaths.join(RemotePaths.parent(e.path), name.trim())) } }.onSuccess { refresh() }.onFailure { notify("rename failed: ${it.message}") } } }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    deleting?.let { e ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${e.name}?") },
            text = { Text(if (e.isDir) "The folder must be empty on most servers. This cannot be undone." else "This cannot be undone.") },
            confirmButton = { TextButton(onClick = { deleting = null; scope.launch { withContext(Dispatchers.IO) { runCatching { fs!!.delete(e.path, e.isDir) } }.onSuccess { refresh() }.onFailure { notify("delete failed: ${it.message}") } } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } })
    }
    command?.let { cmd ->
        var text by remember { mutableStateOf(cmd) }
        var output by remember { mutableStateOf<String?>(null) }
        var running by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { command = null }, title = { Text("Run on ${mount.host}") },
            text = {
                Column {
                    OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Command") }, modifier = Modifier.fillMaxWidth())
                    if (running) CircularProgressIndicator(Modifier.padding(8.dp))
                    output?.let { Text(it, Modifier.verticalScroll(rememberScrollState()).height(240.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(enabled = !running && text.isNotBlank(), onClick = { running = true; scope.launch { output = withContext(Dispatchers.IO) { runCatching { fs!!.exec(text) ?: "(not an ssh mount)" }.getOrElse { "error: ${it.message}" } }; running = false } }) { Text("Run") } },
            dismissButton = { TextButton(onClick = { command = null }) { Text("Close") } })
    }
}

private fun humanBytes(b: Long): String {
    if (b < 0) return ""
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB"); var v = b.toDouble(); var i = -1
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format("%.1f %s", v, units[i])
}
