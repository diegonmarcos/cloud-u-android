package com.diegonmarcos.cloudme

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.commit
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitFragmentHostState
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.Updater
import kotlinx.coroutines.launch

/** Resolves a tile/link target the way [MainActivity.onTarget] does; provided by the shell to every page. */
val LocalMeTarget = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * Single-activity shell: hamburger drawer, top bar with a Configs gear, a
 * content host and the fleet's bottom-nav island — the SuperApp's shape, at a fraction of its
 * size, because Cloud Me has no launcher, no home index and no modes.
 *
 * Both menus are built here from [Sections] rather than inflated from
 * res/menu. That is the point of the app: the bar, the drawer and the tab
 * strips are one JSON list rendered three ways, so they cannot disagree.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The shell is a Compose screen under
 * CloudKitTheme (libs:ui-kit): a ModalNavigationDrawer over [MeBody] (content above the island),
 * and [SectionScreen] draws the section. Only the Health and Fin library pages are still
 * Fragments, hosted by KitFragmentHost.
 */
class MainActivity : AppCompatActivity() {

    /** Section currently on screen — the back handler's only state. */
    private var currentSection by mutableStateOf<String?>(null)

    /** The page a caller asked for inside [currentSection] (null = the section's first). */
    private var currentPage by mutableStateOf<String?>(null)

    /** Bumped on every open(), so reopening a section starts it afresh, as a new fragment did. */
    private var openSeq by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Health and Fin are re-added by KitFragmentHost once Compose has a container for them.
        super.onCreate(KitFragmentHostState.withoutFragments(savedInstanceState))
        FleetChrome.apply(this)

        if (savedInstanceState == null) {
            Sections.default()?.let { open(it.id) }
            // A caller that knows where it wants to land overrides that. Both
            // land in the same frame, so the default is never drawn.
            handleShortcutIntent(intent)
        } else {
            currentSection = savedInstanceState.getString(STATE_SECTION)
        }

        setContent {
            val palette = KitPalette(
                surface = colorResource(R.color.me_surface),
                surfaceSelected = colorResource(R.color.me_surface_high),
                textPrimary = colorResource(R.color.me_text),
                textSecondary = colorResource(R.color.me_text_dim),
                accent = colorResource(R.color.me_primary),
                hairline = colorResource(R.color.me_outline),
                tileInk = colorResource(R.color.me_on_primary),
            )
            CloudKitTheme(palette) {
                CompositionLocalProvider(LocalMeTarget provides { onTarget(it) }) { Shell() }
            }
        }

