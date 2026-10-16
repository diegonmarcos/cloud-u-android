package com.diegonmarcos.cloudagenda.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.diegonmarcos.cloudagenda.data.AgentConfig
import com.diegonmarcos.cloudagenda.data.Attachment
import com.diegonmarcos.cloudagenda.data.ThemeMode
import com.diegonmarcos.superapp.ai.AiAgent
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiModel
import com.diegonmarcos.superapp.bottomnav.BottomNavHost
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.PageTabs
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the shell needs from the app around it. MainActivity supplies the real one; the layout-tree tests a fake. */
interface TasksEnv {
    val controller: TasksController
    val themeMode: ThemeMode
    fun saveThemeMode(mode: ThemeMode)
    val host: TasksHost
    /** A link a system picker returned while the editor was open; the page takes it into the draft once. */
    val pickedLink: Attachment?
    fun consumePicked()
    val chat: AiChatState
    val agents: List<AiAgent>
    fun loadModels(): List<AiModel>
    val secret: String
    val secretAvailable: Boolean
    /** Stores the declaration and its secret, and (re)schedules the worker. */
    fun saveAgent(config: AgentConfig, secret: String)
    /** One pass of the agent, blocking; the result is in the declaration's status afterwards. */
    fun runAgent()
    fun openAccount()
}

private val PageMapSaver = listSaver<androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>, String>(
    save = { m -> m.entries.flatMap { listOf(it.key, it.value) } },
    restore = { l -> mutableStateMapOf<String, String>().also { m -> l.chunked(2).forEach { m[it[0]] = it[1] } } },
)

/** The tab of the legacy tasks.html shell each declared page shows (its own tab ids: todo, events, calendar, invites, configs). */
internal fun webTabFor(page: String): String = when (page) {
    Ids.PAGE_EVENTS -> "events"
    Ids.PAGE_CALENDAR -> "calendar"
    Ids.PAGE_INVITES -> "invites"
    Ids.PAGE_FEEDS -> "configs"
    else -> "events"
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** The app: theme, then the shell. */
@Composable
fun TasksApp(env: TasksEnv, nav: NavDecl = Declarations.nav) {
    var mode by remember { mutableStateOf(env.themeMode) }
    TasksTheme(mode) {
        val dark = LocalDarkTheme.current
        val view = LocalView.current
        SideEffect {
            view.context.activity()?.window?.let { WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            } }
        }
        TasksShell(env, nav, mode) { mode = it; env.saveThemeMode(it) }
    }
}

/**
 * The shell: the top row, the page-managed drawer, the page, and the fleet's bottom island. Navigation is the
 * declaration (build.json::ui) read through NavDecl: the island is ui.bottom_nav, a section's pages are its tab strip
 * (PageTabs). Settings is a declared section that is not on the island: Profile > Settings opens it, Back returns.
 */
