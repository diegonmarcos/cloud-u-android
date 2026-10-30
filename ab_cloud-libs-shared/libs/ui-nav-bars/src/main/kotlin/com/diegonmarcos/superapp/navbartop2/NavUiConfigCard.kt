package com.diegonmarcos.superapp.navbartop2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.diegonmarcos.superapp.bottomnav.LocalNavUiDefault
import com.diegonmarcos.superapp.bottomnav.LocalNavUiStore
import com.diegonmarcos.superapp.bottomnav.NavUiDecl
import com.diegonmarcos.superapp.bottomnav.NavUiMode
import com.diegonmarcos.superapp.bottomnav.NavUiStore
import com.diegonmarcos.superapp.bottomnav.rememberNavUiPrefs
import com.diegonmarcos.superapp.themebg.ThemeBackground

/** Test tags of the Configs > UI card. */
public object NavUiConfigTags {
    public const val CARD: String = "navui_card"
    public const val SCROLL_HIDE: String = "navui_scroll_hide"
    public const val DIALOG: String = "navui_dialog"
    public fun mode(mode: NavUiMode): String = "navui_mode_${mode.declared}"
}

/** The names the card shows for each mode. */
public fun navUiModeLabel(mode: NavUiMode): String = when (mode) {
    NavUiMode.Classic -> "Classic"
    NavUiMode.Clean -> "Clean"
}

public const val NAV_UI_TITLE: String = "Navigation"
public const val NAV_UI_SCROLL_HIDE_LABEL: String = "Hide the bars while scrolling down"

/**
 * THE Configs > UI card every app offers (declared once: `nav-shape.json::nav_ui`, listed per app in `build.json::ui.configs_ui`):
 * the nav mode, Classic or Clean, and the scroll-hide toggle. The choice is persisted per app in [NavUiStore] (the `nav_ui` store of the
 * fleet manifest, so it is part of the app's backup) and the chrome redraws at once. The mode an app starts in is its declared default
 * (`build.json::ui.nav_ui`), marked "(default)".
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
public fun NavUiConfigCard(
    modifier: Modifier = Modifier,
    dark: Boolean = true,
    store: NavUiStore = LocalNavUiStore.current ?: rememberNavUiPrefs(),
) {
    val palette = ThemeBackground.colors(dark)
    val shape = RoundedCornerShape(12.dp)
    val chip = RoundedCornerShape(10.dp)
    val declared = LocalNavUiDefault.current
    val mode = store.mode() ?: declared.mode
    val hide = store.scrollHide() ?: declared.scrollHide
    Column(
        modifier.fillMaxWidth().testTag(NavUiConfigTags.CARD).clip(shape).background(palette.tabStrip, shape)
            .border(1.dp, palette.islandBorder, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(NAV_UI_TITLE, color = palette.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NavUiMode.entries.forEach { m ->
                val on = m == mode
                Text(
                    navUiModeLabel(m) + if (m == declared.mode) " (default)" else "",
                    Modifier.clip(chip)
                        .background(if (on) palette.tabSelected else palette.island, chip)
                        .clickable(role = Role.RadioButton) { store.setMode(if (m == declared.mode) null else m) }
                        .semantics { role = Role.RadioButton; selected = on; contentDescription = NAV_UI_TITLE + " " + navUiModeLabel(m) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag(NavUiConfigTags.mode(m)),
                    color = if (on) palette.onTabSelected else palette.onIsland, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(NAV_UI_SCROLL_HIDE_LABEL, Modifier.weight(1f), color = palette.ink, fontSize = 13.sp)
            Switch(
                checked = hide,
                onCheckedChange = { store.setScrollHide(if (it == declared.scrollHide) null else it) },
                modifier = Modifier.testTag(NavUiConfigTags.SCROLL_HIDE).semantics { contentDescription = NAV_UI_SCROLL_HIDE_LABEL },
            )
        }
    }
}

/** The shared first row's and the Clean drawer's "Configs > UI" entry opens this: the card in a dialog. */
@Composable
public fun NavUiConfigDialog(dark: Boolean = true, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp).testTag(NavUiConfigTags.DIALOG), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(NavUiDecl.CONFIGS_UI_LABEL, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                NavUiConfigCard(dark = dark)
            }
        }
    }
}
