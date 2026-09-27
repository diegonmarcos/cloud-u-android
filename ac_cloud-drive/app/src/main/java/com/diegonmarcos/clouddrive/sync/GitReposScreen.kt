package com.diegonmarcos.clouddrive.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.clouddrive.BuildConfig
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.EngineActivity
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.SharedStore
import com.diegonmarcos.clouddrive.ui.CapsuleBadge
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.Hairline
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.clouddrive.ui.StatusDot
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.clouddrive.ui.StatusLightRow
import com.diegonmarcos.cloudlib.auth.SignIn
import com.diegonmarcos.cloudlib.auth.SignInHost
import com.diegonmarcos.cloudlib.auth.SignInResult
import com.diegonmarcos.cloudlib.auth.SignInWays
import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #608 SYNC ▸ GIT, redesigned whole and DECLARED whole (build.json::ui.sync.git — the
 * sections, the personal split, the login ways, the endpoint, the remote modes and the
 * fourteen per-repository operations all come from there; this file names no repository,
 * no owner, no URL and no operation label).
 *
 * DATA-DENSE: one COMPACT row per repository — the light, the name, a handful of inline
 * monospace stats, its clone state — so a long list is scannable, with every operation
 * COLLAPSED behind the row's own disclosure instead of a large box per repository. The
 * expanded row is the operations box: fetch · pull · stage & commit · push · force push ·
 * force pull · metadata · open on web · stats · commit history · remote switch (HTTPS /
 * SSH / read-only) · hooks · GitHub Actions workflows · size, every git verb reached
 * through libs:git-sync's GitEngine and never a second git.
 *
 * TWO SECTIONS (ui.sync.git.sections):
 *  · public   — the declared set of the owner's PUBLIC repositories in the cloud and
 *               front name families. No login: they clone over plain HTTPS.
 *  · personal — the fleet sign-in (libs:auth's device grant against the declared GitHub
 *               provider, whose scope is already `repo`) or the user's existing SSH key.
 *               After a GitHub sign-in the account's own listing is fetched with the
 *               approval's token and split into the declared groups, Public then Private.
 *
 * Every clone lands in the store's git folder (#606, SharedStore.gitRoot), the same folder
 * and the same registry the first-run seed uses.
 */
