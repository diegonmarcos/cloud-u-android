package com.diegonmarcos.clouddrive.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.clouddrive.BuildConfig
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.EngineActivity
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.SharedStore
import com.diegonmarcos.clouddrive.StoreSeedWorker
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
import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import java.io.File
import java.text.DateFormat
import java.util.Date
import com.diegonmarcos.clouddrive.DriveDebugLog
import com.diegonmarcos.clouddrive.configs.DriveAuthApply
import com.diegonmarcos.clouddrive.configs.DriveGitChain
import com.diegonmarcos.cloudlib.auth.AuthDeclaration
import com.diegonmarcos.cloudlib.auth.AuthMission
import com.diegonmarcos.cloudlib.auth.ConfigArtifact
import com.diegonmarcos.cloudlib.auth.SignIn
import com.diegonmarcos.cloudlib.auth.SignInHost
import com.diegonmarcos.cloudlib.auth.SignInResult
import com.diegonmarcos.cloudlib.auth.SignInWays
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
 *  · personal — the credential the #566 vault config import delivers (#629), or the user's
 *               existing SSH key. The account's own listing is fetched with that credential,
 *               with no tap, and split into the declared groups, Public then Private. #641
 *               the fleet's own GitHub device grant is gone. #689 the GitHub card is gh
 *               ITSELF (libs:gh): gh's own sign-in, `gh repo list`, and a clone on the
 *               credential gh holds — no OAuth-App client of the fleet's own, anywhere.
 *
 * Every clone lands in the store's git folder (#606, SharedStore.gitRoot), the same folder
 * and the same registry the first-run seed uses.
 */
