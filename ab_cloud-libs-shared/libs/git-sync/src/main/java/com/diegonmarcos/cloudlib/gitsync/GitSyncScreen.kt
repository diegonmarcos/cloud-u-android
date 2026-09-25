package com.diegonmarcos.cloudlib.gitsync

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one entry point libs:git-sync exports: GitSync's UX, natively. A list of
 * managed repositories with one-tap Sync, and per repository a manager with
 * Changes (stage / unstage / commit, conflicts on top), Log, Branches
 * (read-only), Remotes and Settings. Hosted by the app that links this module
 * (cloud-drive, push 5 of #567); this library never names that app.
 *
 * @param target a path: a repository (opened directly, and registered if new),
 *   a plain folder (offered for init / clone), or null for the list.
 * @param onOpenFile the host's file hand-off: a LOCAL path the host may open in
 *   its own viewer or editor.
 * @param onClose the host's way out of this screen.
 * @param cloneUrl (#575) when the host names a repository to clone, the URL to
 *   prefill the Add dialog with; [target] is then the folder it lands in.
 */
@Composable
fun GitSyncScreen(
    target: String?,
    onOpenFile: (String) -> Unit,
    onClose: () -> Unit,
    cloneUrl: String? = null,
) {
    val context = LocalContext.current
    val registry = remember { RepoRegistry(File(context.filesDir, "git-sync/repos.json")) }
    val credentials = remember { GitCredentialStore(context) }
    var repos by remember { mutableStateOf(registry.load()) }
    var open by remember { mutableStateOf<ManagedRepo?>(null) }
    var addPrefill by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(target) {
        val t = target?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val dir = File(t)
        if (GitEngine.isRepository(dir)) {
            val existing = repos.firstOrNull { File(it.path).absolutePath == dir.absolutePath }
            open = existing ?: ManagedRepo(RepoRegistry.idFor(t), dir.name, dir.absolutePath).also {
                repos = registry.upsert(it)
            }
        } else if (dir.isDirectory || !cloneUrl.isNullOrBlank()) {
            // A folder to add or init — or (#575) a clone target that does not exist yet.
            addPrefill = dir.absolutePath
        }
    }

    val current = open
    if (current == null) {
        RepoListScreen(
            repos = repos, credentials = credentials, addPrefill = addPrefill, addPrefillUrl = cloneUrl,
            onAddDismiss = { addPrefill = null },
            onAdd = { repo -> repos = registry.upsert(repo); open = repo },
            onOpen = { open = it }, onClose = onClose,
            onSynced = { repo -> repos = registry.upsert(repo) },
        )
    } else {
        RepoScreen(
            repo = current, credentials = credentials, onOpenFile = onOpenFile,
            onBack = { open = null },
            onSave = { repo -> repos = registry.upsert(repo); open = repo },
            onRemove = { repo -> repos = registry.remove(repo.id); credentials.clear(repo.id); open = null },
        )
    }
}

// ── repository list ──────────────────────────────────────────────────────

private data class RepoGlance(val branch: String?, val ahead: Int, val behind: Int, val dirty: Int, val error: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoListScreen(
    repos: List<ManagedRepo>,
    credentials: GitCredentialStore,
    addPrefill: String?,
    addPrefillUrl: String?,
    onAddDismiss: () -> Unit,
    onAdd: (ManagedRepo) -> Unit,
    onOpen: (ManagedRepo) -> Unit,
    onClose: () -> Unit,
    onSynced: (ManagedRepo) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var glances by remember { mutableStateOf<Map<String, RepoGlance>>(emptyMap()) }
    var syncing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffect(repos) {
        glances = withContext(Dispatchers.IO) {
            repos.associate { r ->
                r.id to runCatching {
                    GitEngine(File(r.path)).use { e ->
                        val s = e.status(); RepoGlance(s.branch, s.ahead, s.behind, s.files.size)
                    }
                }.getOrElse { RepoGlance(null, 0, 0, 0, it.message ?: it.toString()) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cloudlib_gitsync_title)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") } },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, contentDescription = "Add repository") } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (repos.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No repositories yet. Add an existing folder or clone one.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(repos, key = { it.id }) { repo ->
                val g = glances[repo.id]
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clickable { onOpen(repo) }) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(repo.name, style = MaterialTheme.typography.titleMedium)
                                Text(repo.path, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    when {
                                        g == null -> "reading…"
                                        g.error != null -> "cannot read: ${g.error}"
                                        else -> "${g.branch ?: "(no branch)"}  ↑${g.ahead} ↓${g.behind}  ${g.dirty} changed"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (repo.id in syncing) CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
                            else IconButton(onClick = {
                                syncing = syncing + repo.id
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            GitEngine(File(repo.path)).use {
                                                it.sync(repo.syncMessage, repo.authorName.ifBlank { "cloud-drive" },
                                                    repo.authorEmail.ifBlank { "cloud-drive@localhost" },
                                                    rebase = repo.pullRebase, auth = credentials.authFor(repo))
                                            }
                                        }.getOrElse { GitOpResult(false, it.message ?: it.toString()) }
                                    }
                                    syncing = syncing - repo.id
                                    onSynced(repo.copy(lastSyncEpochSeconds = System.currentTimeMillis() / 1000, lastSyncSummary = result.summary))
                                    snackbar.showSnackbar(result.summary)
                                }
                            }) { Icon(Icons.Default.Sync, contentDescription = "Sync") }
                        }
                        if (repo.lastSyncEpochSeconds > 0) {
                            Text("last sync ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(repo.lastSyncEpochSeconds * 1000))}: ${repo.lastSyncSummary}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    if (showAdd || addPrefill != null) {
        AddRepoDialog(
            prefillPath = addPrefill ?: "",
            prefillUrl = addPrefillUrl ?: "",
            credentials = credentials,
            onDismiss = { showAdd = false; onAddDismiss() },
            onAdded = { showAdd = false; onAddDismiss(); onAdd(it) },
        )
    }
}

@Composable
private fun AddRepoDialog(prefillPath: String, prefillUrl: String, credentials: GitCredentialStore, onDismiss: () -> Unit, onAdded: (ManagedRepo) -> Unit) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(if (prefillPath.isNotEmpty() && !GitEngine.isRepository(File(prefillPath))) 1 else 0) }
    var path by remember { mutableStateOf(prefillPath) }
    var url by remember { mutableStateOf(prefillUrl) }
    var username by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Add repository") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row { listOf("Existing folder", "Clone", "Init").forEachIndexed { i, label ->
                    Row(Modifier.clickable { mode = i }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == i, onClick = { mode = i }); Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                } }
                OutlinedTextField(path, { path = it }, label = { Text("Folder path") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (mode == 1) {
                    OutlinedTextField(url, { url = it }, label = { Text("Remote URL (https:// or ssh)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(username, { username = it }, label = { Text("Username (https, optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(token, { token = it }, label = { Text("Token / password (optional)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) CircularProgressIndicator(Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && path.isNotBlank(), onClick = {
                busy = true; error = null
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        runCatching {
                            val dir = File(path.trim())
                            val id = RepoRegistry.idFor(dir.absolutePath)
                            val auth = if (username.isNotBlank() || token.isNotBlank()) GitAuth.Https(username, token) else GitAuth.None
                            when (mode) {
                                0 -> require(GitEngine.isRepository(dir)) { "$path is not a git repository" }
                                1 -> { require(url.isNotBlank()) { "a clone needs a URL" }; GitEngine.clone(url.trim(), dir, auth).close() }
                                else -> { dir.mkdirs(); GitEngine.init(dir).close() }
                            }
                            if (token.isNotBlank()) credentials.setSecret(id, token)
                            val remoteUrl = if (mode == 1) url.trim() else GitEngine(dir).use { e -> e.remotes().firstOrNull()?.fetchUrl ?: "" }
                            ManagedRepo(id, dir.name, dir.absolutePath, remoteUrl = remoteUrl,
                                authKind = if (token.isNotBlank()) "https" else "none", authUsername = username)
                        }
                    }
                    busy = false
                    outcome.onSuccess(onAdded).onFailure { error = it.message ?: it.toString() }
                }
            }) { Text(listOf("Add", "Clone", "Init")[mode]) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── one repository ───────────────────────────────────────────────────────

/** Everything the manager screen shows for one repository, refreshed after every verb. */
private class RepoSession(val repo: ManagedRepo, val credentials: GitCredentialStore, val scope: CoroutineScope) {
    var snapshot by mutableStateOf<GitStatusSnapshot?>(null)
    var commits by mutableStateOf<List<GitCommitInfo>>(emptyList())
    var branches by mutableStateOf<List<GitBranchInfo>>(emptyList())
    var remotes by mutableStateOf<List<GitRemoteInfo>>(emptyList())
    var busy by mutableStateOf(false)
    var lastResult by mutableStateOf<GitOpResult?>(null)

    fun refresh() = run("refresh") { e ->
        snapshot = e.status(); commits = e.log(); branches = e.branches(); remotes = e.remotes(); null
    }

    /** Runs [block] on IO with an open engine, then re-reads everything; a thrown error becomes the last result. */
    fun run(label: String, block: (GitEngine) -> GitOpResult?) {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    GitEngine(File(repo.path)).use { e ->
                        val r = block(e)
                        snapshot = e.status(); commits = e.log(); branches = e.branches(); remotes = e.remotes()
                        r
                    }
                }.getOrElse { GitOpResult(false, "$label failed: ${it.message ?: it.toString()}") }
            }
            if (result != null) lastResult = result
            busy = false
        }
    }

    val auth: GitAuth get() = credentials.authFor(repo)
    val authorName: String get() = repo.authorName.ifBlank { "cloud-drive" }
    val authorEmail: String get() = repo.authorEmail.ifBlank { "cloud-drive@localhost" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoScreen(
    repo: ManagedRepo,
    credentials: GitCredentialStore,
    onOpenFile: (String) -> Unit,
    onBack: () -> Unit,
    onSave: (ManagedRepo) -> Unit,
    onRemove: (ManagedRepo) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val session = remember(repo.id) { RepoSession(repo, credentials, scope) }
    var tab by remember { mutableIntStateOf(0) }
    var diff by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    LaunchedEffect(repo.id) { session.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(repo.name, style = MaterialTheme.typography.titleMedium)
                        val s = session.snapshot
                        Text(
                            if (s == null) repo.path else "${s.branch ?: "(no branch)"}" +
                                (s.upstream?.let { " → $it" } ?: "") + "  ↑${s.ahead} ↓${s.behind}" +
                                (if (s.repositoryState != "SAFE") "  ${s.repositoryState}" else ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { onOpenFile(repo.path) }) { Icon(Icons.Default.FolderOpen, contentDescription = "Open folder") }
                    IconButton(onClick = { session.refresh() }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                },
            )
        },
        bottomBar = {
            Column {
                session.lastResult?.let { r ->
                    Row(
                        Modifier.fillMaxWidth().background(if (r.ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer)
                            .clickable { showDetails = !showDetails }.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text(r.summary, style = MaterialTheme.typography.bodySmall, maxLines = if (showDetails) 20 else 1) }
                    if (showDetails && r.details.isNotBlank()) Text(r.details, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 20)
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val hasRemote = session.remotes.isNotEmpty()
                    OutlinedButton(enabled = !session.busy && hasRemote, onClick = { session.run("fetch") { it.fetch(auth = session.auth) } }) { Text("Fetch") }
                    OutlinedButton(enabled = !session.busy && hasRemote, onClick = { session.run("pull") { it.pull(rebase = repo.pullRebase, auth = session.auth) } }) { Text("Pull") }
                    OutlinedButton(enabled = !session.busy && hasRemote, onClick = { session.run("push") { it.push(auth = session.auth) } }) { Text("Push") }
                    Button(enabled = !session.busy, onClick = {
                        session.run("sync") { it.sync(repo.syncMessage, session.authorName, session.authorEmail, rebase = repo.pullRebase, auth = session.auth) }
                    }) { Icon(Icons.Default.Sync, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Sync") }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                listOf("Changes", "Log", "Branches", "Remotes", "Settings").forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }
            if (session.busy) CircularProgressIndicator(Modifier.padding(8.dp).align(Alignment.CenterHorizontally))
            when (tab) {
                0 -> ChangesTab(session) { path, staged -> diff = path to (if (staged) "staged" else "worktree") }
                1 -> LogTab(session) { sha -> diff = sha to "commit" }
                2 -> BranchesTab(session)
                3 -> RemotesTab(session)
                else -> SettingsTab(repo, credentials, onSave, onRemove)
            }
        }
    }

    diff?.let { (key, kind) ->
        var text by remember(key, kind) { mutableStateOf<String?>(null) }
        LaunchedEffect(key, kind) {
            text = withContext(Dispatchers.IO) {
                runCatching {
                    GitEngine(File(repo.path)).use { e ->
                        when (kind) { "commit" -> e.commitDiff(key); "staged" -> e.diff(key, staged = true); else -> e.diff(key) }
                    }
                }.getOrElse { "cannot diff: ${it.message}" }
            }
        }
        ModalBottomSheet(onDismissRequest = { diff = null }) {
            Text(if (kind == "commit") "commit ${key.take(8)}" else key, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall)
            DiffView(text)
        }
    }
}

@Composable
private fun DiffView(text: String?) {
    if (text == null) { CircularProgressIndicator(Modifier.padding(16.dp)); return }
    val lines = remember(text) { text.lines() }
    LazyColumn(Modifier.fillMaxWidth().height(480.dp).horizontalScroll(rememberScrollState()).padding(8.dp)) {
        if (lines.all { it.isBlank() }) item { Text("(no differences)", style = MaterialTheme.typography.bodySmall) }
        items(lines) { line ->
            val colour = when {
                line.startsWith("+++") || line.startsWith("---") -> MaterialTheme.colorScheme.onSurfaceVariant
                line.startsWith("+") -> Color(0xFF2E7D32)
                line.startsWith("-") -> Color(0xFFC62828)
                line.startsWith("@@") -> Color(0xFF1565C0)
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text(line, color = colour, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

private fun GitChange.glyph() = when (this) { GitChange.ADDED -> "A"; GitChange.MODIFIED -> "M"; GitChange.DELETED -> "D"; GitChange.UNTRACKED -> "?" }

@Composable
private fun ChangesTab(session: RepoSession, onDiff: (String, Boolean) -> Unit) {
    val s = session.snapshot
    var message by remember(session.repo.id) { mutableStateOf("") }
    var amend by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf<String?>(null) }
    if (s == null) { Text("reading…", Modifier.padding(12.dp)); return }
    LazyColumn(Modifier.fillMaxSize()) {
        if (s.conflicts.isNotEmpty()) {
            item { SectionHeader("Conflicts (${s.conflicts.size})", "Abort merge") { session.run("abort") { it.abortMerge(); GitOpResult(true, "merge aborted") } } }
            items(s.conflicts, key = { "c" + it.path }) { f ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("${f.path}  (${f.stageState ?: "conflict"})", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { onDiff(f.path, false) })
                    Row {
                        TextButton(onClick = { session.run("ours") { it.resolve(f.path, ConflictSide.OURS); GitOpResult(true, "took ours: ${f.path}") } }) { Text("Ours") }
                        TextButton(onClick = { session.run("theirs") { it.resolve(f.path, ConflictSide.THEIRS); GitOpResult(true, "took theirs: ${f.path}") } }) { Text("Theirs") }
                        TextButton(onClick = { session.run("resolved") { it.markResolved(f.path); GitOpResult(true, "marked resolved: ${f.path}") } }) { Text("Mark resolved") }
                    }
                }
            }
            item { HorizontalDivider() }
        }
        item { SectionHeader("Staged (${s.staged.size})", "Unstage all") { session.run("unstage") { it.unstageAll(); null } } }
        if (s.staged.isEmpty()) item { Text("nothing staged", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall) }
        items(s.staged, key = { "s" + it.path }) { f ->
            ListItem(
                headlineContent = { Text(f.path, style = MaterialTheme.typography.bodyMedium) },
                leadingContent = { Text(f.staged!!.glyph(), fontFamily = FontFamily.Monospace) },
                trailingContent = { IconButton(onClick = { session.run("unstage") { it.unstage(listOf(f.path)); null } }) { Icon(Icons.Default.Remove, contentDescription = "Unstage") } },
                modifier = Modifier.clickable { onDiff(f.path, true) },
            )
        }
        item { HorizontalDivider() }
        item { SectionHeader("Changes (${s.unstaged.size})", "Stage all") { session.run("stage") { it.stageAll(); null } } }
        if (s.unstaged.isEmpty()) item { Text("working tree clean", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall) }
        items(s.unstaged, key = { "u" + it.path }) { f ->
            ListItem(
                headlineContent = { Text(f.path, style = MaterialTheme.typography.bodyMedium) },
                leadingContent = { Text(f.unstaged!!.glyph(), fontFamily = FontFamily.Monospace) },
                trailingContent = {
                    Row {
                        IconButton(onClick = { confirmDiscard = f.path }) { Icon(Icons.Default.Delete, contentDescription = "Discard") }
                        IconButton(onClick = { session.run("stage") { it.stage(listOf(f.path)); null } }) { Icon(Icons.Default.Add, contentDescription = "Stage") }
                    }
                },
                modifier = Modifier.clickable { onDiff(f.path, false) },
            )
        }
        item {
            Column(Modifier.padding(12.dp)) {
                OutlinedTextField(message, { message = it }, label = { Text("Commit message") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = amend, onCheckedChange = { amend = it }); Text("Amend last commit", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Button(enabled = !session.busy && message.isNotBlank() && (s.staged.isNotEmpty() || amend), onClick = {
                        val text = message
                        session.run("commit") { e ->
                            val c = e.commit(text, session.authorName, session.authorEmail, amend)
                            GitOpResult(true, "committed ${c.shortSha}: ${c.summary}")
                        }
                        message = ""; amend = false
                    }) { Text("Commit") }
                }
            }
        }
    }
    confirmDiscard?.let { path ->
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text("Discard changes?") },
            text = { Text("$path goes back to the last committed version. An untracked file is deleted. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = null; session.run("discard") { it.discard(path); GitOpResult(true, "discarded $path") } }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun LogTab(session: RepoSession, onCommit: (String) -> Unit) {
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    if (session.commits.isEmpty()) { Text("no commits yet", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall); return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(session.commits, key = { it.sha }) { c ->
            ListItem(
                headlineContent = { Text(c.summary, maxLines = 2) },
                supportingContent = { Text("${c.shortSha} · ${c.author} · ${fmt.format(Date(c.time * 1000))}", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.clickable { onCommit(c.sha) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun BranchesTab(session: RepoSession) {
    val local = session.branches.filter { !it.isRemote }
    val remote = session.branches.filter { it.isRemote }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Read-only: this manager lists branches and never creates, deletes or switches them (fleet rule).", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
        item { SectionHeader("Local (${local.size})") }
        items(local, key = { it.fullName }) { b ->
            ListItem(headlineContent = { Text((if (b.isCurrent) "● " else "   ") + b.name) }, supportingContent = { Text(b.sha.take(8), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) })
        }
        item { SectionHeader("Remote (${remote.size})") }
        items(remote, key = { it.fullName }) { b ->
            ListItem(headlineContent = { Text("   " + b.name) }, supportingContent = { Text(b.sha.take(8), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) })
        }
    }
}

@Composable
private fun RemotesTab(session: RepoSession) {
    var editing by remember { mutableStateOf<GitRemoteInfo?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Remotes (${session.remotes.size})", "Add") { adding = true } }
        if (session.remotes.isEmpty()) item { Text("no remotes — add one to push and pull", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall) }
        items(session.remotes, key = { it.name }) { r ->
            ListItem(
                headlineContent = { Text(r.name) },
                supportingContent = { Text(if (r.fetchUrl == r.pushUrl) r.fetchUrl else "fetch ${r.fetchUrl}\npush  ${r.pushUrl}", style = MaterialTheme.typography.bodySmall) },
                trailingContent = {
                    Row {
                        IconButton(onClick = { editing = r }) { Icon(Icons.Default.Edit, contentDescription = "Set URL") }
                        IconButton(onClick = { confirmDelete = r.name }) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
                    }
                },
            )
        }
    }
    if (adding || editing != null) {
        var name by remember { mutableStateOf(editing?.name ?: "origin") }
        var url by remember { mutableStateOf(editing?.fetchUrl ?: "") }
        AlertDialog(
            onDismissRequest = { adding = false; editing = null },
            title = { Text(if (editing == null) "Add remote" else "Set URL of ${editing!!.name}") },
            text = {
                Column {
                    if (editing == null) OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(url, { url = it }, label = { Text("URL") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && url.isNotBlank(), onClick = {
                    val wasEditing = editing != null; val n = name.trim(); val u = url.trim()
                    adding = false; editing = null
                    session.run("remote") { e ->
                        if (wasEditing) e.setRemoteUrl(n, u) else e.addRemote(n, u)
                        GitOpResult(true, (if (wasEditing) "set " else "added ") + "$n → $u")
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false; editing = null }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { n ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove remote $n?") },
            text = { Text("Only the remote entry is removed; no commits are touched.") },
            confirmButton = { TextButton(onClick = { confirmDelete = null; session.run("remote") { it.removeRemote(n); GitOpResult(true, "removed remote $n") } }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun SettingsTab(repo: ManagedRepo, credentials: GitCredentialStore, onSave: (ManagedRepo) -> Unit, onRemove: (ManagedRepo) -> Unit) {
    var name by remember { mutableStateOf(repo.name) }
    var authorName by remember { mutableStateOf(repo.authorName) }
    var authorEmail by remember { mutableStateOf(repo.authorEmail) }
    var authKind by remember { mutableStateOf(repo.authKind) }
    var username by remember { mutableStateOf(repo.authUsername) }
    var secret by remember { mutableStateOf("") }
    var keyPath by remember { mutableStateOf(repo.sshKeyPath) }
    var rebase by remember { mutableStateOf(repo.pullRebase) }
    var syncMessage by remember { mutableStateOf(repo.syncMessage) }
    var autoSync by remember { mutableStateOf(repo.autoSync) }
    var intervalText by remember { mutableStateOf(if (repo.syncIntervalMinutes > 0) repo.syncIntervalMinutes.toString() else "") }
    var unmetered by remember { mutableStateOf(repo.syncRequireUnmetered) }
    var confirmRemove by remember { mutableStateOf(false) }
    val hasSecret = remember(repo.id) { credentials.hasSecret(repo.id) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(repo.path, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text("Author", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(authorName, { authorName = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(authorEmail, { authorEmail = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("Authentication", style = MaterialTheme.typography.labelLarge)
        Row {
            listOf("none" to "None", "https" to "HTTPS token", "ssh" to "SSH key").forEach { (k, label) ->
                Row(Modifier.clickable { authKind = k }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = authKind == k, onClick = { authKind = k }); Text(label, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (authKind == "https") {
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(secret, { secret = it }, label = { Text(if (hasSecret) "Token (stored — leave empty to keep)" else "Token / password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        }
        if (authKind == "ssh") {
            OutlinedTextField(keyPath, { keyPath = it }, label = { Text("Private key file path") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(secret, { secret = it }, label = { Text(if (hasSecret) "Passphrase (stored — leave empty to keep)" else "Passphrase (optional)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Text("Host keys are recorded on first contact and refused if they change (accept-new).", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        Text("Sync", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = rebase, onCheckedChange = { rebase = it }); Spacer(Modifier.width(8.dp)); Text("Pull with rebase") }
        // #575 GitSync's scheduler: the host runs Sync for opted-in repositories in the background.
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = autoSync, onCheckedChange = { autoSync = it }); Spacer(Modifier.width(8.dp)); Text("Sync on a schedule (background)") }
        // #579 per-repository period and network rule; the host's scheduler reads both.
        OutlinedTextField(intervalText, { intervalText = it.filter { c -> c.isDigit() } }, label = { Text("Period in minutes (empty = the app's base period)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = unmetered, onCheckedChange = { unmetered = it }); Spacer(Modifier.width(8.dp)); Text("Only on Wi-Fi (unmetered network)") }
        OutlinedTextField(syncMessage, { syncMessage = it }, label = { Text("Sync commit message") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { confirmRemove = true }) { Text("Remove from list") }
            Button(onClick = {
                if (secret.isNotBlank()) credentials.setSecret(repo.id, secret)
                if (authKind == "none") credentials.clear(repo.id)
                onSave(repo.copy(name = name.trim().ifBlank { repo.name }, authorName = authorName.trim(), authorEmail = authorEmail.trim(),
                    authKind = authKind, authUsername = username.trim(), sshKeyPath = keyPath.trim(), pullRebase = rebase,
                    syncMessage = syncMessage.trim().ifBlank { repo.syncMessage }, autoSync = autoSync,
                    syncIntervalMinutes = intervalText.toLongOrNull() ?: 0L, syncRequireUnmetered = unmetered))
                secret = ""
            }) { Text("Save") }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${repo.name} from the list?") },
            text = { Text("The folder and its .git stay on disk; only this manager forgets it and its stored credential.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; onRemove(repo) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } },
        )
    }
}