@Composable
fun GitReposScreen(coordinator: GitSyncCoordinator, actions: DriveActions, nextRunMinutes: Long?, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val page = Declarations.sync.git
    val repos by coordinator.repos.collectAsState()
    val glances by coordinator.glances.collectAsState()
    val running by coordinator.running.collectAsState()
    val events by coordinator.events.collectAsState()
    val details by coordinator.details.collectAsState()
    val opResults by coordinator.opResults.collectAsState()
    val cloning by coordinator.cloning.collectAsState()
    var settingsFor by remember { mutableStateOf<ManagedRepo?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf("") }
    var login by remember { mutableStateOf(GitLogin()) }
    var listing by remember { mutableStateOf(GitListing()) }
    LaunchedEffect(Unit) { coordinator.refresh() }

    // #606 the store's git folder — the ONE place a clone lands, resolved from the declaration.
    val root = remember { SharedStore.gitRoot().absolutePath }
    val clonedByName = repos.associateBy { File(it.path).name }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    /** Clone [name] with whatever the personal section signed in as; public rows need nothing. */
    fun clone(name: String) {
        val ssh = login.ssh && login.sshKeyPath.isNotBlank()
        val mode = if (ssh) Declarations.REMOTE_SSH else Declarations.REMOTE_HTTPS
        coordinator.cloneInto(
            name = name,
            url = page.cloneUrl(name, mode),
            authKind = when {
                ssh -> GitSyncCoordinator.AUTH_SSH
                login.token.isNotBlank() -> GitSyncCoordinator.AUTH_HTTPS
                else -> GitSyncCoordinator.AUTH_NONE
            },
            username = login.identity,
            token = if (ssh) "" else login.token,
            sshKeyPath = if (ssh) login.sshKeyPath else "",
        )
    }

    /** The account's own repositories, with the token the sign-in proved. */
    fun fetchListing(token: String) {
        listing = listing.copy(loading = true, error = "")
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { GitHubRepos.fetch(page.api, token) }
            listing = outcome.fold(
                onSuccess = { GitListing(loaded = true, repos = it.repos, complete = it.complete) },
                onFailure = { GitListing(loaded = true, error = it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    val host = remember {
        object : SignInHost {
            override fun onSignedIn(result: SignInResult) {
                SignIn.Current.session = SignIn.Session(result.provider.id, result.identity)
                login = login.copy(identity = result.identity, token = result.accessToken)
                if (result.accessToken.isNotBlank()) fetchListing(result.accessToken)
            }
        }
    }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_HERO).padding(horizontal = DriveMetrics.gutter + 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Commit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.sync_store) + " · " + root, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.sync_repos_count, repos.size) + " · " +
                            stringResource(R.string.sync_schedule, BuildConfig.GIT_SYNC_INTERVAL_MINUTES, stringResource(if (BuildConfig.GIT_SYNC_REQUIRE_UNMETERED) R.string.sync_network_unmetered else R.string.sync_network_any)) + " · " +
                            (nextRunMinutes?.let { stringResource(R.string.sync_next_run, it) } ?: stringResource(R.string.sync_next_run_unknown)),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                    )
                }
                Pill(stringResource(R.string.sync_add_repo), { actions.openEngine(EngineActivity.ENGINE_GIT, root) }, filled = true)
            }
        }

        // THE TWO DECLARED SECTIONS, in declared order. The dispatch on a section id is the
        // ONE place Kotlin names them; test-drive-git-page.sh diffs it both ways.
        page.sections.forEach { section ->
            when (section.id) {
                "public" -> {
                    item {
                        SectionHeader(section.label, count = page.publicRepos.size)
                    }
                    items(page.publicRepos, key = { "pub-" + it.name }) { decl ->
                        val cloned = clonedByName[decl.name]
                        GitRepoRow(
                            name = decl.label, repoName = decl.name, isPrivate = false, fork = false,
                            repo = cloned, glance = cloned?.let { glances[it.id] }, running = cloned?.let { running[it.id] },
                            cloning = decl.name in cloning, expanded = expanded == "pub-" + decl.name,
                            onToggle = { expanded = if (expanded == "pub-" + decl.name) "" else "pub-" + decl.name },
                            onClone = { clone(decl.name) },
                        )
                        if (expanded == "pub-" + decl.name && cloned != null) {
                            GitRepoOpsBox(
                                repo = cloned, page = page, details = details[cloned.id], result = opResults[cloned.id],
                                running = running[cloned.id], glance = glances[cloned.id], fmt = fmt,
                                coordinator = coordinator, actions = actions,
                                repoName = decl.name, onSettings = { settingsFor = cloned },
                            )
                        }
                        Hairline()
                    }
                }
                "personal" -> {
                    item {
                        SectionHeader(section.label, count = if (listing.loaded) listing.repos.size else null)
                        GitLoginBox(
                            page = page, host = host, login = login, listing = listing,
                            onSshKeyPath = { login = login.copy(sshKeyPath = it) },
                            onUseSsh = { login = login.copy(ssh = it) },
                            onRetry = { if (login.token.isNotBlank()) fetchListing(login.token) },
                        )
                    }
                    if (listing.loaded && listing.error.isBlank()) {
                        page.personalGroups.forEach { group ->
                            val inGroup = GitHubRepos.group(listing.repos, group.id == Declarations.GIT_GROUP_PRIVATE)
                            item { SectionHeader(group.label, count = inGroup.size) }
                            items(inGroup, key = { "own-" + it.owner + "/" + it.name }) { gh ->
                                val cloned = clonedByName[gh.name]
                                val key = "own-" + gh.name
                                GitRepoRow(
                                    name = gh.name, repoName = gh.name, isPrivate = gh.private, fork = gh.fork,
                                    repo = cloned, glance = cloned?.let { glances[it.id] }, running = cloned?.let { running[it.id] },
                                    cloning = gh.name in cloning, expanded = expanded == key,
                                    onToggle = { expanded = if (expanded == key) "" else key },
                                    onClone = { clone(gh.name) },
                                )
                                if (expanded == key && cloned != null) {
                                    GitRepoOpsBox(
                                        repo = cloned, page = page, details = details[cloned.id], result = opResults[cloned.id],
                                        running = running[cloned.id], glance = glances[cloned.id], fmt = fmt,
                                        coordinator = coordinator, actions = actions,
                                        repoName = gh.name, onSettings = { settingsFor = cloned },
                                    )
                                }
                                Hairline()
                            }
                        }
                    }
                }
                else -> item { EmptyState(IconCatalog.vectorOrDefault(Declarations.iconDefault), stringResource(R.string.chrome_unknown_tab), "") }
            }
        }

        // Any clone the store holds that neither section named — a folder added through the
        // engine itself. Shown, never hidden: a repository the page cannot see is one the
        // scheduler still syncs.
        val named = page.publicRepos.map { it.name }.toSet() + listing.repos.map { it.name }.toSet()
        val others = repos.filter { File(it.path).name !in named }
        if (others.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.git_section_other), count = others.size) }
            items(others, key = { "other-" + it.id }) { repo ->
                val key = "other-" + repo.id
                GitRepoRow(
                    name = repo.name, repoName = repo.name, isPrivate = false, fork = false,
                    repo = repo, glance = glances[repo.id], running = running[repo.id],
                    cloning = false, expanded = expanded == key,
                    onToggle = { expanded = if (expanded == key) "" else key },
                    onClone = {},
                )
                if (expanded == key) {
                    GitRepoOpsBox(
                        repo = repo, page = page, details = details[repo.id], result = opResults[repo.id],
                        running = running[repo.id], glance = glances[repo.id], fmt = fmt,
                        coordinator = coordinator, actions = actions,
                        repoName = repo.name, onSettings = { settingsFor = repo },
                    )
                }
                Hairline()
            }
        }
        if (repos.isEmpty() && page.publicRepos.isEmpty()) item { EmptyState(Icons.Filled.Commit, stringResource(R.string.sync_empty_title), stringResource(R.string.sync_empty_hint)) }

        item {
            SectionHeader(stringResource(R.string.sync_history_section), count = events.size, action = if (showHistory) stringResource(R.string.chrome_close) else stringResource(R.string.sync_history)) { showHistory = !showHistory }
        }
        if (showHistory) {
            if (events.isEmpty()) item { Text(stringResource(R.string.sync_history_empty), Modifier.padding(horizontal = DriveMetrics.gutter + 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(events.take(50), key = { "${it.epochSeconds}-${it.repoId}" }) { e -> HistoryRow(e, fmt) }
        }
    }

    settingsFor?.let { repo -> RepoSettingsSheet(repo, coordinator, onDismiss = { settingsFor = null }) }
}

/** What the personal section signed in as, for the process only: never stored, never logged. */
private data class GitLogin(
    val identity: String = "",
    val token: String = "",
    val ssh: Boolean = false,
    val sshKeyPath: String = "",
) {
    val signedIn: Boolean get() = token.isNotBlank() || (ssh && sshKeyPath.isNotBlank())
}

/** The authenticated listing, or why there is none. */
private data class GitListing(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val repos: List<GitHubRepos.Repo> = emptyList(),
    val complete: Boolean = true,
    val error: String = "",
)

/**
 * The personal section's control: the declared ways in, then what the sign-in yielded. The
 * WebAuth way is libs:auth's own surface, narrowed by policy to the DECLARED provider, so
 * this page offers exactly the GitHub device grant and not the fleet's other providers.
 */
@Composable
private fun GitLoginBox(
    page: Declarations.GitPageDecl,
    host: SignInHost,
    login: GitLogin,
    listing: GitListing,
    onSshKeyPath: (String) -> Unit,
    onUseSsh: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    val webauth = page.webauthWay
    val ssh = page.sshWay
    DriveCard(
        stringResource(R.string.git_login_title),
        light = if (login.signedIn) StatusLight.State.ON else StatusLight.State.UNKNOWN,
        summary = when {
            login.token.isNotBlank() -> stringResource(R.string.git_login_as, login.identity.ifBlank { webauth?.label ?: "" }, webauth?.scope ?: "")
            login.ssh && login.sshKeyPath.isNotBlank() -> stringResource(R.string.git_login_ssh_active)
            else -> stringResource(R.string.git_login_hint)
        },
        tag = DriveTags.SYNC_GIT_LOGIN,
    ) {
        if (webauth != null) {
            SignInWays(host = host, policy = listOf(webauth.provider), modifier = Modifier.padding(top = 6.dp), pill = { label, tag, onClick ->
                Pill(label, onClick, modifier = Modifier.padding(top = 6.dp).testTag(tag), icon = IconCatalog.vectorOrDefault(webauth.icon))
            })
        }
        if (ssh != null) {
            PillRow { Pill(ssh.label, { onUseSsh(!login.ssh) }, icon = IconCatalog.vectorOrDefault(ssh.icon), filled = login.ssh) }
            if (login.ssh) {
                OutlinedTextField(
                    login.sshKeyPath, onSshKeyPath,
                    label = { Text(stringResource(R.string.sync_auth_key_path)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Text(stringResource(R.string.git_login_ssh_cannot_list), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when {
            listing.loading -> Text(stringResource(R.string.git_listing_loading), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
            listing.error.isNotBlank() -> {
                Text(stringResource(R.string.git_listing_failed, listing.error), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = colorResource(R.color.status_light_off))
                PillRow { Pill(stringResource(R.string.chrome_retry), onRetry) }
            }
            listing.loaded && !listing.complete -> Text(stringResource(R.string.git_listing_truncated, page.api.maxPages), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listing.loaded && listing.repos.isEmpty() -> Text(stringResource(R.string.git_listing_empty), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * ONE dense row. Single line where it fits: the light, the name, the private/fork badges,
 * the inline monospace glance (branch ↑ahead ↓behind clean|N changed) and either the clone
 * state or the disclosure. No card, no per-repository box — that is what expanding does.
 */
@Composable
private fun GitRepoRow(
    name: String,
    repoName: String,
    isPrivate: Boolean,
    fork: Boolean,
    repo: ManagedRepo?,
    glance: GitSyncCoordinator.Glance?,
    running: GitSyncCoordinator.Running?,
    cloning: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClone: () -> Unit,
) {
    val off = colorResource(R.color.status_light_off)
    val light = lightOf(repo, glance)
    Row(
        Modifier.fillMaxWidth().testTag(DriveTags.SYNC_GIT_ROW)
            .heightIn(min = 40.dp)
            .clickable(enabled = repo != null, onClick = onToggle)
            .padding(horizontal = DriveMetrics.gutter + 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(light, name)
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (isPrivate) CapsuleBadge(stringResource(R.string.chrome_private))
        if (fork) CapsuleBadge(stringResource(R.string.git_fork))
        Spacer(Modifier.width(8.dp))
        Text(
            rowStats(repo, glance),
            Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
            color = if (glance != null && glance.conflicts > 0) off else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        when {
            cloning -> Text(stringResource(R.string.git_cloning), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            repo == null -> Pill(stringResource(R.string.sync_clone_into_store), onClone, filled = true)
            else -> Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.git_collapse else R.string.git_expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (running != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.gutter + 4.dp))
}

/** The row's inline stats: what fits on one line and nothing more. */
@Composable
private fun rowStats(repo: ManagedRepo?, glance: GitSyncCoordinator.Glance?): String = when {
    repo == null -> stringResource(R.string.git_not_cloned)
    glance == null || !glance.read -> stringResource(R.string.sync_glance_reading)
    glance.gone -> stringResource(R.string.sync_glance_gone)
    glance.error != null -> stringResource(R.string.sync_glance_unreadable, glance.error)
    else -> buildString {
        append(glance.branch ?: stringResource(R.string.sync_no_branch))
        append(" ↑").append(glance.ahead).append(" ↓").append(glance.behind).append(" ")
        append(if (glance.changed == 0) stringResource(R.string.sync_clean) else stringResource(R.string.sync_changed, glance.changed))
        if (glance.conflicts > 0) append(" ").append(stringResource(R.string.sync_conflicts, glance.conflicts))
        if (glance.repositoryState != "SAFE") append(" ").append(glance.repositoryState)
    }
}

/** The light of one row, from the last outcome and the live glance — never green on a conflict. */
private fun lightOf(repo: ManagedRepo?, glance: GitSyncCoordinator.Glance?): StatusLight.State = when {
    repo == null -> StatusLight.State.UNKNOWN
    glance == null || !glance.read -> StatusLight.State.UNKNOWN
    glance.gone -> StatusLight.State.UNVERIFIABLE
    glance.error != null -> StatusLight.State.UNKNOWN
    glance.conflicts > 0 -> StatusLight.State.OFF
    repo.lastSyncEpochSeconds == 0L -> StatusLight.State.UNKNOWN
    repo.lastSyncSummary.contains("failed", true) || repo.lastSyncSummary.contains("conflict", true) || repo.lastSyncSummary.contains("rejected", true) -> StatusLight.State.OFF
    else -> StatusLight.State.ON
}

/**
 * THE per-repository operations box, the row's disclosure. Every DECLARED operation is a
 * pill in declared order; the dispatch on an operation id is the ONE place Kotlin names
 * them and test-drive-git-page.sh diffs it against ui.sync.git.ops in both directions.
 * The six transport/commit verbs go to the coordinator (GitEngine); the eight reads open a
 * panel below. A destructive operation opens a confirmation first, never runs on the tap.
 */
@Composable
private fun GitRepoOpsBox(
    repo: ManagedRepo,
    page: Declarations.GitPageDecl,
    details: GitSyncCoordinator.Details?,
    result: com.diegonmarcos.cloudlib.gitsync.GitOpResult?,
    running: GitSyncCoordinator.Running?,
    glance: GitSyncCoordinator.Glance?,
    fmt: DateFormat,
    coordinator: GitSyncCoordinator,
    actions: DriveActions,
    repoName: String,
    onSettings: () -> Unit,
) {
    val off = colorResource(R.color.status_light_off)
    var panel by remember(repo.id) { mutableStateOf("") }
    var confirm by remember(repo.id) { mutableStateOf<Declarations.GitOpDecl?>(null) }
    var message by remember(repo.id) { mutableStateOf("") }
    val readOnly = page.remoteMode(Declarations.REMOTE_READONLY)?.urlFor(page.owner, repoName) == repo.remoteUrl &&
        repo.authKind == GitSyncCoordinator.AUTH_NONE
    LaunchedEffect(repo.id) { coordinator.loadDetails(repo, page.historyMax) }

    DriveCard(repo.name, light = lightOf(repo, glance), tag = DriveTags.SYNC_REPO_CARD) {
        Text(
            if (repo.lastSyncEpochSeconds > 0) stringResource(R.string.sync_last, fmt.format(Date(repo.lastSyncEpochSeconds * 1000)), repo.lastSyncSummary) else stringResource(R.string.sync_never),
            Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (glance != null && glance.conflicts > 0) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = off, modifier = Modifier.padding(horizontal = 4.dp))
                Text(stringResource(R.string.sync_conflicts, glance.conflicts), color = off, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            PillRow { Pill(stringResource(R.string.sync_resolve_conflicts, glance.conflicts), { actions.openEngine(EngineActivity.ENGINE_GIT, repo.path) }, filled = true) }
        }
        if (running != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GitSyncCoordinator.Step.values().forEach { step ->
                    val active = step == running.step
                    Text(stepLabel(step), style = MaterialTheme.typography.labelSmall, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (running.step == GitSyncCoordinator.Step.PUSHING) Text(stringResource(R.string.sync_step_uninterruptible), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // THE OPERATIONS RAIL — declared order, horizontally scrollable so a dense row's
        // disclosure never becomes a wall of buttons.
        Row(
            Modifier.fillMaxWidth().testTag(DriveTags.SYNC_GIT_OPS).padding(top = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            page.ops.forEach { op ->
                val blocked = readOnly && (op.id == GitSyncCoordinator.OP_PUSH || op.id == GitSyncCoordinator.OP_FORCE_PUSH)
                Pill(
                    op.label,
                    {
                        when (op.id) {
                            "fetch" -> coordinator.runOp(repo, op.id)
                            "pull" -> coordinator.runOp(repo, op.id)
                            "commit" -> panel = if (panel == op.id) "" else op.id
                            "push" -> coordinator.runOp(repo, op.id)
                            "force_push" -> confirm = op
                            "force_pull" -> confirm = op
                            "metadata" -> panel = if (panel == op.id) "" else op.id
                            "web" -> actions.openUrl(page.webUrl(repoName))
                            "stats" -> panel = if (panel == op.id) "" else op.id
                            "history" -> panel = if (panel == op.id) "" else op.id
                            "remote" -> panel = if (panel == op.id) "" else op.id
                            "hooks" -> panel = if (panel == op.id) "" else op.id
                            "actions" -> panel = if (panel == op.id) "" else op.id
                            "size" -> panel = if (panel == op.id) "" else op.id
                            else -> panel = ""
                        }
                    },
                    icon = IconCatalog.vectorOrDefault(op.icon),
                    filled = panel == op.id,
                    enabled = running == null && !blocked,
                )
            }
            Pill(stringResource(R.string.sync_open), { actions.openEngine(EngineActivity.ENGINE_GIT, repo.path) }, icon = Icons.Filled.FolderOpen)
            Pill(stringResource(R.string.sync_now), { coordinator.syncNow(repo) }, icon = Icons.Filled.Sync, enabled = running == null && glance?.gone != true)
            Pill(stringResource(R.string.sync_history), { panel = if (panel == "history") "" else "history" }, icon = Icons.Filled.History)
            Pill(stringResource(R.string.sync_settings), onSettings, icon = Icons.Filled.Settings)
        }
        if (readOnly) Text(stringResource(R.string.git_readonly_blocked), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        result?.let { r ->
            Text(
                r.summary, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                color = if (r.ok) MaterialTheme.colorScheme.onSurfaceVariant else off, maxLines = 4, overflow = TextOverflow.Ellipsis,
            )
        }

        // THE PANELS — one open at a time, each a read the declaration named.
        val d = details
        when (panel) {
            "commit" -> {
                OutlinedTextField(message, { message = it }, label = { Text(stringResource(R.string.git_commit_message)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                PillRow { Pill(page.op("commit")?.label.orEmpty(), { coordinator.runOp(repo, "commit", message); panel = "" }, filled = true, enabled = running == null) }
            }
            "metadata" -> Column(Modifier.padding(top = 6.dp)) {
                MetaLine(stringResource(R.string.git_meta_path, repo.path))
                MetaLine(stringResource(R.string.git_meta_branch, glance?.branch ?: stringResource(R.string.sync_no_branch), glance?.upstream ?: stringResource(R.string.git_meta_no_upstream)))
                MetaLine(stringResource(R.string.git_meta_auth, repo.authKind, repo.remoteUrl.ifBlank { stringResource(R.string.git_meta_no_remote) }))
                MetaLine(stringResource(R.string.git_meta_author, repo.authorName.ifBlank { GitSyncCoordinator.DEFAULT_AUTHOR }, repo.authorEmail.ifBlank { GitSyncCoordinator.DEFAULT_EMAIL }))
                if (d != null && !d.loading) {
                    d.remotes.forEach { r -> MetaLine(stringResource(R.string.git_meta_remote, r.name, r.fetchUrl, r.pushUrl)) }
                    MetaLine(stringResource(R.string.git_meta_branches, d.branches.count { !it.isRemote }, d.branches.count { it.isRemote }))
                }
                d?.error?.let { why -> MetaLine(stringResource(R.string.sync_glance_unreadable, why)) }
            }
            "stats" -> Column(Modifier.padding(top = 6.dp)) {
                MetaLine(stringResource(R.string.git_stats_state, if (glance?.changed == 0) stringResource(R.string.sync_clean) else stringResource(R.string.sync_changed, glance?.changed ?: 0), glance?.ahead ?: 0, glance?.behind ?: 0, glance?.conflicts ?: 0))
                if (d != null && !d.loading) MetaLine(stringResource(R.string.git_stats_commits, d.commits.size, d.hooks.size, d.workflows.size))
            }
            "history" -> Column(Modifier.padding(top = 6.dp)) {
                if (d == null || d.loading) Text(stringResource(R.string.sync_glance_reading), style = MaterialTheme.typography.bodySmall)
                else if (d.commits.isEmpty()) Text(stringResource(R.string.git_history_empty), style = MaterialTheme.typography.bodySmall)
                else d.commits.forEach { c ->
                    MetaLine(stringResource(R.string.git_commit_line, c.shortSha, fmt.format(Date(c.time * 1000)), c.author, c.summary))
                }
            }
            "remote" -> Column(Modifier.padding(top = 6.dp)) {
                Text(stringResource(R.string.git_remote_switch), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    page.remoteModes.forEach { mode ->
                        val url = mode.urlFor(page.owner, repoName)
                        Pill(mode.label, { coordinator.setRemote(repo, mode, page.owner, repoName) }, icon = IconCatalog.vectorOrDefault(mode.icon), filled = repo.remoteUrl == url)
                    }
                }
                page.remoteModes.forEach { mode -> MetaLine(stringResource(R.string.git_remote_mode_line, mode.label, mode.urlFor(page.owner, repoName))) }
            }
            "hooks" -> Column(Modifier.padding(top = 6.dp)) {
                if (d == null || d.loading) Text(stringResource(R.string.sync_glance_reading), style = MaterialTheme.typography.bodySmall)
                else if (d.hooks.isEmpty()) Text(stringResource(R.string.git_hooks_none), style = MaterialTheme.typography.bodySmall)
                else d.hooks.forEach { h -> MetaLine(h) }
            }
            "actions" -> Column(Modifier.padding(top = 6.dp)) {
                if (d == null || d.loading) Text(stringResource(R.string.sync_glance_reading), style = MaterialTheme.typography.bodySmall)
                else if (d.workflows.isEmpty()) Text(stringResource(R.string.git_actions_none), style = MaterialTheme.typography.bodySmall)
                else d.workflows.forEach { w -> MetaLine(w) }
            }
            "size" -> Column(Modifier.padding(top = 6.dp)) {
                if (d == null || d.loading) Text(stringResource(R.string.sync_glance_reading), style = MaterialTheme.typography.bodySmall)
                else MetaLine(stringResource(R.string.git_size_line, GitRepoScan.humanBytes(d.size.bytes), d.size.files, d.size.folders, GitRepoScan.humanBytes(d.size.gitBytes)))
            }
        }
        Text(
            if (repo.autoSync) stringResource(R.string.sync_auto, if (repo.syncIntervalMinutes > 0) repo.syncIntervalMinutes else BuildConfig.GIT_SYNC_INTERVAL_MINUTES, stringResource(if (repo.syncRequireUnmetered) R.string.sync_network_unmetered else R.string.sync_network_any)) else stringResource(R.string.sync_manual),
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    confirm?.let { op ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(op.label) },
            text = { Text(stringResource(if (op.id == GitSyncCoordinator.OP_FORCE_PUSH) R.string.git_confirm_force_push else R.string.git_confirm_force_pull, repo.name)) },
            confirmButton = { TextButton(onClick = { coordinator.runOp(repo, op.id); confirm = null }) { Text(op.label) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.chrome_cancel)) } },
        )
    }
}

/** One monospace fact line of a panel. */
@Composable
private fun MetaLine(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun stepLabel(step: GitSyncCoordinator.Step): String = when (step) {
    GitSyncCoordinator.Step.STAGING -> stringResource(R.string.sync_step_staging)
    GitSyncCoordinator.Step.COMMITTING -> stringResource(R.string.sync_step_committing)
    GitSyncCoordinator.Step.PULLING -> stringResource(R.string.sync_step_pulling)
    GitSyncCoordinator.Step.PUSHING -> stringResource(R.string.sync_step_pushing)
}

@Composable
private fun HistoryRow(e: SyncEvent, fmt: DateFormat, compact: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val state = if (e.ok) StatusLight.State.ON else StatusLight.State.OFF
    Column(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_HISTORY).clickable { open = !open }.padding(horizontal = if (compact) 0.dp else DriveMetrics.gutter + 4.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusLightRow(state, e.repoName)
            Spacer(Modifier.width(8.dp))
            Text(fmt.format(Date(e.epochSeconds * 1000)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            if (!compact) Text(e.repoName, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            CapsuleBadge(stringResource(if (e.trigger == SyncHistory.TRIGGER_SCHEDULED) R.string.sync_trigger_scheduled else R.string.sync_trigger_manual))
        }
        Text(e.summary, style = MaterialTheme.typography.bodySmall, maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis)
        if (open && e.details.isNotBlank()) Text(e.details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 12)
    }
}

/** Per-repository settings: schedule (on/off, period, network), auth, author, rebase, message, remove. Writes the ManagedRepo the engine reads. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoSettingsSheet(repo: ManagedRepo, coordinator: GitSyncCoordinator, onDismiss: () -> Unit) {
    var autoSync by remember { mutableStateOf(repo.autoSync) }
    var interval by remember { mutableStateOf(repo.syncIntervalMinutes) }
    var unmetered by remember { mutableStateOf(repo.syncRequireUnmetered) }
    var authKind by remember { mutableStateOf(repo.authKind) }
    var username by remember { mutableStateOf(repo.authUsername) }
    var secret by remember { mutableStateOf("") }
    var keyPath by remember { mutableStateOf(repo.sshKeyPath) }
    var authorName by remember { mutableStateOf(repo.authorName) }
    var authorEmail by remember { mutableStateOf(repo.authorEmail) }
    var rebase by remember { mutableStateOf(repo.pullRebase) }
    var message by remember { mutableStateOf(repo.syncMessage) }
    var confirmRemove by remember { mutableStateOf(false) }
    val hasSecret = remember(repo.id) { coordinator.hasSecret(repo) }
    val periods = Declarations.sync.gitPeriodsMinutes

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(repo.name, style = MaterialTheme.typography.titleLarge)
            Text(repo.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.padding(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = autoSync, onCheckedChange = { autoSync = it }); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.sync_settings_auto)) }
            Text(stringResource(R.string.sync_settings_period), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf(0L) + periods.map { it.toLong() }).forEach { p ->
                    Pill(if (p == 0L) stringResource(R.string.sync_settings_minutes, BuildConfig.GIT_SYNC_INTERVAL_MINUTES) else stringResource(R.string.sync_settings_minutes, p), { interval = p }, filled = interval == p)
                }
            }
            Text(stringResource(R.string.sync_settings_network), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(stringResource(R.string.sync_network_unmetered), { unmetered = true }, filled = unmetered)
                Pill(stringResource(R.string.sync_network_any), { unmetered = false }, filled = !unmetered)
            }
            Text(stringResource(R.string.sync_settings_auth), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
            Row {
                listOf("none" to R.string.sync_auth_none, "https" to R.string.sync_auth_https, "ssh" to R.string.sync_auth_ssh).forEach { (k, label) ->
                    Row(Modifier.clickable { authKind = k }, verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = authKind == k, onClick = { authKind = k }); Text(stringResource(label), style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (authKind == "https") {
                OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.sync_auth_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it }, label = { Text(stringResource(if (hasSecret) R.string.sync_auth_token_stored else R.string.sync_auth_token)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            if (authKind == "ssh") {
                OutlinedTextField(keyPath, { keyPath = it }, label = { Text(stringResource(R.string.sync_auth_key_path)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it }, label = { Text(stringResource(R.string.sync_auth_passphrase)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            Text(stringResource(R.string.sync_settings_author), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(authorName, { authorName = it }, label = { Text(stringResource(R.string.sync_settings_author_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(authorEmail, { authorEmail = it }, label = { Text(stringResource(R.string.sync_settings_author_email)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { Switch(checked = rebase, onCheckedChange = { rebase = it }); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.sync_settings_rebase)) }
            OutlinedTextField(message, { message = it }, label = { Text(stringResource(R.string.sync_settings_message)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Pill(stringResource(R.string.sync_settings_remove), { confirmRemove = true })
                Pill(stringResource(R.string.chrome_save), {
                    if (secret.isNotBlank()) coordinator.setSecret(repo, secret)
                    coordinator.save(repo.copy(
                        autoSync = autoSync, syncIntervalMinutes = interval, syncRequireUnmetered = unmetered,
                        authKind = authKind, authUsername = username.trim(), sshKeyPath = keyPath.trim(),
                        authorName = authorName.trim(), authorEmail = authorEmail.trim(), pullRebase = rebase,
                        syncMessage = message.trim().ifBlank { repo.syncMessage },
                    ))
                    onDismiss()
                }, filled = true)
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.sync_settings_remove)) },
            text = { Text(stringResource(R.string.sync_settings_remove_body)) },
            confirmButton = { TextButton(onClick = { confirmRemove = false; coordinator.remove(repo); onDismiss() }) { Text(stringResource(R.string.sync_settings_remove)) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.chrome_keep)) } },
        )
    }
}
