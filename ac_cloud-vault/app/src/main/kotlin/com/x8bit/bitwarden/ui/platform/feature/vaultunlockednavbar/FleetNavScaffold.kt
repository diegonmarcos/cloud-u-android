package com.x8bit.bitwarden.ui.platform.feature.vaultunlockednavbar

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.x8bit.bitwarden.fleet.SyncSignInNeeded
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.bitwarden.ui.platform.components.navigation.model.NavigationItem
import com.bitwarden.ui.platform.components.scaffold.BitwardenScaffold
import com.bitwarden.ui.platform.components.scaffold.model.ScaffoldNavigationData
import com.diegonmarcos.superapp.bottomnav.BottomNavEntry
import com.diegonmarcos.superapp.bottomnav.BottomNavHost
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.x8bit.bitwarden.BuildConfig
import com.x8bit.bitwarden.ui.platform.feature.vaultunlockednavbar.model.VaultUnlockedNavBarTab

/**
 * #868 FLEET NAV PATCH. Same contract as [BitwardenScaffold], but the bottom bar / navigation rail
 * is the fleet's `libs:bottomnav` island, fed from build.json::ui (UI_BOTTOM_NAV, UI_SECTIONS_B64).
 * The declaration decides which tabs show and in what order; each tab's label, icon and route stay
 * Bitwarden's ([VaultUnlockedNavBarTab]), so the destinations are exactly the upstream ones.
 */
@Composable
fun FleetNavScaffold(
    navigationData: ScaffoldNavigationData,
    contentWindowInsets: WindowInsets,
    content: @Composable () -> Unit,
) {
    val decl = remember {
        NavDecl.fromBuildConfig(
            BuildConfig.UI_SECTIONS_B64,
            BuildConfig.UI_BOTTOM_NAV,
            BuildConfig.UI_DEFAULT_SECTION,
        )
    }
    val byId = navigationData.navigationItems.associateBy { it.fleetId() }
    // A tab the declaration omits is not shown; a tab it does not know cannot be reached.
    val items = decl.bottomNav.mapNotNull { byId[it] }
    val entries = items.map { item ->
        BottomNavEntry(item.fleetId(), stringResource(item.labelRes), painterResource(item.iconRes))
    }
    BottomNavHost(
        entries = entries,
        selectedId = navigationData.selectedNavigationItem?.fleetId(),
        onSelect = { entry -> items.firstOrNull { it.fleetId() == entry.id }?.let(navigationData.onNavigationClick) },
    ) {
        val syncSignInNeeded by SyncSignInNeeded.needed.collectAsState()
        BitwardenScaffold(contentWindowInsets = contentWindowInsets) {
            androidx.compose.foundation.layout.Column {
                // Non-blocking: the vault stays open and usable; only the background sync needs a sign-in.
                if (syncSignInNeeded) {
                    androidx.compose.material3.Text(
                        text = "Sync needs sign-in. Your vault is available offline.",
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                content()
            }
        }
    }
}

private fun NavigationItem.fleetId(): String = when (this) {
    is VaultUnlockedNavBarTab.Vault -> "vault"
    VaultUnlockedNavBarTab.Send -> "send"
    VaultUnlockedNavBarTab.Generator -> "generator"
    is VaultUnlockedNavBarTab.Settings -> "settings"
    else -> ""
}