        // Self-update from GHCR: the periodic check plus a one-shot shortly
        // after launch, because periodic alone defers the first pass by a full
        // interval on a fresh install.
        Updater.start(this)
        UpdateProgress.setListener { state -> runOnUiThread { handleUpdateState(state) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcutIntent(intent)
    }

    /**
     * The one way INTO a specific page from outside the app.
     *
     * The SuperApp's Dashboard icons for Fin and Health are `extapp:cloud-me`
     * tiles that no longer have a page of their own to open, so they launch us
     * with the destination attached. The extra is `shortcut_action` carrying a
     * target string — the SAME name and the SAME grammar the SuperApp already
     * accepts on its own launcher intent (ShellActivity.handleShortcutIntent),
     * because a second convention for "open at this page" would be one more
     * thing for the two apps to disagree about.
     *
     * Consumed once: the extra is removed so a later resume of the same task
     * does not re-navigate away from wherever the user has since gone.
     */
    private fun handleShortcutIntent(intent: Intent?) {
        val target = intent?.getStringExtra(EXTRA_SHORTCUT_ACTION)?.takeIf { it.isNotBlank() }
        intent?.removeExtra(EXTRA_SHORTCUT_ACTION)
        target?.let { onTarget(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SECTION, currentSection)
    }

    override fun onDestroy() {
        super.onDestroy()
        UpdateProgress.setListener(null)
    }

    // ── the screen ───────────────────────────────────────────────────

    @Composable
    private fun Shell() {
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val home = Sections.default()?.id
        // Back from anywhere else lands on the first bar section before it leaves the app — the
        // same one-step-home rule the SuperApp uses, so Back never exits from deep inside.
        BackHandler(enabled = drawer.isOpen || (currentSection != null && currentSection != home && home != null)) {
            if (drawer.isOpen) scope.launch { drawer.close() } else home?.let { open(it) }
        }
        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = { DrawerSheet { scope.launch { drawer.close() } } },
        ) {
            Column(
                Modifier.fillMaxSize().background(colorResource(R.color.me_bg))
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            ) {
                TopBar { scope.launch { drawer.open() } }
                MeBody(
                    selectedSectionId = currentSection,
                    onOpen = { open(it) },
                    onTarget = { onTarget(it) },
                    modifier = Modifier.weight(1f),
                ) {
                    currentSection?.let { id -> key(id, openSeq) { SectionScreen(id, currentPage) } }
                }
            }
        }
    }

    /** The bar of the screen: the hamburger, the section's title and, when declared, the Configs gear.
     *  The gear is a section like any other; it is only drawn here instead of in the bar because
     *  Configs is the one destination you want reachable from wherever you already are. */
    @Composable
    private fun TopBar(onMenu: () -> Unit) {
        val text = colorResource(R.color.me_text)
        val cfg = Sections.toolbarSection()
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu) {
                Icon(painterResource(R.drawable.ic_hamburger_asymmetric), stringResource(R.string.drawer_open), tint = text)
            }
            Text(
                Sections.byId(currentSection)?.label ?: stringResource(R.string.app_name),
                color = text, fontSize = 20.sp, modifier = Modifier.weight(1f),
            )
            cfg?.let { c ->
                iconRes(c.icon)?.let { icon ->
                    IconButton(onClick = { open(c.id) }) { Icon(painterResource(icon), c.label, tint = text) }
                }
            }
        }
    }

    /** The drawer: the profile header, then every section the bar has no room for. */
    @Composable
    private fun DrawerSheet(close: () -> Unit) {
        val dim = colorResource(R.color.me_text_dim)
        val primary = colorResource(R.color.me_primary)
        ModalDrawerSheet(drawerContainerColor = colorResource(R.color.me_surface)) {
            Column(
                Modifier.fillMaxWidth().background(colorResource(R.color.me_surface_high))
                    .padding(start = 20.dp, top = 36.dp, end = 20.dp, bottom = 20.dp),
            ) {
                Text(
                    BuildConfig.UI_PROFILE_NAME.ifBlank { stringResource(R.string.app_name) },
                    color = colorResource(R.color.me_text), fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(BuildConfig.UI_PROFILE_EMAIL, color = dim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
            for (s in Sections.drawer()) {
                val res = iconRes(s.icon)
                NavigationDrawerItem(
                    label = { Text(s.label) },
                    selected = s.id == currentSection,
                    onClick = { close(); select(s) },
                    icon = { res?.let { Icon(painterResource(it), null) } },
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = Color.Transparent, unselectedContainerColor = Color.Transparent,
                        selectedIconColor = primary, selectedTextColor = primary,
                        unselectedIconColor = dim, unselectedTextColor = dim,
                    ),
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        }
    }

    // ── navigation ───────────────────────────────────────────────────

    /** One tap on a drawer item. A section with a `target` launches
     *  something else and reports "not selected", so the bar keeps its
     *  highlight on the page you are still on and will come back to. */
    private fun select(section: Section?) {
        when {
            section == null -> Unit
            section.target.isNotBlank() -> onTarget(section.target)
            else -> open(section.id)
        }
    }

    /** The single entry point for showing a section. Everything — bar, drawer,
     *  gear, tile targets, restored state — comes through here. */
    fun open(sectionId: String, pageId: String? = null) {
        val section = Sections.byId(sectionId) ?: return
        currentSection = section.id
        currentPage = pageId
        openSeq++
    }

    /**
     * Resolves one tile/link target. Three grammars, matching the SuperApp's:
     * `page:<section>/<page>` inside this app, `extapp:<id>` to another
     * constellation member, anything starting http(s) to the browser.
     */
    fun onTarget(target: String) {
        when {
            target.startsWith("page:") -> {
                val path = target.removePrefix("page:")
                open(path.substringBefore('/'), path.substringAfter('/', "").ifBlank { null })
            }
            target.startsWith("section:") -> open(target.removePrefix("section:"))
            target.startsWith("extapp:") -> ExternalApps.launch(this, target)
            target.startsWith("http") -> runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
            }
            else -> Unit
        }
    }

    // ── self-update overlay ──────────────────────────────────────────

    private fun handleUpdateState(state: UpdateProgress.State) {
        val tag = "update_overlay"
        val frag = supportFragmentManager.findFragmentByTag(tag)
        // An unattended pass, or a manual one the user minimized, draws
        // nothing. Failed states are never suppressed.
        if (UpdateProgress.suppressed(this, state)) {
            frag?.let { supportFragmentManager.commit(allowStateLoss = true) { remove(it) } }
            return
        }
        when (state) {
            is UpdateProgress.State.Idle -> frag?.let { supportFragmentManager.commit { remove(it) } }
            else -> if (frag == null) {
                supportFragmentManager.commit {
                    add(android.R.id.content,
                        com.diegonmarcos.superapp.updater.UpdateOverlayFragment.newInstance(), tag)
                }
            } else {
                (frag as? com.diegonmarcos.superapp.updater.UpdateOverlayFragment)?.applyState(state)
            }
        }
    }

    private fun iconRes(name: String): Int? {
        if (name.isBlank()) return null
        @Suppress("DiscouragedApi")
        val id = resources.getIdentifier(name, "drawable", packageName)
        return if (id == 0) null else id
    }

    private companion object {
        const val STATE_SECTION = "section"

        /** Cross-app "open at this target" extra. The name is the SuperApp's —
         *  it is the sending side and already reads this exact extra on its own
         *  launcher intent, so the constellation has one convention, not two. */
        const val EXTRA_SHORTCUT_ACTION = "shortcut_action"
    }
}