@Composable
fun GitReposScreen(coordinator: GitSyncCoordinator, actions: DriveActions, nextRunMinutes: Long?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
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
    // #646 what the DECLARED credential chain is about to do, and afterwards what it did.
    // The declared order is read once, off the declaration, so this page describes the
    // ranking it will actually follow rather than a ranking somebody typed here.
    var chain by remember { mutableStateOf(GitChainState(order = DriveGitChain.declaredOrder())) }
    // #684 THE SECOND LISTING. Two parallel way-cards mean two independent listings: the
    // GitHub card fills [listing] (gh repo list, or the #629 vault token), the Cloud git card
    // fills this one off the Authelia session. Neither card touches the other's.
    var cloudListing by remember { mutableStateOf(GitListing()) }
    // Which way's sign-in is in flight, and the declared fallback a phone without the fleet
    // browser shows for the Cloud sign-in (it SAYS it is the fallback).
    var signingInWay by remember { mutableStateOf("") }
    var pendingWay by remember { mutableStateOf("") }
    var cloudFallback by remember { mutableStateOf(false) }
    // #689 THE GITHUB CARD IS gh: who gh is signed in as, and the one-time code its own
    // `auth login` is waiting on. Neither is a secret; the credential stays in gh's config.
    var ghAuth by remember { mutableStateOf(GhAuth()) }
    // #850 once per entry to the page: the bounded, cached status read, and the auto pull
    // when it is on and the network is unmetered. Recomposition never re-runs this.
    LaunchedEffect(Unit) { coordinator.onPageOpened() }
    val bulk by coordinator.bulk.collectAsState()
    val autoPull by coordinator.autoPullOnOpen.collectAsState()
    val autoPullNote by coordinator.autoPullNote.collectAsState()
    var confirmPullAll by remember { mutableStateOf(false) }

    /**
     * #653 THE FLEET SIGN-IN'S HOST. The browser login's session arrives through
     * [SignInHost.onWebSession] — NOT through SignInResult.bearer, which libs:auth leaves
     * empty for this way — and is kept in [FleetSession], a process-lifetime field with no
     * writer to any store. Nothing here persists a credential, which is what
     * test-drive-configs-sign-in's "the drive host stores no bearer" requires.
     *
     * The artifact a fleet login also returns is NOT applied here: the app's own
     * configs surface owns that, and applying it twice would be a second apply path.
     * Deliberately worded without naming another page — this page states its own
     * state and never sends the owner somewhere else (#639).
     */
    val fleetHost = remember {
        object : SignInHost {
            override fun onSignedIn(result: SignInResult) {
                chain = chain.copy(signedIn = FleetSession.present)
            }
            override fun onWebSession(cookie: String) {
                FleetSession.remember(cookie)
                chain = chain.copy(signedIn = FleetSession.present)
            }
        }
    }

    // #606 the store's git folder — the ONE place a clone lands, resolved from the declaration.
    val root = remember { SharedStore.gitRoot().absolutePath }
    // #683 THE FIRST-RUN SEED'S LAST VERDICT, on the page that owns the store. The tally was
    // persisted for exactly this and then read by nothing: an incomplete pass, or one blocked
    // for want of a credential, was only readable off Download/drive-debug/. What is shown is
    // the report's OWN tally line — the same words the log carries, never new prose and never
    // a credential — and a cleanly complete pass draws nothing.
    val seedTally = remember { StoreSeedWorker.lastReport(ctx).trim().lines().lastOrNull().orEmpty() }
    val clonedByName = repos.associateBy { File(it.path).name }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    // #642 what the last handoff to cloud-terminal-nix resolved to. Never empty after a
    // clone attempt: a handoff that reported nothing is the silent no-op this feature exists
    // to make impossible.
    var handoff by remember { mutableStateOf("") }
    val askRunCommand = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        // #639: state the fact, do not narrate a route through Settings.
        handoff = ctx.getString(if (ok) R.string.git_terminal_granted else R.string.git_terminal_refused)
    }

    /**
     * #642 CLONE IS THE TERMINAL'S JOB. The argv goes to cloud-terminal-nix, which has a real
     * git and the credential for it; this app keeps none. Every outcome is turned into one
     * factual line, including where the clone will land, because the store folder it lands in
     * is the folder Files already lists — so the result is visible without a second step.
     *
     * The declaration's absence is the ONLY case that falls back to the in-process JGit path,
     * and it says so: a build with no terminal block is a misconfiguration, not a mode.
     */
    fun cloneViaTerminal(name: String, url: String): Boolean {
        val dest = SharedStore.repoDir(name)
        val outcome = TerminalGit.run(ctx, page.terminal, Declarations.GIT_OP_CLONE, url, dest)
        handoff = when (outcome) {
            is TerminalGit.Outcome.Sent ->
                ctx.getString(R.string.git_terminal_sent, name, outcome.dest.absolutePath)
            is TerminalGit.Outcome.NotInstalled ->
                ctx.getString(R.string.git_terminal_absent, outcome.pkg)
            is TerminalGit.Outcome.NeedsPermission -> {
                askRunCommand.launch(outcome.permission)
                ctx.getString(R.string.git_terminal_asking)
            }
            is TerminalGit.Outcome.NoService ->
                ctx.getString(R.string.git_terminal_no_service, outcome.action)
            is TerminalGit.Outcome.Refused ->
                ctx.getString(R.string.git_terminal_refused_by, outcome.why)
            is TerminalGit.Outcome.NoSuchOp ->
                ctx.getString(R.string.git_terminal_no_op, outcome.op)
            TerminalGit.Outcome.NotDeclared ->
                ctx.getString(R.string.git_terminal_not_declared)
        }
        return outcome is TerminalGit.Outcome.Sent
    }

    /**
     * Clone [name] through the terminal; only an undeclared handoff falls back to JGit.
     *
     * #669 THE URL IS THE LISTING'S, NEVER RE-TEMPLATED. [listedUrl]/[listedSshUrl] are the
     * clone URLs the server that LISTED the repository declared for it (gitea's items point
     * at the fleet's own git host, GitHub's at github.com). Rebuilding the URL from the
     * page's declared owner and host — what this function used to do for every row — sent a
     * gitea-listed repository to github.com/<declared owner>/<name>: a clone from the WRONG
     * LEG that reads as the right one until the repository is not there. Blank means the row
     * is a DECLARED public repo with no listing item, for which the declared template is the
     * truth; a LISTED item with a blank URL never reaches here (the row says so instead).
     */
    fun clone(name: String, listedUrl: String = "", listedSshUrl: String = "") {
        if (page.terminal != null) {
            cloneViaTerminal(name, listedUrl.ifBlank { page.cloneUrl(name, Declarations.REMOTE_HTTPS) })
            return
        }
        handoff = ctx.getString(R.string.git_terminal_not_declared)
        val ssh = login.ssh && login.sshKeyPath.isNotBlank()
        val mode = if (ssh) Declarations.REMOTE_SSH else Declarations.REMOTE_HTTPS
        coordinator.cloneInto(
            name = name,
            url = (if (ssh) listedSshUrl else listedUrl).ifBlank { page.cloneUrl(name, mode) },
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

    /**
     * #669 A FLEET-LISTED REPOSITORY CLONES ON THE SESSION THAT LISTED IT. The
     * terminal handoff is deliberately NOT taken here: the terminal's git cannot
     * present the app's Authelia session cookie, so a fleet clone through it can
     * only ever bounce off the gate. The IN-PROCESS engine carries the session on
     * the rung's DECLARED header, against the rung's DECLARED clone URL for this
     * item's owner/name (FleetGit.cloneUrl — template and order both data, and both
     * fleet hosts, so the session can never travel to a third party). Every refusal
     * is loud and names its next step; nothing falls through to another host.
     */
    fun cloneViaFleet(rungId: String, gh: GitHubRepos.Repo) {
        if (!FleetSession.present) {
            handoff = ctx.getString(R.string.git_fleet_clone_no_session)
            return
        }
        val url = FleetGit.cloneUrl(rungId, gh.owner, gh.name, gh.cloneUrl)
        if (url.isBlank()) {
            handoff = ctx.getString(R.string.git_clone_url_missing, gh.name)
            return
        }
        coordinator.cloneInto(
            name = gh.name,
            url = url,
            authKind = GitSyncCoordinator.AUTH_SESSION,
            username = "",
            token = "",
        )
    }

    /**
     * #653 THE SAME LISTING, SERVED BY THE FLEET, with no GitHub credential anywhere.
     * Only the fleet session is presented; the proxy's answer is a projection that cannot
     * carry a credential back. Every non-success is reported in words, and the three
     * states are kept APART on purpose — a redirect means the edge answered and says
     * nothing about the service, so it must never read as either working or broken.
     */
    fun fetchFleetListing(rungId: String) {
        cloudListing = cloudListing.copy(loading = true, error = "")
        scope.launch {
            // #669 the listing is fetched from the rung that ANSWERED — gitea and
            // git-proxy-api are both declared under this kind now, and asking the
            // family's first-ranked endpoint after the SECOND one answered would
            // list from a service the chain just measured as unreachable.
            val outcome = withContext(Dispatchers.IO) { FleetGit.repos(FleetSession.cookie, rungId) }
            cloudListing = when (outcome) {
                is FleetGit.Outcome.Listed -> GitListing(loaded = true, repos = outcome.repos, complete = true, rungId = rungId)
                is FleetGit.Outcome.Refused -> GitListing(loaded = true, error = outcome.why)
                is FleetGit.Outcome.Blocked -> GitListing(
                    loaded = true,
                    error = ctx.getString(R.string.git_fleet_blocked, outcome.code),
                )
                is FleetGit.Outcome.Unreachable -> GitListing(loaded = true, error = outcome.why)
            }
        }
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

    /**
     * #646 WALK THE DECLARED CHAIN — fleet first, GitHub second, both from ONE ordered
     * declaration. Nothing about the ranking is decided here: [DriveGitChain] maps
     * `AuthDeclaration.gitChain` straight through, so the order this follows is the order
     * in the JSON.
     *
     * EVERY STEP IS RENDERED, including the ones that were skipped and why — "Cloud fleet
     * unreachable → GitHub signed in" is a sentence the owner can read, not a state he has
     * to infer from a credential appearing. That is the #639/#452 lesson: a chain that
     * degrades silently looks exactly like a chain that works.
     *
     * #653 NOTHING IS TYPED BY THE OWNER ON ANY RUNG. The code display, the verification
     * URI display and the phase callback that fed them are DELETED, along with the device
     * grant behind them: the fleet rung needs no GitHub credential at all, and the gh rung
     * uses one that is already on the phone. So the only things this state ever carries are
     * the declared order, the narrative and the answering provider's label — no token, and
     * now no code either, because there is no longer a code to carry.
     */
    fun startChain() {
        chain = chain.copy(running = true, narrative = "", answeredBy = "")
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                DriveGitChain.resolve(ctx.applicationContext, session = FleetSession.cookie)
            }
            chain = chain.copy(
                running = false,
                narrative = outcome.narrative(),
                answeredBy = outcome.answeredByLabel.orEmpty(),
            )
            val token = outcome.token?.takeIf { it.isNotBlank() }
            when {
                // #653 THE FLEET ANSWERED AND THERE IS NO TOKEN — the whole point. The
                // listing comes from the proxy, which holds the GitHub credential on its
                // own side, so this branch runs on a phone with ZERO GitHub credential.
                outcome.ok && token == null -> fetchFleetListing(outcome.answeredBy.orEmpty())
                token != null -> {
                    login = login.copy(identity = page.owner, token = token, fromVault = false)
                    fetchListing(token)
                }
            }
        }
    }

    // #684 THE HOSTS THE MISSION BROWSER MAY VISIT for the Cloud sign-in: the portal, the
    // config endpoint whose cookie authorises the fleet, and the rung's own git host. Derived
    // from the declaration, never typed — a host list in Kotlin would be a second source of truth.
    fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
    fun cloudPortalUrl(): String = SignIn.byKind(SignIn.Kind.AUTHELIA_WEB)?.portalUrl.orEmpty()
    fun cloudAllowHosts(rungId: String): List<String> = listOf(
        hostOf(cloudPortalUrl()), hostOf(ConfigArtifact.endpoint()),
        hostOf(DriveGitChain.rung(rungId)?.config?.optString("repos_url").orEmpty()),
    ).filter { it.isNotBlank() }.distinct()

    // #684 ONE FACTUAL LINE per mission outcome, in the TerminalGit style: not-declared,
    // not-installed, a signer mismatch, no answering activity, or the browser's own refusal.
    // Everything but a refusal announces the in-app fallback the block then shows.
    fun missionWord(outcome: AuthMission.Outcome): String = when (outcome) {
        AuthMission.Outcome.NotDeclared -> ctx.getString(R.string.git_way_mission_not_declared)
        is AuthMission.Outcome.NotInstalled -> ctx.getString(R.string.git_way_mission_not_installed, outcome.pkg)
        is AuthMission.Outcome.NotGranted -> ctx.getString(R.string.git_way_mission_not_granted, outcome.permission)
        is AuthMission.Outcome.NoActivity -> ctx.getString(R.string.git_way_mission_no_activity, outcome.action)
        is AuthMission.Outcome.Refused -> ctx.getString(R.string.git_way_mission_refused, outcome.why)
        AuthMission.Outcome.Sent -> ""
    }

    // #684 THE CLOUD SIGN-IN COMPLETES: the browser earned an Authelia session cookie; it goes
    // to FleetSession (process memory, never a store — the f45bbbe31 posture) and the listing
    // rides it. The same cookie the existing SignInWays fallback delivers through onWebSession.
    fun completeCloud(cookie: String, rungId: String) {
        FleetSession.remember(cookie)
        chain = chain.copy(signedIn = FleetSession.present)
        signingInWay = ""
        fetchFleetListing(rungId)
    }

    // #684 THE MISSION'S RESULT comes back here, read off the DECLARED result keys. A cookie is
    // the Cloud sign-in; the other three are loud.
    val missionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val contract = AuthDeclaration.browserMission
        if (contract == null) { signingInWay = "" } else when (val cap = AuthMission.read(contract, res.resultCode, res.data)) {
            is AuthMission.Capture.Cookie -> completeCloud(cap.value, pendingWay)
            AuthMission.Capture.Cancelled -> { handoff = ctx.getString(R.string.git_way_mission_cancelled); signingInWay = "" }
            is AuthMission.Capture.Refused -> { handoff = ctx.getString(R.string.git_way_mission_refused, cap.why); signingInWay = "" }
            is AuthMission.Capture.Malformed -> { handoff = ctx.getString(R.string.git_way_mission_malformed, cap.why); signingInWay = "" }
        }
    }

    // #689 THE GITHUB CARD IS gh ITSELF. #705 gh runs in the gh ENGINE (Cloud-Lib-Gh.apk, which
    // the Store installs) and the card reaches it through GhEngine; this app no longer carries the
    // binary, so a gh bump no longer republishes Cloud Drive. Where it points and how much it lists
    // are the github rung's declaration. Every failure is ONE loud line naming the next step.
    val ghEngine = remember { GhEngine(ctx.applicationContext) }
    val ghHost = remember { DriveGitChain.ghHost() }
    val ghLimit = remember { DriveGitChain.ghListLimit() }

    /** Every step of the GitHub card, to logcat AND the on-device log (Download/<debug dir>/). */
    fun ghLog(msg: String) = DriveDebugLog.i(ctx, GhEngine.TAG, msg)

    /** Why the gh leg cannot run on this phone at all, or "" when it can. #705: the engine's
     *  handshake first — missing and too old are two different next steps. */
    fun ghUnavailable(): String = when (val engine = ghEngine.check()) {
        is GhEngine.Check.NotInstalled -> ctx.getString(R.string.git_gh_missing, engine.pkg)
        is GhEngine.Check.TooOld -> ctx.getString(R.string.git_gh_engine_old, engine.pkg, engine.found, engine.needed)
        GhEngine.Check.Ready -> if (ghHost.isBlank() || ghLimit <= 0) ctx.getString(R.string.git_gh_undeclared) else ""
    }

    /** #689 `gh repo list`: the account's own repositories, split below by gh's own isPrivate. */
    fun fetchGhListing() {
        listing = listing.copy(loading = true, error = "")
        scope.launch {
            val r = withContext(Dispatchers.IO) { ghEngine.repoList(ghLimit, GitHubRepos.GH_FIELDS) }
            val repos = if (r.ok) GitHubRepos.parseGh(r.output) else null
            ghLog(if (repos != null) "gh repo list: ${repos.count { !it.private }} public, ${repos.count { it.private }} private" else "gh repo list: no listing (exit ${r.exitCode})")
            listing = when {
                !r.ok -> GitListing(loaded = true, error = ctx.getString(R.string.git_gh_list_failed, r.exitCode, GhEngine.why(r.output)))
                repos == null -> GitListing(loaded = true, error = ctx.getString(R.string.git_gh_list_unreadable))
                else -> GitListing(loaded = true, repos = repos, complete = repos.size < ghLimit, viaGh = true)
            }
        }
    }

    /** #689 who gh is signed in as, and its listing when it is; true when signed in. */
    suspend fun ghCheck(): Boolean {
        val why = ghUnavailable()
        if (why.isNotBlank()) { ghAuth = ghAuth.copy(checked = true, signedIn = false, error = why); return false }
        val r = withContext(Dispatchers.IO) { ghEngine.status(ghHost) }
        val st = GitHubRepos.ghStatus(r.output, ghHost)
        ghAuth = when {
            st.signedIn -> ghAuth.copy(checked = true, signedIn = true, login = st.login, error = "")
            // gh holds a login GitHub did not confirm (offline, revoked): say that, not "signed out".
            st.state.isNotBlank() -> ghAuth.copy(checked = true, signedIn = false, login = "", error = ctx.getString(R.string.git_gh_unconfirmed, st.login, st.state))
            // gh could not answer at all (it would not start, or failed before any JSON): its words, not "signed out".
            !r.ok -> ghAuth.copy(checked = true, signedIn = false, login = "", error = ctx.getString(R.string.git_gh_status_failed, r.exitCode, GhEngine.why(r.output), BuildConfig.GH_ENGINE_PACKAGE))
            else -> ghAuth.copy(checked = true, signedIn = false, login = "")
        }
        ghLog(if (st.signedIn) "gh is signed in as ${st.login}" else "gh is not signed in" + ghAuth.error.let { if (it.isBlank()) "" else ": $it" })
        if (st.signedIn) fetchGhListing()
        return st.signedIn
    }

    /**
     * Open the page gh named in the FLEET'S OWN BROWSER (Cloud Browser, the package the declared
     * browser mission names; it answers VIEW for https). Only when it is not on the phone does
     * any browser get it — and the log says which one did. Nothing at all: say so, loudly.
     */
    fun openGhPage(url: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fleet = AuthDeclaration.browserMission?.pkg.orEmpty()
        runCatching {
            if (fleet.isBlank()) error("no fleet browser is declared")
            ctx.startActivity(Intent(view).setPackage(fleet)); "Cloud Browser ($fleet)"
        }.recoverCatching { why ->
            ghLog("sign-in: Cloud Browser could not open the page (${why.message}); trying any browser")
            ctx.startActivity(view); "the phone's default browser"
        }.onSuccess { ghLog("sign-in: opened $url in $it") }
            .onFailure {
                ghLog("sign-in: nothing on this phone opened $url: ${it.message}")
                ghAuth = ghAuth.copy(error = ctx.getString(R.string.git_gh_no_browser, url))
            }
    }

    /** gh's one-time code onto the clipboard: on arrival, and again from the card's Copy button. */
    fun copyGhCode(code: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText(ctx.getString(R.string.git_gh_code_clip), code))
    }

    /**
     * #689 SIGN IN WITH gh's OWN FLOW: `gh auth login` — gh's device flow against the client id
     * GitHub CLI compiles into its own binary. gh prints a one-time code and the page to enter it
     * at; the card shows the code, copies it and opens that page (only ever on the declared host).
     * gh finishes by itself once the code is approved: exit 0 re-reads who it is signed in as and
     * lists; any other exit is ONE loud line with gh's own last words and the next step.
     */
    fun startGhLogin() {
        val why = ghUnavailable()
        if (why.isNotBlank()) { ghLog("sign-in: cannot start: $why"); ghAuth = ghAuth.copy(checked = true, error = why); return }
        ghLog("sign-in: Sign in with GitHub tapped")
        ghAuth = ghAuth.copy(running = true, code = "", url = "", error = "")
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                ghEngine.login(ghHost) { prompt, page ->
                    val code = prompt.ifBlank { null }
                    val url = GhEngine.pageOnHost(page, ghHost)
                    if (code != null || url != null) scope.launch {
                        if (code != null && ghAuth.code.isBlank()) {
                            ghAuth = ghAuth.copy(code = code)
                            copyGhCode(code)
                        }
                        if (url != null && ghAuth.url.isBlank()) { ghAuth = ghAuth.copy(url = url); openGhPage(url) }
                    }
                }
            }
            ghAuth = ghAuth.copy(running = false, code = "", url = "")
            if (r.ok) ghCheck()
            else ghAuth = ghAuth.copy(error = ctx.getString(R.string.git_gh_login_failed, GhEngine.why(r.output)))
        }
    }

    /**
     * #689 A ROW gh LISTED CLONES ON THE CREDENTIAL gh HOLDS, in-process, into the ONE store's git
     * folder (#676) with the listing's own https URL as origin — so the scheduler's later fetch and
     * push dial the same URL with the same credential. Not the terminal: neither terminal declares
     * a git credential or carries gh, so its git could not clone a private row. The credential goes
     * from gh's git-credential helper straight into the engine's credential store; nothing shows it.
     */
    fun cloneViaGh(repo: GitHubRepos.Repo) {
        if (repo.cloneUrl.isBlank()) { handoff = ctx.getString(R.string.git_clone_url_missing, repo.name); return }
        scope.launch {
            val cred = withContext(Dispatchers.IO) { if (ghHost.isBlank()) null else ghEngine.credential(ghHost) }
            if (cred == null) { handoff = ctx.getString(R.string.git_gh_clone_no_credential, repo.name); return@launch }
            ghLog("clone ${repo.name}: on gh's credential, into the shared store")
            coordinator.cloneInto(
                name = repo.name,
                url = repo.cloneUrl,
                authKind = GitSyncCoordinator.AUTH_HTTPS,
                username = cred.username,
                token = cred.secret,
            )
        }
    }

    // #684 START ONE WAY'S SIGN-IN, dispatched on the rung's KIND. A fleet_proxy rung opens the
    // portal for a cookie through the fleet browser (AuthMission); when the browser is not on the
    // phone the declared in-app fallback is shown and the handoff line SAYS it is the fallback.
    // #689 the github rung is gh's own `auth login`.
    fun startWay(way: Declarations.GitWayDecl) {
        when (DriveGitChain.rung(way.rung)?.kind) {
            DriveGitChain.RUNG_GITHUB -> startGhLogin()
            DriveGitChain.RUNG_FLEET -> {
                signingInWay = way.id
                pendingWay = way.rung
                cloudFallback = false
                val contract = AuthDeclaration.browserMission
                val req = AuthMission.Request(
                    url = cloudPortalUrl(), title = way.label, allowHosts = cloudAllowHosts(way.rung),
                    capture = AuthMission.CAPTURE_COOKIE, cookieUrl = ConfigArtifact.endpoint(),
                )
                val (intent, outcome) = AuthMission.plan(ctx, contract, req)
                if (intent != null) missionLauncher.launch(intent)
                else { handoff = missionWord(outcome); cloudFallback = true; signingInWay = "" }
            }
        }
    }

    // #629 THE VAULT IS THE ONLY ROAD (#641). The credential the #566 config import delivers already
    // has `repo` scope — it is the token that pushes all day — so the personal section authenticates
    // from it and the account's own repositories list themselves with no tap and no browser. There is
    // no browser login beside it: the device grant that used to be here ran against a GitHub App,
    // whose Device Flow the provider ships OFF, so it could never start.
    LaunchedEffect(Unit) {
        // #689 gh first: when gh itself is signed in, the GitHub card is gh's and lists through gh.
        if (ghCheck()) return@LaunchedEffect
        val vault = withContext(Dispatchers.IO) { DriveAuthApply.vaultGitToken(ctx.applicationContext) }
        if (vault.isNotBlank() && login.token.isBlank()) {
            login = login.copy(identity = page.owner, token = vault, fromVault = true)
            fetchListing(vault)
        }
    }

    LazyColumn(modifier.fillMaxSize()) {
        // #642 WHAT THE HANDOFF DID, at the top of the page, above every row that could have
        // triggered it. Shown for every outcome including success (which names the folder the
        // clone lands in, the one Files already lists) so no press of Clone is ever silent.
        if (handoff.isNotBlank()) item {
            Text(
                handoff,
                Modifier.fillMaxWidth()
                    .testTag(DriveTags.SYNC_GIT_HANDOFF)
                    .padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (seedTally.isNotBlank() && !seedTally.endsWith("complete")) item {
            Text(
                seedTally,
                Modifier.fillMaxWidth()
                    .testTag(DriveTags.SYNC_GIT_SEED)
                    .padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_HERO).padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.pad), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Commit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(DriveMetrics.pad))
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
        // #850 THE PAGE'S PULL ACTIONS: force pull every clone (asks first — it is the
        // declared destructive verb), and the persisted Auto pull on open (Wi-Fi only).
        item {
            Column(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_GIT_PULL_ALL).padding(horizontal = DriveMetrics.sectionInset)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(stringResource(R.string.git_force_pull_all), { confirmPullAll = true }, icon = Icons.Filled.Sync, enabled = bulk?.finished != false && repos.isNotEmpty())
                    Spacer(Modifier.width(DriveMetrics.pad))
                    Pill(stringResource(R.string.git_pull_all), { coordinator.pullAll(force = false) }, enabled = bulk?.finished != false && repos.isNotEmpty())
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.git_auto_pull_on_open), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(DriveMetrics.gap))
                    Switch(checked = autoPull, onCheckedChange = { coordinator.setAutoPullOnOpen(it) }, modifier = Modifier.testTag(DriveTags.SYNC_GIT_AUTO_PULL))
                }
                if (page.autoPull.requireUnmetered) Text(stringResource(R.string.git_auto_pull_wifi_only), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (autoPullNote) {
                    GitSyncCoordinator.AUTO_PULL_METERED -> Text(stringResource(R.string.git_auto_pull_skipped_metered), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GitSyncCoordinator.AUTO_PULL_BUSY -> Text(stringResource(R.string.git_auto_pull_skipped_busy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                bulk?.let { b ->
                    if (!b.finished) {
                        val total = if (b.total == Int.MAX_VALUE) 0 else b.total
                        Text(stringResource(R.string.git_pull_all_progress, b.done, total), style = MaterialTheme.typography.labelSmall)
                        LinearProgressIndicator(progress = { if (total == 0) 0f else b.done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    } else {
                        Text(stringResource(R.string.git_pull_all_done, b.total - b.failed, b.failed), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                    b.results.toSortedMap().forEach { (name, r) ->
                        Text(
                            "$name: " + r.summary,
                            style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (r.ok) MaterialTheme.colorScheme.onSurfaceVariant else colorResource(R.color.status_light_off),
                        )
                    }
                }
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
                    // #684 TWO PARALLEL, ALWAYS-VISIBLE WAY-CARDS — GitHub and Cloud git — in
                    // declared order (ui.sync.git.ways). Each states its OWN status, hosts its OWN
                    // sign-in and lists its OWN repositories below it; neither gates or hides the
                    // other, and both can be signed in and listing at once. A way whose declared
                    // rung is missing renders one line saying so, never a placeholder card (#648).
                    item { SectionHeader(section.label) }
                    page.ways.forEach { way ->
                        val rungKind = DriveGitChain.rung(way.rung)?.kind
                        val isCloud = rungKind == DriveGitChain.RUNG_FLEET
                        val wayListing = if (isCloud) cloudListing else listing
                        item {
                            GitWayCard(
                                way = way, isCloud = isCloud, page = page,
                                login = login, listing = wayListing, chain = chain,
                                ghAuth = ghAuth,
                                signingIn = if (isCloud) signingInWay == way.id else ghAuth.running,
                                rungDeclared = rungKind != null,
                                cloudFallback = cloudFallback && isCloud,
                                fleetHost = fleetHost,
                                onSignIn = { startWay(way) },
                                onOpenGhPage = { openGhPage(it) },
                                onCopyGhCode = { copyGhCode(it) },
                                onRetry = {
                                    if (isCloud) { if (FleetSession.present) fetchFleetListing(way.rung) }
                                    else if (ghAuth.signedIn) fetchGhListing()
                                    else if (login.token.isNotBlank()) fetchListing(login.token)
                                },
                                onStartChain = { startChain() },
                                onSshKeyPath = { login = login.copy(sshKeyPath = it) },
                                onUseSsh = { login = login.copy(ssh = it) },
                            )
                        }
                        if (wayListing.loaded && wayListing.error.isBlank()) {
                            page.personalGroups.forEach { group ->
                                val inGroup = GitHubRepos.group(wayListing.repos, group.id == Declarations.GIT_GROUP_PRIVATE)
                                item { SectionHeader(group.label, count = inGroup.size) }
                                items(inGroup, key = { "own-" + way.id + "-" + it.owner + "/" + it.name }) { gh ->
                                    // #684 the row clones from the LEG THAT LISTED IT (#669's rule,
                                    // now per way): the local `listing` is THIS card's listing, so a
                                    // Cloud-git row rides the fleet session in-process and a GitHub row
                                    // clones its listing's own URL. Falling through to the page's github
                                    // template here would clone the wrong leg silently.
                                    val listing = wayListing
                                    val cloned = clonedByName[gh.name]
                                    val key = "own-" + way.id + "-" + gh.name
                                    GitRepoRow(
                                        name = gh.name, repoName = gh.name, isPrivate = gh.private, fork = gh.fork,
                                        repo = cloned, glance = cloned?.let { glances[it.id] }, running = cloned?.let { running[it.id] },
                                        cloning = gh.name in cloning, expanded = expanded == key,
                                        onToggle = { expanded = if (expanded == key) "" else key },
                                        onClone = {
                                            if (listing.rungId.isNotBlank()) cloneViaFleet(listing.rungId, gh)
                                            else if (listing.viaGh) cloneViaGh(gh)
                                            else if (gh.cloneUrl.isBlank()) handoff = ctx.getString(R.string.git_clone_url_missing, gh.name)
                                            else clone(gh.name, gh.cloneUrl, gh.sshUrl)
                                        },
                                    )
                                    // #684 a private GitHub row listed with NO on-phone credential
                                    // (the fleet-proxy leg) cannot clone: it says which credential it
                                    // needs, never a dead button.
                                    if (gh.private && cloned == null && gh.cloneUrl.isBlank() && listing.rungId.isBlank())
                                        Text(
                                            stringResource(R.string.git_way_private_needs_credential, gh.name),
                                            Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap),
                                            style = MaterialTheme.typography.labelSmall, color = colorResource(R.color.status_light_off),
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
            if (events.isEmpty()) item { Text(stringResource(R.string.sync_history_empty), Modifier.padding(horizontal = DriveMetrics.sectionInset), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(events.take(50), key = { "${it.epochSeconds}-${it.repoId}" }) { e -> HistoryRow(e, fmt) }
        }
    }

    settingsFor?.let { repo -> RepoSettingsSheet(repo, coordinator, onDismiss = { settingsFor = null }) }
    if (confirmPullAll) {
        AlertDialog(
            onDismissRequest = { confirmPullAll = false },
            title = { Text(stringResource(R.string.git_force_pull_all)) },
            text = { Text(stringResource(R.string.git_confirm_force_pull_all, repos.size)) },
            confirmButton = { TextButton(onClick = { coordinator.pullAll(force = true); confirmPullAll = false }) { Text(stringResource(R.string.git_force_pull_all)) } },
            dismissButton = { TextButton(onClick = { confirmPullAll = false }) { Text(stringResource(R.string.chrome_cancel)) } },
        )
    }
}

/**
 * #689 what gh ITSELF says about this phone: whether it has been asked yet, who it is signed in
 * as, the one-time code its own `auth login` waits on (and the page to enter it at), and the ONE
 * loud line when the gh leg failed. No credential lives here — gh keeps its own.
 */
private data class GhAuth(
    val checked: Boolean = false,
    val signedIn: Boolean = false,
    val login: String = "",
    val running: Boolean = false,
    val code: String = "",
    val url: String = "",
    val error: String = "",
)

/**
 * What the personal section is authenticated as, for the process only: never stored here, never
 * logged. #629 [fromVault] says the token came from the VAULT-DELIVERED config import (through
 * [DriveAuthApply.vaultGitToken], out of libs:git-sync's own keystore store) rather than from a
 * browser device grant — which is the difference between the supported path and the fallback.
 */
private data class GitLogin(
    val identity: String = "",
    val token: String = "",
    val ssh: Boolean = false,
    val sshKeyPath: String = "",
    val fromVault: Boolean = false,
) {
    val signedIn: Boolean get() = token.isNotBlank() || (ssh && sshKeyPath.isNotBlank())
}

/**
 * #646 THE DECLARED CREDENTIAL CHAIN, as the page sees it. [order] is built from the
 * declaration so the page states the ranking it will actually follow; [narrative] is the
 * chain's account of what happened, every skipped rung included.
 *
 * NO TOKEN LIVES HERE, AND #653 NO CODE EITHER. This state used to carry a `code` and a
 * `prompt` — the device grant's short user code and the URL to type it at. Both fields are
 * deleted with the flow that filled them: there is nothing for the owner to read off this
 * screen and retype anywhere. What is left describes the chain, never a secret.
 */
private data class GitChainState(
    val running: Boolean = false,
    val order: String = "",
    val narrative: String = "",
    val answeredBy: String = "",
    /** #653 a fleet browser login has landed in THIS process (memory only, never stored). */
    val signedIn: Boolean = false,
)

/**
 * The authenticated listing, or why there is none. #669 [rungId] is the declared id of
 * the FLEET rung that served it — blank when the listing came from GitHub with the vault
 * token. It is what routes a row's clone back to the leg that listed it: a fleet-listed
 * repository clones in-process with the fleet session on the rung's declared clone URL,
 * because the terminal's git cannot present an Authelia session cookie.
 */
private data class GitListing(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val repos: List<GitHubRepos.Repo> = emptyList(),
    val complete: Boolean = true,
    val error: String = "",
    val rungId: String = "",
    /** #689 gh listed it, so a row clones on the credential gh holds (cloneViaGh). */
    val viaGh: Boolean = false,
)

/**
 * #684 ONE OF THE TWO PARALLEL WAY-CARDS. GitHub and Cloud git each render this: a prominent
 * DriveCard with a big "Sign in with <way>" button, a status light and a one-line state, its own
 * repository listing state below, and the chain narrative moved BEHIND a details disclosure. The
 * card dispatches on [isCloud] (the way's rung kind), never on a name typed here.
 *
 * WHAT #641 STILL FORBIDS holds: no libs:auth GitHub provider and no fleet-owned device grant.
 * #689 the GitHub card's sign-in is gh's OWN `auth login` (gh's flow, gh's client id; the card
 * shows gh's one-time code and opens the page gh names); the Cloud card's is the fleet browser
 * mission, whose in-app fallback is libs:auth's own SignInWays scoped to the DECLARED
 * session_provider — the one libs:auth sign-in surface this page may host.
 */
@Composable
private fun GitWayCard(
    way: Declarations.GitWayDecl,
    isCloud: Boolean,
    page: Declarations.GitPageDecl,
    login: GitLogin,
    listing: GitListing,
    chain: GitChainState,
    ghAuth: GhAuth,
    signingIn: Boolean,
    rungDeclared: Boolean,
    cloudFallback: Boolean,
    fleetHost: SignInHost,
    onSignIn: () -> Unit,
    onOpenGhPage: (String) -> Unit,
    onCopyGhCode: (String) -> Unit,
    onRetry: () -> Unit,
    onStartChain: () -> Unit,
    onSshKeyPath: (String) -> Unit,
    onUseSsh: (Boolean) -> Unit,
) {
    var detailsOpen by remember { mutableStateOf(false) }
    // #689 the GitHub card is signed in when gh itself is, or when the #629 vault credential is here.
    val signedIn = if (isCloud) chain.signedIn else ghAuth.signedIn || login.token.isNotBlank()
    val identity = if (isCloud) stringResource(R.string.git_way_cloud_identity) else ghAuth.login.ifBlank { login.identity.ifBlank { page.owner } }
    // Only an undeclared rung disables the button: the GitHub way has no client of its own to lack.
    val disabled = !rungDeclared
    DriveCard(
        way.label,
        light = if (signedIn) StatusLight.State.ON else StatusLight.State.UNKNOWN,
        summary = when {
            signingIn -> stringResource(if (isCloud) R.string.git_way_signing_in else R.string.git_gh_waiting)
            // #689 gh's own sign-in speaks first on the GitHub card: it IS the GitHub leg.
            !isCloud && ghAuth.signedIn && listing.loaded -> stringResource(R.string.git_way_repos, identity, listing.repos.size)
            !isCloud && ghAuth.signedIn -> stringResource(R.string.git_way_signed_in, identity)
            // #629 the vault-delivered credential state, read at a glance (kept on the GitHub card).
            !isCloud && login.fromVault -> stringResource(R.string.git_login_from_vault, login.identity)
            signedIn && listing.loaded -> stringResource(R.string.git_way_repos, identity, listing.repos.size)
            signedIn -> stringResource(R.string.git_way_signed_in, identity)
            !isCloud && ghAuth.checked -> stringResource(R.string.git_gh_not_signed_in)
            else -> stringResource(R.string.git_way_signed_out)
        },
        tag = DriveTags.SYNC_GIT_WAY,
    ) {
        PillRow {
            Pill(
                stringResource(R.string.git_way_signin, way.label),
                onSignIn,
                filled = true,
                enabled = !signingIn && !disabled,
                modifier = Modifier.testTag(DriveTags.SYNC_GIT_WAY_SIGNIN),
            )
            Pill(stringResource(if (detailsOpen) R.string.git_way_details_hide else R.string.git_way_details), { detailsOpen = !detailsOpen })
        }
        // The ONE bold line that names the next step when the way cannot start.
        if (!rungDeclared) Text(
            stringResource(R.string.git_way_rung_undeclared, way.rung),
            Modifier.padding(top = DriveMetrics.gap).testTag(DriveTags.SYNC_GIT_WAY_STATE),
            style = MaterialTheme.typography.bodySmall, color = colorResource(R.color.status_light_off), fontWeight = FontWeight.SemiBold,
        )
        // #689 a failed gh leg says so, in gh's own words where it has them, with the next step.
        else if (!isCloud && ghAuth.error.isNotBlank()) Text(
            ghAuth.error,
            Modifier.padding(top = DriveMetrics.gap).testTag(DriveTags.SYNC_GIT_WAY_STATE),
            style = MaterialTheme.typography.bodySmall, color = colorResource(R.color.status_light_off), fontWeight = FontWeight.SemiBold,
        )
        // #689 gh's one-time code while its own `auth login` waits on it: copied already, and the
        // page gh named is one tap away (it was opened once, automatically).
        if (!isCloud && ghAuth.code.isNotBlank()) {
            Text(
                stringResource(R.string.git_gh_code, ghAuth.code),
                Modifier.padding(top = DriveMetrics.gap).testTag(DriveTags.SYNC_GIT_GH_CODE),
                style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
            )
            PillRow {
                Pill(stringResource(R.string.git_gh_copy), { onCopyGhCode(ghAuth.code) }, modifier = Modifier.testTag(DriveTags.SYNC_GIT_GH_COPY))
                if (ghAuth.url.isNotBlank()) Pill(stringResource(R.string.git_gh_open, ghAuth.url), { onOpenGhPage(ghAuth.url) })
            }
        }

        if (detailsOpen) Column(Modifier.padding(top = DriveMetrics.gap).testTag(DriveTags.SYNC_GIT_WAY_DETAILS)) {
            if (isCloud) {
                // The declared credential chain, described BEHIND the disclosure (#684): the order,
                // the automatic "Get a credential" walk, and — when the browser is absent — the
                // declared in-app fallback sign-in, scoped to the fleet rung's session_provider.
                Text(stringResource(R.string.git_chain_order, chain.order), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PillRow {
                    Pill(stringResource(if (chain.running) R.string.git_chain_running else R.string.git_chain_start), onStartChain, filled = !chain.running)
                }
                if (cloudFallback) SignInWays(
                    host = fleetHost,
                    policy = listOf(FleetGit.sessionProvider()),
                    modifier = Modifier.padding(top = DriveMetrics.gap).testTag(DriveTags.SYNC_GIT_FLEET_LOGIN),
                    pill = { label, tag, onClick -> Pill(label, onClick, modifier = Modifier.testTag(tag)) },
                )
                if (chain.narrative.isNotBlank()) Text(
                    chain.narrative, Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.bodySmall,
                    color = if (chain.answeredBy.isNotBlank()) MaterialTheme.colorScheme.onSurfaceVariant else colorResource(R.color.status_light_off),
                )
                if (chain.answeredBy.isNotBlank()) Text(
                    stringResource(R.string.git_chain_answered, chain.answeredBy),
                    Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                )
            } else {
                // #629 the GitHub card states which of its two credential states it is in.
                // gh signed in IS a git credential on this phone (the clone asks gh for it), so the
                // card never says "no credential" beside a signed-in gh.
                Text(
                    if (ghAuth.signedIn) stringResource(R.string.git_gh_credential_note, ghAuth.login)
                    else stringResource(if (login.fromVault) R.string.git_login_vault_note else R.string.git_login_vault_absent),
                    Modifier.testTag(DriveTags.SYNC_GIT_VAULT_NOTE),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val ssh = page.sshWay
                if (ssh != null) {
                    PillRow { Pill(ssh.label, { onUseSsh(!login.ssh) }, icon = IconCatalog.vectorOrDefault(ssh.icon), filled = login.ssh) }
                    if (login.ssh) {
                        OutlinedTextField(
                            login.sshKeyPath, onSshKeyPath,
                            label = { Text(stringResource(R.string.sync_auth_key_path)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = DriveMetrics.gapWide),
                        )
                        Text(stringResource(R.string.git_login_ssh_cannot_list), Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        when {
            listing.loading -> Text(stringResource(R.string.git_listing_loading), Modifier.padding(top = DriveMetrics.gapWide), style = MaterialTheme.typography.bodySmall)
            listing.error.isNotBlank() -> {
                Text(stringResource(R.string.git_listing_failed, AuthDeclaration.explain(listing.error)), Modifier.padding(top = DriveMetrics.gapWide), style = MaterialTheme.typography.bodySmall, color = colorResource(R.color.status_light_off))
                PillRow { Pill(stringResource(R.string.chrome_retry), onRetry) }
            }
            listing.loaded && !listing.complete && listing.viaGh -> Text(stringResource(R.string.git_gh_list_truncated, listing.repos.size), Modifier.padding(top = DriveMetrics.gapWide), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listing.loaded && !listing.complete -> Text(stringResource(R.string.git_listing_truncated, page.api.maxPages), Modifier.padding(top = DriveMetrics.gapWide), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listing.loaded && listing.repos.isEmpty() -> Text(stringResource(R.string.git_listing_empty), Modifier.padding(top = DriveMetrics.gapWide), style = MaterialTheme.typography.bodySmall)
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
            .heightIn(min = DriveMetrics.rowHeight)
            .clickable(enabled = repo != null, onClick = onToggle)
            .padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(light, name)
        Spacer(Modifier.width(DriveMetrics.pad))
        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (isPrivate) CapsuleBadge(stringResource(R.string.chrome_private))
        if (fork) CapsuleBadge(stringResource(R.string.git_fork))
        Spacer(Modifier.width(DriveMetrics.pad))
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
    if (running != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.sectionInset))
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
            Modifier.padding(top = DriveMetrics.tight), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (glance != null && glance.conflicts > 0) {
            Row(Modifier.padding(top = DriveMetrics.gap), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = off, modifier = Modifier.padding(horizontal = DriveMetrics.gap))
                Text(stringResource(R.string.sync_conflicts, glance.conflicts), color = off, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            PillRow { Pill(stringResource(R.string.sync_resolve_conflicts, glance.conflicts), { actions.openEngine(EngineActivity.ENGINE_GIT, repo.path) }, filled = true) }
        }
        if (running != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = DriveMetrics.gapWide))
            Row(horizontalArrangement = Arrangement.spacedBy(DriveMetrics.pad)) {
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
            Modifier.fillMaxWidth().testTag(DriveTags.SYNC_GIT_OPS).padding(top = DriveMetrics.pad).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide),
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
        if (readOnly) Text(stringResource(R.string.git_readonly_blocked), Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        result?.let { r ->
            Text(
                r.summary, Modifier.padding(top = DriveMetrics.gapWide), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                color = if (r.ok) MaterialTheme.colorScheme.onSurfaceVariant else off, maxLines = 4, overflow = TextOverflow.Ellipsis,
            )
        }

        // THE PANELS — one open at a time, each a read the declaration named.
        val d = details
        when (panel) {
            "commit" -> {
                OutlinedTextField(message, { message = it }, label = { Text(stringResource(R.string.git_commit_message)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = DriveMetrics.gapWide))
                PillRow { Pill(page.op("commit")?.label.orEmpty(), { coordinator.runOp(repo, "commit", message); panel = "" }, filled = true, enabled = running == null) }
            }
            "metadata" -> Column(Modifier.padding(top = DriveMetrics.gapWide)) {
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
            "stats" -> Column(Modifier.padding(top = DriveMetrics.gapWide)) {
                MetaLine(stringResource(R.string.git_stats_state, if (glance?.changed == 0) stringResource(R.string.sync_clean) else stringResource(R.string.sync_changed, glance?.changed ?: 0), glance?.ahead ?: 0, glance?.behind ?: 0, glance?.conflicts ?: 0))
                if (d != null && !d.loading) MetaLine(stringResource(R.string.git_stats_commits, d.commits.size, d.hooks.size, d.workflows.size))
            }
            "history" -> Column(Modifier.padding(top = DriveMetrics.gapWide)) {
                if (d == null || d.loading) Text(stringResource(R.string.sync_glance_reading), style = MaterialTheme.typography.bodySmall)
                else if (d.commits.isEmpty()) Text(stringResource(R.string.git_history_empty), style = MaterialTheme.typography.bodySmall)
                else d.commits.forEach { c ->
                    MetaLine(stringResource(R.string.git_commit_line, c.shortSha, fmt.format(Date(c.time * 1000)), c.author, c.summary))
                }
            }
            "remote" -> Column(Modifier.padding(top = DriveMetrics.gapWide)) {
                Text(stringResource(R.string.git_remote_switch), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.padding(top = DriveMetrics.gapWide).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) {
                    page.remoteModes.forEach { mode ->
                        val url = mode.urlFor(page.owner, repoName)
                        Pill(mode.label, { coordinator.setRemote(repo, mode, page.owner, repoName) }, icon = IconCatalog.vectorOrDefault(mode.icon), filled = repo.remoteUrl == url)
                    }
                }
                page.remoteModes.forEach { mode -> MetaLine(stringResource(R.string.git_remote_mode_line, mode.label, mode.urlFor(page.owner, repoName))) }
            }
            "hooks" -> Column(Modifier.padding(top = DriveMetrics.gapWide)) {
                if (d == null || d.loading) Text(stringResource(R.string.sync_glance_reading), style = MaterialTheme.typography.bodySmall)
                else if (d.hooks.isEmpty()) Text(stringResource(R.string.git_hooks_none), style = MaterialTheme.typography.bodySmall)
                else d.hooks.forEach { h -> MetaLine(h) }
            }
            "actions" -> Column(Modifier.padding(top = DriveMetrics.gapWide)) {
                if (d == null || d.loading) Text(stringResource(R.string.sync_glance_reading), style = MaterialTheme.typography.bodySmall)
                else if (d.workflows.isEmpty()) Text(stringResource(R.string.git_actions_none), style = MaterialTheme.typography.bodySmall)
                else d.workflows.forEach { w -> MetaLine(w) }
            }
            "size" -> Column(Modifier.padding(top = DriveMetrics.gapWide)) {
                if (d == null || d.loading) Text(stringResource(R.string.sync_glance_reading), style = MaterialTheme.typography.bodySmall)
                else MetaLine(stringResource(R.string.git_size_line, GitRepoScan.humanBytes(d.size.bytes), d.size.files, d.size.folders, GitRepoScan.humanBytes(d.size.gitBytes)))
            }
        }
        Text(
            if (repo.autoSync) stringResource(R.string.sync_auto, if (repo.syncIntervalMinutes > 0) repo.syncIntervalMinutes else BuildConfig.GIT_SYNC_INTERVAL_MINUTES, stringResource(if (repo.syncRequireUnmetered) R.string.sync_network_unmetered else R.string.sync_network_any)) else stringResource(R.string.sync_manual),
            Modifier.padding(top = DriveMetrics.pad), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    Column(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_HISTORY).clickable { open = !open }.padding(horizontal = if (compact) DriveMetrics.none else DriveMetrics.sectionInset, vertical = DriveMetrics.gap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusLightRow(state, e.repoName)
            Spacer(Modifier.width(DriveMetrics.pad))
            Text(fmt.format(Date(e.epochSeconds * 1000)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(DriveMetrics.pad))
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
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = DriveMetrics.padWide).padding(bottom = DriveMetrics.padWide)) {
            Text(repo.name, style = MaterialTheme.typography.titleLarge)
            Text(repo.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.padding(DriveMetrics.gapWide))
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = autoSync, onCheckedChange = { autoSync = it }); Spacer(Modifier.width(DriveMetrics.pad)); Text(stringResource(R.string.sync_settings_auto)) }
            Text(stringResource(R.string.sync_settings_period), Modifier.padding(top = DriveMetrics.pad), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.padding(top = DriveMetrics.gapWide), horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) {
                (listOf(0L) + periods.map { it.toLong() }).forEach { p ->
                    Pill(if (p == 0L) stringResource(R.string.sync_settings_minutes, BuildConfig.GIT_SYNC_INTERVAL_MINUTES) else stringResource(R.string.sync_settings_minutes, p), { interval = p }, filled = interval == p)
                }
            }
            Text(stringResource(R.string.sync_settings_network), Modifier.padding(top = DriveMetrics.pad), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.padding(top = DriveMetrics.gapWide), horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) {
                Pill(stringResource(R.string.sync_network_unmetered), { unmetered = true }, filled = unmetered)
                Pill(stringResource(R.string.sync_network_any), { unmetered = false }, filled = !unmetered)
            }
            Text(stringResource(R.string.sync_settings_auth), Modifier.padding(top = DriveMetrics.padWide), style = MaterialTheme.typography.labelLarge)
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
            Text(stringResource(R.string.sync_settings_author), Modifier.padding(top = DriveMetrics.padWide), style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(authorName, { authorName = it }, label = { Text(stringResource(R.string.sync_settings_author_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(authorEmail, { authorEmail = it }, label = { Text(stringResource(R.string.sync_settings_author_email)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(top = DriveMetrics.pad), verticalAlignment = Alignment.CenterVertically) { Switch(checked = rebase, onCheckedChange = { rebase = it }); Spacer(Modifier.width(DriveMetrics.pad)); Text(stringResource(R.string.sync_settings_rebase)) }
            OutlinedTextField(message, { message = it }, label = { Text(stringResource(R.string.sync_settings_message)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = DriveMetrics.padWide), horizontalArrangement = Arrangement.SpaceBetween) {
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
