package com.diegonmarcos.cloudagenda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** Test tags of the app chrome, for the layout-tree tests and the shell tester. */
object ChromeTags {
    const val TOP_ROW = "chrome_top_row"
    const val HAMBURGER = "chrome_hamburger"
    const val BACK = "chrome_back"
    const val TITLE = "chrome_title"
    const val AI = "chrome_ai"
    const val PROFILE = "chrome_profile"
    const val PROFILE_MENU = "chrome_profile_menu"
    const val PROFILE_ACCOUNT = "chrome_profile_account"
    const val PROFILE_SETTINGS = "chrome_profile_settings"
    const val DRAWER = "chrome_drawer"
    const val DRAWER_EMPTY = "chrome_drawer_empty"
    const val AI_PANEL = "chrome_ai_panel"
}

/**
 * PAGE-MANAGED DRAWER. The shell owns the hamburger and the drawer sheet; the page decides what is in it. A page
 * calls [PageDrawer] with its content while it is on screen, and the registration goes away with the page, so
 * the next page starts with whatever it supplies (or the empty state) -- the shell never knows what a page's drawer
 * holds. The content gets a `close` callback so a tap inside can dismiss the drawer.
 */
class PageDrawerState {
    private var content by mutableStateOf<(@Composable ColumnScope.(close: () -> Unit) -> Unit)?>(null)
    private var owner: Any? = null

    /** True while some page supplies drawer content. */
    val hasContent: Boolean get() = content != null

    internal fun register(token: Any, body: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
        owner = token
        content = body
    }

    internal fun unregister(token: Any) {
        // A page that replaced this one has registered already; only the current owner may clear.
        if (owner === token) { owner = null; content = null }
    }

    @Composable
    internal fun Render(scope: ColumnScope, close: () -> Unit) {
        val body = content
        if (body == null) {
            Text("This page has no menu.", color = LocalKitPalette.current.textSecondary,
                modifier = Modifier.padding(20.dp).testTag(ChromeTags.DRAWER_EMPTY))
        } else {
            body(scope, close)
        }
    }
}

val LocalPageDrawer = staticCompositionLocalOf { PageDrawerState() }

/** Supplies the hamburger drawer's content for as long as the calling page is composed. */
@Composable
fun PageDrawer(content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val state = LocalPageDrawer.current
    val latest by rememberUpdatedState(content)
    DisposableEffect(state) {
        val token = Any()
        state.register(token) { close -> latest(close) }
        onDispose { state.unregister(token) }
    }
}

/** A circular top-row button: [size] 40dp, filled with the palette's surfaceSelected, the glyph/label inside. */
@Composable
private fun RoundButton(tag: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LocalKitPalette.current
    Box(
        modifier.size(40.dp).clip(CircleShape).background(p.surfaceSelected).testTag(tag)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * The app's top row: a left hamburger (or a back arrow on a sub-screen), the title, and two circular buttons at the
 * right -- AI and Profile. Profile owns its dropdown menu (Account, Settings); AI only reports the tap, the shell
 * draws the panel. The same row on every page.
 */
@Composable
fun TopRow(
    title: String,
    onMenu: () -> Unit,
    onAi: () -> Unit,
    onAccount: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    aiOpen: Boolean = false,
    onBack: (() -> Unit)? = null,
) {
    val p = LocalKitPalette.current
    var menuOpen by androidx.compose.runtime.remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 8.dp).testTag(ChromeTags.TOP_ROW),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.testTag(ChromeTags.BACK)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = p.textPrimary)
            }
        } else {
            IconButton(onClick = onMenu, modifier = Modifier.testTag(ChromeTags.HAMBURGER)) {
                Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = p.textPrimary)
            }
        }
        Text(
            title, color = p.textPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            maxLines = 1, modifier = Modifier.weight(1f).padding(start = 4.dp).testTag(ChromeTags.TITLE),
        )
        RoundButton(ChromeTags.AI, if (aiOpen) "Close AI" else "AI", onAi) {
            Text("AI", color = if (aiOpen) p.accent else p.textPrimary, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Box {
            RoundButton(ChromeTags.PROFILE, "Profile", { menuOpen = true }) {
                Icon(Icons.Filled.Person, contentDescription = null, tint = p.textPrimary)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier.testTag(ChromeTags.PROFILE_MENU)) {
                DropdownMenuItem(
                    text = { Text("Account") }, modifier = Modifier.testTag(ChromeTags.PROFILE_ACCOUNT),
                    onClick = { menuOpen = false; onAccount() },
                )
                DropdownMenuItem(
                    text = { Text("Settings") }, modifier = Modifier.testTag(ChromeTags.PROFILE_SETTINGS),
                    onClick = { menuOpen = false; onSettings() },
                )
            }
        }
    }
}

/** The drawer sheet's frame: the page's content inside a scrolling column on the palette's surface. */
@Composable
fun DrawerSheet(state: PageDrawerState, close: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    Column(modifier.fillMaxWidth().background(p.surface).testTag(ChromeTags.DRAWER)) {
        state.Render(this, close)
    }
}
