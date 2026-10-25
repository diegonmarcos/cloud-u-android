package com.diegonmarcos.superapp.navbartop2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.diegonmarcos.superapp.bottomnav.LocalNavModeStore
import com.diegonmarcos.superapp.bottomnav.NavModeStore
import com.diegonmarcos.superapp.bottomnav.NavSection
import com.diegonmarcos.superapp.bottomnav.SecondRowMode
import com.diegonmarcos.superapp.bottomnav.rememberNavModePrefs
import com.diegonmarcos.superapp.themebg.ThemeBackground

/** Test tags of the Configs card. */
public object NavModeConfigTags {
    public const val CARD: String = "navmode_card"
    public fun section(id: String): String = "navmode_section_$id"
    public fun choice(sectionId: String, mode: SecondRowMode): String = "navmode_choice_${sectionId}_${mode.declared}"
    public fun reset(sectionId: String): String = "navmode_reset_$sectionId"
}

/** The names the card shows for each mode. */
public fun navModeLabel(mode: SecondRowMode): String = when (mode) {
    SecondRowMode.OneRow -> "One row"
    SecondRowMode.ManyRows -> "Two rows"
    SecondRowMode.SideMenu -> "Side menu"
}

/**
 * The per-section "how child pages are navigated" choice every app offers in its Configs: for each section with more than one page,
 * the three modes ([SecondRowMode]) and a way back to the declared one. The choice is persisted in [NavModeStore] (the `nav_modes`
 * store of the fleet manifest, so it is part of the app's backup) and the chrome ([NavSectionLayout]) redraws at once.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
public fun NavModeConfigCard(
    sections: List<NavSection>,
    modifier: Modifier = Modifier,
    dark: Boolean = true,
    store: NavModeStore = LocalNavModeStore.current ?: rememberNavModePrefs(),
    /** Sections whose child pages come from the app's own data at run time (Mail's folders and labels): offered whatever the declaration lists. */
    dynamic: Set<String> = emptySet(),
) {
    val palette = ThemeBackground.colors(dark)
    val shape = RoundedCornerShape(12.dp)
    val chip = RoundedCornerShape(10.dp)
    Column(modifier.fillMaxWidth().testTag(NavModeConfigTags.CARD), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Navigation of child pages", color = palette.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        sections.filter { it.allPages().size > 1 || it.id in dynamic }.forEach { s ->
            val declared = s.rows
            val chosen = store.mode(s.id)
            val current = chosen ?: declared
            Column(
                Modifier.fillMaxWidth().testTag(NavModeConfigTags.section(s.id)).clip(shape).background(palette.tabStrip, shape)
                    .border(1.dp, palette.islandBorder, shape).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(s.label, color = palette.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondRowMode.entries.forEach { m ->
                        val on = m == current
                        Text(
                            navModeLabel(m) + if (m == declared) " (default)" else "",
                            Modifier.clip(chip)
                                .background(if (on) palette.tabSelected else palette.island, chip)
                                .clickable(role = Role.RadioButton) { store.setMode(s.id, if (m == declared) null else m) }
                                .semantics { role = Role.RadioButton; selected = on; contentDescription = s.label + " " + navModeLabel(m) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag(NavModeConfigTags.choice(s.id, m)),
                            color = if (on) palette.onTabSelected else palette.onIsland, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