@Composable
fun TasksShell(env: TasksEnv, nav: NavDecl, themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit) {
    val dark = LocalDarkTheme.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val pageDrawer = remember { PageDrawerState() }

    var section by rememberSaveable { mutableStateOf(nav.default()?.id ?: Ids.TASKS) }
    var returnTo by rememberSaveable { mutableStateOf(nav.default()?.id ?: Ids.TASKS) }
    val pageOf = rememberSaveable(saver = PageMapSaver) { mutableStateMapOf<String, String>() }
    var aiOpen by rememberSaveable { mutableStateOf(false) }

    val current = nav.section(section)
    val pages = current?.pages.orEmpty()
    val page = current?.page(pageOf[section])?.id.orEmpty()
    val onIsland = section in nav.bottomNav
    val title = when (section) {
        Ids.SETTINGS -> "Settings"
        Ids.TASKS -> current?.page(Ids.PAGE_TASKS)?.label ?: "Tasks"
        else -> current?.page(page)?.label ?: current?.label.orEmpty()
    }

    fun go(id: String) { aiOpen = false; section = id }

    CompositionLocalProvider(LocalPageDrawer provides pageDrawer) {
        ModalNavigationDrawer(
            drawerState = drawer, gesturesEnabled = pageDrawer.hasContent,
            drawerContent = { ModalDrawerSheet { DrawerSheet(pageDrawer, close = { scope.launch { drawer.close() } }) } },
        ) {
            BottomNavHost(
                entries = nav.islandEntries { IconCatalog.painter(it) },
                selectedId = if (onIsland) section else null,
                onSelect = { entry -> go(entry.id) },
            ) {
                Column(Modifier.fillMaxSize()) {
                    TopRow(
                        title = title,
                        onMenu = { aiOpen = false; scope.launch { drawer.open() } },
                        onAi = { aiOpen = !aiOpen },
                        onAccount = { env.openAccount() },
                        onSettings = { if (section != Ids.SETTINGS) returnTo = section; go(Ids.SETTINGS) },
                        aiOpen = aiOpen,
                        onBack = if (section == Ids.SETTINGS) ({ go(returnTo) }) else null,
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.fillMaxSize()) {
                            if (pages.size > 1) {
                                PageTabs(
                                    pages = pages, selectedId = page, onSelect = { pageOf[section] = it.id },
                                    underTopChrome = false, surface = nav.stripSurface(section, dark, page),
                                )
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                when (section) {
                                    Ids.TASKS -> TasksScreen(env.controller, env.host, env.pickedLink, onPickedConsumed = env::consumePicked)
                                    Ids.CALENDAR -> {
                                        SectionPagesDrawer(pages, page) { pageOf[section] = it.id }
                                        LegacyWeb(tab = webTabFor(page), Modifier.fillMaxSize())
                                    }
                                    Ids.SETTINGS -> {
                                        SectionPagesDrawer(pages, page) { pageOf[section] = it.id }
                                        SettingsContent(env, page, themeMode, onThemeMode)
                                    }
                                }
                            }
                        }
                        if (aiOpen) AiDropdown(env.chat, Modifier.align(Alignment.TopCenter))
                    }
                }
            }
        }
    }
}

/** The drawer of a section with no content of its own: its pages, so the hamburger always has somewhere to go. */
@Composable
private fun SectionPagesDrawer(pages: List<NavPage>, selected: String, onSelect: (NavPage) -> Unit) {
    PageDrawer { close ->
        val p = LocalKitPalette.current
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 24.dp)) {
            for (pg in pages) {
                Text(
                    pg.label, color = if (pg.id == selected) p.accent else p.textPrimary, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onSelect(pg); close() }.padding(horizontal = 20.dp, vertical = 14.dp)
                        .testTag("drawer_page_" + pg.id),
                )
            }
        }
    }
}

@Composable
private fun SettingsContent(env: TasksEnv, page: String, themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit) {
    val controller = env.controller
    when (page) {
        Ids.PAGE_APPEARANCE -> AppearancePage(themeMode, onThemeMode)
        Ids.PAGE_AGENT -> AgentPageHost(env)
        Ids.PAGE_INBOX -> {
            val target = controller.agent.targetListId.takeIf { controller.snapshot.list(it) != null } ?: controller.selectedListId
            InboxPage(
                controller.proposals,
                onApprove = { controller.decide(it.id, true, target.ifBlank { controller.snapshot.lists.firstOrNull()?.id }) },
                onReject = { controller.decide(it.id, false, null) },
            )
        }
        Ids.PAGE_FEEDS -> LegacyWeb(tab = webTabFor(Ids.PAGE_FEEDS), Modifier.fillMaxSize())
    }
}

@Composable
private fun AgentPageHost(env: TasksEnv) {
    val controller = env.controller
    var models by remember { mutableStateOf(emptyList<AiModel>()) }
    var mailReason by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            models = runCatching { env.loadModels() }.getOrDefault(emptyList())
            mailReason = runCatching { env.host.mail.unavailableReason() }.getOrElse { it.message }
        }
    }
    AgentPage(
        config = controller.agent, snapshot = controller.snapshot, agents = env.agents, models = models, mailReason = mailReason,
        secret = env.secret, secretAvailable = env.secretAvailable, running = running,
        onSave = { cfg, secret -> env.saveAgent(cfg, secret) },
        onRunNow = {
            scope.launch {
                running = true
                withContext(Dispatchers.IO) { runCatching { env.runAgent() } }
                running = false
                controller.refreshAgent()
            }
        },
    )
}
